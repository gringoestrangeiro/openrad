#!/usr/bin/env python3
"""Offline load test: real SH-authenticated TCP PeerChannels and PeerWorker.run.

The traffic generator is a separate process, excluded from client CPU/RSS.
Only the external control/coordinator service is replaced. All established
peer traffic uses the production framing, crypto, worker, Engine and OpenRad
receive paths. Nothing contacts a Radmin server or opens a TAP.
"""
import argparse
import asyncio
import cProfile
import heapq
import importlib
import json
import multiprocessing
import os
from pathlib import Path
import platform
import pstats
import resource
import select
import socket
import struct
import sys
import tempfile
import threading
import time


ROOT = Path(__file__).resolve().parents[1]
NETWORK = 'ab' * 16
PASSWORD = b'offline-benchmark-channel'
MIX = (42, 60, 500, 1500, 60, 500, 1500, 60, 500, 1500)


def modules(source, stdlib):
    sys.path.insert(0, str(source))
    if stdlib:
        # Also exercises the normal import-time optional-dependency fallback.
        sys.modules['Crypto'] = None
    p = importlib.import_module('radproto')
    v = importlib.import_module('radvpn')
    app = importlib.import_module('openrad')
    return p, v, app


def identity(p, index):
    return p.Identity(1000 + index, str(p.IPv4Address(0x1a010001 + index)),
                      f'benchmark-{index}', bytes([1]) * 16,
                      b'offline-identity', '127.0.0.1')


def peer(ident):
    return dict(rid=ident.rid, vip=ident.ipv4, name=ident.node_name,
                server='127.0.0.1', state=1, network_ids=[NETWORK])


def frames(p, source, target):
    sm, tm = p.mac_for_ip(source.ipv4), p.mac_for_ip(target.ipv4)
    arp = p.arp_frame(sm, source.ipv4, b'\xff' * 6, target.ipv4)
    result = {42: arp}
    for size in (60, 500, 1500):
        ip = p.udp_ipv4(source.ipv4, target.ipv4, 49001, 49002,
                        bytes((i % 251 for i in range(size - 42))))
        result[size] = tm + sm + b'\x08\x00' + ip
        assert len(result[size]) == size
    return tuple(result[size] for size in MIX)


def rss_bytes():
    # VmHWM describes this executable; ru_maxrss can inherit a launcher's peak.
    status = Path('/proc/self/status').read_text().splitlines()
    current = int(next(s.split()[1] for s in status if s.startswith('VmRSS:'))) * 1024
    peak = int(next(s.split()[1] for s in status if s.startswith('VmHWM:'))) * 1024
    return current, peak


