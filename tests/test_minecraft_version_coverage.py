import importlib.util
import unittest
from pathlib import Path
script = Path(__file__).resolve().parents[1] / "tools" / "minecraft_version_coverage.py"
spec = importlib.util.spec_from_file_location("version_coverage", script)
coverage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(coverage)

class VersionCoverageTest(unittest.TestCase):
    def test_numeric_sort_and_filter(self):
        entries = [{"type":"release","id":v} for v in ("26.3", "1.10", "1.9", "1.8", "26.4")]
        entries.append({"type":"snapshot","id":"26.3-snapshot-1"})
        self.assertEqual(coverage.stable_versions({"versions":entries}),
                         ["1.8","1.9","1.10","26.3"])

    def test_legacy_fabric_not_faked(self):
        for v in ("1.8","1.8.9","1.12.2","1.13.2"):
            self.assertEqual(coverage.classify(v)["fabric"],
                             "not-targeted-no-fabric-before-1.14")

    def test_experimental_java21_is_not_release(self):
        for version in ("1.20.1", "1.20.2", "1.20.3", "1.20.4"):
            state = coverage.classify(version)
            self.assertEqual(state["fabric"], "experimental-java17-build-verified-runtime-untested")
            self.assertEqual(state["paper"], "experimental-java17-build-verified-runtime-untested")
        self.assertNotIn("1.8", coverage.FABRIC_RELEASES)

    def test_minecraft_2026(self):
        self.assertEqual(coverage.classify("26.1")["paper"],
                         "unsupported-unpatched-paper")
        self.assertEqual(coverage.classify("26.3")["fabric"],
                         "published-build-verified")
        self.assertEqual(coverage.classify("26.3")["paper"],
                         "published-build-verified")

    def test_candidates_distinct_from_release(self):
        for v in ("1.20.5", "1.20.6"):
            self.assertEqual(coverage.classify(v)["fabric"], "development-build-verified")
        self.assertEqual(coverage.classify("1.20.6")["paper"],
                         "development-build-verified")
        self.assertEqual(coverage.classify("1.20.5")["paper"],
                         "blocked-legacy-api-dependency")

    def test_minecraft_paper_121(self):
        self.assertEqual(coverage.classify("1.21.11")["paper"],
                         "paper-line-build-needs-runtime-test")

    def test_invalid(self):
        for v in ("1.7.10","26.4","1.21.11-pre1","random"):
            with self.assertRaises(ValueError):
                coverage.classify(v)

    def test_bad_manifest(self):
        with self.assertRaises(ValueError):
            coverage.stable_versions({"versions":[]})

if __name__ == "__main__":
    unittest.main()
