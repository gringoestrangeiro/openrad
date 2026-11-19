import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from release_sbom import file_component, validate_inventory, write_sbom


class ReleaseSbomTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="openrad-sbom-test-")
        self.addCleanup(self.temporary.cleanup)
        self.payload = Path(self.temporary.name)
        (self.payload / "openrad").write_bytes(b"synthetic executable")
        (self.payload / "driver.sys").write_bytes(b"synthetic driver")
        registry = "registry+https://github.com/rust-lang/crates.io-index"
        names = ("openrad-client", "normal", "transitive", "build-only", "test-only")
        packages = [{"id": name, "name": name, "version": "2.4.5" if i == 0 else "1.0.0",
                     "source": None if i == 0 else registry, "license": "MIT OR Apache-2.0"}
                    for i, name in enumerate(names)]
        self.metadata = {
            "packages": packages,
            "workspace_members": ["openrad-client"],
            "lock_packages": [{**package, "checksum": "ab" * 32}
                              for package in packages[1:]],
            "resolve": {"nodes": [
                {"id": "openrad-client", "deps": [
                    {"pkg": "normal", "dep_kinds": [{"kind": None}]},
                    {"pkg": "build-only", "dep_kinds": [{"kind": "build"}]},
                    {"pkg": "test-only", "dep_kinds": [{"kind": "dev"}]},
                ]},
                {"id": "normal", "deps": [
                    {"pkg": "transitive", "dep_kinds": [{"kind": None}]},
                ]},
                *({"id": name, "deps": []} for name in names[2:]),
            ]},
        }

    def generate(self, bundled=()):
        write_sbom(self.metadata, self.payload, "x86_64-test",
                   {"openrad": "openrad-client"}, bundled)
        return json.loads((self.payload / "SBOM.cdx.json").read_text(encoding="utf-8"))

    def test_target_graph_and_hashes_exclude_build_and_test_dependencies(self):
        driver = {"type": "device-driver", "bom-ref": "driver:synthetic", "name": "Synthetic test driver",
                  "components": [file_component(self.payload, "driver.sys")]}
        bom = self.generate([driver])
        self.assertEqual(bom["specVersion"], "1.6")
        components = {item["bom-ref"]: item for item in bom["components"]}
        self.assertEqual(set(components), {
            "pkg:cargo/openrad-client@2.4.5", "pkg:cargo/normal@1.0.0",
            "pkg:cargo/transitive@1.0.0", "file:openrad", "driver:synthetic",
        })
        self.assertEqual(components["file:openrad"]["hashes"][0]["content"],
                         hashlib.sha256(b"synthetic executable").hexdigest())
        self.assertEqual(components["pkg:cargo/transitive@1.0.0"]["hashes"][0]["content"], "ab" * 32)
        self.assertEqual(components["driver:synthetic"]["components"][0]["hashes"][0]["content"],
                         hashlib.sha256(b"synthetic driver").hexdigest())
        graph = {edge["ref"]: edge["dependsOn"] for edge in bom["dependencies"]}
        self.assertEqual(graph["pkg:cargo/openrad-client@2.4.5"], ["pkg:cargo/normal@1.0.0"])
        self.assertEqual(graph["pkg:cargo/normal@1.0.0"], ["pkg:cargo/transitive@1.0.0"])
        self.assertIn("driver:synthetic", graph[bom["metadata"]["component"]["bom-ref"]])
        validate_inventory(bom)

    def test_duplicate_nested_reference_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate SBOM reference"):
            self.generate([{"type": "device-driver", "bom-ref": "test-driver", "name": "test",
                            "components": [file_component(self.payload, "openrad")]}])

    def test_dangling_dependency_is_rejected(self):
        bom = self.generate()
        bom["dependencies"][0]["dependsOn"].append("unresolved")
        with self.assertRaisesRegex(ValueError, "Unknown SBOM dependency"):
            validate_inventory(bom)

    def test_missing_lock_checksum_fails_generation(self):
        self.metadata["lock_packages"] = []
        with self.assertRaises(KeyError):
            self.generate()

    def test_missing_binary_fails_generation(self):
        (self.payload / "openrad").unlink()
        with self.assertRaises(FileNotFoundError):
            self.generate()


if __name__ == "__main__":
    unittest.main(verbosity=2)
