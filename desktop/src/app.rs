use crate::{
    backend::{Action, Backend, Notice, Phase},
    network_ui::{
        role_label, DeleteConfirmation, FormEvent, MemberConfirmation, Mode, NetworkForm,
    },
    storage::{Paths, PeerSort, Settings, StartPage},
};
use eframe::egui::{self, Align, Color32, FontId, RichText, Stroke, Vec2};
use openrad::{
    i18n::{Language, LanguagePreference},
    network::MemberAction,
    protocol::PublicNetwork,
    runtime::{self, Command, PeerState, PeerView, Snapshot, Update},
};
use std::{
    collections::VecDeque,
    fs::File,
    path::PathBuf,
    time::{Duration, Instant},
};

const BG: Color32 = Color32::from_rgb(13, 18, 25);
const SURFACE: Color32 = Color32::from_rgb(20, 27, 37);
const RAISED: Color32 = Color32::from_rgb(26, 35, 47);
const BORDER: Color32 = Color32::from_rgb(39, 51, 65);
const TEXT: Color32 = Color32::from_rgb(230, 237, 244);
const MUTED: Color32 = Color32::from_rgb(141, 157, 176);
const MINT: Color32 = Color32::from_rgb(98, 226, 181);
const BLUE: Color32 = Color32::from_rgb(132, 164, 255);
const AMBER: Color32 = Color32::from_rgb(244, 190, 100);
const RED: Color32 = Color32::from_rgb(248, 130, 143);

#[derive(Clone, Copy, PartialEq)]
enum Page {
    Networks,
    Discover,
    Settings,
}

