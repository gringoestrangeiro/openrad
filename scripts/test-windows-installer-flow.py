#!/usr/bin/env python3
"""Test NSIS install/launch decisions on Linux with Wine and synthetic programs.

No actual OpenRad executable, driver, or Windows networking API is executed.
Every run creates a fresh isolated Wine prefix under reports/windows-installer.
Requires Wine, an x64 MinGW-w64 C compiler, and NSIS (set NSISDIR if necessary).
"""
import argparse
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def command(arguments, environment, timeout=60):
    result = subprocess.run(arguments, env=environment, timeout=timeout,
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"{arguments[0]} exited with {result.returncode}:\n"
                           f"{result.stdout}\n{result.stderr}")
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--makensis', default='makensis')
    parser.add_argument('--cc', default='x86_64-w64-mingw32-gcc')
    parser.add_argument('--wine', default='wine')
    args = parser.parse_args()
    reports = ROOT / 'reports/windows-installer'
    reports.mkdir(parents=True, exist_ok=True)
    test = Path(tempfile.mkdtemp(prefix='flow-fixture-', dir=reports)).resolve()
    environment = os.environ.copy()
    environment.update(WINEPREFIX=str(test / 'wine-prefix'), WINEARCH='win64',
                       WINEDEBUG='-all', WINEDLLOVERRIDES='mscoree,mshtml=')
    command([args.cc, '-municode', '-mwindows', '-Wall', '-Wextra', '-Werror',
             str(ROOT / 'packaging/windows/test-fixtures/setup-worker.c'),
             '-o', str(test / 'worker.exe')], environment)
    payload = test / 'payload'
    payload.mkdir()
    for name in ['openrad.exe', 'openrad-desktop.exe', 'openrad-setup-helper.exe']:
        shutil.copyfile(test / 'worker.exe', payload / name)
    (payload / 'INSTALL-MANIFEST.json').write_text('{"synthetic":true}\n')
    (payload / 'Launch-CLI.cmd').write_bytes(b'@exit /b 0\r\n')
    delete = test / 'delete.nsh'
    delete.write_text(''.join(f'Delete "$INSTDIR\\{p.name}"\n'
                              for p in sorted(payload.iterdir())))
    setup = test / 'OpenRad-Setup-Flow-Fixture.exe'
    command([args.makensis, '-V2', '-WX', '-DOPENRAD_VERSION=0.0.0',
             f'-DPAYLOAD_DIR={payload}', f'-DSETUP_EXE={setup}',
             f'-DDELETE_INCLUDE={delete}',
             str(ROOT / 'packaging/windows/OpenRad-Setup.nsi')], environment)
    # Wine initializes only this new prefix; never use the user's default prefix.
    command([args.wine, 'wineboot.exe', '-u'], environment)
    checks = []

    def run(flags, directory, configured, desktop, label, custom=True):
        arguments = [args.wine, str(setup), '/S', *flags]
        if custom:
            arguments.append('/D=C:\\' + directory)
        command(arguments, environment, timeout=30)
        installed = test / 'wine-prefix/drive_c' / directory

        def count(name):
            path = installed / name
            return int(path.read_text()) if path.exists() else 0

        deadline = time.monotonic() + 8
        while count('desktop-count.txt') < desktop and time.monotonic() < deadline:
            time.sleep(0.1)
        actual = (count('configured-count.txt'), count('desktop-count.txt'))
        if actual != (configured, desktop):
            raise AssertionError(f'{label}: configure/desktop calls {actual}, '
                                 f'expected {(configured, desktop)}')
        checks.append({'case': label, 'configure_count': configured,
                       'desktop_count': desktop, 'result': 'passed'})

    run(['--no-launch'], 'Program Files/OpenRad', 1, 0,
        'first install uses Program Files without a directory override', custom=False)
    run(['--no-launch'], 'OpenRadFixture', 1, 0, 'first custom install with --no-launch')
    run(['--no-launch'], 'OpenRadFixture', 1, 0, 'repeat install with --no-launch')
    run([], 'OpenRadFixture', 1, 1, 'repeat launches desktop without reinstall')
    run(['--repair', '--no-launch'], 'OpenRadFixture', 2, 1, 'repair with --no-launch')
    run([], 'OpenRadFresh', 1, 1, 'custom folder overrides previous install location')
    run([], 'OpenRadFresh', 1, 2, 'repeat uses recorded install location', custom=False)
    (test / 'wine-prefix/drive_c/OpenRadFresh/Uninstall-OpenRad.exe').unlink()
    run(['--no-launch'], 'OpenRadFresh', 2, 2, 'missing uninstaller repairs registration', custom=False)
    print(json.dumps({'environment': command([args.wine, '--version'], environment) + ' on Linux',
                      'fixture_directory': str(test), 'silent_control_flow_only': True,
                      'real_openrad_executed': False,
                      'real_driver_or_os_adapter_cmdlets_executed': False,
                      'cases': checks}, indent=2))


if __name__ == '__main__':
    main()
