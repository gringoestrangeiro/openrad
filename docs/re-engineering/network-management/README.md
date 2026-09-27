# Private networks and administration — RE handoff

Static IDA evidence, 2026-09-27. Vendor code was not run or patched. Engineering
uses these findings in the native Rust client. Function names are analysis names,
not verified debug symbols. The older `openrad/docs/7bb99jr.md` supplied entry
points, but some addresses belong to another build and were not reused blindly.

- Service SHA256: `546efad9ab3ff85c2a50d61524d754bec94a3f3e30d6ae8addef481a1e8daa1e`.
- ROL SHA256: `6ba7dfecd38183d4bff0fc53f1c8c05d25e636077fe753b1220912aa58938177`.
- SHelper SHA256: `d89daf6bcd5cafa3c7f6173f835ccf045baf8e7134f868819db6fd7615959ac4`.

Service/ROL were inspected via IDA Pro MCP (ports 13338/13340). The older SHelper
MCP instance timed out twice; a separate temporary headless IDA database supplied
static decompilation and assembly for its exported verifier generator.

## Verified request specification

**GREEN** means decompilation, assembly and scalar widths agree. Runtime
interoperability is recorded separately after engineering; static verification
is not a claim of behavioral equivalence.

- **GREEN** SVC `0x4604c0`, ROL `0x100181f0`/`0x10018280`: operation 52,
  body `0x1319`, action BE32 `0x0100030c`, request BE64 `0x02000340`,
  context BE32 `0x0100034a`. SVC vtable slots and return instructions map create=1
  (`0x40a240`), kick=4 (`0x40a280`), grant admin=8 (`0x40a270`), revoke=9
  (`0x40a2a0`), delete=7 (`0x40a250`, vtable `0x51a13c`). Delete serializes
  GUID16 only (`0x40ee00`), and its success reader is `0x416120`. The member actions append GUID16 `0x0d000309` and RID BE64
  `0x020001e1` (`0x40ee80`, `0x40eed0`, `0x40ef50`).
- **GREEN** create adds `0x1314`: bool32 `0x0b000308`, name UTF16BE with
  `0001` terminator `0x03000306`, verifier blob `0x0a000307` (`0x4c4e90`).
  `0x4ca210` packs LE32 salt length, raw salt, minimal BE verifier.
  SHelper `0x1006ade0` -> `0x10066500` uses a random 32-byte salt and SH verifier.
- **GREEN** the SH identity is CRC64 over the network name's UTF16LE bytes without
  a terminator, initial/final all-ones, polynomial `0x42f0e1eba9ea3693`
  (SVC `0x4ca6b0`/`0x5100b0`, table bytes included; ROL equivalents
  `0x100ed6a0`/`0x10106860`). Password bytes are UTF8 (`0x47c450`). SH's
  identity byte conversion and verifier equations match the existing crypto
  research (`openrad/work/NOTES_crypto.md`, `0x10060340`/`0x100602f0`).
- **GREEN** passworded join starts op39/body `0x131c` with name, bool
  `0x0b00033f`, request ID, `0x010003be` sequence and SH blob `0x0a00030e`
  (`0x10017ae0`). Continuations use the same op/body with only blob + sequence
  (`0x10017a20`). Incoming op40/body `0x131c` carries status `0x02000303`
  (BE64), SH data and optional correlation fields
  (`0x1008c7f0`). `0x10090d30` advances SH and sends continuations.
- **GREEN** op37/body `0x131a` echoes action and request; management commands
  echo context. Presence of `0x010001d2` means failure, including value zero
  (`0x4169d0`). Create success contains network `0x1315` (`0x4160a0`). Member
  action success contains GUID and RID (`0x416190`, `0x4162b0`, `0x416320`).
- **GREEN** membership status is BE32 `0x0100030a` within `0x1318` or `0x131e`
  (`0x4c69e0`). NodeRemoved `0x131d` carries GUID/RID (`0x4d8260`);
  NetworkDeleted `0x132e` carries GUID (`0x4d91a0`). Own membership role is also
  read from network `0x1315` / update `0x1323` (`0x4cb980`). Status values
  0=Applicant, 1=Member, 2=Admin are confirmed by the string-selection assembly
  at `0x447870` (strings `0x51df34`, `0x51df2c`, `0x51df24`).

## Runtime reconciliation

The service was exercised with the Rust CLI and two newly provisioned owned
identities. No vendor executable or DLL was executed. These observations are
**GREEN for the tested live service**, not a promise for all server versions.

- Private JOIN uses boolean `0x0b00033f = 0`; public JOIN uses 1. Creation with
  `0x0b000308 = 0` produced a password-protected network absent from public search.
  The broader semantics of the create boolean remain **YELLOW**; no alternate
  creation modes are exposed.
- Successful op40 SH progress uses wire status **zero**. The all-ones initial
  bytes of ROL globals `0x10186f84` / `0x10186f88` are not the runtime wire
  success code. An early interpretation of those bytes was corrected in code,
  this handoff, and the IDA comment. The parser itself is verified at
  `0x1008c7f0` / `0x100a7c00`.
- Wrong-password refusal arrived as an SH index-0 record without a payload
  (`1000000400000000`) inside a zero-status op40. It is rejected explicitly.
  A correct password produced SH parameters (245 bytes), challenge (204), and
  a verified server proof (32), then op37, op41, op42, and further updates.
- JoinApproved op42 lists *existing* network members. It need not contain the
  joining device's RID. The implementation matches its GUID against the named
  network in the correlated op37 response, and verifies SH M2 first. Requiring
  our RID in this packet caused a false timeout despite persisted membership;
  the regression test covers this ordering and shape.
- Admin grant/revoke changes persisted after fresh attachment. A newly granted
  administrator successfully revoked and restored the original creator's admin
  role. An ordinary member's revoke request was refused with server error 20.
- The last administrator's leave request was refused with server error 19.
  Delete action 7 succeeded and a fresh attachment confirmed empty membership.

See [verification.md](verification.md) for the command sequence and test scope.

## Engineering requirements

Use one bounded state machine for CLI and UI. Keep password/proof values out of
Debug, reports and CLI arguments. Match responses before changing membership.
Preserve other shared networks when kicking a member; remove the local network
when our own RID is removed. Verify the network SH server proof before accepting
private join success. Timeouts leave the remote outcome unknown and require
reattachment. Test with fresh owned identities and a disposable private network.
