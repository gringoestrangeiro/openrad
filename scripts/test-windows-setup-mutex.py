"""Test the production NSIS mutex macro without running OpenRad setup.

Windows only. Pass --makensis PATH for NSIS 3.11+. The disposable executable
contains only the extracted mutex macro, runs unelevated, and uses a unique
Local kernel-object name. No installer payload, registry, driver, or VPN is used.
"""
import argparse
import ctypes
from ctypes import wintypes
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--makensis', default=shutil.which('makensis'))
    args = parser.parse_args()
    if sys.platform != 'win32':
        parser.error('Native Windows is required for kernel-object tests')
    if not args.makensis:
        parser.error('Pass --makensis PATH for NSIS 3.11 or newer')

    source = (Path(__file__).resolve().parents[1] /
              'packaging/windows/OpenRad-Setup.nsi').read_text(encoding='utf-8')
    macro = source[source.index('!macro OpenRadAcquireSetupMutex STOP'):]
    macro = macro[:macro.index('!macroend') + len('!macroend')]
    name = 'Local\\OpenRadAuditMutex-' + uuid.uuid4().hex
    macro = macro.replace('Global\\OpenRadSetup', name)

    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR]
    kernel.CreateMutexW.restype = wintypes.HANDLE
    kernel.CreateEventW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR]
    kernel.CreateEventW.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]

    with tempfile.TemporaryDirectory(prefix='openrad-mutex-test-') as temporary:
        directory = Path(temporary)
        executable = directory / 'mutex-only.exe'
        script = directory / 'mutex-only.nsi'
        script.write_text('''Unicode true
RequestExecutionLevel user
SilentInstall silent
AutoCloseWindow true
Name "Disposable mutex regression"
OutFile "''' + str(executable) + '''"
!include "LogicLib.nsh"
Var SetupMutex
!macro FixtureLog TEXT
!macroend
!define Log "!insertmacro FixtureLog"
''' + macro + '''
Function .onInit
!insertmacro OpenRadAcquireSetupMutex Quit
FunctionEnd
Section
SectionEnd
''', encoding='utf-8')
        subprocess.run([args.makensis, '/V2', '/WX', str(script)], check=True)

        def check(label, expected):
            result = subprocess.run([str(executable), '/S'], timeout=15)
            if result.returncode != expected:
                raise AssertionError(f'{label}: expected {expected}, got {result.returncode}')
            print(f'PASS: {label}', flush=True)

        check('uncontended mutex permits execution', 0)
        handle = kernel.CreateMutexW(None, False, name)
        if not handle:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            check('existing mutex rejects a second mutation', 1)
        finally:
            kernel.CloseHandle(handle)

        # An existing event with the same name makes CreateMutex return NULL.
        handle = kernel.CreateEventW(None, False, False, name)
        if not handle:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            check('failed mutex creation prevents execution', 1)
        finally:
            kernel.CloseHandle(handle)


if __name__ == '__main__':
    main()
