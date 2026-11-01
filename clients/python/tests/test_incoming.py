from contextlib import closing
from pathlib import Path
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
from radvpn import Engine, Framed, PeerChannel, PeerWorker, Profile, tcp_candidates, rendezvous_checksum, ensure_identity


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

    def test_timed_out_network_command_cannot_attribute_a_late_reply_to_the_next_join(self):
        clock, controls, replies = [1000.0], [], []
        engine, identity = self.engine, self.identity

        def callback(label):
            def complete(reply):
                replies.append((label, reply))
                if label == 'B':
                    engine.stop.set()
            return complete

        for label in ('A', 'B'):
            engine.commands.put((dict(op='join', name='network-' + label,
                password='synthetic-password'), callback(label)))

        class SyntheticControl:
            def __init__(self, generation):
                self.generation, self.sent, self.closed, self.ready_calls = generation, [], False, 0
                self.stream = SimpleNamespace(sock=SimpleNamespace(
                    getsockname=lambda: ('127.0.0.1', 12345)), ready=self.ready)
                self.incoming = [p.u32(p.SERVER_OPERATION, 21),
                    p.u32(p.SERVER_OPERATION, 38) + p.tlv(p.NETWORKS,
                        p.u32(p.IPV4, int(p.IPv4Address(identity.ipv4)))
                        + p.tlv(p.ADDRESS, identity.address))]

            def send(self, packet):
                self.sent.append(packet)

            def ready(self, timeout):
                self.ready_calls += 1
                if self.generation == 1 and self.ready_calls == 1:
                    clock[0] += 36
                if self.ready_calls > 8:
                    raise AssertionError('synthetic control did not terminate')
                return self.generation > 1 or self.ready_calls >= 3

            def receive(self, until):
                if self.incoming:
                    return self.incoming.pop(0)
                if self.generation > 1:
                    # The next attachment fails explicitly; it must not report
                    # the old attachment's refusal as the result of joining B.
                    return p.u32(p.SERVER_OPERATION, 16)
                return p.u32(p.SERVER_OPERATION, 40) + p.tlv(0x131c,
                    p.u64(0x02000303, 1))  # Late A refusal, optional IDs omitted.

            def close(self):
                self.closed = True

        def login(*args, **kwargs):
            control = SyntheticControl(len(controls) + 1)
            controls.append(control)
            return control

        with patch('radvpn.Control.login', side_effect=login), \
                patch('radvpn.time.monotonic', side_effect=lambda: clock[0]), \
                patch.object(engine.stop, 'wait', return_value=False):
            engine.run()

        def joins(control):
            return sum(p.unique(p.parse_records(body), p.CLIENT_OPERATION).u32() == 39
                for body in control.sent)

        self.assertEqual(joins(controls[0]), 1)
        self.assertEqual(len(controls), 2)
        self.assertEqual(joins(controls[1]), 1)
        self.assertTrue(all(control.closed for control in controls))
        self.assertEqual(replies, [('A', dict(ok=False, error='network operation timed out')),
            ('B', dict(ok=False, error='control connection closed'))])

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


class RegistrationIntentTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.profile = SimpleNamespace(path=Path(self.directory.name))
        self.identity = p.Identity(20, '26.0.0.1', 'fixture', b'a' * 16,
                                   b'fixture-password', '127.0.0.1')

    def registration(self, response=None, error=None):
        from unittest.mock import MagicMock
        stream, control = MagicMock(), MagicMock()
        control.receive.side_effect = error
        with patch('radvpn.Framed.connect', return_value=stream), \
                patch('radvpn.p.bootstrap_session', return_value=(17, None)), \
                patch('radvpn.p.SessionCrypto'), patch('radvpn.authenticate'), \
                patch('radvpn.Control', return_value=control), \
                patch('radvpn.p.Provisioning') as receiver:
            receiver.return_value.receive.return_value = (8, response)
            try:
                return ensure_identity(self.profile, 'fixture')
            finally:
                self.control, self.stream = control, stream

    def test_ambiguous_registration_blocks_restart_before_connect(self):
        with self.assertRaises(ConnectionError):
            self.registration(error=ConnectionError('synthetic drop after login'))
        self.control.send.assert_called_once()
        intent = self.profile.path / 'registration.pending'
        self.assertTrue(intent.is_file())
        self.assertEqual(intent.stat().st_mode & 0o777, 0o600)
        with patch('radvpn.Framed.connect') as connect:
            with self.assertRaisesRegex(ValueError, 'registration outcome unknown'):
                ensure_identity(self.profile, 'fixture')
            connect.assert_not_called()

    def test_authentication_failure_before_login_does_not_leave_marker(self):
        with patch('radvpn.Framed.connect') as connect, \
                patch('radvpn.p.bootstrap_session', return_value=(17, None)), \
                patch('radvpn.p.SessionCrypto'), \
                patch('radvpn.authenticate', side_effect=ConnectionError('pre-login')):
            with self.assertRaises(ConnectionError):
                ensure_identity(self.profile, 'fixture')
            connect.return_value.close.assert_called_once()
        self.assertFalse((self.profile.path / 'registration.pending').exists())

    def test_success_persists_identity_and_removes_marker(self):
        identity = self.registration(response=self.identity)
        self.assertEqual(identity, self.identity)
        self.assertFalse((self.profile.path / 'registration.pending').exists())
        self.assertEqual(p.Identity.load(self.profile.path / 'identity.json'), identity)

    def test_issued_identity_storage_failure_leaves_marker(self):
        with patch('radvpn.save_identity', side_effect=OSError('synthetic disk failure')):
            with self.assertRaises(OSError):
                self.registration(response=self.identity)
        self.assertTrue((self.profile.path / 'registration.pending').is_file())

    def test_authenticated_redirect_reuses_bootstrap_and_clears_intent(self):
        from unittest.mock import MagicMock
        stream, control = MagicMock(), MagicMock()
        control.receive.side_effect = [p.ServerRedirect('127.0.0.2'), b'assigned']
        with patch('radvpn.Framed.connect', return_value=stream) as connect, \
                patch('radvpn.p.bootstrap_session', return_value=(17, None)) as bootstrap, \
                patch('radvpn.p.SessionCrypto'), patch('radvpn.authenticate'), \
                patch('radvpn.Control', return_value=control), \
                patch('radvpn.p.Provisioning') as receiver:
            receiver.return_value.receive.return_value = (8, self.identity)
            self.assertEqual(ensure_identity(self.profile, 'fixture'), self.identity)
            self.assertEqual(connect.call_count, 2)
            bootstrap.assert_called_once()
        self.assertFalse((self.profile.path / 'registration.pending').exists())

    def test_pending_symlink_cannot_be_overwritten(self):
        target = self.profile.path / 'unrelated'
        target.write_text('preserve')
        (self.profile.path / 'registration.pending').symlink_to(target)
        with patch('radvpn.Framed.connect') as connect:
            with self.assertRaisesRegex(ValueError, 'registration outcome unknown'):
                ensure_identity(self.profile, 'fixture')
            connect.assert_not_called()
        self.assertEqual(target.read_text(), 'preserve')


class WorkerBindingTests(unittest.TestCase):
    def setUp(self):
        IncomingTests.setUp(self)
        self.worker = SimpleNamespace(peer=dict(self.peer), state='connected',
            stop=threading.Event(), thread=SimpleNamespace(is_alive=lambda: True),
            channel=SimpleNamespace(mac=p.mac_for_ip(self.peer['vip'])), tx=queue.Queue(), last_use=0)
        self.engine.workers[10] = self.worker
        self.delivered = []
        self.engine.on_frame = lambda rid, frame: self.delivered.append((rid, frame))

    def incoming(self, worker=None):
        worker = worker or self.worker
        frame = p.ipv4_frame(worker.channel.mac, p.mac_for_ip(self.identity.ipv4),
            worker.peer['vip'], self.identity.ipv4, b'payload', protocol=253)
        self.engine.incoming_frame(worker, frame)

    def test_changed_vip_or_server_cancels_worker_and_rejects_its_frames(self):
        for field, value in [('vip', '26.0.0.9'), ('server', '127.0.0.2')]:
            with self.subTest(field=field):
                self.worker.stop.clear()
                self.engine.membership.peers[10] = dict(self.peer, **{field: value})
                self.engine.schedule()
                self.assertTrue(self.worker.stop.is_set())
                self.incoming()
                self.assertFalse(self.delivered)

    def test_replaced_generation_and_cancelled_worker_cannot_deliver(self):
        old = self.worker
        self.engine.workers[10] = SimpleNamespace(**dict(vars(old), stop=threading.Event()))
        self.incoming(old)
        self.assertFalse(self.delivered)
        self.engine.workers[10] = old
        old.stop.set()
        self.incoming(old)
        self.assertFalse(self.delivered)

    def test_name_only_change_preserves_current_channel(self):
        self.engine.membership.peers[10] = dict(self.peer, name='renamed')
        self.engine.schedule()
        self.assertFalse(self.worker.stop.is_set())
        self.incoming()
        self.assertEqual(len(self.delivered), 1)

    def test_stale_worker_cannot_receive_queued_outbound_frames(self):
        self.engine.membership.peers[10] = dict(self.peer, vip='26.0.0.9')
        frame = p.ipv4_frame(p.mac_for_ip(self.identity.ipv4), self.worker.channel.mac,
            self.identity.ipv4, self.peer['vip'], b'payload', protocol=253)
        with self.assertRaises(ValueError):
            self.engine.send_ipv4(frame[14:])
        with self.assertRaises(ValueError):
            self.engine.send_ethernet(frame)
        self.assertTrue(self.worker.tx.empty())


if __name__ == '__main__':
    unittest.main()
