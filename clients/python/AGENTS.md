# AGENTS.md — openrad-python (Linux Python client)

## Stack

Python 3.10+ on Linux, **standard library only**. Optional acceleration via
PyCryptodome (`pip install '.[fast]'`); the pure-Python AES fallback is
tested too (`python3 -S openrad.py self-test`). Three modules, no build
step: `openrad.py` (CLI, UDP server, SDK, integrated tests), `radvpn.py`
(engine, peers, sockets, profiles, optional Linux TAP), `radproto.py`
(crypto, authentication, TLV, networks, packets). Asyncio in-memory API
(`OpenRad`) plus a localhost UDP API (ORAD v1). MIT license.

> Source of truth: the Rust workspace at the repo root (`src/`, `desktop/`,
> `tests/`). Preserve the behavioral contracts below when porting.

## Structure and where to edit

- `openrad.py` — `OpenRad` asyncio class (`subscribe`/`send_udp`/`send_ipv4`/
  `send_ethernet`/`join`/`create`/`leave`/`delete`/`search`/`connect`/
  `force_relay`), `LocalClient` sync SDK, `UDPServer` (ORAD v1, header
  `struct.Struct("!4sBBHIQI16s")`), CLI `parser()`/`main()`, `self_test()`
  (uses `fixtures/*.json`). CLI-only changes stay here.
- `radproto.py` — wire-compatible RSA/SH/`CChannel`, `SessionCrypto`,
  `PureCC`, TLV/record helpers, login/registration, network validation
  (`validate_network_name`), ARP/Ethernet/IPv4 builders. Wire-format changes
  require a vector/test.
- `radvpn.py` — `Engine` (control, peers, commands, membership, selection),
  `PeerChannel`/`PeerWorker` (encrypted channels), `Framed`, `Profile`
  (0700 dir, 0600 files, single active engine), `Tap` (Linux-only,
  pre-existing `CAP_NET_ADMIN`, never sudo). No GUI, no forum code.
- `tests/test_incoming.py` — engine/transport unit tests with synthetic
  identities and loopback TCP (no live service). Only test file; keep it
  that way unless a new module ships.
- `fixtures/` — `reference.json`, `network-verifier.json`: synthetic vectors
  for `self-test`. Never real credentials.
- `tools/` — `benchmark.py` (offline load harness, stubs only the
  coordination service), `live_benchmark.py` (hits the live service, joins
  public networks — never run in CI), `verify_compat.py` (diffs changed
  crypto/framing helpers against a saved `radproto.py`).
- `docs/` — `VALIDATION.md` (live-network validation record).
  Root `README.PT-BR.md` is the Portuguese original; `README.md`
  is the English technical translation — keep them in sync.

## Build / test commands

No build; no generated files. Run everything from `clients/python/`:

```sh
python3 -m py_compile openrad.py radproto.py radvpn.py
PYTHONPATH=. python3 -m unittest discover -s tests -v
python3 run_tests.py            # unittest, then the stdlib-only (-S) pass
python3 openrad.py self-test
python3 -S openrad.py self-test # stdlib-only, no PyCryptodome/site-packages
python3 -m venv .venv && .venv/bin/python -m pip install .
```

Canonical install/test from a checkout (stdlib-only by default):

```sh
pip install ./clients/python  # or: pip install "git+https://github.com/gringoestrangeiro/openrad.git@main#subdirectory=clients/python"
pip install "./clients/python[fast]"  # optional AES acceleration (pycryptodome)
openrad --help
python -c "import openrad,radvpn,radproto"
```

- Do not commit `__pycache__/`, `.venv/`, `profiles/`, `reports/`,
  `api.json`, or identity files.
- `pyproject.toml` `py-modules` must list exactly the three modules;
  `readme` is `README.md`; optional dependency `fast` is pycryptodome only.
- `tools/live_benchmark.py` contacts the live service — manual runs only,
  never automated.

## Style

- Standard library only in the three shipped modules; `sys.path` hacks and
  third-party imports belong in `tools/`, never in `openrad.py`/`radvpn.py`/
  `radproto.py`.
- Small functions, explicit `ValueError` for bad input, no bare `except`.
  Errors that cross the CLI become `{"ok": False, "error": ...}` JSON.
- No `sudo`, no privilege escalation, no threads touching asyncio objects
  directly (bridge via the bounded inbox + batch wakeup).
- Comments explain wire/security rationale briefly; no chatty narration.

## Behavioral contracts (from the Rust core, do NOT relax)

- No direct ENet/UDP peer transport: peers use encrypted TCP channels over
  direct TCP or relay TCP; `force_relay` forbids direct setup both ways and
  drops existing direct connections.
- Local API is always UDP (ORAD v1, 40-byte header, `opcode | 0x80` replies,
  10 s packet-reply / 90 s command-reply dedup windows); the in-memory API
  is transport-independent.
- `peer=0` routes by destination; broadcast/multicast fan out once per
  eligible connected peer, never queued for disconnected members.
- Source MAC/IP stay bound to the authenticated channel, except ARP replies
  (`opcode=2`), which accept arbitrary source MAC/sender MAC/sender IP in
  both directions, bytes unrewritten.
- Private-network passwords: in-memory `bytes`, never persisted to settings;
  `--password-file` for automation, never argv/env.
- Profile `0700`, identity/settings/API discovery `0600`, one active engine
  per profile; ambiguous registration responses are not auto-retried.
- Queue defaults: 256 packets/subscription, 1024-frame bridge, 64
  subscriptions/instance, 32 local-UDP clients, 120 s idle subscription
  TTL; slow consumers drop their own events (`channel.dropped`), never
  block others.

## Tests

- Unit tests in `tests/` (unittest, stdlib only): synthetic crypto vectors,
  password proofs, partial TCP framing, API auth, tampered-packet rejection,
  dedup, localhost UDP over an encrypted TCP channel, multi-instance SH/TCP
  exchange, broadcast/multicast, ARP-reply exception, full queues,
  cancellation, timeouts, concurrent consumers.
- `self-test` additionally covers import-by-external-module and
  profile/thread release; fixtures resolve from `fixtures/` in the source
  tree or the installed `share/openrad-python/fixtures`.
- Integration tests use only `127.0.0.1` and synthetic identities; never
  contact the live service (except the manual `live_benchmark.py`) and never
  register identities in tests.
- Never touch the user's real VPN profile in tests; use temp dirs.

## Security

- Identity/credentials only in the profile dir (`0700`, files `0600`);
  never in common prefs, logs, or serialization. `api.json` token stays in
  the file, never in logs.
- No passwords, session keys, tokens, or payloads in logs; review `print`
  paths before PR.
- Password/key memory limits (CPython): `bytes` are immutable, so passwords
  and cached AES key schedules cannot be wiped from memory. Drop references
  as soon as possible instead: `SHClient`/`SHServer` clear `password` once
  the exponent is derived, `PeerWorker` drops the `(cid, password)` offer
  after setup, expired (40 s TTL) and consumed pending offers release
  theirs, network-command passwords are popped after dispatch, and every
  `PureCC` rekey calls `expand.cache_clear()` to release the previous key
  schedule. Never add passwords to logs, serialization, or settings.
- `MAX_DATAGRAM` 64000, JSON requests capped at 16384 bytes, unauthenticated
  or malformed UDP API datagrams get no reply.

## Commits

Prefixes `feat:`, `fix:`, `docs:`, `chore:`. Describe observable behavior +
validation (`unittest discover`, `self-test`, stdlib-only `-S` pass). No
commits from agents unless asked.
