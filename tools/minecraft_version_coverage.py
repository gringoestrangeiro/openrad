#!/usr/bin/env python3
"""Generate a truthful Minecraft Java release compatibility report, 1.8 through 26.3."""
import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FABRIC_RELEASES = {"1.21", *(f"1.21.{n}" for n in range(1, 12)),
                   "26.1", "26.1.1", "26.1.2", "26.2", "26.3"}
PAPER_EXACT_BUILDS = {"1.21", "26.1.1", "26.1.2", "26.2", "26.3"}

def release_tuple(version):
    if not re.fullmatch(r"(?:1|26)\.\d+(?:\.\d+)?", version):
        return None
    numbers = [int(n) for n in version.split(".")]
    numbers += [0] * (3 - len(numbers))
    result = tuple(numbers)
    return result if (1, 8, 0) <= result <= (26, 3, 0) else None

def classify(version):
    parsed = release_tuple(version)
    if parsed is None:
        raise ValueError("Not a stable Minecraft Java release within 1.8..26.3")
    if version in FABRIC_RELEASES:
        fabric = "published-build-verified"
    elif version in {"1.17", "1.17.1"}:
        fabric = "experimental-java16-build-verified-runtime-untested"
    elif version in {"1.18", "1.18.1", "1.18.2", "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4", "1.20.1", "1.20.2", "1.20.3", "1.20.4"}:
        fabric = "experimental-java17-build-verified-runtime-untested"
    elif version in {"1.20.5", "1.20.6"}:
        fabric = "development-build-verified"
    elif parsed < (1, 14, 0):
        fabric = "not-targeted-no-fabric-before-1.14"
    else:
        fabric = "requires-game-api-and-java-port"
    if version in PAPER_EXACT_BUILDS:
        paper = "published-build-verified"
    elif version in {"1.17", "1.17.1"}:
        paper = "experimental-java16-build-verified-runtime-untested"
    elif parsed >= (1, 21, 0) and parsed < (26, 0, 0):
        paper = "paper-line-build-needs-runtime-test"
    elif version in {"1.18", "1.18.1", "1.18.2", "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4", "1.20.1", "1.20.2", "1.20.3", "1.20.4"}:
        paper = "experimental-java17-build-verified-runtime-untested"
    elif version == "1.20.6":
        paper = "development-build-verified"
    elif version == "1.20.5":
        paper = "development-build-verified"
    else:
        paper = "requires-paper-api-and-java-port"
    if version == "26.1":
        paper = "unsupported-unpatched-paper"
    return {"minecraft": version, "fabric": fabric, "paper": paper}

def stable_versions(manifest):
    entries = manifest.get("versions")
    if not isinstance(entries, list):
        raise ValueError("Manifest missing 'versions'")
    versions = {e["id"] for e in entries if isinstance(e, dict)
                and e.get("type") == "release" and isinstance(e.get("id"), str)
                and release_tuple(e["id"]) is not None}
    if not versions:
        raise ValueError("No stable versions in requested range")
    return sorted(versions, key=release_tuple)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, help="Offline Mojang manifest JSON file")
    parser.add_argument("--output", type=Path, help="Write JSON report")
    args = parser.parse_args()
    try:
        if args.manifest:
            manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        else:
            with urllib.request.urlopen(MOJANG_MANIFEST, timeout=18) as f:
                manifest = json.load(f)
        versions = stable_versions(manifest)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    result = {"baseVersion": "2.2.0",
              "sourceManifest": MOJANG_MANIFEST,
              "range": ["1.8", "26.3"],
              "warning": "Build verified does not mean tested with real Minecraft or Radmin peers.",
              "totalStableReleases": len(versions),
              "versions": [classify(version) for version in versions]}
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
        print("Created coverage report with", len(versions), "stable releases:", args.output)
    else:
        sys.stdout.write(output)

if __name__ == "__main__":
    main()
