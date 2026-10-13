# Packet-path benchmarks (Android engine, JVM headless)

Synthetic but realistic micro-benchmarks for the pure-Kotlin engine packet path.
No emulator, no production service, no real network — only `127.0.0.1` loopback.
Baseline for the 2.0 goal: **+30% packet-path speed with an identical wire format**.

## How to run

One-time setup (idempotent):

```sh
./scripts/setup-android-sdk.sh
```

Run only the benchmarks:

```sh
./gradlew :app:testDebugUnitTest --tests "*PacketPathBenchmarkTest*"
```

Run the full unit suite (must stay green):

```sh
./gradlew :app:testDebugUnitTest
```

Numbers are printed as `[bench]` lines (median of 5 measured runs after a JIT
warmup, plus the best run). They appear on the console with `--info` and always
in the JUnit XML (`system-out`):

```sh
grep '\[bench\]' app/build/test-results/testDebugUnitTest/TEST-*PacketPathBenchmarkTest.xml
```

## What each number means

| Benchmark | What it measures |
|---|---|
| `cryptoChaining` (60/500/1500 B) | Continuous `CChannel` encrypt+decrypt with chained IVs and a rekey per run. Anchored to `reference.json` vectors first. |
| `l3BridgeForward` unicast / broadcast | `L3Bridge.outbound` routing to 1 of 61 peers / subnet-broadcast fan-out to all 61 (peers only count frames). |
| `fullPath` unicast (60/500/1400 B) / broadcast x61 | TUN packet → bridge → envelope → per-peer `CChannel` → remote decrypt, decode, `validFrame`, L3 delivery. Each peer does the engine writeLoop's per-frame work. |
| `peerFanout` roundtrip | Keepalive probe/reply through a real SH-authenticated `PeerChannel` over TCP loopback. |
| `peerFanout` one-way | 1000 frames dialer → listener with one record per write (1.5.0 writeLoop, UDP paths). |
| `peerFanout writeLoop` | The same 1000 frames sent the way the TCP writeLoop now sends: up to 32 queued frames per write. |
| `peerSelection` | Per-packet peer selection for 61 peers: 1.5.0 `Membership.permits` per peer vs the version-keyed cache (both on the same build). |

## Results (2.0 packet-path work)

JDK 17.0.19 (HotSpot, SunJCE AES intrinsics), Linux x86-64; median of 3 class
runs, each a median of 5. The baseline is the same harness run on the unmodified
1.5.0 engine sources.

| Case | 1.5.0 µs/op | now µs/op | speedup |
|---|---:|---:|---:|
| fullPath unicast 60 B | 1.015 | 0.498 | 2.04x |
| fullPath unicast 500 B | 1.809 | 1.064 | 1.70x |
| fullPath unicast 1400 B | 3.677 | 2.527 | 1.46x |
| fullPath broadcast fan-out x61 500 B | 101.327 | 44.940 | 2.25x |
| cryptoChaining 60 B | 0.674 | 0.291 | 2.32x |
| cryptoChaining 500 B | 1.441 | 0.792 | 1.82x |
| cryptoChaining 1500 B | 3.452 | 2.062 | 1.67x |
| l3BridgeForward unicast | 0.073 | 0.070 | 1.04x |
| l3BridgeForward broadcast x61 | 0.230 | 0.137 | 1.68x |
| peerFanout keepalive roundtrip | 9.785 | 8.763 | 1.12x |
| peerFanout one-way 100 B (x1000) | 2938.8 | 2694.8 | 1.09x |
| peerFanout one-way 1400 B (x1000) | 5569.8 | 4500.1 | 1.24x |
| **geomean** | | | **1.56x** (time −36%) |

Engine paths outside that comparison:

| Case | 1.5.0 behaviour | now | speedup |
|---|---:|---:|---:|
| TCP writeLoop, 1000 × 100 B frames | 2938.8 µs (one write per record) | ≈ 435 µs (`writeLoop` case) | ≈ 6.7x |
| TCP writeLoop, 1000 × 1400 B frames | 5569.8 µs | ≈ 1720 µs | ≈ 3.2x |
| Peer selection per TUN packet / received frame, 61 peers | 2.23 µs | 0.20 µs | ≈ 11x |

Loopback per-record cases are bounded by one `write(2)` per record and thread
wake-ups; their remaining cost is the kernel. On Android, `Framed` also no longer
issues a `setsockopt(SO_RCVTIMEO)` per socket read (only when the timeout
changes), which the JVM numbers above cannot show.

### Where the speed comes from

- `CChannel`: the CBC chain stays in the initialised `Cipher` (`update` per record,
  re-initialised only on rekey) when a one-time provider self-check proves this
  is byte-identical; otherwise per-record re-initialisation as before. The trailer
  sum reads 64-bit little-endian words, and the MD4 fold of the 8-byte sum is an
  allocation-free unrolled block. Encrypt and decrypt lock separately and reuse
  scratch buffers.
- Broadcasts: the CChannel plaintext (envelope, 0xcc filler, MD4 trailer, pad byte)
  does not depend on the key, so a broadcast `OutFrame` is sealed once and every
  peer only runs its own AES chain (`CChannel.encryptSealed`).
- TCP writeLoop: frames already queued (up to 32, as before) leave in one gathered
  write (`Framed.sendAll`). The byte stream is identical; only TCP segmentation
  differs. UDP keeps per-record sends because its window decides drops per record.
- Engine: the permitted-peer set is cached per `Membership.version` and worker-set
  version, under the same lock, instead of running `permits()` for every peer on
  every packet and frame.
- Bridge/framing: no double copy of the reused TUN buffer, cached own MAC,
  allocation-free `macForIp`/envelope header, no length-prefix concatenation.

## Rules

- **Wire format identical**: `WireIdentityTest` pins SHA-256 digests recorded from
  the 1.5.0 engine (a 3200-record chained CChannel stream with rekey, the raw TCP
  bytes of framed records, and bridge/tunnel/checksum/validation outputs). It also
  compares the optimised `CChannel` (streaming and fallback modes, sealed and plain
  APIs, corrupted records) byte-for-byte with a verbatim 1.5.0 copy
  (`LegacyCChannel.kt`). The `cryptoChaining` anchor checks `reference.json`.
- **No real network**: only `127.0.0.1` (`ServerSocket(0, …)`, `Framed.connect`
  to loopback). Never point benchmarks at the production coordinator or register
  identities.
- **Bounded**: the whole class runs in about 6 s; payloads are reused and counting
  peers only count frames. Do not raise iteration counts without checking CI time.
- **No benchmark-only code in `engine/`**: every production change serves the
  engine's own packet path. A harness peer models the engine's per-frame work, so
  it follows engine changes (see `ChannelPeer`).
