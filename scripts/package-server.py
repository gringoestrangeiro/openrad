#!/usr/bin/env python3
"""Build and package openrad-server release archives for Linux and Windows.

The server is pure Go (CGO disabled, pure-Go SQLite), so both targets are
cross-built from the release checkout on any host with the Go toolchain named
in server/go.mod. Each archive carries the binary, the server guide, the
OpenRad license, the Go standard-library and linked-module license notices,
build provenance and SHA-256 checksums. The Windows binary is only inspected
here (PE header), never executed.
"""
import argparse
import json
import os
import re
import shutil
import struct
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER = ROOT / 'server'
TARGETS = {
    'linux': {'goarch': 'amd64', 'arch': 'x86_64', 'binary': 'openrad-server', 'archive': 'tar.gz'},
    'windows': {'goarch': 'amd64', 'arch': 'x86_64', 'binary': 'openrad-server.exe', 'archive': 'zip'},
}
LICENSE_NAME = re.compile(r'^(LICEN[CS]E|COPYING|NOTICE|PATENTS)([-._].*)?$', re.I)
DOCS = [('server/README.md', 'README.md'), ('LICENSE', 'LICENSE'), ('CREDITS.md', 'CREDITS.md')]


def run(*args, cwd=ROOT, env=None):
    return subprocess.check_output(args, cwd=cwd, env=env, text=True).strip()


