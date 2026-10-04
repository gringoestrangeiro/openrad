//! Shared per-user VPN service and bounded local control protocol.
#![cfg(any(unix, windows))]
#[cfg(windows)]
#[path = "platform/windows_pipe.rs"]
mod windows_pipe;

use crate::{
    diagnostics::Diagnostics,
    early_log::{self, Stage},
    network::{MemberAction, NetworkPassword, NetworkRequest},
    protocol::Identity,
    runtime::{self, Command, Snapshot, Update},
};
use anyhow::{anyhow, bail, ensure, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
#[cfg(unix)]
use std::io::{Read, Write};
#[cfg(unix)]
use std::os::unix::{
    fs::{DirBuilderExt, FileTypeExt, MetadataExt, OpenOptionsExt, PermissionsExt},
    net::{UnixListener, UnixStream},
};
use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    path::PathBuf,
    process::{Child, Command as ProcessCommand, Stdio},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{self, Sender, SyncSender},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};

const REQUEST_LIMIT: u64 = 64 * 1024;
const REPLY_LIMIT: u64 = 8 * 1024 * 1024;
const CONTROL_CONNECTION_LIMIT: usize = 32;
const COMMAND_QUEUE_LIMIT: usize = 128;
#[cfg(unix)]
const SOCKET: &str = "control.sock";

#[derive(Clone)]
pub struct DataDir {
    pub path: PathBuf,
}
impl DataDir {
    pub fn open(override_path: Option<PathBuf>) -> Result<Self> {
        let path = match override_path {
            Some(path) => path,
            None => {
                #[cfg(unix)]
                let base = std::env::var_os("XDG_STATE_HOME")
                    .map(PathBuf::from)
                    .or_else(|| {
                        std::env::var_os("HOME")
                            .map(|home| PathBuf::from(home).join(".local/state"))
                    })
                    .context("Set HOME or use --data-dir")?;
                #[cfg(windows)]
                let base = std::env::var_os("LOCALAPPDATA")
                    .map(PathBuf::from)
                    .context("Set LOCALAPPDATA or use --data-dir")?;
                let canonical = base.join("openrad");
                let legacy = directories::ProjectDirs::from("org", "OpenRad", "openrad")
                    .map(|dirs| dirs.data_local_dir().to_path_buf());
                if !canonical.join("profile").exists() && !canonical.join("settings.json").exists()
                {
                    legacy
                        .filter(|path| path.join("settings.json").exists())
                        .unwrap_or(canonical)
                } else {
                    canonical
                }
            }
        };
        let path = if path.is_absolute() {
            path
        } else {
            std::env::current_dir()?.join(path)
        };
        let mut builder = fs::DirBuilder::new();
        builder.recursive(true);
        #[cfg(unix)]
        builder.mode(0o700);
        builder.create(&path)?;
        let path = path.canonicalize()?;
        let meta = fs::metadata(&path)?;
        ensure!(meta.is_dir(), "data path is not a directory");
        #[cfg(unix)]
        ensure!(
            meta.uid() == unsafe { libc::getuid() },
            "data directory must be owned by the current user"
        );
        #[cfg(unix)]
        ensure!(
            meta.mode() & 0o077 == 0,
            "data directory must have mode 0700: {}",
            path.display()
        );
        #[cfg(unix)]
        ensure!(
            path.join(SOCKET).as_os_str().len() < 100,
            "data directory path is too long for a Unix socket"
        );
        crate::output::secure_directory(&path)?;
        Ok(Self { path })
    }
    #[cfg(unix)]
    pub fn socket(&self) -> PathBuf {
        self.path.join(SOCKET)
    }
    pub fn endpoint_exists(&self) -> bool {
        #[cfg(unix)]
        {
            self.socket().exists()
        }
        #[cfg(windows)]
        {
            windows_pipe::exists(&self.path)
        }
    }
    pub fn identity(&self) -> PathBuf {
        self.path.join("profile/identity.json")
    }
    /// A private-profile marker, broken link or inaccessible file must not
    /// switch storage backends or authorize a second device registration. An
    /// interrupted CLI initialization can leave a profile without an identity.
    /// The loader reports that error instead of treating the profile as fresh.
    pub fn identity_file_present(&self) -> bool {
        match fs::symlink_metadata(self.identity()) {
            Ok(_) => true,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                match fs::symlink_metadata(self.path.join("profile")) {
                    Ok(_) => true,
                    Err(error) => error.kind() != std::io::ErrorKind::NotFound,
                }
            }
            Err(error) => error.kind() != std::io::ErrorKind::NotFound,
        }
    }
    pub fn modulus(&self) -> PathBuf {
        self.path.join("profile/modulus.bin")
    }
    fn lock(&self) -> Result<File> {
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true).truncate(false);
        #[cfg(unix)]
        options
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
        let file = options.open(self.path.join("service.lock"))?;
        file.try_lock()
            .context("OpenRad service is already running for this profile")?;
        Ok(file)
    }
    /// Hold while replacing a profile to exclude a running VPN service.
    pub fn stopped_lock(&self) -> Result<File> {
        self.lock()
    }
    /// Recover a leftover socket only after proving no service owns this profile.
    pub fn cleanup_stopped_endpoint(&self) -> Result<bool> {
        let Ok(_lock) = self.lock() else {
            return Ok(false);
        };
        #[cfg(unix)]
        match fs::symlink_metadata(self.socket()) {
            Ok(meta) if meta.file_type().is_socket() => fs::remove_file(self.socket())?,
            Ok(_) => bail!(
                "refusing to remove non-socket at {}",
                self.socket().display()
            ),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
        Ok(!self.endpoint_exists())
    }
    pub fn load_identity(&self) -> Result<Identity> {
        if !self.identity_file_present() {
            let entry = self.entry()?;
            let secret = zeroize::Zeroizing::new(entry.get_secret().map_err(|_| {
                anyhow!("Could not read the saved identity. Unlock your credential store; your identity has not been replaced.")
            })?);
            ensure!(secret.as_slice() != b"openrad-provisioning-pending-v1",
                "An earlier registration did not finish saving. Import a saved identity to recover; OpenRad will not create another identity automatically.");
            return Identity::from_secret(&secret);
        }
        Identity::load(&self.identity()).with_context(|| {
            format!(
                "No saved identity at {}. Run `openrad init` first",
                self.identity().display()
            )
        })
    }
    fn load_modulus(&self) -> Result<Vec<u8>> {
        if let Some(bytes) = crate::file_io::read_optional_bounded(&self.modulus(), 4096)? {
            ensure!(!bytes.is_empty(), "public modulus size is invalid");
            Ok(bytes.to_vec())
        } else {
            Ok(crate::SERVER_MODULUS.to_vec())
        }
    }
    pub fn entry(&self) -> Result<keyring::Entry> {
        Ok(keyring::Entry::new(
            "org.openrad.desktop",
            &self.path.to_string_lossy(),
        )?)
    }
    pub fn save_identity(&self, identity: &Identity) -> Result<()> {
        let secret = zeroize::Zeroizing::new(serde_json::to_vec(identity)?);
        if self.identity_file_present() {
            atomic_save(&self.identity(), &secret)
        } else {
            self.entry()?.set_secret(&secret).map_err(|_| anyhow!(
                "Could not save the identity in the credential store. Keep this window open, unlock your keyring, then retry."
            ))
        }
    }
    pub fn profile_lock(&self) -> Result<File> {
        let file = self.profile_lock_file()?;
        file.lock()?;
        Ok(file)
    }
    /// Desktop operations must fail visibly instead of waiting indefinitely
    /// for another frontend to finish changing the identity.
    pub fn try_profile_lock(&self) -> Result<File> {
        let file = self.profile_lock_file()?;
        file.try_lock()
            .context("Another frontend is changing this identity. Try again shortly.")?;
        Ok(file)
    }
    fn profile_lock_file(&self) -> Result<File> {
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true).truncate(false);
        #[cfg(unix)]
        options
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
        let file = options.open(self.path.join("identity.lock"))?;
        Ok(file)
    }
    pub fn preferences(&self) -> Result<ServicePreferences> {
        let path = self.path.join("service.json");
        let Some(bytes) = crate::file_io::read_optional_bounded(&path, 64 * 1024)? else {
            return Ok(ServicePreferences::default());
        };
        let preferences: ServicePreferences = serde_json::from_slice(&bytes)?;
        preferences.validate()?;
        Ok(preferences)
    }
    pub fn save_preferences(&self, preferences: &ServicePreferences) -> Result<()> {
        preferences.validate()?;
        atomic_save(
            &self.path.join("service.json"),
            &serde_json::to_vec(preferences)?,
        )
    }
}