#[derive(PartialEq)]
enum Activity {
    Message(String),
    Peer {
        name: String,
        status: PeerState,
        detail: String,
    },
}
impl From<String> for Activity {
    fn from(message: String) -> Self {
        Self::Message(message)
    }
}
impl Activity {
    fn text(&self, language: Language) -> String {
        match self {
            Self::Message(message) => language.message(message),
            Self::Peer {
                name,
                status,
                detail,
            } => format!(
                "{name}: {} · {}",
                language.text(status.label()),
                language.message(detail),
            ),
        }
    }
}
pub struct App {
    first_frame: bool,
    #[cfg(test)]
    windows_preview: bool,
    backend: Backend,
    _lock: File,
    paths: Paths,
    phase: Phase,
    message: String,
    identity: Option<(u64, String)>,
    settings: Settings,
    saved_settings: Settings,
    system_language: Language,
    snapshot: Snapshot,
    page: Page,
    query: String,
    peer_filter: String,
    selected_network: Option<String>,
    catalog: Vec<PublicNetwork>,
    catalog_query: String,
    cursor: u64,
    has_searched: bool,
    busy: bool,
    toast: Option<(String, bool, Instant)>,
    activity: VecDeque<Activity>,
    import_path: String,
    download: VecDeque<f32>,
    upload: VecDeque<f32>,
    last_sample: Instant,
    last_bytes: (u64, u64),
    rates: (f32, f32),
    focus_search: bool,
    closing: bool,
    stopped: bool,
    confirm_reset: bool,
    replacement_pending: bool,
    network_form: Option<NetworkForm>,
    member_confirmation: Option<MemberConfirmation>,
    delete_confirmation: Option<DeleteConfirmation>,
}
impl App {
    pub fn new(
        cc: &eframe::CreationContext<'_>,
        paths: Paths,
        lock: File,
        import: Option<PathBuf>,
        language_override: Option<LanguagePreference>,
        options: runtime::Options,
    ) -> Self {
        crate::startup_log::checkpoint(crate::startup_log::Stage::ConfiguringFonts);
        configure(&cc.egui_ctx);
        crate::startup_log::checkpoint(crate::startup_log::Stage::FontsReady);
        let mut settings = paths.settings().unwrap_or_default();
        if let Some(language) = language_override {
            settings.language = language;
        }
        cc.egui_ctx.set_zoom_factor(settings.scale);
        let ctx = cc.egui_ctx.clone();
        crate::startup_log::checkpoint(crate::startup_log::Stage::StartingBackend);
        let backend = Backend::spawn(
            paths.clone(),
            import,
            language_override,
            options,
            move || ctx.request_repaint(),
        );
        crate::startup_log::checkpoint(crate::startup_log::Stage::BackendStarted);
        Self::from_parts(backend, paths, lock, settings)
    }
    fn from_parts(backend: Backend, paths: Paths, lock: File, settings: Settings) -> Self {
        Self {
            first_frame: true,
            #[cfg(test)]
            windows_preview: cfg!(windows),
            backend,
            _lock: lock,
            paths,
            phase: Phase::Loading,
            message: "Opening your credential store…".into(),
            identity: None,
            saved_settings: settings.clone(),
            settings,
            system_language: Language::system(),
            snapshot: Snapshot::default(),
            page: Page::Networks,
            query: String::new(),
            peer_filter: String::new(),
            selected_network: None,
            catalog: vec![],
            catalog_query: String::new(),
            cursor: 0,
            has_searched: false,
            busy: false,
            toast: None,
            activity: VecDeque::new(),
            import_path: String::new(),
            confirm_reset: false,
            replacement_pending: false,
            network_form: None,
            member_confirmation: None,
            delete_confirmation: None,
            download: VecDeque::new(),
            upload: VecDeque::new(),
            last_sample: Instant::now(),
            last_bytes: (0, 0),
            rates: (0., 0.),
            focus_search: false,
            closing: false,
            stopped: false,
        }
    }
    fn windows_platform(&self) -> bool {
        #[cfg(test)]
        {
            self.windows_preview
        }
        #[cfg(not(test))]
        {
            cfg!(windows)
        }
    }
    fn language(&self) -> Language {
        self.settings.language.resolve(self.system_language)
    }
    fn log(&mut self, text: impl Into<Activity>) {
        let text = text.into();
        if self.activity.front() != Some(&text) {
            self.activity.push_front(text);
            self.activity.truncate(40);
        }
    }
    fn consume(&mut self, ctx: &egui::Context) {
        for notice in self.backend.notices.try_iter().collect::<Vec<_>>() {
            match notice {
                Notice::Phase(phase, message) => {
                    self.busy = false;
                    if phase != Phase::Connected {
                        self.snapshot.interface_ready = false;
                        self.rates = (0., 0.);
                        for peer in self.snapshot.peers.values_mut() {
                            peer.status = PeerState::Offline;
                            peer.detail = "Client disconnected".into();
                            peer.transport = None;
                        }
                    }
                    if matches!(phase, Phase::Connecting | Phase::Disconnected) {
                        self.last_bytes = (0, 0);
                        self.download.clear();
                        self.upload.clear();
                    }
                    self.phase = phase;
                    self.message = message.clone();
                    self.log(message);
                }
                Notice::Identity { rid, name } => self.identity = Some((rid, name)),
                Notice::ReplacementPending(pending) => self.replacement_pending = pending,
                Notice::Settings(s) => {
                    ctx.set_zoom_factor(s.scale);
                    if self.phase == Phase::Loading {
                        self.page = match s.start_page {
                            StartPage::Networks => Page::Networks,
                            StartPage::Discover => Page::Discover,
                        };
                    }
                    self.saved_settings = s.clone();
                    self.settings = s;
                }
                Notice::Engine(Update::State(state)) => {
                    if !self.closing
                        && !matches!(self.phase, Phase::Disconnecting | Phase::Resetting)
                    {
                        self.phase = Phase::Connected;
                    }
                    if self.last_sample.elapsed() >= Duration::from_secs(1) {
                        let dt = self.last_sample.elapsed().as_secs_f32();
                        self.rates = (
                            state
                                .traffic
                                .received_bytes
                                .saturating_sub(self.last_bytes.0)
                                as f32
                                / dt,
                            state.traffic.sent_bytes.saturating_sub(self.last_bytes.1) as f32 / dt,
                        );
                        self.last_bytes = (state.traffic.received_bytes, state.traffic.sent_bytes);
                        self.last_sample = Instant::now();
                        self.download.push_back(self.rates.0);
                        self.upload.push_back(self.rates.1);
                        if self.download.len() > 60 {
                            self.download.pop_front();
                            self.upload.pop_front();
                        }
                    }
                    for (rid, peer) in &state.peers {
                        if self
                            .snapshot
                            .peers
                            .get(rid)
                            .is_none_or(|old| old.status != peer.status)
                            && matches!(peer.status, PeerState::Refused | PeerState::Failed)
                        {
                            self.log(Activity::Peer {
                                name: peer.peer.name.clone(),
                                status: peer.status.clone(),
                                detail: peer.detail.clone(),
                            });
                        }
                    }
                    if self
                        .selected_network
                        .as_ref()
                        .is_some_and(|id| !state.networks.iter().any(|n| &n.network_id == id))
                    {
                        self.selected_network = None;
                    }
                    self.snapshot = state;
                }
                Notice::Engine(Update::Catalog {
                    query,
                    networks,
                    cursor,
                    append,
                }) => {
                    if append {
                        for n in networks {
                            if !self.catalog.iter().any(|old| old.name == n.name) {
                                self.catalog.push(n);
                            }
                        }
                    } else {
                        self.catalog = networks;
                    }
                    self.catalog_query = query;
                    self.cursor = cursor;
                    self.has_searched = true;
                    self.busy = false;
                }
                Notice::Engine(Update::Operation { message, error }) => {
                    self.busy = false;
                    self.log(message.clone());
                    self.toast = Some((message, error, Instant::now()));
                }
                Notice::Engine(Update::CommandResult { .. }) => {}
                Notice::Stopped => self.stopped = true,
                Notice::CloseBlocked(message) => {
                    self.closing = false;
                    self.phase = Phase::Error;
                    self.message = message.clone();
                    self.toast = Some((message, true, Instant::now()));
                }
            }
        }
    }
    fn network_dialogs(&mut self, ctx: &egui::Context) {
        let language = self.language();
        let enabled = self.connected() && !self.busy;
        let event = self
            .network_form
            .as_mut()
            .and_then(|f| f.show(ctx, enabled, language));
        if let Some(event) = event {
            self.network_form = None;
            if let FormEvent::Submit(request) = event {
                self.command(Command::Network(request));
            }
        }
        if let Some(confirm) = &self.member_confirmation {
            let still_allowed = enabled
                && self
                    .identity
                    .as_ref()
                    .is_some_and(|(rid, _)| confirm.allowed(&self.snapshot, *rid));
            if let Some(event) = confirm.show(ctx, still_allowed, language) {
                self.member_confirmation = None;
                if let FormEvent::Submit(request) = event {
                    self.command(Command::Network(request));
                }
            }
        }
        if let Some(confirm) = &self.delete_confirmation {
            let still_allowed = enabled
                && self
                    .identity
                    .as_ref()
                    .is_some_and(|(rid, _)| confirm.allowed(&self.snapshot, *rid));
            if let Some(event) = confirm.show(ctx, still_allowed, language) {
                self.delete_confirmation = None;
                if let FormEvent::Submit(request) = event {
                    self.command(Command::Network(request));
                }
            }
        }
    }
    fn connected(&self) -> bool {
        self.phase == Phase::Connected
    }
    fn command(&mut self, command: Command) {
        if matches!(
            command,
            Command::Search { .. } | Command::Join(_) | Command::Leave(_) | Command::Network(_)
        ) {
            self.busy = true;
        }
        self.backend.send(Action::Engine(command));
    }
    fn toggle(&mut self) {
        if self.replacement_pending || self.confirm_reset {
            return;
        }
        if matches!(self.phase, Phase::Connected | Phase::Connecting) {
            self.phase = Phase::Disconnecting;
            self.backend.send(Action::Disconnect);
        } else if matches!(self.phase, Phase::Disconnected | Phase::Error) {
            self.phase = Phase::Connecting;
            self.message = "Starting connection…".into();
            self.backend.send(Action::Connect);
        }
    }
    fn sidebar(&mut self, ui: &mut egui::Ui) {
        let language = self.language();
        egui::Panel::left("navigation")
            .resizable(false)
            .exact_size(if ui.available_width() < 900. {
                166.
            } else {
                208.
            })
            .frame(egui::Frame::new().fill(SURFACE).inner_margin(20))
            .show(ui, |ui| {
                ui.add_space(10.);
                ui.horizontal(|ui| {
                    logo(ui, 29.);
                    ui.label(RichText::new("openrad").size(24.).strong().color(TEXT));
                });
                ui.add_space(8.);
                ui.label(
                    RichText::new(language.text("YOUR NETWORK, CLOSER"))
                        .size(9.)
                        .color(MUTED),
                );
                ui.add_space(38.);
                for (page, label) in [
                    (Page::Networks, language.text("My networks")),
                    (Page::Discover, language.text("Discover")),
                    (Page::Settings, language.text("Settings")),
                ] {
                    let selected = self.page == page;
                    let button =
                        egui::Button::new(RichText::new(label).size(14.).color(if selected {
                            MINT
                        } else {
                            MUTED
                        }))
                        .fill(if selected {
                            Color32::from_rgb(29, 56, 51)
                        } else {
                            SURFACE
                        })
                        .stroke(Stroke::NONE)
                        .min_size(Vec2::new(ui.available_width(), 42.))
                        .corner_radius(9);
                    if ui.add(button).clicked() {
                        self.page = page;
                    }
                    ui.add_space(6.);
                }
                ui.add_space(24.);
                ui.label(
                    RichText::new(language.text("MEMBERSHIPS"))
                        .size(10.)
                        .color(MUTED),
                );
                ui.add_space(10.);
                if self.snapshot.networks.is_empty() {
                    ui.label(
                        RichText::new(language.text("No networks yet"))
                            .size(12.)
                            .color(MUTED),
                    );
                }
                for n in self.snapshot.networks.iter().take(8) {
                    if ui
                        .add(
                            egui::Button::new(RichText::new(&n.name).size(12.))
                                .selected(self.selected_network.as_ref() == Some(&n.network_id))
                                .truncate()
                                .min_size(Vec2::new(ui.available_width(), 31.)),
                        )
                        .on_hover_text(&n.name)
                        .clicked()
                    {
                        self.selected_network = Some(n.network_id.clone());
                        self.page = Page::Networks;
                    }
                }
                ui.with_layout(egui::Layout::bottom_up(Align::LEFT), |ui| {
                    ui.label(
                        RichText::new(language.message(&if self.windows_platform() {
                            format!("Native Windows · v{}", env!("CARGO_PKG_VERSION"))
                        } else {
                            format!("Native Linux · v{}", env!("CARGO_PKG_VERSION"))
                        }))
                        .size(10.)
                        .color(MUTED),
                    );
                    ui.add_space(8.);
                    ui.horizontal(|ui| {
                        dot(
                            ui,
                            if self.snapshot.interface_ready {
                                MINT
                            } else {
                                MUTED
                            },
                        );
                        ui.label(
                            RichText::new(if self.snapshot.interface_ready {
                                language.text(if self.windows_platform() {
                                    "OpenRad TAP active"
                                } else {
                                    "radminvpn0 active"
                                })
                            } else {
                                language.text("Interface offline")
                            })
                            .size(11.)
                            .color(MUTED),
                        );
                    });
                    ui.add_space(10.);
                    ui.separator();
                    ui.add_space(12.);
                    ui.label(
                        RichText::new(
                            self.identity
                                .as_ref()
                                .map(|(_, n)| n.as_str())
                                .unwrap_or(language.text("This device")),
                        )
                        .size(12.)
                        .color(TEXT),
                    );
                });
            });
    }
    fn hero(&mut self, ui: &mut egui::Ui) {
        let language = self.language();
        let (title, caption, color) = match self.phase {
            Phase::Loading => (
                language.text("Getting ready"),
                language.text("Opening your saved identity"),
                BLUE,
            ),
            Phase::Provisioning => (
                language.text("Creating your identity"),
                language.text("One private identity, saved for your next connection"),
                BLUE,
            ),
            Phase::Resetting => (
                language.text("Resetting your identity"),
                language.text("Disconnecting, provisioning and saving your replacement"),
                BLUE,
            ),
            Phase::Connecting => (
                language.text("Connecting"),
                language.text("Authenticating with the network"),
                BLUE,
            ),
            Phase::Connected if self.snapshot.interface_error.is_some() => (
                language.text("Interface needs attention"),
                language.text("Your account is connected; application traffic is paused"),
                AMBER,
            ),
            Phase::Connected if !self.snapshot.interface_ready => (
                language.text("Preparing your interface"),
                language.text("Your account is connected"),
                BLUE,
            ),
            Phase::Connected => (
                language.text("You’re connected"),
                language.text("Your devices are within reach"),
                MINT,
            ),
            Phase::Disconnecting => (
                language.text("Disconnecting"),
                language.text("Closing channels and removing the interface"),
                BLUE,
            ),
            Phase::Error => (
                language.text("Connection needs attention"),
                language.text("Your saved identity is kept safe"),
                RED,
            ),
            Phase::Disconnected => (
                language.text("Ready to connect"),
                language.text("Bring your devices onto the same network"),
                MUTED,
            ),
        };
        card().inner_margin(24).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.horizontal(|ui| {
                let (rect, _) = ui.allocate_exact_size(Vec2::splat(54.), egui::Sense::hover());
                ui.painter()
                    .circle_filled(rect.center(), 26., color.gamma_multiply(0.13));
                let c = rect.center();
                let points = vec![
                    c + Vec2::new(-13., -13.),
                    c + Vec2::new(0., -18.),
                    c + Vec2::new(13., -13.),
                    c + Vec2::new(11., 6.),
                    c + Vec2::new(0., 17.),
                    c + Vec2::new(-11., 6.),
                    c + Vec2::new(-13., -13.),
                ];
                ui.painter()
                    .add(egui::Shape::line(points, Stroke::new(1.7, color)));
                if self.snapshot.interface_ready {
                    ui.painter().line_segment(
                        [c + Vec2::new(-6., 0.), c + Vec2::new(-1., 5.)],
                        Stroke::new(2., color),
                    );
                    ui.painter().line_segment(
                        [c + Vec2::new(-1., 5.), c + Vec2::new(7., -5.)],
                        Stroke::new(2., color),
                    );
                }
                ui.add_space(12.);
                ui.allocate_ui_with_layout(
                    Vec2::new((ui.available_width() - 155.).max(140.), 0.),
                    egui::Layout::top_down(Align::LEFT),
                    |ui| {
                        ui.label(RichText::new(title).size(25.).strong().color(TEXT));
                        ui.add_space(3.);
                        ui.label(RichText::new(caption).size(12.).color(MUTED));
                    },
                );
                ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                    let can_toggle = matches!(
                        self.phase,
                        Phase::Disconnected | Phase::Error | Phase::Connected | Phase::Connecting
                    );
                    let label = if matches!(self.phase, Phase::Connected | Phase::Connecting) {
                        language.text("Disconnect")
                    } else {
                        language.text("Connect")
                    };
                    if ui
                        .add_enabled(can_toggle, primary(label).min_size(Vec2::new(112., 40.)))
                        .on_hover_text("Ctrl+D")
                        .clicked()
                    {
                        self.toggle();
                    }
                });
            });
            ui.add_space(18.);
            ui.separator();
            ui.add_space(12.);
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new(language.text("VPN ADDRESS"))
                        .size(10.)
                        .color(MUTED),
                );
                let ip = if self.connected() {
                    self.snapshot
                        .vip
                        .map(|i| i.to_string())
                        .unwrap_or_else(|| language.text("Awaiting assignment").into())
                } else {
                    "—".into()
                };
                ui.label(RichText::new(&ip).monospace().size(16.).color(color));
                if self.connected() && ui.small_button(language.text("Copy")).clicked() {
                    ui.ctx().copy_text(ip);
                }
                ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                    if self.connected() {
                        ui.label(
                            RichText::new(format!(
                                "{}  ·  {} ms",
                                elapsed(self.snapshot.elapsed_secs),
                                self.snapshot.latency_ms
                            ))
                            .size(11.)
                            .color(MUTED),
                        );
                    } else if matches!(
                        self.phase,
                        Phase::Loading
                            | Phase::Connecting
                            | Phase::Provisioning
                            | Phase::Resetting
                            | Phase::Disconnecting
                    ) {
                        ui.spinner();
                    } else {
                        ui.label(
                            RichText::new(language.text("IPv4 · per-peer transport"))
                                .size(11.)
                                .color(MUTED),
                        );
                    }
                });
            });
        });
        if self.phase == Phase::Error {
            self.banner(ui, &self.message.clone(), RED);
        }
        if let Some(error) = self
            .snapshot
            .interface_error
            .clone()
            .filter(|_| self.connected())
        {
            ui.add_space(12.);
            egui::Frame::new().fill(Color32::from_rgb(49,39,29)).corner_radius(10).inner_margin(16).show(ui, |ui| {
                ui.label(RichText::new(language.message(&error)).color(AMBER));
                ui.label(RichText::new(language.text(if self.windows_platform() { "Windows: run OpenRad-Setup.exe to install or repair the TAP adapter, then retry. Administrator approval is requested automatically." } else { "Linux: authorize the setup helper with sudo -v in the launching terminal, then retry. The desktop stays unprivileged." })).size(12.).color(MUTED));
                ui.add_space(6.); if ui.button(language.text("Retry interface setup")).clicked() { self.command(Command::RetryInterface); }
            });
        }
    }
    fn banner(&self, ui: &mut egui::Ui, message: &str, color: Color32) {
        ui.add_space(12.);
        egui::Frame::new()
            .fill(color.gamma_multiply(0.10))
            .stroke(Stroke::new(1., color.gamma_multiply(0.35)))
            .corner_radius(9)
            .inner_margin(12)
            .show(ui, |ui| {
                ui.set_min_width(ui.available_width());
                ui.label(
                    RichText::new(self.language().message(message))
                        .size(12.)
                        .color(color),
                );
            });
    }
    fn traffic(&self, ui: &mut egui::Ui) {
        let language = self.language();
        ui.add_space(16.);
        ui.columns(3, |cols| {
            metric(
                &mut cols[0],
                language.text("DOWNLOAD"),
                &rate(self.rates.0, self.settings.decimal_units),
                &language.message(&format!(
                    "{} received",
                    bytes(
                        self.snapshot.traffic.received_bytes,
                        self.settings.decimal_units
                    )
                )),
                MINT,
                self.settings.show_traffic_graphs.then_some(&self.download),
            );
            metric(
                &mut cols[1],
                language.text("UPLOAD"),
                &rate(self.rates.1, self.settings.decimal_units),
                &language.message(&format!(
                    "{} sent",
                    bytes(
                        self.snapshot.traffic.sent_bytes,
                        self.settings.decimal_units
                    )
                )),
                BLUE,
                self.settings.show_traffic_graphs.then_some(&self.upload),
            );
            let active = self
                .snapshot
                .peers
                .values()
                .filter(|p| p.status == PeerState::Connected)
                .count();
            let queued = self
                .snapshot
                .peers
                .values()
                .filter(|p| matches!(p.status, PeerState::Online | PeerState::Connecting))
                .count();
            metric(
                &mut cols[2],
                language.text("PEER CONNECTIONS"),
                &format!("{active} / {}", self.snapshot.peers.len()),
                &language.message(&format!(
                    "{queued} pending · {} filtered frames",
                    self.snapshot.traffic.dropped
                )),
                TEXT,
                None,
            );
        });
    }
    fn networks(&mut self, ui: &mut egui::Ui) {
        let language = self.language();
        self.hero(ui);
        if self.settings.show_traffic {
            self.traffic(ui);
        }
        ui.add_space(25.);
        ui.label(
            RichText::new(language.text("Your networks"))
                .size(19.)
                .strong(),
        );
        ui.add_space(8.);
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    self.connected() && !self.busy,
                    primary(language.text("Create private network")),
                )
                .clicked()
            {
                self.network_form = Some(NetworkForm::new(Mode::Create));
            }
            if ui
                .add_enabled(
                    self.connected() && !self.busy,
                    egui::Button::new(language.text("Join private network")),
                )
                .clicked()
            {
                self.network_form = Some(NetworkForm::new(Mode::Join));
            }
            if ui.button(language.text("Browse public")).clicked() {
                self.page = Page::Discover;
                self.focus_search = true;
            }
        });
        ui.add_space(12.);
        if self.snapshot.networks.is_empty() {
            card().inner_margin(28).show(ui, |ui| {
                ui.set_min_width(ui.available_width());
                ui.label(RichText::new(if self.connected() { language.text("Make your first connection") } else { language.text("Your networks will appear here") }).size(19.).strong());
                ui.add_space(8.); ui.label(RichText::new(language.text("Create a private network, join one with a password, or explore public networks. OpenRad connects to available members.")).color(MUTED));
                ui.add_space(12.); if ui.add_enabled(self.connected(), primary(language.text("Explore public networks"))).clicked() { self.page = Page::Discover; self.focus_search = true; }
            });
            return;
        }
        ui.horizontal_wrapped(|ui| {
            if ui
                .selectable_label(
                    self.selected_network.is_none(),
                    language.text("All networks"),
                )
                .clicked()
            {
                self.selected_network = None;
            }
            for n in &self.snapshot.networks {
                if ui
                    .selectable_label(
                        self.selected_network.as_ref() == Some(&n.network_id),
                        &n.name,
                    )
                    .clicked()
                {
                    self.selected_network = Some(n.network_id.clone());
                }
            }
        });
        ui.add_space(12.);
        if self.selected_network.is_none() {
            ui.label(
                RichText::new(
                    language.text("Select a network to see roles and manage its members."),
                )
                .size(11.)
                .color(MUTED),
            );
            ui.add_space(8.);
        }
        ui.horizontal_wrapped(|ui| {
            ui.add(
                egui::TextEdit::singleline(&mut self.peer_filter)
                    .hint_text(language.text("Filter peers by name or VPN address"))
                    .desired_width((ui.available_width() - 130.).max(180.)),
            );
            if ui
                .add_enabled(
                    self.connected(),
                    egui::Button::new(language.text("Retry failed peers")),
                )
                .clicked()
            {
                self.command(Command::RetryPeers);
            }
        });
        let failed = self
            .snapshot
            .peers
            .values()
            .filter(|p| matches!(p.status, PeerState::Failed | PeerState::Refused))
            .count();
        let offline = self
            .snapshot
            .peers
            .values()
            .filter(|p| p.status == PeerState::Offline)
            .count();
        ui.label(
            RichText::new(language.message(&format!(
                "{failed} failed/refused · {offline} offline · {} retrying · {} retries queued",
                self.snapshot.retry_active, self.snapshot.retry_queued
            )))
            .size(11.)
            .color(MUTED),
        );
        if let Some(id) = &self.selected_network {
            if let Some(n) = self
                .snapshot
                .networks
                .iter()
                .find(|n| &n.network_id == id)
                .cloned()
            {
                ui.add_space(10.);
                let role = self
                    .identity
                    .as_ref()
                    .and_then(|(rid, _)| self.snapshot.roles.get(&n.network_id)?.get(rid).copied());
                ui.horizontal_wrapped(|ui| {
                    ui.label(RichText::new(&n.name).strong());
                    if self.settings.show_internal_ids {
                        ui.label(
                            RichText::new(&n.network_id)
                                .monospace()
                                .size(11.)
                                .color(MUTED),
                        );
                    }
                    badge(
                        ui,
                        role_label(role, language),
                        if role == Some(2) { MINT } else { MUTED },
                    );
                });
                ui.horizontal_wrapped(|ui| {
                    if ui
                        .add_enabled(
                            self.connected() && !self.busy,
                            egui::Button::new(language.text("Leave network")),
                        )
                        .clicked()
                    {
                        self.command(Command::Leave(n.network_id.clone()));
                    }
                    if role == Some(2)
                        && ui
                            .add_enabled(
                                self.connected() && !self.busy,
                                egui::Button::new(
                                    RichText::new(language.text("Delete network")).color(RED),
                                ),
                            )
                            .clicked()
                    {
                        self.delete_confirmation = Some(DeleteConfirmation {
                            network: n.network_id.clone(),
                            name: n.name.clone(),
                        });
                    }
                });
            }
        }
        ui.add_space(10.);
        let can_manage = self.connected() && !self.busy;
        let peers = visible_peers(
            &self.snapshot,
            self.selected_network.as_deref(),
            &self.peer_filter,
            &self.settings,
        );
        card().inner_margin(14).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            if peers.is_empty() {
                ui.add_space(15.);
                ui.label(
                    RichText::new(language.text(
                        "No peers match this view. Members will appear as the server reports them.",
                    ))
                    .color(MUTED),
                );
                ui.add_space(15.);
            }
            for (index, peer) in peers.iter().enumerate() {
                ui.push_id(peer.peer.rid, |ui| {
                    ui.horizontal(|ui| {
                        let color = peer_color(&peer.status);
                        dot(ui, color);
                        let width = (ui.available_width() - 330.).max(100.);
                        ui.allocate_ui_with_layout(
                            Vec2::new(
                                width,
                                if self.settings.show_internal_ids {
                                    58.
                                } else {
                                    43.
                                },
                            ),
                            egui::Layout::top_down(Align::LEFT),
                            |ui| {
                                ui.add(
                                    egui::Label::new(
                                        RichText::new(&peer.peer.name).size(13.).strong(),
                                    )
                                    .truncate(),
                                )
                                .on_hover_text(&peer.peer.name);
                                ui.label(
                                    RichText::new(peer.peer.vip.to_string())
                                        .monospace()
                                        .size(11.)
                                        .color(MUTED),
                                );
                                if self.settings.show_internal_ids {
                                    ui.label(
                                        RichText::new(format!("RID {}", peer.peer.rid))
                                            .monospace()
                                            .size(10.)
                                            .color(MUTED),
                                    );
                                }
                            },
                        );
                        ui.horizontal_wrapped(|ui| {
                            if let Some(network) = &self.selected_network {
                                let role = self
                                    .snapshot
                                    .roles
                                    .get(network)
                                    .and_then(|r| r.get(&peer.peer.rid))
                                    .copied();
                                let own_role = self.identity.as_ref().and_then(|(rid, _)| {
                                    self.snapshot.roles.get(network)?.get(rid).copied()
                                });
                                if own_role == Some(2) {
                                    ui.add_enabled_ui(can_manage, |ui| {
                                        ui.menu_button(language.text("Manage"), |ui| {
                                            let role_action = match role {
                                                Some(2) => Some(MemberAction::RevokeAdmin),
                                                Some(1) => Some(MemberAction::GrantAdmin),
                                                _ => None,
                                            };
                                            for action in
                                                role_action.into_iter().chain([MemberAction::Kick])
                                            {
                                                if ui
                                                    .button(language.text(action.label()))
                                                    .clicked()
                                                {
                                                    self.member_confirmation =
                                                        Some(MemberConfirmation {
                                                            network: network.clone(),
                                                            network_name: self
                                                                .snapshot
                                                                .networks
                                                                .iter()
                                                                .find(|n| &n.network_id == network)
                                                                .map(|n| n.name.clone())
                                                                .unwrap_or_default(),
                                                            member: peer.peer.rid,
                                                            member_name: peer.peer.name.clone(),
                                                            action,
                                                        });
                                                    ui.close();
                                                }
                                            }
                                        });
                                    });
                                }
                                badge(
                                    ui,
                                    role_label(role, language),
                                    if role == Some(2) { MINT } else { MUTED },
                                );
                            }
                            badge(
                                ui,
                                language.text(
                                    peer.transport
                                        .map(|p| p.label())
                                        .unwrap_or(peer.status.label()),
                                ),
                                color,
                            )
                            .on_hover_text(language.message(
                                if peer.detail.is_empty() {
                                    peer.status.label()
                                } else {
                                    &peer.detail
                                },
                            ));
                        });
                    });
                    if self.settings.show_peer_details && !peer.detail.is_empty() {
                        ui.label(
                            RichText::new(language.message(&peer.detail))
                                .size(11.)
                                .color(MUTED),
                        );
                    }
                    if index + 1 < peers.len() {
                        ui.separator();
                    }
                });
            }
        });
        ui.add_space(12.);
        ui.label(RichText::new(language.text("Refused means the remote service declined the connection. Offline members are kept in your network list.")).size(11.).color(MUTED));
        if self.settings.show_recent_activity && !self.activity.is_empty() {
            ui.add_space(15.);
            egui::CollapsingHeader::new(language.text("Recent activity")).show(ui, |ui| {
                for event in self
                    .activity
                    .iter()
                    .take(self.settings.recent_activity_count)
                {
                    ui.label(RichText::new(event.text(language)).size(11.).color(MUTED));
                }
            });
        }
    }
    fn discover(&mut self, ui: &mut egui::Ui) {
        let language = self.language();
        ui.label(
            RichText::new(language.text("Find your people."))
                .size(29.)
                .strong(),
        );
        ui.add_space(8.);
        ui.label(
            RichText::new(
                language.text("Explore public networks and bring everyone onto the same LAN."),
            )
            .color(MUTED),
        );
        ui.add_space(24.);
        let available = self.connected() && !self.busy;
        card().inner_margin(18).show(ui, |ui| {
            ui.horizontal(|ui| {
                let response = ui.add_enabled(
                    available,
                    egui::TextEdit::singleline(&mut self.query)
                        .hint_text(language.text("Search games, communities, or a network name"))
                        .desired_width((ui.available_width() - 100.).max(180.)),
                );
                if self.focus_search {
                    response.request_focus();
                    self.focus_search = false;
                }
                let enter = response.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                if ui
                    .add_enabled(available, primary(language.text("Search")))
                    .clicked()
                    || available && enter
                {
                    self.command(Command::Search {
                        query: self.query.trim().to_owned(),
                        cursor: 0,
                    });
                }
                if self.busy {
                    ui.spinner();
                }
            });
        });
        ui.add_space(18.);
        if !self.connected() {
            self.banner(
                ui,
                language.text("Connect your device to search and join public networks."),
                AMBER,
            );
            if ui
                .add_enabled(
                    matches!(self.phase, Phase::Disconnected | Phase::Error),
                    primary(language.text("Connect")),
                )
                .clicked()
            {
                self.toggle();
            }
            return;
        }
        if !self.has_searched && !self.busy {
            ui.label(
                RichText::new(language.text("Start with a name, or browse what’s available."))
                    .size(18.),
            );
            ui.add_space(12.);
            if ui
                .add(primary(language.text("Browse public networks")))
                .clicked()
            {
                self.command(Command::Search {
                    query: String::new(),
                    cursor: 0,
                });
            }
        } else if self.catalog.is_empty() && !self.busy {
            ui.label(
                RichText::new(language.text("No networks found"))
                    .size(21.)
                    .strong(),
            );
            ui.label(
                RichText::new(language.text("Try a shorter name or a different search."))
                    .color(MUTED),
            );
        } else {
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new(language.message(&format!("{} networks", self.catalog.len())))
                        .size(13.)
                        .color(MUTED),
                );
                if self.busy {
                    ui.spinner();
                    ui.label(RichText::new(language.text("Working…")).color(MUTED));
                }
            });
            ui.add_space(12.);
            for network in self.catalog.clone() {
                let joined = self
                    .snapshot
                    .networks
                    .iter()
                    .any(|n| n.name == network.name);
                ui.push_id(&network.name, |ui| {
                    card().inner_margin(18).show(ui, |ui| {
                        ui.horizontal(|ui| {
                            let width = (ui.available_width() - 110.).max(150.);
                            ui.allocate_ui_with_layout(
                                Vec2::new(width, 45.),
                                egui::Layout::top_down(Align::LEFT),
                                |ui| {
                                    ui.add(
                                        egui::Label::new(
                                            RichText::new(&network.name).size(16.).strong(),
                                        )
                                        .truncate(),
                                    )
                                    .on_hover_text(&network.name);
                                    ui.add_space(5.);
                                    ui.label(
                                        RichText::new(language.message(&format!(
                                            "Public network  ·  {} members reported",
                                            network.reported_count
                                        )))
                                        .size(11.)
                                        .color(MUTED),
                                    );
                                },
                            );
                            ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                                if joined {
                                    badge(ui, language.text("Joined"), MINT);
                                } else if ui
                                    .add_enabled(!self.busy, primary(language.text("Join network")))
                                    .clicked()
                                {
                                    self.command(Command::Join(network.name.clone()));
                                }
                            });
                        });
                    });
                    ui.add_space(9.);
                });
            }
            if self.cursor != 0
                && ui
                    .add_enabled(
                        !self.busy,
                        egui::Button::new(language.text("Load more networks")),
                    )
                    .clicked()
            {
                self.command(Command::Search {
                    query: self.catalog_query.clone(),
                    cursor: self.cursor,
                });
            }
        }
    }
    fn settings(&mut self, ui: &mut egui::Ui) {
        let language = self.language();
        ui.label(
            RichText::new(language.text("Make yourself at home."))
                .size(28.)
                .strong(),
        );
        ui.add_space(8.);
        ui.label(
            RichText::new(
                language
                    .text("Tune connection behavior, the workspace, and developer diagnostics."),
            )
            .color(MUTED),
        );
        ui.add_space(25.);
        card().inner_margin(22).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            let label = ui.label(RichText::new(language.text("Language")).size(18.).strong());
            ui.add_space(12.);
            egui::ComboBox::from_id_salt("language")
                .selected_text(self.settings.language.label(language))
                .show_ui(ui, |ui| {
                    for preference in LanguagePreference::ALL {
                        ui.selectable_value(
                            &mut self.settings.language,
                            preference,
                            preference.label(language),
                        );
                    }
                })
                .response
                .labelled_by(label.id);
            ui.label(
                RichText::new(language.format(
                    "Detected system language: {name}",
                    &[("name", self.system_language.localized_name(language))],
                ))
                .size(11.)
                .color(MUTED),
            );
        });
        ui.add_space(16.);
        card().inner_margin(22).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(RichText::new(language.text("Connection")).size(18.).strong());
            ui.add_space(12.);
            ui.checkbox(
                &mut self.settings.auto_connect,
                language.text("Connect when OpenRad launches"),
            );
            ui.checkbox(
                &mut self.settings.auto_reconnect,
                language.text("Reconnect after a connection failure"),
            );
            ui.add_enabled_ui(self.settings.auto_reconnect, |ui| {
                ui.horizontal(|ui| {
                    ui.label(language.text("Maximum attempts"));
                    ui.add(egui::Slider::new(&mut self.settings.reconnect_attempts, 1..=10));
                });
                ui.horizontal(|ui| {
                    ui.label(language.text("Initial retry delay"));
                    ui.add(egui::Slider::new(&mut self.settings.reconnect_base_delay_seconds, 1..=30).suffix(" s"));
                });
            });
            ui.label(RichText::new(language.text("Each retry waits twice as long, up to five minutes. Changes apply to the next failure.")).size(11.).color(MUTED));
            ui.add_space(14.);
            ui.label(language.text("Device name"));
            ui.add_enabled(
                self.identity.is_none(),
                egui::TextEdit::singleline(&mut self.settings.node_name).desired_width(300.),
            );
            ui.label(
                RichText::new(language.text("The device name is set when your identity is created."))
                    .size(11.)
                    .color(MUTED),
            );
        });
        ui.add_space(16.);
        card().inner_margin(22).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(RichText::new(language.text("Workspace")).size(18.).strong());
            ui.add_space(12.);
            ui.label(language.text("Open to"));
            egui::ComboBox::from_id_salt("start_page")
                .selected_text(match self.settings.start_page {
                    StartPage::Networks => language.text("My networks"),
                    StartPage::Discover => language.text("Discover"),
                })
                .show_ui(ui, |ui| {
                    ui.selectable_value(
                        &mut self.settings.start_page,
                        StartPage::Networks,
                        language.text("My networks"),
                    );
                    ui.selectable_value(
                        &mut self.settings.start_page,
                        StartPage::Discover,
                        language.text("Discover"),
                    );
                });
            ui.label(
                RichText::new(language.text("Used the next time OpenRad starts."))
                    .size(11.)
                    .color(MUTED),
            );
            ui.add_space(12.);
            ui.label(language.text("Interface scale"));
            if ui
                .add(egui::Slider::new(&mut self.settings.scale, 0.8..=1.5).step_by(0.05))
                .changed()
            {
                ui.ctx().set_zoom_factor(self.settings.scale);
            }
            ui.separator();
            ui.checkbox(
                &mut self.settings.show_traffic,
                language.text("Show traffic overview"),
            );
            ui.add_enabled_ui(self.settings.show_traffic, |ui| {
                ui.checkbox(
                    &mut self.settings.show_traffic_graphs,
                    language.text("Show traffic graphs"),
                );
                ui.checkbox(
                    &mut self.settings.decimal_units,
                    language.text("Use decimal traffic units (kB / MB)"),
                );
            });
            ui.checkbox(
                &mut self.settings.show_offline_peers,
                language.text("Show offline peers"),
            );
            ui.label(language.text("Sort peers by"));
            egui::ComboBox::from_id_salt("peer_sort")
                .selected_text(match self.settings.peer_sort {
                    PeerSort::Name => language.text("Name"),
                    PeerSort::Status => language.text("Connection status"),
                    PeerSort::Address => language.text("VPN address"),
                })
                .show_ui(ui, |ui| {
                    ui.selectable_value(
                        &mut self.settings.peer_sort,
                        PeerSort::Name,
                        language.text("Name"),
                    );
                    ui.selectable_value(
                        &mut self.settings.peer_sort,
                        PeerSort::Status,
                        language.text("Connection status"),
                    );
                    ui.selectable_value(
                        &mut self.settings.peer_sort,
                        PeerSort::Address,
                        language.text("VPN address"),
                    );
                });
            ui.checkbox(
                &mut self.settings.show_recent_activity,
                language.text("Show recent activity"),
            );
            ui.add_enabled_ui(self.settings.show_recent_activity, |ui| {
                ui.horizontal(|ui| {
                    ui.label(language.text("Events shown"));
                    ui.add(egui::Slider::new(
                        &mut self.settings.recent_activity_count,
                        5..=40,
                    ));
                });
            });
        });
        ui.add_space(16.);
        card().inner_margin(22).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(
                RichText::new(language.text("Developer view"))
                    .size(18.)
                    .strong(),
            );
            ui.add_space(12.);
            ui.checkbox(
                &mut self.settings.show_peer_details,
                language.text("Show peer connection details under each row"),
            );
            ui.checkbox(
                &mut self.settings.show_internal_ids,
                language.text("Show internal peer and network IDs"),
            );
            ui.checkbox(
                &mut self.settings.show_diagnostics,
                language.text("Show live diagnostic counters"),
            );
            if self.settings.show_diagnostics {
                ui.separator();
                let connected = self
                    .snapshot
                    .peers
                    .values()
                    .filter(|p| p.status == PeerState::Connected)
                    .count();
                ui.label(language.message(&format!(
                    "Session: {} · latency: {} ms · interface: {}",
                    elapsed(self.snapshot.elapsed_secs),
                    self.snapshot.latency_ms,
                    if self.snapshot.interface_ready {
                        language.text("ready")
                    } else {
                        language.text("not ready")
                    }
                )));
                ui.label(language.message(&format!(
                    "Networks: {} · peers: {} · connected: {}",
                    self.snapshot.networks.len(),
                    self.snapshot.peers.len(),
                    connected
                )));
                ui.label(language.message(&format!(
                    "Frames: {} in / {} out / {} filtered",
                    self.snapshot.traffic.received_frames,
                    self.snapshot.traffic.sent_frames,
                    self.snapshot.traffic.dropped
                )));
                if ui
                    .button(language.text("Copy diagnostic summary"))
                    .clicked()
                {
                    ui.ctx().copy_text(self.diagnostic_summary());
                }
            }
        });
        ui.add_space(16.);
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    self.settings != self.saved_settings,
                    primary(language.text("Save preferences")),
                )
                .clicked()
            {
                self.backend.send(Action::Save(self.settings.clone()));
            }
            if ui
                .add_enabled(
                    self.settings != self.saved_settings,
                    egui::Button::new(language.text("Discard changes")),
                )
                .clicked()
            {
                self.settings = self.saved_settings.clone();
                ui.ctx().set_zoom_factor(self.settings.scale);
            }
            if ui.button(language.text("Restore defaults")).clicked() {
                let mut defaults = Settings::default();
                if self.identity.is_some() {
                    defaults.node_name = self.settings.node_name.clone();
                }
                self.settings = defaults;
                ui.ctx().set_zoom_factor(self.settings.scale);
            }
        });
        ui.label(
            RichText::new(language.text(
                "Display changes preview immediately. Save preferences to keep them after restart.",
            ))
            .size(11.)
            .color(MUTED),
        );
        ui.add_space(16.);
        card().inner_margin(22).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(RichText::new(language.text("Private device identity")).size(18.).strong()); ui.add_space(12.);
            if let Some((rid, name)) = &self.identity { ui.label(language.format("{name}  ·  Device {rid}", &[("name", name), ("rid", &rid.to_string())])); }
            else { ui.label(language.text("No identity loaded")); }
            ui.label(RichText::new(language.text("Stored in your operating system’s credential store. The same identity is reused each time you connect.")).size(12.).color(MUTED));
            ui.add_space(12.);
            let can_reset = self.identity.is_some() && !self.closing && matches!(self.phase, Phase::Connected | Phase::Disconnected | Phase::Error);
            let label = if self.replacement_pending { language.text("Retry saving new identity") } else { language.text("Reset identity…") };
            if ui.add_enabled(can_reset, egui::Button::new(RichText::new(label).color(AMBER))).clicked() {
                if self.replacement_pending { self.phase = Phase::Resetting; self.backend.send(Action::ResetIdentity); }
                else { self.confirm_reset = true; }
            }
            ui.add_space(12.);
            ui.label(RichText::new(language.text("Import an existing OpenRad identity")).strong());
            ui.horizontal(|ui| {
                ui.add(egui::TextEdit::singleline(&mut self.import_path).hint_text("/path/to/identity.json").desired_width((ui.available_width()-90.).max(160.)));
                if ui.add_enabled(!self.connected() && self.identity.is_none() && !self.import_path.is_empty(), egui::Button::new(language.text("Import"))).clicked() { self.backend.send(Action::Import(PathBuf::from(&self.import_path))); }
            });
        });
        ui.add_space(16.);
        card().inner_margin(22).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(RichText::new(language.text(if self.windows_platform() { "Windows interface" } else { "Linux interface" })).size(18.).strong()); ui.add_space(10.);
            ui.label(if self.windows_platform() { "OpenRad · TAP-Windows6 · MTU 1500" } else { "radminvpn0 · Ethernet TAP · MTU 1500" });
            ui.label(RichText::new(language.text(if self.windows_platform() { "OpenRad Setup creates the dedicated TAP-Windows6 adapter. Disconnecting removes the VPN address and session routes; the installed adapter remains." } else { "A short-lived helper configures the interface and member host routes. OpenRad runs as your normal user. Closing the app removes its interface and routes." })).size(12.).color(MUTED));
            ui.add_space(10.); ui.label(RichText::new(language.text(if self.windows_platform() { "Run OpenRad-Setup.exe again to check or repair setup. Use --no-launch for CLI setup. Windows runtime validation is still pending. See docs/windows.md." } else { "If setup needs permission, run sudo -v in the terminal that launches OpenRad, then retry interface setup." })).size(12.).color(MUTED));
            ui.add_space(10.); ui.label(RichText::new(language.text("Windows uses TAP-Windows6 and awaits Windows runtime validation. macOS has no data plane. Each connected peer shows its authenticated transport; relay remains available when direct connection fails.")).size(12.).color(MUTED));
            ui.add_space(10.); ui.label(RichText::new(language.message(&format!("Settings: {}", self.paths.directory.display()))).size(11.).color(MUTED));
            if ui.button(language.text("Copy profile path")).clicked() {
                ui.ctx().copy_text(self.paths.directory.display().to_string());
            }
            ui.add_space(10.);
            ui.label(RichText::new(language.message(&format!("Connection logs: {}", self.paths.directory.join("diagnostics").display()))).size(11.).color(MUTED));
            if ui.button(language.text("Copy connection log path")).clicked() {
                ui.ctx().copy_text(self.paths.directory.join("diagnostics").display().to_string());
            }
        });
        ui.add_space(16.);
        ui.label(RichText::new(language.text("Shortcuts: Ctrl+K search · Ctrl+D connect / disconnect · Ctrl+, settings · Tab / Shift+Tab navigate")).size(11.).color(MUTED));
    }
    fn diagnostic_summary(&self) -> String {
        let language = self.language();
        let connected = self
            .snapshot
            .peers
            .values()
            .filter(|p| p.status == PeerState::Connected)
            .count();
        language.format(
            "OpenRad {version}\nState: {state}\nSession: {session}\nInterface ready: {interface}\nLatency: {latency} ms\nNetworks: {networks}\nPeers: {peers} ({connected} connected)\nTraffic: {received} received / {sent} sent bytes\nFrames: {frames_in} received / {frames_out} sent / {filtered} filtered\nRestricted traffic: {restricted}",
            &[
                ("version", env!("CARGO_PKG_VERSION")),
                ("state", language.text(self.phase.label())),
                ("session", &elapsed(self.snapshot.elapsed_secs)),
                ("interface", language.text(if self.snapshot.interface_ready { "Yes" } else { "No" })),
                ("latency", &self.snapshot.latency_ms.to_string()),
                ("networks", &self.snapshot.networks.len().to_string()),
                ("peers", &self.snapshot.peers.len().to_string()),
                ("connected", &connected.to_string()),
                ("received", &self.snapshot.traffic.received_bytes.to_string()),
                ("sent", &self.snapshot.traffic.sent_bytes.to_string()),
                ("frames_in", &self.snapshot.traffic.received_frames.to_string()),
                ("frames_out", &self.snapshot.traffic.sent_frames.to_string()),
                ("filtered", &self.snapshot.traffic.dropped.to_string()),
                ("restricted", language.text(if self.snapshot.restricted_traffic { "Yes" } else { "No" })),
            ],
        )
    }
}
impl eframe::App for App {
    fn logic(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        if self.first_frame {
            crate::startup_log::checkpoint(crate::startup_log::Stage::FirstLogic);
        }
        self.consume(ctx);
        if ctx.input(|i| i.viewport().close_requested()) && !self.stopped {
            ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
            if !self.closing {
                crate::startup_log::checkpoint(crate::startup_log::Stage::CloseRequested);
                self.closing = true;
                self.phase = Phase::Disconnecting;
                self.backend.send(Action::Shutdown);
            }
        }
        if self.closing && self.stopped {
            crate::startup_log::checkpoint(crate::startup_log::Stage::BackendStopped);
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }
    }
    fn ui(&mut self, ui: &mut egui::Ui, _: &mut eframe::Frame) {
        if self.first_frame {
            crate::startup_log::checkpoint(crate::startup_log::Stage::FirstUi);
        }
        self.show(ui);
        if self.first_frame {
            crate::startup_log::checkpoint(crate::startup_log::Stage::FirstUiCompleted);
            self.first_frame = false;
        }
    }
}
impl App {
    fn show(&mut self, ui: &mut egui::Ui) {
        let language = self.language();
        let ctx = ui.ctx().clone();
        if ctx.input_mut(|i| i.consume_key(egui::Modifiers::CTRL, egui::Key::K)) {
            self.page = Page::Discover;
            self.focus_search = true;
        }
        if ctx.input_mut(|i| i.consume_key(egui::Modifiers::CTRL, egui::Key::D)) {
            self.toggle();
        }
        if ctx.input_mut(|i| i.consume_key(egui::Modifiers::CTRL, egui::Key::Comma)) {
            self.page = Page::Settings;
        }
        self.sidebar(ui);
        egui::Panel::top("page_header")
            .frame(
                egui::Frame::new()
                    .fill(BG)
                    .inner_margin(egui::Margin::symmetric(28, 16)),
            )
            .show(ui, |ui| {
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new(match self.page {
                            Page::Networks => language.text("WORKSPACE / MY NETWORKS"),
                            Page::Discover => language.text("WORKSPACE / DISCOVER"),
                            Page::Settings => language.text("WORKSPACE / SETTINGS"),
                        })
                        .size(10.)
                        .color(MUTED),
                    );
                    ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                        badge(
                            ui,
                            if self.snapshot.interface_ready {
                                language.text("Connected")
                            } else {
                                language.text(if self.windows_platform() {
                                    "Windows preview"
                                } else {
                                    "Linux preview"
                                })
                            },
                            if self.snapshot.interface_ready {
                                MINT
                            } else {
                                MUTED
                            },
                        );
                    });
                });
            });
        egui::CentralPanel::default()
            .frame(
                egui::Frame::new()
                    .fill(BG)
                    .inner_margin(egui::Margin::symmetric(28, 12)),
            )
            .show(ui, |ui| {
                egui::ScrollArea::vertical()
                    .id_salt(match self.page { Page::Networks => 0, Page::Discover => 1, Page::Settings => 2 })
                    .auto_shrink([false, false])
                    .show(ui, |ui| {
                        ui.set_min_width(ui.available_width());
                        if self.snapshot.restricted_traffic {
                            self.banner(ui, language.text("Controlled test mode · application traffic is restricted to the selected test peers"), AMBER);
                            ui.add_space(12.);
                        }
                        match self.page {
                            Page::Networks => self.networks(ui),
                            Page::Discover => self.discover(ui),
                            Page::Settings => self.settings(ui),
                        }
                        ui.add_space(20.);
                    });
            });
        if let Some((message, error, at)) = self.toast.clone() {
            if at.elapsed() < Duration::from_secs(9) {
                egui::Area::new(egui::Id::new("notification"))
                    .anchor(egui::Align2::RIGHT_BOTTOM, [-24., -20.])
                    .order(egui::Order::Foreground)
                    .show(&ctx, |ui| {
                        ui.set_max_width(420.);
                        self.banner(ui, &message, if error { RED } else { MINT });
                    });
            } else {
                self.toast = None;
            }
        }
        self.network_dialogs(&ctx);
        if self.confirm_reset && !self.closing {
            egui::Modal::new(egui::Id::new("reset-identity")).show(&ctx, |ui| {
                ui.set_max_width(430.);
                ui.heading(language.text("Reset device identity?"));
                ui.label(language.text("OpenRad will disconnect this session and create a new device identity and VPN address. Your network memberships belong to the old identity; you will need to join networks again."));
                ui.add_space(8.);
                ui.label(language.text("The saved identity is replaced only after provisioning succeeds. Once saved, the old identity cannot be recovered from this profile."));
                ui.add_space(12.);
                ui.horizontal(|ui| {
                    if ui.button(language.text("Cancel")).clicked() { self.confirm_reset = false; }
                    if ui.button(RichText::new(language.text("Disconnect and reset identity")).color(AMBER)).clicked() {
                        self.confirm_reset = false;
                        self.phase = Phase::Resetting;
                        self.backend.send(Action::ResetIdentity);
                    }
                });
            });
        }
        if self.closing {
            egui::Modal::new(egui::Id::new("closing")).show(&ctx, |ui| {
                ui.heading(language.text("Closing your connection"));
                ui.label(
                    language.text("Finishing identity storage and removing the VPN interface…"),
                );
                ui.spinner();
            });
        }
        ctx.request_repaint_after(Duration::from_secs(1));
    }
}

