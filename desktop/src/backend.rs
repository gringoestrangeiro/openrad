use crate::storage::{self, Paths, Settings};
use anyhow::{ensure, Context, Result};
use openrad::{
    i18n::LanguagePreference,
    output::ReportDirectory,
    protocol::Identity,
    runtime,
    session::{ProvisionProgress, Session},
};
use std::{
    collections::VecDeque,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, Sender},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};

pub const MODULUS: &[u8] = openrad::SERVER_MODULUS;
#[derive(Clone, Debug, PartialEq)]
pub enum Phase {
    Loading,
    Disconnected,
    Provisioning,
    Resetting,
    Connecting,
    Connected,
    Disconnecting,
    Error,
}
impl Phase {
    pub fn label(&self) -> &'static str {
        match self {
            Self::Loading => "Loading",
            Self::Disconnected => "Disconnected",
            Self::Provisioning => "Provisioning",
            Self::Resetting => "Resetting",
            Self::Connecting => "Connecting",
            Self::Connected => "Connected",
            Self::Disconnecting => "Disconnecting",
            Self::Error => "Error",
        }
    }
}
#[derive(Clone)]
pub enum Notice {
    Phase(Phase, String),
    Identity { rid: u64, name: String },
    Settings(Settings),
    Engine(runtime::Update),
    Stopped,
    CloseBlocked(String),
    ReplacementPending(bool),
    Release(openrad::releases::Release),
    SearchFailed { query: String, message: String },
    NodeName(String),
    RelayPreference(bool),
}
pub enum Action {
    Connect,
    Disconnect,
    ResetIdentity,
    Save(Settings),
    Import(PathBuf),
    Engine(runtime::Command),
    Shutdown,
}
/// Coalesce consecutive state snapshots while preserving phase/operation order.
#[derive(Clone, Default)]
pub struct Notices(Arc<Mutex<VecDeque<Notice>>>);
impl Notices {
    pub(crate) fn send(&self, notice: Notice) {
        let mut queue = self.0.lock().unwrap();
        if matches!(notice, Notice::Engine(runtime::Update::State(_)))
            && matches!(
                queue.back(),
                Some(Notice::Engine(runtime::Update::State(_)))
            )
        {
            queue.pop_back();
        }
        queue.push_back(notice);
    }
    pub fn try_iter(&self) -> std::collections::vec_deque::IntoIter<Notice> {
        std::mem::take(&mut *self.0.lock().unwrap()).into_iter()
    }
}
pub struct Backend {
    sender: Sender<Action>,
    pub notices: Notices,
    cancel: Cancellation,
    update_stop: Arc<AtomicBool>,
}
#[derive(Clone, Default)]
struct Cancellation {
    connection: Arc<AtomicBool>,
    replacement: Arc<AtomicBool>,
}
impl Backend {
    #[cfg(test)]
    pub fn fixture() -> Self {
        Self::recording_fixture().0
    }
    #[cfg(test)]
    pub fn recording_fixture() -> (Self, Receiver<Action>) {
        let (sender, receiver) = mpsc::channel();
        (
            Self {
                sender,
                notices: Notices::default(),
                cancel: Cancellation::default(),
                update_stop: Arc::new(AtomicBool::new(false)),
            },
            receiver,
        )
    }
    pub fn spawn(
        paths: Paths,
        import: Option<PathBuf>,
        language_override: Option<LanguagePreference>,
        options: runtime::Options,
        wake: impl Fn() + Send + Sync + 'static,
    ) -> Self {
        let (sender, actions) = mpsc::channel();
        let notices = Notices::default();
        let updates = notices.clone();
        let cancel = Cancellation::default();
        let worker_cancel = cancel.clone();
        let release_notices = notices.clone();
        let update_stop = Arc::new(AtomicBool::new(false));
        let update_cancel = update_stop.clone();
        let wake = Arc::new(wake);
        let release_wake = wake.clone();
        thread::spawn(move || {
            while !update_cancel.load(Ordering::Relaxed) {
                if let Ok(Some(release)) = openrad::releases::latest(env!("CARGO_PKG_VERSION")) {
                    release_notices.send(Notice::Release(release));
                    release_wake();
                }
                let until = Instant::now() + openrad::releases::CHECK_INTERVAL;
                while !update_cancel.load(Ordering::Relaxed) && Instant::now() < until {
                    thread::sleep(Duration::from_millis(250));
                }
            }
        });
        thread::spawn(move || {
            let report = {
                let wake = wake.clone();
                move |n| {
                    updates.send(n);
                    wake();
                }
            };
            manager(
                paths,
                import,
                language_override,
                options,
                actions,
                worker_cancel,
                Arc::new(report),
            );
        });
        Self {
            sender,
            notices,
            cancel,
            update_stop,
        }
    }
    pub fn send(&self, action: Action) {
        match action {
            Action::Connect => self.cancel.connection.store(false, Ordering::Relaxed),
            Action::ResetIdentity => {
                self.cancel.connection.store(true, Ordering::Relaxed);
                self.cancel.replacement.store(false, Ordering::Relaxed);
            }
            Action::Disconnect | Action::Shutdown => {
                self.cancel.connection.store(true, Ordering::Relaxed);
                self.cancel.replacement.store(true, Ordering::Relaxed);
            }
            _ => {}
        }
        let _ = self.sender.send(action);
    }
}
impl Drop for Backend {
    fn drop(&mut self) {
        self.update_stop.store(true, Ordering::Relaxed);
        self.send(Action::Shutdown);
    }
}
type Report = Arc<dyn Fn(Notice) + Send + Sync>;
#[cfg(test)]
fn reconnect_delay(settings: &Settings, attempt: u32) -> Duration {
    Duration::from_secs(
        settings
            .reconnect_base_delay_seconds
            .saturating_mul(1u64 << attempt.saturating_sub(1).min(9))
            .min(300),
    )
}
fn manager(
    paths: Paths,
    import: Option<PathBuf>,
    language_override: Option<LanguagePreference>,
    mut options: runtime::Options,
    actions: Receiver<Action>,
    cancel: Cancellation,
    report: Report,
) {
    use openrad::daemon::{self, DataDir, Request};
    let dir = match DataDir::open(Some(paths.directory.clone())) {
        Ok(dir) => dir,
        Err(error) => {
            report(Notice::Phase(Phase::Error, error.to_string()));
            report(Notice::Stopped);
            return;
        }
    };
    crate::startup_log::checkpoint(crate::startup_log::Stage::BackendSettings);
    let mut settings = match paths.settings() {
        Ok(settings) => settings,
        Err(_) => {
            report(Notice::Phase(
                Phase::Error,
                "Settings could not be loaded. Repair settings.json and restart.".into(),
            ));
            report(Notice::Stopped);
            return;
        }
    };
    if let Some(language) = language_override {
        settings.language = language;
    }
    if dir.path.join("service.json").exists() {
        match dir.preferences() {
            Ok(preferences) => {
                settings.force_relay = preferences.force_relay;
                settings.auto_reconnect = preferences.auto_reconnect;
                settings.reconnect_attempts = preferences.reconnect_attempts;
                settings.reconnect_base_delay_seconds = preferences.reconnect_base_delay_seconds;
            }
            Err(error) => {
                report(Notice::Phase(Phase::Error, error.to_string()));
                report(Notice::Stopped);
                return;
            }
        }
    }
    if options.traffic_peers.is_some() {
        if let Ok(mut reply) = service_status(&dir) {
            let result = (|| -> Result<()> {
                let mut preferences: daemon::ServicePreferences =
                    serde_json::from_value(take_reply_field(&mut reply.data, "preferences"))?;
                preferences.traffic_peers = options.traffic_peers.clone();
                let reply = daemon::request(&dir, &Request::Configure { preferences })?;
                ensure!(reply.ok, "{}", reply.message);
                Ok(())
            })();
            if let Err(error) = result {
                report(Notice::Phase(Phase::Error, error.to_string()));
                report(Notice::Stopped);
                return;
            }
        }
    }
    let mut identity = None;
    let mut replacement = storage::Replacement::default();
    let mut service_running = service_status(&dir).is_ok();
    let loaded = (|| -> Result<()> {
        crate::startup_log::checkpoint(crate::startup_log::Stage::BackendVault);
        crate::startup_log::checkpoint(crate::startup_log::Stage::BackendIdentity);
        if dir.identity().exists() {
            identity = Some(dir.load_identity()?);
        } else if !service_running {
            identity = storage::load(&paths.entry()?)?;
        }
        if let Some(path) = import {
            let imported = Identity::load(&path)?;
            ensure!(!service_running, "Disconnect before importing an identity");
            ensure!(identity.as_ref().is_none_or(|id| id.rid == imported.rid),
                "This profile already has an identity; use another data directory to import a different one");
            if dir.identity().exists() {
                dir.save_identity(&imported)?;
            } else {
                storage::save(&paths.entry()?, &imported)?;
            }
            identity = Some(imported);
        }
        if let Some(id) = &identity {
            settings.node_name = id.node_name.clone();
            report(Notice::Identity {
                rid: id.rid,
                name: id.node_name.clone(),
            });
        }
        Ok(())
    })();
    crate::startup_log::checkpoint(crate::startup_log::Stage::BackendIdentityReady);
    crate::startup_log::checkpoint(crate::startup_log::Stage::BackendReady);
    let mut persisted = identity.is_some();
    report(Notice::Settings(settings.clone()));
    if let Err(error) = &loaded {
        report(Notice::Phase(Phase::Error, error.to_string()));
    } else if !service_running {
        report(Notice::Phase(
            Phase::Disconnected,
            "Ready when you are".into(),
        ));
    }
    let executable = options.helper.take().unwrap_or_else(|| {
        std::env::current_exe()
            .unwrap_or_default()
            .with_file_name(if cfg!(windows) {
                "openrad.exe"
            } else {
                "openrad"
            })
    });
    let configure = |settings: &Settings| daemon::ServicePreferences {
        force_relay: settings.force_relay,
        auto_reconnect: settings.auto_reconnect,
        reconnect_attempts: settings.reconnect_attempts,
        reconnect_base_delay_seconds: settings.reconnect_base_delay_seconds,
        traffic_peers: options.traffic_peers.clone(),
    };
    let operation = |result: Result<()>| {
        if let Err(error) = result {
            report(Notice::Engine(runtime::Update::Operation {
                message: format!("{error:#}"),
                error: true,
            }));
        }
    };
    let mut connect = settings.auto_connect && loaded.is_ok() && !service_running;
    let mut next_status = Instant::now();
    let mut last_phase = None;
    let mut last_identity: Option<(u64, String)> = None;
    let mut reset_failed = false;
    loop {
        if connect {
            connect = false;
            let result = (|| -> Result<()> {
                if cancel.connection.load(Ordering::Relaxed) {
                    return Ok(());
                }
                // Another frontend may have started while the GUI was loading.
                if let Ok(reply) = service_status(&dir) {
                    if reply.data["phase"] == "error" {
                        let reply = daemon::request(&dir, &Request::Reconnect)?;
                        ensure!(reply.ok, "{}", reply.message);
                    }
                    return Ok(());
                }
                let profile_lock = dir.try_profile_lock()?;
                if dir.identity().exists() {
                    identity = Some(dir.load_identity()?);
                } else if identity.is_none() {
                    identity = storage::load(&paths.entry()?)?;
                }
                if identity.is_none() {
                    report(Notice::Phase(
                        Phase::Provisioning,
                        "Preparing your private device identity…".into(),
                    ));
                    let vault = paths.entry()?;
                    identity = Some(storage::provision_once(&vault, |commit| {
                        Session::provision_controlled(
                            MODULUS,
                            &settings.node_name,
                            openrad::DEFAULT_BOOTSTRAP_HOST,
                            &ReportDirectory::disabled(),
                            commit,
                            Some(cancel.connection.clone()),
                            &mut |progress| {
                                provision_progress(&report, Phase::Provisioning, progress)
                            },
                        )
                    })?);
                    persisted = false;
                }
                if !persisted {
                    if dir.identity().exists() {
                        persisted = true;
                    } else {
                        storage::save(&paths.entry()?, identity.as_ref().unwrap())?;
                        persisted = true;
                    }
                }
                if cancel.connection.load(Ordering::Relaxed) {
                    return Ok(());
                }
                report(Notice::Phase(
                    Phase::Connecting,
                    "Authenticating your saved identity…".into(),
                ));
                dir.save_preferences(&configure(&settings))?;
                drop(profile_lock);
                daemon::spawn_with_executable(&dir, options.disable_interface, &executable)?;
                service_running = true;
                Ok(())
            })();
            if let Err(error) = result {
                report(Notice::Phase(Phase::Error, format!("{error:#}")));
            }
            next_status = Instant::now();
        }
        if !reset_failed && Instant::now() >= next_status {
            next_status = Instant::now() + Duration::from_millis(250);
            match service_status(&dir) {
                Ok(mut reply) => {
                    service_running = true;
                    let name = reply.data["node_name"]
                        .as_str()
                        .unwrap_or_default()
                        .to_owned();
                    if let Some(rid) = reply.data["rid"].as_u64() {
                        let current = (rid, name.clone());
                        if last_identity.as_ref() != Some(&current) {
                            last_identity = Some(current);
                            settings.node_name = name.clone();
                            report(Notice::NodeName(name.clone()));
                            report(Notice::Identity { rid, name });
                        }
                    }
                    if let Ok(preferences) = serde_json::from_value::<daemon::ServicePreferences>(
                        take_reply_field(&mut reply.data, "preferences"),
                    ) {
                        if settings.force_relay != preferences.force_relay {
                            settings.force_relay = preferences.force_relay;
                            report(Notice::RelayPreference(preferences.force_relay));
                        }
                    }
                    let phase = match reply.data["phase"].as_str() {
                        Some("connected") => Phase::Connected,
                        Some("error") => Phase::Error,
                        _ => Phase::Connecting,
                    };
                    let message = reply.data["error"]
                        .as_str()
                        .unwrap_or("Using the shared VPN session")
                        .to_owned();
                    let key = (phase.clone(), message.clone());
                    if last_phase.as_ref() != Some(&key) {
                        last_phase = Some(key);
                        report(Notice::Phase(phase, message));
                    }
                    if !reply.data["snapshot"].is_null() {
                        match serde_json::from_value(take_reply_field(&mut reply.data, "snapshot"))
                        {
                            Ok(snapshot) if reply.data["phase"] == "connected" => {
                                report(Notice::Engine(runtime::Update::State(snapshot)))
                            }
                            Ok(_) => {}
                            Err(error) => operation(Err(error.into())),
                        }
                    }
                }
                Err(_) if service_running => {
                    service_running = false;
                    last_phase = None;
                    report(Notice::Phase(
                        Phase::Disconnected,
                        "Disconnected · interface removed".into(),
                    ));
                    report(Notice::Engine(runtime::Update::State(
                        runtime::Snapshot::default(),
                    )));
                    // A disconnected snapshot must not mark the UI connected.
                    report(Notice::Phase(
                        Phase::Disconnected,
                        "Ready when you are".into(),
                    ));
                }
                _ => {}
            }
        }
        match actions.recv_timeout(Duration::from_millis(50)) {
            Ok(Action::Connect) if !replacement.is_pending() => {
                reset_failed = false;
                connect = true;
                next_status = Instant::now();
            }
            Ok(Action::Disconnect) => {
                reset_failed = false;
                connect = false;
                operation(stop_service(&dir));
                next_status = Instant::now();
            }
            Ok(Action::Shutdown) | Err(mpsc::RecvTimeoutError::Disconnected) => {
                // Closing a frontend leaves the shared service available to CLI
                // clients. Disconnect/Stop are explicit, shared session actions.
                if replacement.is_pending() {
                    let result = if dir.identity().exists() {
                        replacement.commit(&ProfileVault(dir.clone()), &mut identity)
                    } else {
                        paths
                            .entry()
                            .and_then(|vault| replacement.commit(&vault, &mut identity))
                    };
                    if let Err(error) = result {
                        report(Notice::CloseBlocked(error.to_string()));
                        continue;
                    }
                }
                if let Some(id) = identity.as_ref().filter(|_| !persisted) {
                    if let Err(error) = paths.entry().and_then(|vault| storage::save(&vault, id)) {
                        report(Notice::CloseBlocked(error.to_string()));
                        continue;
                    }
                }
                report(Notice::Stopped);
                break;
            }
            Ok(Action::ResetIdentity) => {
                connect = false;
                reset_failed = false;
                last_phase = None;
                let started = Instant::now();
                crate::startup_log::event(format_args!(
                    "Identity reset requested; pending_save={}",
                    replacement.is_pending()
                ));
                report(Notice::Phase(
                    Phase::Resetting,
                    "Stopping the VPN service before resetting identity…".into(),
                ));
                let result = (|| -> Result<()> {
                    ensure!(
                        !cancel.replacement.load(Ordering::Relaxed),
                        "Identity reset cancelled"
                    );
                    let _profile_lock = dir.try_profile_lock()?;
                    stop_service(&dir)?;
                    service_running = false;
                    let _service_lock = dir.stopped_lock()?;
                    if !replacement.is_pending() {
                        identity = Some(
                            dir.load_identity()
                                .context("Loading the saved identity before reset")?,
                        );
                    }
                    report(Notice::Phase(
                        Phase::Resetting,
                        "Creating your replacement identity…".into(),
                    ));
                    replacement.prepare(|| {
                        Session::provision_controlled(
                            MODULUS,
                            &settings.node_name,
                            openrad::DEFAULT_BOOTSTRAP_HOST,
                            &ReportDirectory::disabled(),
                            &mut || Ok(()),
                            Some(cancel.replacement.clone()),
                            &mut |progress| provision_progress(&report, Phase::Resetting, progress),
                        )
                    })?;
                    report(Notice::Phase(
                        Phase::Resetting,
                        "Saving your replacement identity securely…".into(),
                    ));
                    crate::startup_log::event(format_args!(
                        "Identity reset saving replacement; elapsed_ms={}",
                        started.elapsed().as_millis()
                    ));
                    if dir.identity().exists() {
                        commit_replacement(
                            &mut replacement,
                            &ProfileVault(dir.clone()),
                            &mut identity,
                            &report,
                        )?;
                    } else {
                        commit_replacement(
                            &mut replacement,
                            &paths.entry()?,
                            &mut identity,
                            &report,
                        )?;
                    }
                    let id = identity.as_ref().unwrap();
                    persisted = true;
                    report(Notice::Identity {
                        rid: id.rid,
                        name: id.node_name.clone(),
                    });
                    report(Notice::Engine(runtime::Update::State(
                        runtime::Snapshot::default(),
                    )));
                    report(Notice::Phase(
                        Phase::Disconnected,
                        "Identity reset and saved securely. Connect to use your new device.".into(),
                    ));
                    Ok(())
                })();
                report(Notice::ReplacementPending(replacement.is_pending()));
                if let Err(error) = &result {
                    // Status from the old service must not hide a failed reset
                    // behind another Connecting snapshot. Explicit actions
                    // resume service polling after the user has seen the error.
                    reset_failed = true;
                    let message = format!("Identity reset failed: {error:#}");
                    crate::startup_log::event(format_args!(
                        "{message}; elapsed_ms={}; pending_save={}",
                        started.elapsed().as_millis(),
                        replacement.is_pending()
                    ));
                    report(Notice::Phase(Phase::Error, message));
                } else {
                    crate::startup_log::event(format_args!(
                        "Identity reset completed; elapsed_ms={}",
                        started.elapsed().as_millis()
                    ));
                }
                operation(result);
                next_status = Instant::now();
            }
            Ok(Action::Save(next)) => {
                let result = (|| -> Result<()> {
                    openrad::protocol::validate_node_name(&next.node_name)?;
                    if service_running {
                        let reply = daemon::request(
                            &dir,
                            &Request::Configure {
                                preferences: configure(&next),
                            },
                        )?;
                        ensure!(reply.ok, "{}", reply.message);
                        if next.node_name != settings.node_name {
                            let reply = daemon::request(
                                &dir,
                                &Request::Rename {
                                    node_name: next.node_name.clone(),
                                },
                            )?;
                            ensure!(reply.ok, "{}", reply.message);
                        }
                    } else {
                        dir.save_preferences(&configure(&next))?;
                        if (identity.is_some() || last_identity.is_some())
                            && next.node_name != settings.node_name
                        {
                            let mut renamed = dir.load_identity()?;
                            renamed.node_name = next.node_name.clone();
                            dir.save_identity(&renamed)?;
                            identity = Some(renamed);
                        }
                    }
                    paths.save_settings(&next)?;
                    settings = next.clone();
                    report(Notice::Settings(next));
                    if let Some(id) = &mut identity {
                        id.node_name = settings.node_name.clone();
                        report(Notice::Identity {
                            rid: id.rid,
                            name: id.node_name.clone(),
                        });
                    }
                    report(Notice::Engine(runtime::Update::Operation {
                        message: "Settings saved".into(),
                        error: false,
                    }));
                    Ok(())
                })();
                operation(result);
                next_status = Instant::now();
            }
            Ok(Action::Import(path)) if !service_running && !replacement.is_pending() => {
                let result = (|| -> Result<()> {
                    let imported = Identity::load(&path)?;
                    ensure!(
                        identity.as_ref().is_none_or(|id| id.rid == imported.rid),
                        "An identity is already saved in this profile"
                    );
                    if dir.identity().exists() {
                        dir.save_identity(&imported)?;
                    } else {
                        storage::save(&paths.entry()?, &imported)?;
                    }
                    settings.node_name = imported.node_name.clone();
                    report(Notice::NodeName(imported.node_name.clone()));
                    report(Notice::Identity {
                        rid: imported.rid,
                        name: imported.node_name.clone(),
                    });
                    identity = Some(imported);
                    persisted = true;
                    report(Notice::Phase(
                        Phase::Disconnected,
                        "Identity imported securely. Ready to connect.".into(),
                    ));
                    Ok(())
                })();
                operation(result);
            }
            Ok(Action::Engine(command)) => {
                let (client_id, command) = match command {
                    runtime::Command::Tagged { id, command } => (Some(id), *command),
                    command => (None, command),
                };
                let (dir, publish) = (dir.clone(), report.clone());
                thread::spawn(move || {
                    let search = match &command {
                        runtime::Command::Search { query, cursor } => {
                            Some((query.clone(), *cursor))
                        }
                        _ => None,
                    };
                    let ping = match &command {
                        runtime::Command::Ping { peer } => Some(*peer),
                        _ => None,
                    };
                    let result = daemon::Request::from_runtime(command)
                        .and_then(|request| daemon::request(&dir, &request));
                    if let Some(peer) = ping {
                        let (rtt_ms, error) = match result {
                            Ok(reply) if reply.ok => (reply.data["rtt_ms"].as_f64(), None),
                            Ok(reply) => (None, Some(reply.message)),
                            Err(error) => (None, Some(error.to_string())),
                        };
                        publish(Notice::Engine(runtime::Update::Ping {
                            peer,
                            id: client_id,
                            rtt_ms,
                            error,
                        }));
                    } else if let Some((query, cursor)) = search {
                        match result.and_then(|mut reply| {
                            ensure!(reply.ok, "{}", reply.message);
                            Ok((
                                serde_json::from_value(take_reply_field(
                                    &mut reply.data,
                                    "networks",
                                ))?,
                                reply.data["cursor"].as_u64().unwrap_or(0),
                            ))
                        }) {
                            Ok((networks, next)) => {
                                publish(Notice::Engine(runtime::Update::Catalog {
                                    query,
                                    networks,
                                    cursor: next,
                                    append: cursor != 0,
                                }))
                            }
                            Err(error) => publish(Notice::SearchFailed {
                                query,
                                message: error.to_string(),
                            }),
                        }
                    } else {
                        let (message, error) = match result {
                            Ok(reply) => (reply.message, !reply.ok),
                            Err(error) => (error.to_string(), true),
                        };
                        publish(Notice::Engine(match client_id {
                            Some(id) => runtime::Update::CommandResult {
                                id,
                                message,
                                error,
                                catalog: None,
                            },
                            None => runtime::Update::Operation { message, error },
                        }));
                    }
                });
            }
            _ => {}
        }
    }
}