/// Atomic private writes also preserve the previous identity on storage errors.
fn atomic_save(path: &std::path::Path, bytes: &[u8]) -> Result<()> {
    let temporary = path.with_extension(format!(
        "{}-{}.tmp",
        std::process::id(),
        rand::random::<u64>()
    ));
    let result = (|| -> Result<()> {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        options
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
        let mut file = options.open(&temporary)?;
        std::io::Write::write_all(&mut file, bytes)?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ServicePreferences {
    pub force_relay: bool,
    pub auto_reconnect: bool,
    pub reconnect_attempts: u32,
    pub reconnect_base_delay_seconds: u64,
    pub traffic_peers: Option<std::collections::BTreeSet<u64>>,
    pub broadcast_peer: Option<u64>,
}
impl Default for ServicePreferences {
    fn default() -> Self {
        Self {
            force_relay: false,
            auto_reconnect: true,
            reconnect_attempts: 3,
            reconnect_base_delay_seconds: 2,
            traffic_peers: None,
            broadcast_peer: None,
        }
    }
}
impl ServicePreferences {
    fn validate(&self) -> Result<()> {
        ensure!(
            self.broadcast_peer != Some(0)
                && (1..=10).contains(&self.reconnect_attempts)
                && (1..=30).contains(&self.reconnect_base_delay_seconds)
                && self
                    .traffic_peers
                    .as_ref()
                    .is_none_or(|peers| peers.len() <= 1024 && !peers.contains(&0)),
            "Settings are out of range"
        );
        Ok(())
    }
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "command", rename_all = "snake_case")]
pub enum Request {
    Status,
    Networks,
    Peers,
    Search {
        query: String,
        cursor: u64,
    },
    Join {
        name: String,
        password: Option<NetworkPassword>,
    },
    Create {
        name: String,
        password: NetworkPassword,
    },
    Leave {
        network: String,
    },
    Delete {
        network: String,
    },
    Member {
        network: String,
        member: u64,
        action: MemberActionWire,
    },
    RetryPeers,
    RetryInterface,
    Ping {
        peer: u64,
    },
    Rename {
        node_name: String,
    },
    BroadcastPeer {
        target: Option<String>,
    },
    Configure {
        preferences: ServicePreferences,
    },
    Reconnect,
    Stop,
}
#[derive(Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MemberActionWire {
    Kick,
    GrantAdmin,
    RevokeAdmin,
}
impl From<MemberActionWire> for MemberAction {
    fn from(value: MemberActionWire) -> Self {
        match value {
            MemberActionWire::Kick => Self::Kick,
            MemberActionWire::GrantAdmin => Self::GrantAdmin,
            MemberActionWire::RevokeAdmin => Self::RevokeAdmin,
        }
    }
}
impl Request {
    pub fn from_runtime(command: Command) -> Result<Self> {
        Ok(match command {
            Command::Search { query, cursor } => Self::Search { query, cursor },
            Command::Ping { peer } => Self::Ping { peer },
            Command::RetryPeers => Self::RetryPeers,
            Command::RetryInterface => Self::RetryInterface,
            Command::Join(name) => Self::Join {
                name,
                password: None,
            },
            Command::Leave(network) => Self::Leave { network },
            Command::Network(request) => match request {
                NetworkRequest::Join { name, password } => Self::Join { name, password },
                NetworkRequest::Create { name, password } => Self::Create { name, password },
                NetworkRequest::Leave { network } => Self::Leave { network },
                NetworkRequest::Delete { network } => Self::Delete { network },
                NetworkRequest::Member {
                    network,
                    member,
                    action,
                } => Self::Member {
                    network,
                    member,
                    action: match action {
                        MemberAction::Kick => MemberActionWire::Kick,
                        MemberAction::GrantAdmin => MemberActionWire::GrantAdmin,
                        MemberAction::RevokeAdmin => MemberActionWire::RevokeAdmin,
                    },
                },
            },
            Command::Tagged { .. } => bail!("unsupported control command"),
        })
    }
}
#[derive(Serialize, Deserialize)]
pub struct Reply {
    pub ok: bool,
    pub message: String,
    pub data: Value,
}
impl Reply {
    fn ok(message: impl Into<String>, data: Value) -> Self {
        Self {
            ok: true,
            message: message.into(),
            data,
        }
    }
    fn error(message: impl Into<String>) -> Self {
        Self {
            ok: false,
            message: message.into(),
            data: Value::Null,
        }
    }
}

fn resolve_broadcast_peer(
    target: &str,
    own_rid: u64,
    snapshot: Option<&Snapshot>,
) -> Result<Option<u64>> {
    if target == "0.0.0.0" {
        return Ok(None);
    }
    if let Ok(peer) = target.parse::<u64>() {
        ensure!(
            peer != 0 && peer != own_rid,
            "Broadcast peer RID must be nonzero and belong to another device"
        );
        return Ok(Some(peer));
    }
    let snapshot = snapshot.context("Connect before selecting a broadcast peer by name or IP; a RID can be saved while disconnected")?;
    let ip = target.parse::<std::net::Ipv4Addr>().ok();
    let mut matches = snapshot.peers.values().filter(|view| {
        view.peer.rid != own_rid
            && ip.map_or_else(|| view.peer.name == target, |ip| view.peer.vip == ip)
    });
    let peer = matches.next().map(|view| view.peer.rid);
    ensure!(
        peer.is_some() && matches.next().is_none(),
        "Broadcast peer not found or ambiguous; use its RID from `openrad peers`"
    );
    Ok(peer)
}

fn broadcast_peer_reply(peer: Option<u64>) -> Reply {
    Reply::ok(
        peer.map_or_else(
            || "Outgoing broadcasts: all peers".to_owned(),
            |peer| format!("Outgoing broadcasts: RID {peer}"),
        ),
        json!({"broadcast_peer": peer}),
    )
}

/// Query or save the shared policy without starting or reconnecting the VPN.
pub fn broadcast_peer(dir: &DataDir, target: Option<String>) -> Result<Reply> {
    if dir.endpoint_exists() {
        return request(dir, &Request::BroadcastPeer { target });
    }
    let _profile_lock = dir.try_profile_lock()?;
    let _service_lock = dir.stopped_lock()?;
    let mut preferences = dir.preferences()?;
    if let Some(target) = target {
        preferences.broadcast_peer = resolve_broadcast_peer(&target, 0, None)?;
        dir.save_preferences(&preferences)?;
    }
    Ok(broadcast_peer_reply(preferences.broadcast_peer))
}

#[cfg(unix)]
fn read_bounded(
    stream: &mut UnixStream,
    limit: u64,
    timeout: Duration,
) -> Result<zeroize::Zeroizing<Vec<u8>>> {
    let until = Instant::now() + timeout;
    let mut data = zeroize::Zeroizing::new(Vec::new());
    let mut buffer = zeroize::Zeroizing::new([0; 8192]);
    loop {
        let remaining = until.saturating_duration_since(Instant::now());
        ensure!(!remaining.is_zero(), "service request timed out");
        stream.set_read_timeout(Some(remaining))?;
        let length = match stream.read(buffer.as_mut_slice()) {
            Ok(length) => length,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error.into()),
        };
        if length == 0 {
            break;
        }
        data.extend_from_slice(&buffer[..length]);
        ensure!(
            data.len() as u64 <= limit,
            "local control message too large"
        );
    }
    Ok(data)
}
#[cfg(unix)]
fn write_bounded(stream: &mut UnixStream, mut bytes: &[u8], timeout: Duration) -> Result<()> {
    let until = Instant::now() + timeout;
    while !bytes.is_empty() {
        let remaining = until.saturating_duration_since(Instant::now());
        ensure!(!remaining.is_zero(), "service request timed out");
        stream.set_write_timeout(Some(remaining))?;
        match stream.write(bytes) {
            Ok(0) => bail!("service closed the control connection"),
            Ok(length) => bytes = &bytes[length..],
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}
#[cfg(unix)]
pub fn request(dir: &DataDir, command: &Request) -> Result<Reply> {
    request_with_timeout(dir, command, Duration::from_secs(35))
}

#[cfg(target_os = "linux")]
fn connect_control(dir: &DataDir, until: Instant) -> Result<UnixStream> {
    use std::os::{
        fd::{AsRawFd, FromRawFd, OwnedFd},
        unix::ffi::OsStrExt,
    };
    // A blocking Unix connect can hang on a full service backlog before socket
    // read/write timeouts apply. Poll it within the same request deadline.
    let raw = unsafe {
        libc::socket(
            libc::AF_UNIX,
            libc::SOCK_STREAM | libc::SOCK_NONBLOCK | libc::SOCK_CLOEXEC,
            0,
        )
    };
    if raw < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    // SAFETY: the newly created descriptor is owned only by this guard.
    let fd = unsafe { OwnedFd::from_raw_fd(raw) };
    let mut address: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    address.sun_family = libc::AF_UNIX as _;
    let socket = dir.socket();
    let path = socket.as_os_str().as_bytes();
    ensure!(
        path.len() < address.sun_path.len(),
        "control socket path is too long"
    );
    for (target, byte) in address.sun_path.iter_mut().zip(path) {
        *target = *byte as _;
    }
    loop {
        ensure!(Instant::now() < until, "service request timed out");
        // SAFETY: pointer/size describe the initialized sockaddr_un; the path
        // fits its zero-terminated field and the fd stays owned above.
        let result = unsafe {
            libc::connect(
                fd.as_raw_fd(),
                (&address as *const libc::sockaddr_un).cast(),
                std::mem::size_of_val(&address) as _,
            )
        };
        if result == 0 {
            break;
        }
        let error = std::io::Error::last_os_error();
        match error.raw_os_error() {
            Some(libc::EISCONN) => break,
            // AF_UNIX reports a full listen backlog as EAGAIN, without queuing
            // the connect. Retrying here cannot duplicate an accepted request.
            Some(libc::EAGAIN | libc::EINTR) => {
                thread::sleep(
                    until
                        .saturating_duration_since(Instant::now())
                        .min(Duration::from_millis(10)),
                );
            }
            Some(libc::EINPROGRESS | libc::EALREADY) => {
                let mut poll = libc::pollfd {
                    fd: fd.as_raw_fd(),
                    events: libc::POLLOUT,
                    revents: 0,
                };
                let millis = until
                    .saturating_duration_since(Instant::now())
                    .as_millis()
                    .clamp(1, 50) as i32;
                let result = unsafe { libc::poll(&mut poll, 1, millis) };
                if result < 0
                    && std::io::Error::last_os_error().kind() != std::io::ErrorKind::Interrupted
                {
                    return Err(std::io::Error::last_os_error().into());
                }
            }
            _ => return Err(error.into()),
        }
    }
    let stream = UnixStream::from(fd);
    stream.set_nonblocking(false)?;
    Ok(stream)
}

#[cfg(all(unix, not(target_os = "linux")))]
fn connect_control(dir: &DataDir, _until: Instant) -> Result<UnixStream> {
    Ok(UnixStream::connect(dir.socket())?)
}

#[cfg(unix)]
pub fn request_with_timeout(dir: &DataDir, command: &Request, timeout: Duration) -> Result<Reply> {
    ensure!(!timeout.is_zero(), "service request timed out");
    let until = Instant::now() + timeout;
    let mut stream =
        connect_control(dir, until).context("Service is stopped. Run `openrad start` first")?;
    stream.set_read_timeout(Some(timeout))?;
    let payload = zeroize::Zeroizing::new(serde_json::to_vec(command)?);
    ensure!(
        payload.len() as u64 <= REQUEST_LIMIT,
        "local control message too large"
    );
    write_bounded(
        &mut stream,
        &payload,
        until
            .saturating_duration_since(Instant::now())
            .min(Duration::from_secs(5)),
    )?;
    stream.shutdown(std::net::Shutdown::Write)?;
    let mut bytes = Vec::new();
    let mut buffer = [0; 8192];
    loop {
        let remaining = until.saturating_duration_since(Instant::now());
        ensure!(!remaining.is_zero(), "service request timed out");
        stream.set_read_timeout(Some(remaining))?;
        let length = stream.read(&mut buffer)?;
        if length == 0 {
            break;
        }
        bytes.extend_from_slice(&buffer[..length]);
        ensure!(
            bytes.len() as u64 <= REPLY_LIMIT,
            "local control message too large"
        );
    }
    ensure!(!bytes.is_empty(), "service closed the control connection");
    Ok(serde_json::from_slice(&bytes)?)
}

#[cfg(windows)]
pub fn request(dir: &DataDir, command: &Request) -> Result<Reply> {
    request_with_timeout(dir, command, Duration::from_secs(35))
}

#[cfg(windows)]
pub fn request_with_timeout(dir: &DataDir, command: &Request, timeout: Duration) -> Result<Reply> {
    let until = Instant::now() + timeout;
    let mut stream =
        windows_pipe::Connection::connect_timeout(&dir.path, timeout.min(Duration::from_secs(5)))
            .context("Service is stopped. Run `openrad start` first")?;
    let payload = zeroize::Zeroizing::new(serde_json::to_vec(command)?);
    ensure!(
        payload.len() as u64 <= REQUEST_LIMIT,
        "local control message too large"
    );
    stream.write_message(
        &payload,
        until
            .saturating_duration_since(Instant::now())
            .min(Duration::from_secs(5)),
    )?;
    let bytes =
        stream.read_message(REPLY_LIMIT, until.saturating_duration_since(Instant::now()))?;
    stream.acknowledge()?;
    Ok(serde_json::from_slice(&bytes)?)
}

struct State {
    identity: Option<Identity>,
    preferences: ServicePreferences,
    broadcast_policy: runtime::BroadcastPolicy,
    restart: bool,
    phase: &'static str,
    error: Option<String>,
    snapshot: Option<Arc<Snapshot>>,
    sender: Option<SyncSender<Command>>,
    session_stop: Option<Arc<AtomicBool>>,
    pending: BTreeMap<u64, Sender<Reply>>,
}
struct Shared {
    state: Mutex<State>,
    shutdown: AtomicBool,
    next_id: AtomicU64,
    rid: u64,
    directory: Option<DataDir>,
    disable_interface: bool,
}
impl Shared {
    fn new(id: &Identity, disable_interface: bool) -> Self {
        Self {
            state: Mutex::new(State {
                identity: Some(id.clone()),
                preferences: ServicePreferences::default(),
                broadcast_policy: runtime::BroadcastPolicy::default(),
                restart: false,
                phase: "connecting",
                error: None,
                snapshot: None,
                sender: None,
                session_stop: None,
                pending: BTreeMap::new(),
            }),
            shutdown: AtomicBool::new(false),
            next_id: AtomicU64::new(1),
            rid: id.rid,
            directory: None,
            disable_interface,
        }
    }
    fn stop(&self) {
        self.shutdown.store(true, Ordering::Relaxed);
        if let Some(stop) = &self.state.lock().unwrap().session_stop {
            stop.store(true, Ordering::Relaxed);
        }
    }
    fn update(&self, update: Update) {
        match update {
            Update::Ping {
                peer,
                id: Some(id),
                rtt_ms,
                error,
            } => {
                if let Some(waiter) = self.state.lock().unwrap().pending.remove(&id) {
                    let _ = waiter.send(Reply {
                        ok: error.is_none(),
                        message: error.unwrap_or_else(|| "Peer RTT measured".into()),
                        data: json!({"peer": peer, "rtt_ms": rtt_ms}),
                    });
                }
            }
            Update::Ping { .. } => {}
            Update::State(snapshot) => {
                let mut state = self.state.lock().unwrap();
                state.phase = "connected";
                state.error = None;
                state.snapshot = Some(Arc::new(snapshot));
            }
            Update::CommandResult {
                id,
                message,
                error,
                catalog,
            } => {
                let waiter = self.state.lock().unwrap().pending.remove(&id);
                if let Some(waiter) = waiter {
                    let data = catalog
                        .map(|(networks, cursor)| json!({"networks":networks,"cursor":cursor}))
                        .unwrap_or(Value::Null);
                    let _ = waiter.send(Reply {
                        ok: !error,
                        message,
                        data,
                    });
                }
            }
            Update::Operation { .. } | Update::Catalog { .. } => {}
        }
    }
    fn disconnected(&self, error: String) {
        let pending = {
            let mut state = self.state.lock().unwrap();
            state.phase = "reconnecting";
            state.error = Some(error.clone());
            state.sender = None;
            state.session_stop = None;
            state.snapshot = None;
            std::mem::take(&mut state.pending)
        };
        for (_, waiter) in pending {
            let _ = waiter.send(Reply::error(format!(
                "Service connection lost; membership will reload: {error}"
            )));
        }
    }
    fn status(&self) -> Reply {
        // Keep the immutable snapshot alive without cloning the roster or
        // holding the engine state lock throughout JSON construction.
        let (phase, node_name, preferences, error, snapshot) = {
            let state = self.state.lock().unwrap();
            (
                state.phase,
                state.identity.as_ref().map(|id| id.node_name.clone()),
                state.preferences.clone(),
                state.error.clone(),
                state.snapshot.clone(),
            )
        };
        Reply::ok(
            phase,
            json!({
            "phase":phase,"rid":self.rid,"node_name":node_name,
            "process_id": std::process::id(),
            "preferences": preferences,
            "interface_disabled":self.disable_interface,
                "error":error,"snapshot":snapshot.as_deref(),
            }),
        )
    }
    fn command(&self, command: Command) -> Reply {
        let ping = match &command {
            Command::Ping { peer } => Some(*peer),
            _ => None,
        };
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = mpsc::channel();
        let sender = {
            let mut state = self.state.lock().unwrap();
            if state.phase != "connected" && state.phase != "connecting" {
                return Reply::error(
                    "Service is reconnecting; run `openrad status` and retry shortly",
                );
            }
            let Some(sender) = state.sender.clone() else {
                return Reply::error("Service connection is unavailable");
            };
            state.pending.insert(id, tx);
            sender
        };
        if let Err(error) = sender.try_send(Command::Tagged {
            id,
            command: Box::new(command),
        }) {
            self.state.lock().unwrap().pending.remove(&id);
            return Reply::error(match error {
                mpsc::TrySendError::Full(_) => "Service command queue is full; retry shortly",
                mpsc::TrySendError::Disconnected(_) => "Service connection is unavailable",
            });
        }
        match rx.recv_timeout(if ping.is_some() {
            runtime::PING_TIMEOUT
        } else {
            Duration::from_secs(27)
        }) {
            Ok(reply) => reply,
            Err(_) => {
                self.state.lock().unwrap().pending.remove(&id);
                if let Some(peer) = ping {
                    Reply {
                        ok: false,
                        message: runtime::PING_TIMEOUT_MESSAGE.into(),
                        data: json!({"peer": peer, "rtt_ms": null}),
                    }
                } else {
                    Reply::error(
                        "Network command timed out; check `openrad status` before retrying",
                    )
                }
            }
        }
    }
    fn queue(&self, command: Command) -> Reply {
        let sender = {
            let state = self.state.lock().unwrap();
            if state.phase != "connected" {
                return Reply::error("Service is reconnecting; retry shortly");
            }
            state.sender.clone()
        };
        match sender.map(|sender| sender.try_send(command)) {
            Some(Ok(())) => Reply::ok("Requested", Value::Null),
            Some(Err(mpsc::TrySendError::Full(_))) => {
                Reply::error("Service command queue is full; retry shortly")
            }
            _ => Reply::error("Service connection is unavailable"),
        }
    }
    fn resolve_network(&self, name: &str) -> Result<String> {
        let state = self.state.lock().unwrap();
        ensure!(
            state.phase == "connected",
            "Service is reconnecting; retry shortly"
        );
        let snapshot = state
            .snapshot
            .as_ref()
            .context("Membership is not loaded yet")?;
        let matches: Vec<_> = snapshot
            .networks
            .iter()
            .filter(|network| network.name == name || network.network_id == name)
            .collect();
        ensure!(
            matches.len() == 1,
            "network not found or ambiguous; use its ID from `openrad networks`"
        );
        Ok(matches[0].network_id.clone())
    }
    fn handle(&self, command: Request) -> Reply {
        match self.handle_result(command) {
            Ok(reply) => reply,
            Err(error) => Reply::error(format!("{error:#}")),
        }
    }
    fn handle_result(&self, command: Request) -> Result<Reply> {
        Ok(match command {
            Request::Status => self.status(),
            Request::Networks => {
                let state = self.state.lock().unwrap();
                let snapshot = state
                    .snapshot
                    .as_ref()
                    .context("Membership is not loaded yet; run `openrad status`")?;
                let networks: Vec<_> = snapshot.networks.iter().map(|n| json!({
                    "name":n.name,"id":n.network_id,
                    "role": snapshot.roles.get(&n.network_id).and_then(|roles| roles.get(&self.rid)).copied(),
                })).collect();
                Reply::ok(
                    format!("{} networks", networks.len()),
                    Value::Array(networks),
                )
            }
            Request::Peers => {
                let state = self.state.lock().unwrap();
                let snapshot = state
                    .snapshot
                    .as_ref()
                    .context("Membership is not loaded yet; run `openrad status`")?;
                Reply::ok(
                    format!("{} peers", snapshot.peers.len()),
                    json!(snapshot.peers),
                )
            }
            Request::Search { query, cursor } => self.command(Command::Search { query, cursor }),
            Request::Join { name, password } => {
                {
                    let state = self.state.lock().unwrap();
                    if let Some(network) = state.snapshot.as_ref().and_then(|snapshot| {
                        snapshot
                            .networks
                            .iter()
                            .find(|network| network.name == name)
                    }) {
                        let role = state
                            .snapshot
                            .as_ref()
                            .and_then(|snapshot| snapshot.roles.get(&network.network_id))
                            .and_then(|roles| roles.get(&self.rid))
                            .copied();
                        return Ok(Reply::ok(
                            if role == Some(0) {
                                "Membership is pending administrator approval"
                            } else {
                                "Already joined"
                            },
                            json!({"name":network.name,"id":network.network_id,"role":role}),
                        ));
                    }
                }
                self.command(Command::Network(NetworkRequest::Join { name, password }))
            }
            Request::Create { name, password } => {
                self.command(Command::Network(NetworkRequest::Create { name, password }))
            }
            Request::Leave { network } => {
                let network = self.resolve_network(&network)?;
                self.command(Command::Network(NetworkRequest::Leave { network }))
            }
            Request::Delete { network } => {
                let network = self.resolve_network(&network)?;
                self.command(Command::Network(NetworkRequest::Delete { network }))
            }
            Request::Member {
                network,
                member,
                action,
            } => {
                ensure!(member != 0, "member RID must be nonzero");
                let network = self.resolve_network(&network)?;
                self.command(Command::Network(NetworkRequest::Member {
                    network,
                    member,
                    action: action.into(),
                }))
            }
            Request::RetryPeers => self.queue(Command::RetryPeers),
            Request::RetryInterface => self.queue(Command::RetryInterface),
            Request::Ping { peer } => self.command(Command::Ping { peer }),
            Request::BroadcastPeer { target } => {
                let mut state = self.state.lock().unwrap();
                if let Some(target) = target {
                    let peer =
                        resolve_broadcast_peer(&target, self.rid, state.snapshot.as_deref())?;
                    let mut preferences = state.preferences.clone();
                    preferences.broadcast_peer = peer;
                    self.directory
                        .as_ref()
                        .context("Profile is unavailable")?
                        .save_preferences(&preferences)?;
                    state.preferences = preferences;
                    state.broadcast_policy.set_peer(peer);
                }
                broadcast_peer_reply(state.preferences.broadcast_peer)
            }
            Request::Rename { node_name } => {
                crate::protocol::validate_node_name(&node_name)?;
                let mut state = self.state.lock().unwrap();
                let mut identity = state.identity.clone().context("No identity loaded")?;
                identity.node_name = node_name;
                self.directory
                    .as_ref()
                    .context("Profile is unavailable")?
                    .save_identity(&identity)?;
                state.identity = Some(identity);
                state.restart = true;
                if let Some(stop) = &state.session_stop {
                    stop.store(true, Ordering::Relaxed);
                }
                Reply::ok(
                    "Device name saved. Reconnecting with the same identity…",
                    Value::Null,
                )
            }
            Request::Configure { preferences } => {
                let mut state = self.state.lock().unwrap();
                // Broadcast selection has its own command; stale frontend
                // settings must not overwrite a concurrent selection.
                let mut preferences = preferences;
                preferences.broadcast_peer = state.preferences.broadcast_peer;
                preferences.validate()?;
                self.directory
                    .as_ref()
                    .context("Profile is unavailable")?
                    .save_preferences(&preferences)?;
                let restart = preferences.force_relay != state.preferences.force_relay
                    || preferences.traffic_peers != state.preferences.traffic_peers;
                state.preferences = preferences;
                if restart {
                    state.restart = true;
                    if let Some(stop) = &state.session_stop {
                        stop.store(true, Ordering::Relaxed);
                    }
                }
                Reply::ok("Settings saved", Value::Null)
            }
            Request::Reconnect => {
                let mut state = self.state.lock().unwrap();
                state.restart = true;
                if let Some(stop) = &state.session_stop {
                    stop.store(true, Ordering::Relaxed);
                }
                Reply::ok("Reconnecting with the same identity…", Value::Null)
            }
            Request::Stop => Reply::ok("Service stopping", Value::Null),
        })
    }
}

fn supervisor(
    shared: Arc<Shared>,
    identity: Identity,
    modulus: Vec<u8>,
    dir: DataDir,
    disable_interface: bool,
) {
    let diagnostics = Diagnostics::open(&dir.path.join("diagnostics")).unwrap_or_default();
    early_log::checkpoint(Stage::SessionStarting);
    let mut failures = 0u32;
    while !shared.shutdown.load(Ordering::Relaxed) {
        let (tx, rx) = mpsc::sync_channel(COMMAND_QUEUE_LIMIT);
        let session_stop = Arc::new(AtomicBool::new(false));
        let (identity, preferences) = {
            let mut state = shared.state.lock().unwrap();
            state.phase = "connecting";
            state.sender = Some(tx);
            state.session_stop = Some(session_stop.clone());
            state.restart = false;
            state.snapshot = None;
            state
                .broadcast_policy
                .set_peer(state.preferences.broadcast_peer);
            (
                state.identity.clone().unwrap_or_else(|| identity.clone()),
                state.preferences.clone(),
            )
        };
        let shared_updates = shared.clone();
        let options = runtime::Options {
            helper: std::env::current_exe().ok(),
            disable_interface,
            diagnostics: diagnostics.clone(),
            force_relay: preferences.force_relay,
            traffic_peers: preferences.traffic_peers.clone(),
            broadcast_policy: shared.state.lock().unwrap().broadcast_policy.clone(),
        };
        let started = Instant::now();
        let outcome = runtime::run(
            identity.clone(),
            modulus.clone(),
            options,
            rx,
            session_stop,
            move |update| shared_updates.update(update),
        );
        if shared.shutdown.load(Ordering::Relaxed) {
            break;
        }
        if shared.state.lock().unwrap().restart {
            failures = 0;
            shared.disconnected("Reconnecting with the same identity…".into());
            continue;
        }
        if let Err(error) = &outcome {
            early_log::fatal_error(error);
        }
        let error = outcome
            .err()
            .map(|e| format!("{e:#}"))
            .unwrap_or_else(|| "VPN engine stopped unexpectedly".into());
        shared.disconnected(error);
        if started.elapsed() >= Duration::from_secs(60) {
            failures = 0;
        }
        failures = failures.saturating_add(1);
        if !preferences.auto_reconnect || failures > preferences.reconnect_attempts {
            shared.state.lock().unwrap().phase = "error";
            while !shared.shutdown.load(Ordering::Relaxed) && !shared.state.lock().unwrap().restart
            {
                thread::sleep(Duration::from_millis(50));
            }
            continue;
        }
        let delay = Duration::from_secs(
            preferences
                .reconnect_base_delay_seconds
                .saturating_mul(1u64 << failures.saturating_sub(1).min(9))
                .min(300),
        );
        let until = Instant::now() + delay;
        while !shared.shutdown.load(Ordering::Relaxed)
            && !shared.state.lock().unwrap().restart
            && Instant::now() < until
        {
            thread::sleep(Duration::from_millis(100));
        }
    }
    shared.disconnected("Service stopped".into());
}

#[cfg(unix)]
fn serve_connection(mut stream: UnixStream, shared: Arc<Shared>) -> Result<()> {
    let bytes = read_bounded(&mut stream, REQUEST_LIMIT, Duration::from_secs(5))?;
    ensure!(!bytes.is_empty(), "empty control request");
    let command: Request = serde_json::from_slice(&bytes)?;
    let stopping = matches!(&command, Request::Stop);
    let reply = shared.handle(command);
    let payload = serde_json::to_vec(&reply)?;
    ensure!(
        payload.len() as u64 <= REPLY_LIMIT,
        "control reply too large"
    );
    write_bounded(&mut stream, &payload, Duration::from_secs(5))?;
    if stopping {
        shared.stop();
    }
    Ok(())
}

#[cfg(unix)]
type ControlConnection = UnixStream;
#[cfg(windows)]
type ControlConnection = windows_pipe::Connection;

fn reap_control_workers(workers: &mut Vec<thread::JoinHandle<()>>) {
    let mut index = 0;
    while index < workers.len() {
        if workers[index].is_finished() {
            let _ = workers.swap_remove(index).join();
        } else {
            index += 1;
        }
    }
}

fn dispatch_control_connection(
    stream: ControlConnection,
    shared: Arc<Shared>,
    workers: &mut Vec<thread::JoinHandle<()>>,
) -> std::io::Result<()> {
    if workers.len() >= CONTROL_CONNECTION_LIMIT {
        return Err(std::io::ErrorKind::WouldBlock.into());
    }
    workers.push(
        thread::Builder::new()
            .name("openrad-control".into())
            .spawn(move || {
                let _ = serve_connection(stream, shared);
            })?,
    );
    Ok(())
}

#[cfg(unix)]
pub fn run(dir: DataDir, disable_interface: bool) -> Result<()> {
    early_log::checkpoint(Stage::ServiceProfileLoading);
    let profile_lock = dir.profile_lock()?;
    let identity = dir.load_identity()?;
    let modulus = dir.load_modulus()?;
    early_log::checkpoint(Stage::ServiceLock);
    let _lock = dir.lock()?;
    let socket = dir.socket();
    match fs::symlink_metadata(&socket) {
        Ok(meta) if meta.file_type().is_socket() => fs::remove_file(&socket)?,
        Ok(_) => bail!("refusing to replace non-socket at {}", socket.display()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    let listener = UnixListener::bind(&socket)?;
    fs::set_permissions(&socket, fs::Permissions::from_mode(0o600))?;
    listener.set_nonblocking(true)?;
    drop(profile_lock);
    let mut shared = Shared::new(&identity, disable_interface);
    shared.directory = Some(dir.clone());
    shared.state.get_mut().unwrap().preferences = dir.preferences()?;
    let shared = Arc::new(shared);
    let on_signal = shared.clone();
    ctrlc::set_handler(move || on_signal.stop())?;
    let worker_shared = shared.clone();
    let worker =
        thread::spawn(move || supervisor(worker_shared, identity, modulus, dir, disable_interface));
    let mut control_workers = Vec::new();
    while !shared.shutdown.load(Ordering::Relaxed) {
        reap_control_workers(&mut control_workers);
        // Leave excess clients in the bounded OS backlog. The Windows listener
        // also needs room for its next instance to keep the pipe name occupied.
        if control_workers.len() >= CONTROL_CONNECTION_LIMIT {
            thread::sleep(Duration::from_millis(50));
            continue;
        }
        match listener.accept() {
            Ok((stream, _)) => {
                if let Err(error) =
                    dispatch_control_connection(stream, shared.clone(), &mut control_workers)
                {
                    shared.stop();
                    let _ = worker.join();
                    let _ = fs::remove_file(&socket);
                    return Err(error.into());
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(50))
            }
            Err(error) => {
                shared.stop();
                let _ = worker.join();
                let _ = fs::remove_file(&socket);
                return Err(error.into());
            }
        }
    }
    worker
        .join()
        .map_err(|_| anyhow!("service supervisor panicked"))?;
    drop(listener);
    fs::remove_file(&socket)?;
    Ok(())
}

#[cfg(windows)]
fn serve_connection(mut stream: windows_pipe::Connection, shared: Arc<Shared>) -> Result<()> {
    let bytes =
        zeroize::Zeroizing::new(stream.read_message(REQUEST_LIMIT, Duration::from_secs(5))?);
    let command: Request = serde_json::from_slice(&bytes)?;
    let stopping = matches!(&command, Request::Stop);
    let payload = serde_json::to_vec(&shared.handle(command))?;
    ensure!(
        payload.len() as u64 <= REPLY_LIMIT,
        "control reply too large"
    );
    stream.write_message(&payload, Duration::from_secs(5))?;
    let acknowledged = stream.wait_for_acknowledgement();
    if stopping {
        shared.stop();
    }
    acknowledged?;
    Ok(())
}

#[cfg(windows)]
pub fn run(dir: DataDir, disable_interface: bool) -> Result<()> {
    early_log::checkpoint(Stage::ServiceProfileLoading);
    let profile_lock = dir.profile_lock()?;
    let identity = dir.load_identity()?;
    let modulus = dir.load_modulus()?;
    early_log::checkpoint(Stage::ServiceLock);
    let _lock = dir.lock()?;
    let mut listener = windows_pipe::Listener::bind(&dir.path)?;
    drop(profile_lock);
    early_log::checkpoint(Stage::ServiceListening);
    let mut shared = Shared::new(&identity, disable_interface);
    shared.directory = Some(dir.clone());
    shared.state.get_mut().unwrap().preferences = dir.preferences()?;
    let shared = Arc::new(shared);
    let worker_shared = shared.clone();
    let worker =
        thread::spawn(move || supervisor(worker_shared, identity, modulus, dir, disable_interface));
    let mut control_workers = Vec::new();
    let result = (|| -> Result<()> {
        while !shared.shutdown.load(Ordering::Relaxed) {
            reap_control_workers(&mut control_workers);
            if control_workers.len() >= CONTROL_CONNECTION_LIMIT {
                thread::sleep(Duration::from_millis(50));
                continue;
            }
            if let Some(stream) = listener.accept()? {
                dispatch_control_connection(stream, shared.clone(), &mut control_workers)?;
            } else {
                thread::sleep(Duration::from_millis(50));
            }
        }
        Ok(())
    })();
    shared.stop();
    worker
        .join()
        .map_err(|_| anyhow!("service supervisor panicked"))?;
    result
}

pub fn spawn(dir: &DataDir, disable_interface: bool) -> Result<bool> {
    spawn_with_executable(dir, disable_interface, &std::env::current_exe()?)
}
pub fn spawn_with_executable(
    dir: &DataDir,
    disable_interface: bool,
    executable: &std::path::Path,
) -> Result<bool> {
    early_log::checkpoint(Stage::ServiceStarting);
    if request(dir, &Request::Status).is_ok() {
        return Ok(false);
    }
    dir.load_identity()?;
    let _startup_lock = {
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true).truncate(false);
        #[cfg(unix)]
        options
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
        let lock = options.open(dir.path.join("startup.lock"))?;
        lock.lock()?;
        lock
    };
    if request(dir, &Request::Status).is_ok() {
        return Ok(false);
    }
    let mut options = OpenOptions::new();
    options.create(true).append(true);
    #[cfg(unix)]
    options
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    let log = options.open(dir.path.join("service.log"))?;
    let mut command = ProcessCommand::new(executable);
    command.arg("--data-dir").arg(&dir.path).arg("__daemon");
    if disable_interface {
        command.arg("--no-tap");
    }
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        unsafe {
            command.pre_exec(|| {
                if libc::setsid() < 0 {
                    return Err(std::io::Error::last_os_error());
                }
                Ok(())
            });
        }
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        use windows_sys::Win32::System::Threading::{CREATE_NEW_PROCESS_GROUP, DETACHED_PROCESS};
        command.creation_flags(DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP);
    }
    let mut child: Child = command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::from(log))
        .spawn()?;
    early_log::child_started(child.id());
    let until = Instant::now() + Duration::from_secs(5);
    loop {
        if request(dir, &Request::Status).is_ok() {
            early_log::checkpoint(Stage::ServiceReady);
            return Ok(true);
        }
        if let Some(status) = child.try_wait()? {
            early_log::child_exited(status);
            early_log::checkpoint(Stage::ServiceExited);
            bail!(
                "service exited with {status}; see {}",
                dir.path.join("service.log").display()
            );
        }
        if Instant::now() >= until {
            early_log::checkpoint(Stage::ServiceTimeout);
        }
        ensure!(
            Instant::now() < until,
            "service did not create its control socket; see {}",
            dir.path.join("service.log").display()
        );
        thread::sleep(Duration::from_millis(50));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    fn shared() -> Shared {
        let id = Identity::from_secret(br#"{"format":"openrad-identity-v1","rid":123,"vip":"26.0.0.5","node_name":"test","address":"00000000000000000000000000000000","credential":"010203040506","server_address":"192.0.2.1"}"#).unwrap();
        Shared::new(&id, false)
    }
    #[test]
    fn broadcast_targets_resolve_by_rid_name_or_ip_and_reject_ambiguous_matches() {
        let mut snapshot = Snapshot::default();
        for rid in [2, 3] {
            snapshot.peers.insert(
                rid,
                runtime::PeerView {
                    peer: crate::protocol::Peer {
                        rid,
                        name: format!("peer-{rid}"),
                        vip: std::net::Ipv4Addr::new(26, 0, 0, rid as u8),
                        server: None,
                        state: 0,
                        network_ids: Default::default(),
                    },
                    status: runtime::PeerState::Offline,
                    detail: String::new(),
                    transport: None,
                },
            );
        }
        for target in ["2", "peer-2", "26.0.0.2"] {
            assert_eq!(
                resolve_broadcast_peer(target, 123, Some(&snapshot)).unwrap(),
                Some(2)
            );
        }
        assert_eq!(resolve_broadcast_peer("0.0.0.0", 123, None).unwrap(), None);
        assert_eq!(resolve_broadcast_peer("2", 123, None).unwrap(), Some(2));
        for target in ["0", "123", "missing", "26.0.0.99"] {
            assert!(resolve_broadcast_peer(target, 123, Some(&snapshot)).is_err());
        }
        assert!(resolve_broadcast_peer("peer-2", 123, None).is_err());
        snapshot.peers.get_mut(&3).unwrap().peer.name = "peer-2".into();
        snapshot.peers.get_mut(&3).unwrap().peer.vip = "26.0.0.2".parse().unwrap();
        assert!(resolve_broadcast_peer("peer-2", 123, Some(&snapshot)).is_err());
        assert!(resolve_broadcast_peer("26.0.0.2", 123, Some(&snapshot)).is_err());
        assert_eq!(
            resolve_broadcast_peer("2", 123, Some(&snapshot)).unwrap(),
            Some(2)
        );
    }
    #[test]
    fn broadcast_policy_is_saved_applied_live_and_preserved_by_other_frontend_settings() {
        let path = std::env::temp_dir().join(format!(
            "openrad-broadcast-policy-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        let directory = DataDir::open(Some(path)).unwrap();
        let mut shared = shared();
        shared.directory = Some(directory.clone());
        assert!(
            shared
                .handle(Request::BroadcastPeer {
                    target: Some("2".into())
                })
                .ok
        );
        assert_eq!(directory.preferences().unwrap().broadcast_peer, Some(2));
        {
            let state = shared.state.lock().unwrap();
            assert_eq!(state.broadcast_policy.peer(), Some(2));
            assert!(!state.restart);
        }
        assert!(
            shared
                .handle(Request::Configure {
                    preferences: ServicePreferences::default()
                })
                .ok
        );
        assert_eq!(directory.preferences().unwrap().broadcast_peer, Some(2));
        assert_eq!(
            shared.handle(Request::BroadcastPeer { target: None }).data["broadcast_peer"],
            2
        );
        assert!(
            !shared
                .handle(Request::BroadcastPeer {
                    target: Some("0".into())
                })
                .ok
        );
        assert_eq!(directory.preferences().unwrap().broadcast_peer, Some(2));
        assert!(
            shared
                .handle(Request::BroadcastPeer {
                    target: Some("0.0.0.0".into())
                })
                .ok
        );
        assert_eq!(directory.preferences().unwrap().broadcast_peer, None);
        assert_eq!(shared.state.lock().unwrap().broadcast_policy.peer(), None);
        let mut legacy: ServicePreferences = serde_json::from_str("{}").unwrap();
        assert_eq!(legacy.broadcast_peer, None);
        legacy.broadcast_peer = Some(0);
        assert!(legacy.validate().is_err());
        fs::remove_dir_all(directory.path).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn a_broken_identity_link_does_not_switch_to_credential_storage() {
        let path = std::env::temp_dir().join(format!(
            "openrad-identity-link-test-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        let directory = DataDir::open(Some(path)).unwrap();
        assert!(!directory.identity_file_present());
        fs::create_dir(directory.path.join("profile")).unwrap();
        assert!(directory.identity_file_present());
        assert!(directory
            .load_identity()
            .err()
            .unwrap()
            .to_string()
            .starts_with("No saved identity at "));
        std::os::unix::fs::symlink(
            directory.path.join("missing-identity"),
            directory.identity(),
        )
        .unwrap();
        assert!(directory.identity_file_present());
        let error = directory.load_identity().err().unwrap();
        assert!(error.to_string().starts_with("No saved identity at "));
        assert!(fs::symlink_metadata(directory.identity())
            .unwrap()
            .file_type()
            .is_symlink());
        fs::remove_dir_all(directory.path).unwrap();
    }
    #[test]
    fn private_control_commands_preserve_the_wire_format_and_validate_passwords() {
        let request = Request::from_runtime(Command::Network(NetworkRequest::Create {
            name: "Synthetic LAN".into(),
            password: NetworkPassword::new("synthetic-password".into()).unwrap(),
        }))
        .unwrap();
        let json = zeroize::Zeroizing::new(serde_json::to_vec(&request).unwrap());
        let Request::Create { name, password } = serde_json::from_slice::<Request>(&json).unwrap()
        else {
            panic!("expected a create command");
        };
        assert_eq!(name, "Synthetic LAN");
        assert_eq!(password.as_str(), "synthetic-password");
        assert_eq!(format!("{password:?}"), "NetworkPassword([redacted])");
        assert!(serde_json::from_slice::<Request>(
            br#"{"command":"create","name":"Synthetic LAN","password":"short"}"#
        )
        .is_err());
    }
    #[test]
    fn full_service_command_queue_rejects_work_without_leaking_waiters() {
        let shared = shared();
        let (tx, rx) = mpsc::sync_channel(2);
        {
            let mut state = shared.state.lock().unwrap();
            state.phase = "connected";
            state.sender = Some(tx);
        }
        assert!(shared.queue(Command::RetryPeers).ok);
        assert!(shared.queue(Command::RetryInterface).ok);
        let rejected = shared.command(Command::Search {
            query: "synthetic".into(),
            cursor: 0,
        });
        assert!(!rejected.ok);
        assert!(rejected.message.contains("queue is full"));
        assert!(shared.state.lock().unwrap().pending.is_empty());
        assert!(!shared.queue(Command::RetryPeers).ok);
        assert!(matches!(rx.try_recv().unwrap(), Command::RetryPeers));
        assert!(shared.queue(Command::RetryPeers).ok);
        drop(rx);
        assert!(!shared.queue(Command::RetryPeers).ok);
    }

    #[cfg(unix)]
    #[test]
    fn slowly_dripped_control_requests_cannot_extend_the_read_deadline() {
        let (mut server, mut client) = UnixStream::pair().unwrap();
        client.write_all(b"{").unwrap();
        let writer = thread::spawn(move || {
            for _ in 0..50 {
                thread::sleep(Duration::from_millis(20));
                if client.write_all(b" ").is_err() {
                    break;
                }
            }
        });
        let started = Instant::now();
        assert!(read_bounded(&mut server, REQUEST_LIMIT, Duration::from_millis(100)).is_err());
        assert!(started.elapsed() < Duration::from_secs(1));
        drop(server);
        writer.join().unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn control_reads_accept_the_limit_and_reject_an_extra_byte() {
        for length in [32, 33] {
            let (mut server, mut client) = UnixStream::pair().unwrap();
            client.write_all(&vec![b' '; length]).unwrap();
            client.shutdown(std::net::Shutdown::Write).unwrap();
            let result = read_bounded(&mut server, 32, Duration::from_secs(1));
            assert_eq!(result.is_ok(), length == 32);
        }
    }

    #[cfg(unix)]
    #[test]
    fn nonreading_control_clients_cannot_extend_the_write_deadline() {
        let (mut server, _client) = UnixStream::pair().unwrap();
        let payload = vec![0; REPLY_LIMIT as usize];
        let started = Instant::now();
        assert!(write_bounded(&mut server, &payload, Duration::from_millis(100)).is_err());
        assert!(started.elapsed() < Duration::from_secs(1));
    }

    #[cfg(unix)]
    #[test]
    fn idle_control_clients_have_a_fixed_worker_limit() {
        let shared = Arc::new(shared());
        let mut workers = Vec::new();
        let mut clients = Vec::new();
        for _ in 0..CONTROL_CONNECTION_LIMIT {
            let (server, client) = UnixStream::pair().unwrap();
            dispatch_control_connection(server, shared.clone(), &mut workers).unwrap();
            clients.push(client);
        }
        let (server, _client) = UnixStream::pair().unwrap();
        assert_eq!(
            dispatch_control_connection(server, shared, &mut workers)
                .unwrap_err()
                .kind(),
            std::io::ErrorKind::WouldBlock
        );
        assert_eq!(workers.len(), CONTROL_CONNECTION_LIMIT);
        drop(clients);
        for worker in workers.drain(..) {
            worker.join().unwrap();
        }
        workers.push(thread::spawn(|| {}));
        let until = Instant::now() + Duration::from_secs(2);
        while !workers[0].is_finished() {
            assert!(Instant::now() < until, "control worker did not finish");
            thread::yield_now();
        }
        reap_control_workers(&mut workers);
        assert!(workers.is_empty());
    }

    #[test]
    fn commands_fail_while_reconnecting_without_queuing() {
        let shared = shared();
        assert!(
            !shared
                .handle(Request::Join {
                    name: "Example".into(),
                    password: None
                })
                .ok
        );
        assert!(shared.state.lock().unwrap().pending.is_empty());
    }
    #[test]
    fn network_names_must_resolve_unambiguously() {
        let shared = shared();
        {
            let mut state = shared.state.lock().unwrap();
            state.phase = "connected";
            state.snapshot = Some(Arc::new(Snapshot {
                vip: Some(Ipv4Addr::new(26, 0, 0, 5)),
                networks: vec![
                    crate::protocol::Network {
                        name: "Same".into(),
                        network_id: "a".into(),
                    },
                    crate::protocol::Network {
                        name: "Same".into(),
                        network_id: "b".into(),
                    },
                ],
                ..Default::default()
            }));
        }
        assert!(shared.resolve_network("Same").is_err());
        assert_eq!(shared.resolve_network("a").unwrap(), "a");
    }
    #[test]
    fn joining_an_existing_network_is_idempotent() {
        let shared = shared();
        {
            let mut state = shared.state.lock().unwrap();
            state.phase = "connected";
            state.snapshot = Some(Arc::new(Snapshot {
                networks: vec![crate::protocol::Network {
                    name: "Friends".into(),
                    network_id: "a".into(),
                }],
                roles: BTreeMap::from([("a".into(), BTreeMap::from([(123, 2)]))]),
                ..Default::default()
            }));
        }
        let reply = shared.handle(Request::Join {
            name: "Friends".into(),
            password: None,
        });
        assert!(reply.ok);
        assert_eq!(reply.message, "Already joined");
        assert!(shared.state.lock().unwrap().pending.is_empty());
    }
    #[test]
    fn status_preserves_snapshot_json_and_previous_snapshot_ownership() {
        let shared = shared();
        let snapshot = Snapshot {
            vip: Some(Ipv4Addr::new(26, 0, 0, 5)),
            networks: vec![crate::protocol::Network {
                name: "Synthetic LAN".into(),
                network_id: "synthetic-id".into(),
            }],
            roles: BTreeMap::from([("synthetic-id".into(), BTreeMap::from([(123, 2)]))]),
            ..Default::default()
        };
        let expected = serde_json::to_value(&snapshot).unwrap();
        shared.update(Update::State(snapshot));
        let previous = shared.state.lock().unwrap().snapshot.clone().unwrap();
        let reply = shared.status();
        assert!(reply.ok);
        assert_eq!(reply.message, "connected");
        assert_eq!(reply.data["snapshot"], expected);
        assert_eq!(reply.data["rid"], 123);
        assert_eq!(reply.data["phase"], "connected");
        assert!(serde_json::from_value::<Snapshot>(reply.data["snapshot"].clone()).is_ok());
        shared.update(Update::State(Snapshot::default()));
        assert_eq!(serde_json::to_value(previous.as_ref()).unwrap(), expected);
        assert!(shared.status().data["snapshot"]["networks"]
            .as_array()
            .unwrap()
            .is_empty());
    }

    #[test]
    fn tagged_result_reaches_only_its_waiter() {
        let shared = shared();
        let (tx, rx) = mpsc::channel();
        shared.state.lock().unwrap().pending.insert(7, tx);
        shared.update(Update::CommandResult {
            id: 8,
            message: "other".into(),
            error: false,
            catalog: None,
        });
        assert!(rx.try_recv().is_err());
        shared.update(Update::CommandResult {
            id: 7,
            message: "done".into(),
            error: false,
            catalog: None,
        });
        assert_eq!(rx.recv().unwrap().message, "done");
    }
}
