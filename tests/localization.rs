//! Translation coverage and real, unprivileged CLI behavior.
use openrad::i18n::Language;

fn literals(source: &str) -> Vec<String> {
    let mut result = Vec::new();
    let mut source = source.split("\n#[cfg(test)]\nmod ").next().unwrap();
    while let Some(start) = source.find('"') {
        source = &source[start + 1..];
        let mut escaped = false;
        let end = source.char_indices().find_map(|(i, c)| {
            if escaped {
                escaped = false;
            } else if c == '\\' {
                escaped = true;
            } else if c == '"' {
                return Some(i);
            }
            None
        });
        let Some(end) = end else { break };
        if let Ok(value) = serde_json::from_str::<String>(&format!("\"{}\"", &source[..end])) {
            result.push(value);
        }
        source = &source[end + 1..];
    }
    result
}

fn normalized(source: &str) -> String {
    let mut result = String::new();
    let mut source = source;
    let mut index = 0;
    while let Some(start) = source.find('{') {
        let Some(end) = source[start..].find('}').map(|n| start + n) else {
            break;
        };
        result.push_str(&source[..start]);
        let key = source[start + 1..end].split(':').next().unwrap();
        result.push('{');
        if key.is_empty() {
            result.push_str(&index.to_string());
            index += 1;
        } else {
            result.push_str(key);
        }
        result.push('}');
        source = &source[end + 1..];
    }
    result.push_str(source);
    result
}

#[test]
fn application_text_and_shared_error_messages_have_complete_catalog_coverage() {
    let sources = [
        ("desktop/app", include_str!("../desktop/src/app.rs")),
        (
            "desktop/network_ui",
            include_str!("../desktop/src/network_ui.rs"),
        ),
        ("desktop/backend", include_str!("../desktop/src/backend.rs")),
        ("desktop/storage", include_str!("../desktop/src/storage.rs")),
        ("desktop/main", include_str!("../desktop/src/main.rs")),
        ("main", include_str!("../src/main.rs")),
        ("daemon", include_str!("../src/daemon.rs")),
        ("network", include_str!("../src/network.rs")),
        ("runtime", include_str!("../src/runtime.rs")),
        ("releases", include_str!("../src/releases.rs")),
        (
            "release_http",
            include_str!("../src/platform/release_http.rs"),
        ),
        ("session", include_str!("../src/session.rs")),
        ("peer", include_str!("../src/peer.rs")),
        ("protocol", include_str!("../src/protocol.rs")),
        ("crypto", include_str!("../src/crypto.rs")),
        ("incoming", include_str!("../src/incoming.rs")),
        ("udp", include_str!("../src/udp.rs")),
        ("tunnel", include_str!("../src/tunnel.rs")),
        ("client", include_str!("../src/client.rs")),
        ("output", include_str!("../src/output.rs")),
        ("file_io", include_str!("../src/file_io.rs")),
        ("diagnostics", include_str!("../src/diagnostics.rs")),
        ("linux", include_str!("../src/platform/linux.rs")),
        ("windows", include_str!("../src/platform/windows.rs")),
        (
            "windows_radmin",
            include_str!("../src/platform/windows_radmin.rs"),
        ),
        (
            "tap_windows_contract",
            include_str!("../src/platform/tap_windows_contract.rs"),
        ),
        (
            "unsupported",
            include_str!("../src/platform/unsupported.rs"),
        ),
    ];
    // Protocol names, identifiers, units and punctuation-only formatting stay
    // universal. Everything else that reads as a sentence or label is audited.
    let universal = [
        "OpenRad",
        "OpenRad/",
        "Accept: application/vnd.github+json",
        "OpenRad: {e}",
        "OpenRad: {0}",
        "openrad: {0}",
        "NetworkPassword([redacted])",
        "F",
        "R",
        "CARGO_PKG_VERSION",
        "XDG_STATE_HOME",
        "HOME",
        "LOCALAPPDATA",
        "ComponentId",
        "NetCfgInstanceId",
        "Name",
        "Ctrl+D",
        "MB",
        "MiB",
        "KiB",
        "{0}  ·  {1} ms",
        "RID {0}",
        " s",
        "{n} B",
        "radminvpn0 · Ethernet TAP · MTU 1500",
        "OpenRad · TAP-Windows6 · MTU 1500",
        "{0}: {error}",
        " · Retry",
        "Famatech Radmin VPN Ethernet Adapter",
        "WindowsPowerShell/v1.0/powershell.exe",
        "{0}\nInvoke-OpenRadRadminRecovery -InterfaceIndex @({indices})",
        "Official Radmin VPN conflict; starting temporary SYSTEM recovery",
        "Official Radmin VPN SYSTEM recovery finished; retrying interface address check",
        // These are persisted diagnostics, which deliberately stay in English.
        "Identity reset requested; pending_save={0}",
        "Identity reset saving replacement; elapsed_ms={0}",
        "{message}; elapsed_ms={0}; pending_save={1}",
        "Identity reset completed; elapsed_ms={0}",
        "Stopping VPN service; timeout_seconds=10",
        "VPN stop request failed; attempt={attempts}; error={error}",
        "VPN service stopped; attempts={attempts}",
        "Identity save retry; attempt={attempt}; error={error}",
        "Recovered stale VPN service endpoint",
    ];
    let mut missing = Vec::new();
    for (file, source) in sources {
        for source in literals(source) {
            let key = normalized(&source);
            let mut words = String::new();
            let mut inside = false;
            for c in key.chars() {
                match c {
                    '{' => inside = true,
                    '}' => inside = false,
                    _ if !inside => words.push(c),
                    _ => {}
                }
            }
            let human = words.chars().any(|c| c.is_ascii_alphabetic())
                && (key.contains(' ')
                    || words.chars().next().is_some_and(|c| c.is_ascii_uppercase()));
            if human
                && !universal.contains(&key.as_str())
                && Language::English.lookup(&key).is_none()
            {
                missing.push(format!("{file}: {key}"));
            }
        }
    }
    assert!(
        missing.is_empty(),
        "missing translations:\n{}",
        missing.join("\n")
    );
}

