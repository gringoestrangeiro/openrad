#!/usr/bin/env python3
"""Verify remapped Fabric JAR and exact Minecraft version without running Minecraft."""
import json
import pathlib
import struct
import sys
import zipfile

def verify(jar: pathlib.Path, expected_minecraft: str, expected_version: str) -> None:
    with zipfile.ZipFile(jar) as z:
        files = set(z.namelist())
        required = {
            "fabric.mod.json", "openrad-client.mixins.json", "META-INF/LICENSE",
            "io/github/gringoestrangeiro/openradclient/fabric/OpenRadClientMod.class",
            "io/github/gringoestrangeiro/openradclient/ClientVpnRuntime.class",
        }
        missing = required - files
        if missing:
            raise ValueError(f"Missing release classes/metadata: {sorted(missing)}")
        metadata = json.loads(z.read("fabric.mod.json"))
        actual_target = metadata.get("depends", {}).get("minecraft")
        if actual_target != expected_minecraft:
            raise ValueError(f"JAR targets {actual_target!r}, expected {expected_minecraft!r}")
        if metadata.get("version") != expected_version:
            raise ValueError(f"Wrong mod version: {metadata.get('version')!r}")
        if metadata.get("environment") != "client":
            raise ValueError("Not a client-only mod")
        mixins = json.loads(z.read("openrad-client.mixins.json"))
        for name in mixins.get("client", []):
            class_path = mixins["package"].replace(".", "/") + "/" + name + ".class"
            if class_path not in files:
                raise ValueError(f"Missing mixin {class_path}")
        for name in ("io/github/gringoestrangeiro/openradclient/fabric/OpenRadClientMod.class",
                     "io/github/gringoestrangeiro/openradclient/ClientVpnRuntime.class"):
            bytecode = z.read(name)
            if len(bytecode) < 8 or bytecode[:4] != b"\xca\xfe\xba\xbe":
                raise ValueError(f"Invalid Java class {name}")
            major = struct.unpack(">H", bytecode[6:8])[0]
            expected_java = 25 if expected_minecraft.startswith("26.") else (17 if expected_minecraft in ("1.20.3", "1.20.4") else 21)
            expected_major = expected_java + 44
            if major != expected_major:
                raise ValueError(f"Unexpected Java bytecode major {major}; expected {expected_major}")
        if metadata.get("depends", {}).get("java") != f">={expected_java}":
            raise ValueError(f"Unexpected Java metadata: {metadata.get('depends', {}).get('java')}")
        print(f"VERIFIED {jar.name}: Fabric Minecraft {actual_target}, "
              f"mod {metadata['version']}, Java {expected_java}, classes and mixins included")

if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("Usage: verify-version-jar.py JAR MINECRAFT_VERSION MOD_VERSION")
    verify(pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3])