pub(crate) fn configure(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    fonts.font_data.insert(
        "openrad_noto".into(),
        std::sync::Arc::new(egui::FontData::from_static(include_bytes!(
            "../assets/NotoSans-Regular.ttf"
        ))),
    );
    fonts
        .families
        .entry(egui::FontFamily::Proportional)
        .or_default()
        .insert(0, "openrad_noto".into());
    fonts
        .families
        .entry(egui::FontFamily::Monospace)
        .or_default()
        .push("openrad_noto".into());
    ctx.set_fonts(fonts);
    let mut style = egui::Style {
        visuals: egui::Visuals::dark(),
        ..Default::default()
    };
    style.visuals.override_text_color = Some(TEXT);
    style.visuals.panel_fill = BG;
    style.visuals.window_fill = SURFACE;
    style.visuals.extreme_bg_color = BG;
    style.visuals.faint_bg_color = RAISED;
    style.visuals.selection.bg_fill = Color32::from_rgb(37, 79, 67);
    style.visuals.selection.stroke = Stroke::new(1., MINT);
    style.visuals.widgets.noninteractive.bg_stroke = Stroke::new(1., BORDER);
    style.visuals.widgets.inactive.weak_bg_fill = RAISED;
    style.visuals.widgets.inactive.bg_fill = RAISED;
    style.visuals.widgets.inactive.bg_stroke = Stroke::new(1., BORDER);
    style.visuals.widgets.hovered.bg_fill = Color32::from_rgb(41, 56, 68);
    style.visuals.widgets.hovered.weak_bg_fill = Color32::from_rgb(41, 56, 68);
    style.visuals.widgets.hovered.bg_stroke = Stroke::new(1., MINT);
    style.visuals.widgets.active.bg_stroke = Stroke::new(1., MINT);
    style.spacing.item_spacing = Vec2::new(10., 8.);
    style.spacing.button_padding = Vec2::new(13., 8.);
    style.spacing.interact_size = Vec2::new(40., 32.);
    style
        .text_styles
        .insert(egui::TextStyle::Body, FontId::proportional(14.));
    style
        .text_styles
        .insert(egui::TextStyle::Button, FontId::proportional(13.));
    style
        .text_styles
        .insert(egui::TextStyle::Small, FontId::proportional(11.));
    ctx.set_theme(egui::Theme::Dark);
    ctx.set_style_of(egui::Theme::Dark, style);
}
fn card() -> egui::Frame {
    egui::Frame::new()
        .fill(SURFACE)
        .stroke(Stroke::new(1., BORDER))
        .corner_radius(12)
        .inner_margin(18)
}
fn primary(label: &str) -> egui::Button<'_> {
    egui::Button::new(
        RichText::new(label)
            .color(Color32::from_rgb(14, 42, 33))
            .strong(),
    )
    .fill(MINT)
    .corner_radius(8)
    .stroke(Stroke::NONE)
}
fn badge(ui: &mut egui::Ui, text: &str, color: Color32) -> egui::Response {
    egui::Frame::new()
        .fill(color.gamma_multiply(0.12))
        .corner_radius(6)
        .inner_margin(egui::Margin::symmetric(9, 5))
        .show(ui, |ui| {
            ui.label(RichText::new(text).size(11.).color(color))
        })
        .response
}
fn dot(ui: &mut egui::Ui, color: Color32) {
    let (rect, _) = ui.allocate_exact_size(Vec2::new(12., 14.), egui::Sense::hover());
    ui.painter().circle_filled(rect.center(), 3., color);
}
fn logo(ui: &mut egui::Ui, size: f32) {
    let (rect, _) = ui.allocate_exact_size(Vec2::splat(size), egui::Sense::hover());
    let c = rect.center();
    for offset in [-7., 7.] {
        ui.painter()
            .circle_stroke(c + Vec2::new(offset, 0.), 8., Stroke::new(2.8, MINT));
    }
}
fn metric(
    ui: &mut egui::Ui,
    label: &str,
    value: &str,
    sub: &str,
    color: Color32,
    series: Option<&VecDeque<f32>>,
) {
    card().inner_margin(16).show(ui, |ui| {
        ui.set_min_width(ui.available_width());
        ui.set_min_height(103.);
        ui.label(RichText::new(label).size(10.).color(MUTED));
        ui.label(RichText::new(value).size(23.).color(color));
        ui.add(egui::Label::new(RichText::new(sub).size(10.).color(MUTED)).truncate())
            .on_hover_text(sub);
        if let Some(values) = series {
            let (rect, _) =
                ui.allocate_exact_size(Vec2::new(ui.available_width(), 22.), egui::Sense::hover());
            let max = values.iter().copied().fold(128f32, f32::max);
            let points = (0..60)
                .map(|i| {
                    let j = i as isize - (60 - values.len()) as isize;
                    let value = if j >= 0 { values[j as usize] } else { 0. };
                    egui::pos2(
                        rect.left() + rect.width() * i as f32 / 59.,
                        rect.bottom() - 2. - value / max * (rect.height() - 4.),
                    )
                })
                .collect();
            ui.painter().add(egui::Shape::line(
                points,
                Stroke::new(1.5, color.gamma_multiply(0.7)),
            ));
        }
    });
}
fn peer_color(state: &PeerState) -> Color32 {
    match state {
        PeerState::Connected => MINT,
        PeerState::Online | PeerState::Connecting => BLUE,
        PeerState::Refused => AMBER,
        PeerState::Failed => RED,
        _ => MUTED,
    }
}
fn peer_rank(state: &PeerState) -> u8 {
    match state {
        PeerState::Connected => 0,
        PeerState::Connecting => 1,
        PeerState::Online => 2,
        PeerState::Failed => 3,
        PeerState::Refused => 4,
        PeerState::Unavailable => 5,
        PeerState::Offline => 6,
    }
}
fn visible_peers<'a>(
    snapshot: &'a Snapshot,
    network: Option<&str>,
    filter: &str,
    settings: &Settings,
) -> Vec<&'a PeerView> {
    let filter = filter.to_lowercase();
    let mut peers: Vec<_> = snapshot
        .peers
        .values()
        .filter(|p| {
            network.is_none_or(|id| p.peer.network_ids.contains(id))
                && (settings.show_offline_peers || p.status != PeerState::Offline)
                && (filter.is_empty()
                    || p.peer.name.to_lowercase().contains(&filter)
                    || p.peer.vip.to_string().contains(&filter))
        })
        .collect();
    match settings.peer_sort {
        PeerSort::Name => peers.sort_by_cached_key(|p| p.peer.name.to_lowercase()),
        PeerSort::Status => {
            peers.sort_by_cached_key(|p| (peer_rank(&p.status), p.peer.name.to_lowercase()))
        }
        PeerSort::Address => peers.sort_by_key(|p| (p.peer.vip, p.peer.rid)),
    }
    peers
}
fn bytes(n: u64, decimal: bool) -> String {
    let base = if decimal { 1000 } else { 1024 };
    if n >= base * base {
        format!(
            "{:.1} {}",
            n as f64 / (base * base) as f64,
            if decimal { "MB" } else { "MiB" }
        )
    } else if n >= base {
        format!(
            "{:.1} {}",
            n as f64 / base as f64,
            if decimal { "kB" } else { "KiB" }
        )
    } else {
        format!("{n} B")
    }
}
fn rate(n: f32, decimal: bool) -> String {
    format!("{}/s", bytes(n.max(0.) as u64, decimal))
}
fn elapsed(n: u64) -> String {
    format!("{:02}:{:02}:{:02}", n / 3600, n / 60 % 60, n % 60)
}

