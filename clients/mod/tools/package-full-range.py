#!/usr/bin/env python3
"""Package only verified Minecraft-version artifacts; exclude credentials and state."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import zipfile
import io

MOD = Path(__file__).resolve().parents[1]
PAPER = MOD.parent / "openrad-bridge"
DOWNLOADS = MOD.parent
DEST = DOWNLOADS / "OpenRad-1.21-to-26.3-Alpha-Bundle.zip"
STATUS = MOD / "release/OpenRad-1.21-26.3-status.json"
SOURCE_MOD = MOD / "release/OpenRadClientMod-1.21-26.3-source.zip"
SOURCE_PAPER = PAPER / "release/OpenRadPlugin-0.2.6-dev-source.zip"
PAPER_JARS = [
    "OpenRadPlugin-0.2.6-dev-paper-1.21-to-1.21.11-java21.jar",
    "OpenRadPlugin-0.2.6-dev-paper-26.1.1-java25.jar",
    "OpenRadPlugin-0.2.6-dev-paper-26.1.2-java25.jar",
    "OpenRadPlugin-0.2.6-dev-paper-26.2-java25.jar",
    "OpenRadPlugin-0.2.6-dev-paper-26.3-java25.jar",
]

def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def add_file(z: zipfile.ZipFile, path: Path, arcname: str, checks: list[str]):
    if not path.is_file():
        raise FileNotFoundError(str(path))
    data = path.read_bytes()
    z.writestr(arcname, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=7)
    checks.append(f"{sha256(data)}  {arcname}")
    return len(data)

def main():
    if not STATUS.exists():
        raise RuntimeError("Run tools/report-full-range.py before packaging")
    matrix = json.loads(STATUS.read_text(encoding="utf-8"))
    verified = [x for x in matrix if x["status"] == "BUILD VERIFICADO"]
    unverified = [x["minecraft"] for x in matrix if x["status"] != "BUILD VERIFICADO"]
    if not verified:
        raise RuntimeError("No verified Fabric builds available")
    if not SOURCE_MOD.exists() or not SOURCE_PAPER.exists():
        raise RuntimeError("Both repositories must have a committed source archive before packaging")

    checks: list[str] = []
    with zipfile.ZipFile(DEST, "w", allowZip64=True) as z:
        intro = (
            "OpenRad Minecraft Java 1.21 - 26.3 | DEVELOPMENT ALPHA\n\n"
            f"Fabric compilado/verificado: {len(verified)}/{len(matrix)} versoes.\n"
            "Nao verificadas: " + (", ".join(unverified) or "nenhuma") + "\n"
            "JARs legacy 1.21.x: Java 21. JARs modernos 26.x: Java 25.\n"
            "Paper: Java 21 e Java 25 em artefatos separados.\n\n"
            "IMPORTANTE: sao resultados de BUILD, nao de jogo em runtime. "
            "Carregamento dos mixins, login no servidor, jogabilidade e VPN WAN "
            "oficial permanecem sem validacao por versao.\n"
            "Instale um unico JAR Fabric que corresponda a sua versao, "
            "com Fabric API compativel. "
            "No Paper, instale apenas um JAR de plugin da geracao adequada.\n"
            "Nao copie identidades ou chaves VPN entre cliente e servidor.\n"
            "Nenhum servidor de producao foi alterado.\n"
        ).encode("utf-8")
        z.writestr("OpenRad-1.21-to-26.3/LEIA-PRIMEIRO.txt", intro)
        checks.append(f"{sha256(intro)}  LEIA-PRIMEIRO.txt")
        for item in verified:
            path = MOD / "release" / item["jar"]
            if sha256(path.read_bytes()) != item["sha256"]:
                raise ValueError(f"Checksum mismatch: {path.name}")
            add_file(z, path,
                f"OpenRad-1.21-to-26.3/Mods-Fabric/{path.name}", checks)
        for name in PAPER_JARS:
            path = PAPER / "release" / name
            if path.exists():
                add_file(z, path, f"OpenRad-1.21-to-26.3/Plugin-Paper/{name}", checks)
        add_file(z, SOURCE_MOD,
                 f"OpenRad-1.21-to-26.3/Codigo-Fonte/{SOURCE_MOD.name}", checks)
        add_file(z, SOURCE_PAPER,
                 f"OpenRad-1.21-to-26.3/Codigo-Fonte/{SOURCE_PAPER.name}", checks)
        add_file(z, STATUS, "OpenRad-1.21-to-26.3/Status-Fabric.json", checks)
        doc = MOD / "docs/COMPATIBILITY-1.21-26.3.md"
        add_file(z, doc, "OpenRad-1.21-to-26.3/COMPATIBILITY-FABRIC.md", checks)
        docp = PAPER / "docs/compatibility-0.2.6.md"
        add_file(z, docp, "OpenRad-1.21-to-26.3/COMPATIBILITY-PAPER.md", checks)
        manifest = "\n".join(checks) + "\n"
        z.writestr("OpenRad-1.21-to-26.3/SHA256SUMS.txt", manifest.encode("utf-8"))
    with zipfile.ZipFile(DEST) as z:
        bad = [name for name in z.namelist() if any(
            part in name.lower() for part in ("identity.json", "registration.pending",
                "secrets.properties", ".git/", "audit-results/")
        )]
        if bad:
            raise ValueError(f"Unsafe bundle paths: {bad[:3]}")
        corrupt = z.testzip()
        if corrupt is not None:
            raise RuntimeError(f"Bad ZIP entry: {corrupt}")
        count = len(z.namelist())
    print(f"BUNDLE={DEST}")
    print(f"SIZE={DEST.stat().st_size}")
    print(f"SHA256={sha256(DEST.read_bytes())}")
    print(f"JARS_FABRIC={len(verified)}")
    print(f"NOT_VERIFIED={','.join(unverified) if unverified else '(none)'}")
    print(f"TOTAL_ENTRIES={count}")

if __name__ == "__main__":
    main()
