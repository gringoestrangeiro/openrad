"""Inventory a Rust release payload and its target-resolved Cargo dependencies."""
import hashlib
import json
import re
from urllib.parse import quote


def file_hash(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def file_component(directory, name, kind="file"):
    return {
        "type": kind,
        "bom-ref": f"file:{name}",
        "name": name,
        "hashes": [{"alg": "SHA-256", "content": file_hash(directory / name)}],
    }


def embedded_font_component(root):
    component = file_component(root, "desktop/assets/NotoSans-Regular.ttf")
    component["bom-ref"] = "embedded:noto-sans-regular"
    component["name"] = "Noto Sans Regular"
    component["licenses"] = [{"expression": "OFL-1.1"}]
    component["properties"] = [{"name": "openrad:embedded-in", "value": "openrad-desktop"}]
    return component


def validate_inventory(bom):
    references = set()
    def visit(component):
        ref = component["bom-ref"]
        if ref in references:
            raise ValueError(f"Duplicate SBOM reference: {ref}")
        references.add(ref)
        for checksum in component.get("hashes", []):
            if checksum["alg"] == "SHA-256" and not re.fullmatch("[0-9a-f]{64}", checksum["content"]):
                raise ValueError(f"Invalid SHA-256 for {ref}")
        for child in component.get("components", []):
            visit(child)

    visit(bom["metadata"]["component"])
    for component in bom["components"]:
        visit(component)
    declared = set()
    for edge in bom["dependencies"]:
        if edge["ref"] not in references or edge["ref"] in declared:
            raise ValueError(f"Invalid SBOM dependency source: {edge['ref']}")
        if not set(edge["dependsOn"]) <= references:
            raise ValueError(f"Unknown SBOM dependency for {edge['ref']}")
        declared.add(edge["ref"])
    expected = {bom["metadata"]["component"]["bom-ref"]}
    expected.update(component["bom-ref"] for component in bom["components"])
    if declared != expected:
        raise ValueError("Incomplete top-level SBOM dependency graph")


def write_sbom(metadata, directory, target, binaries, bundled=()):
    packages = {package["id"]: package for package in metadata["packages"]}
    lock_checksums = {
        (entry["name"], entry["version"]): entry["checksum"]
        for entry in metadata["lock_packages"]
        if entry.get("source") == "registry+https://github.com/rust-lang/crates.io-index"
        and "checksum" in entry
    }
    workspace = {packages[key]["name"]: key for key in metadata["workspace_members"]}
    graph = {
        node["id"]: {dep["pkg"] for dep in node["deps"]
                     if any(kind["kind"] is None for kind in dep["dep_kinds"])}
        for node in metadata["resolve"]["nodes"]
    }
    seen = set()
    pending = [workspace[crate] for crate in binaries.values()]
    while pending:
        key = pending.pop()
        if key not in seen:
            seen.add(key)
            pending.extend(graph[key])

    def purl(key):
        pkg = packages[key]
        if pkg["source"] not in (None, "registry+https://github.com/rust-lang/crates.io-index"):
            raise ValueError(f"Unsupported crate source for {pkg['name']}")
        return f"pkg:cargo/{quote(pkg['name'], safe='')}@{quote(pkg['version'], safe='')}"

    components = []
    edges = {}
    for key in sorted(seen, key=purl):
        pkg = packages[key]
        ref = purl(key)
        component = {"type": "library", "bom-ref": ref, "purl": ref,
                     "name": pkg["name"], "version": pkg["version"]}
        if pkg["license"]:
            component["licenses"] = [{"expression": pkg["license"].replace("/", " OR ")}]
        if pkg["source"]:
            checksum = lock_checksums[pkg["name"], pkg["version"]]
            component["hashes"] = [{"alg": "SHA-256", "content": checksum}]
        components.append(component)
        edges[ref] = sorted(map(purl, graph[key]))

    for executable, crate in sorted(binaries.items()):
        component = file_component(directory, executable, "application")
        components.append(component)
        edges[component["bom-ref"]] = [purl(workspace[crate])]

    for component in bundled:
        components.append(component)
        edges[component["bom-ref"]] = []

    version = packages[workspace["openrad-client"]]["version"]
    root = f"openrad:{version}:{target}"
    edges[root] = sorted(f"file:{executable}" for executable in binaries)
    edges[root].extend(component["bom-ref"] for component in bundled)
    result = {
        "$schema": "http://cyclonedx.org/schema/bom-1.6.schema.json",
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "version": 1,
        "metadata": {
            "component": {"type": "application", "bom-ref": root, "name": "OpenRad",
                          "version": version, "properties": [
                              {"name": "openrad:target", "value": target},
                              {"name": "openrad:scope", "value":
                               "Target-filtered Cargo normal dependency graph and explicitly inventoried bundled components; not per-binary linker evidence"},
                          ]},
        },
        "components": components,
        "dependencies": [{"ref": ref, "dependsOn": edges[ref]} for ref in sorted(edges)],
    }
    validate_inventory(result)
    (directory / "SBOM.cdx.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
