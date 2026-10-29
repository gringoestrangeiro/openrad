"""Synthetic metadata/bytecode regressions; no Minecraft or network required."""
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("verify_version_jar", Path(__file__).with_name("verify-version-jar.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class VerifyVersionJarTest(unittest.TestCase):
    def fixture(self, directory, compatibility="JAVA_17", extra_major=61):
        jar = Path(directory) / "synthetic.jar"
        with zipfile.ZipFile(jar, "w") as z:
            z.writestr("fabric.mod.json", json.dumps({"version": "2.2.0", "environment": "client",
                "depends": {"minecraft": "1.18.2", "java": ">=17"}}))
            z.writestr("openrad-client.mixins.json", json.dumps({"package": "synthetic",
                "client": [], "compatibilityLevel": compatibility}))
            z.writestr("META-INF/LICENSE", "synthetic")
            for name in ("io/github/gringoestrangeiro/openradclient/fabric/OpenRadClientMod.class",
                         "io/github/gringoestrangeiro/openradclient/ClientVpnRuntime.class"):
                z.writestr(name, b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, 61))
            z.writestr("synthetic/Additional.class", b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, extra_major))
        return jar

    def test_accepts_matching_legacy_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            verifier.verify(self.fixture(directory), "1.18.2", "2.2.0")

    def test_rejects_newer_mixin_compatibility(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "mixin Java compatibility"):
                verifier.verify(self.fixture(directory, compatibility="JAVA_21"), "1.18.2", "2.2.0")

    def test_checks_additional_classes_not_only_entrypoints(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "bytecode major"):
                verifier.verify(self.fixture(directory, extra_major=65), "1.18.2", "2.2.0")


if __name__ == "__main__":
    unittest.main()
