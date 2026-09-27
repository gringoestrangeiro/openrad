# Network management verification — 2026-09-27

The CLI was exercised against the live service with two **new, owned identities**
and one uniquely named private network. Existing profiles and memberships were
not used. Passwords, identity credentials and SH proofs remain outside the
repository. Static analysis used IDA Pro MCP and the adjacent `openrad` research;
vendor binaries were not executed.

## Live CLI results

| CLI operation / follow-up | Observed result |
| --- | --- |
| `provision` for owner and member | Both received separate identities and VIPs |
| `create-network --password-file …` | Created network; a fresh `peers` connection showed creator role 2 (Admin) |
| `public-networks --query EXACT_PRIVATE_NAME` | No public listing for the private network |
| `join --password-file WRONG_FILE` | Exit 1, explicit password-authentication failure; no membership accepted |
| `join --password-file CORRECT_FILE` | Exit 0, `join_approved`; verified SH M2 and correlated network acknowledgement |
| Ordinary member calls `revoke-admin` on owner | Exit 1, service error 20; roles unchanged |
| Owner calls `grant-admin` on member | Exit 0; member's fresh `peers` connection showed role 2 |
| Owner calls `revoke-admin` on member | Exit 0; member's fresh `peers` connection showed role 1 |
| Newly granted admin revokes then restores creator's admin role | Both commands succeeded; creator reattached after each and observed roles 1 then 2 |
| Owner calls `kick` on member | Exit 0; member's fresh `peers` connection showed no networks, peers or roles |
| Last administrator calls `leave` | Exit 1, service error 19; membership preserved |
| Owner calls `delete-network --confirm` | Exit 0; a fresh owner connection showed no networks, peers or roles |

The disposable network was deleted. Both test identities finished with empty
membership. Initial investigation runs that exposed mismatched public/private
flags, status interpretation, and JoinApproved contents are documented in the
[handoff](README.md); the successful sequence above was rerun after fixes.

The command forms and password-file setup are in [the CLI guide](../../cli.md).
Use new output directories on each invocation. The actual live run used local
mode-0700 output storage and mode-0600 password files; no reusable secret was
copied into these notes or synthetic fixtures.

## Automated coverage

`tests/network_management.rs` covers the native request tags/action numbers,
response correlation, private SH sequencing and server-proof rejection, the SH
abort marker, approval ordering, legacy public joins, create/delete results,
role updates, and kicks preserving other shared memberships. Pending applicants
cannot be selected for forwarding through an unapproved network. The Unicode
CRC/verifier fixture was independently computed with Python's SHA-1 and modular
arithmetic, using the CRC table extracted from the service binary.

Desktop tests use egui's actual widgets, accessibility bounds and pointer events
to exercise the create/join buttons and member/delete confirmations. They check
minimum-window layout, disabled submissions, exact action/target selection,
cancellation, stale permissions, password confirmation and redacted debugging.
CLI parser tests require explicit deletion confirmation and reject plaintext
password arguments.

The existing transport, cryptography, peer-selection, desktop-engine and storage
tests remain part of the workspace run. The privileged TAP integration test is
ignored by default. No new traffic was forwarded to unrelated peers.

## Final workspace checks

- `cargo test --workspace --locked`: **77 passed**, no failures; one existing
  privileged TAP integration test ignored.
- `cargo clippy --workspace --all-targets --locked -- -D warnings`: passed.
- `cargo fmt --all -- --check` and `git diff --check`: passed.
- `cargo build --workspace --release --locked`: passed; both `openrad` and
  `openrad-desktop` rebuilt in `target/release/`.

## Limits

The live checks establish service interoperability for the tested build and
service on this date. They do not establish equivalence across all vendor
versions. Unicode verifier construction and pending-approval behavior have local
coverage; the live disposable network used ordinary automatic admission with
ASCII credentials. The new GUI was tested headlessly; live service mutations
were driven through the CLI using the shared operation engine.
