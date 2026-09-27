use crate::{
    backend::{Action, Backend, Notice, Phase},
    storage::{Paths, Settings},
};
use eframe::egui::{self, Align, Color32, FontId, RichText, Stroke, Vec2};
use openrad::{
    protocol::PublicNetwork,
    runtime::{self, Command, PeerState, Snapshot, Update},
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
pub struct App {
    backend: Backend,
    _lock: File,
    paths: Paths,
    phase: Phase,
    message: String,
    identity: Option<(u64, String)>,
    settings: Settings,
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
    activity: VecDeque<String>,
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
}
impl App {
    pub fn new(
        cc: &eframe::CreationContext<'_>,
        paths: Paths,
        lock: File,
        import: Option<PathBuf>,
        options: runtime::Options,
    ) -> Self {
        configure(&cc.egui_ctx);
        let ctx = cc.egui_ctx.clone();
        let backend = Backend::spawn(paths.clone(), import, options, move || {
            ctx.request_repaint()
        });
        Self {
            backend,
            _lock: lock,
            paths,
            phase: Phase::Loading,
            message: "Opening your credential store…".into(),
            identity: None,
            settings: Settings::default(),
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
    fn log(&mut self, text: String) {
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
                            self.log(format!("{}: {}", peer.peer.name, peer.status.label()));
                        }
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
    fn connected(&self) -> bool {
        self.phase == Phase::Connected
    }
    fn command(&mut self, command: Command) {
        if matches!(
            command,
            Command::Search { .. } | Command::Join(_) | Command::Leave(_)
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
                ui.label(RichText::new("YOUR NETWORK, CLOSER").size(9.).color(MUTED));
                ui.add_space(38.);
                for (page, label) in [
                    (Page::Networks, "My networks"),
                    (Page::Discover, "Discover"),
                    (Page::Settings, "Settings"),
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
                ui.label(RichText::new("MEMBERSHIPS").size(10.).color(MUTED));
                ui.add_space(10.);
                let networks = self.snapshot.networks.clone();
                if networks.is_empty() {
                    ui.label(RichText::new("No networks yet").size(12.).color(MUTED));
                }
                for n in networks.iter().take(8) {
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
                        RichText::new("Native Linux · v0.1.0")
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
                                "radminvpn0 active"
                            } else {
                                "Interface offline"
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
                                .unwrap_or("This device"),
                        )
                        .size(12.)
                        .color(TEXT),
                    );
                });
            });
    }
    fn hero(&mut self, ui: &mut egui::Ui) {
        let (title, caption, color) = match self.phase {
            Phase::Loading => ("Getting ready", "Opening your saved identity", BLUE),
            Phase::Provisioning => (
                "Creating your identity",
                "One private identity, saved for your next connection",
                BLUE,
            ),
            Phase::Resetting => (
                "Resetting your identity",
                "Disconnecting, provisioning and saving your replacement",
                BLUE,
            ),
            Phase::Connecting => ("Connecting", "Authenticating with the network", BLUE),
            Phase::Connected if self.snapshot.interface_error.is_some() => (
                "Interface needs attention",
                "Your account is connected; application traffic is paused",
                AMBER,
            ),
            Phase::Connected if !self.snapshot.interface_ready => (
                "Preparing your interface",
                "Your account is connected",
                BLUE,
            ),
            Phase::Connected => ("You’re connected", "Your devices are within reach", MINT),
            Phase::Disconnecting => (
                "Disconnecting",
                "Closing channels and removing the interface",
                BLUE,
            ),
            Phase::Error => (
                "Connection needs attention",
                "Your saved identity is kept safe",
                RED,
            ),
            Phase::Disconnected => (
                "Ready to connect",
                "Bring your devices onto the same network",
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
                ui.vertical(|ui| {
                    ui.label(RichText::new(title).size(25.).strong().color(TEXT));
                    ui.add_space(3.);
                    ui.label(RichText::new(caption).size(12.).color(MUTED));
                });
                ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                    let can_toggle = matches!(
                        self.phase,
                        Phase::Disconnected | Phase::Error | Phase::Connected | Phase::Connecting
                    );
                    let label = if matches!(self.phase, Phase::Connected | Phase::Connecting) {
                        "Disconnect"
                    } else {
                        "Connect"
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
                ui.label(RichText::new("VPN ADDRESS").size(10.).color(MUTED));
                let ip = if self.connected() {
                    self.snapshot
                        .vip
                        .map(|i| i.to_string())
                        .unwrap_or_else(|| "Awaiting assignment".into())
                } else {
                    "—".into()
                };
                ui.label(RichText::new(&ip).monospace().size(16.).color(color));
                if self.connected() && ui.small_button("Copy").clicked() {
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
                            RichText::new("IPv4 · per-peer transport")
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
                ui.label(RichText::new(&error).color(AMBER));
                ui.label(RichText::new("Linux: authorize the setup helper with sudo -v in the launching terminal, then retry. The desktop stays unprivileged.").size(12.).color(MUTED));
                ui.add_space(6.); if ui.button("Retry interface setup").clicked() { self.command(Command::RetryInterface); }
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
                ui.label(RichText::new(message).size(12.).color(color));
            });
    }
    fn traffic(&self, ui: &mut egui::Ui) {
        ui.add_space(16.);
        ui.columns(3, |cols| {
            metric(
                &mut cols[0],
                "DOWNLOAD",
                &rate(self.rates.0),
                &format!("{} received", bytes(self.snapshot.traffic.received_bytes)),
                MINT,
                Some(&self.download),
            );
            metric(
                &mut cols[1],
                "UPLOAD",
                &rate(self.rates.1),
                &format!("{} sent", bytes(self.snapshot.traffic.sent_bytes)),
                BLUE,
                Some(&self.upload),
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
                "PEER CONNECTIONS",
                &format!("{active} / {}", self.snapshot.peers.len()),
                &format!(
                    "{queued} pending · {} filtered frames",
                    self.snapshot.traffic.dropped
                ),
                TEXT,
                None,
            );
        });
    }
    fn networks(&mut self, ui: &mut egui::Ui) {
        self.hero(ui);
        self.traffic(ui);
        ui.add_space(25.);
        ui.horizontal(|ui| {
            ui.label(RichText::new("Your networks").size(19.).strong());
            ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                if ui.add(primary("+ Join a network")).clicked() {
                    self.page = Page::Discover;
                    self.focus_search = true;
                }
            });
        });
        ui.add_space(12.);
        if self.snapshot.networks.is_empty() {
            card().inner_margin(28).show(ui, |ui| {
                ui.set_min_width(ui.available_width());
                ui.label(RichText::new(if self.connected() { "Make your first connection" } else { "Your networks will appear here" }).size(19.).strong());
                ui.add_space(8.); ui.label(RichText::new("Find a public network, join it, and OpenRad connects to available members.").color(MUTED));
                ui.add_space(12.); if ui.add_enabled(self.connected(), primary("Explore public networks")).clicked() { self.page = Page::Discover; self.focus_search = true; }
            });
            return;
        }
        ui.horizontal_wrapped(|ui| {
            if ui
                .selectable_label(self.selected_network.is_none(), "All networks")
                .clicked()
            {
                self.selected_network = None;
            }
            for n in self.snapshot.networks.clone() {
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
        ui.horizontal(|ui| {
            ui.add(
                egui::TextEdit::singleline(&mut self.peer_filter)
                    .hint_text("Filter peers by name or VPN address")
                    .desired_width((ui.available_width() - 130.).max(180.)),
            );
            if ui
                .add_enabled(self.connected(), egui::Button::new("Retry failed peers"))
                .clicked()
            {
                self.command(Command::RetryPeers);
            }
        });
        if let Some(id) = &self.selected_network {
            if let Some(n) = self
                .snapshot
                .networks
                .iter()
                .find(|n| &n.network_id == id)
                .cloned()
            {
                ui.add_space(10.);
                ui.horizontal(|ui| {
                    ui.label(RichText::new(&n.name).strong());
                    ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                        if ui
                            .add_enabled(
                                self.connected() && !self.busy,
                                egui::Button::new(RichText::new("Leave network").color(RED)),
                            )
                            .clicked()
                        {
                            self.command(Command::Leave(n.network_id));
                            self.selected_network = None;
                        }
                    });
                });
            }
        }
        ui.add_space(10.);
        let filter = self.peer_filter.to_lowercase();
        let peers: Vec<_> = self
            .snapshot
            .peers
            .values()
            .filter(|p| {
                self.selected_network
                    .as_ref()
                    .is_none_or(|id| p.peer.network_ids.contains(id))
                    && (p.peer.name.to_lowercase().contains(&filter)
                        || p.peer.vip.to_string().contains(&filter))
            })
            .cloned()
            .collect();
        card().inner_margin(14).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            if peers.is_empty() {
                ui.add_space(15.);
                ui.label(
                    RichText::new(
                        "No peers match this view. Members will appear as the server reports them.",
                    )
                    .color(MUTED),
                );
                ui.add_space(15.);
            }
            for (index, peer) in peers.iter().enumerate() {
                ui.push_id(peer.peer.rid, |ui| {
                    ui.horizontal(|ui| {
                        let color = peer_color(&peer.status);
                        dot(ui, color);
                        let width = (ui.available_width() - 180.).max(120.);
                        ui.allocate_ui_with_layout(
                            Vec2::new(width, 43.),
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
                            },
                        );
                        ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                            badge(
                                ui,
                                peer.transport
                                    .map(|p| p.label())
                                    .unwrap_or(peer.status.label()),
                                color,
                            )
                            .on_hover_text(if peer.detail.is_empty() {
                                peer.status.label()
                            } else {
                                &peer.detail
                            });
                        });
                    });
                    if index + 1 < peers.len() {
                        ui.separator();
                    }
                });
            }
        });
        ui.add_space(12.);
        ui.label(RichText::new("Refused means the remote service declined the connection. Offline members are kept in your network list.").size(11.).color(MUTED));
        if !self.activity.is_empty() {
            ui.add_space(15.);
            egui::CollapsingHeader::new("Recent activity").show(ui, |ui| {
                for event in self.activity.iter().take(12) {
                    ui.label(RichText::new(event).size(11.).color(MUTED));
                }
            });
        }
    }
    fn discover(&mut self, ui: &mut egui::Ui) {
        ui.label(RichText::new("Find your people.").size(29.).strong());
        ui.add_space(8.);
        ui.label(
            RichText::new("Explore public networks and bring everyone onto the same LAN.")
                .color(MUTED),
        );
        ui.add_space(24.);
        let available = self.connected() && !self.busy;
        card().inner_margin(18).show(ui, |ui| {
            ui.horizontal(|ui| {
                let response = ui.add_enabled(
                    available,
                    egui::TextEdit::singleline(&mut self.query)
                        .hint_text("Search games, communities, or a network name")
                        .desired_width((ui.available_width() - 100.).max(180.)),
                );
                if self.focus_search {
                    response.request_focus();
                    self.focus_search = false;
                }
                let enter = response.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                if ui.add_enabled(available, primary("Search")).clicked() || available && enter {
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
                "Connect your device to search and join public networks.",
                AMBER,
            );
            if ui
                .add_enabled(
                    matches!(self.phase, Phase::Disconnected | Phase::Error),
                    primary("Connect"),
                )
                .clicked()
            {
                self.toggle();
            }
            return;
        }
        if !self.has_searched && !self.busy {
            ui.label(RichText::new("Start with a name, or browse what’s available.").size(18.));
            ui.add_space(12.);
            if ui.add(primary("Browse public networks")).clicked() {
                self.command(Command::Search {
                    query: String::new(),
                    cursor: 0,
                });
            }
        } else if self.catalog.is_empty() && !self.busy {
            ui.label(RichText::new("No networks found").size(21.).strong());
            ui.label(RichText::new("Try a shorter name or a different search.").color(MUTED));
        } else {
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new(format!("{} networks", self.catalog.len()))
                        .size(13.)
                        .color(MUTED),
                );
                if self.busy {
                    ui.spinner();
                    ui.label(RichText::new("Working…").color(MUTED));
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
                                        RichText::new(format!(
                                            "Public network  ·  {} members reported",
                                            network.reported_count
                                        ))
                                        .size(11.)
                                        .color(MUTED),
                                    );
                                },
                            );
                            ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                                if joined {
                                    badge(ui, "Joined", MINT);
                                } else if ui
                                    .add_enabled(!self.busy, primary("Join network"))
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
                    .add_enabled(!self.busy, egui::Button::new("Load more networks"))
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
        ui.label(RichText::new("Make yourself at home.").size(28.).strong());
        ui.add_space(8.);
        ui.label(
            RichText::new("Connection preferences, your identity, and the essentials.")
                .color(MUTED),
        );
        ui.add_space(25.);
        card().inner_margin(22).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(RichText::new("Connection").size(18.).strong());
            ui.add_space(12.);
            ui.checkbox(
                &mut self.settings.auto_connect,
                "Connect when OpenRad launches",
            );
            ui.checkbox(
                &mut self.settings.auto_reconnect,
                "Reconnect after a connection failure (up to 3 attempts)",
            );
            ui.add_space(14.);
            ui.label("Device name");
            ui.add_enabled(
                self.identity.is_none(),
                egui::TextEdit::singleline(&mut self.settings.node_name).desired_width(300.),
            );
            ui.label(
                RichText::new("The device name is set when your identity is created.")
                    .size(11.)
                    .color(MUTED),
            );
            ui.add_space(16.);
            ui.label("Interface scale");
            if ui
                .add(egui::Slider::new(&mut self.settings.scale, 0.8..=1.5).step_by(0.05))
                .changed()
            {
                ui.ctx().set_zoom_factor(self.settings.scale);
            }
            ui.add_space(12.);
            if ui.add(primary("Save preferences")).clicked() {
                self.backend.send(Action::Save(self.settings.clone()));
            }
        });
        ui.add_space(16.);
        card().inner_margin(22).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(RichText::new("Private device identity").size(18.).strong()); ui.add_space(12.);
            if let Some((rid, name)) = &self.identity { ui.label(format!("{name}  ·  Device {rid}")); }
            else { ui.label("No identity loaded"); }
            ui.label(RichText::new("Stored in your operating system’s credential store. The same identity is reused each time you connect.").size(12.).color(MUTED));
            ui.add_space(12.);
            let can_reset = self.identity.is_some() && !self.closing && matches!(self.phase, Phase::Connected | Phase::Disconnected | Phase::Error);
            let label = if self.replacement_pending { "Retry saving new identity" } else { "Reset identity…" };
            if ui.add_enabled(can_reset, egui::Button::new(RichText::new(label).color(AMBER))).clicked() {
                if self.replacement_pending { self.phase = Phase::Resetting; self.backend.send(Action::ResetIdentity); }
                else { self.confirm_reset = true; }
            }
            ui.add_space(12.);
            ui.label(RichText::new("Import an existing OpenRad identity").strong());
            ui.horizontal(|ui| {
                ui.add(egui::TextEdit::singleline(&mut self.import_path).hint_text("/path/to/identity.json").desired_width((ui.available_width()-90.).max(160.)));
                if ui.add_enabled(!self.connected() && self.identity.is_none() && !self.import_path.is_empty(), egui::Button::new("Import")).clicked() { self.backend.send(Action::Import(PathBuf::from(&self.import_path))); }
            });
        });
        ui.add_space(16.);
        card().inner_margin(22).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(RichText::new("Linux interface").size(18.).strong()); ui.add_space(10.);
            ui.label("radminvpn0 · Ethernet TAP · MTU 1400");
            ui.label(RichText::new("A short-lived helper configures the interface and member host routes. OpenRad runs as your normal user. Closing the app removes its interface and routes.").size(12.).color(MUTED));
            ui.add_space(10.); ui.label(RichText::new("If setup needs permission, run sudo -v in the terminal that launches OpenRad, then retry interface setup.").size(12.).color(MUTED));
            ui.add_space(10.); ui.label(RichText::new("Windows and macOS data planes are not implemented or tested. Each connected peer shows its authenticated transport. Direct candidates come from the server; relay remains available when direct connection fails.").size(12.).color(MUTED));
            ui.add_space(10.); ui.label(RichText::new(format!("Settings: {}", self.paths.directory.display())).size(11.).color(MUTED));
        });
        ui.add_space(16.);
        ui.label(RichText::new("Shortcuts: Ctrl+K search · Ctrl+D connect / disconnect · Ctrl+, settings · Tab / Shift+Tab navigate").size(11.).color(MUTED));
    }
}
impl eframe::App for App {
    fn logic(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        self.consume(ctx);
        if ctx.input(|i| i.viewport().close_requested()) && !self.stopped {
            ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
            if !self.closing {
                self.closing = true;
                self.phase = Phase::Disconnecting;
                self.backend.send(Action::Shutdown);
            }
        }
        if self.closing && self.stopped {
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }
    }
    fn ui(&mut self, ui: &mut egui::Ui, _: &mut eframe::Frame) {
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
                            Page::Networks => "WORKSPACE / MY NETWORKS",
                            Page::Discover => "WORKSPACE / DISCOVER",
                            Page::Settings => "WORKSPACE / SETTINGS",
                        })
                        .size(10.)
                        .color(MUTED),
                    );
                    ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                        badge(
                            ui,
                            if self.snapshot.interface_ready {
                                "Connected"
                            } else {
                                "Linux preview"
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
                            self.banner(ui, "Controlled test mode · application traffic is restricted to the selected test peers", AMBER);
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
        if self.confirm_reset && !self.closing {
            egui::Modal::new(egui::Id::new("reset-identity")).show(&ctx, |ui| {
                ui.set_max_width(430.);
                ui.heading("Reset device identity?");
                ui.label("OpenRad will disconnect this session and create a new device identity and VPN address. Your network memberships belong to the old identity; you will need to join networks again.");
                ui.add_space(8.);
                ui.label("The saved identity is replaced only after provisioning succeeds. Once saved, the old identity cannot be recovered from this profile.");
                ui.add_space(12.);
                ui.horizontal(|ui| {
                    if ui.button("Cancel").clicked() { self.confirm_reset = false; }
                    if ui.button(RichText::new("Disconnect and reset identity").color(AMBER)).clicked() {
                        self.confirm_reset = false;
                        self.phase = Phase::Resetting;
                        self.backend.send(Action::ResetIdentity);
                    }
                });
            });
        }
        if self.closing {
            egui::Modal::new(egui::Id::new("closing")).show(&ctx, |ui| {
                ui.heading("Closing your connection");
                ui.label("Finishing identity storage and removing the VPN interface…");
                ui.spinner();
            });
        }
        ctx.request_repaint_after(Duration::from_secs(1));
    }
}
fn configure(ctx: &egui::Context) {
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
        let (rect, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 22.), egui::Sense::hover());
        if let Some(values) = series {
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
fn bytes(n: u64) -> String {
    if n >= 1024 * 1024 {
        format!("{:.1} MiB", n as f64 / 1048576.)
    } else if n >= 1024 {
        format!("{:.1} KiB", n as f64 / 1024.)
    } else {
        format!("{n} B")
    }
}
fn rate(n: f32) -> String {
    format!("{}/s", bytes(n.max(0.) as u64))
}
fn elapsed(n: u64) -> String {
    format!("{:02}:{:02}:{:02}", n / 3600, n / 60 % 60, n % 60)
}
