# Credits

The Windows adapter follows the public ABI from [OpenVPN TAP-Windows6](https://github.com/OpenVPN/tap-windows6) and its upstream client's device-access/I/O examples. The public `tap-windows.h` interface is also MIT licensed; its notice is retained in [TAP-Windows6-MIT.txt](docs/licenses/TAP-Windows6-MIT.txt). The offline Windows setup bundles the unchanged signed TAP-Windows6 9.27.0 x64 driver extracted from an official OpenVPN MSI, together with its complete GPL-2.0 upstream source/build scripts and notices. Installer device creation follows the documented Windows SetupAPI sequence also used by OpenVPN's tapctl; source references and package hashes are recorded in the Windows guide and driver provenance file. The installer uses NSIS, with its redistribution notices included.

**asimplestray** contributed approximately 95% of the cryptography work behind OpenRad, including the foundations used here for RSA session setup, the secure handshake, and encrypted channels.

The repository's [MIT license](LICENSE) also lists asimplestray as a copyright holder.

The desktop embeds **Noto Sans Regular**, Copyright 2022 The Noto Project Authors, to display Portuguese, Cyrillic, and Vietnamese text consistently. The font is distributed under the [SIL Open Font License 1.1](desktop/assets/OFL-NotoSans.txt); its upstream project is [notofonts/latin-greek-cyrillic](https://github.com/notofonts/latin-greek-cyrillic).

[Baptiste Rajaut (@baptisterajaut)](https://github.com/baptisterajaut) contributed a robustness audit and fixes for reliable UDP flow control, full-size Ethernet frames, display-name decoding, identity provisioning, reconnection, and TAP helper cleanup in [PR #1](https://github.com/gringoestrangeiro/openrad/pull/1).