def generator(config, pipe):
    p, v, _ = modules(config['source'], config['stdlib'])
    channels, traffic, counts = [], [], dict(sent=0, received=0, arp_replies=0,
        keepalives=0, keepalive_requests=0, keepalive_replies=0)
    own = identity(p, 0)
    error = None
    try:
        for index in range(1, config['peers'] + 1):
            remote = identity(p, index)
            sock = socket.create_connection(config['address'], timeout=30)
            channel = v.PeerChannel(v.Framed(sock), remote, peer(own), PASSWORD, incoming=True)
            channels.append(channel)
            traffic.append(frames(p, remote, own))
        pipe.send(dict(ready=True))
        command = pipe.recv()
        start, end = command['start'], command['end']
        interval = 1 / config['rate'] if config['rate'] else float('inf')
        due = [(start + i * interval / len(channels), i, 0) for i in range(len(channels))]
        heapq.heapify(due)
        sockets = {c.stream.sock: i for i, c in enumerate(channels)}
        heartbeat, heartbeat_sequence = start + 15, 0
        running = True
        while running:
            now = time.monotonic()
            if pipe.poll():
                if pipe.recv() == 'stop':
                    break
            if now >= heartbeat and now < end:
                heartbeat_sequence += 1
                for channel in channels:
                    channel.send(p.keepalive(heartbeat_sequence))
                    counts['keepalive_requests'] += 1
                heartbeat = now + 15
            if config['rate'] and config['direction'] in ('rx', 'duplex') and now < end:
                # Bound bursts so receiving/keepalive replies cannot starve.
                for _ in range(64):
                    if not due or due[0][0] > now:
                        break
                    when, i, sequence = heapq.heappop(due)
                    channels[i].send(p.encode_frame(traffic[i][sequence % len(MIX)]))
                    counts['sent'] += 1
                    heapq.heappush(due, (when + interval, i, sequence + 1))
            wait = min(.005, max(0, due[0][0] - time.monotonic())) if config['rate'] and now < end and config['direction'] != 'tx' else .005
            ready = select.select(list(sockets), [], [], wait)[0]
            # Framed may have already buffered a subsequent complete record.
            ready.extend(s for s, i in sockets.items() if s not in ready and len(channels[i].stream.buffer) >= 4)
            for sock in ready:
                i = sockets[sock]
                channel = channels[i]
                packet = p.decode_packet(channel.receive(time.monotonic() + 1))
                if packet['kind'] == 3:
                    channel.send(p.keepalive(packet['sequence'], reply=True))
                    counts['keepalives'] += 1
                elif packet['kind'] == 7:
                    counts['keepalive_replies'] += 1
                for entry in packet.get('frames', ()):
                    frame = entry['ethernet']
                    counts['received'] += 1
                    remote = identity(p, i + 1)
                    if frame[12:14] == b'\x08\x06' and frame[20:22] == b'\0\x01' and frame[38:42] == p.IPv4Address(remote.ipv4).packed:
                        channel.send(p.encode_frame(p.arp_frame(p.mac_for_ip(remote.ipv4), remote.ipv4,
                            p.mac_for_ip(own.ipv4), own.ipv4, reply=True)))
                        counts['arp_replies'] += 1
        pipe.send(dict(counts=counts, cpu_seconds=time.process_time(), error=None))
        pipe.recv()  # Keep sockets connected until the client has sampled them.
    except Exception as exc:
        error = f'{type(exc).__name__}: {exc}'
        pipe.send(dict(error=error, counts=counts))
    finally:
        for channel in channels:
            channel.close()
        pipe.close()


