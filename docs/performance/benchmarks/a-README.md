# Windows UDP send experiment

The benchmark builds actual source functions selected from an explicit source
tree. Use the audited source snapshot as the baseline, including existing fixes;
an earlier Git commit is not an equivalent baseline.

From the repository root on Windows, with Cargo debug dependency artifacts built:

```powershell
python docs/performance/benchmarks/a-dataplane.py BASELINE_ROOT target/debug/deps work/a-before
python docs/performance/benchmarks/a-dataplane.py CANDIDATE_ROOT target/debug/deps work/a-after
work/a-before/a-dataplane.exe
work/a-after/a-dataplane.exe
```

Collect at least six runs per executable, alternating the order of each pair.
The JSON lines include time per operation, percentiles of 64-operation batch
means, and allocations in a separate 64-operation pass. Warmup, allocation
counting and socket creation are outside timed batches. CRC and chained CBC
roundtrips are controls; the UDP case sends and drains one datagram at a time
across 1, 10 or 100 loopback socket pairs. Every payload and CRC is checked.

This is an isolated `rustc -O` harness linked to existing debug dependency
artifacts, not a full release VPN benchmark. Crypto dependency costs and local
system noise limit interpretation of its absolute times and small differences.

## Rejected candidate, 2026-11-05

Replacing the Windows temporary packet allocation with synchronous `WSASend`
and two `WSABUF` descriptors removed one allocation per datagram. Six alternating
pairs on Windows 11 / i7-11370H did not show a consistent timing benefit:

| Command bytes | Socket pairs | Before ns/op | Candidate ns/op | Change |
| ---: | ---: | ---: | ---: | ---: |
| 100 | 1 | 9,291.9 | 8,428.6 | -9.29% |
| 100 | 10 | 8,664.2 | 8,437.8 | -2.61% |
| 100 | 100 | 9,546.6 | 9,369.0 | -1.86% |
| 1,392 | 1 | 10,454.4 | 10,170.1 | -2.72% |
| 1,392 | 10 | 10,195.9 | 10,394.5 | +1.95% |
| 1,392 | 100 | 11,649.8 | 11,266.9 | -3.29% |

The MTU-sized, ten-pair case also regressed in median batch p95 from 14,371.1
to 15,189.1 ns/op. Ranges overlapped broadly, and unchanged crypto controls
varied by about 4%. The candidate was rejected: the allocation result alone
did not justify an additional unsafe FFI path. Production sources were kept
byte-identical to the audited snapshot. Raw results and the isolated candidate
are retained in the task's `work/optimization/a/` directory.
