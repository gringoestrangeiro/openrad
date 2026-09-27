use crate::storage::{self, Paths, Settings};
use anyhow::{ensure, Result};
use openrad::{output::ReportDirectory, protocol::Identity, runtime, session::Session};
use std::{
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, Sender},
        Arc,
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
#[derive(Clone)]
pub enum Notice {
    Phase(Phase, String),
    Identity { rid: u64, name: String },
    Settings(Settings),
    Engine(runtime::Update),
    Stopped,
    CloseBlocked(String),
    ReplacementPending(bool),
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
pub struct Backend {
    sender: Sender<Action>,
    pub notices: Receiver<Notice>,
    stop: Arc<AtomicBool>,
}
impl Backend {
    pub fn spawn(
        paths: Paths,
        import: Option<PathBuf>,
        options: runtime::Options,
        wake: impl Fn() + Send + Sync + 'static,
    ) -> Self {
        let (sender, actions) = mpsc::channel();
        let (updates, notices) = mpsc::channel();
        let stop = Arc::new(AtomicBool::new(false));
        let cancel = stop.clone();
        thread::spawn(move || {
            let wake = Arc::new(wake);
            let report = {
                let wake = wake.clone();
                move |n| {
                    let _ = updates.send(n);
                    wake();
                }
            };
            manager(paths, import, options, actions, cancel, Arc::new(report));
        });
        Self {
            sender,
            notices,
            stop,
        }
    }
    pub fn send(&self, action: Action) {
        if matches!(
            action,
            Action::Disconnect | Action::ResetIdentity | Action::Shutdown
        ) {
            self.stop.store(true, Ordering::Relaxed);
        }
        let _ = self.sender.send(action);
    }
}
impl Drop for Backend {
    fn drop(&mut self) {
        self.send(Action::Shutdown);
    }
}
type Report = Arc<dyn Fn(Notice) + Send + Sync>;
fn manager(
    paths: Paths,
    import: Option<PathBuf>,
    options: runtime::Options,
    actions: Receiver<Action>,
    stop: Arc<AtomicBool>,
    report: Report,
) {
    let mut identity: Option<Identity> = None;
    let mut persisted = false;
    let mut settings = match paths.settings() {
        Ok(s) => s,
        Err(_) => {
            report(Notice::Phase(
                Phase::Error,
                "Settings could not be loaded. Repair settings.json and restart.".into(),
            ));
            report(Notice::Stopped);
            return;
        }
    };
    report(Notice::Settings(settings.clone()));
    let load_result = (|| -> Result<()> {
        let vault = paths.entry()?;
        identity = storage::load(&vault)?;
        if let Some(path) = import {
            let imported = Identity::load(&path)?;
            ensure!(identity.as_ref().is_none_or(|i| i.rid == imported.rid), "This profile already has an identity; use another data directory to import a different one");
            storage::save(&vault, &imported)?;
            identity = Some(imported);
        }
        persisted = identity.is_some();
        Ok(())
    })();
    let mut want_connect = settings.auto_connect && load_result.is_ok();
    match load_result {
        Ok(()) => report(Notice::Phase(
            Phase::Disconnected,
            "Ready when you are".into(),
        )),
        Err(e) => report(Notice::Phase(Phase::Error, e.to_string())),
    }
    if let Some(i) = &identity {
        report(Notice::Identity {
            rid: i.rid,
            name: i.node_name.clone(),
        });
    }
    let mut session: Option<(Sender<runtime::Command>, thread::JoinHandle<Result<()>>)> = None;
    let mut closing = false;
    let mut retries = 0u32;
    let mut retry_at = Instant::now();
    let mut want_reset = false;
    let mut replacement = storage::Replacement::default();
    loop {
        if want_reset && session.is_none() && !closing {
            want_reset = false;
            report(Notice::Phase(
                Phase::Resetting,
                "Creating your replacement identity…".into(),
            ));
            let result = (|| -> Result<()> {
                let vault = paths.entry()?;
                replacement.prepare(|| {
                    Session::provision(
                        MODULUS,
                        &settings.node_name,
                        openrad::DEFAULT_BOOTSTRAP_HOST,
                        &ReportDirectory::disabled(),
                    )
                })?;
                replacement.commit(&vault, &mut identity)?;
                persisted = true;
                retries = 0;
                let id = identity.as_ref().unwrap();
                report(Notice::Identity {
                    rid: id.rid,
                    name: id.node_name.clone(),
                });
                report(Notice::Engine(runtime::Update::State(
                    runtime::Snapshot::default(),
                )));
                Ok(())
            })();
            report(Notice::ReplacementPending(replacement.is_pending()));
            match result {
                Ok(()) => report(Notice::Phase(
                    Phase::Disconnected,
                    "Identity reset and saved securely. Connect to use your new device.".into(),
                )),
                Err(e) => report(Notice::Phase(
                    Phase::Error,
                    format!("Identity reset: {e}. Your previous saved identity is preserved."),
                )),
            }
        }
        if want_connect
            && !want_reset
            && !replacement.is_pending()
            && session.is_none()
            && Instant::now() >= retry_at
            && !closing
        {
            want_connect = false;
            stop.store(false, Ordering::Relaxed);
            let result = (|| -> Result<()> {
                let vault = paths.entry()?;
                if identity.is_none() {
                    report(Notice::Phase(
                        Phase::Provisioning,
                        "Preparing your private device identity…".into(),
                    ));
                    identity = Some(storage::provision_once(&vault, |commit| {
                        Session::provision_with_commit(
                            MODULUS,
                            &settings.node_name,
                            openrad::DEFAULT_BOOTSTRAP_HOST,
                            &ReportDirectory::disabled(),
                            commit,
                        )
                    })?);
                }
                let id = identity.as_ref().unwrap();
                if !persisted {
                    storage::save(&vault, id)?;
                    persisted = true;
                }
                report(Notice::Identity {
                    rid: id.rid,
                    name: id.node_name.clone(),
                });
                if stop.load(Ordering::Relaxed) {
                    return Ok(());
                }
                report(Notice::Phase(
                    Phase::Connecting,
                    "Authenticating your saved identity…".into(),
                ));
                let (tx, rx) = mpsc::channel();
                let (id, options, stop, publish) =
                    (id.clone(), options.clone(), stop.clone(), report.clone());
                let handle = thread::spawn(move || {
                    runtime::run(id, MODULUS.to_vec(), options, rx, stop, move |update| {
                        publish(Notice::Engine(update))
                    })
                });
                session = Some((tx, handle));
                Ok(())
            })();
            if let Err(e) = result {
                report(Notice::Phase(Phase::Error, e.to_string()));
            }
        }
        if session.as_ref().is_some_and(|(_, h)| h.is_finished()) {
            let (_, handle) = session.take().unwrap();
            let result = handle
                .join()
                .unwrap_or_else(|_| Err(anyhow::anyhow!("VPN worker stopped unexpectedly")));
            match result {
                Ok(()) => {
                    if !want_reset {
                        report(Notice::Phase(
                            Phase::Disconnected,
                            "Disconnected · interface removed".into(),
                        ));
                    }
                    retries = 0;
                }
                Err(e) if !closing => {
                    let can_retry = !want_reset
                        && !replacement.is_pending()
                        && settings.auto_reconnect
                        && retries < 3
                        && persisted;
                    let suffix = if can_retry {
                        retries += 1;
                        want_connect = true;
                        retry_at = Instant::now() + Duration::from_secs(2u64.pow(retries));
                        " Reconnecting with the same identity…"
                    } else {
                        ""
                    };
                    report(Notice::Phase(Phase::Error, format!("{e}.{suffix}")));
                }
                _ => {}
            }
        }
        if closing && session.is_none() {
            if replacement.is_pending() {
                if let Err(e) = paths
                    .entry()
                    .and_then(|vault| replacement.commit(&vault, &mut identity))
                {
                    closing = false;
                    report(Notice::CloseBlocked(e.to_string()));
                    continue;
                }
            }
            if let Some(id) = identity.as_ref().filter(|_| !persisted) {
                match paths.entry().and_then(|vault| storage::save(&vault, id)) {
                    Ok(()) => {}
                    Err(e) => {
                        closing = false;
                        report(Notice::CloseBlocked(e.to_string()));
                        continue;
                    }
                }
            }
            report(Notice::Stopped);
            break;
        }
        match actions.recv_timeout(Duration::from_millis(50)) {
            Ok(Action::Connect)
                if session.is_none() && !want_reset && !replacement.is_pending() =>
            {
                retries = 0;
                want_connect = true;
                retry_at = Instant::now();
            }
            Ok(Action::Disconnect) => {
                want_connect = false;
                stop.store(true, Ordering::Relaxed);
                report(Notice::Phase(
                    if session.is_some() {
                        Phase::Disconnecting
                    } else {
                        Phase::Disconnected
                    },
                    "Disconnecting…".into(),
                ));
            }
            Ok(Action::ResetIdentity) if identity.is_some() && persisted && !want_reset => {
                want_connect = false;
                want_reset = true;
                stop.store(true, Ordering::Relaxed);
                report(Notice::Phase(
                    Phase::Resetting,
                    "Disconnecting the current session before resetting identity…".into(),
                ));
            }
            Ok(Action::Shutdown) | Err(mpsc::RecvTimeoutError::Disconnected) => {
                closing = true;
                want_connect = false;
                stop.store(true, Ordering::Relaxed);
            }
            Ok(Action::Engine(command)) => {
                if let Some((tx, _)) = &session {
                    let _ = tx.send(command);
                }
            }
            Ok(Action::Save(s)) => match paths.save_settings(&s) {
                Ok(()) => {
                    settings = s;
                    report(Notice::Engine(runtime::Update::Operation {
                        message: "Settings saved".into(),
                        error: false,
                    }));
                }
                Err(e) => report(Notice::Engine(runtime::Update::Operation {
                    message: e.to_string(),
                    error: true,
                })),
            },
            Ok(Action::Import(path))
                if session.is_none() && !want_reset && !replacement.is_pending() =>
            {
                let result = (|| -> Result<()> {
                    let imported = Identity::load(&path)?;
                    ensure!(
                        identity.as_ref().is_none_or(|i| i.rid == imported.rid),
                        "An identity is already saved in this profile"
                    );
                    storage::save(&paths.entry()?, &imported)?;
                    report(Notice::Identity {
                        rid: imported.rid,
                        name: imported.node_name.clone(),
                    });
                    identity = Some(imported);
                    persisted = true;
                    Ok(())
                })();
                match result {
                    Ok(()) => report(Notice::Phase(
                        Phase::Disconnected,
                        "Identity imported securely. Ready to connect.".into(),
                    )),
                    Err(e) => report(Notice::Phase(Phase::Error, e.to_string())),
                }
            }
            _ => {}
        }
    }
}
