#!/usr/bin/env python3
"""Generate evidence-based release metadata for Minecraft Java 1.21 to 26.3."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RELEASE = ROOT / "release"
DOCS = ROOT / "docs"
LEGACY = "1.21 1.21.1 1.21.2 1.21.3 1.21.4 1.21.5 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11".split()
MODERN = "26.1 26.1.1 26.1.2 26.2 26.3".split()
ALL = LEGACY + MODERN

def sha256(path: Path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()

def check_mod(path: Path, version: str, expected_mod: str):
    if not path.is_file():
        return "NÃO COMPILADO", "JAR ausente"
    try:
        with zipfile.ZipFile(path) as z:
            meta = json.loads(z.read("fabric.mod.json"))
            mixin = json.loads(z.read("openrad-client.mixins.json"))
            classes = set(z.namelist())
            java_version = 25 if version in MODERN else 21
            required = ["assets/openrad-client/lang/pt_br.json",
                        "assets/openrad-client/lang/ja_jp.json",
                        "META-INF/LICENSE",
                        "io/github/gringoestrangeiro/openradclient/fabric/OpenRadClientMod.class",
                        "io/github/gringoestrangeiro/openradclient/ClientVpnRuntime.class"]
            for name in mixin.get("client", []):
                required.append(mixin["package"].replace(".", "/") + "/" + name + ".class")
            if any(item not in classes for item in required):
                return "NÃO VALIDADO", "classes, traduções ou mixins ausentes"
            if meta.get("version") != expected_mod or meta["depends"]["minecraft"] != version:
                return "NÃO VALIDADO", "versão de mod ou Minecraft incorreta"
            if "25" not in str(meta["depends"].get("java", "")) and java_version == 25:
                return "NÃO VALIDADO", "Java 25 não exigido"
            data = z.read("io/github/gringoestrangeiro/openradclient/fabric/OpenRadClientMod.class")
            major = int.from_bytes(data[6:8], "big")
            if major != (69 if java_version == 25 else 65):
                return "NÃO VALIDADO", f"bytecode Java major={major} incorreto"
        return "BUILD VERIFICADO", ""
    except Exception as e:
        return "NÃO VALIDADO", f"{type(e).__name__}: {e}"

def main():
    RELEASE.mkdir(parents=True, exist_ok=True)
    DOCS.mkdir(parents=True, exist_ok=True)
    rows = []
    files = []
    for version in ALL:
        modern = version in MODERN
        mod_version = "2.1.5"
        name = f"OpenRadClientMod-{mod_version}-fabric-{version}.jar"
        path = RELEASE / name
        status, note = check_mod(path, version, mod_version)
        entry = dict(minecraft=version, java=25 if modern else 21,
                     mod=mod_version, status=status, jar=name if path.is_file() else None,
                     sha256=sha256(path) if status == "BUILD VERIFICADO" else None,
                     note=note)
        rows.append(entry)
        if status == "BUILD VERIFICADO":
            files.append((path, entry["sha256"]))
    status = RELEASE / "OpenRad-1.21-26.3-status.json"
    status.write_bytes((json.dumps(rows, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))
    lines = [
        "# OpenRad Client Mod — matriz Minecraft 1.21 a 26.3",
        "",
        "Versões no intervalo: 17 releases Java, com compilações Fabric específicas.",
        "O status é gerado lendo o próprio JAR: versão declarada, Java, classes,",
        "mixins, licença e arquivos de idioma português e japonês.",
        "",
        "| Minecraft | Java | OpenRad mod | Estado do artefato |",
        "|---|---:|---|---|",
    ]
    for item in rows:
        lines.append(f"| {item['minecraft']} | {item['java']} | {item['mod']} | {item['status']} |")
    lines += [
        "",
        "## Como instalar",
        "",
        "No cliente Minecraft, instale apenas um JAR correspondente à versão exata",
        "do jogo. Instale também Fabric Loader/Fabric API adequados. Os builds 1.21.x",
        "usam Java 21 e o projeto fabric/; os builds 26.x usam Java 25 e o projeto",
        "modern/, com a API de renderização e comandos adaptada.",
        "",
        "## Qualidade de validação",
        "",
        "BUILD VERIFICADO não significa gameplay validado. Compilação e",
        "inspeção do JAR não comprovam aplicação de todos os mixins durante o jogo,",
        "conexão real ao Radmin/OpenRad, multiplayer, passagem de status ou movimentação.",
        "Os testes do núcleo executam no projeto legacy; o projeto modern",
        "ainda não possui uma suíte de smoke tests Minecraft. Faça testes isolados",
        "antes de usar no perfil principal.",
        "",
        "## Recompilação reproduzível",
        "",
        "    .\\build-full-range.ps1",
        "    .\\build-full-range.ps1 -Targets @('1.21.11','26.3') -Rebuild",
        "    python tools/report-full-range.py",
        "",
        "Em builds legados, Fabric Loom 1.13.6 e Gradle 9.1; na geração 26.x,",
        "Fabric Loom 1.17.20 e Gradle 9.7.1. Fabric API é específica por versão.",
        "",
        "Não modifique identidades VPN nem o servidor Paper de produção durante testes.",
    ]
    doc = DOCS / "COMPATIBILITY-1.21-26.3.md"
    doc.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))
    sumfile = RELEASE / "SHA256SUMS-1.21-26.3.txt"
    sumfile.write_text("".join(f"{digest}  {p.name}\n" for p, digest in files), encoding="utf-8")
    print(f"Verified {len(files)}/{len(ALL)} target JARs")
    print(f"Report: {doc}")
    print(f"Status: {status}")
    return len(files)

if __name__ == "__main__":
    main()
