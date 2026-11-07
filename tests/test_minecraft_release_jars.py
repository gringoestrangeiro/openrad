"""Synthetic regressions for merging per-patch Minecraft JARs into release JARs."""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.minecraft_release_jars import build, expected_targets, major_of

FABRIC_JSON = """{
  "schemaVersion": 1,
  "id": "openrad-client",
  "version": "2.3.0",
  "description": "OpenRad for Fabric Minecraft %s.",
  "depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": "%s",
    "java": ">=21"
  }
}
"""


def fabric_jar(directory, minecraft, payload=b"same", manifest_extra=""):
    path = Path(directory) / f"OpenRadClientMod-2.3.0-fabric-{minecraft}.jar"
    with zipfile.ZipFile(path, "w") as jar:
        jar.writestr("META-INF/MANIFEST.MF",
                     f"Manifest-Version: 1.0\r\nFabric-Minecraft-Version: {minecraft}\r\n{manifest_extra}")
        jar.writestr("fabric.mod.json", FABRIC_JSON % (minecraft, minecraft))
        jar.writestr("a/Mod.class", payload)
    return path


def paper_jar(directory, minecraft, api, payload=b"same", java=None, description="Paper"):
    suffix = f"-java{java}" if java else ""
    path = Path(directory) / f"OpenRadPlugin-2.3.0-paper-{minecraft}{suffix}.jar"
    with zipfile.ZipFile(path, "w") as jar:
        jar.writestr("plugin.yml", f"name: OpenRadPlugin\napi-version: '{api}'\ndescription: {description}\n")
        jar.writestr("a/Plugin.class", payload)
    return path


class MinecraftReleaseJarTests(unittest.TestCase):
    def run_build(self, make):
        source = tempfile.TemporaryDirectory()
        output = tempfile.TemporaryDirectory()
        self.addCleanup(source.cleanup)
        self.addCleanup(output.cleanup)
        make(source.name)
        report = build(Path(source.name), Path(output.name) / "out", "2.3.0", verify_fabric=False)
        return report, Path(output.name) / "out"

    def files(self, report):
        return {jar["file"]: jar["minecraft"] for jar in report["jars"]}

    def test_identical_fabric_patches_share_one_jar_listing_each_patch(self):
        report, out = self.run_build(lambda d: [fabric_jar(d, v) for v in ("1.18", "1.18.1", "1.18.2")])
        self.assertEqual(self.files(report), {
            "OpenRadClientMod-2.3.0-fabric-1.18-1.18.2.jar": ["1.18", "1.18.1", "1.18.2"]})
        with zipfile.ZipFile(out / "OpenRadClientMod-2.3.0-fabric-1.18-1.18.2.jar") as jar:
            metadata = json.loads(jar.read("fabric.mod.json"))
            self.assertEqual(metadata["depends"]["minecraft"], ["1.18", "1.18.1", "1.18.2"])
            self.assertEqual(metadata["depends"]["java"], ">=21")
            self.assertEqual(jar.read("a/Mod.class"), b"same")
            self.assertIn(b"Fabric-Minecraft-Version: 1.18\r\n", jar.read("META-INF/MANIFEST.MF"))
        sums = (out / "SHA256SUMS").read_text(encoding="utf-8")
        self.assertIn("OpenRadClientMod-2.3.0-fabric-1.18-1.18.2.jar", sums)

    def test_patch_with_different_classes_gets_its_own_jar(self):
        report, _ = self.run_build(lambda d: [
            fabric_jar(d, "1.19"), fabric_jar(d, "1.19.1"),
            fabric_jar(d, "1.19.3", payload=b"other"), fabric_jar(d, "1.19.4")])
        self.assertEqual(self.files(report), {
            "OpenRadClientMod-2.3.0-fabric-1.19-1.19.1.jar": ["1.19", "1.19.1"],
            "OpenRadClientMod-2.3.0-fabric-1.19.3.jar": ["1.19.3"],
            "OpenRadClientMod-2.3.0-fabric-1.19.4.jar": ["1.19.4"]})

    def test_other_manifest_differences_prevent_merging(self):
        report, _ = self.run_build(lambda d: [
            fabric_jar(d, "1.20.2"), fabric_jar(d, "1.20.3", manifest_extra="Extra: 1\r\n")])
        self.assertEqual(len(report["jars"]), 2)

    def test_identical_builds_never_span_two_majors(self):
        report, _ = self.run_build(lambda d: [fabric_jar(d, v) for v in ("1.20.5", "1.20.6", "1.21", "1.21.1")])
        self.assertEqual(sorted(self.files(report)), [
            "OpenRadClientMod-2.3.0-fabric-1.20.5-1.20.6.jar", "OpenRadClientMod-2.3.0-fabric-1.21-1.21.1.jar"])

    def test_year_versions_form_one_major(self):
        self.assertEqual(major_of("26.1"), major_of("26.3"))
        self.assertEqual(major_of("1.21.11"), "1.21")

    def test_paper_merge_keeps_lowest_api_version_and_drops_java_suffix(self):
        report, out = self.run_build(lambda d: [
            paper_jar(d, "1.20.5", "1.20.5", java=21), paper_jar(d, "1.20.6", "1.20.6", java=21)])
        self.assertEqual(self.files(report), {"OpenRadPlugin-2.3.0-paper-1.20.5-1.20.6.jar": ["1.20.5", "1.20.6"]})
        with zipfile.ZipFile(out / "OpenRadPlugin-2.3.0-paper-1.20.5-1.20.6.jar") as jar:
            self.assertIn("api-version: '1.20.5'", jar.read("plugin.yml").decode())

    def test_paper_metadata_beyond_api_version_prevents_merging(self):
        report, _ = self.run_build(lambda d: [
            paper_jar(d, "1.17", "1.17"), paper_jar(d, "1.17.1", "1.17", description="Other")])
        self.assertEqual(len(report["jars"]), 2)

    def test_missing_matrix_target_fails_closed(self):
        with tempfile.TemporaryDirectory() as source, tempfile.TemporaryDirectory() as output:
            fabric_jar(source, "1.18")
            with self.assertRaisesRegex(ValueError, "missing"):
                build(Path(source), Path(output) / "out", "2.3.0",
                      {"fabric": {"1.18", "1.18.1"}, "paper": set()}, verify_fabric=False)

    def test_expected_targets_follow_the_ci_matrix(self):
        targets = expected_targets()
        self.assertEqual(len(targets["fabric"]), 33)
        self.assertEqual(len(targets["paper"]), 21)


if __name__ == "__main__":
    unittest.main()