async def load(args, profiles):
    p, v, app = modules(args.source, args.stdlib)
    loop = asyncio.get_running_loop()
    loop_errors = []
    loop.set_exception_handler(lambda _, context: loop_errors.append(str(context.get('exception', context['message']))))
    ctx = multiprocessing.get_context('spawn')
    parent, child = ctx.Pipe()
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(('127.0.0.1', 0))
    listener.listen(32)
    listener.settimeout(60)
    config = dict(source=str(args.source), peers=args.peers, rate=args.rate,
                  stdlib=args.stdlib, address=listener.getsockname(), direction=args.direction)
    process = ctx.Process(target=generator, args=(config, child), name='benchmark-generator')
    workers, consumers, channel = [], [], None
    with tempfile.TemporaryDirectory(prefix='openrad-benchmark-') as directory:
        ident = identity(p, 0)
        engine = v.Engine(ident, v.Profile(directory), networks=['benchmark'], max_peers=args.peers, passive=True)
        engine.state = 'online'
        engine.membership.networks[NETWORK] = dict(name='benchmark', network_id=NETWORK)
        # Engine already accepted N in the baseline; its public wrapper capped
        # N at 64. Using min here allows a directly comparable old-source run.
        try:
            client = app.OpenRad(directory, max_peers=args.peers, receive_queue=1024)
        except ValueError:
            if args.peers <= 64:
                raise
            client = app.OpenRad(directory, max_peers=64, receive_queue=1024)
        client._loop, client._engine = loop, engine
        client._default_channel = client.subscribe('ethernet')
        engine.add_frame_listener(client._incoming)
        delivered = 0

        async def consume(subscription):
            nonlocal delivered
            try:
                async for packet in subscription:
                    delivered += 1
            except app.ChannelClosed:
                pass

        if args.subscribers:
            channel = client.subscribe(args.subscribers, capacity=4096)
            consumers.append(asyncio.create_task(consume(channel)))
        process.start()
        child.close()
        setup_start = time.monotonic()
        try:
            for i in range(1, args.peers + 1):
                sock, _ = listener.accept()
                remote = identity(p, i)
                channel = v.PeerChannel(v.Framed(sock), ident, peer(remote), PASSWORD)
                worker = v.PeerWorker(engine, peer(remote))
                # Replace coordinator discovery only; worker.run remains real.
                worker.outgoing = lambda c=channel: (c, None)
                # Python 3.14 allows one active cProfile monitor per process.
                # Profile a representative real worker, or the client loop.
                if args.profile and args.profile_scope == 'peer' and i == 1:
                    profile = cProfile.Profile()
                    profiles.append(profile)
                    worker.thread = threading.Thread(target=profile.runcall, args=(worker.run,), daemon=True)
                with engine.lock:
                    engine.membership.peers[remote.rid] = peer(remote)
                    engine.workers[remote.rid] = worker
                workers.append(worker)
                worker.thread.start()
                if i % 100 == 0:
                    print(f'Authenticated {i}/{args.peers} peers', file=sys.stderr, flush=True)
            listener.close()
            if not parent.poll(30):
                raise RuntimeError('generator setup timed out')
            status = parent.recv()
            if status.get('error') or not status.get('ready'):
                raise RuntimeError(status)
            await asyncio.sleep(.5)
            setup_seconds = time.monotonic() - setup_start
            outgoing = [frames(p, ident, identity(p, i)) for i in range(1, args.peers + 1)]
            start = time.monotonic() + .1
            end = start + args.seconds
            before = dict(engine.counters)
            parent.send(dict(start=start, end=end))
            await asyncio.sleep(max(0, start - time.monotonic()))
            cpu_start = time.process_time()
            switches_start = resource.getrusage(resource.RUSAGE_SELF)
            interval = 1 / args.rate if args.rate else float('inf')
            due = [(start + i * interval / args.peers, i, 0) for i in range(args.peers)]
            heapq.heapify(due)
            next_schedule = start
            attempted = queued = drops = 0
            rss_samples, healthy = [], args.peers
            while time.monotonic() < end:
                now = time.monotonic()
                if args.direction in ('tx', 'duplex') and args.rate:
                    for _ in range(64):
                        if due[0][0] > now:
                            break
                        when, i, sequence = heapq.heappop(due)
                        attempted += 1
                        try:
                            queued += engine.send_ethernet(outgoing[i][sequence % len(MIX)], 1001 + i)['queued']
                        except ValueError:
                            drops += 1
                        heapq.heappush(due, (when + interval, i, sequence + 1))
                if now >= next_schedule:
                    engine.schedule()
                    rss_samples.append(rss_bytes()[0])
                    healthy = min(healthy, sum(w.state == 'connected' for w in workers))
                    next_schedule = now + .05
                wake = min(end, next_schedule, due[0][0] if args.direction != 'rx' and args.rate else end)
                await asyncio.sleep(max(0, min(.005, wake - time.monotonic())))
            cpu_seconds = time.process_time() - cpu_start
            wall_seconds = time.monotonic() - start
            switches = resource.getrusage(resource.RUSAGE_SELF)
            at_end = dict(engine.counters)
            # Allow all accepted records to drain before accounting for losses.
            drain_start = time.monotonic()
            await asyncio.sleep(args.drain)
            parent.send('stop')
            deadline = time.monotonic() + 10
            while not parent.poll() and time.monotonic() < deadline:
                await asyncio.sleep(.01)
            remote = parent.recv() if parent.poll() else dict(error='generator stop timed out')
            after = dict(engine.counters)
            current_rss, peak_rss = rss_bytes()
            rx = after.get('peer_rx', 0) - before.get('peer_rx', 0)
            tx = after.get('peer_tx', 0) - before.get('peer_tx', 0)
            steady_rx = at_end.get('peer_rx', 0) - before.get('peer_rx', 0)
            steady_tx = at_end.get('peer_tx', 0) - before.get('peer_tx', 0)
            expected_rx = remote.get('counts', {}).get('sent', 0) + remote.get('counts', {}).get('arp_replies', 0)
            sent = remote.get('counts', {}).get('sent', 0)
            requested = args.peers * args.rate * args.seconds
            load_met = ((args.direction == 'tx' or sent >= requested * .99)
                and (args.direction == 'rx' or attempted >= requested * .99))
            result = dict(peers=args.peers, connected_at_end=sum(w.state == 'connected' for w in workers),
                minimum_connected=healthy, seconds=wall_seconds, setup_seconds=setup_seconds,
                rate_per_peer_per_direction=args.rate, direction=args.direction,
                subscribers=args.subscribers or None, frame_mix='10% ARP; 30% each 60/500/1500-byte UDP Ethernet',
                rx_frames=rx, tx_frames=tx, rx_pps=rx / wall_seconds, tx_pps=tx / wall_seconds,
                l2_pps=(steady_rx + steady_tx) / wall_seconds, drained_l2_pps=(rx + tx) / wall_seconds,
                offered_l2_pps=(sent + attempted) / wall_seconds,
                requested_l2_pps=args.peers * args.rate * (2 if args.direction == 'duplex' else 1), load_met=load_met,
                tx_attempted=attempted, tx_queued=queued, tx_rejected=drops,
                rx_missing=expected_rx - rx,
                tx_missing=tx + args.peers - remote.get('counts', {}).get('received', 0),
                drain_seconds=time.monotonic() - drain_start,
                cpu_seconds=cpu_seconds, estimated_cores=cpu_seconds / wall_seconds,
                cpu_percent_one_core=100 * cpu_seconds / wall_seconds,
                rss_bytes=current_rss, measured_peak_rss_bytes=max(rss_samples, default=current_rss), peak_rss_bytes=peak_rss,
                threads=int(next(line.split()[1] for line in Path('/proc/self/status').read_text().splitlines() if line.startswith('Threads:'))),
                voluntary_context_switches=switches.ru_nvcsw - switches_start.ru_nvcsw,
                involuntary_context_switches=switches.ru_nivcsw - switches_start.ru_nivcsw,
                subscriber_deliveries=delivered, counters={k: after[k] - before.get(k, 0) for k in after},
                measurement_counters={k: at_end[k] - before.get(k, 0) for k in at_end},
                errors=[w.error for w in workers if w.error] + loop_errors + ([remote['error']] if remote.get('error') else []),
                generator=remote, source=str(args.source), wrapper_max_peers=client.max_peers, profiled=bool(args.profile),
                python=sys.version, platform=platform.platform(), cpu=platform.processor(),
                affinity=sorted(os.sched_getaffinity(0)), crypto='stdlib' if p._AES is None else 'pycryptodome')
            result['target_met'] = (args.peers >= 400 and result['minimum_connected'] == args.peers
                and result['connected_at_end'] == args.peers and peak_rss < 300_000_000
                and result['estimated_cores'] <= 1 and not result['errors'] and not result['rx_missing']
                and not result['tx_missing'] and not drops and load_met)
            return result
        finally:
            if process.is_alive():
                try:
                    parent.send('close')
                except (BrokenPipeError, EOFError):
                    pass
            await client.close()
            for task in consumers:
                task.cancel()
            if consumers:
                await asyncio.gather(*consumers, return_exceptions=True)
            process.join(timeout=5)
            if process.is_alive():
                process.terminate()
                process.join(timeout=5)
            listener.close()
            parent.close()


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--peers', type=int, default=400)
    cli.add_argument('--seconds', type=float, default=30)
    cli.add_argument('--rate', type=float, default=5, help='frames/sec per peer, per enabled direction; 0 = idle')
    cli.add_argument('--direction', choices=('rx', 'tx', 'duplex'), default='duplex')
    cli.add_argument('--subscribers', choices=('ethernet', 'ipv4', 'udp'))
    cli.add_argument('--stdlib', action='store_true')
    cli.add_argument('--drain', type=float, default=2)
    cli.add_argument('--source', type=Path, default=ROOT, help='compare a saved source tree')
    cli.add_argument('--profile', type=Path, help='cProfile output for the selected client scope')
    cli.add_argument('--profile-scope', choices=('peer', 'loop'), default='peer')
    cli.add_argument('--output', type=Path)
    args = cli.parse_args()
    if args.peers < 1 or args.rate < 0 or args.seconds <= 0:
        cli.error('peers/seconds must be positive and rate nonnegative')
    args.source = args.source.resolve()
    profiles = []
    profile = cProfile.Profile() if args.profile and args.profile_scope == 'loop' else None
    result = profile.runcall(asyncio.run, load(args, profiles)) if profile else asyncio.run(load(args, profiles))
    if args.profile:
        args.profile.parent.mkdir(parents=True, exist_ok=True)
        pstats.Stats(*(profiles if profiles else [profile])).dump_stats(str(args.profile))
    encoded = json.dumps(result, indent=2)
    print(encoded)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + '\n')
    return 0 if not result['errors'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
