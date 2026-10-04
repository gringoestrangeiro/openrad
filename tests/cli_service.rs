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
fn cli_saves_queries_and_restores_broadcast_policy_without_starting_a_service() {
    let harness = Harness::new();
    assert!(harness.ok(&["broadcast-peer"])["data"]["broadcast_peer"].is_null());
    assert_eq!(
        harness.ok(&["broadcast-peer", "456"])["data"]["broadcast_peer"],
        456
    );
    assert_eq!(
        harness.ok(&["broadcast-peer"])["data"]["broadcast_peer"],
        456
    );
    let invalid = harness.run(&["broadcast-peer", "missing-node"]);
    assert!(!invalid.status.success());
    assert_eq!(
        harness.ok(&["broadcast-peer"])["data"]["broadcast_peer"],
        456
    );
    assert!(harness.ok(&["broadcast-peer", "0.0.0.0"])["data"]["broadcast_peer"].is_null());
    harness.ok(&["broadcast-peer", "456"]);
    assert!(harness.ok(&["broadcast-peer", "--all"])["data"]["broadcast_peer"].is_null());
    assert!(!harness
        .run(&["broadcast-peer", "456", "--all"])
        .status
        .success());
    assert_eq!(harness.ok(&["status"])["data"]["phase"], "stopped");
}

#[test]
fn cli_includes_and_excludes_multiple_broadcast_recipients_atomically() {
    let harness = Harness::new();
    let included = harness.ok(&["broadcast-peer", "789", "456", "456"]);
    assert_eq!(
        included["data"]["broadcast_peers"],
        serde_json::json!({"mode":"include", "peers":[456,789]})
    );
    assert!(included["data"]["broadcast_peer"].is_null());
    let excluded = harness.ok(&["broadcast-peer", "--exclude", "456", "789"]);
    assert_eq!(
        excluded["data"]["broadcast_peers"],
        serde_json::json!({"mode":"exclude", "peers":[456,789]})
    );
    assert_eq!(harness.ok(&["broadcast-peer"])["data"], excluded["data"]);
    for args in [
        vec!["broadcast-peer", "--exclude"],
        vec!["broadcast-peer", "--exclude", "456", "--all"],
        vec!["broadcast-peer", "--exclude", "0.0.0.0"],
        vec!["broadcast-peer", "456", "0"],
        vec!["broadcast-peer", "456", "0.0.0.0"],
        vec!["broadcast-peer", "456", "missing-node"],
    ] {
        assert!(!harness.run(&args).status.success(), "{args:?}");
        assert_eq!(harness.ok(&["broadcast-peer"])["data"], excluded["data"]);
    }
    assert_eq!(
        harness.ok(&["broadcast-peer", "--all"])["data"]["broadcast_peers"],
        serde_json::json!({"mode":"all"})
    );
    assert_eq!(harness.ok(&["status"])["data"]["phase"], "stopped");
}

