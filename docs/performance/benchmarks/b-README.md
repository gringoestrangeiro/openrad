# Daemon status serialization benchmark

From the repository root, run
`python docs/performance/benchmarks/b-status.py SOURCE_OR_BASELINE_ZIP CARGO_DEPS OUTPUT_DIRECTORY`, then
`OUTPUT_DIRECTORY/b-status.exe 300 stable` and repeat with `updated` and `clone`.
Requires Windows, Rust, Python, and existing compatible Cargo dependency rlibs.
The build records the daemon SHA-256 and full compiler invocation. An archived
source baseline is accepted so uncommitted audited fixes remain part of it.

The script extracts the production `State`, `Shared`, constructor, state-lock
helper, status capture, and status serialization methods verbatim. Public
`Snapshot`/peer/preferences types come from the existing `openrad` rlib. The
benchmark driver and extracted methods use `rustc -O`; linked dependencies and
the public types may be debug builds. It is an isolated operation benchmark,
not a release-mode service or end-to-end IPC benchmark.

Each fresh process visits 0, 10, 100, 1,000, 5,000, and 10,000 synthetic peers,
with Unicode names, one network, role metadata, direct UDP status, an endpoint,
and nonempty details. `stable` serializes an unchanged snapshot; `updated`
first clones/replaces the snapshot and changes elapsed/traffic counters, then
serializes it. `clone` measures snapshot construction independently. Ten warmup
operations precede allocation counting, then individual request timings record
p50/p95/p99. Use alternating baseline/candidate processes and multiple rounds.

Allocation/reallocation calls, total requested bytes and peak incremental live
heap bytes use the real global allocator. Snapshot replacement retains one
equally sized snapshot across operations. The allocator-counting pass is
separate from timing. Windows `GetProcessTimes` records actual whole-process CPU
time, and `GetProcessMemoryInfo` records working set and peak working set. Small
CPU samples are limited by the OS timer resolution. Working set includes the
driver, dependencies, allocator retention, and previous smaller-peer cases;
it is not application RSS or a whole-service memory saving.

No profile, named pipe, socket, TAP, credential, real account, public network,
worker scheduling, or GUI is involved. Queue bounds, cancellation/idle latency,
and the 500 ms publication cadence are outside the measured operation.