def digest(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def release_version():
    match = re.search(r'^\[package\]\s*\nname = "openrad-client"\s*\nversion = "([^"]+)"',
                      (ROOT / 'Cargo.toml').read_text(encoding='utf-8'), re.M)
    if not match:
        raise RuntimeError('Cannot read the openrad-client version from Cargo.toml')
    return match[1]


def check_elf_static_x86_64(path):
    data = path.read_bytes()
    if data[:4] != b'\x7fELF' or data[4] != 2 or data[5] != 1:
        raise RuntimeError(f'{path.name} is not a little-endian ELF64 file')
    if struct.unpack_from('<H', data, 18)[0] != 62:
        raise RuntimeError(f'{path.name} is not x86-64')
    phoff, = struct.unpack_from('<Q', data, 32)
    phentsize, phnum = struct.unpack_from('<HH', data, 54)
    for i in range(phnum):
        if struct.unpack_from('<I', data, phoff + i * phentsize)[0] == 3:  # PT_INTERP
            raise RuntimeError(f'{path.name} is dynamically linked; expected a static CGO-free build')


def check_pe_console_amd64(path):
    data = path.read_bytes()
    if data[:2] != b'MZ':
        raise RuntimeError(f'{path.name} is not a PE file')
    pe, = struct.unpack_from('<I', data, 0x3c)
    if data[pe:pe + 4] != b'PE\0\0' or struct.unpack_from('<H', data, pe + 4)[0] != 0x8664:
        raise RuntimeError(f'{path.name} is not an x86-64 PE image')
    optional = pe + 24
    if struct.unpack_from('<H', data, optional)[0] != 0x20b:
        raise RuntimeError(f'{path.name} is not PE32+')
    if struct.unpack_from('<H', data, optional + 68)[0] != 3:
        raise RuntimeError(f'{path.name} is not a console-subsystem executable')


def module_licenses(go, env, destination, go_license=None):
    """Copy the license files of every module linked into this target."""
    listing = run(go, 'list', '-mod=readonly', '-deps',
                  '-f', '{{with .Module}}{{if not .Main}}{{.Path}}\t{{.Version}}\t{{.Dir}}{{end}}{{end}}', '.',
                  cwd=SERVER, env=env)
    modules = {}
    for line in filter(None, listing.splitlines()):
        path, version, directory = line.split('\t')
        modules[path] = (version, Path(directory))
    inventory = []
    for path, (version, directory) in sorted(modules.items()):
        files = sorted(p for p in directory.iterdir() if p.is_file() and LICENSE_NAME.match(p.name))
        if not files:
            raise RuntimeError(f'Linked Go module {path}@{version} has no license file')
        target = destination / 'go-modules' / path.replace('/', '_')
        target.mkdir(parents=True)
        for file in files:
            shutil.copyfile(file, target / file.name)
        inventory.append({'module': path, 'version': version, 'license_files': [f.name for f in files]})
    goroot = Path(run(go, 'env', 'GOROOT'))
    license_file = go_license or goroot / 'LICENSE'
    if not license_file.is_file():
        raise RuntimeError(f'Go standard-library LICENSE not found at {license_file}; '
                           'pass --go-license (distribution packages may move it)')
    shutil.copyfile(license_file, destination / 'Go-LICENSE')
    if (goroot / 'PATENTS').is_file():
        shutil.copyfile(goroot / 'PATENTS', destination / 'Go-PATENTS')
    (destination / 'go-modules.json').write_text(json.dumps(inventory, indent=2) + '\n')
    return inventory


def package(goos, spec, version, args, dist):
    go = args.go
    env = dict(os.environ, CGO_ENABLED='0', GOOS=goos, GOARCH=spec['goarch'], GOFLAGS='-mod=readonly')
    name = f'openrad-server-v{version}-{goos}-{spec["arch"]}'
    with tempfile.TemporaryDirectory(prefix='server-package-', dir=dist) as temporary:
        output = Path(temporary) / name
        output.mkdir()
        binary = output / spec['binary']
        run(go, 'build', '-trimpath', '-buildvcs=false',
            '-ldflags', f'-s -w -buildid= -X main.version={version}', '-o', str(binary), '.', cwd=SERVER, env=env)
        if goos == 'linux':
            check_elf_static_x86_64(binary)
            reported = run(str(binary), '--version')
            if reported != f'openrad-server {version}':
                raise RuntimeError(f'Unexpected server version: {reported}')
        else:
            check_pe_console_amd64(binary)
        for source, target in DOCS:
            shutil.copy2(ROOT / source, output / target)
        inventory = module_licenses(go, env, output / 'licenses', args.go_license)
        info = {
            'version': version, 'build_date': args.build_date, 'goos': goos, 'goarch': spec['goarch'],
            'go': run(go, 'version'), 'cgo': False,
            'source_commit': run('git', 'rev-parse', 'HEAD'),
            'source_tree': run('git', 'rev-parse', 'HEAD^{tree}'),
            'worktree_dirty': bool(run('git', 'status', '--porcelain')),
            'linked_modules': [f'{m["module"]}@{m["version"]}' for m in inventory],
            'binary_stripped': True,
            'version_checked_by_builder': goos == 'linux',
            'native_runtime_tested_by_builder': False,
        }
        if goos == 'windows':
            info['windows_support'] = 'experimental: cross-built; not validated on native Windows by the builder'
        (output / 'BUILD-INFO.json').write_text(json.dumps(info, indent=2) + '\n')
        files = sorted(p for p in output.rglob('*') if p.is_file())
        (output / 'SHA256SUMS').write_text(''.join(f'{digest(p)}  {p.relative_to(output).as_posix()}\n' for p in files))
        archive = dist / f'{name}.{spec["archive"]}'
        if spec['archive'] == 'zip':
            with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as packed:
                for path in sorted(p for p in output.rglob('*') if p.is_file()):
                    packed.write(path, path.relative_to(output.parent).as_posix())
        else:
            with tarfile.open(archive, 'w:gz') as packed:
                packed.add(output, arcname=name)
    checksum = digest(archive)
    Path(f'{archive}.sha256').write_text(f'{checksum}  {archive.name}\n')
    return {'archive': str(archive), 'bytes': archive.stat().st_size, 'sha256': checksum}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-date', required=True)
    parser.add_argument('--go', default='go', help='Go toolchain executable')
    parser.add_argument('--go-license', type=Path, help='Go LICENSE file (default: $GOROOT/LICENSE)')
    parser.add_argument('--target', action='append', choices=sorted(TARGETS),
                        help='target OS (repeatable; default: all)')
    args = parser.parse_args()
    version = release_version()
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    results = {goos: package(goos, TARGETS[goos], version, args, dist) for goos in (args.target or sorted(TARGETS))}
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
