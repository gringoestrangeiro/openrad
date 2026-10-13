"""Run the OpenRAD Python unit tests, including a stdlib-only pass."""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def main():
    env = dict(os.environ, PYTHONPATH=str(ROOT))
    first = subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', 'tests', '-v'], cwd=ROOT, env=env)
    if first.returncode:
        return first.returncode
    # Second pass without site-packages: also exercises the pure-Python AES fallback.
    return subprocess.run([sys.executable, '-S', '-m', 'unittest', 'discover', '-s', 'tests', '-v'],
                          cwd=ROOT, env=env).returncode


if __name__ == '__main__':
    raise SystemExit(main())
