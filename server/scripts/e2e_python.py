#!/usr/bin/env python3
"""End-to-end test: run openrad-server and drive two OpenRad Python clients.

Usage (from the repository root or anywhere):
    python3 server/scripts/e2e_python.py [--server PATH] [--keep]

Without --server, the server is built with `go build` into the temporary
directory. The Python client comes from this repository's clients/python/.
TCP port 17301 on 127.0.0.1 must be free: the Python client always reaches
the control server and peer coordinators on that port. Everything else lives
in a temporary directory that is removed afterwards.
"""
import argparse
import json
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parent.parent
REPO = SERVER_DIR.parent
CONTROL_PORT = 17301


def port_free(port):
    with socket.socket() as s:
        if sys.platform != 'win32':
            # Like the server's listener, ignore sockets left in TIME_WAIT.
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(('127.0.0.1', port))
        except OSError:
            return False
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--server', type=Path, help='prebuilt openrad-server binary (default: build one)')
    ap.add_argument('--client', type=Path, default=REPO / 'clients' / 'python' / 'openrad.py',
                    help='OpenRad Python client entry point')
    ap.add_argument('--keep', action='store_true', help='keep the temporary directory')
    args = ap.parse_args()
    client = args.client.resolve()
    if not client.exists():
        sys.exit(f'missing {client}')
    if not port_free(CONTROL_PORT):
        sys.exit(f'TCP port {CONTROL_PORT} is in use; stop the other server first')
    work = Path(tempfile.mkdtemp(prefix='openrad-e2e-'))
    if args.server:
        server = args.server.resolve()
    else:
        server = work / ('openrad-server.exe' if sys.platform == 'win32' else 'openrad-server')
        subprocess.run(['go', 'build', '-o', str(server), '.'], cwd=SERVER_DIR, check=True)
    procs, failures = [], []

    def spawn(cmd, log):
        p = subprocess.Popen(cmd, stdout=open(work / log, 'w'), stderr=subprocess.STDOUT,
                             cwd=client.parent)
        procs.append(p)
        return p

    def cli(profile, *a, timeout=60):
        r = subprocess.run([sys.executable, str(client), '--data-dir', str(work / profile), *a],
                           capture_output=True, text=True, timeout=timeout, cwd=client.parent)
        try:
            return json.loads(r.stdout)
        except json.JSONDecodeError:
            return {'ok': False, 'raw': r.stdout + r.stderr}

    def check(name, cond, detail=''):
        print(('PASS ' if cond else 'FAIL ') + name + ('' if cond else f'  {detail}'))
        if not cond:
            failures.append(name)

    def wait(pred, timeout=45):
        end = time.time() + timeout
        while time.time() < end:
            try:
                if pred():
                    return True
            except (KeyError, TypeError, IndexError):
                pass
            time.sleep(0.5)
        return False

    try:
        spawn([str(server), '--db', str(work / 'server.db'), 'serve', '-v',
               '--public', 'E2E Public', '--public', 'E2E Other'], 'server.log')
        modulus = work / 'modulus.bin'
        check('server writes modulus', wait(modulus.exists, 15))
        base = ['run', '--host', '127.0.0.1', '--modulus', str(modulus), '--mode', 'server',
                '--network', 'E2E Public']
        pw, bad = work / 'pw.txt', work / 'bad.txt'
        pw.write_text('correct horse battery\n')
        bad.write_text('wrong password!\n')
        for f in (pw, bad):
            f.chmod(0o600)
        for name in ('alice', 'bob'):
            (work / name).mkdir(mode=0o700)
            spawn([sys.executable, str(client), '--data-dir', str(work / name), *base, '--name', name],
                  name + '.log')
        online = lambda p: cli(p, 'status').get('state') == 'online'
        check('alice registers and logs in', wait(lambda: online('alice')))
        check('bob registers and logs in', wait(lambda: online('bob')))
        bob = cli('bob', 'status')
        check('bob has a 26.x VIP', bob.get('vip', '').startswith('26.'), bob)
        check('public network joined',
              [n['name'] for n in bob.get('networks', [])] == ['E2E Public'], bob.get('networks'))

        def connected(profile, transport=None):
            peers = cli(profile, 'peers').get('peers', [])
            return any(p['connection'] == 'connected' and (transport is None or p['transport'] == transport)
                       for p in peers)
        check('peers connect directly', wait(lambda: connected('alice', 'direct-tcp')))
        bob.setdefault('vip', '0.0.0.0')
        ping = cli('alice', 'ping', bob['vip'], '--count', '3')
        check('ping over direct TCP', ping.get('ok') is True, ping)

        echo = subprocess.Popen([sys.executable, str(client), '--data-dir', str(work / 'bob'),
                                 'udp-echo', '--port', '49000', '--duration', '8'],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, cwd=client.parent)
        procs.append(echo)
        time.sleep(1.5)
        udp = cli('alice', 'udp-send', bob['vip'], '49000', 'e2e payload')
        check('UDP echo through tunnel', udp.get('received') is True, udp)

        search = cli('alice', 'search')
        names = [n['name'] for n in search.get('networks', [])]
        check('public search lists networks', names == ['E2E Other', 'E2E Public'], search)
        check('search filters', [n['name'] for n in cli('alice', 'search', 'other').get('networks', [])] == ['E2E Other'])

        check('create private network',
              cli('alice', 'create', 'E2E Private', '--password-file', str(pw)).get('ok') is True)
        r = cli('bob', 'join', 'E2E Private', '--password-file', str(bad))
        check('wrong password refused', r.get('ok') is False and 'authentication failed' in r.get('error', ''), r)
        r = cli('bob', 'join', 'E2E Private')
        check('passwordless join of private network refused', r.get('ok') is False, r)
        r = cli('bob', 'join', 'E2E Private', '--password-file', str(pw))
        check('correct password accepted', r.get('ok') is True and r.get('pending_approval') is False, r)
        check('private network hidden from search', cli('bob', 'search', 'Private').get('networks') == [])
        r = cli('alice', 'leave', 'E2E Private')
        check('sole admin cannot leave (error 19)', 'error 19' in r.get('error', ''), r)
        r = cli('bob', 'delete', 'E2E Private')
        check('member cannot delete', r.get('ok') is False, r)
        check('member leaves', cli('bob', 'leave', 'E2E Private').get('left') is True)
        check('admin deletes', cli('alice', 'delete', 'E2E Private').get('deleted') is True)

        for p in ('alice', 'bob'):
            cli(p, 'force-relay', 'true')
        # Both clients redial at once here. The Python client can drop the
        # peer's offer while its previous worker is still shutting down and
        # dial out itself, which the lower RID correctly ignores; the pair
        # then converges on the next retry (~45 s). Allow one retry cycle.
        check('relay transport', wait(lambda: connected('alice', 'relay'), 100))
        ping = cli('alice', 'ping', bob['vip'], '--count', '2')
        check('ping over relay', ping.get('ok') is True and ping.get('transport') == 'relay', ping)

        cli('bob', 'stop')
        check('offline presence propagates', wait(lambda: all(
            p['state'] == 0 for p in cli('alice', 'peers')['peers']), 15))
    finally:
        for p in procs:
            if p.poll() is None:
                p.send_signal(signal.SIGINT)
        for p in procs:
            try:
                p.wait(timeout=8)
            except subprocess.TimeoutExpired:
                p.kill()
        if args.keep or failures:
            print(f'logs kept in {work}')
        else:
            shutil.rmtree(work, ignore_errors=True)
    print(f'\n{"ALL PASSED" if not failures else f"{len(failures)} FAILED: " + ", ".join(failures)}')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
