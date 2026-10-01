//! Per-process Unix resource limits for the desktop and VPN service.
use crate::early_log;
use std::io;

const MIN_OPEN_FILES: libc::rlim_t = 8192;

struct OpenFileLimit {
    previous: libc::rlim_t,
    soft: libc::rlim_t,
    hard: libc::rlim_t,
}

fn raise_open_file_limit() -> io::Result<OpenFileLimit> {
    let mut limits = libc::rlimit {
        rlim_cur: 0,
        rlim_max: 0,
    };
    // SAFETY: limits points to writable storage for the requested resource.
    if unsafe { libc::getrlimit(libc::RLIMIT_NOFILE, &mut limits) } != 0 {
        return Err(io::Error::last_os_error());
    }
    let previous = limits.rlim_cur;
    let target = previous.max(MIN_OPEN_FILES.min(limits.rlim_max));
    if target > previous {
        limits.rlim_cur = target;
        // SAFETY: limits is initialized; only the soft limit is raised, within
        // the existing hard limit. No privilege or system-wide change is needed.
        if unsafe { libc::setrlimit(libc::RLIMIT_NOFILE, &limits) } != 0 {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(OpenFileLimit {
        previous,
        soft: limits.rlim_cur,
        hard: limits.rlim_max,
    })
}

/// Raise the descriptor budget before starting graphics or VPN workers. Children
/// inherit it, and the service also applies it when launched directly via CLI.
/// A restrictive administrator limit must not prevent the application opening.
pub fn configure_open_file_limit() {
    match raise_open_file_limit() {
        Ok(limits) => {
            early_log::event(format_args!(
                "Open-file limit: soft={} -> {}; hard={}; target={MIN_OPEN_FILES}",
                limits.previous, limits.soft, limits.hard,
            ));
            if limits.soft < MIN_OPEN_FILES {
                let message = format!(
                    "Open-file limit remains {}; the system hard limit is below {MIN_OPEN_FILES}",
                    limits.soft,
                );
                early_log::event(format_args!("{message}"));
                eprintln!("OpenRad: {message}");
            }
        }
        Err(error) => {
            early_log::event(format_args!("Could not raise open-file limit: {error}"));
            eprintln!("OpenRad: could not raise open-file limit: {error}");
        }
    }
}
