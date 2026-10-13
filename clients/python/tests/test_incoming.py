from contextlib import closing
import queue
import socket
import struct
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import radproto as p
from radvpn import Engine, Framed, PeerChannel, PeerWorker, Profile, tcp_candidates, rendezvous_checksum


def offer(cid=123, rid=10, password=b'synthetic-password'):
    return p.u32(p.SERVER_OPERATION, 11) + p.u64(0x020001c1, cid) + p.tlv(0x1235,
        p.u64(0x020001e1, rid) + p.tlv(0x0a0001cd, password))


class IncomingTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.identity = p.Identity(20, '26.0.0.1', 'fixture', b'a' * 16, b'fixture-password', '127.0.0.1')
        self.engine = Engine(self.identity, Profile(self.directory.name), passive=True, force_relay=False)
        self.engine.route_ip = '127.0.0.1'
        self.nid = 'ab' * 16
        self.engine.membership.networks[self.nid] = dict(name='fixture', network_id=self.nid)
        self.peer = dict(rid=10, name='peer', vip='26.0.0.2', server='127.0.0.1', state=1, network_ids=[self.nid])
        self.engine.membership.peers[10] = self.peer

    def test_ipv6_candidate_does_not_abort_ipv4_or_relay_discovery(self):
        addresses = [('2001:db8::10', 1234), ('127.0.0.1', 4567), ('0.0.0.0', 1), ('ff02::1', 1)]
        body = b''.join(p.tlv(0x127d, p.text16(0x030001c2, host) + p.u32(0x010001c3, port)) for host, port in addresses)
        data = p.u32(p.SERVER_OPERATION, 6) + p.u64(0x020001c1, 123) + p.tlv(0x1236, body)
        self.assertEqual(tcp_candidates(data, 123), addresses[:2])

    def test_ipv6_literal_connect(self):
        try:
            listener = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
            listener.bind(('::1', 0))
        except OSError:
            self.skipTest('IPv6 loopback unavailable')
        with closing(listener):
            listener.listen(1)
            framed = Framed.connect('::1', listener.getsockname()[1])
            with closing(framed), closing(listener.accept()[0]) as remote:
                framed.write(b'IPv6')
                self.assertEqual(remote.recv(4), b'IPv6')

    def test_server_disconnect_is_not_reported_as_online(self):
        with self.assertRaises(ConnectionError):
            self.engine.control_data(p.u32(p.SERVER_OPERATION, 16))

    def test_network_selection_preserves_membership_without_joining_again(self):
        replies = []
        self.engine._start_command(dict(op='select_networks', names=['fixture']), replies.append, 1)
        self.assertEqual(self.engine.selected, {'fixture'})
        self.assertEqual(replies[0]['networks'], ['fixture'])
        self.assertIn(self.nid, self.engine.membership.networks)
        self.assertTrue(self.engine.wire.empty())

    def test_duplicate_cid_and_peer_offer_preserve_original_credentials(self):
        self.engine.control_data(offer())
        self.engine.control_data(offer(password=b'other-secret'))
        self.engine.control_data(offer(cid=124))
        self.assertEqual(list(self.engine.pending), [123])
        self.assertEqual(self.engine.pending[123]['password'], b'synthetic-password')

    def test_followup_records_route_to_running_incoming_worker(self):
        worker = SimpleNamespace(incoming=(123, b'password'), state='connecting', offers=queue.Queue(16),
                                 thread=SimpleNamespace(is_alive=lambda: True))
        self.engine.workers[10] = worker
        record = p.u32(p.SERVER_OPERATION, 23) + p.u64(0x020001c1, 123)
        self.engine.control_data(record)
        self.assertEqual(worker.offers.get_nowait(), record)
        self.assertFalse(self.engine.pending)

    def test_offline_member_offer_cannot_start_listener(self):
        self.engine.membership.peers[10]['state'] = 0
        self.engine.control_data(offer())
        with patch('radvpn.PeerWorker') as worker:
            self.engine.schedule()
            worker.assert_not_called()

    def test_simultaneous_dial_yields_to_lower_initiator(self):
        worker = SimpleNamespace(incoming=None, state='connecting', stop=threading.Event(),
                                 thread=SimpleNamespace(is_alive=lambda: True))
        self.engine.workers[10] = worker
        self.engine.control_data(offer())
        self.assertTrue(worker.stop.is_set())
        self.assertIn(123, self.engine.pending)

    def test_established_connection_not_replaced_by_duplicate_offer(self):
        worker = SimpleNamespace(incoming=None, state='connected', stop=threading.Event(),
                                 thread=SimpleNamespace(is_alive=lambda: True))
        self.engine.workers[10] = worker
        self.engine.control_data(offer())
        self.assertFalse(worker.stop.is_set())
        self.assertFalse(self.engine.pending)

    def test_incoming_has_reserved_capacity_during_outgoing_scan(self):
        for rid in range(30, 30 + self.engine._max_connecting):
            self.engine.workers[rid] = SimpleNamespace(state='connecting', thread=SimpleNamespace(is_alive=lambda: True))
        def fake_worker(engine, peer, incoming):
            return SimpleNamespace(incoming=incoming, offers=queue.Queue(16),
                                   thread=SimpleNamespace(start=lambda: None, is_alive=lambda: True))
        with patch('radvpn.PeerWorker', side_effect=fake_worker):
            self.assertFalse(self.engine._spawn(self.peer))
            self.assertTrue(self.engine._spawn(self.peer, (123, b'password')))

    def test_pending_incoming_precedes_explicit_outgoing_request(self):
        self.engine.control_data(offer())
        self.engine.desired[10] = time.monotonic() + 60
        calls = []
        with patch.object(self.engine, '_spawn', side_effect=lambda *args: calls.append(args) or True):
            self.engine.schedule()
        self.assertEqual(calls[0][1], (123, b'synthetic-password'))

    def direct_handshake(self, relay=False, bad_rendezvous=False):
        worker = PeerWorker(self.engine, self.peer, incoming=(123, b'synthetic-password'))
        result, errors = queue.Queue(), []
        relay_listener, relay_thread, relay_accepted = None, None, threading.Event()
        relay_stop = threading.Event()
        if relay:
            relay_listener = socket.socket()
            relay_listener.bind(('127.0.0.1', 0))
            relay_listener.listen(1)
            relay_listener.settimeout(8)
            record = p.u32(p.SERVER_OPERATION, 23) + p.u64(0x020001c1, 123) + p.tlv(0x123f,
                p.text16(0x030001cb, '127.0.0.1') + p.u32(0x010001cc, relay_listener.getsockname()[1]) + p.tlv(0x090001ca, b't' * 256))
            worker.offers.put(record)
            def hold_relay():
                try:
                    sock, _ = relay_listener.accept()
                    with closing(Framed(sock, relay_stop)) as stream:
                        stream.receive(until=time.monotonic() + 8)
                        relay_accepted.set()
                        relay_stop.wait(8)
                except OSError:
                    pass
            relay_thread = threading.Thread(target=hold_relay)
            relay_thread.start()
        def accepting():
            try:
                result.put(worker.accept())
            except Exception as exc:
                errors.append(exc)
        thread = threading.Thread(target=accepting)
        thread.start()
        client_channel = accepted_channel = None
        try:
            advert = self.engine.wire.get(timeout=3)
            endpoint = p.parse_records(p.unique(p.parse_records(p.unique(p.parse_records(advert), 0x1230).value), 0x127d).value)
            port = p.unique(endpoint, 0x010001c3).u32()
            if relay:
                self.assertTrue(relay_accepted.wait(6), 'relay fallback never started')
            if bad_rendezvous:
                with closing(Framed.connect('127.0.0.1', port)) as invalid:
                    invalid.write(b'x' * 24)
            stream = Framed.connect('127.0.0.1', port)
            body = struct.pack('!QQI', 10, 123, 7)
            stream.write(b'\0\x14\0\x01' + body)
            self.assertEqual(stream.read_exact(4, time.monotonic() + 3), struct.pack('!I', rendezvous_checksum(body)))
            identity = p.Identity(10, '26.0.0.2', 'peer', b'b' * 16, b'fixture-password', '127.0.0.1')
            peer = dict(rid=20, vip='26.0.0.1')
            client_channel = PeerChannel(stream, identity, peer, b'synthetic-password', transport='direct-tcp')
            accepted_channel = result.get(timeout=3)
            self.assertEqual(accepted_channel.transport, 'direct-tcp')
            self.assertIs(accepted_channel.stream.stop, worker.stop)
            client_channel.send(b'authenticated request')
            self.assertEqual(accepted_channel.receive(), b'authenticated request')
            accepted_channel.send(b'authenticated response')
            self.assertEqual(client_channel.receive(), b'authenticated response')
            self.assertFalse(errors)
        finally:
            worker.stop.set()
            relay_stop.set()
            if client_channel:
                client_channel.close()
            if accepted_channel:
                accepted_channel.close()
            if relay_listener:
                relay_listener.close()
            thread.join(8)
            if relay_thread:
                relay_thread.join(8)
            worker.tx.close()
        self.assertFalse(thread.is_alive())

    def test_real_incoming_tcp_rendezvous_sh_and_encrypted_service(self):
        self.direct_handshake()

    def test_invalid_rendezvous_does_not_poison_listener(self):
        self.direct_handshake(bad_rendezvous=True)

    def test_delayed_direct_connection_wins_while_relay_pairing_waits(self):
        self.direct_handshake(relay=True)


if __name__ == '__main__':
    unittest.main()
