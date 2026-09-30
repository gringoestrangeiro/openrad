#!/usr/bin/env python3
"""Build the single offline Windows setup EXE and its ready-to-test ZIP on Linux.

Run the Windows workspace release cross-build first. NSIS and 7-Zip are required.
Build inputs are pinned official packages. No Windows executable is run here.
"""
import argparse
import gzip
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

from pe_resources import resources

_spec = importlib.util.spec_from_file_location('package_windows_support', Path(__file__).with_name('package-windows.py'))
_support = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_support)
ROOT, SYSTEM_DLLS, TARGET = _support.ROOT, _support.SYSTEM_DLLS, _support.TARGET
command, digest, imports, license_notices = _support.command, _support.digest, _support.imports, _support.license_notices

DRIVER_COMMIT = "0cad8664c2a51832df61f2e1853b6da317d1c129"
DRIVER_TAG = "9.27.0"
MSI_NAME = "OpenVPN-2.6.22-I001-amd64.msi"
MSI_URL = "https://build.openvpn.net/downloads/releases/" + MSI_NAME
MSI_SHA256 = "1e1bb9a712990d1b2b961de7e8df3384964e4fb6f6776a100840f0d9a82ed507"
DRIVER_HASHES = {
    "OemVista.inf": "1327ab3a8c50691f04bea8e2ca356c5b604092a719e219464f8cc4b42e192de9",
    "tap0901.cat": "ee062e5ef2743ceab10c64830e4cefe52e35cc1ece85947ac4e61ddd1c0b05f7",
    "tap0901.sys": "581dcaace05d5c1ac9512457ff50565aca5d904d2c209bd3fc369ca4d4a0d2b1",
}
TAP_STREAM = "Binary.installer.dll.8D9A59BA_33C1_4CA0_96AF_E7F728DEC8D7"


def prepare_driver(cache):
    cache.mkdir(parents=True, exist_ok=True)
    msi = cache / MSI_NAME
    if not msi.exists():
        with urllib.request.urlopen(MSI_URL, timeout=120) as source, msi.open('wb') as destination:
            shutil.copyfileobj(source, destination)
    if digest(msi) != MSI_SHA256:
        raise RuntimeError("Official driver input has a different SHA-256; refusing to package it")
    streams = cache / 'streams'
    streams.mkdir(exist_ok=True)
    command('7z', 'e', '-y', '-tCompound', f'-o{streams}', str(msi), TAP_STREAM)
    extracted = resources(streams / TAP_STREAM)
    driver = cache / 'driver'
    driver.mkdir(exist_ok=True)
    for resource, name in [('DRIVER-WHQL.INF', 'OemVista.inf'), ('DRIVER-WHQL.CAT', 'tap0901.cat'), ('DRIVER-WHQL.SYS', 'tap0901.sys')]:
        contents = extracted[(10, resource, 1033)]
        if hashlib.sha256(contents).hexdigest() != DRIVER_HASHES[name]:
            raise RuntimeError(f"Unexpected signed TAP driver resource: {name}")
        (driver / name).write_bytes(contents)
    inf = (driver / 'OemVista.inf').read_text()
    if not re.search(r'DriverVer\s*=\s*02/27/2024,9\.27\.0\.0', inf):
        raise RuntimeError("Unexpected TAP-Windows6 driver version")
    source_archive = cache / 'tap-windows6-9.27.0-source.tar.gz'
    if not source_archive.exists():
        source = cache / 'source-repository'
        if not source.exists():
            command('git', 'clone', '--depth', '1', '--branch', DRIVER_TAG, 'https://github.com/OpenVPN/tap-windows6.git', str(source))
        if command('git', '-C', str(source), 'rev-parse', 'HEAD') != DRIVER_COMMIT:
            raise RuntimeError("Unexpected TAP-Windows6 corresponding source commit")
        archived = subprocess.check_output(['git', '-C', str(source), 'archive', '--format=tar', f'--prefix=tap-windows6-{DRIVER_TAG}/', 'HEAD'])
        source_archive.write_bytes(gzip.compress(archived, compresslevel=9, mtime=0))
    if hashlib.sha256(gzip.decompress(source_archive.read_bytes())).hexdigest() != '944961b7a0f7b38ceaf69850e85c073c3d4dfb099979b6e76de7223ebf74e54b':
        raise RuntimeError("Unexpected TAP-Windows6 source archive")
    return driver, source_archive


def copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--makensis', default='makensis')
    parser.add_argument('--driver-cache', type=Path, default=ROOT / 'dist/installer-inputs/tap')
    parser.add_argument('--build-date', required=True)
    parser.add_argument('--validation', type=Path, help='Release validation/provenance document to include')
    args = parser.parse_args()
    metadata = json.loads(command('cargo', 'metadata', '--format-version', '1', '--filter-platform', TARGET, '--locked', '--offline'))
    version = next(p['version'] for p in metadata['packages'] if p['name'] == 'openrad-client')
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    (ROOT / 'reports/windows-installer').mkdir(parents=True, exist_ok=True)
    driver, source_archive = prepare_driver(args.driver_cache.resolve())
    binaries = Path(metadata['target_directory']) / TARGET / 'release'
    setup_exe = dist / 'OpenRad-Setup.exe'
    with tempfile.TemporaryDirectory(prefix='installer-package-', dir=dist) as temp_name:
        temp = Path(temp_name).resolve()
        payload = temp / 'payload'
        payload.mkdir()
        audit = {}
        for name in ['openrad.exe', 'openrad-desktop.exe', 'openrad-setup-helper.exe']:
            source = binaries / name
            audit[name] = imports(source, 'x86_64-w64-mingw32-objdump')
            unknown = [dll for dll in audit[name] if dll.lower() not in SYSTEM_DLLS and not dll.lower().startswith(('api-ms-win-', 'ext-ms-win-'))]
            if unknown:
                raise RuntimeError(f"Unbundled application DLLs required by {name}: {unknown}")
            copy(source, payload / name)
        for source, target in [
            ('LICENSE', 'LICENSE'), ('CREDITS.md', 'CREDITS.md'),
            ('README.md', 'README.md'), ('CHANGELOG.md', 'CHANGELOG.md'),
            ('docs/windows.md', 'README-WINDOWS.md'),
            ('docs/windows.md', 'docs/windows.md'), ('docs/cli.md', 'docs/cli.md'),
            ('docs/desktop.md', 'docs/desktop.md'), ('docs/architecture.md', 'docs/architecture.md'),
            ('docs/linux.md', 'docs/linux.md'), ('docs/performance.md', 'docs/performance.md'),
            (f'docs/releases/{version}.md', f'docs/releases/{version}.md'),
            ('docs/screenshots/windows-settings-linux-preview.png', 'docs/screenshots/windows-settings-linux-preview.png'),
            ('docs/licenses/TAP-Windows6-MIT.txt', 'licenses/TAP-Windows6-MIT.txt'),
            ('docs/licenses/NSIS.txt', 'licenses/NSIS.txt'),
            ('desktop/assets/OFL-NotoSans.txt', 'licenses/OFL-NotoSans.txt'),
            ('packaging/windows/setup-adapter.ps1', 'setup-adapter.ps1'),
            ('packaging/windows/Launch-CLI.cmd', 'Launch-CLI.cmd'),
            ('packaging/windows/Launch-CLI.ps1', 'Launch-CLI.ps1'),
            ('packaging/windows/Debug-OpenRad.cmd', 'Debug-OpenRad.cmd'),
            ('packaging/windows/Debug-OpenRad.ps1', 'Debug-OpenRad.ps1'),
            ('packaging/windows/Test-Windows.ps1', 'Test-Windows.ps1'),
        ]:
            copy(ROOT / source, payload / target)
        copy((args.validation or ROOT / f'docs/releases/{version}.md').resolve(), payload / 'VALIDATION.md')
        guide = payload / 'README-WINDOWS.md'
        guide.write_text(guide.read_text().replace('](cli.md)', '](docs/cli.md)').replace('](desktop.md)', '](docs/desktop.md)').replace('](screenshots/', '](docs/screenshots/').replace('](releases/', '](docs/releases/'), encoding='utf-8')
        credits = payload / 'CREDITS.md'
        credits.write_text(credits.read_text().replace('](docs/licenses/TAP-Windows6-MIT.txt)', '](licenses/TAP-Windows6-MIT.txt)').replace('](desktop/assets/OFL-NotoSans.txt)', '](licenses/OFL-NotoSans.txt)'), encoding='utf-8')
        for notice in sorted((ROOT / 'docs/licenses/windows-runtime').iterdir()):
            if notice.is_file():
                copy(notice, payload / 'licenses/windows-runtime' / notice.name)
        sysroot = Path(command('rustc', '--print', 'sysroot'))
        standard_library_notice = sysroot / 'share/doc/rust/COPYRIGHT-library.html'
        if not standard_library_notice.is_file():
            raise RuntimeError('Rust standard-library notices are missing; install the rust-docs toolchain component')
        copy(standard_library_notice, payload / 'licenses/Rust-Standard-Library.html')
        for name in DRIVER_HASHES:
            copy(driver / name, payload / 'driver' / name)
        copy(source_archive, payload / 'driver-source' / source_archive.name)
        with tarfile.open(source_archive) as archive:
            for name in ['COPYING', 'COPYRIGHT.GPL', 'COPYRIGHT.MIT', 'README.rst', 'version.m4']:
                contents = archive.extractfile(f'tap-windows6-{DRIVER_TAG}/{name}').read()
                target = payload / 'licenses/TAP-Windows6' / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(contents)
        provenance = {
            'driver_version': '9.27.0.0', 'architecture': 'amd64',
            'upstream_binary_package': MSI_URL, 'upstream_package_sha256': MSI_SHA256,
            'upstream_resource_stream': TAP_STREAM, 'driver_file_sha256': DRIVER_HASHES,
            'source_repository': 'https://github.com/OpenVPN/tap-windows6',
            'source_tag': DRIVER_TAG, 'source_commit': DRIVER_COMMIT,
            'source_archive': 'driver-source/' + source_archive.name,
            'source_archive_sha256': digest(source_archive),
            'windows_catalog_trust_runtime_verified': False,
            'redistribution': 'Unchanged signed TAP files, accompanied by complete corresponding upstream source/build scripts and licenses',
        }
        (payload / 'DRIVER-PROVENANCE.json').write_text(json.dumps(provenance, indent=2) + '\n')
        license_notices(metadata, payload)
        (payload / 'DLL-IMPORTS.json').write_text(json.dumps({
            'target': TARGET, 'application_runtime_dlls': [],
            'windows_system_dlls_are_not_bundled': True, 'imports': audit,
            'dynamic_windows_components': ['newdev.dll', 'Windows PowerShell 5.1 NetAdapter module', 'dxgi.dll', 'd3d12.dll', 'd3dcompiler_47.dll', 'Windows WARP CPU renderer'],
            'graphics_default': 'auto: Direct3D 12 hardware, Direct3D 12 WARP CPU, OpenGL',
            'dx12_shader_compiler': 'System FXC; no DXC or Agility SDK files required',
            'setup_engine': 'NSIS bootstrapper and embedded System plugin; automatically extracted by the installer',
        }, indent=2) + '\n')
        (payload / 'BUILD-INFO.json').write_text(json.dumps({
            'version': version, 'build_date': args.build_date, 'target': TARGET,
            'rustc': command('rustc', '--version'),
            'git_base_commit': command('git', 'rev-parse', 'HEAD'),
            'worktree_dirty': bool(command('git', 'status', '--porcelain')),
            'windows_runtime_tested_by_builder': False,
            'windows_runtime_feedback': 'User reports successful operation on Windows 10; other Windows versions remain unverified',
            'windows_support': 'experimental', 'makensis': command(args.makensis, '-VERSION'),
            'windows_minimum': 'Windows 10 version 1709, x64',
            'graphics_renderers': ['auto', 'opengl', 'wgpu (DX12 hardware)', 'software (DX12 WARP CPU)'],
            'diagnostics_build': f'release-{version}',
            'system_mode': 'Opt-in Debug-OpenRad.cmd -System; official Microsoft PsExec download, signature validation and separate profile',
        }, indent=2) + '\n')
        # This manifest is also embedded separately for repeat-run readiness checks.
        records = [{'path': path.relative_to(payload).as_posix(), 'sha256': digest(path)} for path in sorted(payload.rglob('*')) if path.is_file()]
        (payload / 'INSTALL-MANIFEST.json').write_text(json.dumps({'format': 'openrad-install-v1', 'version': version, 'files': records}, indent=2) + '\n')
        delete = temp / 'uninstall-files.nsh'
        files = sorted(p for p in payload.rglob('*') if p.is_file())
        directories = sorted((p for p in payload.rglob('*') if p.is_dir()), key=lambda p: len(p.parts), reverse=True)
        def quoted(path):
            return path.relative_to(payload).as_posix().replace('/', '\\').replace('$', '$$').replace('"', '$\\"')
        delete.write_text(''.join(f'Delete "$INSTDIR\\{quoted(path)}"\n' for path in files) + ''.join(f'RMDir "$INSTDIR\\{quoted(path)}"\n' for path in directories), encoding='utf-8')
        # Source of our installer is shipped too; it contains no user state.
        source_zip = payload / 'installer-source.zip'
        with zipfile.ZipFile(source_zip, 'w', zipfile.ZIP_DEFLATED) as archive:
            for path in ['packaging/windows/OpenRad-Setup.nsi', 'packaging/windows/setup-adapter.ps1', 'packaging/windows/Launch-CLI.cmd', 'packaging/windows/Launch-CLI.ps1', 'packaging/windows/Test-Windows.ps1', 'packaging/windows/Test-SetupLogic.ps1', 'packaging/windows/test-fixtures/setup-worker.c', 'scripts/test-windows-installer-flow.py', 'scripts/package-windows-installer.py', 'scripts/pe_resources.py', 'scripts/package-windows.py', 'src/setup.rs', 'src/setup_main.rs', 'src/platform/windows_setup.rs', 'desktop/Cargo.toml', 'desktop/src/main.rs', 'desktop/src/graphics.rs', 'desktop/src/startup_log.rs', 'src/early_log.rs', 'src/platform/windows.rs', 'src/platform/windows_security.rs', 'src/platform/windows_crash.rs', 'src/main.rs', 'src/daemon.rs', 'Cargo.toml', 'Cargo.lock', 'packaging/windows/Debug-OpenRad.cmd', 'packaging/windows/Debug-OpenRad.ps1', 'desktop/src/platform/windows_launch.rs', 'LICENSE']:
                archive.write(ROOT / path, path)
        # Include installer source in both repeat-run hashes and exact uninstall.
        records.append({'path': source_zip.name, 'sha256': digest(source_zip)})
        (payload / 'INSTALL-MANIFEST.json').write_text(json.dumps({'format': 'openrad-install-v1', 'version': version, 'files': records}, indent=2) + '\n')
        with delete.open('a') as output:
            output.write('Delete "$INSTDIR\\installer-source.zip"\n')
        log = command(args.makensis, '-V3', '-WX', f'-DOPENRAD_VERSION={version}', f'-DPAYLOAD_DIR={payload}', f'-DSETUP_EXE={setup_exe}', f'-DDELETE_INCLUDE={delete}', str(ROOT / 'packaging/windows/OpenRad-Setup.nsi'))
        (ROOT / 'reports/windows-installer/nsis-build.log').write_text(log + '\n')
        manifest = next(value for key, value in resources(setup_exe).items() if key[0] == 24)
        if b'requireAdministrator' not in manifest:
            raise RuntimeError('Setup has no administrator manifest')
        # 7-Zip reads the finished NSIS payload. It does not execute the installer.
        unpacked = temp / 'extracted-setup'
        command('7z', 'x', '-y', f'-o{unpacked}', str(setup_exe))
        for path in payload.rglob('*'):
            if path.is_file():
                relative = path.relative_to(payload)
                extracted = unpacked / relative
                if not extracted.exists() or digest(extracted) != digest(path):
                    raise RuntimeError(f'Finished setup payload does not match: {relative}')
        # The separately extracted check worker and manifest must also match.
        if digest(unpacked / '$PLUGINSDIR/setup-worker.exe') != digest(payload / 'openrad-setup-helper.exe'):
            raise RuntimeError('Incorrect embedded setup worker')
        if digest(unpacked / '$PLUGINSDIR/expected-manifest.json') != digest(payload / 'INSTALL-MANIFEST.json'):
            raise RuntimeError('Incorrect repeat-run readiness manifest')
        bootstrap_imports = {}
        for binary in [setup_exe, *sorted((unpacked / '$PLUGINSDIR').glob('*.dll'))]:
            dependencies = imports(binary, 'x86_64-w64-mingw32-objdump', formats=('pei-i386', 'pei-x86-64'))
            unknown = [dll for dll in dependencies if dll.lower() not in SYSTEM_DLLS and not dll.lower().startswith(('api-ms-win-', 'ext-ms-win-'))]
            if unknown:
                raise RuntimeError(f'Unbundled setup-engine DLLs required by {binary.name}: {unknown}')
            bootstrap_imports[binary.name] = dependencies
        (ROOT / 'reports/windows-installer/setup-dll-imports.json').write_text(json.dumps(bootstrap_imports, indent=2) + '\n')
        output = temp / f'openrad-{version}-windows-x86_64-setup'
        output.mkdir()
        copy(setup_exe, output / setup_exe.name)
        for name in ['README.md', 'CHANGELOG.md', 'README-WINDOWS.md', 'VALIDATION.md', 'Test-Windows.ps1', 'Debug-OpenRad.cmd', 'Debug-OpenRad.ps1', 'DRIVER-PROVENANCE.json', 'DLL-IMPORTS.json', 'BUILD-INFO.json']:
            copy(payload / name, output / name)
        # Preserve the guide's relative local links for readers outside the EXE.
        shutil.copytree(payload / 'docs', output / 'docs')
        (output / 'SHA256SUMS.txt').write_text(''.join(f'{digest(p)}  {p.relative_to(output).as_posix()}\n' for p in sorted(output.rglob('*')) if p.is_file()))
        zip_path = dist / f'{output.name}.zip'
        with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9, strict_timestamps=False) as archive:
            for path in sorted(p for p in output.rglob('*') if p.is_file()):
                archive.write(path, path.relative_to(temp).as_posix())
    zip_path.with_suffix('.zip.sha256').write_text(f'{digest(zip_path)}  {zip_path.name}\n')
    setup_exe.with_suffix('.exe.sha256').write_text(f'{digest(setup_exe)}  {setup_exe.name}\n')
    print(json.dumps({'archive': str(zip_path), 'bytes': zip_path.stat().st_size, 'sha256': digest(zip_path), 'setup_executable': str(setup_exe), 'setup_sha256': digest(setup_exe), 'setup_payload_matches_built_files': True, 'windows_runtime_tested_by_builder': False, 'windows_support': 'experimental'}, indent=2))


if __name__ == '__main__':
    main()
