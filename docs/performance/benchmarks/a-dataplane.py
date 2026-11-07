"""Build Windows -O dataplane benchmarks from an explicitly selected source tree.

Uses the existing Cargo dependency artifacts (which may be debug builds); this
is not a whole-application release benchmark. No TAP, credentials or remote I/O.
Run: python a-dataplane.py SOURCE_ROOT CARGO_DEPS OUTPUT_DIRECTORY
Then run OUTPUT_DIRECTORY/a-dataplane.exe; each invocation prints JSON lines.
"""
import hashlib
import json
import pathlib
import subprocess
import sys

source, deps, output = map(pathlib.Path, sys.argv[1:])
output.mkdir(parents=True, exist_ok=True)


def function(text, signature):
    start = text.index(signature)
    brace = text.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


session = (source / "src/session.rs").read_text(encoding="utf-8")
checksum = session[session.index("pub fn rendezvous_checksum"):session.index("#[cfg(test)]\nmod checksum_tests")]
udp = (source / "src/udp.rs").read_text(encoding="utf-8")
tunnel = (source / "src/tunnel.rs").read_text(encoding="utf-8")
main = pathlib.Path(__file__).with_suffix(".rs").read_text(encoding="utf-8")
crypto = str((source / "src/crypto.rs").resolve()).replace("\\", "/")
main = main.replace("// CRYPTO_MODULE", '#[path = "' + crypto + '"] mod crypto;')
main = main.replace("// CHECKSUM_MODULE", "mod session {" + checksum + "}")
main = main.replace("// TRANSMIT_FUNCTION", function(udp, "fn transmit_to("))
main = main.replace("// REFUSED_FUNCTION", function(udp, "fn send_refused("))
main = main.replace("// ENCODE_FUNCTION", function(tunnel, "pub fn encode_into("))
file = output / "a-dataplane-generated.rs"
file.write_text(main, encoding="utf-8")
args = ["rustc", "--edition=2021", "-O", "-A", "dead_code", "--crate-name", "a_dataplane", str(file), "-L", "dependency=" + str(deps), "-o", str(output / "a-dataplane.exe")]
for name in ["aes", "anyhow", "md4", "num_bigint", "num_traits", "rand", "sha1", "subtle", "zeroize", "hex", "windows_sys"]:
    candidates = list(deps.glob("lib" + name + "-*.rlib"))
    if name == "windows_sys":
        selected = []
        for item in candidates:
            fingerprint = deps.parent / ".fingerprint" / item.stem[3:].replace("windows_sys", "windows-sys") / "lib-windows_sys.json"
            if fingerprint.exists():
                features = json.loads(fingerprint.read_text())["features"]
                if "Win32_Networking_WinSock" in features and "Win32_System_IO" in features:
                    selected.append(item)
        candidates = selected
    if not candidates:
        raise RuntimeError("missing dependency " + name)
    dependency = max(candidates, key=lambda x: x.stat().st_mtime)
    args += ["--extern", name + "=" + str(dependency)]
subprocess.run(args, check=True)
print(json.dumps({"source": str(source), "command": args, "source_sha256": {name: hashlib.sha256((source / "src" / name).read_bytes()).hexdigest() for name in ["crypto.rs", "udp.rs", "session.rs", "tunnel.rs"]}}))