#[cfg(unix)]
mod cli {
    use super::*;
    use std::{fs, path::PathBuf, process::Command};

    struct Profile(PathBuf);
    impl Profile {
        fn new() -> Self {
            Self(std::env::temp_dir().join(format!(
                "openrad-locale-cli-{}-{}",
                std::process::id(),
                rand::random::<u64>()
            )))
        }
        fn command(&self) -> Command {
            let mut command = Command::new(env!("CARGO_BIN_EXE_openrad"));
            command
                .arg("--data-dir")
                .arg(&self.0)
                .env_remove("LC_ALL")
                .env_remove("LC_MESSAGES")
                .env_remove("LANGUAGE")
                .env("LANG", "en_US.UTF-8");
            command
        }
    }
    impl Drop for Profile {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn cli_detects_system_language_and_all_overrides_without_changing_json() {
        let profile = Profile::new();
        for (locale, code, language) in [
            ("en_US.UTF-8", "en", Language::English),
            ("pt_BR.UTF-8", "pt", Language::Portuguese),
            ("ru_RU.UTF-8", "ru", Language::Russian),
            ("vi_VN.UTF-8", "vi", Language::Vietnamese),
        ] {
            let output = profile
                .command()
                .env("LANG", locale)
                .arg("status")
                .output()
                .unwrap();
            assert!(
                output.status.success(),
                "{}",
                String::from_utf8_lossy(&output.stderr)
            );
            assert_eq!(
                String::from_utf8(output.stdout).unwrap().trim(),
                language.format("Service: {phase}", &[("phase", language.text("stopped"))])
            );
            let output = profile
                .command()
                .args(["--language", code, "--json", "status"])
                .output()
                .unwrap();
            assert!(output.status.success());
            let json: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
            assert_eq!(json["message"], "Service stopped");
            assert_eq!(json["data"]["phase"], "stopped");
            let help = profile
                .command()
                .args(["--language", code, "--help"])
                .output()
                .unwrap();
            assert!(help.status.success());
            let help = String::from_utf8(help.stdout).unwrap();
            assert!(help.contains(
                language.text("OpenRad VPN: one persistent connection, simple local commands")
            ));
            assert!(
                help.contains(language.text("Options"))
                    && help.contains(language.text("Search public networks"))
            );
            assert!(help.contains(language.text("Display language: system, en, pt, ru or vi")));
            if language != Language::English {
                assert!(!help.contains("Print help") && !help.contains("[default:"));
            }
            let error = profile
                .command()
                .args(["--language", code, "delete", "Synthetic"])
                .output()
                .unwrap();
            assert!(!error.status.success());
            let error = String::from_utf8(error.stderr).unwrap();
            assert!(error.contains(
                language.text("error: the following required arguments were not provided:")
            ));
            assert!(error.contains("--yes"));
        }
        let output = profile
            .command()
            .env("LANG", "ru_RU.UTF-8")
            .args(["status", "--language=vi"])
            .output()
            .unwrap();
        assert_eq!(
            String::from_utf8(output.stdout).unwrap().trim(),
            "Dịch vụ: đã dừng"
        );
    }
}
