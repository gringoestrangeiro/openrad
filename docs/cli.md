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

## Forward application traffic

The bounded CLI requires an explicit allowlist for TAP traffic. Substitute the peer ID of a device you control or whose owner agreed to the test:

```sh
sudo -v
./target/release/openrad run --identity profiles/main/identity.json --network 'Example Network' --traffic-peer PEER_ID --tap --duration 90 --output reports/tap-1
```

Repeat `--traffic-peer` as needed. TAP becomes available when the selected traffic peers have connected. The CLI installs host routes for those peers, forwards approved application traffic, and removes the interface on shutdown. The desktop instead provides ordinary joined-network LAN forwarding.

Reports include per-peer `DirectTcp`, `DirectUdp`, or `Relay` paths, authentication status, connection attempts, errors, and counters. They may contain peer IDs, endpoint addresses, and network names. They do not contain session keys or packet payloads. The identity export from `provision` is intentionally separate and confidential.
