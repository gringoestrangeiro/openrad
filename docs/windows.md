# Windows with TAP-Windows6

OpenRad **1.0.0** includes **experimental Windows x64 support**. Windows has
been tested only on **Windows 10** so far; the tester reports it working perfectly
in that setup after the native Direct3D/WARP startup fix. Windows 11 and other
Windows environments still need validation. Please [open an issue](https://github.com/gringoestrangeiro/openrad/issues/new)
with your configuration, results and relevant logs so Windows support can become
stable in a later release.

Both the desktop and persistent CLI use the shared VPN engine. The Layer 2
interface uses the official OpenVPN TAP-Windows6 driver, component ID `tap0901`.
No Radmin driver, Wintun driver, OpenVPN service, or vendor VPN runtime is used.

The published binaries were built on Linux. Automated Windows test executables
ran under Wine, but the release builder did not exercise a native Windows
installation or TAP driver. The Windows 10 success and performance figures are
user-provided test feedback. [Release details](releases/1.0.0.md) distinguish this
feedback from Linux verification and list the remaining validation work.

[Windows settings UI preview](screenshots/windows-settings-linux-preview.png) was
rendered from a synthetic headless Linux fixture using the Windows presentation
branch. It verifies the setup guidance and layout, not Windows runtime behavior.

## Install and open OpenRad

Extract the new ZIP and double-click **OpenRad-Setup.exe**. Approve Windows' UAC
prompt. Setup works offline: it installs the CLI and desktop, stages the official
signed TAP-Windows6 9.27.0 x64 driver, creates/enables a dedicated adapter named
`OpenRad`, adds Start menu/Desktop shortcuts and an uninstaller, and then opens
the desktop automatically. No separate driver download, adapter rename or
PowerShell command is needed for normal installation.

The default application directory is `C:\Program Files\OpenRad`. Fonts and
translations are embedded. The executables import only Windows system DLLs.
The desktop automatically tries Direct3D 12 hardware rendering, Windows' built-in
WARP CPU renderer, then OpenGL. VirtualBox with 3D acceleration disabled
can use the software fallback without OpenGL or additional graphics DLLs.
Setup neither
provisions an identity nor connects to a VPN. Use the desktop to create/import
your identity and connect when ready.

When the same application files, uninstall registration and compatible enabled
TAP adapter are already installed, setup displays **OpenRad is already installed and set up**, then opens
or focuses the desktop. It does not create another adapter. Missing/modified
application files, an old driver, or a disabled/renamed owned adapter trigger
installation/repair. A physical/other-driver adapter named OpenRad is rejected.
An existing verified TAP adapter already named OpenRad may be reused; another
VPN's differently named TAP adapter is left alone.

The corrected 2026-09-30 installer handles string GUIDs and reads only the
selected adapter's driver key, obtained through SetupAPI. Earlier installers
could fail during naming/enabling with “Windows has not finished creating the
TAP adapter” or a `Get-ChildItem` registry access-denied error. Close the failed
installer and run the corrected setup EXE; it replaces the worker and script
automatically. Normal UAC administrator approval is sufficient for the intended
setup flow. SYSTEM elevation and changes to protected registry ACLs are not
needed for these fixes.

For CLI use, suppress desktop launch on both first and repeat runs:

```powershell
.\OpenRad-Setup.exe --no-launch
# Optional unattended mode, with the normal UAC requirement:
.\OpenRad-Setup.exe /S --no-launch
# Explicit repair even when setup currently passes its checks:
.\OpenRad-Setup.exe --repair --no-launch
```

A custom application folder uses NSIS's standard `/D=PATH` switch, last and
without surrounding quotes, for example:

```powershell
.\OpenRad-Setup.exe --no-launch /D=D:\Apps\OpenRad
```

Keep custom installation folders protected from modification by other users.
OpenRad runs with elevation on Windows, so its executables and DLLs must be
trusted when launched. Setup and recovery resolve PowerShell through Windows'
system-directory API, load only system modules, and run embedded scripts.
Release checks similarly resolve the system `curl.exe`; a `curl.exe` in the
working directory or PATH is not used.

The setup EXE is self-contained and can be carried to another PC. TAP is a
Windows kernel driver, so each PC needs its own setup/UAC approval. Copying just
the installed application folder to a new PC does not install that PC's driver.
No internet is needed by setup; using the VPN afterward requires connectivity.

Use your usual account's UAC elevation. Elevating with another account selects
that account's profile and Windows Credential Manager identity. The desktop
requests elevation automatically on ordinary GUI startup; `--help`, `--version`
and headless test harnesses remain unprivileged. This build still elevates the
whole VPN application. A conflicting official Radmin VPN adapter triggers a
temporary SYSTEM recovery task as described below. Linux's separate TAP helper
is unchanged.

Launch **OpenRad Desktop** or **OpenRad CLI** from the Start menu afterward. The
CLI shortcut opens an elevated PowerShell in the installation folder:

```powershell
.\openrad.exe --help
.\openrad.exe init --node-name my-windows-device
.\openrad.exe start
.\openrad.exe status
.\openrad.exe peers
.\openrad.exe join 'Your test network'
.\openrad.exe stop
```

CLI data defaults to `%LOCALAPPDATA%\openrad`; `--data-dir PATH` selects a
dedicated profile. Files inherit an ACL granting access to the current user and
SYSTEM. Desktop identities use Windows Credential Manager; CLI-initialized identities
retain their private file storage. The desktop and CLI share one profile and
background session, so either frontend can be opened first and both can remain
open. The TAP device is exclusive across different profiles. The CLI background process is per-user, not an installed
Windows Service. Closing a terminal leaves it running; `openrad stop` shuts it down.
For other commands see [CLI usage](cli.md) and [desktop usage](desktop.md).

If Windows requests a driver restart, setup reports it and exits with code 3010;
restart Windows and run the setup EXE again. Close/disconnect an active desktop
and stop CLI background processes before repair, upgrade or uninstall. Use
**Uninstall OpenRad** or Windows Installed apps to remove application files and
only an adapter created by this setup. Shared TAP driver packages, manually
adopted adapters, profile files and stored identities are retained. Unknown files
added to the application directory are retained as well.

## Switching from official Radmin VPN

Both clients use addresses in `26.0.0.0/8`. Starting with 0.9.5, a desktop or CLI
connection detects an enabled **Famatech Radmin VPN Ethernet Adapter** with a
`26.x.x.x` address and automatically resolves that conflict before configuring
OpenRad's TAP interface. The adapter's display name may have been renamed;
recovery identifies the official driver description and selected interface.

Using the application's existing administrator elevation, OpenRad registers a
temporary local Task Scheduler task as SYSTEM. Its embedded worker stops the
service whose executable is `RvControlSvc.exe`, force-terminates any remaining
`RvControlSvc.exe` processes, then disables the selected official adapter. This
order prevents the running service from immediately reenabling the driver.
If the service refuses to stop, or its process cannot be terminated, recovery
still tries to disable the selected official adapter. It never force-kills a
service whose stop request was refused, changes service startup/recovery
settings, or disables another adapter. The worker checks the selected GUIDs and
administrative state for up to ten seconds, requiring two seconds of continuously
disabled state before reporting success. A disconnected but enabled adapter
does not count as recovered.
OpenRad waits for the worker, removes the task, and rechecks all active adapter
addresses for up to ten seconds before continuing the same connection attempt.
The worker has a thirty-second execution limit; scheduler completion is awaited
for up to forty seconds. A failed recovery leaves the connection failed and
records an error in the usual connection/startup logs. A temporary ACL-protected
named pipe carries a bounded worker error back to the application, including
adapter-disable failures and refused service-stop result codes. Errors appear
as readable text rather than PowerShell CLIXML/module-initialization progress.

This recovery is offline and uses built-in Windows PowerShell and Task Scheduler.
Microsoft documents [SYSTEM task principals](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtaskprincipal),
[service stop results](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/stopservice-method-in-class-win32-service),
and [adapter disabling](https://learn.microsoft.com/en-us/powershell/module/netadapter/disable-netadapter).
The VPN application keeps the ordinary user's profile and credentials. There is
no PsExec download, SYSTEM application launch, driver removal, or permanent
change to the Radmin service's startup setting. OpenRad leaves the official
adapter disabled when disconnecting; its service remains stopped if recovery
could stop it. To switch back,
disconnect/close OpenRad, enable **Radmin VPN** in Windows network settings, and
start its service or restart Windows. Starting official Radmin while OpenRad is
connected can recreate a routing conflict.

Other adapters using `26.x.x.x`, and stale/static addresses on the OpenRad TAP,
still report a conflict. The automatic recovery is limited to the official
Famatech adapter. Disabled adapters' retained addresses are ignored. The new
SYSTEM recovery has synthetic regression coverage and cross-build validation;
it still needs a real Windows migration test with official Radmin installed.

TAP setup runs on a temporary worker thread. Its first overlapped read starts
on the engine thread after handoff: Windows cancels pending I/O from a thread
when that thread exits, which otherwise produces `ERROR_OPERATION_ABORTED`
(OS error 995) on the first read. Subsequent reads and cancellation retain owned
buffers through completion. See Microsoft's [thread exit behavior](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-exitthread).

## Graphics compatibility

Normal shortcuts and the installer use automatic renderer selection. A failed
graphics initialization is retried before creating the VPN backend or importing
an identity. A failure after the desktop has started does not restart a running
VPN session. The previous `egui_glow requires opengl 2.0+` error can therefore
fall back to Direct3D instead of preventing startup.

To select a renderer explicitly, run the installed desktop:

```powershell
& 'C:\Program Files\OpenRad\openrad-desktop.exe' --renderer software
& 'C:\Program Files\OpenRad\openrad-desktop.exe' --renderer opengl
& 'C:\Program Files\OpenRad\openrad-desktop.exe' --renderer wgpu
```

`software` selects only a CPU adapter (WARP on Windows); `opengl` selects the
existing OpenGL renderer; `wgpu` selects a Direct3D 12 hardware adapter. Explicit
selections do not switch to another renderer. The default is `--renderer auto`.
Rendering choice does not change the TAP driver or networking implementation.

This build targets Windows 10 version 1709 or later and Windows 11, x64.
[Microsoft's WARP guide](https://learn.microsoft.com/en-us/windows/win32/direct3darticles/directx-warp)
documents CPU rendering and Direct3D 12 support supplied by Windows. The FXC
shader compiler uses Windows' `d3dcompiler_47.dll`; no DXC/Agility SDK download
or optional Graphics Tools debug layer is needed. Software rendering can use
more CPU and draw more slowly than hardware rendering. Modified Windows images
with missing system graphics components are outside the supported setup.

The tester confirmed successful desktop startup on Windows 10 after native rendering was preferred. Direct3D/WARP runtime behavior was not tested by the Linux release builder.
Test a VM with 3D disabled, then an OpenGL-capable system; resize, minimize and
restore the window and check text/input on each. The native Linux renderer and
Linux CPU-rendering check exercise shared UI/rendering code, not Windows APIs.

## Startup and crash logs

This debugging build automatically writes a log before graphics initialization:

```text
%LOCALAPPDATA%\OpenRad\logs\desktop-startup.log
%LOCALAPPDATA%\OpenRad\logs\desktop-stderr.log
```

Paste `%LOCALAPPDATA%\OpenRad\logs` into Explorer's address bar after reproducing
a crash. Send both files. They contain process IDs, elapsed times, elevation and
profile-lock checkpoints, renderer/adapter details, first UI checkpoints and
Rust panic backtraces. A small parent process records the desktop child's exit
code, including native crashes where Rust's panic hook cannot run. Only the child
creates the UI and VPN backend; the parent exits when the desktop exits. An
unexpected nonzero exit now produces a dialog pointing to the startup log.

The previous build had connection diagnostics under
`%LOCALAPPDATA%\OpenRad\openrad\data\diagnostics\connection.jsonl`. Those start
after App/backend creation and cannot reliably diagnose a graphics startup
crash. A custom `--data-dir` changes connection-log placement; early startup logs
stay in the location above. If that location cannot be written, early logs fall
back to `%TEMP%\OpenRad\logs`. Alternate-admin-account elevation uses that
account's local app-data folder. `OPENRAD_LOG_DIR` (or `OPENRAD_DESKTOP_LOG_DIR` for desktop only) can select an explicit
startup log folder for controlled debugging.

Logs omit command-line argument values, credentials, packet contents and memory
dumps. Graphics-library logging is limited to graphics modules. Library messages
are capped at 4 MiB; the next launch keeps one previous startup log. Checkpoints
are written synchronously so a process crash does not lose a queued final stage.
This adds diagnostics; the cause of the reported white-window exit remains
unconfirmed until a Windows log is collected.

### Native exception 0x80070057 on the first frame

The supplied Windows log confirms administrator elevation, completed App
creation and a completed first UI pass, followed by exit `0x80070057` without a
Rust panic. Its OpenGL implementation was Mesa 26.2.3 over D3D12 on the Microsoft
Basic Render Driver. `0x80070057` is [E_INVALIDARG](https://learn.microsoft.com/windows/win32/seccrypto/common-hresult-values).
The log does not establish the native faulting DLL or API; the OpenGL translation
path is a suspect, rather than a confirmed cause.

Windows automatic startup now tries native Direct3D 12 hardware, then native
WARP CPU rendering, and finally OpenGL if both native initialization paths are
unavailable. This bypasses that OpenGL translation path when a native renderer
is available. Explicit `--renderer opengl` remains supported; Linux startup order
is unchanged. Fallback still occurs only before App creation and does not restart
an already-running VPN backend after a native fault.

The shared startup logger now installs a best-effort Windows unhandled exception
filter for desktop and CLI processes. It records exception code, Windows thread
ID, instruction address, module path/offset and a handler backtrace, then preserves
the prior Windows exception policy. It excludes exception parameters, register
values and process memory. Native code may replace the filter, terminate directly,
fail fast, or corrupt the process enough to prevent logging; the desktop parent
still records the final exit code in those cases. Backend credential-store startup
also has checkpoints to distinguish a backend failure from a rendering failure.
No native exception callback or Direct3D/WARP runtime has been tested on Linux.

## CLI diagnostics and error 5

Early CLI logs are written automatically, including before profile loading:

```text
%LOCALAPPDATA%\OpenRad\logs\cli-startup.log
%LOCALAPPDATA%\OpenRad\logs\cli-daemon-startup.log
%LOCALAPPDATA%\OpenRad\logs\cli-launcher.log
%LOCALAPPDATA%\openrad\service.log
%LOCALAPPDATA%\openrad\diagnostics\connection.jsonl
```

`cli-launcher.log` is added by the CLI/Diagnostics shortcut; direct `openrad.exe`
commands still produce the two startup logs. A custom `--data-dir` moves
`service.log` and connection diagnostics. The logs record the account SID,
administrator token, safe command name, daemon PID/readiness/early exit, panic
backtraces and each TAP configuration stage. They omit command arguments and
reply data. TAP failures also log their full error chain; the preceding TAP stage
identifies the Win32/driver operation that failed.

The runtime previously scanned every network-class registry child. A protected
unrelated child could cause error 5 even as administrator. Runtime discovery now
uses SetupAPI device software keys with query-only access, matching the exact
TAP component and OpenRad name. Other causes of error 5, including a denied TAP
device open or IPv4 configuration, still require Windows investigation.

The driver's advanced **MAC Address: Absent** option means no manual override.
TAP-Windows6 generates its permanent MAC from the device instance and uses it
when no override exists ([official driver source](https://github.com/OpenVPN/tap-windows6/blob/9.27.0/src/adapter.c)).
The new logs record the six-byte MAC returned by the documented TAP GET_MAC
IOCTL. Do not set an arbitrary override to diagnose this. Automatic IPv4 settings
while idle are expected: OpenRad assigns its VPN address, MTU and ActiveStore
routes during connection, then removes/restores them on disconnect. A failure
before address assignment leaves the idle adapter without a VPN address.

## Explicit SYSTEM diagnostic mode

Normal setup remains offline and starts the ordinary administrator desktop.
Launching the whole application as SYSTEM is opt-in and separate from the
automatic adapter recovery above. The Diagnostics shortcut opens an elevated
CLI with the normal profile. From the ZIP or installed directory, use:

```bat
Debug-OpenRad.cmd -System
Debug-OpenRad.cmd -System -Target Desktop -Renderer software
```

The SYSTEM launcher downloads [official Microsoft PsTools](https://download.sysinternals.com/files/PSTools.zip)
on its first use, verifies a valid Microsoft Authenticode signature on
`PsExec64.exe`, and invokes [documented PsExec `-s -i SESSION`](https://learn.microsoft.com/en-us/sysinternals/downloads/psexec)
locally in your interactive session. Its original license dialog is shown;
OpenRad does not silently accept it. PsExec is not redistributed: its included
Sysinternals license prohibits publishing it for others to copy. Normal setup
and normal debugging do not download it.

For offline SYSTEM diagnostics, download/extract that official ZIP separately
and provide the verified utility:

```bat
Debug-OpenRad.cmd -System -PsExecPath "C:\Tools\PsTools\PsExec64.exe"
```

Close the ordinary desktop and run `openrad stop` in the ordinary CLI first so
the two processes do not compete for the same TAP adapter. SYSTEM uses separate
profiles and credentials. Its logs are in
`%PROGRAMDATA%\OpenRad\system-diagnostic\logs`, and its profiles in the sibling
`cli` / `desktop` directories; only SYSTEM and administrators may access these.
The console shows its effective account. In the SYSTEM CLI, `openrad` is a
wrapper that passes the isolated profile automatically; initialize/import an
identity there before connecting, for example:

```powershell
openrad init --identity "C:\Users\YOUR_USER\AppData\Local\openrad\profile\identity.json"
openrad start
openrad status
openrad retry-interface
openrad stop
```

Use your real exported CLI identity path in that example; desktop credentials
are in the user's Windows Credential Manager and are not copied automatically.
SYSTEM mode is a diagnostic comparison; successful normal administrator TAP
operation still needs verification. It does not repair a graphics-driver crash.
No Windows runtime verification of this mode has been performed on Linux.

## Bundled driver provenance and licenses

The installer embeds the unchanged, Microsoft-signed Windows 10/11 x64
TAP-Windows6 **9.27.0.0** package extracted from the official
[OpenVPN 2.6.22 I001 amd64 MSI](https://build.openvpn.net/downloads/releases/OpenVPN-2.6.22-I001-amd64.msi).
Only the TAP `.inf`, `.cat` and `.sys` are distributed from that package; OpenVPN
userspace programs and other drivers are not installed. The prior 9.24.7
standalone package predates TAP's 2024 integer-overflow fix, so this installer
uses 9.27.0. The official source tag `9.27.0` resolves to
`0cad8664c2a51832df61f2e1853b6da317d1c129`.

TAP-Windows6 is GPL-2.0. Its complete corresponding upstream source tree,
build/installation scripts, GPL license and upstream notices are included as
`driver-source/tap-windows6-9.27.0-source.tar.gz` and `licenses/TAP-Windows6/`.
They are embedded in the setup EXE and installed with the application.
`DRIVER-PROVENANCE.json` records the upstream URL/commit and package/file hashes.
NSIS and Rust dependency notices are also included. No Radmin driver is used.

Linux inspection verifies extraction, version, hashes and certificate contents;
Windows must verify the driver catalog's trust and accept/install it through
SetupAPI. The setup EXE and OpenRad executables themselves are unsigned.

## Tests to run on Windows

1. On a clean Windows x64 machine, run setup with no network connection and verify
   file installation, exactly one dedicated TAP adapter, shortcuts and automatic
   desktop launch. Close the desktop and run setup again; check the already-installed
   notice and launch. Repeat with `--no-launch` and `/S --no-launch` and verify no
   desktop is opened. Test repair after removing a file, disabling/renaming the
   owned adapter, and upgrading an older installation. Check UAC cancellation,
   a custom folder, another VPN adapter, reboot-required handling and uninstall.
   Then run the bundled local CLI smoke test from the extracted folder:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Test-Windows.ps1
   ```

   The execution-policy override applies only to this invocation. Review the
   script first. It uses a temporary isolated profile and synthetic identity,
   tests the CLI's versions/help, import, detached service, local named pipes,
   offline-command rejection and stop/restart, and cleans up. It does not install
   a driver, provision an account, connect to a public server or create a TAP.
   Its default binary directory is `C:\Program Files\OpenRad`; use
   `-InstallDirectory PATH` for a custom installation.
2. Launch the desktop as administrator. Check startup, font/language rendering,
   provision or explicitly import your identity, restart, and verify the same
   identity is reused from Windows Credential Manager. Check preference saving
   and the single-profile lock. A normal unelevated GUI launch should request UAC automatically; cancellation
   should produce a visible error without changing any addresses or routes.
3. Connect with TAP enabled. Confirm the adapter's IPv4 address matches the UI
   and inspect its MTU and routes:

   ```powershell
   Get-NetIPAddress -InterfaceAlias 'OpenRad' -AddressFamily IPv4
   Get-NetIPInterface -InterfaceAlias 'OpenRad' -AddressFamily IPv4 |
     Format-Table InterfaceAlias,NlMtu,InterfaceMetric
   Get-NetRoute -InterfaceAlias 'OpenRad' -AddressFamily IPv4 |
     Format-Table DestinationPrefix,NextHop,RouteMetric
   ```

   Expect the service-issued `26.x.x.x/8`, MTU 1500, the connected `26.0.0.0/8`
   route, and on-link `224.0.0.0/4` and `255.255.255.255/32` routes. Confirm the
   physical adapter's default gateway and DNS configuration remain intact.
4. With an owned peer, test ARP resolution, ping, bidirectional TCP and UDP,
   full-size IPv4 packets, an application that uses directed/limited broadcasts,
   and IPv4 multicast. Test Windows-to-Linux and Windows-to-official-client links,
   and exercise Direct TCP, Direct UDP and relay where your environment allows.
   Check actual traffic counters in addition to connection indicators. Windows
   Firewall can block peer listeners or ICMP; use appropriate application/ICMP
   rules on the test interfaces instead of disabling the firewall. Applications
   bound explicitly to a physical adapter may need their interface set to OpenRad.
5. Disconnect, reconnect, retry interface setup, stop the CLI, close the desktop,
   and confirm session addresses/routes are removed and the installed adapter
   remains. Also test adapter disabled/missing/in use, driver removal, setup
   failure, multiple profiles, network membership changes and reconnect recovery.
   Check that another user's CLI cannot control your local pipe. The pipe rejects
   remote clients and has a current-user/SYSTEM ACL, but those access checks still
   need Windows verification.
6. Separately test forced termination and reboot recovery. Before each networking
   test, disconnect the official Radmin client and other VPNs using `26.0.0.0/8`.
   OpenRad refuses conflicting existing `26.x.x.x` addresses and static IPv4
   configuration on its dedicated adapter.

If you have a Windows Rust toolchain, `cargo test --workspace --locked` also
includes the unprivileged named-pipe round-trip test and portable packet/transport
tests. Linux cross-compilation of those tests verifies compilation/linking only.

## Limits and recovery

- Windows support is experimental and has been tested only on Windows 10 in the tester's setup. Other Windows configurations still need validation. The setup and application executables are unsigned;
  Windows may display an unknown-publisher/SmartScreen warning.
- This artifact is x64 only. Windows ARM64, 32-bit Windows, Windows 7/8 and macOS
  data planes are outside this deliverable. IPv4 Ethernet/ARP, a 1500-byte MTU,
  broadcast and multicast are supported by the engine; IPv6 forwarding and VLAN
  frames are not implemented.
- Whole-process elevation is required on Windows. The Linux privilege boundary
  is unchanged. Windows peer socket waits retain the portable bounded polling
  path; the TAP/engine wait uses Windows events. Linux CPU benchmark results do
  not establish Windows performance.
- A normal disconnect or a setup error cancels and completes pending TAP I/O,
  removes addresses/routes created by this instance and restores the dedicated
  adapter's previous IPv4 MTU/metric and any adjusted group-route metrics. The
  original physical interfaces are not configured. Windows group-route/interface
  metrics are temporarily 5 so ordinary LAN discovery can prefer the VPN; verify
  application multicast-interface selection in your Windows tests.
- Forced termination cannot run Rust destructors. The TAP driver closes its
  handle, but ActiveStore addresses/routes or temporary metrics may remain until
  cleanup/reboot. A subsequent connection refuses stale `26.x.x.x` addresses.
  Stop all OpenRad processes and remove only the stale VPN address from the
  verified dedicated TAP adapter:

  ```powershell
  $tap = Get-NetAdapter -Name 'OpenRad'
  if ($tap.InterfaceDescription -notlike 'TAP-Windows Adapter V9*') {
    throw 'OpenRad is not a TAP-Windows Adapter V9; stop and inspect it.'
  }
  Get-NetIPAddress -InterfaceIndex $tap.ifIndex -AddressFamily IPv4 |
    Where-Object IPAddress -Like '26.*' |
    Remove-NetIPAddress -Confirm:$false
  ```

  Reboot before the next test to rebuild transient routes and interface state.
  The installed driver and adapter remain installed. Preserve the profile to
  keep your identity. Do not remove another VPN's address to work around a conflict.

## Implementation sources and Linux cross-build

The implementation was checked against upstream TAP-Windows6 commit
`0cad8664c2a51832df61f2e1853b6da317d1c129` and OpenVPN client commit
`0af3dd397ececd733e501cf264ed2240c191f34e`:

- [TAP-Windows6 README: setup and driver installation](https://github.com/OpenVPN/tap-windows6/blob/0cad8664c2a51832df61f2e1853b6da317d1c129/README.rst).
- [Public header: device path, registry keys and IOCTLs](https://github.com/OpenVPN/tap-windows6/blob/0cad8664c2a51832df61f2e1853b6da317d1c129/src/tap-windows.h).
- [Driver device access, IOCTL handling and cleanup](https://github.com/OpenVPN/tap-windows6/blob/0cad8664c2a51832df61f2e1853b6da317d1c129/src/device.c),
  [outbound read path](https://github.com/OpenVPN/tap-windows6/blob/0cad8664c2a51832df61f2e1853b6da317d1c129/src/txpath.c), and
  [inbound write path](https://github.com/OpenVPN/tap-windows6/blob/0cad8664c2a51832df61f2e1853b6da317d1c129/src/rxpath.c).
- [OpenVPN's documented-in-source device opening and overlapped packet I/O](https://github.com/OpenVPN/openvpn/blob/0af3dd397ececd733e501cf264ed2240c191f34e/src/openvpn/tun.c).
- [OpenVPN tapctl: root-device creation, exact-device driver installation and removal](https://github.com/OpenVPN/openvpn/blob/0af3dd397ececd733e501cf264ed2240c191f34e/src/tapctl/tap.c),
  [TAP package staging](https://github.com/OpenVPN/tap-windows6/blob/9.27.0/msm/installation.c),
  Microsoft [SetupCopyOEMInf](https://learn.microsoft.com/en-us/windows/win32/api/setupapi/nf-setupapi-setupcopyoeminfw),
  [SetupDiCreateDeviceInfo](https://learn.microsoft.com/en-us/windows/win32/api/setupapi/nf-setupapi-setupdicreatedeviceinfow),
  [SetupDiCallClassInstaller](https://learn.microsoft.com/en-us/windows/win32/api/setupapi/nf-setupapi-setupdicallclassinstaller),
  and [DiInstallDevice](https://learn.microsoft.com/en-us/windows/win32/api/newdev/nf-newdev-diinstalldevice).
- Microsoft [SetupDiGetDeviceRegistryProperty: the selected device's SPDRP_DRIVER key](https://learn.microsoft.com/en-us/windows/win32/api/setupapi/nf-setupapi-setupdigetdeviceregistrypropertyw).
- Microsoft [ReadFile](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-readfile),
  [WriteFile](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-writefile),
  [DeviceIoControl](https://learn.microsoft.com/en-us/windows/win32/api/ioapiset/nf-ioapiset-deviceiocontrol),
  [CancelIoEx](https://learn.microsoft.com/en-us/windows/win32/api/ioapiset/nf-ioapiset-cancelioex),
  [SetIpInterfaceEntry](https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-setipinterfaceentry),
  [CreateUnicastIpAddressEntry](https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-createunicastipaddressentry),
  [CreateIpForwardEntry2](https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-createipforwardentry2),
  [SetIpForwardEntry2](https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-setipforwardentry2), and
  [named-pipe security](https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipe-security-and-access-rights).
- Microsoft [NetAdapter CIM properties](https://learn.microsoft.com/en-us/windows/win32/fwp/wmi/netadaptercimprov/msft-netadapter)
  and [PowerShell comparison conversions](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_comparison_operators?view=powershell-5.1).
- [NSIS installer command-line options](https://nsis.sourceforge.io/Docs/Chapter3.html#installerusage)
  and [command-line handling](https://nsis.sourceforge.io/Reference/%24CMDLINE).

The driver starts in Layer 2 TAP mode. The backend opens
`\\.\Global\{NetCfgInstanceId}.tap` exclusively with `FILE_FLAG_OVERLAPPED`, reads
its version/MAC/MTU, and uses `TAP_WIN_IOCTL_SET_MEDIA_STATUS` to connect/disconnect.
It never enables TUN emulation or DHCP masquerading. Stable owned buffers/events
handle `ERROR_IO_PENDING`; cancellation waits for completion before releasing
buffers. Ethernet and IPv4 ARP header MAC translation preserves OpenRad's wire MAC
without changing the driver registry or IP payload checksums.

## Build from source on Windows

Follow the [native Windows/MSVC build instructions](../README.md#build-on-windows).
The compiled binaries do not install TAP-Windows6 automatically; use the released
setup once with `--no-launch` as described above. It installs the official signed driver and creates the dedicated adapter.
These native MSVC build steps are documented from official Rust requirements and
have not been run by the Linux release builder.

## Linux cross-build and offline installer

To reproduce the published package with Rust 1.95+ and an x64 MinGW-w64 compiler
on Linux:

```sh
rustup target add x86_64-pc-windows-gnu
cargo build --workspace --release --target x86_64-pc-windows-gnu --locked
cargo test --workspace --target x86_64-pc-windows-gnu --no-run --locked
cargo clippy --workspace --all-targets --target x86_64-pc-windows-gnu --locked -- -D warnings
python3 scripts/package-windows-installer.py --build-date 2026-10-01
```

Install NSIS 3.11+ and 7-Zip on the build host. Use `--makensis PATH` and set
`NSISDIR` if the compiler/data are in a local tool directory. The packager fetches
pinned official build inputs (or reuses `--driver-cache PATH`), verifies the MSI,
extracts only signed TAP resources, and obtains its pinned corresponding source.
Internet is needed for a first uncached build, not for the resulting installer.
It inspects application and bootstrapper/plugin PE DLL imports, rejects unknown
DLLs, includes source/licenses, and verifies every file extracted from the completed
NSIS executable before writing the ZIP and checksum. Only an explicit allowlist
is packaged; profiles, credentials, captures and local logs are excluded.
The Rust toolchain's `rust-docs` component supplies its standard-library notices;
these and the MinGW/GCC runtime notices are included with dependency licenses.

Additional Linux installer regressions, with PowerShell and Wine available:

```sh
pwsh -NoLogo -NoProfile -File packaging/windows/Test-SetupLogic.ps1
pwsh -NoLogo -NoProfile -File packaging/windows/Test-RadminRecovery.ps1
python3 scripts/test-windows-installer-flow.py
```

The PowerShell checks mock Windows cmdlets and registry reads. The Wine checks
compile the production NSIS script with dummy worker/desktop programs in a fresh
isolated prefix. They exercise silent install/launch decisions and exit codes,
without executing the real application, TAP driver or Windows network APIs.
They do not establish Windows runtime compatibility or UAC/catalog trust.