#[cfg(test)]
mod tests {
    use super::*;
    use openrad::protocol::Peer;
    use std::net::Ipv4Addr;

    struct Fixture {
        app: Option<App>,
        directory: PathBuf,
    }
    impl Fixture {
        fn new() -> Self {
            let directory = std::env::temp_dir().join(format!(
                "openrad-language-ui-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos(),
            ));
            let paths = Paths::new(Some(directory.clone())).unwrap();
            let lock = paths.lock().unwrap();
            let mut app = App::from_parts(Backend::fixture(), paths, lock, Settings::default());
            app.system_language = Language::Portuguese;
            app.phase = Phase::Disconnected;
            Self {
                app: Some(app),
                directory,
            }
        }
        fn app(&mut self) -> &mut App {
            self.app.as_mut().unwrap()
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            drop(self.app.take());
            std::fs::remove_dir_all(&self.directory).unwrap();
        }
    }
    fn frame(ctx: &egui::Context, app: &mut App, events: Vec<egui::Event>) -> egui::FullOutput {
        let mut output = ctx.run_ui(
            egui::RawInput {
                screen_rect: Some(egui::Rect::from_min_size(
                    egui::Pos2::ZERO,
                    Vec2::new(780., 540.),
                )),
                events,
                ..Default::default()
            },
            |ui| app.show(ui),
        );
        output.textures_delta.clear();
        output
    }
    fn labels(output: &egui::FullOutput) -> Vec<String> {
        output
            .platform_output
            .accesskit_update
            .as_ref()
            .unwrap()
            .nodes
            .iter()
            .filter_map(|(_, node)| node.label().or_else(|| node.value()).map(str::to_owned))
            .collect()
    }

