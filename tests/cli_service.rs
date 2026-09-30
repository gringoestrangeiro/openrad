#![cfg(any(unix, windows))]

use serde_json::Value;
#[cfg(unix)]
use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use std::{
    fs,
    path::{Path, PathBuf},
    process::{Command, Output},
};

const IDENTITY: &str = r#"{"format":"openrad-identity-v1","rid":123,"vip":"26.0.0.5","node_name":"synthetic","address":"00000000000000000000000000000000","credential":"010203040506","server_address":"127.0.0.1"}"#;

struct Harness {
    dir: PathBuf,
}
impl Harness {
    fn new() -> Self {
        let dir = std::env::temp_dir().join(format!(
            "openrad-cli-service-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        fs::create_dir(&dir).unwrap();
        Self { dir }
    }
    fn run(&self, args: &[&str]) -> Output {
        Command::new(env!("CARGO_BIN_EXE_openrad"))
            .arg("--data-dir")
            .arg(self.dir.join("state"))
            .arg("--json")
            .args(args)
            .output()
            .unwrap()
    }
    fn ok(&self, args: &[&str]) -> Value {
        let output = self.run(args);
        assert!(
            output.status.success(),
            "{args:?}: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        serde_json::from_slice(&output.stdout).unwrap()
    }
}
impl Drop for Harness {
    fn drop(&mut self) {
        let _ = self.run(&["stop"]);
        let _ = fs::remove_dir_all(&self.dir);
    }
}

#[test]
fn detached_service_survives_cli_exit_rejects_commands_offline_and_stops_cleanly() {
    let harness = Harness::new();
    let identity = harness.dir.join("source.json");
    fs::write(&identity, IDENTITY).unwrap();
    let imported = harness.ok(&["init", "--identity", identity.to_str().unwrap()]);
    assert_eq!(imported["data"]["rid"], 123);
    assert!(Path::new(imported["data"]["identity"].as_str().unwrap()).exists());
    #[cfg(unix)]
    assert_eq!(
        fs::metadata(harness.dir.join("state"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o700
    );

    harness.ok(&["start", "--no-tap"]);
    #[cfg(unix)]
    assert!(fs::symlink_metadata(harness.dir.join("state/control.sock"))
        .unwrap()
        .file_type()
        .is_socket());
    harness.ok(&["start", "--no-tap"]);
    let status = harness.ok(&["status"]);
    assert_eq!(status["data"]["rid"], 123);
    assert!(matches!(
        status["data"]["phase"].as_str(),
        Some("connecting" | "reconnecting")
    ));
    let join = harness.run(&["join", "Synthetic network"]);
    assert!(!join.status.success());
    let join_reply: Value = serde_json::from_slice(&join.stdout).unwrap();
    assert_eq!(join_reply["ok"], false);

    harness.ok(&["stop"]);
    #[cfg(unix)]
    assert!(!harness.dir.join("state/control.sock").exists());
    assert_eq!(harness.ok(&["status"])["data"]["phase"], "stopped");
}
