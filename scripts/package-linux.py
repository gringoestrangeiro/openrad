#!/usr/bin/env python3
"""Package trusted Linux release binaries, dependency notices and build metadata."""
import argparse
import importlib.util
import json
import re
import shutil
import tarfile
import tempfile
from pathlib import Path

_spec = importlib.util.spec_from_file_location('windows_package_support', Path(__file__).with_name('package-windows.py'))
_support = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_support)
ROOT, command, digest, license_notices = _support.ROOT, _support.command, _support.digest, _support.license_notices
TARGET = 'x86_64-unknown-linux-gnu'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binaries', type=Path, required=True)
    parser.add_argument('--build-info', type=Path, required=True, help='JSON with actual builder/toolchain/source provenance')
    parser.add_argument('--rust-notice', type=Path, required=True, help='COPYRIGHT-library.html from the compiler used for these binaries')
    parser.add_argument('--build-date', required=True)
    args = parser.parse_args()
    metadata = json.loads(command('cargo', 'metadata', '--format-version', '1', '--filter-platform', TARGET, '--locked', '--offline'))
    version = next(p['version'] for p in metadata['packages'] if p['name'] == 'openrad-client')
    info = json.loads(args.build_info.read_text())
    if info['source_commit'] != command('git', 'rev-parse', 'HEAD'):
        raise RuntimeError('Builder source commit does not match this checkout')
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    name = f'openrad-v{version}-linux-x86_64'
    with tempfile.TemporaryDirectory(prefix='linux-package-', dir=dist) as temporary:
        output = Path(temporary) / name
        output.mkdir()
        audit = {}
        for binary in ['openrad', 'openrad-desktop']:
            source = args.binaries / binary
            if 'elf64-x86-64' not in command('objdump', '-f', str(source)):
                raise RuntimeError(f'Unexpected ELF architecture: {binary}')
            target = output / binary
            shutil.copy2(source, target)
            command('strip', '--strip-unneeded', str(target))
            reported = command(str(target.resolve()), '--version')
            if not reported.endswith(version):
                raise RuntimeError(f'Unexpected binary version: {reported}')
            libraries = command('ldd', str(target.resolve()))
            if 'not found' in libraries:
                raise RuntimeError(f'Missing Linux library for {binary}: {libraries}')
            glibc = sorted(set(re.findall(r'GLIBC_(\d+(?:\.\d+)+)', command('objdump', '-T', str(target)))), key=lambda v: tuple(map(int, v.split('.'))))
            audit[binary] = {'version': reported, 'glibc_versions': glibc,
                             'maximum_required_glibc': glibc[-1] if glibc else None,
                             'host_shared_libraries': libraries}
        for source in ['README.md', 'CHANGELOG.md', 'LICENSE', 'CREDITS.md',
                       'docs/linux.md', 'docs/windows.md', 'docs/desktop.md', 'docs/cli.md', 'docs/cli-pt-BR.md',
                       'docs/architecture.md', 'docs/performance.md', f'docs/releases/{version}.md',
                       f'docs/releases/{version}-changes.md',
                       'docs/screenshots/1.1.0-tap-authorization.png',
                       'docs/screenshots/1.2.0-broadcast-settings-pt.png',
                       'docs/screenshots/1.0.0-networks.png',
                       'docs/screenshots/1.0.0-discover.png',
                       'docs/screenshots/1.0.0-auto-join.png',
                       'docs/screenshots/1.0.0-many-networks.png',
                       'docs/screenshots/1.0.0-identity-reset.png',
                       'docs/screenshots/windows-settings-linux-preview.png',
                       'docs/licenses/TAP-Windows6-MIT.txt', 'desktop/assets/OFL-NotoSans.txt']:
            target = output / source
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / source, target)
        _support.copy_release_history(output)
        license_notices(metadata, output, platform='Linux')
        rust_notice = args.rust_notice
        if not rust_notice.is_file():
            raise RuntimeError('Install rust-docs to include Rust standard-library notices')
        shutil.copy2(rust_notice, output / 'licenses/Rust-Standard-Library.html')
        info.update(version=version, build_date=args.build_date, target=TARGET,
                    source_tree=command('git', 'rev-parse', 'HEAD^{tree}'),
                    worktree_dirty=bool(command('git', 'status', '--porcelain')),
                    binaries_stripped=True)
        (output / 'BUILD-INFO.json').write_text(json.dumps(info, indent=2) + '\n')
        (output / 'LIBRARY-REQUIREMENTS.json').write_text(json.dumps(audit, indent=2) + '\n')
        files = sorted(p for p in output.rglob('*') if p.is_file())
        (output / 'SHA256SUMS').write_text(''.join(f'{digest(path)}  {path.relative_to(output).as_posix()}\n' for path in files))
        archive = dist / f'{name}.tar.gz'
        with tarfile.open(archive, 'w:gz') as packed:
            packed.add(output, arcname=name)
    checksum = digest(archive)
    archive.with_suffix('.gz.sha256').write_text(f'{checksum}  {archive.name}\n')
    print(json.dumps({'archive': str(archive), 'bytes': archive.stat().st_size, 'sha256': checksum,
                      'maximum_required_glibc': {name: data['maximum_required_glibc'] for name, data in audit.items()}}, indent=2))


if __name__ == '__main__':
    main()