#[cfg(target_os = "linux")]
#[test]
fn service_inherits_the_raised_startup_descriptor_limit() {
    use std::os::unix::process::CommandExt;

    let mut inherited = libc::rlimit {
        rlim_cur: 0,
        rlim_max: 0,
    };
    // SAFETY: inherited is writable storage for the requested resource.
    assert_eq!(
        unsafe { libc::getrlimit(libc::RLIMIT_NOFILE, &mut inherited) },
        0
    );
    if inherited.rlim_max < 16384 {
        return;
    }
    let harness = Harness::new();
    let source = harness.dir.join("source.json");
    fs::write(&source, IDENTITY).unwrap();
    harness.ok(&["init", "--identity", source.to_str().unwrap()]);
    let logs = harness.dir.join("logs");
    let mut command = Command::new(env!("CARGO_BIN_EXE_openrad"));
    command
        .arg("--data-dir")
        .arg(harness.dir.join("state"))
        .args(["--json", "start", "--no-tap"])
        .env("OPENRAD_LOG_DIR", &logs);
    // SAFETY: only the child's limits change, using async-signal-safe setrlimit.
    unsafe {
        command.pre_exec(|| {
            let limits = libc::rlimit {
                rlim_cur: 1024,
                rlim_max: 16384,
            };
            if libc::setrlimit(libc::RLIMIT_NOFILE, &limits) != 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    let output = command.output().unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let reply: Value = serde_json::from_slice(&output.stdout).unwrap();
    let pid = reply["data"]["process_id"].as_u64().unwrap();
    let limits = fs::read_to_string(format!("/proc/{pid}/limits")).unwrap();
    let open_files: Vec<_> = limits
        .lines()
        .find(|line| line.starts_with("Max open files"))
        .unwrap()
        .split_whitespace()
        .collect();
    assert_eq!(&open_files[3..5], ["8192", "16384"]);
    let log = fs::read_to_string(logs.join("cli-daemon-startup.log")).unwrap();
    assert!(log.contains("Open-file limit: soft=8192 -> 8192; hard=16384; target=8192"));
}

#[test]
fn gui_service_start_and_cli_start_share_one_process_in_either_order() {
    use openrad::daemon::{self, DataDir, Request};
    for gui_first in [true, false] {
        let harness = Harness::new();
        let source = harness.dir.join("source.json");
        fs::write(&source, IDENTITY).unwrap();
        harness.ok(&["init", "--identity", source.to_str().unwrap()]);
        let dir = DataDir::open(Some(harness.dir.join("state"))).unwrap();
        let executable = Path::new(env!("CARGO_BIN_EXE_openrad"));
        if gui_first {
            assert!(daemon::spawn_with_executable(&dir, true, executable).unwrap());
        } else {
            assert_eq!(harness.ok(&["start", "--no-tap"])["data"]["started"], true);
        }
        let before = daemon::request(&dir, &Request::Status).unwrap().data;
        if gui_first {
            assert_eq!(harness.ok(&["start", "--no-tap"])["data"]["started"], false);
        } else {
            assert!(!daemon::spawn_with_executable(&dir, true, executable).unwrap());
        }
        let after = harness.ok(&["status"])["data"].clone();
        assert_eq!(before["process_id"], after["process_id"]);
        assert_eq!(before["rid"], after["rid"]);
        let renamed = daemon::request(
            &dir,
            &Request::Rename {
                node_name: "renamed-from-gui".into(),
            },
        )
        .unwrap();
        assert!(renamed.ok, "{}", renamed.message);
        assert_eq!(
            harness.ok(&["status"])["data"]["node_name"],
            "renamed-from-gui"
        );
        harness.ok(&["rename", "renamed-from-cli"]);
        assert_eq!(
            daemon::request(&dir, &Request::Status).unwrap().data["node_name"],
            "renamed-from-cli"
        );
        assert_eq!(dir.load_identity().unwrap().rid, 123);
        assert_eq!(dir.load_identity().unwrap().vip.to_string(), "26.0.0.5");
        assert_eq!(dir.load_identity().unwrap().node_name, "renamed-from-cli");
        // Desktop-style service requests and CLI changes share one persisted
        // selection, including while this disposable engine is reconnecting.
        assert!(
            daemon::broadcast_peers(&dir, Some(vec!["456".into(), "789".into()]), true)
                .unwrap()
                .ok
        );
        assert_eq!(
            harness.ok(&["broadcast-peer"])["data"]["broadcast_peers"],
            serde_json::json!({"mode":"exclude", "peers":[456,789]})
        );
        harness.ok(&["broadcast-peer", "456", "789"]);
        assert_eq!(
            daemon::request(&dir, &Request::Status).unwrap().data["preferences"]["broadcast_peers"],
            serde_json::json!({"mode":"include", "peers":[456,789]})
        );
        harness.ok(&["force-relay", "true"]);
        assert_eq!(
            daemon::request(&dir, &Request::Status).unwrap().data["preferences"]["force_relay"],
            true
        );
        harness.ok(&["stop"]);
        harness.ok(&["start", "--no-tap"]);
        let restarted = harness.ok(&["status"]);
        assert_eq!(restarted["data"]["node_name"], "renamed-from-cli");
        assert_eq!(restarted["data"]["preferences"]["force_relay"], true);
    }
}

#[test]
fn simultaneous_frontend_starts_are_serialized_and_offline_rename_preserves_secrets() {
    use openrad::daemon::{self, DataDir};
    let harness = Harness::new();
    let source = harness.dir.join("source.json");
    fs::write(&source, IDENTITY).unwrap();
    harness.ok(&["init", "--identity", source.to_str().unwrap()]);
    harness.ok(&["rename", "new device name"]);
    let dir = DataDir::open(Some(harness.dir.join("state"))).unwrap();
    let saved = dir.load_identity().unwrap();
    assert_eq!(saved.node_name, "new device name");
    assert_eq!(saved.password().unwrap(), [1, 2, 3, 4, 5, 6]);
    let starts: Vec<_> = (0..4)
        .map(|_| {
            let dir = dir.clone();
            std::thread::spawn(move || {
                daemon::spawn_with_executable(&dir, true, Path::new(env!("CARGO_BIN_EXE_openrad")))
                    .unwrap()
            })
        })
        .collect();
    let newly_started = starts
        .into_iter()
        .map(|thread| thread.join().unwrap())
        .filter(|started| *started)
        .count();
    assert_eq!(newly_started, 1);
}

#[cfg(unix)]
#[test]
fn control_reply_timeout_is_aggregate_even_when_server_keeps_sending_bytes() {
    use openrad::daemon::{self, DataDir, Request};
    use std::{
        io::{Read, Write},
        os::unix::net::UnixListener,
        thread,
        time::{Duration, Instant},
    };
    let harness = Harness::new();
    let dir = DataDir::open(Some(harness.dir.join("state"))).unwrap();
    let listener = UnixListener::bind(dir.socket()).unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(1)))
            .unwrap();
        let mut command = Vec::new();
        stream.read_to_end(&mut command).unwrap();
        assert!(matches!(
            serde_json::from_slice::<Request>(&command).unwrap(),
            Request::Status
        ));
        for _ in 0..20 {
            if stream.write_all(b" ").is_err() {
                break;
            }
            thread::sleep(Duration::from_millis(20));
        }
    });
    let started = Instant::now();
    let result = daemon::request_with_timeout(&dir, &Request::Status, Duration::from_millis(100));
    assert!(result.is_err());
    assert!(started.elapsed() < Duration::from_millis(350));
    server.join().unwrap();
    fs::remove_file(dir.socket()).unwrap();
}

#[cfg(target_os = "linux")]
#[test]
fn full_control_socket_backlog_obeys_request_timeout() {
    use openrad::daemon::{self, DataDir, Request};
    use std::{
        os::{
            fd::AsRawFd,
            unix::net::{UnixListener, UnixStream},
        },
        time::{Duration, Instant},
    };
    let harness = Harness::new();
    let dir = DataDir::open(Some(harness.dir.join("state"))).unwrap();
    let listener = UnixListener::bind(dir.socket()).unwrap();
    assert_eq!(unsafe { libc::listen(listener.as_raw_fd(), 0) }, 0);
    let _occupied = UnixStream::connect(dir.socket()).unwrap();
    let started = Instant::now();
    let result = daemon::request_with_timeout(&dir, &Request::Stop, Duration::from_millis(100));
    let error = result.err().expect("full backlog must time out");
    assert!(format!("{error:#}").contains("timed out"));
    assert!(started.elapsed() < Duration::from_secs(1));
    drop(listener);
    fs::remove_file(dir.socket()).unwrap();
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
