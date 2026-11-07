#!/usr/bin/env python3
"""Synthetic loopback ORAD latency/queue admission benchmark; no live VPN.

Uses authenticated production UDPServer/LocalClient and Engine.send_ipv4.
Peer transmit queues are counting sinks: this measures API admission and
fan-out, not encrypted transport or global VPN throughput. Use benchmark.py
separately for real authenticated encrypted TCP workers.
"""
import argparse
import asyncio
import cProfile
import gc
import json
import os
from pathlib import Path
import resource
import statistics
import sys
import tempfile
import threading
import time
from types import SimpleNamespace


def percentile(values, percent):
    return sorted(values)[min(len(values) - 1, int((len(values) - 1) * percent))]


async def measure(args):
    sys.path.insert(0, str(args.source.resolve()))
    import openrad as app
    import radproto as p
    import radvpn as v
    loop = asyncio.get_running_loop()
    results = []
    for peers, size, fill in [(1, 0, 0), (1, 0, 3000)] + [
            (peers, size, 3000) for peers in (1, 10, 100) for size in (100, 1472)]:
        with tempfile.TemporaryDirectory(prefix='openrad-opt-api-') as directory:
            identity = p.Identity(1000, '26.1.0.1', 'synthetic', b'a' * 16,
                                  b'synthetic-identity', '127.0.0.1')
            engine = v.Engine(identity, v.Profile(directory), networks=['synthetic'], passive=True, max_peers=peers)
            engine.state = 'online'
            network = 'ab' * 16
            engine.membership.networks[network] = dict(name='synthetic', network_id=network)
            admitted = [0]

            def admit(frame):
                admitted[0] += 1

            for index in range(peers):
                rid = 1001 + index
                peer = dict(rid=rid, vip=str(p.IPv4Address(0x1a010002 + index)),
                            name='synthetic', server='127.0.0.1', state=1, network_ids=[network])
                engine.membership.peers[rid] = peer
                engine.workers[rid] = SimpleNamespace(peer=peer, state='connected', stop=threading.Event(),
                    channel=SimpleNamespace(mac=p.mac_for_ip(peer['vip'])),
                    tx=SimpleNamespace(put_nowait=admit), incoming=None)
            server = app.UDPServer(engine, engine.profile)
            client = app.LocalClient(server.endpoint, server.token)
            # Fill via real authenticated request parsing, discarding setup replies.
            send = server.send
            server.send = lambda address, data: True
            address = client.sock.getsockname()
            server.request(app.encode_message(app.HELLO, 1, server.token, b'{}'), address)
            for request in range(2, fill + 2):
                server.request(app.encode_message(app.STATUS, request, server.token), address)
            server.send = send
            client.sequence = fill + 10
            readers = (server.sock, server.frames.reader, server.replies.reader)
            for reader in readers:
                loop.add_reader(reader, server.poll, 0)
            try:
                # Engine owns an unstarted thread whose bound target forms a
                # cycle. Collect previous cases equally, outside timed work;
                # allocation rates must not decide when old cases free memory.
                gc.collect()
                wall, cpu = time.monotonic(), time.process_time()
                switches = resource.getrusage(resource.RUSAGE_SELF)
                await asyncio.sleep(args.idle)
                idle = dict(seconds=time.monotonic() - wall, cpu_seconds=time.process_time() - cpu,
                    voluntary_context_switches=resource.getrusage(resource.RUSAGE_SELF).ru_nvcsw - switches.ru_nvcsw)

                def requests():
                    latencies = []
                    start_cpu, start = time.process_time(), time.monotonic()
                    for _ in range(args.requests):
                        before = time.perf_counter_ns()
                        if size:
                            result = client.send_udp('255.255.255.255', 49001, 49002, bytes(size))
                            assert result['queued'] == peers, result
                        else:
                            result = client.status()
                        assert result['ok'], result
                        latencies.append((time.perf_counter_ns() - before) / 1000)
                    elapsed = time.monotonic() - start
                    return dict(seconds=elapsed, cpu_seconds=time.process_time() - start_cpu,
                        requests_per_second=args.requests / elapsed,
                        latency_us=dict(p50=statistics.median(latencies), p95=percentile(latencies, .95),
                                        p99=percentile(latencies, .99)), latencies_us=latencies)

                record = await asyncio.to_thread(requests)
                status = Path('/proc/self/status').read_text().splitlines()
                record.update(peers=peers, payload_bytes=size, cache_fill=fill,
                    requests=args.requests, idle=idle, counters=dict(engine.counters),
                    synthetic_queue_admissions=admitted[0],
                    rss_bytes=1024 * int(next(line.split()[1] for line in status if line.startswith('VmRSS:'))))
                results.append(record)
            finally:
                for reader in readers:
                    loop.remove_reader(reader)
                client.sock.close()
                server.close()
    return dict(kind='synthetic_authenticated_loopback_api_queue_admission',
                python=sys.version, uid=os.geteuid(), fd_limit=resource.getrlimit(resource.RLIMIT_NOFILE),
                source=str(args.source), results=results)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--requests', type=int, default=500)
    parser.add_argument('--idle', type=float, default=.25)
    parser.add_argument('--profile', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not 1 <= args.requests <= 1000 or args.idle < 0:
        parser.error('requests must be 1–1000; idle must be nonnegative')
    profile = cProfile.Profile() if args.profile else None
    result = profile.runcall(asyncio.run, measure(args)) if profile else asyncio.run(measure(args))
    if profile:
        profile.dump_stats(str(args.profile))
    encoded = json.dumps(result, indent=2)
    if args.output:
        args.output.write_text(encoded + '\n')
    else:
        print(encoded)


if __name__ == '__main__':
    main()
