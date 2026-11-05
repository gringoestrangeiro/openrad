"""Compare audited/current desktop sources with real App/cache/VirtualRows paths.

Windows: python c-desktop.py --repo PATH --baseline SOURCE.zip --output PATH
Uses rustc -O with the SAME existing Cargo debug dependency rlibs for both
versions. No real profile, backend service, native window, GPU, TAP or network.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import zipfile


def process_measurements(process):
    """Windows process CPU and peak working set, after this handle exits."""
    if os.name != "nt":
        return {}
    import ctypes
    from ctypes import wintypes

    class MemoryCounters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in (
                "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]

    times = [wintypes.FILETIME() for _ in range(4)]
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    memory = MemoryCounters()
    memory.cb = ctypes.sizeof(memory)
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(MemoryCounters), wintypes.DWORD]
    measurements = {}
    if kernel.GetProcessTimes(int(process._handle), *(ctypes.byref(t) for t in times)):
        ticks = lambda t: (t.dwHighDateTime << 32) + t.dwLowDateTime
        measurements["process_cpu_s"] = (ticks(times[2]) + ticks(times[3])) / 10_000_000
    if psapi.GetProcessMemoryInfo(int(process._handle), ctypes.byref(memory), memory.cb):
        measurements["peak_working_set_bytes"] = memory.PeakWorkingSetSize
    return measurements

APP_BENCH = r'''
    #[test]
    #[ignore]
    fn optimization_c_benchmark() {
        for count in [0, 10, 100, 1000, 5000, 10000] {
            let mut fixture = Fixture::new();
            let app = fixture.app();
            app.phase = Phase::Connected;
            app.settings.language = LanguagePreference::English;
            app.settings.show_traffic = false;
            app.snapshot.networks = vec![Network { name: "Synthetic LAN".into(), network_id: "a".into() }];
            for rid in 1..=count { app.snapshot.peers.insert(rid, cached_peer(rid, &format!("Synthetic peer {rid:05}"), &["a"])); }
            let ctx = egui::Context::default();
            configure(&ctx);
            let mut draw = |app: &mut App, width: f32, scroll: f32, metrics: bool| {
                let mut out = ctx.run_ui(egui::RawInput { screen_rect: Some(egui::Rect::from_min_size(egui::Pos2::ZERO, Vec2::new(width, 900.))), ..Default::default() }, |ui| {
                    egui::CentralPanel::default().show(ui, |ui| {
                        egui::ScrollArea::vertical().vertical_scroll_offset(scroll).auto_shrink([false,false]).show(ui, |ui| if metrics { app.traffic(ui) } else { app.networks(ui) });
                    });
                });
                out.textures_delta.clear();
                std::hint::black_box(out);
            };
            for _ in 0..5 { draw(app,780.,0.,false); }
            for mode in ["cache", "steady", "scroll", "layout", "data_update", "metrics"] {
                for _ in 0..5 { draw(app,780.,0.,mode == "metrics"); }
                let mut samples = Vec::new();
                for i in 0..80 {
                    // Mutating source data is outside the measured interval.
                    if mode == "data_update" && count > 0 {
                        app.snapshot.peers.get_mut(&count).unwrap().peer.name = format!("Synthetic peer updated {i}");
                    }
                    let start = std::time::Instant::now();
                    match mode {
                        "cache" => { for _ in 0..8 { let app = std::hint::black_box(&mut *app); app.joined_networks(); app.peer_list.refresh(&app.snapshot, None, "", &app.settings); std::hint::black_box(&app.peer_list); } }
                        "scroll" => draw(app,780.,if i%2 == 0 {0.} else {count as f32 * 32.},false),
                        "layout" => draw(app,if i%2 == 0 {640.} else {900.},0.,false),
                        _ => draw(app,780.,0.,mode == "metrics"),
                    }
                    samples.push(start.elapsed().as_nanos() as u64 / if mode == "cache" {8} else {1});
                }
                samples.sort_unstable();
                eprintln!("C_BENCH {{\"peers\":{count},\"mode\":\"{mode}\",\"p50_ns\":{},\"p95_ns\":{},\"p99_ns\":{}}}",samples[40],samples[76],samples[79]);
            }
        }
    }
'''

ROWS_BENCH = r'''
    #[test]
    #[ignore]
    fn optimization_c_rows_benchmark() {
        let ctx = egui::Context::default();
        let keys: Vec<u32> = (0..50_000).collect();
        let rows = Rows { keys: &keys, revision: 0, layout: 0., height: &|_|20.3125, kind: &|_|0 };
        let mut list = VirtualRows::default();
        settle(&ctx,&mut list,&rows,None);
        settle(&ctx,&mut list,&rows,Some(28.3125*45_000.));
        for mode in ["steady", "scroll", "layout", "data_update", "kept"] {
            for _ in 0..5 { render(&ctx,&mut list,&rows,if mode == "kept" {Some(49_999)} else {None},Some(28.3125*45_000.)); }
            let mut samples=Vec::new();
            for i in 0..80 {
                let modified = Rows { revision: if mode == "data_update" {i} else {0}, layout: if mode == "layout" {(i%2) as f32} else {0.}, ..rows };
                let start=std::time::Instant::now();
                std::hint::black_box(render(&ctx,&mut list,&modified,if mode == "kept" {Some(49_999)} else {None},Some(if mode == "scroll" && i%2 == 0 {0.} else {28.3125*45_000.})));
                samples.push(start.elapsed().as_nanos() as u64);
            }
            samples.sort_unstable();
            eprintln!("C_BENCH {{\"rows\":50000,\"mode\":\"{mode}\",\"p50_ns\":{},\"p95_ns\":{},\"p99_ns\":{}}}",samples[40],samples[76],samples[79]);
        }
    }
'''


def inject(path, extra):
    source = path.read_text(encoding="utf-8")
    index = source.rfind("}")
    path.write_text(source[:index] + extra + source[index:], encoding="utf-8")


def desktop_dependencies(repo):
    """Resolve the exact Cargo desktop test graph, including feature variants."""
    fingerprints = repo / "target/debug/.fingerprint"
    candidates = sorted(fingerprints.glob("openrad-desktop-*/test-bin-openrad-desktop.json"), key=lambda p: p.stat().st_mtime, reverse=True)
    for candidate in candidates:
        resolved = {}
        for _, name, _, expected in json.loads(candidate.read_text())["deps"]:
            if name == "build_script_build":
                continue
            for stamp in fingerprints.glob("*/lib-" + name):
                if int.from_bytes(bytes.fromhex(stamp.read_text()), "little") == expected:
                    suffix = stamp.parent.name.rsplit("-", 1)[1]
                    library = repo / "target/debug/deps" / f"lib{name}-{suffix}.rlib"
                    if library.exists():
                        resolved[name] = library
            if name not in resolved:
                break
        else:
            return resolved
    raise RuntimeError("Build cargo test -p openrad-desktop --no-run --locked first")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("repo", "baseline", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--baseline-only", action="store_true")
    parser.add_argument("--compile-only", action="store_true")
    parser.add_argument("--run-only", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    versions = ["baseline"] if args.baseline_only else ["baseline", "candidate"]
    deps = args.repo / "target/debug/deps"
    libraries = desktop_dependencies(args.repo)
    env = dict(os.environ, CARGO_PKG_VERSION="2.2.0")
    commands = {}
    hashes = {}
    for version in ([] if args.run_only else versions):
        root = args.output / version
        exe = args.output / (version + ".exe")
        root.mkdir(exist_ok=True)
        with zipfile.ZipFile(args.baseline) as archive:
            for name in archive.namelist():
                if name.startswith(("desktop/", "assets/", "locales/")):
                    archive.extract(name, root)
        if version == "candidate":
            # Only the desktop implementation under test differs; keep the
            # audited dependencies/modules constant while other agents work.
            for relative in ("desktop/src/app.rs", "desktop/src/virtual_list.rs"):
                shutil.copyfile(args.repo / relative, root / relative)
        hashes[version] = {p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in ("desktop/src/app.rs", "desktop/src/virtual_list.rs")}
        inject(root / "desktop/src/app.rs", APP_BENCH)
        inject(root / "desktop/src/virtual_list.rs", ROWS_BENCH)
        command = ["rustc", "--edition=2021", "--test", "-O", "-Awarnings", str(root / "desktop/src/main.rs"), "-L", "dependency=" + str(deps), "-o", str(exe)]
        for name, library in libraries.items():
            command += ["--extern", name + "=" + str(library)]
        for native in Path.home().joinpath(".cargo/registry/src").glob("*/windows_x86_64_msvc-*/lib"):
            command += ["-L", "native=" + str(native)]
        commands[version] = command
        env["CARGO_MANIFEST_DIR"] = str(root / "desktop")
        result = subprocess.run(command, capture_output=True, text=True, env=env)
        (args.output / (version + "-compile.log")).write_text(result.stdout + result.stderr, encoding="utf-8")
        result.check_returncode()
    metadata = args.output / "build-info.json"
    if args.run_only:
        previous = json.loads(metadata.read_text(encoding="utf-8"))
        hashes, commands = previous["source_hashes"], previous["commands"]
    else:
        metadata.write_text(json.dumps(dict(source_hashes=hashes, commands=commands), indent=2), encoding="utf-8")
    if args.compile_only:
        return
    records = []
    processes = []
    for round_index in range(5):
        for version in (versions if round_index % 2 == 0 else versions[::-1]):
            start = time.perf_counter()
            process = subprocess.Popen([str(args.output / (version + ".exe")), "optimization_c_", "--ignored", "--nocapture", "--test-threads=1"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            stdout, stderr = process.communicate()
            (args.output / f"{version}-{round_index}.log").write_text(stdout + stderr, encoding="utf-8")
            if process.returncode:
                raise RuntimeError(f"{version} benchmark exited {process.returncode}")
            elapsed = time.perf_counter() - start
            processes.append(dict(version=version, round=round_index, wall_s=elapsed, **process_measurements(process)))
            records.extend(dict(json.loads(line), version=version, round=round_index) for line in re.findall(r"C_BENCH (.+)", stderr))
            print(version, round_index, round(elapsed, 3), flush=True)
    (args.output / "results.json").write_text(json.dumps(dict(source_hashes=hashes, commands=commands, processes=processes, samples=records), indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