// Move owned JSON subtrees into deserialization. Missing fields retain the
// read-only indexing behavior (null), including malformed non-object replies.
fn take_reply_field(data: &mut serde_json::Value, field: &str) -> serde_json::Value {
    data.as_object_mut()
        .and_then(|object| object.remove(field))
        .unwrap_or(serde_json::Value::Null)
}

fn service_status(dir: &openrad::daemon::DataDir) -> Result<openrad::daemon::Reply> {
    openrad::daemon::request_with_timeout(
        dir,
        &openrad::daemon::Request::Status,
        Duration::from_secs(1),
    )
}

fn commit_replacement(
    replacement: &mut storage::Replacement,
    vault: &impl storage::Vault,
    identity: &mut Option<Identity>,
    report: &Report,
) -> Result<()> {
    for attempt in 1..=3 {
        match replacement.commit(vault, identity) {
            Ok(()) => return Ok(()),
            Err(error) if attempt < 3 => {
                crate::startup_log::event(format_args!("Identity save retry; attempt={attempt}; error={error:#}"));
                report(Notice::Phase(Phase::Resetting,
                    format!("Saving new identity failed (attempt {attempt}/3): {error:#}. Retrying…")));
                thread::sleep(Duration::from_millis(250 * attempt));
            }
            Err(error) => return Err(error).context("Could not save the new identity after 3 attempts. Keep the window open and retry saving."),
        }
    }
    unreachable!()
}

