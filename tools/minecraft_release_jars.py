#!/usr/bin/env python3
"""Combine per-patch Minecraft release JARs into one JAR per major version.

The CI matrix builds and tests every supported Minecraft patch separately.
This tool merges consecutive patches of the same major version (1.17, 1.18,
..., 1.21, 26) only when their JARs are byte-identical apart from the version
metadata, so a merged JAR contains exactly the classes, mixins and refmaps that
were compiled and tested against each patch it lists:

* Fabric: only ``fabric.mod.json`` may differ, and only in
  ``depends.minecraft`` and ``description``, plus Loom's informational
  ``Fabric-Minecraft-Version`` manifest line (the merged JAR keeps the value of
  the build its bytes come from). The merged JAR lists every covered
  patch in ``depends.minecraft`` (Fabric Loader accepts any entry of a list),
  so Fabric Loader still refuses an untested patch.
* Paper: only ``plugin.yml`` may differ, and only in ``api-version``. The merged
  JAR is the lowest patch's JAR unchanged; Paper loads a plugin whose
  ``api-version`` is not newer than the server.

A major whose patches need different builds is split into ranges, so a JAR is
never stretched over a patch it was not proven on. Output names contain only the
Minecraft version or range, never the Java version:
``OpenRadClientMod-2.3.0-fabric-1.21-1.21.5.jar``, ``OpenRadPlugin-2.3.0-paper-1.17-1.17.1.jar``.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FABRIC_METADATA = "fabric.mod.json"
PAPER_METADATA = "plugin.yml"
MANIFEST = "META-INF/MANIFEST.MF"
LOADERS = {
    "fabric": ("OpenRadClientMod", FABRIC_METADATA),
    "paper": ("OpenRadPlugin", PAPER_METADATA),
}


def version_key(version: str) -> tuple[int, ...]:
    if not re.fullmatch(r"\d+(?:\.\d+){1,2}", version):
        raise ValueError(f"Not a Minecraft release version: {version!r}")
    numbers = [int(n) for n in version.split(".")]
    return tuple(numbers + [0] * (3 - len(numbers)))


def major_of(version: str) -> str:
    """1.21.5 -> 1.21; 26.1.2 -> 26 (year-based versions share one line)."""
    parts = version.split(".")
    return parts[0] if parts[0] != "1" else ".".join(parts[:2])


def label(versions: list[str]) -> str:
    return versions[0] if len(versions) == 1 else f"{versions[0]}-{versions[-1]}"


@dataclass
class Source:
    loader: str
    minecraft: str
    path: Path
    entries: dict[str, bytes] = field(default_factory=dict)

    def load(self) -> "Source":
        with zipfile.ZipFile(self.path) as jar:
            names = jar.namelist()
            if len(names) != len(set(names)):
                raise ValueError(f"{self.path.name}: duplicate ZIP entries")
            self.entries = {name: jar.read(name) for name in names}
        return self


def find_sources(directory: Path, mod_version: str) -> dict[str, dict[str, Source]]:
    found: dict[str, dict[str, Source]] = {loader: {} for loader in LOADERS}
    for loader, (prefix, _) in LOADERS.items():
        pattern = re.compile(
            re.escape(f"{prefix}-{mod_version}-{loader}-") + r"(\d+(?:\.\d+){1,2})(?:-java\d+)?\.jar")
        for path in sorted(directory.rglob(f"{prefix}-{mod_version}-{loader}-*.jar")):
            match = pattern.fullmatch(path.name)
            if not match:
                raise ValueError(f"Unexpected JAR name: {path.name}")
            minecraft = match.group(1)
            if minecraft in found[loader]:
                raise ValueError(f"Two {loader} JARs for Minecraft {minecraft}")
            found[loader][minecraft] = Source(loader, minecraft, path)
    return found


def fabric_identity(source: Source) -> dict:
    metadata = json.loads(source.entries[FABRIC_METADATA])
    if metadata.get("depends", {}).get("minecraft") != source.minecraft:
        raise ValueError(f"{source.path.name}: fabric.mod.json does not target {source.minecraft}")
    metadata = json.loads(json.dumps(metadata))
    metadata["depends"].pop("minecraft")
    metadata.pop("description", None)
    return metadata


def paper_identity(source: Source) -> tuple[str, str]:
    text = source.entries[PAPER_METADATA].decode("utf-8")
    versions = re.findall(r"^api-version: '([0-9.]+)'$", text, re.M)
    if len(versions) != 1:
        raise ValueError(f"{source.path.name}: expected one api-version in plugin.yml")
    return versions[0], re.sub(r"^api-version: .*$", "api-version: <merged>", text, flags=re.M)


def manifest_identity(source: Source) -> bytes:
    """Loom records the compile target as Fabric-Minecraft-Version; loading ignores it."""
    return re.sub(rb"(?m)^Fabric-Minecraft-Version: [^\r\n]*\r?\n", b"", source.entries[MANIFEST])


def equivalent(a: Source, b: Source) -> bool:
    metadata = {LOADERS[a.loader][1]} | ({MANIFEST} if a.loader == "fabric" else set())
    if set(a.entries) != set(b.entries):
        return False
    if any(a.entries[n] != b.entries[n] for n in a.entries if n not in metadata):
        return False
    if a.loader == "fabric":
        return (fabric_identity(a) == fabric_identity(b)
                and manifest_identity(a) == manifest_identity(b))
    (api_a, rest_a), (api_b, rest_b) = paper_identity(a), paper_identity(b)
    return rest_a == rest_b and version_key(api_a) <= version_key(api_b)


def group(sources: dict[str, Source]) -> list[list[Source]]:
    groups: list[list[Source]] = []
    for minecraft in sorted(sources, key=version_key):
        source = sources[minecraft].load()
        current = groups[-1] if groups else None
        if (current and major_of(current[0].minecraft) == major_of(minecraft)
                and equivalent(current[0], source)):
            current.append(source)
        else:
            groups.append([source])
    return groups


def fabric_metadata(base: Source, versions: list[str]) -> bytes:
    if len(versions) == 1:
        return base.entries[FABRIC_METADATA]
    text = base.entries[FABRIC_METADATA].decode("utf-8")
    target = json.dumps(base.minecraft)
    text, count = re.subn(r'("minecraft"\s*:\s*)' + re.escape(target),
                          lambda m: m.group(1) + "[" + ", ".join(json.dumps(v) for v in versions) + "]",
                          text)
    if count != 1:
        raise ValueError(f"{base.path.name}: cannot rewrite depends.minecraft")
    span = f"{versions[0]}–{versions[-1]} ({', '.join(versions)})"
    text, count = re.subn(r"(for Fabric Minecraft )" + re.escape(base.minecraft) + r"\.",
                          lambda m: m.group(1) + span + ".", text)
    if count != 1:
        raise ValueError(f"{base.path.name}: cannot rewrite description")
    expected = json.loads(base.entries[FABRIC_METADATA])
    expected["depends"]["minecraft"] = versions
    actual = json.loads(text)
    actual_without_description = {k: v for k, v in actual.items() if k != "description"}
    if actual_without_description != {k: v for k, v in expected.items() if k != "description"}:
        raise ValueError(f"{base.path.name}: rewritten fabric.mod.json changed other fields")
    return text.encode("utf-8")


def write_jar(base: Source, output: Path, replacements: dict[str, bytes]) -> None:
    """Copy BASE entry by entry (order, timestamps, compression), replacing data."""
    with zipfile.ZipFile(base.path) as source, zipfile.ZipFile(output, "w") as target:
        for info in source.infolist():
            data = replacements.get(info.filename, source.read(info.filename))
            target.writestr(info, data, compress_type=info.compress_type)


def load_verifier():
    path = ROOT / "clients/mod/tools/verify-version-jar.py"
    spec = importlib.util.spec_from_file_location("verify_version_jar", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(input_dir: Path, output_dir: Path, mod_version: str,
          expected: dict[str, set[str]] | None = None, verify_fabric: bool = True) -> dict:
    sources = find_sources(input_dir, mod_version)
    if expected is not None:
        for loader, versions in expected.items():
            actual = set(sources[loader])
            if actual != versions:
                raise ValueError(f"{loader}: missing {sorted(versions - actual, key=version_key)}, "
                                 f"unexpected {sorted(actual - versions, key=version_key)}")
    if not any(sources.values()):
        raise ValueError(f"No {mod_version} Minecraft JARs found under {input_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    verifier = load_verifier() if verify_fabric else None
    report: dict = {"mod_version": mod_version, "jars": []}
    for loader, (prefix, _) in LOADERS.items():
        for members in group(sources[loader]):
            base = members[0]
            versions = [m.minecraft for m in members]
            output = output_dir / f"{prefix}-{mod_version}-{loader}-{label(versions)}.jar"
            if output.exists():
                raise ValueError(f"Refusing to overwrite {output}")
            replacements = {FABRIC_METADATA: fabric_metadata(base, versions)} if loader == "fabric" else {}
            write_jar(base, output, replacements)
            if loader == "fabric" and verifier is not None:
                for minecraft in versions:
                    verifier.verify(output, minecraft, mod_version)
            report["jars"].append({
                "file": output.name,
                "loader": loader,
                "minecraft": versions,
                "built_from": {m.minecraft: {"file": m.path.name, "sha256": sha256(m.path)} for m in members},
                "sha256": sha256(output),
            })
    with (output_dir / "SHA256SUMS").open("w", encoding="utf-8") as sums:
        for jar in report["jars"]:
            sums.write(f"{jar['sha256']}  {jar['file']}\n")
    (output_dir / "minecraft-release-jars.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def expected_targets() -> dict[str, set[str]]:
    """All targets scheduled by the CI matrix workflow (fail closed if one is missing)."""
    text = (ROOT / ".github/workflows/minecraft-version-compat.yml").read_text(encoding="utf-8")
    fabric = re.search(r"^\s{8}minecraft: (\[.*\])\s*$", text, re.M)
    paper_block = text.split("\n  paper:", 1)[-1].split("\n    steps:", 1)[0]
    if fabric is None:
        raise ValueError("Fabric matrix not found in minecraft-version-compat.yml")
    return {
        "fabric": set(json.loads(fabric.group(1).replace("'", '"'))),
        "paper": set(re.findall(r"^\s{10}- minecraft: '([^']+)'\s*$", paper_block, re.M)),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("input", type=Path, help="directory with the per-patch verified JARs")
    parser.add_argument("output", type=Path, help="new directory for the release JARs")
    parser.add_argument("--mod-version", required=True)
    parser.add_argument("--allow-partial", action="store_true",
                        help="do not require every target of the CI matrix (local experiments only)")
    args = parser.parse_args()
    report = build(args.input, args.output, args.mod_version,
                   None if args.allow_partial else expected_targets())
    for jar in report["jars"]:
        print(f"{jar['file']}: {', '.join(jar['minecraft'])}")
    print(f"{len(report['jars'])} release JARs written to {args.output}")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        sys.exit(f"error: {error}")
