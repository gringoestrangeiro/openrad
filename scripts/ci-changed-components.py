"""Select Minecraft CI components from the *latest event's* changed files.

Pull-request path filters consider the cumulative PR diff. For synchronize
events, use before..head instead, so unrelated follow-up commits do not
rebuild every Minecraft target. Unknown, rewritten, or inaccessible history
fails closed: both component matrices run.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
from pathlib import Path
from typing import Any

_SHA = re.compile(r"^[0-9a-fA-F]{40,64}$")
_BOTH_PREFIXES = (
    "tests/fixtures/",
    "clients/mod/core/src/main/java/io/github/gringoestrangeiro/openradplugin/",
)
_BOTH_EXACT = {
    ".github/workflows/minecraft-version-compat.yml",
    ".github/workflows/minecraft-2.2.0-integration.yml",
    "scripts/ci-changed-components.py",
    "tools/minecraft_version_coverage.py",
    "src/protocol.rs",
    "src/crypto.rs",
    "src/network.rs",
}


def _sha(value: Any) -> str:
    if not isinstance(value, str) or _SHA.fullmatch(value) is None:
        raise ValueError("Missing or invalid Git SHA")
    if set(value) == {"0"}:
        raise ValueError("New branch or unavailable previous commit")
    return value


def _git(cwd: Path, *args: str) -> bytes:
    return subprocess.check_output(
        ["git", *args], cwd=cwd, stderr=subprocess.DEVNULL
    )


def changed_paths(event: str, payload: dict[str, Any], cwd: Path) -> set[str] | None:
    """Return changed paths, or None to require both matrices (safe fallback)."""
    if event == "workflow_dispatch":
        return None
    try:
        if event == "push":
            before = _sha(payload["before"])
            head = _sha(payload["after"])
        elif event == "pull_request":
            request = payload["pull_request"]
            head = _sha(request["head"]["sha"])
            if payload.get("action") == "synchronize":
                before = _sha(payload["before"])
            else:
                base = _sha(request["base"]["sha"])
                before = _git(cwd, "merge-base", base, head).decode("ascii").strip()
                before = _sha(before)
        else:
            return None

        # A force-push, deleted commit or missing history cannot safely
        # inherit previously validated results.
        _git(cwd, "merge-base", "--is-ancestor", before, head)
        raw = _git(cwd, "diff", "--no-renames", "--name-only", "-z", before, head, "--")
        return {os.fsdecode(p) for p in raw.split(b"\0") if p}
    except (KeyError, TypeError, ValueError, subprocess.CalledProcessError, OSError):
        return None


def affected_components(paths: set[str] | None) -> tuple[bool, bool]:
    if paths is None:
        return True, True
    fabric = False
    paper = False
    for path in paths:
        if path in _BOTH_EXACT or path.startswith(_BOTH_PREFIXES):
            return True, True
        fabric |= path.startswith("clients/mod/")
        paper |= path.startswith("clients/plugin/")
    return fabric, paper


def main() -> None:
    event = os.environ.get("GITHUB_EVENT_NAME", "")
    path = os.environ.get("GITHUB_EVENT_PATH")
    try:
        if not path:
            raise ValueError("Missing GITHUB_EVENT_PATH")
        data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
        paths = changed_paths(event, data, Path.cwd())
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        paths = None

    fabric, paper = affected_components(paths)
    print(f"Minecraft scope: fabric={fabric}, paper={paper}")
    print("Changed paths: " + (
        "[unavailable; running all]" if paths is None else
        ", ".join(sorted(paths)) if paths else "[none]"
    ))
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with Path(output).open("a", encoding="utf-8") as f:
            f.write(f"fabric={str(fabric).lower()}\npaper={str(paper).lower()}\n")


if __name__ == "__main__":
    main()