    #[test]
    fn windows_guidance_uses_the_driver_and_privilege_requirements_in_every_language() {
        let mut fixture = Fixture::new();
        for preference in [
            LanguagePreference::English,
            LanguagePreference::Portuguese,
            LanguagePreference::Russian,
            LanguagePreference::Vietnamese,
        ] {
            let app = fixture.app();
            app.windows_preview = true;
            app.settings.language = preference;
            app.page = Page::Settings;
            let ctx = egui::Context::default();
            configure(&ctx);
            ctx.enable_accesskit();
            let mut textures = Vec::new();
            let mut output = None;
            for _ in 0..2 {
                let mut current = ctx.run_ui(
                    egui::RawInput {
                        screen_rect: Some(egui::Rect::from_min_size(
                            egui::Pos2::ZERO,
                            Vec2::new(1120., 2600.),
                        )),
                        ..Default::default()
                    },
                    |ui| app.show(ui),
                );
                for (id, deltas) in &current.textures_delta.set {
                    for delta in deltas {
                        let egui::ImageData::Color(image) = &delta.image;
                        textures.push(serde_json::json!({"id":format!("{id:?}"),"size":image.size,"pos":delta.pos,"pixels":image.pixels.iter().map(|p|p.to_array()).collect::<Vec<_>>() }));
                    }
                }
                current.textures_delta.clear();
                output = Some(current);
            }
            let output = output.unwrap();
            let language = app.language();
            let text = labels(&output);
            assert!(text
                .iter()
                .any(|label| label == language.text("Windows interface")));
            assert!(text.iter().any(|label| label.contains("TAP-Windows6")));
            assert!(!text
                .iter()
                .any(|label| label == language.text("Linux interface")));
            if preference == LanguagePreference::English {
                if let Ok(path) = std::env::var("OPENRAD_UI_CAPTURE_PATH") {
                    let primitives = ctx.tessellate(output.shapes, output.pixels_per_point);
                    let meshes: Vec<_> = primitives.into_iter().filter_map(|p| {
                        let egui::epaint::Primitive::Mesh(mesh) = p.primitive else { return None; };
                        Some(serde_json::json!({"clip":[p.clip_rect.min.x,p.clip_rect.min.y,p.clip_rect.max.x,p.clip_rect.max.y],"texture":format!("{:?}",mesh.texture_id),"indices":mesh.indices,"vertices":mesh.vertices.iter().map(|v|serde_json::json!([v.pos.x,v.pos.y,v.uv.x,v.uv.y,v.color.to_array()])).collect::<Vec<_>>() }))
                    }).collect();
                    std::fs::write(path, serde_json::to_vec(&serde_json::json!({"width":1120,"height":2600,"textures":textures,"meshes":meshes})).unwrap()).unwrap();
                }
            }
        }
    }