fn stop_service(dir: &openrad::daemon::DataDir) -> Result<()> {
    use openrad::daemon::{request_with_timeout, Request};
    if !dir.endpoint_exists() {
        return Ok(());
    }
    crate::startup_log::event(format_args!("Stopping VPN service; timeout_seconds=10"));
    let until = Instant::now() + Duration::from_secs(10);
    let mut retry_at = Instant::now();
    let mut attempts = 0;
    let mut last_error = None;
    while dir.endpoint_exists() && Instant::now() < until {
        if Instant::now() >= retry_at {
            attempts += 1;
            let result = request_with_timeout(
                dir,
                &Request::Stop,
                until
                    .saturating_duration_since(Instant::now())
                    .min(Duration::from_secs(1)),
            )
            .and_then(|reply| {
                ensure!(reply.ok, "{}", reply.message);
                Ok(())
            });
            if let Err(error) = result {
                crate::startup_log::event(format_args!(
                    "VPN stop request failed; attempt={attempts}; error={error:#}"
                ));
                last_error = Some(error);
                let unavailable = last_error.as_ref().unwrap().chain().any(|error| {
                    error.downcast_ref::<std::io::Error>().is_some_and(|error| {
                        matches!(
                            error.kind(),
                            std::io::ErrorKind::ConnectionRefused | std::io::ErrorKind::NotFound
                        )
                    })
                });
                if unavailable && dir.cleanup_stopped_endpoint()? {
                    crate::startup_log::event(format_args!("Recovered stale VPN service endpoint"));
                    return Ok(());
                }
            }
            retry_at = Instant::now() + Duration::from_millis(250);
        }
        thread::sleep(Duration::from_millis(50));
    }
    if dir.endpoint_exists() {
        return Err(last_error.unwrap_or_else(|| anyhow::anyhow!("VPN service is still stopping")))
            .context("VPN service did not stop within 10 seconds. Try again shortly.");
    }
    crate::startup_log::event(format_args!("VPN service stopped; attempts={attempts}"));
    Ok(())
}

