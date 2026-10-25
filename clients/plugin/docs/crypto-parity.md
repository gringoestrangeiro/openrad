# Cryptographic and identity parity

The Java implementation was translated from the read-only canonical Rust files
`src/crypto.rs`, `src/config.rs`, `src/protocol.rs`, and `src/network.rs` in the
OpenRad reference checkout. It uses JDK AES, SHA-1, secure randomness and unsigned
`BigInteger` arithmetic. An internal RFC 1320 MD4 implementation supplies the
legacy channel trailer. No additional cryptography provider is required.

## Wire behavior

- `ShClient` and `ShServer` implement the canonical 1536-bit group, generator 5,
  SHA-1 multiplier/scrambling parameters, identity word swapping, variable-width
  shared-secret serialization and two SHA-1 key expansion blocks. Neither side
  returns session key material before checking the remote proof. The incoming
  server consumes a failed proof as Rust does.
- `Channel` uses AES-256 CBC with independent all-zero initial transmit and
  receive IVs. Each direction keeps its final ciphertext block across records
  and rekeys. Plaintext is extended with `cc` padding, the folded MD4 checksum,
  and a length byte. A rejected authenticated record still consumes the receive
  IV; malformed lengths do not. This same channel serves control and peer data.
- `RsaSession` creates the PKCS#1 v1.5 envelope containing little-endian
  `3, 0x4b, purpose` and 64 random bytes. It emits modulus-width ciphertext and
  supports the canonical purpose codes 3, 4 and 5. The caller supplies the public
  modulus from the public service configuration.
- `Crypto.networkVerifier` accepts the unsigned network identifier, password
  wire bytes and 32-byte salt. The caller must supply the Rust network identity
  CRC64 and UTF-8 password **with its trailing zero byte**.

SH parsing checks the state index, record types and lengths, complete coverage,
group parameters and peer public values. Cryptographic protocol rejection uses
`IllegalArgumentException`; a missing JDK algorithm uses `IllegalStateException`.
Keys, passwords and proofs are not included in object string representations or
exception diagnostics.

## Persistent identity

`Identity.registered` parses the actual registration TLVs, and `load`/`save` use
the Rust-compatible `openrad-identity-v1` JSON schema. Unsigned 64-bit RIDs round
trip without losing their upper bit. The credential remains an opaque byte
sequence, serialized only as hexadecimal in the private identity file.

Loading restricts the file to its owner, is limited to 65,536 bytes, rejects
symlinks, validates UTF-8 and identity fields, and leaves malformed file contents
intact. Saving first validates an assigned
identity, creates an unpredictable temporary file, applies POSIX `0600` or an
owner-only Windows ACL before writing secrets, forces the file to storage, then
atomically replaces the destination. If atomic replacement is unsupported, the
save fails instead of using a partial overwrite. `Identity.secureFile` exposes
the same permission operation for provisioning markers and private settings.
The caller owns the process/profile lock and registration retry policy.

Owned credential, session-key and proof byte arrays are cleared on `close`.
Java cannot guarantee erasure of provider copies, immutable JSON strings or
`BigInteger` internals; this is best-effort memory hygiene rather than a claim
of complete zeroization. Identity JSON must never be included in logs.

## Validation performed

`gradlew.bat test --tests '*crypto.*' --console=plain` passes 13 synthetic tests
on Windows with JDK 25 compiling for Java 21. The tests cover:

- Six independent RFC 1320 MD4 vectors.
- All 11 canonical CBC records plus the canonical chained rekey vector,
  encryption and decryption in both cases.
- Every outgoing and incoming SH fixture message and the final 40-byte key,
  invalid state/group/public values, trailing bytes and rejected proofs.
- RSA recovery through an independent private modular operation, checking
  nonzero padding and all three purpose headers.
- The canonical Unicode private-network verifier vector.
- Rust identity JSON, unsigned RID and Unicode round trips; registration TLVs;
  malformed/oversized records; replacement and failure preservation; and
  owner-only ACL checks on the current Windows filesystem.

The expanded focused command below passes 21 tests:

```powershell
.\gradlew.bat test --tests '*crypto.*' --tests '*network.NetworkJoinTest' --tests '*session.ControlSessionTest' --console=plain
```

The additional tests run an isolated localhost service with a synthetic RSA
private key through the actual Java registration, attachment and private-network
join code. The service sends provisioning `LoginComplete` (operation 21) before
`Registered` (operation 8), then accepts a second authenticated attachment with
the persisted issued credential. It advertises synthetic UES addresses and VIP,
completes private-network SH authentication and correlated operation 37/42
membership approval, and exchanges an encrypted heartbeat after joining. Other
regressions cover approvals arriving in either order, administrator-pending
membership, storage retries retaining the same issued identity, persistent
registration uncertainty, malformed UES addresses, host-count limits and
deduplication. No transport or cryptography is stubbed in this localhost control
test. The coordinator itself is a synthetic Java endpoint, so independent Rust
fixture checks remain the evidence for cryptographic wire parity.

The fixtures under `src/test/resources/crypto/` are unchanged copies of the
canonical synthetic fixtures. No real identity, credential, server capture,
VPN endpoint or Minecraft session was used. These checks establish fixture
parity and local identity behavior; they do not establish live service or
end-to-end interoperability. POSIX permission behavior is implemented but was
not executed on Linux in this validation.

## Isolated live service validation

The explicit developer script `scripts/LivePrivateNetworkSmoke.java` was also
run against the real service using two newly created, disposable Java test
identities. It never imports an existing user profile. A unique private test
network and a random password held only in memory were used for each run.

The live check successfully created a private network, authenticated the second
identity's password join, discovered both authenticated eligible peers, negotiated
a direct TCP peer path through the real coordinator and incoming-offer hub,
completed mutual SH and service negotiation, and verified exact Ethernet frames
in both directions. The second test identity explicitly left the network, then
the creator deleted the test network. Both attachments and peer transports were
closed. Sanitized evidence is retained in the task workspace's
`work/live-private-evidence.txt`; credentials remain only in owner-private test
identity files outside the repository.

This live check establishes Java-to-Java service and direct TCP interoperability.
It does not establish official Radmin-client gameplay, direct UDP/relay behavior
against real peers, or Minecraft authentication/gameplay.
