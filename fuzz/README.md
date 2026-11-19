# Bounded local parser fuzzing

These targets consume byte slices only and do not communicate with any remote
service. Corpus preparation uses the repository's synthetic protocol fixtures
and a disposable synthetic identity. Do not add real profiles or captures.

Use a Linux host with the pinned nightly toolchain in
../.github/workflows/rust-fuzz.yml and cargo-fuzz 0.13.2:

```sh
python3 fuzz/prepare_corpus.py control_records fuzz/corpus/control_records
cargo metadata --manifest-path fuzz/Cargo.toml --format-version 1 --locked
cargo fuzz run control_records fuzz/corpus/control_records -- -max_total_time=30 -max_len=65537 -timeout=5 -rss_limit_mb=2048
```

Targets: identity_import, control_records, tunnel_records, handshake_records.
Prepare each target's own corpus before running it. The workflow runs them
independently with the default AddressSanitizer configuration. Short checks
run on changes; the weekly job runs each target for up to 15 minutes.

A local Windows MSVC cargo-fuzz build may not link LLVM coverage instrumentation.
Do not treat an uninstrumented smoke run or cargo check as a coverage-guided
fuzz campaign. Preserve failure artifacts privately and convert confirmed
defects into deterministic, synthetic regression tests.

The current targets cover parsing and bounded roster admission. They do not
qualify the full stateful handshake, encrypted transport, reliable UDP
reassembly, operating-system I/O or interoperability.
