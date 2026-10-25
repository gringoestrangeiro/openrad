//! Per-process Unix resource limits and allocator tuning for the desktop and VPN service.
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

/// glibc gives every thread that contends on `malloc` its own arena, up to
/// eight per CPU. The service runs one thread per peer, so idle arenas each
/// retain freed pages and the resident set grows with the peer count instead
/// of with live data. A small fixed pool keeps allocation concurrent for the
/// busy TAP/forwarding/peer threads while bounding that retention.
#[cfg(all(target_os = "linux", target_env = "gnu"))]
const MALLOC_ARENAS: libc::c_int = 4;

/// Limit allocator arenas before any worker thread starts. An explicit
/// `MALLOC_ARENA_MAX` from the environment keeps precedence.
pub fn configure_allocator() {
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    if std::env::var_os("MALLOC_ARENA_MAX").is_none() {
        // SAFETY: mallopt only adjusts glibc allocator tuning; it is called
        // before this process creates additional threads.
        if unsafe { libc::mallopt(libc::M_ARENA_MAX, MALLOC_ARENAS) } != 1 {
            early_log::event(format_args!("Could not limit allocator arenas"));
        }
    }
}

/// Return freed heap pages to the operating system after large transient
/// allocations (session teardown, full-roster snapshots). Purely advisory:
/// live allocations are untouched.
pub fn release_free_memory() {
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    // SAFETY: malloc_trim only releases free pages and is thread-safe.
    unsafe {
        libc::malloc_trim(0);
    }
}
