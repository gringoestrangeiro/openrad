//! Ephemeral TAP descriptor. Only its setup helper runs with CAP_NET_ADMIN.
//!
//! The helper passes an open, nonpersistent TAP FD over an inherited Unix socket
//! then exits. Closing the last descriptor removes the interface and its routes,
//! including on panic, SIGKILL, helper failure or parent process termination.
use crate::{session::readable, tunnel};
use anyhow::{ensure, Context, Result};
use std::{
    fs::{File, OpenOptions},
    io::{Read, Write},
    net::Ipv4Addr,
    os::{
        fd::{AsRawFd, FromRawFd, OwnedFd, RawFd},
        unix::{fs::OpenOptionsExt, net::UnixStream},
    },
    process::{Command, Stdio},
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, Instant},
};
pub const NAME: &str = "radminvpn0";
const TUNSETIFF: libc::c_ulong = 0x400454ca;
const TUNSETOWNER: libc::c_ulong = 0x400454cc;

pub struct Tap {
    file: File,
    read_buffer: Vec<u8>,
}
impl Tap {
    pub fn create(vip: Ipv4Addr, peers: &[Ipv4Addr]) -> Result<Self> {
        Self::create_with_helper(vip, peers, &std::env::current_exe()?)
    }
    pub fn create_with_helper(
        vip: Ipv4Addr,
        peers: &[Ipv4Addr],
        helper: &std::path::Path,
    ) -> Result<Self> {
        Self::create_configured(vip, peers, helper, false, &AtomicBool::new(false))
    }
    /// Persistent desktop and CLI LAN: a connected /8 and IPv4 group routes.
    /// The bounded diagnostic client retains controlled-peer /32 route mode.
    pub fn create_lan_with_helper(vip: Ipv4Addr, helper: &std::path::Path) -> Result<Self> {
        Self::create_lan_with_helper_cancellable(vip, helper, &AtomicBool::new(false))
    }
    pub(crate) fn create_lan_with_helper_cancellable(
        vip: Ipv4Addr,
        helper: &std::path::Path,
        cancelled: &AtomicBool,
    ) -> Result<Self> {
        Self::create_configured(vip, &[], helper, true, cancelled)
    }
    fn create_configured(
        vip: Ipv4Addr,
        peers: &[Ipv4Addr],
        helper: &std::path::Path,
        lan: bool,
        cancelled: &AtomicBool,
    ) -> Result<Self> {
        ensure!(
            helper.is_file(),
            "TAP helper executable missing; build the workspace including the openrad CLI"
        );
        ensure!(
            !std::path::Path::new("/sys/class/net/radminvpn0").exists(),
            "radminvpn0 already exists; refusing to touch it"
        );

        let helper = helper.canonicalize()?;
        let mut arguments = vec![
            helper.into_os_string(),
            "tap-helper".into(),
            "--vip".into(),
            vip.to_string().into(),
            "--owner".into(),
            unsafe { libc::getuid() }.to_string().into(),
        ];
        for peer in peers {
            arguments.extend(["--peer".into(), peer.to_string().into()]);
        }
        if lan {
            arguments.push("--lan".into());
        }
        let mut sudo = Command::new("/usr/bin/sudo");
        sudo.arg("-n").args(&arguments);
        let file = authorize_helper(sudo, &arguments, cancelled)?;
        Ok(Self {
            file,
            read_buffer: Vec::new(),
        })
    }
    pub fn ready(&self, timeout: i32) -> Result<bool> {
        readable(self.file.as_raw_fd(), timeout)
    }
    pub(crate) fn poll_fd(&self) -> Option<i32> {
        Some(self.file.as_raw_fd())
    }
    pub fn receive(&mut self) -> Result<Vec<u8>> {
        if self.read_buffer.is_empty() {
            self.read_buffer.resize(65536, 0);
        }
        let n = self.file.read(&mut self.read_buffer)?;
        Ok(self.read_buffer[..n].to_vec())
    }
    pub fn send(&mut self, b: &[u8]) -> Result<()> {
        ensure!(
            (14..=crate::tunnel::MAX_FRAME).contains(&b.len()),
            "TAP Ethernet frame exceeds configured MTU"
        );
        ensure!(self.file.write(b)? == b.len(), "short TAP packet write");
        Ok(())
    }
}
// A detached service has no launching terminal's sudo timestamp. Keep the
// noninteractive sudo path for administrator-managed authorization, then ask the
// session's Polkit agent. stdin remains the descriptor socket, never a password.
fn authorize_helper(
    sudo: Command,
    arguments: &[std::ffi::OsString],
    cancelled: &AtomicBool,
) -> Result<File> {
    let mut polkit = Command::new("/usr/bin/pkexec");
    polkit.arg("--disable-internal-agent").args(arguments);
    authorize_with_commands(sudo, polkit, cancelled)
}
fn authorize_with_commands(sudo: Command, polkit: Command, cancelled: &AtomicBool) -> Result<File> {
    if let Some(file) = run_helper(sudo, Duration::from_secs(15), cancelled)? {
        return Ok(file);
    }
    run_helper(polkit, Duration::from_secs(120), cancelled)?
        .context("TAP authorization failed; allow the system permission dialog and retry. A running Polkit authentication agent is required.")
}