    #[test]
    fn embedded_font_covers_every_translation() {
        use skrifa::MetadataProvider;
        let font = skrifa::FontRef::new(include_bytes!("../assets/NotoSans-Regular.ttf")).unwrap();
        let charmap = font.charmap();
        let catalog: std::collections::BTreeMap<String, [String; 3]> =
            serde_json::from_str(include_str!("../../locales/messages.json")).unwrap();
        for (source, translations) in &catalog {
            for text in std::iter::once(source).chain(translations.iter()) {
                for character in text.chars().filter(|c| !c.is_control()) {
                    assert!(
                        charmap.map(character).is_some(),
                        "missing glyph {character:?}: {text}"
                    );
                }
            }
        }
        for language in Language::ALL {
            assert!(language.name().chars().all(|c| charmap.map(c).is_some()));
        }
    }

    #[test]
    fn every_page_and_connection_phase_uses_the_selected_language() {
        let mut fixture = Fixture::new();
        for preference in LanguagePreference::ALL {
            let app = fixture.app();
            app.settings.language = preference;
            let language = app.language();
            for (page, heading) in [
                (Page::Networks, "Your networks"),
                (Page::Discover, "Find your people."),
                (Page::Settings, "Language"),
            ] {
                app.page = page;
                let ctx = egui::Context::default();
                configure(&ctx);
                ctx.enable_accesskit();
                frame(&ctx, app, vec![]);
                let output = frame(&ctx, app, vec![]);
                let labels = labels(&output);
                assert!(
                    labels.iter().any(|s| s == language.text(heading)),
                    "missing {heading} in {language:?}: {labels:?}"
                );
                if language != Language::English {
                    assert!(
                        !labels.iter().any(|s| s == heading),
                        "English heading in {language:?}"
                    );
                }
                for (_, node) in &output
                    .platform_output
                    .accesskit_update
                    .as_ref()
                    .unwrap()
                    .nodes
                {
                    if node.role() == egui::accesskit::Role::Button {
                        if let Some(bounds) = node.bounds() {
                            assert!(
                                bounds.x0 >= 0. && bounds.x1 <= 780.,
                                "button overflows: {:?} ({language:?}) {bounds:?}",
                                node.label()
                            );
                        }
                    }
                }
            }
            for (phase, heading) in [
                (Phase::Loading, "Getting ready"),
                (Phase::Provisioning, "Creating your identity"),
                (Phase::Resetting, "Resetting your identity"),
                (Phase::Connecting, "Connecting"),
                (Phase::Connected, "You’re connected"),
                (Phase::Disconnecting, "Disconnecting"),
                (Phase::Error, "Connection needs attention"),
                (Phase::Disconnected, "Ready to connect"),
            ] {
                app.page = Page::Networks;
                app.phase = phase;
                app.snapshot.interface_ready = app.phase == Phase::Connected;
                let ctx = egui::Context::default();
                configure(&ctx);
                ctx.enable_accesskit();
                frame(&ctx, app, vec![]);
                let output = frame(&ctx, app, vec![]);
                assert!(
                    labels(&output).iter().any(|s| s == language.text(heading)),
                    "phase heading {heading} in {language:?}"
                );
            }
            app.phase = Phase::Disconnected;
            app.snapshot.interface_ready = false;
        }
    }

