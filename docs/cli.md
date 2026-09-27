# Headless CLI

Build the CLI with `cargo build -p openrad-client --release --locked`, or build the whole workspace. Run it as a normal user. Use `openrad --help` and `openrad COMMAND --help` for the complete argument reference.

Examples below use ignored `profiles/` and `reports/` directories. Every `--output` value must name a **new directory**; the client refuses to overwrite an existing one. Parent directories must already exist.

## Provision an identity

```sh
mkdir -p profiles reports
./target/release/openrad provision --node-name my-device --output profiles/main
```

The result is `profiles/main/identity.json`. This is a reusable credential, not a report for public sharing. On Unix, newly created output directories have mode `0700` and files have mode `0600`.

Provisioning uses the bundled public server key and the default public registration endpoint. `--host IPV4_ADDRESS` overrides the registration endpoint. `--modulus FILE` overrides the public RSA modulus with raw big-endian bytes; normal use does not require it. Session commands use the server recorded in the saved identity.

## Inspect service state

```sh
./target/release/openrad connect --identity profiles/main/identity.json --output reports/connect-1
./target/release/openrad public-networks --identity profiles/main/identity.json --query example --output reports/search-1
./target/release/openrad peers --identity profiles/main/identity.json --output reports/peers-1
```

These commands print JSON results and write private operational reports. A service connection alone says nothing about the path to individual peers.

## Join and connect

Substitute the exact public network name returned by discovery:

```sh
./target/release/openrad join --identity profiles/main/identity.json --network 'Example Network' --output reports/join-1
./target/release/openrad run --identity profiles/main/identity.json --network 'Example Network' --duration 90 --output reports/run-1
```

Repeat `--network` for multiple joined networks. `run` connects eligible online peers with bounded concurrency. It does not generate test payloads. Without `--tap`, it authenticates peer channels and reports their paths without forwarding application Ethernet traffic. Session durations are bounded to 10–300 seconds; Ctrl-C cancels a run.

`--peer RID` restricts outgoing selection to particular peer IDs. `--passive` waits for incoming offers instead of initiating outgoing channels. Incoming offers must still match eligible members. `--incoming-transport all|tcp|udp|relay` restricts incoming transport attempts when diagnosing a controlled connection. Check command help for accepted values.

## Private networks and administration

The CLI reads private-network passwords from a UTF-8 file so they do not appear in process arguments. It removes one final LF or CRLF; all other characters are preserved. Use a 6–256 character password. For example, in Bash:

```sh
umask 077
read -r -s -p 'Network password: ' network_password
printf '\n'
printf '%s\n' "$network_password" > profiles/network-password.txt
unset network_password

./target/release/openrad create-network --identity profiles/main/identity.json --network 'My Private Network' --password-file profiles/network-password.txt --output reports/create-1
./target/release/openrad join --identity profiles/second/identity.json --network 'My Private Network' --password-file profiles/network-password.txt --output reports/private-join-1
```

Provision `profiles/second` separately for a second device. A password file is used with exactly one `--network`. Existing memberships are verified without rejoining. Network names and passwords are used exactly as entered; names are not trimmed or case-folded. Remove an unneeded password file after sharing it securely with the intended members.

`peers` includes `roles`, indexed by network ID and member RID: `0` means pending approval, `1` means member, and `2` means administrator. Use the member's RID from this result for administration:

```sh
./target/release/openrad grant-admin --identity profiles/main/identity.json --network 'My Private Network' --member MEMBER_RID --output reports/grant-1
./target/release/openrad revoke-admin --identity profiles/main/identity.json --network 'My Private Network' --member MEMBER_RID --output reports/revoke-1
./target/release/openrad kick --identity profiles/main/identity.json --network 'My Private Network' --member MEMBER_RID --output reports/kick-1
./target/release/openrad leave --identity profiles/second/identity.json --network 'My Private Network' --output reports/leave-1
./target/release/openrad delete-network --identity profiles/main/identity.json --network 'My Private Network' --confirm --output reports/delete-1
```

For these management commands, `--network` accepts an exact name or network ID. The server enforces permissions; an ordinary member cannot administer the network. Kicking affects only that network and does not ban someone who knows the password from joining again. Revoking admin leaves the target as an ordinary member. The last administrator may need to transfer administration or delete the network instead of leaving. Deletion removes it for every member and requires `--confirm`.

Successful commands return only after the correlated service acknowledgement. Private joins also check the server's password proof. A refusal exits nonzero; an approval request reports `approval_pending`, not `join_approved`. After a timeout, use a fresh `peers` connection to check the actual service state before retrying. Reports do not include network passwords or authentication proofs.

## Forward application traffic

The bounded CLI requires an explicit allowlist for TAP traffic. Substitute the peer ID of a device you control or whose owner agreed to the test:

```sh
sudo -v
./target/release/openrad run --identity profiles/main/identity.json --network 'Example Network' --traffic-peer PEER_ID --tap --duration 90 --output reports/tap-1
```

Repeat `--traffic-peer` as needed. TAP becomes available when the selected traffic peers have connected. The CLI installs host routes for those peers, forwards approved application traffic, and removes the interface on shutdown. The desktop instead provides ordinary joined-network LAN forwarding.

The CLI uses the same Ethernet validation as the desktop, including IPv4 group
traffic and gratuitous ARP requests/replies. Group frames fan out only to the
explicit traffic allowlist. It announces the local TAP IP/MAC with a gratuitous
ARP reply when the TAP or an allowed channel becomes ready. The CLI's host-only
routes remain unchanged; applications must select the TAP for group traffic.

Reports include per-peer `DirectTcp`, `DirectUdp`, or `Relay` paths, authentication status, connection attempts, errors, and counters. They may contain peer IDs, endpoint addresses, and network names. They do not contain session keys or packet payloads. The identity export from `provision` is intentionally separate and confidential.
