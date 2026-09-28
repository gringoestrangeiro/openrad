# Changelog

## 0.6.0 — 2026-09-28

### Fixed

- Private-network joins now authenticate with networks created by the official Radmin VPN client. The network password's UTF-8 wire representation includes its terminating NUL byte; the same correction applies when creating a private network.

### Desktop

- Added settings for the maximum reconnect attempts and initial retry delay, with bounded exponential backoff.
- Added startup page, traffic display and units, offline-peer visibility, peer sorting, recent activity count, and developer diagnostic display preferences.
- Added Save, Discard, and Restore defaults actions for preferences. Existing settings files receive defaults for the new fields.

### Validation

- Confirmed private-network joins through both development and release CLIs using the test network, followed by a fresh membership check.
- Passed the default workspace tests, formatting, Clippy, and the Linux release build. The privileged TAP test remains opt-in.
