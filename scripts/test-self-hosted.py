#!/usr/bin/env python3
"""Run the Rust control-plane test with a disposable real OpenRad coordinator."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]


def main():
    with tempfile.TemporaryDirectory(prefix="openrad-self-hosted-") as tmp:
        root = Path(tmp)
        server = root / ("coordinator.exe" if os.name == "nt" else "coordinator")
        subprocess.run(["go", "build", "-mod=readonly", "-o", server, "."],
                       cwd=ROOT / "server", check=True, timeout=300)
        log = root / "coordinator.log"
        with log.open("wb") as output, subprocess.Popen(
            [server, "--db", root / "test.db", "serve", "--listen", "127.0.0.1:0",
             "--relay-listen", "127.0.0.1:0", "--public", "OpenRad loopback test"],
            stdout=output, stderr=subprocess.STDOUT,
        ) as child:
            try:
                deadline = time.monotonic() + 15
                while child.poll() is None and time.monotonic() < deadline:
                    match = re.search(r"control listening on 127\.0\.0\.1:(\d+)", log.read_text())
                    if match:
                        break
                    time.sleep(0.05)
                else:
                    raise RuntimeError("Local coordinator did not start")
                subprocess.run(
                    ["cargo", "test", "--locked", "-p", "openrad-client", "--test", "self_hosted",
                     "--", "--ignored"], cwd=ROOT, check=True, timeout=300, env={
                        **os.environ, "OPENRAD_TEST_COORDINATOR_DIR": tmp,
                        "OPENRAD_TEST_COORDINATOR_PORT": match[1],
                    },
                )
            except Exception:
                print(log.read_text(errors="replace"))
                raise
            finally:
                child.terminate()
                child.wait(timeout=10)


if __name__ == "__main__":
    main()