fn run_helper(
    mut command: Command,
    authorization_timeout: Duration,
    cancelled: &AtomicBool,
) -> Result<Option<File>> {
    let (mut parent, child) = UnixStream::pair()?;
    let fd: OwnedFd = child.into();
    let mut child = match command
        .stdin(Stdio::from(fd))
        .stdout(Stdio::null())
        .stderr(Stdio::inherit())
        .spawn()
    {
        Ok(child) => child,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    drop(command); // EOF must be observable when elevation is refused.
    let result = (|| {
        let deadline = Instant::now() + authorization_timeout;
        wait_helper_socket(&parent, deadline, cancelled)?;
        let mut started = [0];
        if parent.read(&mut started)? == 0 {
            return Ok(None); // The helper never ran: authorization refused.
        }
        ensure!(started == *b"R", "invalid TAP helper startup response");
        // Once elevated, setup has its own short deadline. A setup failure must
        // not trigger another authorization prompt or another setup attempt.
        let deadline = Instant::now() + Duration::from_secs(15);
        wait_helper_socket(&parent, deadline, cancelled)?;
        let file = receive_fd(parent.as_raw_fd())
            .context("TAP helper did not return an interface descriptor")?;
        loop {
            if let Some(status) = child.try_wait()? {
                ensure!(status.success(), "TAP helper failed after authorization");
                return Ok(Some(file));
            }
            check_helper_deadline(deadline, cancelled)?;
            std::thread::sleep(Duration::from_millis(20));
        }
    })();
    // A timeout, cancellation, or malformed response must reap the process and
    // close our socket/received descriptor. Never wait indefinitely on a dialog.
    if child.try_wait()?.is_none() && child.kill().is_err() {
        // After elevation the kernel may deny signalling a root-owned helper.
        // Close the socket now and reap asynchronously; keep cancellation bounded.
        drop(parent);
        std::thread::spawn(move || {
            let _ = child.wait();
        });
    } else {
        let _ = child.wait();
    }
    result
}
fn check_helper_deadline(deadline: Instant, cancelled: &AtomicBool) -> Result<()> {
    ensure!(!cancelled.load(Ordering::Relaxed), "TAP setup cancelled");
    ensure!(
        Instant::now() < deadline,
        "TAP authorization or setup timed out; retry interface setup"
    );
    Ok(())
}
fn wait_helper_socket(
    socket: &UnixStream,
    deadline: Instant,
    cancelled: &AtomicBool,
) -> Result<()> {
    loop {
        check_helper_deadline(deadline, cancelled)?;
        if readable(socket.as_raw_fd(), 50)? {
            return Ok(());
        }
    }
}

fn ip(args: &[&str]) -> Result<()> {
    let status = Command::new("/usr/bin/ip").args(args).status()?;
    ensure!(status.success(), "TAP interface configuration failed");
    Ok(())
}
pub fn helper(vip: Ipv4Addr, owner: u32, peers: &[Ipv4Addr]) -> Result<()> {
    helper_with_lan(vip, owner, peers, false)
}
pub fn helper_with_lan(vip: Ipv4Addr, owner: u32, peers: &[Ipv4Addr], lan: bool) -> Result<()> {
    ensure!(
        unsafe { libc::geteuid() } == 0,
        "interface helper requires CAP_NET_ADMIN via sudo"
    );
    // Distinguish an elevation refusal from a failure inside the real helper.
    let socket_fd = unsafe { libc::dup(0) };
    ensure!(
        socket_fd >= 0,
        "TAP helper descriptor socket is unavailable"
    );
    let mut socket = unsafe { UnixStream::from_raw_fd(socket_fd) };
    socket.write_all(b"R")?;
    ensure!(
        vip.octets()[0] == 26 && peers.len() <= 1024,
        "invalid VPN interface parameters"
    );
    ensure!(
        peers.iter().all(|p| p.octets()[0] == 26 && *p != vip),
        "invalid peer host route"
    );
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC)
        .open("/dev/net/tun")?;
    let mut request: libc::ifreq = unsafe { std::mem::zeroed() };
    for (out, b) in request.ifr_name.iter_mut().zip(NAME.bytes()) {
        *out = b as libc::c_char;
    }
    request.ifr_ifru.ifru_flags =
        (libc::IFF_TAP | libc::IFF_NO_PI | libc::IFF_TUN_EXCL) as libc::c_short;
    ensure!(
        unsafe { libc::ioctl(file.as_raw_fd(), TUNSETIFF, &request) } >= 0,
        "TUNSETIFF failed: {}",
        std::io::Error::last_os_error()
    );
    ensure!(
        unsafe { libc::ioctl(file.as_raw_fd(), TUNSETOWNER, owner as libc::c_ulong) } >= 0,
        "TUNSETOWNER failed"
    );
    // No TUNSETPERSIST: any error below closes file and removes all new state.
    let mac = tunnel::mac(vip)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect::<Vec<_>>()
        .join(":");
    ip(&[
        "link",
        "set",
        "dev",
        NAME,
        "address",
        &mac,
        "mtu",
        "1500",
        "addrgenmode",
        "none",
    ])?;
    if lan {
        ip(&[
            "address",
            "add",
            &format!("{vip}/8"),
            "broadcast",
            "26.255.255.255",
            "dev",
            NAME,
        ])?;
        ip(&["link", "set", "dev", NAME, "multicast", "on"])?;
    } else {
        ip(&["address", "add", &format!("{vip}/32"), "dev", NAME])?;
    }
    ip(&["link", "set", "dev", NAME, "up"])?;
    if lan {
        // These routes are owned by the ephemeral interface. Do not replace an
        // existing route or default gateway. Application interface selection and
        // any more-specific multicast routes continue to take precedence.
        for destination in ["224.0.0.0/4", "255.255.255.255/32"] {
            ip(&[
                "route",
                "add",
                destination,
                "dev",
                NAME,
                "src",
                &vip.to_string(),
                "metric",
                "500",
            ])?;
        }
    }
    for peer in peers {
        ip(&[
            "route",
            "add",
            &format!("{peer}/32"),
            "dev",
            NAME,
            "src",
            &vip.to_string(),
        ])?;
    }
    send_fd(0, file.as_raw_fd())?;
    Ok(())
}
fn send_fd(socket: RawFd, fd: RawFd) -> Result<()> {
    let mut payload = *b"F";
    let mut iov = libc::iovec {
        iov_base: payload.as_mut_ptr().cast(),
        iov_len: 1,
    };
    let mut control = [0usize; 8];
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr().cast();
    msg.msg_controllen = unsafe { libc::CMSG_SPACE(std::mem::size_of::<RawFd>() as u32) } as usize;
    unsafe {
        let c = libc::CMSG_FIRSTHDR(&msg);
        (*c).cmsg_level = libc::SOL_SOCKET;
        (*c).cmsg_type = libc::SCM_RIGHTS;
        (*c).cmsg_len = libc::CMSG_LEN(std::mem::size_of::<RawFd>() as u32) as usize;
        std::ptr::write_unaligned(libc::CMSG_DATA(c).cast::<RawFd>(), fd);
        ensure!(
            libc::sendmsg(socket, &msg, libc::MSG_NOSIGNAL) == 1,
            "could not pass TAP descriptor"
        );
    }
    Ok(())
}
fn receive_fd(socket: RawFd) -> Result<File> {
    let mut payload = [0];
    let mut iov = libc::iovec {
        iov_base: payload.as_mut_ptr().cast(),
        iov_len: 1,
    };
    let mut control = [0usize; 8];
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr().cast();
    msg.msg_controllen = std::mem::size_of_val(&control);
    unsafe {
        ensure!(
            libc::recvmsg(socket, &mut msg, libc::MSG_CMSG_CLOEXEC) == 1 && payload == *b"F",
            "TAP helper ended before descriptor transfer"
        );
        let c = libc::CMSG_FIRSTHDR(&msg);
        ensure!(
            !c.is_null()
                && (*c).cmsg_level == libc::SOL_SOCKET
                && (*c).cmsg_type == libc::SCM_RIGHTS
                && (*c).cmsg_len == libc::CMSG_LEN(std::mem::size_of::<RawFd>() as u32) as usize
                && msg.msg_flags & libc::MSG_CTRUNC == 0,
            "invalid TAP descriptor transfer"
        );
        Ok(File::from_raw_fd(std::ptr::read_unaligned(
            libc::CMSG_DATA(c).cast::<RawFd>(),
        )))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn synthetic(mode: &str) -> Command {
        let mut command = Command::new(std::env::current_exe().unwrap());
        command
            .args([
                "--exact",
                "tap::implementation::tests::synthetic_elevated_helper",
                "--nocapture",
            ])
            .env("OPENRAD_SYNTHETIC_TAP", mode);
        command
    }

    #[test]
    fn synthetic_elevated_helper() {
        let Ok(mode) = std::env::var("OPENRAD_SYNTHETIC_TAP") else {
            return;
        };
        if mode == "refused" {
            std::process::exit(1);
        }
        let mut socket = unsafe { UnixStream::from_raw_fd(libc::dup(0)) };
        socket.write_all(b"R").unwrap();
        if mode == "setup-failed" {
            std::process::exit(1);
        }
        let file = File::open("/dev/null").unwrap();
        send_fd(0, file.as_raw_fd()).unwrap();
        std::process::exit(0);
    }

    #[test]
    fn refused_sudo_falls_back_and_preserves_the_descriptor_socket() {
        let file = authorize_with_commands(
            synthetic("refused"),
            synthetic("success"),
            &AtomicBool::new(false),
        )
        .unwrap();
        let mut data = [0];
        assert_eq!((&file).read(&mut data).unwrap(), 0);
        assert_ne!(
            unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFD) } & libc::FD_CLOEXEC,
            0
        );
    }

    #[test]
    fn authorized_sudo_does_not_request_polkit() {
        authorize_with_commands(
            synthetic("success"),
            Command::new("/nonexistent-polkit"),
            &AtomicBool::new(false),
        )
        .unwrap();
    }

    #[test]
    fn setup_failure_does_not_start_another_authorization() {
        let error = authorize_with_commands(
            synthetic("setup-failed"),
            synthetic("success"),
            &AtomicBool::new(false),
        )
        .unwrap_err();
        assert!(error.to_string().contains("descriptor"));
    }

    #[test]
    fn rejected_authorization_is_reported_without_waiting_for_the_timeout() {
        let started = Instant::now();
        let error = authorize_with_commands(
            synthetic("refused"),
            synthetic("refused"),
            &AtomicBool::new(false),
        )
        .unwrap_err();
        assert!(error.to_string().contains("Polkit"));
        assert!(started.elapsed() < Duration::from_secs(5));
    }

    #[test]
    fn stalled_authorization_is_bounded_and_reaped() {
        let mut command = Command::new("/bin/sleep");
        command.arg("10");
        let started = Instant::now();
        let error =
            run_helper(command, Duration::from_millis(100), &AtomicBool::new(false)).unwrap_err();
        assert!(error.to_string().contains("timed out"));
        assert!(started.elapsed() < Duration::from_secs(2));
    }

    #[test]
    fn stopping_the_session_cancels_pending_authorization() {
        let cancelled = std::sync::Arc::new(AtomicBool::new(false));
        let stop = cancelled.clone();
        let worker = std::thread::spawn(move || {
            let mut command = Command::new("/bin/sleep");
            command.arg("10");
            run_helper(command, Duration::from_secs(120), &stop)
        });
        std::thread::sleep(Duration::from_millis(100));
        let started = Instant::now();
        cancelled.store(true, Ordering::Relaxed);
        assert!(worker
            .join()
            .unwrap()
            .unwrap_err()
            .to_string()
            .contains("cancelled"));
        assert!(started.elapsed() < Duration::from_secs(2));
    }
}
