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
    def fixture(self, directory, compatibility="JAVA_17", extra_major=61, mutation=None, minecraft="1.18.2"):
        jar = Path(directory) / "synthetic.jar"
        java = 25 if minecraft.startswith("26.") else 17
        major = java + 44
        with zipfile.ZipFile(jar, "w") as z:
            metadata = {"version": "2.3.0", "environment": "client",
                "entrypoints": {"client": ["io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod"]},
                "mixins": ["openrad-client.mixins.json"],
                "depends": {"minecraft": minecraft, "java": f">={java}"}}
            mixin_names = ["ConnectionRoutingMixin" if java == 25 else "ConnectionMixin", "LegacyStatusPingerMixin", "MultiplayerScreenMixin"]
            if java == 25:
                mixin_names.append("ScreenWidgetInvoker")
            mixins = {"package": "io.github.gringoestrangeiro.openradclient.fabric.mixin",
                "client": mixin_names.copy(), "compatibilityLevel": compatibility,
                "required": True, "injectors": {"defaultRequire": 1}}
            if mutation == "entrypoint": metadata["entrypoints"] = {}
            if mutation == "mixin-reference": metadata["mixins"] = []
            if mutation == "empty-mixins": mixins["client"] = []
            if mutation == "missing-routing": mixins["client"].remove("ConnectionMixin")
            if mutation == "optional-injections": mixins["required"] = False
            if mutation == "missing-widget": mixins["client"].remove("ScreenWidgetInvoker")
            z.writestr("fabric.mod.json", json.dumps(metadata))
            z.writestr("openrad-client.mixins.json", json.dumps(mixins))
            z.writestr("META-INF/LICENSE", "synthetic")
            for name in ("io/github/gringoestrangeiro/openradclient/fabric/OpenRadClientMod.class",
                         "io/github/gringoestrangeiro/openradclient/ClientVpnRuntime.class"):
                z.writestr(name, b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, major))
            z.writestr("synthetic/Additional.class", b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, extra_major))
            for name in mixin_names:
                if mutation != "missing-class" or name != "ConnectionMixin":
                    z.writestr(mixins["package"].replace(".", "/") + "/" + name + ".class",
                               b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, major))
        return jar

    def test_accepts_matching_legacy_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            verifier.verify(self.fixture(directory), "1.18.2", "2.3.0")

    def test_rejects_newer_mixin_compatibility(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "mixin Java compatibility"):
                verifier.verify(self.fixture(directory, compatibility="JAVA_21"), "1.18.2", "2.3.0")

    def test_checks_additional_classes_not_only_entrypoints(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "bytecode major"):
                verifier.verify(self.fixture(directory, extra_major=65), "1.18.2", "2.3.0")

    def test_rejects_inert_client_or_missing_injections(self):
        for mutation in ("entrypoint", "mixin-reference", "empty-mixins", "missing-routing", "optional-injections", "missing-class"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError):
                    verifier.verify(self.fixture(directory, mutation=mutation), "1.18.2", "2.3.0")

    def test_modern_adapter_requires_its_widget_invoker(self):
        with tempfile.TemporaryDirectory() as directory:
            verifier.verify(self.fixture(directory, compatibility="JAVA_25", extra_major=69, minecraft="26.3"), "26.3", "2.3.0")
            with self.assertRaisesRegex(ValueError, "required OpenRad client mixin"):
                verifier.verify(self.fixture(directory, compatibility="JAVA_25", extra_major=69, minecraft="26.3", mutation="missing-widget"), "26.3", "2.3.0")


if __name__ == "__main__":
    unittest.main()
