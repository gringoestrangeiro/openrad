"""Keep every declared Minecraft adapter target represented by a CI build job.

This checks what gets scheduled; it does not replace a successful CI run or runtime
multiplayer tests.
"""
import ast
import re
import unittest
from pathlib import Path

from tools.minecraft_version_coverage import FABRIC_RELEASES, PAPER_EXACT_BUILDS

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/minecraft-version-compat.yml"

LEGACY_TARGETS = {
    "1.17", "1.17.1", "1.18", "1.18.1", "1.18.2",
    "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4",
    "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
}
EXPECTED_FABRIC = LEGACY_TARGETS | FABRIC_RELEASES
EXPECTED_PAPER = LEGACY_TARGETS | PAPER_EXACT_BUILDS


class MinecraftCIMatrixTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        m = re.search(r"^\s{8}minecraft: (\[.*\])\s*$", cls.text, re.M)
        if m is None:
            raise AssertionError("Fabric CI matrix is missing or not explicit")
        cls.fabric = ast.literal_eval(m.group(1))
        paper = cls.text.split("\n  paper:", 1)[-1].split("\n    steps:", 1)[0]
        cls.paper = re.findall(r"^\s{10}- minecraft: '([^']+)'\s*$", paper, re.M)

    def test_all_fabric_targets_are_explicit_and_unique(self):
        self.assertEqual(len(self.fabric), len(set(self.fabric)))
        self.assertEqual(set(self.fabric), EXPECTED_FABRIC)
        self.assertEqual(len(self.fabric), 33)

    def test_all_paper_targets_are_explicit_and_unique(self):
        self.assertEqual(len(self.paper), len(set(self.paper)))
        self.assertEqual(set(self.paper), EXPECTED_PAPER)
        self.assertEqual(len(self.paper), 21)
        self.assertNotIn("26.1", self.paper)  # Unsupported unpatched Paper API

    def step_conditions(self, job):
        """Map each step name of one workflow job to its `if:` expression."""
        block = self.text.split(f"\n  {job}:", 1)[1]
        block = re.split(r"\n  [a-z]+:\n", block, maxsplit=1)[0]
        conditions = {}
        for step in re.split(r"\n      - ", block)[1:]:
            name = re.match(r"name: (.+)", step)
            condition = re.search(r"^\s+if: \$\{\{ (.+) \}\}\s*$", step, re.M)
            if name:
                conditions[name.group(1).strip()] = condition.group(1) if condition else None
        return conditions

    def test_modern_targets_use_separate_java25_build(self):
        steps = self.step_conditions("fabric")
        modern = "startsWith(matrix.minecraft, '26.')"
        regular = "!" + modern
        for name in ("Compile and test modern Fabric on Java 25",
                     "Verify modern Fabric version, bytecode, mixins and metadata"):
            self.assertEqual(steps[name], modern, name)
        for name in ("Compile, remap and test regular Fabric target",
                     "Verify regular Fabric version, bytecode, mixins and metadata"):
            self.assertEqual(steps[name], regular, name)
        self.assertIn("working-directory: clients/mod/modern", self.text)
        for version in ("26.1", "26.1.1", "26.1.2", "26.2", "26.3"):
            self.assertIn(version, self.fabric)

    def test_verifier_tests_run_in_inventory_job(self):
        inventory = self.text.split("\n  fabric:", 1)[0]
        self.assertIn("test_verify_version_jar.py", inventory)

    def test_paper_26_targets_use_matching_api(self):
        for version in ("26.1.1", "26.1.2", "26.2", "26.3"):
            self.assertRegex(self.text, re.escape(f"minecraft: '{version}'") +
                             r"\s+api: '" + re.escape(version) + r"\.build\.\+'\s+java: 25")

    def test_versioned_artifacts_and_checksum_uploaded(self):
        self.assertIn("openrad-2.2.0-fabric-${{ matrix.minecraft }}-verified", self.text)
        self.assertIn("openrad-2.2.0-paper-${{ matrix.minecraft }}-verified", self.text)
        self.assertIn("sha256sum", self.text)
        self.assertIn("if-no-files-found: error", self.text)


if __name__ == "__main__":
    unittest.main()