    #[test]
    fn language_previews_discard_defaults_and_persistence_keep_their_behavior() {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        assert_eq!(app.language(), Language::Portuguese);
        app.page = Page::Settings;
        app.saved_settings.language = LanguagePreference::English;
        app.settings.language = LanguagePreference::Vietnamese;
        let ctx = egui::Context::default();
        configure(&ctx);
        ctx.enable_accesskit();
        frame(&ctx, app, vec![]);
        let output = frame(&ctx, app, vec![]);
        assert!(labels(&output)
            .iter()
            .any(|s| s == Language::Vietnamese.text("Language")));
        let bounds = output
            .platform_output
            .accesskit_update
            .unwrap()
            .nodes
            .iter()
            .find(|(_, node)| {
                node.role() == egui::accesskit::Role::Button
                    && node.label() == Some(Language::Vietnamese.text("Discard changes"))
            })
            .unwrap()
            .1
            .bounds()
            .unwrap();
        // Preferences actions are below the fold; scrolling is covered by egui.
        // Verify persistence through the real atomic settings store instead.
        assert!(bounds.x1 <= 780.);
        for preference in LanguagePreference::ALL {
            app.settings.language = preference;
            app.paths.save_settings(&app.settings).unwrap();
            assert_eq!(app.paths.settings().unwrap().language, preference);
        }
        app.settings = app.saved_settings.clone();
        assert_eq!(app.language(), Language::English);
        app.settings = Settings::default();
        assert_eq!(app.language(), Language::Portuguese);
    }

