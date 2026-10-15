//! Embed the canonical logo as the Windows executable icon.
//!
//! Zero-dependency: invokes the MinGW resource compiler directly instead of
//! pulling a `winres`-style build crate (which is absent from the offline
//! cargo cache). No-op on non-Windows targets, and any resource-compiler
//! failure degrades to a warning so a missing `windres` never breaks the
//! cross build — the executable just ships without an embedded icon.

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
    if let Err(reason) = embed(&icon) {
        println!(
            "cargo:warning=openrad-desktop: embedding the window icon failed ({reason}); continuing without it"
        );
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
                println!("cargo:rerun-if-changed={}", rc.display());
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