fn provision_progress(report: &Report, phase: Phase, progress: ProvisionProgress) {
    let message = match progress {
        ProvisionProgress::Authenticating { endpoint, attempt } => {
            format!("Connecting to identity server {endpoint} (attempt {attempt}/3)…")
        }
        ProvisionProgress::Retry {
            attempt,
            delay_secs,
            error,
        } => format!(
            "Identity connection attempt {attempt}/3 failed: {error}. Retrying in {delay_secs} s…"
        ),
        ProvisionProgress::Registering => "Registering your new device identity…".into(),
        ProvisionProgress::Redirect { endpoint } => {
            format!("Identity server redirected registration to {endpoint}…")
        }
        ProvisionProgress::ReceivingIdentity => "Receiving your new device identity…".into(),
        ProvisionProgress::Complete => "New device identity received; preparing to save…".into(),
    };
    report(Notice::Phase(phase, message));
}

struct ProfileVault(openrad::daemon::DataDir);
impl storage::Vault for ProfileVault {
    fn get(&self) -> Result<Option<zeroize::Zeroizing<Vec<u8>>>> {
        Ok(Some(zeroize::Zeroizing::new(serde_json::to_vec(
            &self.0.load_identity()?,
        )?)))
    }
    fn set(&self, secret: &[u8]) -> Result<()> {
        self.0.save_identity(&Identity::from_secret(secret)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reply_fields_move_without_losing_other_status_fields() {
        let mut data = serde_json::json!({
            "phase": "connected",
            "snapshot": runtime::Snapshot::default(),
            "cursor": 9,
        });
        let _: runtime::Snapshot =
            serde_json::from_value(take_reply_field(&mut data, "snapshot")).unwrap();
        assert_eq!(data["phase"], "connected");
        assert_eq!(data["cursor"], 9);
        assert!(take_reply_field(&mut data, "missing").is_null());
        for mut malformed in [serde_json::Value::Null, serde_json::json!([1, 2])] {
            assert!(take_reply_field(&mut malformed, "snapshot").is_null());
        }
    }

    #[test]
    fn reset_cancels_queued_connections_and_later_shutdown_cancels_reset() {
        let (backend, actions) = Backend::recording_fixture();
        backend.send(Action::Disconnect);
        backend.send(Action::Connect);
        backend.send(Action::ResetIdentity);
        assert!(backend.cancel.connection.load(Ordering::Relaxed));
        assert!(!backend.cancel.replacement.load(Ordering::Relaxed));
        assert!(matches!(actions.recv().unwrap(), Action::Disconnect));
        assert!(matches!(actions.recv().unwrap(), Action::Connect));
        assert!(matches!(actions.recv().unwrap(), Action::ResetIdentity));
        backend.send(Action::Shutdown);
        assert!(backend.cancel.replacement.load(Ordering::Relaxed));
    }

    #[test]
    fn replacement_save_retries_keep_the_issued_identity() {
        use std::cell::{Cell, RefCell};
        struct Vault {
            attempts: Cell<u32>,
            secret: RefCell<Option<Vec<u8>>>,
        }
        impl storage::Vault for Vault {
            fn get(&self) -> Result<Option<zeroize::Zeroizing<Vec<u8>>>> {
                Ok(self.secret.borrow().clone().map(zeroize::Zeroizing::new))
            }
            fn set(&self, secret: &[u8]) -> Result<()> {
                let attempt = self.attempts.get() + 1;
                self.attempts.set(attempt);
                ensure!(attempt >= 3, "temporarily locked");
                *self.secret.borrow_mut() = Some(secret.to_vec());
                Ok(())
            }
        }
        let vault = Vault {
            attempts: Cell::new(0),
            secret: RefCell::new(None),
        };
        let next = Identity::from_secret(br#"{"format":"openrad-identity-v1","rid":456,"vip":"26.0.0.6","node_name":"synthetic","address":"00000000000000000000000000000000","credential":"010203040506","server_address":"192.0.2.1"}"#).unwrap();
        let mut replacement = storage::Replacement::default();
        replacement.prepare(|| Ok(next)).unwrap();
        let mut current = None;
        let report: Report = Arc::new(|_| {});
        commit_replacement(&mut replacement, &vault, &mut current, &report).unwrap();
        assert_eq!(vault.attempts.get(), 3);
        assert_eq!(current.unwrap().rid, 456);
        assert_eq!(storage::load(&vault).unwrap().unwrap().rid, 456);
        assert!(!replacement.is_pending());
    }

    #[test]
    fn snapshots_stay_bounded_without_crossing_phase_or_operation_events() {
        let notices = Notices::default();
        let state = |n| {
            Notice::Engine(runtime::Update::State(runtime::Snapshot {
                elapsed_secs: n,
                ..Default::default()
            }))
        };
        for n in 0..10_000 {
            notices.send(state(n));
        }
        notices.send(Notice::Engine(runtime::Update::Operation {
            message: "keep this result".into(),
            error: false,
        }));
        for n in 10_000..20_000 {
            notices.send(state(n));
        }
        notices.send(Notice::Phase(Phase::Disconnected, "finished".into()));
        let mut queued = notices.try_iter();
        assert_eq!(queued.len(), 4);
        assert!(
            matches!(queued.next(), Some(Notice::Engine(runtime::Update::State(s))) if s.elapsed_secs == 9999)
        );
        assert!(
            matches!(queued.next(), Some(Notice::Engine(runtime::Update::Operation { message, .. })) if message == "keep this result")
        );
        assert!(
            matches!(queued.next(), Some(Notice::Engine(runtime::Update::State(s))) if s.elapsed_secs == 19999)
        );
        assert!(matches!(
            queued.next(),
            Some(Notice::Phase(Phase::Disconnected, _))
        ));
        assert_eq!(notices.try_iter().count(), 0);
    }

    #[test]
    fn configurable_reconnect_delay_doubles_and_caps() {
        let settings = Settings::default();
        assert_eq!(reconnect_delay(&settings, 1), Duration::from_secs(2));
        assert_eq!(reconnect_delay(&settings, 2), Duration::from_secs(4));
        assert_eq!(reconnect_delay(&settings, 10), Duration::from_secs(300));
    }
    #[cfg(unix)]
    #[test]
    fn failed_reset_cancels_connect_and_cannot_be_hidden_by_old_service_status() {
        use openrad::daemon::{DataDir, Reply, Request};
        use std::{
            io::{Read, Write},
            os::unix::net::UnixListener,
        };
        for running in [false, true] {
            let directory = std::env::temp_dir().join(format!(
                "openrad-reset-{}-{}",
                std::process::id(),
                rand_suffix()
            ));
            let paths = Paths::new(Some(directory.clone())).unwrap();
            paths
                .save_settings(&Settings {
                    auto_connect: false,
                    ..Settings::default()
                })
                .unwrap();
            let dir = DataDir::open(Some(directory.clone())).unwrap();
            let old = Identity::from_secret(br#"{"format":"openrad-identity-v1","rid":123,"vip":"26.0.0.5","node_name":"synthetic","address":"00000000000000000000000000000000","credential":"010203040506","server_address":"192.0.2.1"}"#).unwrap();
            old.save(&ReportDirectory::new(&directory.join("profile")).unwrap())
                .unwrap();
            let saved = std::fs::read(dir.identity()).unwrap();
            let lock = dir.try_profile_lock().unwrap();
            let stop_server = Arc::new(AtomicBool::new(false));
            let server = running.then(|| {
                let listener = UnixListener::bind(dir.socket()).unwrap();
                listener.set_nonblocking(true).unwrap();
                let stop = stop_server.clone();
                thread::spawn(move || {
                    while !stop.load(Ordering::Relaxed) {
                        match listener.accept() {
                            Ok((mut stream, _)) => {
                                stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
                                let mut bytes = Vec::new();
                                stream.read_to_end(&mut bytes).unwrap();
                                assert!(matches!(serde_json::from_slice::<Request>(&bytes).unwrap(), Request::Status | Request::Stop));
                                let reply = Reply { ok: true, message: "connecting".into(), data: serde_json::json!({"phase":"connecting", "rid":123, "node_name":"synthetic"}) };
                                stream.write_all(&serde_json::to_vec(&reply).unwrap()).unwrap();
                            }
                            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => thread::sleep(Duration::from_millis(5)),
                            Err(error) => panic!("{error}"),
                        }
                    }
                })
            });
            let (backend, actions) = Backend::recording_fixture();
            let cancel = backend.cancel.clone();
            let (publish, notices) = mpsc::channel();
            // With no service this exactly exercises Disconnect -> queued
            // Connect -> Reset. An old service is left running in the second
            // case so its Connecting status must not overwrite the reset error.
            if !running {
                backend.send(Action::Disconnect);
                backend.send(Action::Connect);
            }
            backend.send(Action::ResetIdentity);
            let worker = thread::spawn(move || {
                manager(
                    paths,
                    None,
                    None,
                    runtime::Options::default(),
                    actions,
                    cancel,
                    Arc::new(move |notice| {
                        publish.send(notice).unwrap();
                    }),
                )
            });
            let until = Instant::now() + Duration::from_secs(3);
            loop {
                assert!(
                    Instant::now() < until,
                    "reset must leave its busy phase promptly"
                );
                if let Notice::Phase(phase, message) =
                    notices.recv_timeout(Duration::from_millis(500)).unwrap()
                {
                    if phase == Phase::Error {
                        assert!(message.contains("Identity reset failed"));
                        assert!(message.contains("Another frontend"));
                        break;
                    }
                    if !running {
                        assert_ne!(phase, Phase::Connecting);
                    }
                }
            }
            let until = Instant::now() + Duration::from_millis(600);
            while Instant::now() < until {
                if let Ok(Notice::Phase(phase, _)) =
                    notices.recv_timeout(Duration::from_millis(100))
                {
                    assert_eq!(
                        phase,
                        Phase::Error,
                        "old status must not obscure the failure"
                    );
                }
            }
            assert_eq!(std::fs::read(dir.identity()).unwrap(), saved);
            backend.send(Action::Shutdown);
            while !matches!(
                notices.recv_timeout(Duration::from_secs(3)).unwrap(),
                Notice::Stopped
            ) {}
            worker.join().unwrap();
            stop_server.store(true, Ordering::Relaxed);
            if let Some(server) = server {
                server.join().unwrap();
            }
            drop(lock);
            std::fs::remove_dir_all(directory).unwrap();
        }
    }

    #[cfg(unix)]
    fn rand_suffix() -> u128 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    }

    #[cfg(unix)]
    #[test]
    fn stopping_service_retries_a_rejected_request_until_endpoint_disappears() {
        use openrad::daemon::{DataDir, Reply, Request};
        use std::{
            io::{Read, Write},
            os::unix::net::UnixListener,
        };
        let directory = std::env::temp_dir().join(format!(
            "openrad-stop-{}-{}",
            std::process::id(),
            rand_suffix()
        ));
        let dir = DataDir::open(Some(directory.clone())).unwrap();
        let socket = dir.socket();
        let listener = UnixListener::bind(&socket).unwrap();
        let server = thread::spawn(move || {
            for attempt in 1..=3 {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(2)))
                    .unwrap();
                let mut bytes = Vec::new();
                stream.read_to_end(&mut bytes).unwrap();
                assert!(matches!(
                    serde_json::from_slice::<Request>(&bytes).unwrap(),
                    Request::Stop
                ));
                stream
                    .write_all(
                        &serde_json::to_vec(&Reply {
                            ok: attempt == 3,
                            message: "busy".into(),
                            data: serde_json::Value::Null,
                        })
                        .unwrap(),
                    )
                    .unwrap();
            }
            drop(listener);
            std::fs::remove_file(socket).unwrap();
        });
        stop_service(&dir).unwrap();
        server.join().unwrap();
        assert!(!dir.endpoint_exists());
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn crashed_service_socket_is_removed_before_reset_without_deleting_other_files() {
        use openrad::daemon::DataDir;
        use std::os::unix::net::UnixListener;
        let directory = std::env::temp_dir().join(format!(
            "openrad-stale-{}-{}",
            std::process::id(),
            rand_suffix()
        ));
        let dir = DataDir::open(Some(directory.clone())).unwrap();
        let listener = UnixListener::bind(dir.socket()).unwrap();
        let service_lock = dir.stopped_lock().unwrap();
        drop(listener);
        assert!(
            !dir.cleanup_stopped_endpoint().unwrap(),
            "an owned service must never be cleaned up"
        );
        assert!(dir.endpoint_exists());
        drop(service_lock);
        stop_service(&dir).unwrap();
        assert!(!dir.endpoint_exists());
        std::fs::write(dir.socket(), b"unrelated file").unwrap();
        assert!(dir.cleanup_stopped_endpoint().is_err());
        assert_eq!(std::fs::read(dir.socket()).unwrap(), b"unrelated file");
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn desktop_inherits_a_cli_session_without_reading_the_vault_and_closes_without_stopping_it() {
        use openrad::{
            daemon::{DataDir, Reply, Request},
            protocol::Network,
        };
        use serde_json::json;
        use std::{
            io::{Read, Write},
            os::unix::net::UnixListener,
        };
        let directory = std::env::temp_dir().join(format!(
            "openrad-shared-ui-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let paths = Paths::new(Some(directory.clone())).unwrap();
        let dir = DataDir::open(Some(directory.clone())).unwrap();
        let listener = UnixListener::bind(dir.socket()).unwrap();
        listener.set_nonblocking(true).unwrap();
        let server_stop = Arc::new(AtomicBool::new(false));
        let cancel = server_stop.clone();
        let stopped_by_gui = Arc::new(AtomicBool::new(false));
        let stopped = stopped_by_gui.clone();
        let server = thread::spawn(move || {
            while !cancel.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        stream
                            .set_read_timeout(Some(Duration::from_secs(2)))
                            .unwrap();
                        let mut bytes = Vec::new();
                        stream.read_to_end(&mut bytes).unwrap();
                        let request: Request = serde_json::from_slice(&bytes).unwrap();
                        let data = match request {
                            Request::Status => {
                                json!({"phase":"connected", "rid":123, "node_name":"cli-device",
                                "preferences": openrad::daemon::ServicePreferences::default(),
                                "snapshot":runtime::Snapshot { vip: Some("26.0.0.5".parse().unwrap()),
                                    networks:vec![Network { name:"Inherited LAN".into(), network_id:"a".into() }],
                                    ..Default::default() }})
                            }
                            Request::Search { query, .. } => {
                                assert_eq!(query, "friends");
                                json!({"networks":[{"name":"Friends","reported_count":4}],"cursor":0})
                            }
                            Request::Stop => {
                                stopped.store(true, Ordering::Relaxed);
                                serde_json::Value::Null
                            }
                            _ => serde_json::Value::Null,
                        };
                        stream
                            .write_all(
                                &serde_json::to_vec(&Reply {
                                    ok: true,
                                    message: "Requested".into(),
                                    data,
                                })
                                .unwrap(),
                            )
                            .unwrap();
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5))
                    }
                    Err(error) => panic!("{error}"),
                }
            }
        });
        let (send, actions) = mpsc::channel();
        let (publish, notices) = mpsc::channel();
        let worker = thread::spawn(move || {
            manager(
                paths,
                None,
                None,
                runtime::Options::default(),
                actions,
                Cancellation::default(),
                Arc::new(move |notice| {
                    publish.send(notice).unwrap();
                }),
            )
        });
        let until = Instant::now() + Duration::from_secs(3);
        let mut inherited = false;
        let mut named = false;
        while Instant::now() < until && !(inherited && named) {
            match notices.recv_timeout(Duration::from_millis(200)).unwrap() {
                Notice::Identity { rid: 123, name } => {
                    assert_eq!(name, "cli-device");
                    named = true;
                }
                Notice::Engine(runtime::Update::State(snapshot)) => {
                    assert_eq!(snapshot.networks[0].name, "Inherited LAN");
                    inherited = true;
                }
                Notice::Phase(Phase::Error, message) => {
                    panic!("must inherit before accessing the vault: {message}")
                }
                _ => {}
            }
        }
        assert!(inherited && named);
        send.send(Action::Engine(runtime::Command::Search {
            query: "friends".into(),
            cursor: 0,
        }))
        .unwrap();
        loop {
            if let Notice::Engine(runtime::Update::Catalog {
                query, networks, ..
            }) = notices.recv_timeout(Duration::from_secs(3)).unwrap()
            {
                assert_eq!(query, "friends");
                assert_eq!(networks[0].name, "Friends");
                break;
            }
        }
        send.send(Action::Shutdown).unwrap();
        while !matches!(
            notices.recv_timeout(Duration::from_secs(3)).unwrap(),
            Notice::Stopped
        ) {}
        worker.join().unwrap();
        assert!(!stopped_by_gui.load(Ordering::Relaxed));
        server_stop.store(true, Ordering::Relaxed);
        server.join().unwrap();
        std::fs::remove_dir_all(directory).unwrap();
    }
}
