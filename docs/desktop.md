# Desktop and identities

Build the workspace and keep both binaries together. Start `openrad-desktop` from an ordinary user session with the credential store unlocked. The desktop's network operations run in a background engine so handshakes and packet forwarding do not block the interface.

## Connection and network management

On first connection, OpenRad provisions a new identity and saves it to the OS credential store. Later connections reuse that identity. The desktop supports connecting and disconnecting, listing public networks, joining and leaving networks, and viewing member status and transport information. Settings include the device name, automatic connection/reconnection, and UI scale.

The peer table distinguishes Direct TCP, Direct UDP, and Relay. These labels represent authenticated peer channels. They are separate from the overall service connection status. Incoming channels and outgoing channels use the same authentication and Ethernet forwarding checks.

The Linux desktop uses a `26.0.0.0/8` virtual LAN and group routes for broadcast and multicast. Disconnecting closes the TAP descriptor and removes the temporary interface and its routes.

## Reset identity

The **Reset identity** action opens a confirmation dialog explaining that the device identity and network memberships will change. Confirming it:

1. Disconnects the running session and closes the TAP interface.
2. Provisions a replacement through the normal registration flow.
3. Saves the new identity in the credential store only after provisioning succeeds.
4. Updates the active identity only after the credential-store write succeeds.

If provisioning fails, the existing saved identity remains intact. If provisioning succeeds but the credential store cannot save it, the replacement remains in memory for a storage retry; retrying does not provision another identity. Keep the window open until the credential store is unlocked and the save succeeds. The confirmation UI and close handling warn about an unsaved replacement.

New identities do not inherit the previous identity's memberships. Resetting is not a way to recover access to a lost identity.

## Profiles and imports

The default profile directory comes from the platform's application-data location. `--data-dir PATH` selects an independent profile. The canonical profile path identifies the corresponding credential-store entry, so moving a profile directory does not automatically move its saved identity.

```sh
./target/release/openrad-desktop --data-dir ./profiles/alternate
```

`--identity FILE` imports a saved CLI identity into that profile's credential store. An import cannot overwrite a different identity already saved in the same profile. Identity files contain reusable credentials; store them outside source control and delete redundant copies only after verifying the import.

Settings are written separately from credentials. A profile lock prevents two desktop instances from using the same profile concurrently. Do not run two clients with the same identity at the same time.

For controlled traffic checks, `--traffic-peer RID` can be repeated to limit desktop application traffic to selected peers. Ordinary desktop mode allows traffic to eligible joined-network members.