    #[test]
    fn language_changes_retranslate_retained_activity_without_changing_peer_names() {
        let event = Activity::Peer {
            name: "Connected {error} Русский".into(),
            status: PeerState::Refused,
            detail: "Channel closed · Retry queued (at least 4s; adaptive recovery)".into(),
        };
        for language in Language::ALL {
            let text = event.text(language);
            assert!(text.starts_with("Connected {error} Русский: "));
            assert!(text.contains(language.text("Refused")));
            assert!(text.contains(language.text("Channel closed")));
            if language != Language::English {
                assert!(!text.contains("Retry queued"));
            }
        }
    }

    #[test]
    fn language_selector_changes_the_live_ui_and_preserves_unsaved_preferences() {
        fn click_text(ctx: &egui::Context, app: &mut App, text: &str) {
            frame(ctx, app, vec![]);
            let output = frame(ctx, app, vec![]);
            let bounds = output
                .platform_output
                .accesskit_update
                .as_ref()
                .unwrap()
                .nodes
                .iter()
                .find(|(_, node)| node.label().or_else(|| node.value()) == Some(text))
                .unwrap_or_else(|| panic!("missing selectable text: {text}"))
                .1
                .bounds()
                .unwrap();
            let pos = egui::pos2(
                ((bounds.x0 + bounds.x1) / 2.) as f32,
                ((bounds.y0 + bounds.y1) / 2.) as f32,
            );
            frame(
                ctx,
                app,
                vec![
                    egui::Event::PointerMoved(pos),
                    egui::Event::PointerButton {
                        pos,
                        button: egui::PointerButton::Primary,
                        pressed: true,
                        modifiers: Default::default(),
                    },
                ],
            );
            frame(
                ctx,
                app,
                vec![egui::Event::PointerButton {
                    pos,
                    button: egui::PointerButton::Primary,
                    pressed: false,
                    modifiers: Default::default(),
                }],
            );
        }
        let mut fixture = Fixture::new();
        let app = fixture.app();
        app.page = Page::Settings;
        let ctx = egui::Context::default();
        configure(&ctx);
        ctx.enable_accesskit();
        click_text(
            &ctx,
            app,
            LanguagePreference::System.label(Language::Portuguese),
        );
        click_text(&ctx, app, Language::Russian.name());
        assert_eq!(app.settings.language, LanguagePreference::Russian);
        assert_eq!(app.saved_settings.language, LanguagePreference::System);
        let output = frame(&ctx, app, vec![]);
        assert!(labels(&output)
            .iter()
            .any(|s| s == Language::Russian.text("Language")));
        click_text(&ctx, app, Language::Russian.name());
        click_text(&ctx, app, Language::Vietnamese.name());
        assert_eq!(app.language(), Language::Vietnamese);
        click_text(&ctx, app, Language::Vietnamese.name());
        click_text(
            &ctx,
            app,
            LanguagePreference::System.label(Language::Vietnamese),
        );
        assert_eq!(app.language(), Language::Portuguese);
    }

    #[test]
    fn translated_peer_management_and_catalog_actions_fit_the_minimum_window() {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        app.phase = Phase::Connected;
        app.snapshot.interface_ready = true;
        app.identity = Some((99, "Synthetic device".into()));
        app.selected_network = Some("n".into());
        app.snapshot.networks.push(openrad::protocol::Network {
            name: "Connected {name} · 雪".into(),
            network_id: "n".into(),
        });
        app.snapshot
            .roles
            .insert("n".into(), [(99, 2), (2, 2)].into_iter().collect());
        app.snapshot.peers.insert(
            2,
            PeerView {
                peer: Peer {
                    rid: 2,
                    name: "Connected {error} Русский".into(),
                    vip: "26.0.0.2".parse().unwrap(),
                    server: None,
                    state: 1,
                    network_ids: ["n".into()].into_iter().collect(),
                },
                status: PeerState::Connected,
                detail: "Authenticated Direct UDP · Incoming".into(),
                transport: Some(openrad::peer::TransportPath::DirectUdp),
            },
        );
        app.has_searched = true;
        app.catalog.push(PublicNetwork {
            name: "Synthetic public network".into(),
            reported_count: 42,
        });
        for preference in LanguagePreference::ALL {
            app.settings.language = preference;
            let language = app.language();
            for page in [Page::Networks, Page::Discover] {
                app.page = page;
                let ctx = egui::Context::default();
                configure(&ctx);
                ctx.enable_accesskit();
                frame(&ctx, app, vec![]);
                let output = frame(&ctx, app, vec![]);
                for (_, node) in &output
                    .platform_output
                    .accesskit_update
                    .as_ref()
                    .unwrap()
                    .nodes
                {
                    if node.role() == egui::accesskit::Role::Button {
                        if let Some(bounds) = node.bounds() {
                            assert!(
                                bounds.x0 >= 0. && bounds.x1 <= 780.,
                                "overflow in {language:?}: {:?} {bounds:?}",
                                node.label()
                            );
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn peer_view_preserves_unicode_filtering_sort_order_and_membership() {
        let mut snapshot = Snapshot::default();
        for (rid, name, status, network, address) in [
            (1, "Zeta", PeerState::Offline, "a", 3),
            (2, "áLPHA", PeerState::Connected, "a", 2),
            (3, "alpha", PeerState::Connected, "b", 1),
            (4, "ALPHA", PeerState::Connected, "a", 2),
            (5, "Beta", PeerState::Failed, "a", 4),
        ] {
            snapshot.peers.insert(
                rid,
                PeerView {
                    peer: Peer {
                        rid,
                        name: name.into(),
                        vip: Ipv4Addr::new(26, 0, 0, address),
                        server: None,
                        state: 1,
                        network_ids: [network.into()].into_iter().collect(),
                    },
                    status,
                    detail: String::new(),
                    transport: None,
                },
            );
        }
        let ids = |network, filter, settings: &Settings| {
            visible_peers(&snapshot, network, filter, settings)
                .iter()
                .map(|p| p.peer.rid)
                .collect::<Vec<_>>()
        };
        let mut settings = Settings {
            show_offline_peers: true,
            peer_sort: PeerSort::Name,
            ..Settings::default()
        };
        assert_eq!(ids(None, "", &settings), [3, 4, 5, 1, 2]);
        settings.peer_sort = PeerSort::Status;
        assert_eq!(ids(None, "", &settings), [3, 4, 2, 5, 1]);
        settings.peer_sort = PeerSort::Address;
        assert_eq!(ids(None, "", &settings), [3, 2, 4, 1, 5]);
        settings.peer_sort = PeerSort::Name;
        settings.show_offline_peers = false;
        assert_eq!(ids(Some("a"), "", &settings), [4, 5, 2]);
        assert_eq!(ids(Some("a"), "Ál", &settings), [2]);
        assert_eq!(ids(None, "26.0.0.2", &settings), [4, 2]);
        assert_eq!(ids(Some("b"), "ALPHA", &settings), [3]);
        assert!(ids(Some("missing"), "", &settings).is_empty());
    }
}
