//! Embed the canonical logo as the Windows executable icon.
//!
//! Use the native Windows SDK resource compiler for MSVC and windres for GNU.

use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    // CWD of a build script is the package root (desktop/).
    let icon = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("../assets/logo.ico");
    println!("cargo:rerun-if-changed={}", icon.display());
    for name in [
        "WINDRES",
        "RC",
        "WindowsSdkDir",
        "WindowsSDKVersion",
        "ProgramFiles(x86)",
        "PATH",
    ] {
        println!("cargo:rerun-if-env-changed={name}");
    }
    if let Err(reason) = embed(&icon) {
        panic!("cannot embed the OpenRad executable icon: {reason}");
    }
}

fn embed(icon: &Path) -> Result<(), String> {
    let icon = icon
        .canonicalize()
        .map_err(|e| format!("cannot resolve {}: {e}", icon.display()))?;
    if !icon.is_file() {
        return Err(format!("{} does not exist", icon.display()));
    }
    let out = PathBuf::from(env::var("OUT_DIR").map_err(|e| e.to_string())?);
    let rc = out.join("openrad-logo.rc");
    let rc_contents = format!("1 ICON \"{}\"\n", rc_path(&icon));
    std::fs::write(&rc, rc_contents).map_err(|e| e.to_string())?;
    if env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc") {
        return embed_msvc(&rc, &out);
    }
    let object = out.join("openrad-logo.o");
    let candidates = [
        env::var("WINDRES").unwrap_or_default(),
        "x86_64-w64-mingw32-windres".to_owned(),
        "windres".to_owned(),
    ];
    let mut last_error = "no windres candidate configured".to_owned();
    for windres in candidates.iter().filter(|name| !name.is_empty()) {
        match Command::new(windres)
            .arg(&rc)
            .arg("-O")
            .arg("coff")
            .arg("-o")
            .arg(&object)
            .output()
        {
            Ok(output) if output.status.success() && object.is_file() => {
                println!("cargo:rustc-link-arg={}", object.display());
                return Ok(());
            }
            Ok(output) => {
                last_error = format!(
                    "{windres} failed: {}",
                    String::from_utf8_lossy(&output.stderr).trim()
                );
            }
            Err(e) => last_error = format!("cannot run {windres}: {e}"),
        }
    }
    Err(last_error)
}

fn embed_msvc(rc: &Path, out: &Path) -> Result<(), String> {
    let resource = out.join("openrad-logo.res");
    let mut compilers = Vec::new();
    if let Some(explicit) = env::var_os("RC") {
        compilers.push(PathBuf::from(explicit));
    }
    if let (Some(root), Some(version)) = (
        env::var_os("WindowsSdkDir"),
        env::var_os("WindowsSDKVersion"),
    ) {
        compilers.push(
            PathBuf::from(root)
                .join("bin")
                .join(version)
                .join("x64/rc.exe"),
        );
    }
    compilers.push(PathBuf::from("rc.exe"));
    if let Some(program_files) = env::var_os("ProgramFiles(x86)") {
        let sdk = PathBuf::from(program_files).join("Windows Kits/10/bin");
        if let Ok(entries) = std::fs::read_dir(sdk) {
            let mut versions: Vec<_> = entries
                .filter_map(Result::ok)
                .map(|entry| entry.path())
                .collect();
            versions.sort_unstable_by(|left, right| right.file_name().cmp(&left.file_name()));
            compilers.extend(
                versions
                    .into_iter()
                    .map(|version| version.join("x64/rc.exe")),
            );
        }
    }
    let mut errors = Vec::new();
    for compiler in compilers {
        match Command::new(&compiler)
            .arg("/nologo")
            .arg("/fo")
            .arg(&resource)
            .arg(rc)
            .output()
        {
            Ok(output) if output.status.success() && resource.is_file() => {
                println!("cargo:rustc-link-arg={}", resource.display());
                return Ok(());
            }
            Ok(output) => errors.push(format!(
                "{}: {}{}",
                compiler.display(),
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr)
            )),
            Err(error) => errors.push(format!("{}: {error}", compiler.display())),
        }
    }
    Err(errors.join("; "))
}

/// windres accepts forward slashes on every host, but not the `\\?\` verbatim
/// prefix that `canonicalize` returns on Windows hosts, nor backslashes, which
/// a resource script treats as escapes.
fn rc_path(path: &Path) -> String {
    let text = path.to_string_lossy();
    let text = match text.strip_prefix(r"\\?\UNC\") {
        Some(share) => format!(r"\\{share}"),
        None => text.strip_prefix(r"\\?\").unwrap_or(&text).to_owned(),
    };
    if cfg!(windows) {
        text.replace('\\', "/")
    } else {
        text
    }
}
