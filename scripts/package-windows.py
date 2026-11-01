#!/usr/bin/env python3
"""Package the cross-built x64 workspace, audit PE imports, and include licenses."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = "x86_64-pc-windows-gnu"
# Native Windows MSVC builds link to Microsoft's redistributable C runtime.
# QA ZIPs may list it as an explicit prerequisite, never as a system DLL.
MSVC_EXTERNAL_RUNTIME = {"vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "concrt140.dll"}
SYSTEM_DLLS = {
    "advapi32.dll", "bcrypt.dll", "bcryptprimitives.dll", "combase.dll",
    "comctl32.dll", "comdlg32.dll", "crypt32.dll", "dcomp.dll", "dbghelp.dll", "dwrite.dll",
    "d3d12.dll", "d3dcompiler_47.dll", "dxgi.dll",
    "dwmapi.dll", "gdi32.dll", "imm32.dll", "iphlpapi.dll", "kernel32.dll",
    "msvcrt.dll", "ntdll.dll", "ole32.dll", "oleaut32.dll", "opengl32.dll",
    "propsys.dll", "rpcrt4.dll", "setupapi.dll", "newdev.dll", "shcore.dll", "shell32.dll", "ucrtbase.dll",
    "uiautomationcore.dll", "user32.dll", "userenv.dll", "uxtheme.dll",
    "version.dll", "winmm.dll", "ws2_32.dll",
}


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def root_release_document(text, version):
    """Keep links usable when release notes are also copied to the ZIP root."""
    text = re.sub(r'\]\((\d+\.\d+\.\d+(?:-[a-z0-9-]+)?\.md)(?=[#)])', r'](docs/releases/\1', text)
    return (text.replace('](../../CHANGELOG.md', '](CHANGELOG.md')
            .replace('](../screenshots/', '](docs/screenshots/')
            .replace('](../linux.md', '](docs/linux.md')
            .replace('](../windows.md', '](docs/windows.md')
            .replace('](../desktop.md', '](docs/desktop.md')
            .replace('](../architecture.md', '](docs/architecture.md')
            .replace('](../cli.md', '](docs/cli.md')
            .replace('](../cli-pt-BR.md', '](docs/cli-pt-BR.md')
            .replace('](../performance.md', '](docs/performance.md'))


def copy_release_history(destination):
    """Include the historical guides linked by the changelog and platform guides."""
    for name in ['1.0.0.md', '1.0.0-changes.md', '1.1.0.md', '1.1.0-changes.md', '1.2.0.md', '1.2.0-changes.md', '1.2.0-windows-refresh.md', '1.3.0.md', '1.3.0-changes.md', '1.4.0.md', '1.4.0-changes.md', '1.5.0.md', '1.5.0-changes.md', '2.0.0.md', '2.0.0-changes.md', '2.0.1.md', '2.0.1-changes.md', '2.0.2.md', '2.0.2-changes.md', '2.1.0.md', '2.1.0-changes.md', '2.1.5.md', '2.1.5-changes.md', '2.1.5-minecraft-integration.md']:
        target = destination / 'docs/releases' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        text = (ROOT / 'docs/releases' / name).read_text(encoding='utf-8')
        if name == '1.0.0-changes.md':
            # Its source-file inventory refers to the old tag, not the installed payload.
            text = text.replace('](../../', '](https://github.com/gringoestrangeiro/openrad/blob/v1.0.0/')
        target.write_text(text, encoding='utf-8')


def imports(path, objdump, formats=("pei-x86-64",)):
    header = command(objdump, "-f", str(path))
    if not any(format in header for format in formats):
        raise RuntimeError(f"Unexpected Windows PE architecture: {path.name}")
    return sorted(set(re.findall(r"DLL Name:\s*(\S+)", command(objdump, "-p", str(path)))), key=str.lower)


def license_notices(metadata, destination, platform="Windows"):
    fallback = json.loads((ROOT / "docs/licenses/dependencies/index.json").read_text())
    packages = {p["id"]: p for p in metadata["packages"]}
    graph = {n["id"]: [d["pkg"] for d in n["deps"]] for n in metadata["resolve"]["nodes"]}
    pending = list(metadata["workspace_members"])
    visited = set()
    while pending:
        package = pending.pop()
        if package in visited:
            continue
        visited.add(package)
        pending.extend(graph.get(package, []))
    summary = [f"Rust dependency notices for the {platform} build. Operating-system libraries are supplied by the OS.\n"]
    for package in sorted((packages[i] for i in visited), key=lambda p: (p["name"], p["version"])):
        if package["id"] in metadata["workspace_members"]:
            continue
        directory = Path(package["manifest_path"]).parent
        files = {p for p in directory.rglob("*") if p.is_file() and re.match(r"^(LICENSE|LICENCE|COPYING|NOTICE)([._-]|$)", p.name, re.I)}
        if package.get("license_file"):
            files.add(directory / package["license_file"])
        key = f"{package['name']}-{package['version']}"
        if key in fallback:
            files.update(p for p in (ROOT / "docs/licenses/dependencies" / fallback[key]).iterdir() if p.is_file())
        if package['name'] == 'epaint_default_fonts':
            files.update((directory / 'fonts').glob('*.txt'))
        if not files:
            raise RuntimeError(f"No license notice found for {package['name']} {package['version']}")
        target = destination / "licenses" / "dependencies" / key
        target.mkdir(parents=True)
        for source in sorted(files):
            try:
                relative = source.relative_to(directory)
            except ValueError:
                relative = Path(source.name)
            license_target = target / relative
            license_target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, license_target)
        summary.append(f"{key}: {package.get('license') or 'see license file'}\n{package.get('repository') or package.get('homepage') or ''}\nNotices: licenses/dependencies/{key}/\n")
    (destination / "THIRD-PARTY-NOTICES.txt").write_text("\n".join(summary), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--objdump", default="x86_64-w64-mingw32-objdump")
    parser.add_argument("--native-msvc", action="store_true", help="Package native Windows MSVC release as a portable QA candidate, without claiming an offline installer")
    parser.add_argument("--runtime-dir", type=Path, help="Directory containing any non-system runtime DLLs")
    parser.add_argument("--runtime-license", type=Path, help="Redistribution notice for supplied runtime DLLs")
    parser.add_argument("--build-date", help="Optional reported build date")
    args = parser.parse_args()
    build_target = "x86_64-pc-windows-msvc" if args.native_msvc else TARGET
    objdump = "objdump" if args.native_msvc and args.objdump == "x86_64-w64-mingw32-objdump" else args.objdump
    if shutil.which(objdump) is None:
        flag = "--objdump PATH to a GNU binutils objdump that reads PE files (pei-x86-64)" if args.native_msvc else "--objdump PATH"
        parser.error(f"{objdump!r} was not found on PATH; supply {flag}")
    metadata = json.loads(command("cargo", "metadata", "--format-version", "1", "--filter-platform", build_target, "--locked", "--offline"))
    version = next(p["version"] for p in metadata["packages"] if p["name"] == "openrad-client")
    name = f"openrad-{version}-windows-x86_64-test"
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    runtime = {p.name.lower(): p for p in args.runtime_dir.iterdir() if p.is_file()} if args.runtime_dir else {}
    binaries = Path(metadata["target_directory"]) / ("release" if args.native_msvc else f"{TARGET}/release")
    with tempfile.TemporaryDirectory(prefix="windows-package-", dir=dist) as temp:
        output = Path(temp) / name
        output.mkdir()
        manifest = {}
        pending = []
        for filename in ["openrad.exe", "openrad-desktop.exe"]:
            source = binaries / filename
            shutil.copy2(source, output / filename)
            pending.append(output / filename)
        provided = set()
        external_prerequisites = set()
        while pending:
            path = pending.pop()
            dependencies = imports(path, objdump)
            manifest[path.name] = dependencies
            for dll in dependencies:
                key = dll.lower()
                if key in SYSTEM_DLLS or key.startswith(("api-ms-win-", "ext-ms-win-")):
                    continue
                if key in provided:
                    continue
                if args.native_msvc and key in MSVC_EXTERNAL_RUNTIME and key not in runtime:
                    external_prerequisites.add(dll)
                    continue
                if key not in runtime or not args.runtime_license:
                    raise RuntimeError(f"Missing non-system DLL or its redistribution notice: {dll}; provide --runtime-dir and --runtime-license")
                source = runtime[key]
                shutil.copy2(source, output / dll)
                provided.add(key)
                pending.append(output / dll)
        if provided:
            shutil.copy2(args.runtime_license, output / "RUNTIME-DLL-LICENSE.txt")
        if args.native_msvc:
            (output / "NATIVE-MSVC-QA.txt").write_text(
                "Portable Windows MSVC QA candidate. NOT the signed offline installer.\n"
                + ("Requires Microsoft Visual C++ Redistributable x64: " + ", ".join(sorted(external_prerequisites)) + "\n"
                   if external_prerequisites else "")
                + "TAP driver/UAC and real VPN interoperability require manual checks.\n", encoding="utf-8")
        # Explicit allowlist: never package credentials, captures or runtime logs.
        for source, target in [
            ("LICENSE", "LICENSE"), ("CREDITS.md", "CREDITS.md"),
            ("docs/windows.md", "README-WINDOWS.md"),
            ("docs/windows.md", "docs/windows.md"),
            ("docs/cli.md", "docs/cli.md"),
            ("docs/cli-pt-BR.md", "docs/cli-pt-BR.md"),
            ("docs/desktop.md", "docs/desktop.md"),
            ("docs/architecture.md", "docs/architecture.md"),
            ("README.md", "README.md"), ("CHANGELOG.md", "CHANGELOG.md"),
            ("docs/linux.md", "docs/linux.md"), ("docs/performance.md", "docs/performance.md"),
            (f"docs/releases/{version}.md", f"docs/releases/{version}.md"),
            (f"docs/releases/{version}-changes.md", f"docs/releases/{version}-changes.md"),
            ('docs/screenshots/1.1.0-tap-authorization.png', 'docs/screenshots/1.1.0-tap-authorization.png'),
            ('docs/screenshots/1.2.0-broadcast-settings-pt.png', 'docs/screenshots/1.2.0-broadcast-settings-pt.png'),
            ('docs/screenshots/broadcast-multiple-peers-pt.png', 'docs/screenshots/broadcast-multiple-peers-pt.png'),
            ('docs/screenshots/peer-ip-portuguese.png', 'docs/screenshots/peer-ip-portuguese.png'),
            ('docs/screenshots/peer-ip-settings-portuguese.png', 'docs/screenshots/peer-ip-settings-portuguese.png'),
            ("docs/screenshots/1.0.0-networks.png", "docs/screenshots/1.0.0-networks.png"),
            ("docs/screenshots/1.0.0-discover.png", "docs/screenshots/1.0.0-discover.png"),
            ("docs/screenshots/1.0.0-auto-join.png", "docs/screenshots/1.0.0-auto-join.png"),
            ("docs/screenshots/1.0.0-many-networks.png", "docs/screenshots/1.0.0-many-networks.png"),
            ("docs/screenshots/1.0.0-identity-reset.png", "docs/screenshots/1.0.0-identity-reset.png"),
            ("docs/screenshots/windows-settings-linux-preview.png", "docs/screenshots/windows-settings-linux-preview.png"),
            ("docs/licenses/TAP-Windows6-MIT.txt", "licenses/TAP-Windows6-MIT.txt"),
            ("desktop/assets/OFL-NotoSans.txt", "licenses/OFL-NotoSans.txt"),
            ("packaging/windows/Launch-OpenRad.cmd", "Launch-OpenRad.cmd"),
            ("packaging/windows/Test-Windows.ps1", "Test-Windows.ps1"),
            (f"docs/releases/{version}.md", "VALIDATION.md"),
        ]:
            destination = output / target
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / source, destination)
        copy_release_history(output)
        root_guide = output / "README-WINDOWS.md"
        root_guide.write_text(
            root_guide.read_text(encoding="utf-8")
            .replace("](cli.md)", "](docs/cli.md)")
            .replace("](../README.md", "](README.md")
            .replace("](desktop.md)", "](docs/desktop.md)")
            .replace("](screenshots/", "](docs/screenshots/")
            .replace("](releases/", "](docs/releases/"),
            encoding="utf-8",
        )
        validation = output / "VALIDATION.md"
        validation.write_text(root_release_document(validation.read_text(encoding="utf-8"), version), encoding="utf-8")
        credits = output / "CREDITS.md"
        credits.write_text(
            credits.read_text(encoding="utf-8")
            .replace("](docs/licenses/TAP-Windows6-MIT.txt)", "](licenses/TAP-Windows6-MIT.txt)")
            .replace("](desktop/assets/OFL-NotoSans.txt)", "](licenses/OFL-NotoSans.txt)"),
            encoding="utf-8",
        )
        license_notices(metadata, output)
        sysroot = Path(command("rustc", "--print", "sysroot"))
        shutil.copy2(sysroot / "share/doc/rust/COPYRIGHT-library.html", output / "licenses/Rust-Standard-Library.html")
        (output / "DLL-IMPORTS.json").write_text(json.dumps({
            "target": build_target, "audit": "Static PE imports and supplied non-system DLLs, inspected on the build host",
            "windows_system_dlls_are_not_bundled": True,
            "application_runtime_dlls": sorted(provided),
            "external_vendor_runtime_dlls": sorted(external_prerequisites), "imports": manifest,
        }, indent=2) + "\n", encoding="utf-8")
        (output / "BUILD-INFO.json").write_text(json.dumps({
            "version": version, "target": build_target, "build_date": args.build_date,
            "git_base_commit": command("git", "rev-parse", "HEAD"),
            "source_commit": command("git", "rev-parse", "HEAD"),
            "worktree_dirty": bool(command("git", "status", "--porcelain")),
            "rustc": command("rustc", "--version"),
            "windows_runtime_tested": False,
            "external_runtime_prerequisite": "Microsoft Visual C++ Redistributable x64" if external_prerequisites else None,
            "windows_support": "experimental",
            "radmin_recovery": "Administrator worker, SYSTEM fallback and final administrator retry; forced service/GUI termination and verified adapter-disable fallbacks",
        }, indent=2) + "\n", encoding="utf-8")
        files = sorted(p for p in output.rglob("*") if p.is_file())
        (output / "SHA256SUMS.txt").write_text("".join(f"{digest(p)}  {p.relative_to(output).as_posix()}\n" for p in files), encoding="utf-8")
        archive = dist / f"{name}.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9, strict_timestamps=False) as zip_file:
            for file in sorted(p for p in output.rglob("*") if p.is_file()):
                zip_file.write(file, file.relative_to(Path(temp)).as_posix())
        checksum = digest(archive)
        archive.with_suffix(".zip.sha256").write_text(f"{checksum}  {archive.name}\n", encoding="utf-8")
    print(json.dumps({"archive": str(archive), "bytes": archive.stat().st_size, "sha256": checksum, "runtime_dlls": sorted(provided)}, indent=2))


if __name__ == "__main__":
    main()
