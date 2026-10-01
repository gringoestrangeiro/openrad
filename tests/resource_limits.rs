//! Startup limits are changed only in disposable, unprivileged child processes.
#![cfg(unix)]

use std::{fs, os::unix::process::CommandExt, process::Command};

fn startup_with_limits(soft: libc::rlim_t, hard: libc::rlim_t) -> Option<String> {
    let mut inherited = libc::rlimit {
        rlim_cur: 0,
        rlim_max: 0,
    };
    // SAFETY: inherited is writable storage for the requested resource.
    assert_eq!(
        unsafe { libc::getrlimit(libc::RLIMIT_NOFILE, &mut inherited) },
        0
    );
    if inherited.rlim_max < hard {
        return None;
    }
    let directory = std::env::temp_dir().join(format!(
        "openrad-resource-limits-{}-{}",
        std::process::id(),
        rand::random::<u64>(),
    ));
    fs::create_dir(&directory).unwrap();
    let mut command = Command::new(env!("CARGO_BIN_EXE_openrad"));
    command.arg("--help").env("OPENRAD_LOG_DIR", &directory);
    // SAFETY: the child only calls async-signal-safe setrlimit before exec. The
    // parent's limits and concurrently running tests are never changed.
    unsafe {
        command.pre_exec(move || {
            let limits = libc::rlimit {
                rlim_cur: soft,
                rlim_max: hard,
            };
            if libc::setrlimit(libc::RLIMIT_NOFILE, &limits) != 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    let output = command.output().unwrap();
    let log = fs::read_to_string(directory.join("cli-startup.log")).unwrap();
    fs::remove_dir_all(&directory).unwrap();
    assert!(
        output.status.success(),
        "startup failed: {}",
        String::from_utf8_lossy(&output.stderr),
    );
    Some(log)
}

#[test]
fn startup_raises_low_descriptor_limit_without_changing_hard_limit() {
    let Some(log) = startup_with_limits(1024, 16384) else {
        return;
    };
    assert!(log.contains("Open-file limit: soft=1024 -> 8192; hard=16384; target=8192"));
}

#[test]
fn startup_preserves_a_descriptor_limit_above_the_target() {
    let Some(log) = startup_with_limits(16384, 32768) else {
        return;
    };
    assert!(log.contains("Open-file limit: soft=16384 -> 16384; hard=32768; target=8192"));
}

#[test]
fn restrictive_hard_limit_caps_the_increase_without_preventing_startup() {
    let Some(log) = startup_with_limits(128, 256) else {
        return;
    };
    assert!(log.contains("Open-file limit: soft=128 -> 256; hard=256; target=8192"));
    assert!(log.contains("Open-file limit remains 256; the system hard limit is below 8192"));
}
