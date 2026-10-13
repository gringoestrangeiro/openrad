#!/usr/bin/env python3
"""Join the eight requested Minecraft public networks and sample real traffic.

Uses a separate persistent profile, no TAP/UDP API, and the public OpenRad API.
Reports aggregate counts only, never identity credentials or peer addresses.
"""
import argparse
import asyncio
from collections import Counter
import json
from pathlib import Path
import platform
import resource
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from openrad import OpenRad

NETWORKS = [f'Minecraft [Português {i}]' for i in range(1, 9)]


def memory():
    lines = Path('/proc/self/status').read_text().splitlines()
    return {key: int(next(line.split()[1] for line in lines if line.startswith(key + ':')))
            * (1 if key == 'Threads' else 1024) for key in ('VmRSS', 'VmHWM', 'Threads')}


def sample(client, elapsed, cpu_delta, wall_delta):
    engine = client._engine
    with engine.lock:
        networks = {nid: network['name'] for nid, network in engine.membership.networks.items()}
        eligible = engine.membership.eligible(engine.selected)
        connected = {rid for rid, worker in engine.workers.items() if worker.state == 'connected'}
        states = Counter(worker.state for worker in engine.workers.values())
        by_network = {name: dict(eligible=sum(nid in peer['network_ids'] for peer in eligible.values()),
            connected=sum(rid in connected and nid in peer['network_ids'] for rid, peer in eligible.items()))
            for nid, name in networks.items()}
        counters = dict(engine.counters)
        recent_failures = Counter(event.get('error', '') for event in engine.events if event['event'] == 'peer_failed')
    mem = memory()
    return dict(elapsed_seconds=elapsed, state=engine.state, joined=sorted(networks.values()),
        known_peers=len(engine.membership.peers), eligible_peers=len(eligible),
        connected_peers=len(connected), connection_states=dict(states), networks=by_network,
        rss_bytes=mem['VmRSS'], peak_rss_bytes=mem['VmHWM'], threads=mem['Threads'],
        cpu_percent_one_core=100 * cpu_delta / wall_delta if wall_delta else 0,
        estimated_cores=cpu_delta / wall_delta if wall_delta else 0,
        counters=counters, recent_failure_counts=dict(recent_failures), control_error=engine.error)


async def run(args):
    client = OpenRad(args.profile_dir, name='openrad-live-benchmark', networks=(),
                     max_peers=args.max_peers, force_relay=args.force_relay)
    result = dict(requested_networks=NETWORKS, max_peers=args.max_peers,
        profile_directory=str(args.profile_dir.resolve()), joins=[], samples=[], errors=[],
        python=sys.version, platform=platform.platform(),
        open_file_limits=resource.getrlimit(resource.RLIMIT_NOFILE))
    setup_start = time.monotonic()
    try:
        await client.start(timeout=60)
        for name in NETWORKS:
            try:
                reply = await client.join(name, timeout=45)
                result['joins'].append(dict(name=name, ok=bool(reply.get('ok')), result=reply))
                print(f'Join {name}: {reply.get("ok", False)}', flush=True)
            except Exception as exc:
                result['joins'].append(dict(name=name, ok=False, error=f'{type(exc).__name__}: {exc}'))
                print(f'Join {name} failed: {type(exc).__name__}: {exc}', flush=True)
        result['setup_seconds'] = time.monotonic() - setup_start
        start, cpu_start = time.monotonic(), time.process_time()
        prior_wall, prior_cpu = start, cpu_start
        initial = sample(client, 0, 0, 0)
        result['samples'].append(initial)
        while time.monotonic() - start < args.seconds:
            await asyncio.sleep(min(5, max(0, start + args.seconds - time.monotonic())))
            now, cpu = time.monotonic(), time.process_time()
            row = sample(client, now - start, cpu - prior_cpu, now - prior_wall)
            result['samples'].append(row)
            prior_wall, prior_cpu = now, cpu
            print(f'{row["elapsed_seconds"]:.1f}s: connected={row["connected_peers"]}/{row["eligible_peers"]}, '
                f'RSS={row["rss_bytes"] / 1e6:.1f} MB, CPU={row["cpu_percent_one_core"]:.1f}% of one core, '
                f'threads={row["threads"]}', flush=True)
            args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
        result['seconds'] = time.monotonic() - start
        result['cpu_seconds'] = time.process_time() - cpu_start
        result['estimated_cores'] = result['cpu_seconds'] / result['seconds']
        result['mean_cpu_percent_one_core'] = 100 * result['estimated_cores']
        result['peak_rss_bytes'] = max(row['peak_rss_bytes'] for row in result['samples'])
        result['final'] = result['samples'][-1]
        result['rx_frames'] = result['final']['counters'].get('peer_rx', 0) - initial['counters'].get('peer_rx', 0)
        result['tx_frames'] = result['final']['counters'].get('peer_tx', 0) - initial['counters'].get('peer_tx', 0)
    except Exception as exc:
        result['errors'].append(f'{type(exc).__name__}: {exc}')
        print(result['errors'][-1], flush=True)
    finally:
        await client.close()
        result['closed_cleanly'] = True
        args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
    return result


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--profile-dir', type=Path, default=ROOT / 'reports/optimization/live-profile')
    cli.add_argument('--max-peers', type=int, default=4096)
    cli.add_argument('--seconds', type=float, default=60)
    cli.add_argument('--force-relay', action=argparse.BooleanOptionalAction, default=False)
    cli.add_argument('--output', type=Path, default=ROOT / 'reports/optimization/live-minecraft-8.json')
    args = cli.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    result = asyncio.run(run(args))
    print(json.dumps({key: result[key] for key in ('errors', 'seconds', 'mean_cpu_percent_one_core', 'peak_rss_bytes') if key in result}, indent=2))
    return 1 if result['errors'] or any(not join['ok'] for join in result['joins']) else 0


if __name__ == '__main__':
    raise SystemExit(main())
