# Maintenance and release checks

OpenRad has one primary maintainer. Keep security and maintenance work
reviewable in small changes; prioritize fixes by impact and demonstrated
reachability rather than by static occurrence counts.

## Dependency policy

Dependabot proposes grouped routine updates for Cargo (including fuzzing),
GitHub Actions, Gradle, Go and the Python client. Review upstream changes,
MSRV, licenses, feature selections, platform support and compatibility before
merging. Major updates require separate review. Do not merge a dependency
update solely to silence an advisory. Re-run the relevant client suites and
the sync check. Keep Cargo lockfiles committed and validate with --locked.

For Rust releases, inspect Cargo Audit output including informational
warnings. Record the advisory ID, affected target, dependency path, mitigation,
and a follow-up condition for any warning left unresolved. Do not suppress
advisories globally. Actions in the Rust, tooling and candidate release
workflows are pinned to commits; review pin changes rather than accepting
mutable tags. Other workflows are outside this Rust release checkpoint.

The fuzz workspace uses a separate lockfile. When the Rust lockfile changes,
reseed fuzz/Cargo.lock from Cargo.lock and run
`cargo metadata --manifest-path fuzz/Cargo.toml --format-version 1`, then
review the resulting lockfile and use --locked. The fuzz workflow checks the lock and compares shared crate versions and
checksums before building.

## Release checklist

1. Confirm the authorized branch, version, release scope and clean worktree.
   Rust-only updates must not re-version other clients.
2. Run formatting, workspace tests, Clippy with warnings denied, locked
   check/build, Cargo Audit, sync check, packaging regressions and the
   disposable coordinator test. Run bounded parser fuzzing on Linux.
3. Generate release artifacts using the existing candidate tooling, including
   notices, checksums, driver provenance and corresponding driver source.
   Check binaries and extracted payloads against their manifests.
4. Review SBOM.cdx.json: it inventories the target-filtered normal Cargo
   dependency graph and explicit bundled native components. It is not a
   linker-derived inventory of each individual executable. Build metadata and
   license notices separately record toolchain/OS and build components.
5. Qualify real TAP on supported platforms, route cleanup, Windows startup and
   official-client interoperability using authorized test resources. Record
   what was not tested. Do not label candidates as stable on the basis of CI.
6. Publish only following explicit approval. Keep a prior working version and
   its checksums available; do not overwrite user identities or profiles.
   Confirm an upgrade and a documented rollback on disposable profiles.

If a regression is found, mark the affected release accurately, record the
reproduction and corrective commit, and publish a replacement only after
the same gates pass. Never downgrade or reset user identities without
their consent.

The Windows installer already packages the unchanged driver files, verifies
their upstream hashes, includes the corresponding pinned source archive
and GPL/MIT notices, and records provenance. Legal compliance beyond this
technical inventory has not been independently evaluated.
