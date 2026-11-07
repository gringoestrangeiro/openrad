"""Build exact daemon status methods with -O and existing Cargo dependencies.

python b-status.py SOURCE_OR_BASELINE_ZIP CARGO_DEPS OUTPUT_DIRECTORY
Then run b-status.exe [iterations] [stable|updated|clone]. No sockets/TAP/profile.
The source's Shared/State/new/lock_state/status are extracted verbatim. Snapshot,
Reply, ServicePreferences and Identity use the existing openrad Cargo rlib;
therefore dependencies/types can remain debug built. This is not release IPC.
"""
import hashlib
import json
import pathlib
import subprocess
import sys
import zipfile

source, deps, output = map(pathlib.Path, sys.argv[1:])
output.mkdir(parents=True, exist_ok=True)
if source.suffix == ".zip":
    with zipfile.ZipFile(source) as archive:
        daemon = archive.read("src/daemon.rs").decode("utf-8")
else:
    daemon = (source / "src/daemon.rs").read_text(encoding="utf-8")


def item(signature):
    start = daemon.index(signature)
    brace = daemon.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        depth += (daemon[end] == "{") - (daemon[end] == "}")
        end += 1
    return daemon[start:end]


methods = [item("fn new(id: &Identity"), item("fn lock_state(&self)"), item("fn status(&self)")]
extra = ""
if "fn status_snapshot(&self)" in daemon:
    methods += [item("fn status_snapshot(&self)"), item("fn status_payload(&self)")]
    extra = "#[derive(Serialize)]\n" + item("struct StatusSnapshot") + "\n" + item("fn serialize_status_snapshot<") + "\n#[derive(Serialize)]\n" + item("struct StatusReply")
else:
    methods.append("fn status_payload(&self) -> Result<Vec<u8>> { Ok(serde_json::to_vec(&self.status())?) }")
# Reply::ok is private, so retain the exact production Reply/impl as well.
extra += "\n#[derive(Serialize, Deserialize)]\n" + item("pub struct Reply") + "\n" + item("impl Reply")
main = pathlib.Path(__file__).with_suffix(".rs").read_text(encoding="utf-8")
main = main.replace("// STATUS_SOURCE", item("struct State") + "\n" + item("struct Shared") + "\n" + extra + "\nimpl Shared {\n" + "\n".join(methods) + "\n}")
generated = output / "b-status-generated.rs"
generated.write_text(main, encoding="utf-8")
args = ["rustc", "--edition=2021", "-O", "-A", "dead_code", "-A", "unused_imports", "--crate-name", "b_status", str(generated), "-L", "dependency=" + str(deps), "-o", str(output / "b-status.exe")]
for name in ["openrad", "anyhow", "serde", "serde_json", "windows_sys"]:
    candidates = list(deps.glob("lib" + name + "-*.rlib"))
    if name == "windows_sys":
        selected = []
        for candidate in candidates:
            fingerprint = deps.parent / ".fingerprint" / candidate.stem[3:].replace("windows_sys", "windows-sys") / "lib-windows_sys.json"
            if fingerprint.exists() and "Win32_System_Threading" in json.loads(fingerprint.read_text())["features"]:
                selected.append(candidate)
        candidates = selected
    dependency = max(candidates, key=lambda path: path.stat().st_mtime)
    args += ["--extern", name + "=" + str(dependency)]
subprocess.run(args, check=True)
print(json.dumps({"source": str(source), "daemon_sha256": hashlib.sha256(daemon.encode()).hexdigest(), "command": args}))
