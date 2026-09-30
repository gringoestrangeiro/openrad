//! Per-user CLI service and bounded local control protocol.
#![cfg(any(unix, windows))]
#[cfg(windows)]
#[path = "platform/windows_pipe.rs"]
mod windows_pipe;

use anyhow::{anyhow, bail, ensure, Context, Result};
use openrad::{
    diagnostics::Diagnostics,
    early_log::{self, Stage},
    network::{MemberAction, NetworkPassword, NetworkRequest},
    protocol::Identity,
    runtime::{self, Command, Snapshot, Update},
};
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
        mpsc::{self, Sender},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};

const REQUEST_LIMIT: u64 = 64 * 1024;
const REPLY_LIMIT: u64 = 8 * 1024 * 1024;
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
                base.join("openrad")
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
        openrad::output::secure_directory(&path)?;
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
    fn load_identity(&self) -> Result<Identity> {
        Identity::load(&self.identity()).with_context(|| {
            format!(
                "No saved identity at {}. Run `openrad init` first",
                self.identity().display()
            )
        })
    }
    fn load_modulus(&self) -> Result<Vec<u8>> {
        if self.modulus().exists() {
            let meta = fs::metadata(self.modulus())?;
            ensure!(
                (1..=4096).contains(&meta.len()),
                "public modulus size is invalid"
            );
            Ok(fs::read(self.modulus())?)
        } else {
            Ok(openrad::SERVER_MODULUS.to_vec())
        }
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
        password: Option<String>,
    },
    Create {
        name: String,
        password: String,
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
#[cfg(unix)]
fn read_bounded(stream: &mut UnixStream, limit: u64) -> Result<Vec<u8>> {
    let mut data = Vec::new();
    stream.take(limit + 1).read_to_end(&mut data)?;
    ensure!(
        data.len() as u64 <= limit,
        "local control message too large"
    );
    Ok(data)
}
#[cfg(unix)]
pub fn request(dir: &DataDir, command: &Request) -> Result<Reply> {
    let mut stream = UnixStream::connect(dir.socket())
        .context("Service is stopped. Run `openrad start` first")?;
    stream.set_read_timeout(Some(Duration::from_secs(35)))?;
    stream.set_write_timeout(Some(Duration::from_secs(5)))?;
    stream.write_all(&serde_json::to_vec(command)?)?;
    stream.shutdown(std::net::Shutdown::Write)?;
    let bytes = read_bounded(&mut stream, REPLY_LIMIT)?;
    ensure!(!bytes.is_empty(), "service closed the control connection");
    Ok(serde_json::from_slice(&bytes)?)
}

#[cfg(windows)]
pub fn request(dir: &DataDir, command: &Request) -> Result<Reply> {
    let mut stream = windows_pipe::Connection::connect(&dir.path)
        .context("Service is stopped. Run `openrad start` first")?;
    let payload = serde_json::to_vec(command)?;
    ensure!(
        payload.len() as u64 <= REQUEST_LIMIT,
        "local control message too large"
    );
    stream.write_message(&payload, Duration::from_secs(5))?;
    let bytes = stream.read_message(REPLY_LIMIT, Duration::from_secs(35))?;
    stream.acknowledge()?;
    Ok(serde_json::from_slice(&bytes)?)
}

struct State {
    phase: &'static str,
    error: Option<String>,
    snapshot: Option<Snapshot>,
    sender: Option<Sender<Command>>,
    session_stop: Option<Arc<AtomicBool>>,
    pending: BTreeMap<u64, Sender<Reply>>,
}
struct Shared {
    state: Mutex<State>,
    shutdown: AtomicBool,
    next_id: AtomicU64,
    rid: u64,
    node_name: String,
    disable_interface: bool,
}
impl Shared {
    fn new(id: &Identity, disable_interface: bool) -> Self {
        Self {
            state: Mutex::new(State {
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
            node_name: id.node_name.clone(),
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
            Update::State(snapshot) => {
                let mut state = self.state.lock().unwrap();
                state.phase = "connected";
                state.error = None;
                state.snapshot = Some(snapshot);
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
        let state = self.state.lock().unwrap();
        Reply::ok(
            state.phase,
            json!({
            "phase":state.phase,"rid":self.rid,"node_name":self.node_name,
            "interface_disabled":self.disable_interface,
                "error":state.error,"snapshot":state.snapshot,
            }),
        )
    }
    fn command(&self, command: Command) -> Reply {
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
        if sender
            .send(Command::Tagged {
                id,
                command: Box::new(command),
            })
            .is_err()
        {
            self.state.lock().unwrap().pending.remove(&id);
            return Reply::error("Service connection is unavailable");
        }
        match rx.recv_timeout(Duration::from_secs(27)) {
            Ok(reply) => reply,
            Err(_) => {
                self.state.lock().unwrap().pending.remove(&id);
                Reply::error("Network command timed out; check `openrad status` before retrying")
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
        match sender.and_then(|sender| sender.send(command).ok()) {
            Some(()) => Reply::ok("Requested", Value::Null),
            None => Reply::error("Service connection is unavailable"),
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
                Reply::ok(format!("{} networks", networks.len()), json!(networks))
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
                let password = password.map(NetworkPassword::new).transpose()?;
                self.command(Command::Network(NetworkRequest::Join { name, password }))
            }
            Request::Create { name, password } => {
                self.command(Command::Network(NetworkRequest::Create {
                    name,
                    password: NetworkPassword::new(password)?,
                }))
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
        let (tx, rx) = mpsc::channel();
        let session_stop = Arc::new(AtomicBool::new(false));
        {
            let mut state = shared.state.lock().unwrap();
            state.phase = "connecting";
            state.sender = Some(tx);
            state.session_stop = Some(session_stop.clone());
        }
        let shared_updates = shared.clone();
        let options = runtime::Options {
            helper: std::env::current_exe().ok(),
            disable_interface,
            diagnostics: diagnostics.clone(),
            ..Default::default()
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
        let delay = Duration::from_secs((1u64 << failures.min(6)).min(60));
        let until = Instant::now() + delay;
        while !shared.shutdown.load(Ordering::Relaxed) && Instant::now() < until {
            thread::sleep(Duration::from_millis(100));
        }
    }
    shared.disconnected("Service stopped".into());
}

#[cfg(unix)]
fn serve_connection(mut stream: UnixStream, shared: Arc<Shared>) -> Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(5)))?;
    stream.set_write_timeout(Some(Duration::from_secs(5)))?;
    let bytes = read_bounded(&mut stream, REQUEST_LIMIT)?;
    ensure!(!bytes.is_empty(), "empty control request");
    let command: Request = serde_json::from_slice(&bytes)?;
    let stopping = matches!(&command, Request::Stop);
    let reply = shared.handle(command);
    let payload = serde_json::to_vec(&reply)?;
    ensure!(
        payload.len() as u64 <= REPLY_LIMIT,
        "control reply too large"
    );
    stream.write_all(&payload)?;
    if stopping {
        shared.stop();
    }
    Ok(())
}

#[cfg(unix)]
pub fn run(dir: DataDir, disable_interface: bool) -> Result<()> {
    early_log::checkpoint(Stage::ServiceProfileLoading);
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
    let shared = Arc::new(Shared::new(&identity, disable_interface));
    let on_signal = shared.clone();
    ctrlc::set_handler(move || on_signal.stop())?;
    let worker_shared = shared.clone();
    let worker =
        thread::spawn(move || supervisor(worker_shared, identity, modulus, dir, disable_interface));
    while !shared.shutdown.load(Ordering::Relaxed) {
        match listener.accept() {
            Ok((stream, _)) => {
                let shared = shared.clone();
                thread::spawn(move || {
                    let _ = serve_connection(stream, shared);
                });
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
    let bytes = stream.read_message(REQUEST_LIMIT, Duration::from_secs(5))?;
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
    let identity = dir.load_identity()?;
    let modulus = dir.load_modulus()?;
    early_log::checkpoint(Stage::ServiceLock);
    let _lock = dir.lock()?;
    let mut listener = windows_pipe::Listener::bind(&dir.path)?;
    early_log::checkpoint(Stage::ServiceListening);
    let shared = Arc::new(Shared::new(&identity, disable_interface));
    let worker_shared = shared.clone();
    let worker =
        thread::spawn(move || supervisor(worker_shared, identity, modulus, dir, disable_interface));
    let result = (|| -> Result<()> {
        while !shared.shutdown.load(Ordering::Relaxed) {
            if let Some(stream) = listener.accept()? {
                let shared = shared.clone();
                thread::spawn(move || {
                    let _ = serve_connection(stream, shared);
                });
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
    early_log::checkpoint(Stage::ServiceStarting);
    dir.load_identity()?;
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
    let mut command = ProcessCommand::new(std::env::current_exe()?);
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
            state.snapshot = Some(Snapshot {
                vip: Some(Ipv4Addr::new(26, 0, 0, 5)),
                networks: vec![
                    openrad::protocol::Network {
                        name: "Same".into(),
                        network_id: "a".into(),
                    },
                    openrad::protocol::Network {
                        name: "Same".into(),
                        network_id: "b".into(),
                    },
                ],
                ..Default::default()
            });
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
            state.snapshot = Some(Snapshot {
                networks: vec![openrad::protocol::Network {
                    name: "Friends".into(),
                    network_id: "a".into(),
                }],
                roles: BTreeMap::from([("a".into(), BTreeMap::from([(123, 2)]))]),
                ..Default::default()
            });
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
