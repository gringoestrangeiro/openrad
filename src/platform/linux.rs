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
    time::Duration,
};
pub const NAME: &str = "radminvpn0";
const TUNSETIFF: libc::c_ulong = 0x400454ca;
const TUNSETOWNER: libc::c_ulong = 0x400454cc;

pub struct Tap {
    file: File,
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
        Self::create_configured(vip, peers, helper, false)
    }
    /// Normal desktop LAN: a connected /8 and explicit IPv4 group routes.
    /// The bounded CLI retains its separate controlled-peer /32 route mode.
    pub fn create_lan_with_helper(vip: Ipv4Addr, helper: &std::path::Path) -> Result<Self> {
        Self::create_configured(vip, &[], helper, true)
    }
    fn create_configured(
        vip: Ipv4Addr,
        peers: &[Ipv4Addr],
        helper: &std::path::Path,
        lan: bool,
    ) -> Result<Self> {
        ensure!(
            helper.is_file(),
            "TAP helper executable missing; build the workspace including the openrad CLI"
        );
        ensure!(
            !std::path::Path::new("/sys/class/net/radminvpn0").exists(),
            "radminvpn0 already exists; refusing to touch it"
        );

        let (parent, child) = UnixStream::pair()?;
        parent.set_read_timeout(Some(Duration::from_secs(15)))?;
        let fd: OwnedFd = child.into();
        let mut command = Command::new("/usr/bin/sudo");
        command
            .arg("-n")
            .arg(helper)
            .arg("tap-helper")
            .arg("--vip")
            .arg(vip.to_string())
            .arg("--owner")
            .arg(unsafe { libc::getuid() }.to_string());
        for peer in peers {
            command.arg("--peer").arg(peer.to_string());
        }
        if lan {
            command.arg("--lan");
        }
        let mut child = command
            .stdin(Stdio::from(fd))
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()?;
        let received = receive_fd(parent.as_raw_fd());
        let status = child.wait()?;
        ensure!(
            status.success(),
            "TAP helper failed; sudo is required only for interface setup"
        );
        let file = received.context("TAP helper did not return an interface descriptor")?;
        Ok(Self { file })
    }
    pub fn ready(&self, timeout: i32) -> Result<bool> {
        readable(self.file.as_raw_fd(), timeout)
    }
    pub fn receive(&mut self) -> Result<Vec<u8>> {
        let mut b = vec![0; 65536];
        let n = self.file.read(&mut b)?;
        b.truncate(n);
        Ok(b)
    }
    pub fn send(&mut self, b: &[u8]) -> Result<()> {
        ensure!(
            (14..=1414).contains(&b.len()),
            "TAP Ethernet frame exceeds configured MTU"
        );
        ensure!(self.file.write(b)? == b.len(), "short TAP packet write");
        Ok(())
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
        "1400",
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
