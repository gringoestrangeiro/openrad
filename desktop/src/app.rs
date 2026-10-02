use crate::{
    backend::{Action, Backend, Notice, Phase},
    network_ui::{
        role_label, DeleteConfirmation, FormEvent, MemberConfirmation, Mode, NetworkForm,
    },
    storage::{
        JoinNetwork, NetworkPreferences, Paths, PeerSort, Settings, StartPage, MAX_JOIN_NETWORKS,
    },
};
use eframe::egui::{self, Align, Color32, FontId, RichText, Stroke, Vec2};
use openrad::{
    i18n::{Language, LanguagePreference},
    network::{MemberAction, NetworkPassword, NetworkRequest},
    protocol::{Network, PublicNetwork},
    runtime::{self, Command, PeerState, PeerView, Snapshot, Update},
};
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    fs::File,
    path::PathBuf,
    sync::Arc,
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
    AutoJoin,
    Settings,
}

#[derive(PartialEq)]
enum Activity {
    Message(String),
    Network {
        name: String,
        message: String,
    },
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
            Self::Network { name, message } => language.format(
                "{name}: {result}",
                &[("name", name), ("result", &language.message(message))],
            ),
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
enum PingState {
    Pending { id: u64, started: Instant },
    Measured(f64),
    Failed(String),
}

const SEARCH_DEBOUNCE: Duration = Duration::from_millis(300);

#[derive(Default)]
struct JoinRun {
    queue: VecDeque<NetworkRequest>,
    pending: BTreeMap<u64, String>,
    results: BTreeMap<String, (String, bool)>,
    next_send: Option<Instant>,
}
impl JoinRun {
    fn active(&self) -> bool {
        !self.queue.is_empty() || !self.pending.is_empty()
    }
}

// These caches compare only inputs that affect a list. Traffic counters and
// snapshot replacement do not invalidate them, and callers that edit a
// snapshot directly still get current filtering, ordering, and membership.
struct PeerDisplay {
    key: u64,
    rid: u64,
    name: String,
    normalized_name: String,
    address: String,
    vip: std::net::Ipv4Addr,
    network_ids: BTreeSet<String>,
    status: PeerState,
}
impl PeerDisplay {
    fn new(key: u64, peer: &PeerView) -> Self {
        Self {
            key,
            rid: peer.peer.rid,
            name: peer.peer.name.clone(),
            normalized_name: peer.peer.name.to_lowercase(),
            address: peer.peer.vip.to_string(),
            vip: peer.peer.vip,
            network_ids: peer.peer.network_ids.clone(),
            status: peer.status.clone(),
        }
    }
    fn refresh(&mut self, peer: &PeerView) -> bool {
        let mut changed = false;
        if self.name != peer.peer.name {
            self.name.clone_from(&peer.peer.name);
            self.normalized_name = peer.peer.name.to_lowercase();
            changed = true;
        }
        if self.vip != peer.peer.vip {
            self.vip = peer.peer.vip;
            self.address = peer.peer.vip.to_string();
            changed = true;
        }
        if self.rid != peer.peer.rid {
            self.rid = peer.peer.rid;
            changed = true;
        }
        if self.network_ids != peer.peer.network_ids {
            self.network_ids.clone_from(&peer.peer.network_ids);
            changed = true;
        }
        if self.status != peer.status {
            self.status.clone_from(&peer.status);
            changed = true;
        }
        changed
    }
}
struct PeerSelection {
    network: Option<String>,
    filter: String,
    normalized_filter: String,
    sort: PeerSort,
    show_offline: bool,
}
impl PeerSelection {
    fn matches(&self, network: Option<&str>, filter: &str, settings: &Settings) -> bool {
        self.network.as_deref() == network
            && self.filter == filter
            && self.sort == settings.peer_sort
            && self.show_offline == settings.show_offline_peers
    }
}
#[derive(Default)]
struct PeerListCache {
    peers: Vec<PeerDisplay>,
    visible: Vec<usize>,
    selection: Option<PeerSelection>,
    revision: u64,
}
impl PeerListCache {
    fn refresh(
        &mut self,
        snapshot: &Snapshot,
        network: Option<&str>,
        filter: &str,
        settings: &Settings,
    ) {
        let same_keys = self.peers.len() == snapshot.peers.len()
            && self
                .peers
                .iter()
                .map(|peer| peer.key)
                .eq(snapshot.peers.keys().copied());
        let mut changed = !same_keys;
        if same_keys {
            for (display, peer) in self.peers.iter_mut().zip(snapshot.peers.values()) {
                changed |= display.refresh(peer);
            }
        } else {
            self.peers = snapshot
                .peers
                .iter()
                .map(|(key, peer)| PeerDisplay::new(*key, peer))
                .collect();
        }
        if self
            .selection
            .as_ref()
            .is_none_or(|selection| !selection.matches(network, filter, settings))
        {
            self.selection = Some(PeerSelection {
                network: network.map(str::to_owned),
                filter: filter.to_owned(),
                normalized_filter: filter.to_lowercase(),
                sort: settings.peer_sort,
                show_offline: settings.show_offline_peers,
            });
            changed = true;
        }
        if !changed {
            return;
        }
        let selection = self.selection.as_ref().unwrap();
        self.visible.clear();
        self.visible
            .extend(self.peers.iter().enumerate().filter_map(|(index, peer)| {
                (selection
                    .network
                    .as_deref()
                    .is_none_or(|id| peer.network_ids.contains(id))
                    && (selection.show_offline || peer.status != PeerState::Offline)
                    && (selection.normalized_filter.is_empty()
                        || peer.normalized_name.contains(&selection.normalized_filter)
                        || peer.address.contains(&selection.normalized_filter)))
                .then_some(index)
            }));
        // Stable sorting retains BTreeMap RID order for equal names/statuses.
        let peers = &self.peers;
        self.visible.sort_by(|a, b| {
            let (a, b) = (&peers[*a], &peers[*b]);
            match selection.sort {
                PeerSort::Name => a.normalized_name.cmp(&b.normalized_name),
                PeerSort::Status => peer_rank(&a.status)
                    .cmp(&peer_rank(&b.status))
                    .then_with(|| a.normalized_name.cmp(&b.normalized_name)),
                PeerSort::Address => (a.vip, a.rid).cmp(&(b.vip, b.rid)),
            }
        });
        self.revision = self.revision.wrapping_add(1);
    }
}

#[derive(PartialEq)]
struct PeerRowLayout {
    width: f32,
    pixels_per_point: f32,
    style: Arc<egui::Style>,
    language: Language,
    show_internal_ids: bool,
    show_details: bool,
    selected_network: bool,
}
enum CachedPing {
    None,
    Pending,
    Measured(u64),
    Failed(String),
}
impl CachedPing {
    fn new(ping: Option<&PingState>) -> Self {
        match ping {
            None => Self::None,
            Some(PingState::Pending { .. }) => Self::Pending,
            Some(PingState::Measured(ms)) => Self::Measured(ms.to_bits()),
            Some(PingState::Failed(error)) => Self::Failed(error.clone()),
        }
    }
    fn matches(&self, ping: Option<&PingState>) -> bool {
        match (self, ping) {
            (Self::None, None) | (Self::Pending, Some(PingState::Pending { .. })) => true,
            (Self::Measured(old), Some(PingState::Measured(ms))) => *old == ms.to_bits(),
            (Self::Failed(old), Some(PingState::Failed(error))) => old == error,
            _ => false,
        }
    }
}
struct CachedPeerRow {
    height: f32,
    name: String,
    rid: u64,
    vip: std::net::Ipv4Addr,
    status: PeerState,
    detail: String,
    transport: Option<openrad::peer::TransportPath>,
    role: Option<u32>,
    own_role: Option<u32>,
    ping: CachedPing,
    separator: bool,
}
impl CachedPeerRow {
    fn matches(
        &self,
        peer: &PeerView,
        ping: Option<&PingState>,
        role: Option<u32>,
        own_role: Option<u32>,
        separator: bool,
    ) -> bool {
        self.name == peer.peer.name
            && self.rid == peer.peer.rid
            && self.vip == peer.peer.vip
            && self.status == peer.status
            && self.detail == peer.detail
            && self.transport == peer.transport
            && self.role == role
            && self.own_role == own_role
            && self.separator == separator
            && self.ping.matches(ping)
    }
    fn new(
        height: f32,
        peer: &PeerView,
        ping: Option<&PingState>,
        role: Option<u32>,
        own_role: Option<u32>,
        separator: bool,
    ) -> Self {
        Self {
            height,
            name: peer.peer.name.clone(),
            rid: peer.peer.rid,
            vip: peer.peer.vip,
            status: peer.status.clone(),
            detail: peer.detail.clone(),
            transport: peer.transport,
            role,
            own_role,
            ping: CachedPing::new(ping),
            separator,
        }
    }
}
#[derive(Default)]
struct PeerRowCache {
    layout: Option<PeerRowLayout>,
    rows: BTreeMap<u64, CachedPeerRow>,
    peer_revision: u64,
}
impl PeerRowCache {
    fn refresh_layout(
        &mut self,
        ui: &egui::Ui,
        language: Language,
        settings: &Settings,
        selected_network: bool,
    ) {
        let layout = PeerRowLayout {
            width: ui.available_width(),
            pixels_per_point: ui.ctx().pixels_per_point(),
            style: ui.style().clone(),
            language,
            show_internal_ids: settings.show_internal_ids,
            show_details: settings.show_peer_details,
            selected_network,
        };
        if self.layout.as_ref() != Some(&layout) {
            self.layout = Some(layout);
            self.rows.clear();
        }
    }
}

#[derive(Default)]
struct NetworkListCache {
    initialized: bool,
    networks: Vec<Network>,
    memberships: Vec<(u64, BTreeSet<String>)>,
    role_sizes: Vec<(String, usize)>,
    favorites: BTreeSet<String>,
    counts: BTreeMap<String, usize>,
    ordered: Arc<[Network]>,
    revision: u64,
}
impl NetworkListCache {
    fn refresh(&mut self, snapshot: &Snapshot, favorites: &BTreeSet<String>) {
        let same_networks = same_joined_networks(&self.networks, &snapshot.networks);
        let same_memberships = self.memberships.len() == snapshot.peers.len()
            && self.memberships.iter().zip(&snapshot.peers).all(
                |((old_rid, old_ids), (rid, peer))| {
                    *old_rid == *rid && old_ids == &peer.peer.network_ids
                },
            );
        let same_roles = self.role_sizes.len() == snapshot.roles.len()
            && self.role_sizes.iter().zip(&snapshot.roles).all(
                |((old_id, old_count), (id, roles))| old_id == id && *old_count == roles.len(),
            );
        if self.initialized
            && same_networks
            && same_memberships
            && same_roles
            && self.favorites == *favorites
        {
            return;
        }
        if !same_networks {
            self.networks.clone_from(&snapshot.networks);
        }
        if !same_memberships {
            self.memberships = snapshot
                .peers
                .iter()
                .map(|(rid, peer)| (*rid, peer.peer.network_ids.clone()))
                .collect();
        }
        if !same_roles {
            self.role_sizes = snapshot
                .roles
                .iter()
                .map(|(id, roles)| (id.clone(), roles.len()))
                .collect();
        }
        if !self.initialized || !same_networks || !same_memberships || !same_roles {
            self.counts = network_member_counts(snapshot);
        }
        self.favorites.clone_from(favorites);
        let mut ordered = self.networks.clone();
        sort_joined_networks(&mut ordered, favorites, &self.counts);
        self.ordered = ordered.into();
        self.initialized = true;
        self.revision = self.revision.wrapping_add(1);
    }
}
fn same_joined_networks(a: &[Network], b: &[Network]) -> bool {
    a.len() == b.len()
        && a.iter()
            .zip(b)
            .all(|(a, b)| a.name == b.name && a.network_id == b.network_id)
}
fn same_public_networks(a: &[PublicNetwork], b: &[PublicNetwork]) -> bool {
    a.len() == b.len()
        && a.iter()
            .zip(b)
            .all(|(a, b)| a.name == b.name && a.reported_count == b.reported_count)
}
struct PublicNetworkDisplay {
    network: PublicNetwork,
    normalized_name: String,
}
#[derive(Default)]
struct PublicNetworkListCache {
    initialized: bool,
    networks: Vec<PublicNetwork>,
    favorites: BTreeSet<String>,
    ordered: Arc<[PublicNetworkDisplay]>,
}
impl PublicNetworkListCache {
    fn refresh(&mut self, networks: &[PublicNetwork], favorites: &BTreeSet<String>) {
        if self.initialized
            && same_public_networks(&self.networks, networks)
            && self.favorites == *favorites
        {
            return;
        }
        self.networks = networks.to_vec();
        self.favorites.clone_from(favorites);
        self.ordered = ordered_public_networks(networks, favorites)
            .into_iter()
            .map(|network| {
                let normalized_name = network.name.to_lowercase();
                PublicNetworkDisplay {
                    network,
                    normalized_name,
                }
            })
            .collect::<Vec<_>>()
            .into();
        self.initialized = true;
    }
}
struct AvailableNetwork {
    name: String,
    private: bool,
    selected: bool,
    joined: bool,
    favorite: bool,
    member_count: usize,
    normalized_name: String,
}
#[derive(Default)]
struct AutoJoinListCache {
    initialized: bool,
    network_revision: u64,
    known_networks: BTreeMap<String, bool>,
    catalog: Vec<PublicNetwork>,
    draft: Vec<JoinNetwork>,
    ordered: Arc<[AvailableNetwork]>,
}
impl AutoJoinListCache {
    fn refresh(
        &mut self,
        networks: &NetworkListCache,
        preferences: &NetworkPreferences,
        catalog: &[PublicNetwork],
        draft: &[JoinNetwork],
    ) {
        if self.initialized
            && self.network_revision == networks.revision
            && self.known_networks == preferences.known_networks
            && same_public_networks(&self.catalog, catalog)
            && self.draft.as_slice() == draft
        {
            return;
        }
        let mut public_counts = BTreeMap::new();
        for network in catalog {
            // Match the previous first-name lookup if catalog names repeat.
            public_counts
                .entry(network.name.as_str())
                .or_insert(network.reported_count as usize);
        }
        let mut joined_counts = BTreeMap::new();
        let mut available = preferences.known_networks.clone();
        for name in &preferences.favorites {
            available.entry(name.clone()).or_insert(true);
        }
        for network in &networks.networks {
            let private = !public_counts.contains_key(network.name.as_str())
                && preferences
                    .known_networks
                    .get(&network.name)
                    .copied()
                    .unwrap_or(true);
            available.insert(network.name.clone(), private);
            joined_counts
                .entry(network.name.as_str())
                .or_insert(networks.counts[&network.network_id]);
        }
        for network in catalog {
            available.insert(network.name.clone(), false);
        }
        let selected: BTreeSet<_> = draft.iter().map(|network| network.name.as_str()).collect();
        for network in draft {
            available.insert(network.name.clone(), network.private);
        }
        let mut ordered: Vec<_> = available
            .into_iter()
            .map(|(name, private)| {
                let favorite = preferences.favorites.contains(&name);
                let joined = joined_counts.contains_key(name.as_str());
                let is_selected = selected.contains(name.as_str());
                let member_count = joined_counts
                    .get(name.as_str())
                    .or_else(|| public_counts.get(name.as_str()))
                    .copied()
                    .unwrap_or(0);
                let normalized_name = name.to_lowercase();
                AvailableNetwork {
                    name,
                    private,
                    selected: is_selected,
                    joined,
                    favorite,
                    member_count,
                    normalized_name,
                }
            })
            .collect();
        ordered.sort_by(|a, b| {
            b.favorite
                .cmp(&a.favorite)
                .then_with(|| {
                    if a.favorite && b.favorite {
                        b.member_count.cmp(&a.member_count)
                    } else {
                        b.selected.cmp(&a.selected)
                    }
                })
                .then_with(|| a.normalized_name.cmp(&b.normalized_name))
                .then_with(|| a.name.cmp(&b.name))
        });
        self.ordered = ordered.into();
        self.known_networks.clone_from(&preferences.known_networks);
        self.catalog = catalog.to_vec();
        self.draft = draft.to_vec();
        self.network_revision = networks.revision;
        self.initialized = true;
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
    peer_list: PeerListCache,
    peer_rows: PeerRowCache,
    network_list: NetworkListCache,
    public_list: PublicNetworkListCache,
    auto_join_list: AutoJoinListCache,
    page: Page,
    query: String,
    peer_filter: String,
    selected_network: Option<String>,
    catalog: Vec<PublicNetwork>,
    catalog_query: String,
    cursor: u64,
    has_searched: bool,
    busy: bool,
    search_in_flight: Option<String>,
    search_due: Option<Instant>,
    pings: BTreeMap<u64, PingState>,
    next_ping_id: u64,
    release: Option<openrad::releases::Release>,
    dismissed_releases: BTreeSet<String>,
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
    network_preferences: NetworkPreferences,
    network_preferences_error: Option<String>,
    join_draft: Vec<JoinNetwork>,
    join_passwords: BTreeMap<String, zeroize::Zeroizing<String>>,
    join_config_name: String,
    join_config_to_load: String,
    join_new_name: String,
    join_new_private: bool,
    join_run: JoinRun,
    next_join_id: u64,
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
        let (network_preferences, network_preferences_error) = match paths.network_preferences() {
            Ok(preferences) => (preferences, None),
            Err(_) => (NetworkPreferences::default(), Some("Network preferences could not be loaded. Repair network-preferences.json and restart.".into())),
        };
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
            peer_list: PeerListCache::default(),
            peer_rows: PeerRowCache::default(),
            network_list: NetworkListCache::default(),
            public_list: PublicNetworkListCache::default(),
            auto_join_list: AutoJoinListCache::default(),
            page: Page::Networks,
            query: String::new(),
            peer_filter: String::new(),
            selected_network: None,
            catalog: vec![],
            catalog_query: String::new(),
            cursor: 0,
            has_searched: false,
            busy: false,
            search_in_flight: None,
            search_due: None,
            pings: BTreeMap::new(),
            next_ping_id: 0,
            release: None,
            dismissed_releases: BTreeSet::new(),
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
            network_preferences,
            network_preferences_error,
            join_draft: Vec::new(),
            join_passwords: BTreeMap::new(),
            join_config_name: String::new(),
            join_config_to_load: String::new(),
            join_new_name: String::new(),
            join_new_private: true,
            join_run: JoinRun::default(),
            next_join_id: 0,
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
                        self.interrupt_join();
                        self.search_in_flight = None;
                        self.pings.clear();
                    }
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
                Notice::Identity { rid, name } => {
                    if self.identity.as_ref().is_some_and(|(old, _)| *old != rid) {
                        self.interrupt_join();
                        self.join_passwords.clear();
                        self.snapshot = Snapshot::default();
                        self.selected_network = None;
                    }
                    self.identity = Some((rid, name));
                }
                Notice::NodeName(name) => {
                    if self.settings.node_name == self.saved_settings.node_name {
                        self.settings.node_name = name.clone();
                    }
                    self.saved_settings.node_name = name;
                }
                Notice::RelayPreference(enabled) => {
                    if self.settings.force_relay == self.saved_settings.force_relay {
                        self.settings.force_relay = enabled;
                    }
                    self.saved_settings.force_relay = enabled;
                }
                Notice::Release(release) => {
                    if !self.dismissed_releases.contains(&release.version) {
                        self.release = Some(release);
                    }
                }
                Notice::SearchFailed { query, message } => {
                    if self.search_in_flight.as_ref() == Some(&query) {
                        self.search_in_flight = None;
                    }
                    if query == self.query.trim() {
                        self.search_due = None;
                        self.log(message.clone());
                        self.toast = Some((message, true, Instant::now()));
                    }
                }
                Notice::Engine(Update::Ping {
                    peer,
                    rtt_ms,
                    error,
                    id,
                }) => {
                    if !matches!(self.pings.get(&peer), Some(PingState::Pending { id: expected, started })
                        if Some(*expected) == id && started.elapsed() < runtime::PING_TIMEOUT)
                    {
                        continue;
                    }
                    self.pings.insert(
                        peer,
                        match (rtt_ms, error) {
                            (Some(ms), None) => PingState::Measured(ms),
                            (_, Some(error)) => PingState::Failed(error),
                            _ => PingState::Failed(runtime::PING_TIMEOUT_MESSAGE.into()),
                        },
                    );
                }
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
                    if self.search_in_flight.as_ref() == Some(&query) {
                        self.search_in_flight = None;
                    }
                    if query != self.query.trim() {
                        continue;
                    }
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
                }
                Notice::Engine(Update::Operation { message, error }) => {
                    self.busy = false;
                    self.log(message.clone());
                    self.toast = Some((message, error, Instant::now()));
                }
                Notice::Engine(Update::CommandResult {
                    id, message, error, ..
                }) => {
                    if let Some(name) = self.join_run.pending.remove(&id) {
                        self.log(Activity::Network {
                            name: name.clone(),
                            message: message.clone(),
                        });
                        self.join_run.results.insert(name, (message, error));
                    }
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
        for ping in self.pings.values_mut() {
            if let PingState::Pending { started, .. } = ping {
                let remaining = runtime::PING_TIMEOUT.saturating_sub(started.elapsed());
                if remaining.is_zero() {
                    *ping = PingState::Failed(runtime::PING_TIMEOUT_MESSAGE.into());
                } else {
                    ctx.request_repaint_after(remaining);
                }
            }
        }
    }
    fn network_dialogs(&mut self, ctx: &egui::Context) {
        let language = self.language();
        let enabled = self.connected() && !self.mutation_busy();
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
    fn joined_networks(&mut self) -> Arc<[Network]> {
        self.network_list
            .refresh(&self.snapshot, &self.network_preferences.favorites);
        Arc::clone(&self.network_list.ordered)
    }
    fn public_networks(&mut self) -> Arc<[PublicNetworkDisplay]> {
        self.public_list
            .refresh(&self.catalog, &self.network_preferences.favorites);
        Arc::clone(&self.public_list.ordered)
    }
    fn available_join_networks(&mut self) -> Arc<[AvailableNetwork]> {
        self.network_list
            .refresh(&self.snapshot, &self.network_preferences.favorites);
        self.auto_join_list.refresh(
            &self.network_list,
            &self.network_preferences,
            &self.catalog,
            &self.join_draft,
        );
        Arc::clone(&self.auto_join_list.ordered)
    }
    fn connected(&self) -> bool {
        self.phase == Phase::Connected
    }
    fn mutation_busy(&self) -> bool {
        self.busy || self.join_run.active()
    }
    fn notify_error(&mut self, error: anyhow::Error) {
        self.toast = Some((error.to_string(), true, Instant::now()));
    }
    fn persist_network_preferences(
        &mut self,
        preferences: NetworkPreferences,
    ) -> anyhow::Result<()> {
        if let Some(error) = &self.network_preferences_error {
            anyhow::bail!("{error}");
        }
        self.paths.save_network_preferences(&preferences)?;
        self.network_preferences = preferences;
        Ok(())
    }
    fn favorite_button(&mut self, ui: &mut egui::Ui, name: &str, private: bool) {
        let favorite = self.network_preferences.favorites.contains(name);
        let label = self
            .language()
            .text(if favorite {
                "Remove favorite"
            } else {
                "Add favorite"
            })
            .to_owned();
        let response = ui
            .add_enabled(
                self.network_preferences_error.is_none(),
                egui::Button::new(
                    RichText::new(if favorite { "★" } else { "☆" }).color(if favorite {
                        AMBER
                    } else {
                        MUTED
                    }),
                )
                .min_size(Vec2::splat(30.)),
            )
            .on_hover_text(&label);
        response.widget_info(|| {
            egui::WidgetInfo::labeled(egui::WidgetType::Button, response.enabled(), &label)
        });
        if response.clicked() {
            let mut next = self.network_preferences.clone();
            if favorite {
                next.favorites.remove(name);
            } else {
                next.favorites.insert(name.to_owned());
                next.known_networks.insert(name.to_owned(), private);
            }
            if let Err(error) = self.persist_network_preferences(next) {
                self.notify_error(error);
            }
        }
    }
    fn network_is_private(&self, name: &str) -> bool {
        if self.catalog.iter().any(|network| network.name == name) {
            return false;
        }
        self.network_preferences
            .known_networks
            .get(name)
            .copied()
            .unwrap_or(true)
    }
    fn membership_pending(&self, name: &str) -> bool {
        self.snapshot
            .networks
            .iter()
            .find(|n| n.name == name)
            .is_some_and(|network| {
                self.identity
                    .as_ref()
                    .and_then(|(rid, _)| self.snapshot.roles.get(&network.network_id)?.get(rid))
                    .copied()
                    == Some(0)
            })
    }
    fn select_join_network(&mut self, network: JoinNetwork, selected: bool) {
        if selected && !self.join_draft.iter().any(|n| n.name == network.name) {
            if self.join_draft.len() >= MAX_JOIN_NETWORKS {
                self.notify_error(anyhow::anyhow!("Select between 1 and 128 networks"));
                return;
            }
            self.join_draft.push(network);
        } else if !selected {
            self.join_draft.retain(|n| n.name != network.name);
            self.join_passwords.remove(&network.name);
        }
    }
    fn join_selection_button(&mut self, ui: &mut egui::Ui, name: &str, private: bool) {
        let mut selected = self.join_draft.iter().any(|network| network.name == name);
        if ui
            .add_enabled(
                !self.join_run.active(),
                egui::Checkbox::new(&mut selected, self.language().text("Select for auto join")),
            )
            .changed()
        {
            self.select_join_network(
                JoinNetwork {
                    name: name.to_owned(),
                    private,
                },
                selected,
            );
        }
    }
    fn save_join_configuration(&mut self) -> anyhow::Result<()> {
        let name = self.join_config_name.trim().to_owned();
        let mut next = self.network_preferences.clone();
        next.join_configurations
            .insert(name.clone(), self.join_draft.clone());
        for network in &self.join_draft {
            next.known_networks
                .insert(network.name.clone(), network.private);
        }
        self.persist_network_preferences(next)?;
        self.join_config_to_load = name.clone();
        self.join_config_name = name;
        self.toast = Some(("Join configuration saved".into(), false, Instant::now()));
        Ok(())
    }
    fn load_join_configuration(&mut self) {
        if let Some(networks) = self
            .network_preferences
            .join_configurations
            .get(&self.join_config_to_load)
        {
            self.join_draft = networks.clone();
            self.join_config_name = self.join_config_to_load.clone();
            self.join_passwords.clear();
            self.join_run = JoinRun::default();
        }
    }
    fn start_join(&mut self, now: Instant) -> anyhow::Result<()> {
        anyhow::ensure!(
            self.connected() && !self.mutation_busy(),
            "Connect your device and wait for the current operation to finish."
        );
        anyhow::ensure!(
            !self.join_draft.is_empty() && self.join_draft.len() <= MAX_JOIN_NETWORKS,
            "Select between 1 and 128 networks"
        );
        let mut run = JoinRun::default();
        for network in &self.join_draft {
            openrad::network::validate_name(&network.name)?;
            if self
                .snapshot
                .networks
                .iter()
                .any(|joined| joined.name == network.name)
            {
                let message = if self.membership_pending(&network.name) {
                    "Membership is pending administrator approval"
                } else {
                    "Already joined"
                };
                run.results
                    .insert(network.name.clone(), (message.into(), false));
                continue;
            }
            let password = if network.private {
                let value = self
                    .join_passwords
                    .get(&network.name)
                    .map(|p| p.to_string())
                    .unwrap_or_default();
                Some(NetworkPassword::new(value).map_err(|_| {
                    anyhow::anyhow!("Enter a valid password for every selected private network")
                })?)
            } else {
                None
            };
            run.queue.push_back(NetworkRequest::Join {
                name: network.name.clone(),
                password,
            });
        }
        run.next_send = Some(now);
        self.join_run = run;
        self.join_passwords.clear();
        Ok(())
    }
    fn advance_join(&mut self, ctx: &egui::Context, now: Instant) {
        if !self.connected() || self.closing {
            self.interrupt_join();
            return;
        }
        if let Some(due) = self.join_run.next_send {
            if now >= due {
                if let Some(request) = self.join_run.queue.pop_front() {
                    let NetworkRequest::Join { name, .. } = &request else {
                        unreachable!()
                    };
                    self.next_join_id += 1;
                    self.join_run
                        .pending
                        .insert(self.next_join_id, name.clone());
                    self.backend.send(Action::Engine(Command::Tagged {
                        id: self.next_join_id,
                        command: Box::new(Command::Network(request)),
                    }));
                    self.join_run.next_send = (!self.join_run.queue.is_empty())
                        .then_some(now + runtime::NETWORK_JOIN_DELAY);
                } else {
                    self.join_run.next_send = None;
                }
            }
        }
        if let Some(due) = self.join_run.next_send {
            ctx.request_repaint_after(due.saturating_duration_since(now));
        }
    }
    fn interrupt_join(&mut self) {
        let interrupted = "Join interrupted; reconnect before retrying";
        for request in self.join_run.queue.drain(..) {
            if let NetworkRequest::Join { name, .. } = request {
                self.join_run
                    .results
                    .insert(name, (interrupted.into(), true));
            }
        }
        for (_, name) in std::mem::take(&mut self.join_run.pending) {
            self.join_run
                .results
                .insert(name, (interrupted.into(), true));
        }
        self.join_run.next_send = None;
    }
    fn command(&mut self, command: Command) {
        let access = match &command {
            Command::Join(name) => Some((name, false)),
            Command::Network(NetworkRequest::Join { name, password }) => {
                Some((name, password.is_some()))
            }
            Command::Network(NetworkRequest::Create { name, .. }) => Some((name, true)),
            _ => None,
        };
        if let Some((name, private)) = access {
            if self.network_preferences.known_networks.get(name) != Some(&private)
                && self.network_preferences_error.is_none()
            {
                let mut next = self.network_preferences.clone();
                next.known_networks.insert(name.clone(), private);
                if let Err(error) = self.persist_network_preferences(next) {
                    self.notify_error(error);
                }
            }
        }
        if matches!(
            command,
            Command::Join(_) | Command::Leave(_) | Command::Network(_)
        ) {
            self.busy = true;
        }
        if let Command::Search { query, .. } = &command {
            self.search_in_flight = Some(query.clone());
            self.search_due = None;
            self.has_searched = true;
        }
        self.backend.send(Action::Engine(command));
    }
    fn toggle(&mut self) {
        if self.replacement_pending || self.confirm_reset {
            return;
        }
        if matches!(self.phase, Phase::Connected | Phase::Connecting) {
            self.interrupt_join();
            self.phase = Phase::Disconnecting;
            self.backend.send(Action::Disconnect);
        } else if matches!(self.phase, Phase::Disconnected | Phase::Error) {
            self.phase = Phase::Connecting;
            self.message = "Starting connection…".into();
            self.backend.send(Action::Connect);
        }
    }
    fn sidebar(&mut self, ui: &mut egui::Ui) -> egui::scroll_area::ScrollAreaOutput<()> {
        let language = self.language();
        let max_width = (ui.available_width() * 0.4).clamp(166., 420.);
        egui::Panel::left("navigation")
            .resizable(true)
            .default_size(240.)
            .size_range(166. ..=max_width)
            .frame(egui::Frame::new().fill(SURFACE).inner_margin(20))
            .show(ui, |ui| {
                // Reserve the footer before laying out an arbitrarily long membership list.
                egui::Panel::bottom("sidebar-footer")
                    .resizable(false)
                    .exact_size(120.)
                    .show_separator_line(false)
                    .frame(egui::Frame::new())
                    .show(ui, |ui| self.sidebar_footer(ui));
                let compact = ui.available_height() < 500.;
                let narrow = ui.available_width() < 150.;
                ui.scope(|ui| {
                    ui.style_mut().spacing.scroll.floating = false;
                    egui::ScrollArea::vertical()
                        .id_salt("sidebar-memberships")
                        .auto_shrink([false, false])
                        .min_scrolled_height(0.)
                        .show(ui, |ui| {
                            ui.add_space(10.);
                            ui.horizontal(|ui| {
                                ui.spacing_mut().item_spacing.x = 8.;
                                logo(ui, if narrow { 26. } else { 29. });
                                ui.add(
                                    egui::Label::new(
                                        RichText::new("openrad")
                                            .size(if narrow { 20. } else { 24. })
                                            .strong()
                                            .color(TEXT),
                                    )
                                    .truncate(),
                                );
                            });
                            ui.add_space(8.);
                            ui.add(
                                egui::Label::new(
                                    RichText::new(language.text("YOUR NETWORK, CLOSER"))
                                        .size(9.)
                                        .color(MUTED),
                                )
                                .truncate(),
                            );
                            ui.add_space(if compact { 20. } else { 38. });
                            for (page, label) in [
                                (Page::Networks, language.text("My networks")),
                                (Page::Discover, language.text("Discover")),
                                (Page::AutoJoin, language.text("Auto join")),
                                (Page::Settings, language.text("Settings")),
                            ] {
                                let selected = self.page == page;
                                let button = egui::Button::new(
                                    RichText::new(label).size(14.).color(if selected {
                                        MINT
                                    } else {
                                        MUTED
                                    }),
                                )
                                .fill(if selected {
                                    Color32::from_rgb(29, 56, 51)
                                } else {
                                    SURFACE
                                })
                                .stroke(Stroke::NONE)
                                .min_size(Vec2::new(ui.available_width(), 42.))
                                .corner_radius(9);
                                if ui.add(button).on_hover_text(label).clicked() {
                                    self.page = page;
                                }
                                ui.add_space(6.);
                            }
                            ui.add_space(if compact { 16. } else { 24. });
                            ui.add(
                                egui::Label::new(
                                    RichText::new(language.text("MEMBERSHIPS"))
                                        .size(10.)
                                        .color(MUTED),
                                )
                                .truncate(),
                            );
                            ui.add_space(10.);
                            if self.snapshot.networks.is_empty() {
                                ui.add(
                                    egui::Label::new(
                                        RichText::new(language.text("No networks yet"))
                                            .size(12.)
                                            .color(MUTED),
                                    )
                                    .truncate(),
                                );
                            }
                            for n in self.joined_networks().iter() {
                                let name = if self.network_preferences.favorites.contains(&n.name) {
                                    format!("★ {}", n.name)
                                } else {
                                    n.name.clone()
                                };
                                if ui
                                    .push_id(&n.network_id, |ui| {
                                        ui.add(
                                            egui::Button::new(RichText::new(name).size(12.))
                                                .selected(
                                                    self.selected_network.as_ref()
                                                        == Some(&n.network_id),
                                                )
                                                .truncate()
                                                .min_size(Vec2::new(ui.available_width(), 31.)),
                                        )
                                        .on_hover_text(&n.name)
                                    })
                                    .inner
                                    .clicked()
                                {
                                    self.selected_network = Some(n.network_id.clone());
                                    self.page = Page::Networks;
                                }
                            }
                        })
                })
                .inner
            })
            .inner
    }
    fn sidebar_footer(&self, ui: &mut egui::Ui) {
        let language = self.language();
        ui.add_space(12.);
        let name = self
            .identity
            .as_ref()
            .map(|(_, n)| n.as_str())
            .unwrap_or(language.text("This device"));
        ui.add(egui::Label::new(RichText::new(name).size(12.).color(TEXT)).truncate())
            .on_hover_text(name);
        ui.add_space(4.);
        ui.separator();
        ui.add_space(4.);
        let status = if self.snapshot.interface_ready {
            language.text(if self.windows_platform() {
                "OpenRad TAP active"
            } else {
                "radminvpn0 active"
            })
        } else {
            language.text("Interface offline")
        };
        ui.horizontal(|ui| {
            dot(
                ui,
                if self.snapshot.interface_ready {
                    MINT
                } else {
                    MUTED
                },
            );
            ui.add(egui::Label::new(RichText::new(status).size(11.).color(MUTED)).truncate())
                .on_hover_text(status);
        });
        let version = language.message(&if self.windows_platform() {
            format!("Native Windows · v{}", env!("CARGO_PKG_VERSION"))
        } else {
            format!("Native Linux · v{}", env!("CARGO_PKG_VERSION"))
        });
        ui.add(egui::Label::new(RichText::new(&version).size(10.).color(MUTED)).truncate())
            .on_hover_text(version);
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
            let compact = ui.available_width() < 480.;
            let toggle_label = language.text(
                if matches!(self.phase, Phase::Connected | Phase::Connecting) {
                    "Disconnect"
                } else {
                    "Connect"
                },
            );
            let button_width = ui
                .painter()
                .layout_no_wrap(toggle_label.to_owned(), FontId::proportional(14.), BG)
                .size()
                .x
                .max(80.)
                + 32.;
            let gap = 16.;
            let text_width =
                (ui.available_width() - 54. - gap - if compact { 0. } else { button_width + gap })
                    .max(120.);
            let title_height = ui
                .painter()
                .layout(
                    title.to_owned(),
                    FontId::proportional(25.),
                    TEXT,
                    text_width,
                )
                .size()
                .y;
            let caption_height = ui
                .painter()
                .layout(
                    caption.to_owned(),
                    FontId::proportional(12.),
                    MUTED,
                    text_width,
                )
                .size()
                .y;
            let text_height = title_height + caption_height + 3.;
            let row_height = text_height.max(54.);
            ui.allocate_ui_with_layout(
                Vec2::new(ui.available_width(), row_height),
                egui::Layout::left_to_right(Align::Center),
                |ui| {
                    ui.spacing_mut().item_spacing.x = gap;
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
                    ui.allocate_ui_with_layout(
                        Vec2::new(text_width, text_height),
                        egui::Layout::top_down(Align::LEFT),
                        |ui| {
                            ui.set_min_width(text_width);
                            ui.spacing_mut().item_spacing.y = 3.;
                            ui.label(RichText::new(title).size(25.).strong().color(TEXT));
                            ui.label(RichText::new(caption).size(12.).color(MUTED));
                        },
                    );
                    if !compact {
                        ui.allocate_ui_with_layout(
                            Vec2::new(button_width, row_height),
                            egui::Layout::left_to_right(Align::Center),
                            |ui| self.connection_button(ui, toggle_label, button_width),
                        );
                    }
                },
            );
            if compact {
                ui.add_space(12.);
                ui.allocate_ui_with_layout(
                    Vec2::new(ui.available_width(), 40.),
                    egui::Layout::right_to_left(Align::Center),
                    |ui| self.connection_button(ui, toggle_label, button_width),
                );
            }
            ui.add_space(18.);
            ui.separator();
            ui.add_space(12.);
            let compact_footer = ui.available_width() < 460.;
            ui.horizontal_wrapped(|ui| {
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
                if !compact_footer {
                    self.connection_timing(ui);
                }
            });
            if compact_footer {
                ui.add_space(6.);
                self.connection_timing(ui);
            }
        });
        if matches!(self.phase, Phase::Provisioning | Phase::Resetting) {
            ui.add_space(8.);
            ui.label(
                RichText::new(language.message(&self.message))
                    .size(12.)
                    .color(MUTED),
            );
        }
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
                ui.label(RichText::new(language.text(if self.windows_platform() { "Windows: run OpenRad-Setup.exe to install or repair the TAP adapter, then retry. Administrator approval is requested automatically." } else { "Linux: allow the system permission dialog for TAP setup, then retry if needed. A running Polkit authentication agent is required. The desktop stays unprivileged." })).size(12.).color(MUTED));
                ui.add_space(6.); if ui.button(language.text("Retry interface setup")).clicked() { self.command(Command::RetryInterface); }
            });
        }
    }
    fn connection_button(&mut self, ui: &mut egui::Ui, label: &str, width: f32) {
        let enabled = !self.replacement_pending
            && !self.confirm_reset
            && matches!(
                self.phase,
                Phase::Disconnected | Phase::Error | Phase::Connected | Phase::Connecting
            );
        if ui
            .add_enabled(enabled, primary(label).min_size(Vec2::new(width, 40.)))
            .on_hover_text("Ctrl+D")
            .clicked()
        {
            self.toggle();
        }
    }
    fn connection_timing(&self, ui: &mut egui::Ui) {
        ui.allocate_ui_with_layout(
            Vec2::new(ui.available_width(), 18.),
            egui::Layout::right_to_left(Align::Center),
            |ui| {
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
                        RichText::new(self.language().text("IPv4 · per-peer transport"))
                            .size(11.)
                            .color(MUTED),
                    );
                }
            },
        );
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
    fn network_selector(&mut self, ui: &mut egui::Ui) -> egui::scroll_area::ScrollAreaOutput<()> {
        let language = self.language();
        let max_name_width = (ui.available_width() - 60.).clamp(80., 240.);
        let row_height = ui.spacing().interact_size.y.max(40.);
        ui.scope(|ui| {
            // Allocate space for the scrollbar so it cannot cover the network buttons.
            ui.style_mut().spacing.scroll.floating = false;
            egui::ScrollArea::horizontal()
                .id_salt("joined-network-selector")
                .auto_shrink([false, true])
                .min_scrolled_width(0.)
                .max_height(row_height + ui.spacing().scroll.allocated_width())
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        if ui
                            .add(
                                egui::Button::new(language.text("All networks"))
                                    .selected(self.selected_network.is_none())
                                    .min_size(Vec2::new(0., row_height)),
                            )
                            .clicked()
                        {
                            self.selected_network = None;
                        }
                        for n in self.joined_networks().iter() {
                            ui.push_id(&n.network_id, |ui| {
                                self.favorite_button(ui, &n.name, self.network_is_private(&n.name));
                                let font = egui::TextStyle::Button.resolve(ui.style());
                                let width = (ui
                                    .painter()
                                    .layout_no_wrap(n.name.clone(), font.clone(), TEXT)
                                    .size()
                                    .x
                                    + 2. * ui.spacing().button_padding.x
                                    + 2.)
                                    .min(max_name_width);
                                if ui
                                    .add_sized(
                                        Vec2::new(width, row_height),
                                        egui::Button::new(RichText::new(&n.name).font(font))
                                            .selected(
                                                self.selected_network.as_ref()
                                                    == Some(&n.network_id),
                                            )
                                            .truncate(),
                                    )
                                    .on_hover_text(&n.name)
                                    .clicked()
                                {
                                    self.selected_network = Some(n.network_id.clone());
                                }
                            });
                        }
                    });
                })
        })
        .inner
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
                    self.connected() && !self.mutation_busy(),
                    primary(language.text("Create private network")),
                )
                .clicked()
            {
                self.network_form = Some(NetworkForm::new(Mode::Create));
            }
            if ui
                .add_enabled(
                    self.connected() && !self.mutation_busy(),
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
            if ui.button(language.text("Auto join")).clicked() {
                self.page = Page::AutoJoin;
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
        self.network_selector(ui);
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
                    self.join_selection_button(ui, &n.name, self.network_is_private(&n.name));
                    if ui
                        .add_enabled(
                            self.connected() && !self.mutation_busy(),
                            egui::Button::new(language.text("Leave network")),
                        )
                        .clicked()
                    {
                        self.command(Command::Leave(n.network_id.clone()));
                    }
                    if role == Some(2)
                        && ui
                            .add_enabled(
                                self.connected() && !self.mutation_busy(),
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
        let can_manage = self.connected() && !self.mutation_busy();
        let can_ping = self.connected();
        let mut peer_list = std::mem::take(&mut self.peer_list);
        peer_list.refresh(
            &self.snapshot,
            self.selected_network.as_deref(),
            &self.peer_filter,
            &self.settings,
        );
        let mut peer_rows = std::mem::take(&mut self.peer_rows);
        if peer_rows.peer_revision != peer_list.revision {
            peer_rows
                .rows
                .retain(|rid, _| self.snapshot.peers.contains_key(rid));
            peer_rows.peer_revision = peer_list.revision;
        }
        card().inner_margin(14).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            peer_rows.refresh_layout(
                ui,
                language,
                &self.settings,
                self.selected_network.is_some(),
            );
            if peer_list.visible.is_empty() {
                ui.add_space(15.);
                ui.label(
                    RichText::new(language.text(
                        "No peers match this view. Members will appear as the server reports them.",
                    ))
                    .color(MUTED),
                );
                ui.add_space(15.);
            }
            let keep_menus_alive = egui::Popup::is_any_open(ui.ctx());
            for (index, display_index) in peer_list.visible.iter().enumerate() {
                let display = &peer_list.peers[*display_index];
                let peer = &self.snapshot.peers[&display.key];
                let role = self.selected_network.as_ref().and_then(|network| {
                    self.snapshot
                        .roles
                        .get(network)?
                        .get(&peer.peer.rid)
                        .copied()
                });
                let own_role = self.selected_network.as_ref().and_then(|network| {
                    let (rid, _) = self.identity.as_ref()?;
                    self.snapshot.roles.get(network)?.get(rid).copied()
                });
                let separator = index + 1 < peer_list.visible.len();
                if let Some(row) = peer_rows.rows.get(&display.key).filter(|row| {
                    row.matches(
                        peer,
                        self.pings.get(&peer.peer.rid),
                        role,
                        own_role,
                        separator,
                    )
                }) {
                    let size = Vec2::new(ui.available_width(), row.height);
                    let rect = egui::Rect::from_min_size(ui.next_widget_position(), size);
                    if !keep_menus_alive && !ui.is_rect_visible(rect) {
                        // Same allocation and spacing as push_id, with no
                        // widgets, text layout, or actions for an offscreen row.
                        ui.allocate_space(size);
                        continue;
                    }
                }
                let response = ui.push_id(peer.peer.rid, |ui| {
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
                                    RichText::new(&display.address)
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
                            let rid = peer.peer.rid;
                            let pending =
                                matches!(self.pings.get(&rid), Some(PingState::Pending { .. }));
                            if ui
                                .add_enabled(
                                    can_ping && peer.status == PeerState::Connected && !pending,
                                    egui::Button::new(language.text("Test RTT")),
                                )
                                .clicked()
                            {
                                self.next_ping_id = self.next_ping_id.wrapping_add(1);
                                let id = self.next_ping_id;
                                self.pings.insert(
                                    rid,
                                    PingState::Pending {
                                        id,
                                        started: Instant::now(),
                                    },
                                );
                                self.backend.send(Action::Engine(Command::Tagged {
                                    id,
                                    command: Box::new(Command::Ping { peer: rid }),
                                }));
                            }
                            match self.pings.get(&rid) {
                                Some(PingState::Pending { .. }) => {
                                    ui.spinner();
                                }
                                Some(PingState::Measured(ms)) => {
                                    ui.label(
                                        language
                                            .format("RTT: {ms} ms", &[("ms", &format!("{ms:.1}"))]),
                                    );
                                }
                                _ => {}
                            }
                            if let Some(network) = &self.selected_network {
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
                    if let Some(PingState::Failed(error)) = self.pings.get(&peer.peer.rid) {
                        ui.label(
                            RichText::new(language.message(error))
                                .size(11.)
                                .color(AMBER),
                        );
                    }
                    if self.settings.show_peer_details && !peer.detail.is_empty() {
                        ui.label(
                            RichText::new(language.message(&peer.detail))
                                .size(11.)
                                .color(MUTED),
                        );
                    }
                    if separator {
                        ui.separator();
                    }
                });
                let height = response.response.rect.height();
                let ping = self.pings.get(&peer.peer.rid);
                if let Some(row) = peer_rows
                    .rows
                    .get_mut(&display.key)
                    .filter(|row| row.matches(peer, ping, role, own_role, separator))
                {
                    row.height = height;
                } else {
                    peer_rows.rows.insert(
                        display.key,
                        CachedPeerRow::new(height, peer, ping, role, own_role, separator),
                    );
                }
            }
        });
        self.peer_list = peer_list;
        self.peer_rows = peer_rows;
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
        ui.horizontal_wrapped(|ui| {
            if ui.button(language.text("Auto join")).clicked() {
                self.page = Page::AutoJoin;
            }
            ui.label(
                RichText::new(language.format(
                    "{count} networks selected",
                    &[("count", &self.join_draft.len().to_string())],
                ))
                .size(11.)
                .color(MUTED),
            );
        });
        ui.add_space(24.);
        let available = self.connected();
        card().inner_margin(18).show(ui, |ui| {
            ui.horizontal(|ui| {
                let response = ui.add_enabled(
                    available,
                    egui::TextEdit::singleline(&mut self.query)
                        .hint_text(language.text("Search games, communities, or a network name"))
                        .desired_width((ui.available_width() - 28.).max(180.)),
                );
                if self.focus_search {
                    response.request_focus();
                    self.focus_search = false;
                }
                if response.changed() {
                    self.search_due = Some(Instant::now() + SEARCH_DEBOUNCE);
                    self.cursor = 0;
                }
                if self.search_in_flight.is_some() {
                    ui.spinner();
                }
            });
            ui.label(
                RichText::new(language.text("Results update as you type."))
                    .size(11.)
                    .color(MUTED),
            );
        });
        if self.connected()
            && !self.has_searched
            && self.search_due.is_none()
            && self.search_in_flight.is_none()
        {
            self.search_due = Some(Instant::now() + SEARCH_DEBOUNCE);
        }
        if let Some(due) = self.search_due {
            if Instant::now() >= due
                && !self.mutation_busy()
                && self.search_in_flight.is_none()
                && self.connected()
            {
                self.command(Command::Search {
                    query: self.query.trim().to_owned(),
                    cursor: 0,
                });
            } else {
                ui.ctx().request_repaint_after(
                    due.saturating_duration_since(Instant::now())
                        .max(Duration::from_millis(20)),
                );
            }
        }
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
        if !self.has_searched && self.search_in_flight.is_none() {
            ui.label(
                RichText::new(language.text("Start with a name, or browse what’s available."))
                    .size(18.),
            );
        } else if self.catalog.is_empty()
            && self.search_in_flight.is_none()
            && self.search_due.is_none()
        {
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
            let filter = self.query.trim().to_lowercase();
            for display in self
                .public_networks()
                .iter()
                .filter(|display| display.normalized_name.contains(&filter))
            {
                let network = &display.network;
                let joined = self
                    .snapshot
                    .networks
                    .iter()
                    .any(|n| n.name == network.name);
                ui.push_id(&network.name, |ui| {
                    card().inner_margin(18).show(ui, |ui| {
                        ui.horizontal(|ui| {
                            self.favorite_button(ui, &network.name, false);
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
                                    .add_enabled(
                                        !self.mutation_busy(),
                                        primary(language.text("Join network")),
                                    )
                                    .clicked()
                                {
                                    self.command(Command::Join(network.name.clone()));
                                }
                            });
                        });
                        self.join_selection_button(ui, &network.name, false);
                    });
                    ui.add_space(9.);
                });
            }
            if self.cursor != 0
                && self.catalog_query == self.query.trim()
                && self.search_in_flight.is_none()
                && self.search_due.is_none()
                && ui
                    .add_enabled(
                        !self.mutation_busy(),
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
    fn auto_join(&mut self, ui: &mut egui::Ui) {
        let language = self.language();
        let active = self.join_run.active();
        ui.label(RichText::new(language.text("Auto join")).size(29.).strong());
        ui.add_space(8.);
        ui.label(
            RichText::new(
                language
                    .text("Select networks and join them together, with 50 ms between requests."),
            )
            .color(MUTED),
        );
        ui.add_space(18.);
        card().inner_margin(18).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(RichText::new(language.text("Saved configurations")).size(17.).strong());
            ui.add_space(10.);
            ui.add_enabled_ui(!active, |ui| {
                ui.horizontal_wrapped(|ui| {
                    egui::ComboBox::from_id_salt("join-configuration")
                        .width(210.)
                        .selected_text(if self.join_config_to_load.is_empty() { language.text("Choose configuration") } else { &self.join_config_to_load })
                        .show_ui(ui, |ui| {
                            for name in self.network_preferences.join_configurations.keys() {
                                ui.selectable_value(&mut self.join_config_to_load, name.clone(), name);
                            }
                        });
                    let exists = self.network_preferences.join_configurations.contains_key(&self.join_config_to_load);
                    if ui.add_enabled(exists, egui::Button::new(language.text("Load configuration"))).clicked() {
                        self.load_join_configuration();
                    }
                    if ui.add_enabled(exists, egui::Button::new(language.text("Delete configuration"))).clicked() {
                        let mut next = self.network_preferences.clone();
                        next.join_configurations.remove(&self.join_config_to_load);
                        if let Err(error) = self.persist_network_preferences(next) {
                            self.notify_error(error);
                        } else {
                            self.join_config_to_load.clear();
                        }
                    }
                });
                ui.add_space(8.);
                ui.horizontal_wrapped(|ui| {
                    ui.add(egui::TextEdit::singleline(&mut self.join_config_name)
                        .hint_text(language.text("Configuration name"))
                        .char_limit(80).desired_width(240.));
                    if ui.add_enabled(!self.join_draft.is_empty() && self.network_preferences_error.is_none(), egui::Button::new(language.text("Save configuration"))).clicked() {
                        if let Err(error) = self.save_join_configuration() { self.notify_error(error); }
                    }
                });
            });
            ui.add_space(8.);
            ui.label(RichText::new(language.text("Saved lists stay available after changing identity. Private passwords are requested again and never saved.")).size(11.).color(MUTED));
        });
        ui.add_space(18.);
        ui.label(
            RichText::new(language.text("Select networks"))
                .size(19.)
                .strong(),
        );
        ui.add_space(8.);
        ui.add_enabled_ui(!active, |ui| {
            ui.horizontal_wrapped(|ui| {
                ui.add(
                    egui::TextEdit::singleline(&mut self.join_new_name)
                        .hint_text(language.text("Exact network name"))
                        .char_limit(255)
                        .desired_width(240.),
                );
                ui.checkbox(&mut self.join_new_private, language.text("Private network"));
                if ui.button(language.text("Add network")).clicked() {
                    if let Err(error) = openrad::network::validate_name(&self.join_new_name) {
                        self.notify_error(error);
                    } else {
                        let network = JoinNetwork {
                            name: self.join_new_name.clone(),
                            private: self.join_new_private,
                        };
                        self.select_join_network(network, true);
                        self.join_new_name.clear();
                    }
                }
            });
            ui.horizontal_wrapped(|ui| {
                if ui.button(language.text("Select joined networks")).clicked() {
                    for network in self.joined_networks().iter() {
                        let private = self.network_is_private(&network.name);
                        self.select_join_network(
                            JoinNetwork {
                                name: network.name.clone(),
                                private,
                            },
                            true,
                        );
                    }
                }
                if ui.button(language.text("Clear selection")).clicked() {
                    self.join_draft.clear();
                    self.join_passwords.clear();
                    self.join_run = JoinRun::default();
                }
                if ui.button(language.text("Browse public")).clicked() {
                    self.page = Page::Discover;
                    self.focus_search = true;
                }
            });
        });
        ui.add_space(12.);
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    self.connected() && !self.mutation_busy() && !self.join_draft.is_empty(),
                    primary(language.text("Join selected networks")),
                )
                .clicked()
            {
                if let Err(error) = self.start_join(Instant::now()) {
                    self.notify_error(error);
                }
            }
            ui.label(
                RichText::new(language.format(
                    "{count} networks selected",
                    &[("count", &self.join_draft.len().to_string())],
                ))
                .color(MUTED),
            );
            if active {
                ui.spinner();
            }
        });
        if !self.connected() {
            self.banner(
                ui,
                language.text("Connect your device to join the selected networks."),
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
        }
        ui.add_space(12.);
        let available = self.available_join_networks();
        if available.is_empty() {
            ui.label(
                RichText::new(language.text(
                    "Browse public networks or add an exact private network name to get started.",
                ))
                .color(MUTED),
            );
        }
        for network in available.iter() {
            let name = network.name.as_str();
            let mut private = network.private;
            let mut selected = network.selected;
            let joined = network.joined;
            ui.push_id(name, |ui| {
                card().inner_margin(14).show(ui, |ui| {
                    ui.set_min_width(ui.available_width());
                    ui.horizontal_wrapped(|ui| {
                        if ui
                            .add_enabled(!active, egui::Checkbox::new(&mut selected, name))
                            .changed()
                        {
                            self.select_join_network(
                                JoinNetwork {
                                    name: name.to_owned(),
                                    private,
                                },
                                selected,
                            );
                        }
                        self.favorite_button(ui, name, private);
                        if joined {
                            let pending = self.membership_pending(name);
                            badge(
                                ui,
                                language.text(if pending {
                                    "Pending approval"
                                } else {
                                    "Joined"
                                }),
                                if pending { AMBER } else { MINT },
                            );
                        }
                    });
                    if selected {
                        if ui
                            .add_enabled(
                                !active,
                                egui::Checkbox::new(&mut private, language.text("Private network")),
                            )
                            .changed()
                        {
                            if let Some(network) = self
                                .join_draft
                                .iter_mut()
                                .find(|network| network.name == name)
                            {
                                network.private = private;
                            }
                            self.join_passwords.remove(name);
                        }
                        if private && !joined && !active {
                            let label = ui.label(language.text("Password"));
                            let password = self.join_passwords.entry(name.to_owned()).or_default();
                            ui.add(
                                egui::TextEdit::singleline(&mut **password)
                                    .password(true)
                                    .char_limit(256)
                                    .desired_width(f32::INFINITY),
                            )
                            .labelled_by(label.id);
                        }
                    }
                    if let Some((message, error)) = self.join_run.results.get(name) {
                        ui.label(
                            RichText::new(language.message(message))
                                .size(12.)
                                .color(if *error { RED } else { MINT }),
                        );
                    } else if self
                        .join_run
                        .pending
                        .values()
                        .any(|pending| pending.as_str() == name)
                    {
                        ui.label(
                            RichText::new(language.text("Joining…"))
                                .size(12.)
                                .color(BLUE),
                        );
                    } else if active && selected {
                        ui.label(
                            RichText::new(language.text("Waiting to join…"))
                                .size(12.)
                                .color(MUTED),
                        );
                    }
                });
            });
            ui.add_space(8.);
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
            ui.checkbox(&mut self.settings.force_relay, language.text("Force Relay"));
            ui.label(RichText::new(language.text("Use relays only. Saving reconnects the session without direct UDP or TCP attempts.")).size(11.).color(MUTED));
            ui.add_space(14.);
            ui.label(language.text("Device name"));
            ui.add(egui::TextEdit::singleline(&mut self.settings.node_name).desired_width(300.));
            ui.label(RichText::new(language.text("Save preferences to change the name peers see. Your identity and memberships are preserved.")).size(11.).color(MUTED));
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
            ui.label(
                RichText::new(language.text("Private device identity"))
                    .size(18.)
                    .strong(),
            );
            ui.add_space(12.);
            if let Some((rid, name)) = &self.identity {
                ui.label(language.format(
                    "{name}  ·  Device {rid}",
                    &[("name", name), ("rid", &rid.to_string())],
                ));
            } else {
                ui.label(language.text("No identity loaded"));
            }
            ui.label(
                RichText::new(language.text(
                    "The desktop and CLI share this identity. It is reused each time you connect.",
                ))
                .size(12.)
                .color(MUTED),
            );
            ui.add_space(12.);
            let can_reset = self.identity.is_some()
                && !self.closing
                && matches!(
                    self.phase,
                    Phase::Connected | Phase::Disconnected | Phase::Error
                );
            let label = if self.replacement_pending {
                language.text("Retry saving new identity")
            } else {
                language.text("Reset identity…")
            };
            if ui
                .add_enabled(
                    can_reset,
                    egui::Button::new(RichText::new(label).color(AMBER)),
                )
                .clicked()
            {
                if self.replacement_pending {
                    self.phase = Phase::Resetting;
                    self.backend.send(Action::ResetIdentity);
                } else {
                    self.confirm_reset = true;
                }
            }
            ui.add_space(12.);
            ui.label(RichText::new(language.text("Import an existing OpenRad identity")).strong());
            ui.horizontal(|ui| {
                ui.add(
                    egui::TextEdit::singleline(&mut self.import_path)
                        .hint_text("/path/to/identity.json")
                        .desired_width((ui.available_width() - 90.).max(160.)),
                );
                if ui
                    .add_enabled(
                        !self.connected()
                            && self.identity.is_none()
                            && !self.import_path.is_empty(),
                        egui::Button::new(language.text("Import")),
                    )
                    .clicked()
                {
                    self.backend
                        .send(Action::Import(PathBuf::from(&self.import_path)));
                }
            });
        });
        ui.add_space(16.);
        card().inner_margin(22).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(RichText::new(language.text(if self.windows_platform() { "Windows interface" } else { "Linux interface" })).size(18.).strong()); ui.add_space(10.);
            ui.label(if self.windows_platform() { "OpenRad · TAP-Windows6 · MTU 1500" } else { "radminvpn0 · Ethernet TAP · MTU 1500" });
            ui.label(RichText::new(language.text(if self.windows_platform() { "OpenRad Setup creates the dedicated TAP-Windows6 adapter. Disconnecting removes the VPN address and session routes; the installed adapter remains." } else { "A short-lived helper configures the interface. OpenRad runs as your normal user. Disconnect removes the interface and routes; closing the window keeps the VPN running." })).size(12.).color(MUTED));
            ui.add_space(10.); ui.label(RichText::new(language.text(if self.windows_platform() { "Run OpenRad-Setup.exe again to check or repair setup. Use --no-launch for CLI setup. Windows runtime validation is still pending. See docs/windows.md." } else { "TAP setup requests permission through the system dialog. If it does not appear, check that Polkit and its authentication agent are running, then retry interface setup." })).size(12.).color(MUTED));
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
            if ui.button(language.text("Copy identity reset log path")).clicked() {
                ui.ctx().copy_text(crate::startup_log::path().display().to_string());
            }
        });
        ui.add_space(16.);
        ui.label(RichText::new(language.text("Shortcuts: Ctrl+K search · Ctrl+D connect / disconnect · Ctrl+, settings · Tab / Shift+Tab navigate")).size(11.).color(MUTED));
    }
    fn update_notice(&mut self, ui: &mut egui::Ui) {
        let Some(release) = self.release.clone() else {
            return;
        };
        let language = self.language();
        card().inner_margin(12).show(ui, |ui| {
            ui.horizontal_wrapped(|ui| {
                ui.label(
                    RichText::new(language.format(
                        "OpenRad {version} is available",
                        &[("version", &release.version)],
                    ))
                    .color(BLUE),
                );
                ui.hyperlink_to(language.text("View release"), &release.url);
                if ui
                    .button(language.text("×"))
                    .on_hover_text(language.text("Dismiss update notice"))
                    .clicked()
                {
                    self.dismissed_releases.insert(release.version.clone());
                    self.release = None;
                }
            });
        });
        ui.add_space(12.);
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
                            Page::AutoJoin => language.text("WORKSPACE / AUTO JOIN"),
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
                    .id_salt(match self.page { Page::Networks => 0, Page::Discover => 1, Page::Settings => 2, Page::AutoJoin => 3 })
                    .auto_shrink([false, false])
                    .show(ui, |ui| {
                        ui.set_min_width(ui.available_width());
                        self.update_notice(ui);
                        if let Some(error) = &self.network_preferences_error { self.banner(ui, error, RED); }
                        if self.snapshot.restricted_traffic {
                            self.banner(ui, language.text("Controlled test mode · application traffic is restricted to the selected test peers"), AMBER);
                            ui.add_space(12.);
                        }
                        match self.page {
                            Page::Networks => self.networks(ui),
                            Page::Discover => self.discover(ui),
                            Page::AutoJoin => self.auto_join(ui),
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
                        self.interrupt_join();
                        self.join_passwords.clear();
                        self.backend.send(Action::ResetIdentity);
                    }
                });
            });
        }
        if self.closing {
            egui::Modal::new(egui::Id::new("closing")).show(&ctx, |ui| {
                ui.heading(language.text("Closing OpenRad"));
                ui.label(language.text(
                    "The VPN session stays available to the CLI. Use Disconnect to stop it.",
                ));
                ui.spinner();
            });
        }
        self.advance_join(&ctx, Instant::now());
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
#[cfg(test)]
fn visible_peers<'a>(
    snapshot: &'a Snapshot,
    network: Option<&str>,
    filter: &str,
    settings: &Settings,
) -> Vec<&'a PeerView> {
    let mut cache = PeerListCache::default();
    cache.refresh(snapshot, network, filter, settings);
    cache
        .visible
        .iter()
        .map(|index| &snapshot.peers[&cache.peers[*index].key])
        .collect()
}
fn ordered_public_networks(
    networks: &[PublicNetwork],
    favorites: &BTreeSet<String>,
) -> Vec<PublicNetwork> {
    let mut networks: Vec<_> = networks
        .iter()
        .map(|network| (favorites.contains(&network.name), network.clone()))
        .collect();
    networks.sort_by(|(a_favorite, a), (b_favorite, b)| {
        b_favorite.cmp(a_favorite).then_with(|| {
            if *a_favorite && *b_favorite {
                b.reported_count
                    .cmp(&a.reported_count)
                    .then_with(|| a.name.cmp(&b.name))
            } else {
                std::cmp::Ordering::Equal
            }
        })
    });
    networks.into_iter().map(|(_, network)| network).collect()
}
#[cfg(test)]
fn ordered_joined_networks(snapshot: &Snapshot, favorites: &BTreeSet<String>) -> Vec<Network> {
    let mut networks = snapshot.networks.clone();
    sort_joined_networks(&mut networks, favorites, &network_member_counts(snapshot));
    networks
}
fn sort_joined_networks(
    networks: &mut [Network],
    favorites: &BTreeSet<String>,
    counts: &BTreeMap<String, usize>,
) {
    // Decorate once so membership counts and favorite lookups do not run from
    // the comparator. Non-favorite ties retain the server's existing order.
    let keys: Vec<_> = networks
        .iter()
        .map(|network| {
            (
                favorites.contains(&network.name),
                counts[&network.network_id],
            )
        })
        .collect();
    let mut order: Vec<_> = (0..networks.len()).collect();
    order.sort_by(|a, b| {
        let ((a_favorite, a_count), (b_favorite, b_count)) = (keys[*a], keys[*b]);
        let (a, b) = (&networks[*a], &networks[*b]);
        b_favorite.cmp(&a_favorite).then_with(|| {
            if a_favorite && b_favorite {
                b_count
                    .cmp(&a_count)
                    .then_with(|| a.name.cmp(&b.name))
                    .then_with(|| a.network_id.cmp(&b.network_id))
            } else {
                std::cmp::Ordering::Equal
            }
        })
    });
    // The source cache owns its copy; move records through the permutation.
    // Avoid cloning every string a second time merely to reorder that copy.
    let mut destination: Vec<_> = (0..order.len()).collect();
    for (new_index, old_index) in order.into_iter().enumerate() {
        destination[old_index] = new_index;
    }
    for index in 0..destination.len() {
        while destination[index] != index {
            let target = destination[index];
            networks.swap(index, target);
            destination.swap(index, target);
        }
    }
}
fn network_member_counts(snapshot: &Snapshot) -> BTreeMap<String, usize> {
    let mut counts: BTreeMap<_, _> = snapshot
        .networks
        .iter()
        .map(|network| (network.network_id.clone(), 1))
        .collect();
    for peer in snapshot.peers.values() {
        for id in &peer.peer.network_ids {
            if let Some(count) = counts.get_mut(id) {
                *count += 1;
            }
        }
    }
    for (id, roles) in &snapshot.roles {
        if let Some(count) = counts.get_mut(id) {
            *count = roles.len();
        }
    }
    counts
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
    fn many_joined_networks(app: &mut App) {
        app.snapshot.networks = (0..128)
            .map(|index| Network {
                name: if index == 0 {
                    format!("Minecraft [Português {index:03}] {}", "á".repeat(230))
                } else {
                    format!("Minecraft [Português {index:03}]")
                },
                network_id: format!("synthetic-{index}"),
            })
            .collect();
        app.identity = Some((
            1,
            "Synthetic device with a long name Русский Tiếng Việt".into(),
        ));
        app.snapshot.interface_ready = true;
    }
    fn node_rect(output: &egui::FullOutput, text: &str) -> egui::Rect {
        let bounds = output
            .platform_output
            .accesskit_update
            .as_ref()
            .unwrap()
            .nodes
            .iter()
            .find(|(_, node)| node.label() == Some(text) || node.value() == Some(text))
            .unwrap_or_else(|| panic!("missing widget: {text}"))
            .1
            .bounds()
            .unwrap();
        egui::Rect::from_min_max(
            egui::pos2(bounds.x0 as f32, bounds.y0 as f32),
            egui::pos2(bounds.x1 as f32, bounds.y1 as f32),
        )
    }
    fn network_list_frame(
        ctx: &egui::Context,
        app: &mut App,
        size: Vec2,
        sidebar: bool,
        events: Vec<egui::Event>,
    ) -> (egui::FullOutput, egui::scroll_area::ScrollAreaOutput<()>) {
        let mut scroll = None;
        let mut output = ctx.run_ui(
            egui::RawInput {
                screen_rect: Some(egui::Rect::from_min_size(egui::Pos2::ZERO, size)),
                events,
                ..Default::default()
            },
            |ui| {
                if sidebar {
                    scroll = Some(app.sidebar(ui));
                } else {
                    egui::CentralPanel::default()
                        .frame(egui::Frame::new().inner_margin(24))
                        .show(ui, |ui| {
                            scroll = Some(app.network_selector(ui));
                            ui.add_space(12.);
                            let hint = app.language().text("Filter peers by name or VPN address");
                            ui.add(
                                egui::TextEdit::singleline(&mut app.peer_filter)
                                    .hint_text(hint)
                                    .desired_width(ui.available_width()),
                            );
                        });
                }
            },
        );
        output.textures_delta.clear();
        (output, scroll.unwrap())
    }
    #[test]
    fn sidebar_shows_numbered_networks_and_remembers_dragged_widths() {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        many_joined_networks(app);
        app.settings.language = LanguagePreference::Portuguese;
        app.snapshot.networks[0].name = "Minecraft [Português 15]".into();
        app.snapshot.networks[1].name = "Minecraft [Português 23]".into();
        app.network_preferences
            .favorites
            .insert(app.snapshot.networks[1].name.clone());
        let ctx = egui::Context::default();
        configure(&ctx);
        ctx.enable_accesskit();
        let size = Vec2::new(1600., 850.);
        network_list_frame(&ctx, app, size, true, vec![]);
        let (output, _) = network_list_frame(&ctx, app, size, true, vec![]);
        for name in ["Minecraft [Português 15]", "★ Minecraft [Português 23]"] {
            let galley = output
                .shapes
                .iter()
                .find_map(|shape| match &shape.shape {
                    egui::Shape::Text(text) if text.galley.job.text == name => Some(&text.galley),
                    _ => None,
                })
                .unwrap_or_else(|| panic!("missing visible network: {name}"));
            assert!(!galley.elided, "the network number must fit: {name}");
        }
        let panel_width = || {
            egui::containers::panel::PanelState::load(&ctx, egui::Id::new("navigation"))
                .unwrap()
                .size()
                .x
        };
        assert_eq!(panel_width(), 240.);
        let start = egui::pos2(panel_width(), 200.);
        let end = egui::pos2(330., 200.);
        network_list_frame(
            &ctx,
            app,
            size,
            true,
            vec![
                egui::Event::PointerMoved(start),
                egui::Event::PointerButton {
                    pos: start,
                    button: egui::PointerButton::Primary,
                    pressed: true,
                    modifiers: Default::default(),
                },
            ],
        );
        network_list_frame(&ctx, app, size, true, vec![egui::Event::PointerMoved(end)]);
        network_list_frame(
            &ctx,
            app,
            size,
            true,
            vec![egui::Event::PointerButton {
                pos: end,
                button: egui::PointerButton::Primary,
                pressed: false,
                modifiers: Default::default(),
            }],
        );
        network_list_frame(&ctx, app, size, true, vec![]);
        assert!(
            (panel_width() - 330.).abs() < 1.,
            "dragging the right edge changes the width"
        );
        app.page = Page::Discover;
        network_list_frame(&ctx, app, size, true, vec![]);
        assert!(
            (panel_width() - 330.).abs() < 1.,
            "navigation keeps the chosen width"
        );
        let small = Vec2::new(520., 360.);
        network_list_frame(&ctx, app, small, true, vec![]);
        assert!(
            panel_width() <= small.x * 0.4,
            "a resized window still has room for its page"
        );
    }
    #[test]
    fn membership_sidebar_scrolls_to_every_network_without_covering_device_details() {
        for preference in [
            LanguagePreference::English,
            LanguagePreference::Portuguese,
            LanguagePreference::Russian,
            LanguagePreference::Vietnamese,
        ] {
            for (pixels, scale) in [(Vec2::new(1600., 850.), 1.), (Vec2::new(780., 540.), 1.5)] {
                let size = pixels / scale;
                let mut fixture = Fixture::new();
                let app = fixture.app();
                many_joined_networks(app);
                app.settings.language = preference;
                let ctx = egui::Context::default();
                configure(&ctx);
                ctx.set_zoom_factor(scale);
                ctx.enable_accesskit();
                network_list_frame(&ctx, app, size, true, vec![]);
                let (output, mut scroll) = network_list_frame(&ctx, app, size, true, vec![]);
                let device_name = app.identity.as_ref().unwrap().1.clone();
                let footer = node_rect(&output, &device_name);
                let version = node_rect(
                    &output,
                    &app.language().message(&format!(
                        "Native {} · v{}",
                        if app.windows_platform() {
                            "Windows"
                        } else {
                            "Linux"
                        },
                        env!("CARGO_PKG_VERSION")
                    )),
                );
                assert!(scroll.content_size.y > scroll.inner_rect.height());
                assert!(scroll.inner_rect.max.y < footer.min.y);
                assert!(version.max.y <= ctx.content_rect().max.y - 20.);
                let network_buttons = output
                    .platform_output
                    .accesskit_update
                    .as_ref()
                    .unwrap()
                    .nodes
                    .iter()
                    .filter(|(_, node)| {
                        node.role() == egui::accesskit::Role::Button
                            && node
                                .label()
                                .is_some_and(|label| label.starts_with("Minecraft"))
                    })
                    .count();
                assert_eq!(network_buttons, 128, "every membership is reachable");
                scroll.state.offset.y = scroll.content_size.y - scroll.inner_rect.height();
                scroll.state.store(&ctx, scroll.id);
                let (output, scroll) = network_list_frame(&ctx, app, size, true, vec![]);
                assert_eq!(
                    footer,
                    node_rect(&output, &device_name),
                    "footer stays anchored"
                );
                let last = app.snapshot.networks.last().unwrap().clone();
                let button = node_rect(&output, &last.name);
                assert!(scroll.inner_rect.expand(0.5).contains_rect(button), "{preference:?} at {size:?}/{scale}: viewport {:?}, button {button:?}, offset {:?}", scroll.inner_rect, scroll.state.offset);
                for shape in &output.shapes {
                    if let egui::Shape::Text(text) = &shape.shape {
                        if text.galley.job.text.starts_with("Minecraft") {
                            assert!(shape.clip_rect.max.y <= scroll.inner_rect.max.y);
                        }
                    }
                }
                for pressed in [true, false] {
                    network_list_frame(
                        &ctx,
                        app,
                        size,
                        true,
                        vec![
                            egui::Event::PointerMoved(button.center()),
                            egui::Event::PointerButton {
                                pos: button.center(),
                                button: egui::PointerButton::Primary,
                                pressed,
                                modifiers: Default::default(),
                            },
                        ],
                    );
                }
                assert_eq!(app.selected_network.as_ref(), Some(&last.network_id));
                assert!(app.page == Page::Networks);
            }
        }
    }
    #[test]
    fn network_selector_scrolls_horizontally_without_expanding_the_page() {
        for preference in [
            LanguagePreference::English,
            LanguagePreference::Portuguese,
            LanguagePreference::Russian,
            LanguagePreference::Vietnamese,
        ] {
            for width in [360., 1000.] {
                let mut fixture = Fixture::new();
                let app = fixture.app();
                many_joined_networks(app);
                app.settings.language = preference;
                let ctx = egui::Context::default();
                configure(&ctx);
                ctx.enable_accesskit();
                let size = Vec2::new(width, 500.);
                network_list_frame(&ctx, app, size, false, vec![]);
                let (output, mut scroll) = network_list_frame(&ctx, app, size, false, vec![]);
                assert!(scroll.inner_rect.max.x <= width - 24.);
                assert!(scroll.content_size.x > scroll.inner_rect.width());
                assert!(
                    scroll.content_size.y <= 40.,
                    "{preference:?} at {width}: the selector stays one row tall: {:?}, content {:?}", scroll.inner_rect, scroll.content_size
                );
                let long_name = node_rect(&output, &app.snapshot.networks[0].name);
                assert!(long_name.width() <= 240.);
                let filter = output
                    .platform_output
                    .accesskit_update
                    .as_ref()
                    .unwrap()
                    .nodes
                    .iter()
                    .find(|(_, node)| node.role() == egui::accesskit::Role::TextInput)
                    .unwrap()
                    .1
                    .bounds()
                    .unwrap();
                assert!(filter.x1 <= width as f64 - 24.);
                assert!(filter.y0 > (scroll.inner_rect.min.y + scroll.content_size.y) as f64);
                assert!(
                    filter.y0 < 100.,
                    "the selector and its scrollbar leave room for peer controls"
                );
                scroll.state.offset.x = scroll.content_size.x - scroll.inner_rect.width();
                scroll.state.store(&ctx, scroll.id);
                let (output, scroll) = network_list_frame(&ctx, app, size, false, vec![]);
                let last = app.snapshot.networks.last().unwrap().clone();
                let button = node_rect(&output, &last.name);
                assert!(
                    scroll.inner_rect.expand(0.5).contains_rect(button),
                    "{preference:?} at {width}: viewport {:?}, button {button:?}, offset {:?}",
                    scroll.inner_rect,
                    scroll.state.offset
                );
                for shape in &output.shapes {
                    if let egui::Shape::Text(text) = &shape.shape {
                        if text.galley.job.text.starts_with("Minecraft") {
                            assert!(shape.clip_rect.max.x <= scroll.inner_rect.max.x);
                        }
                    }
                }
                for pressed in [true, false] {
                    network_list_frame(
                        &ctx,
                        app,
                        size,
                        false,
                        vec![
                            egui::Event::PointerMoved(button.center()),
                            egui::Event::PointerButton {
                                pos: button.center(),
                                button: egui::PointerButton::Primary,
                                pressed,
                                modifiers: Default::default(),
                            },
                        ],
                    );
                }
                assert_eq!(app.selected_network.as_ref(), Some(&last.network_id));
            }
        }
    }
    #[test]
    fn favorites_pin_public_and_private_networks_by_member_count() {
        let favorites = ["Private LAN".into(), "Public LAN".into()]
            .into_iter()
            .collect();
        let public = vec![
            PublicNetwork {
                name: "Other".into(),
                reported_count: 100,
            },
            PublicNetwork {
                name: "Public LAN".into(),
                reported_count: 4,
            },
            PublicNetwork {
                name: "Private LAN".into(),
                reported_count: 9,
            },
            PublicNetwork {
                name: "Last".into(),
                reported_count: 200,
            },
        ];
        assert_eq!(
            ordered_public_networks(&public, &favorites)
                .iter()
                .map(|n| n.name.as_str())
                .collect::<Vec<_>>(),
            ["Private LAN", "Public LAN", "Other", "Last"]
        );
        let mut snapshot = Snapshot {
            networks: public
                .iter()
                .map(|n| Network {
                    name: n.name.clone(),
                    network_id: n.name.clone(),
                })
                .collect(),
            ..Default::default()
        };
        snapshot
            .roles
            .insert("Private LAN".into(), [(1, 1), (2, 1)].into_iter().collect());
        snapshot.roles.insert(
            "Public LAN".into(),
            [(1, 1), (2, 1), (3, 1)].into_iter().collect(),
        );
        assert_eq!(
            ordered_joined_networks(&snapshot, &favorites)
                .iter()
                .map(|n| n.name.as_str())
                .collect::<Vec<_>>(),
            ["Public LAN", "Private LAN", "Other", "Last"]
        );
        snapshot
            .roles
            .get_mut("Private LAN")
            .unwrap()
            .extend([(3, 1), (4, 1)]);
        assert_eq!(
            ordered_joined_networks(&snapshot, &favorites)[0].name,
            "Private LAN"
        );
    }
    #[test]
    fn connection_card_centers_its_header_and_keeps_actions_on_the_right() {
        for preference in [
            LanguagePreference::English,
            LanguagePreference::Portuguese,
            LanguagePreference::Russian,
            LanguagePreference::Vietnamese,
        ] {
            for width in [1000., 440.] {
                let mut fixture = Fixture::new();
                let app = fixture.app();
                app.settings.language = preference;
                app.phase = Phase::Connected;
                app.snapshot.interface_ready = true;
                app.snapshot.vip = Some(Ipv4Addr::new(26, 20, 95, 241));
                let ctx = egui::Context::default();
                configure(&ctx);
                ctx.enable_accesskit();
                let mut output = None;
                for _ in 0..2 {
                    let mut current = ctx.run_ui(
                        egui::RawInput {
                            screen_rect: Some(egui::Rect::from_min_size(
                                egui::Pos2::ZERO,
                                Vec2::new(width, 500.),
                            )),
                            ..Default::default()
                        },
                        |ui| {
                            egui::CentralPanel::default()
                                .frame(egui::Frame::new().inner_margin(24))
                                .show(ui, |ui| {
                                    egui::ScrollArea::vertical()
                                        .auto_shrink([false, false])
                                        .show(ui, |ui| app.hero(ui));
                                });
                        },
                    );
                    current.textures_delta.clear();
                    output = Some(current);
                }
                let output = output.unwrap();
                let nodes = &output
                    .platform_output
                    .accesskit_update
                    .as_ref()
                    .unwrap()
                    .nodes;
                let bounds = |text: &str| {
                    nodes
                        .iter()
                        .find(|(_, n)| n.label() == Some(text) || n.value() == Some(text))
                        .unwrap()
                        .1
                        .bounds()
                        .unwrap()
                };
                let button = bounds(app.language().text("Disconnect"));
                assert!(
                    (button.x1 - (width as f64 - 49.)).abs() < 3.,
                    "{preference:?}: button is not at the right edge: {button:?}"
                );
                let title = bounds(app.language().text("You’re connected"));
                let caption = bounds(app.language().text("Your devices are within reach"));
                let shield = output
                    .shapes
                    .iter()
                    .find_map(|shape| match &shape.shape {
                        egui::Shape::Circle(circle) if circle.radius == 26. => Some(circle.center),
                        _ => None,
                    })
                    .unwrap();
                assert!(
                    ((title.y0 + caption.y1) / 2. - shield.y as f64).abs() < 3.,
                    "{preference:?}: title and shield are not centered"
                );
                if width > 480. {
                    assert!(((button.y0 + button.y1) / 2. - shield.y as f64).abs() < 2.);
                } else {
                    assert!(
                        button.y0 > caption.y1,
                        "compact header places the action below the text"
                    );
                    assert!(
                        button.y0 - caption.y1 < 40.,
                        "the compact action stays close to the header inside a scrolling page"
                    );
                    let timing = bounds("00:00:00  ·  0 ms");
                    let copy = bounds(app.language().text("Copy"));
                    assert!(
                        timing.y0 - copy.y1 < 50.,
                        "{preference:?}: the compact footer stays close to the address row: timing {timing:?}, copy {copy:?}"
                    );
                }
                for (_, node) in nodes {
                    if node.role() == egui::accesskit::Role::Button {
                        if let Some(bounds) = node.bounds() {
                            assert!(bounds.x0 >= 0. && bounds.x1 <= width as f64);
                        }
                    }
                }
            }
        }
    }
    #[test]
    fn join_selection_starts_at_fifty_millisecond_intervals_and_matches_each_result() {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        let (backend, actions) = Backend::recording_fixture();
        app.backend = backend;
        app.phase = Phase::Connected;
        app.join_draft = vec![
            JoinNetwork {
                name: "Already here".into(),
                private: true,
            },
            JoinNetwork {
                name: "Public LAN".into(),
                private: false,
            },
            JoinNetwork {
                name: "Private LAN".into(),
                private: true,
            },
        ];
        app.snapshot.networks.push(Network {
            name: "Already here".into(),
            network_id: "a".into(),
        });
        let now = Instant::now();
        assert!(app.start_join(now).is_err());
        assert!(
            actions.try_recv().is_err(),
            "validate every password before sending any request"
        );
        app.join_passwords.insert(
            "Private LAN".into(),
            zeroize::Zeroizing::new("synthetic password".into()),
        );
        app.start_join(now).unwrap();
        assert!(app.join_passwords.is_empty());
        let ctx = egui::Context::default();
        app.advance_join(&ctx, now);
        assert!(
            matches!(actions.try_recv().unwrap(), Action::Engine(Command::Tagged { id: 1, command }) if matches!(&*command, Command::Network(NetworkRequest::Join { name, password: None }) if name == "Public LAN"))
        );
        app.advance_join(&ctx, now + Duration::from_millis(49));
        assert!(actions.try_recv().is_err());
        app.advance_join(&ctx, now + Duration::from_millis(50));
        assert!(
            matches!(actions.try_recv().unwrap(), Action::Engine(Command::Tagged { id: 2, command }) if matches!(&*command, Command::Network(NetworkRequest::Join { name, password: Some(_) }) if name == "Private LAN"))
        );
        assert!(app.mutation_busy());
        for (id, message, error) in [
            (99, "stale", false),
            (2, "refused", true),
            (1, "joined", false),
        ] {
            app.backend
                .notices
                .send(Notice::Engine(Update::CommandResult {
                    id,
                    message: message.into(),
                    error,
                    catalog: None,
                }));
            app.consume(&ctx);
            if id != 1 {
                assert!(app.mutation_busy());
            }
        }
        assert!(!app.mutation_busy());
        assert_eq!(app.join_run.results.len(), 3);
        assert!(app.join_run.results["Private LAN"].1);
        assert!(!app.join_run.results["Public LAN"].1);
        assert_eq!(app.join_run.results["Already here"].0, "Already joined");
    }
    #[test]
    fn changing_identity_interrupts_queued_joins_and_keeps_saved_lists() {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        let (backend, actions) = Backend::recording_fixture();
        app.backend = backend;
        app.phase = Phase::Connected;
        app.identity = Some((1, "old".into()));
        app.join_config_name = "Friends".into();
        app.join_draft = vec![
            JoinNetwork {
                name: "Public LAN".into(),
                private: false,
            },
            JoinNetwork {
                name: "Private LAN".into(),
                private: true,
            },
        ];
        app.network_preferences
            .favorites
            .insert("Private LAN".into());
        app.save_join_configuration().unwrap();
        app.join_passwords.insert(
            "Private LAN".into(),
            zeroize::Zeroizing::new("synthetic password".into()),
        );
        let now = Instant::now();
        app.start_join(now).unwrap();
        let ctx = egui::Context::default();
        app.advance_join(&ctx, now);
        assert!(actions.try_recv().is_ok());
        app.backend.notices.send(Notice::Identity {
            rid: 2,
            name: "new".into(),
        });
        app.consume(&ctx);
        app.advance_join(&ctx, now + Duration::from_millis(50));
        assert!(actions.try_recv().is_err());
        assert!(!app.join_run.active() && app.join_passwords.is_empty());
        app.join_draft.clear();
        app.load_join_configuration();
        assert_eq!(app.join_draft.len(), 2);
        assert!(app.network_preferences.favorites.contains("Private LAN"));
        assert!(app
            .paths
            .network_preferences()
            .unwrap()
            .join_configurations
            .contains_key("Friends"));
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
                (Page::AutoJoin, "Auto join"),
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
            for page in [Page::Networks, Page::Discover, Page::AutoJoin] {
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

    fn cached_peer(rid: u64, name: &str, ids: &[&str]) -> PeerView {
        PeerView {
            peer: Peer {
                rid,
                name: name.to_owned(),
                vip: Ipv4Addr::new(26, 0, 0, rid as u8),
                server: None,
                state: 1,
                network_ids: ids.iter().map(|id| (*id).to_owned()).collect(),
            },
            status: PeerState::Connected,
            detail: String::new(),
            transport: None,
        }
    }
    #[test]
    fn peer_cache_preserves_allocations_for_traffic_and_tracks_direct_roster_edits() {
        let mut snapshot = Snapshot::default();
        snapshot.peers.insert(1, cached_peer(1, "ALPHA", &["a"]));
        snapshot.peers.insert(2, cached_peer(2, "alpha", &["b"]));
        let mut settings = Settings {
            show_offline_peers: false,
            ..Settings::default()
        };
        let mut cache = PeerListCache::default();
        let ids = |cache: &PeerListCache| {
            cache
                .visible
                .iter()
                .map(|index| cache.peers[*index].rid)
                .collect::<Vec<_>>()
        };
        cache.refresh(&snapshot, None, "", &settings);
        assert_eq!(ids(&cache), [1, 2]);
        let revision = cache.revision;
        let name = cache.peers[0].normalized_name.as_ptr();
        let address = cache.peers[0].address.as_ptr();
        snapshot.traffic.sent_frames = 99;
        snapshot.peers.get_mut(&1).unwrap().detail = "Current detail".into();
        cache.refresh(&snapshot, None, "", &settings);
        assert_eq!(cache.revision, revision);
        assert_eq!(cache.peers[0].normalized_name.as_ptr(), name);
        assert_eq!(cache.peers[0].address.as_ptr(), address);
        cache.refresh(&snapshot, Some("a"), "ALPHA", &settings);
        assert_eq!(ids(&cache), [1]);
        snapshot.peers.get_mut(&1).unwrap().peer.name = "áLPHA".into();
        cache.refresh(&snapshot, Some("a"), "Ál", &settings);
        assert_eq!(ids(&cache), [1]);
        snapshot.peers.get_mut(&1).unwrap().status = PeerState::Offline;
        cache.refresh(&snapshot, Some("a"), "Ál", &settings);
        assert!(cache.visible.is_empty());
        settings.show_offline_peers = true;
        cache.refresh(&snapshot, Some("a"), "Ál", &settings);
        assert_eq!(ids(&cache), [1]);
        snapshot.peers.remove(&1);
        snapshot.peers.insert(3, cached_peer(3, "áLPHA", &["a"]));
        cache.refresh(&snapshot, Some("a"), "Ál", &settings);
        assert_eq!(ids(&cache), [3]);
    }
    #[test]
    fn network_caches_preserve_count_fallbacks_roles_and_join_selection_precedence() {
        let mut snapshot = Snapshot {
            networks: ["Zeta", "Alpha", "Unpinned"]
                .into_iter()
                .map(|name| Network {
                    name: name.into(),
                    network_id: name.into(),
                })
                .collect(),
            ..Default::default()
        };
        snapshot.peers.insert(1, cached_peer(1, "peer", &["Zeta"]));
        let mut preferences = NetworkPreferences {
            favorites: ["Zeta".into(), "Alpha".into()].into_iter().collect(),
            known_networks: [("Private".into(), true)].into_iter().collect(),
            ..Default::default()
        };
        let mut cache = NetworkListCache::default();
        cache.refresh(&snapshot, &preferences.favorites);
        assert_eq!(cache.counts["Zeta"], 2);
        assert_eq!(cache.counts["Alpha"], 1);
        assert_eq!(cache.ordered[0].name, "Zeta");
        let revision = cache.revision;
        let ordered = Arc::clone(&cache.ordered);
        snapshot.traffic.received_bytes = 123;
        cache.refresh(&snapshot, &preferences.favorites);
        assert_eq!(cache.revision, revision);
        assert!(Arc::ptr_eq(&ordered, &cache.ordered));
        snapshot.roles.insert(
            "Alpha".into(),
            [(1, 1), (2, 0), (3, 2)].into_iter().collect(),
        );
        cache.refresh(&snapshot, &preferences.favorites);
        assert_eq!(cache.ordered[0].name, "Alpha");
        let revision = cache.revision;
        snapshot.roles.get_mut("Alpha").unwrap().insert(1, 2);
        cache.refresh(&snapshot, &preferences.favorites);
        assert_eq!(cache.revision, revision);
        let catalog = vec![PublicNetwork {
            name: "Alpha".into(),
            reported_count: 999,
        }];
        let mut draft = vec![JoinNetwork {
            name: "Private".into(),
            private: false,
        }];
        let mut available = AutoJoinListCache::default();
        available.refresh(&cache, &preferences, &catalog, &draft);
        assert_eq!(
            available
                .ordered
                .iter()
                .map(|network| network.name.as_str())
                .collect::<Vec<_>>(),
            ["Alpha", "Zeta", "Private", "Unpinned"]
        );
        let alpha = &available.ordered[0];
        assert_eq!(alpha.member_count, 3);
        assert!(!alpha.private && alpha.joined);
        let private = &available.ordered[2];
        assert!(!private.private && private.selected && !private.joined);
        let ordered = Arc::clone(&available.ordered);
        available.refresh(&cache, &preferences, &catalog, &draft);
        assert!(Arc::ptr_eq(&ordered, &available.ordered));
        draft[0].private = true;
        available.refresh(&cache, &preferences, &catalog, &draft);
        assert!(available.ordered[2].private);
        preferences.favorites.insert("Private".into());
        cache.refresh(&snapshot, &preferences.favorites);
        available.refresh(&cache, &preferences, &catalog, &draft);
        assert!(available.ordered[2].favorite);
        snapshot.roles.insert("Zeta".into(), BTreeMap::new());
        cache.refresh(&snapshot, &preferences.favorites);
        assert_eq!(cache.counts["Zeta"], 0);
    }
    #[test]
    fn peer_rows_keep_exact_scroll_extent_and_remeasure_layout_changes() {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        app.phase = Phase::Connected;
        app.settings.language = LanguagePreference::English;
        app.settings.show_traffic = false;
        app.settings.show_peer_details = true;
        app.snapshot.networks = vec![Network {
            name: "LAN".into(),
            network_id: "a".into(),
        }];
        for rid in 1..=80 {
            app.snapshot.peers.insert(
                rid,
                cached_peer(rid, &format!("Synthetic peer {rid:03}"), &["a"]),
            );
        }
        let ctx = egui::Context::default();
        configure(&ctx);
        ctx.enable_accesskit();
        let render = |app: &mut App| {
            let mut extent = Vec2::ZERO;
            let mut output = ctx.run_ui(
                egui::RawInput {
                    screen_rect: Some(egui::Rect::from_min_size(
                        egui::Pos2::ZERO,
                        Vec2::new(780., 540.),
                    )),
                    ..Default::default()
                },
                |ui| {
                    egui::CentralPanel::default().show(ui, |ui| {
                        extent = egui::ScrollArea::vertical()
                            .auto_shrink([false, false])
                            .show(ui, |ui| app.networks(ui))
                            .content_size;
                    });
                },
            );
            output.textures_delta.clear();
            (output, extent)
        };
        // Warm the actual row measurements and scrollbar width.
        for _ in 0..3 {
            render(app);
        }
        let (output, extent) = render(app);
        assert_eq!(app.peer_rows.rows.len(), 80);
        assert!(
            labels(&output)
                .iter()
                .filter(|label| label.starts_with("Synthetic peer"))
                .count()
                < 20
        );
        let (_, next_extent) = render(app);
        assert!((extent.y - next_extent.y).abs() < 0.1);
        let old_height = app.peer_rows.rows[&80].height;
        app.settings.show_internal_ids = true;
        render(app);
        assert!(app.peer_rows.rows[&80].height >= old_height + 14.);
        let old_height = app.peer_rows.rows[&80].height;
        app.snapshot.peers.get_mut(&80).unwrap().detail =
            "Long synthetic connection detail that wraps across several lines. ".repeat(6);
        app.pings
            .insert(80, PingState::Failed("Synthetic ping failure".into()));
        render(app);
        assert!(app.peer_rows.rows[&80].height > old_height);
        let cached = &app.peer_rows.rows[&80];
        assert!(cached.matches(
            &app.snapshot.peers[&80],
            app.pings.get(&80),
            None,
            None,
            false
        ));
        app.snapshot.peers.remove(&80);
        render(app);
        assert!(!app.peer_rows.rows.contains_key(&80));
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
    fn tall_frame(
        ctx: &egui::Context,
        app: &mut App,
        events: Vec<egui::Event>,
    ) -> egui::FullOutput {
        let mut output = ctx.run_ui(
            egui::RawInput {
                screen_rect: Some(egui::Rect::from_min_size(
                    egui::Pos2::ZERO,
                    Vec2::new(780., 2600.),
                )),
                events,
                ..Default::default()
            },
            |ui| app.show(ui),
        );
        output.textures_delta.clear();
        output
    }

    fn click_button(ctx: &egui::Context, app: &mut App, text: &str) {
        tall_frame(ctx, app, vec![]);
        let output = tall_frame(ctx, app, vec![]);
        let bounds = output
            .platform_output
            .accesskit_update
            .unwrap()
            .nodes
            .into_iter()
            .find(|(_, node)| {
                node.role() == egui::accesskit::Role::Button && node.label() == Some(text)
            })
            .unwrap_or_else(|| panic!("missing button: {text}"))
            .1
            .bounds()
            .unwrap();
        let pos = egui::pos2(
            ((bounds.x0 + bounds.x1) / 2.) as f32,
            ((bounds.y0 + bounds.y1) / 2.) as f32,
        );
        for pressed in [true, false] {
            tall_frame(
                ctx,
                app,
                vec![
                    egui::Event::PointerMoved(pos),
                    egui::Event::PointerButton {
                        pos,
                        button: egui::PointerButton::Primary,
                        pressed,
                        modifiers: Default::default(),
                    },
                ],
            );
        }
    }
    #[test]
    fn favorite_and_saved_join_controls_work_in_every_language() {
        for preference in [
            LanguagePreference::English,
            LanguagePreference::Portuguese,
            LanguagePreference::Russian,
            LanguagePreference::Vietnamese,
        ] {
            let mut fixture = Fixture::new();
            let app = fixture.app();
            let (backend, actions) = Backend::recording_fixture();
            app.backend = backend;
            app.phase = Phase::Connected;
            app.page = Page::Discover;
            app.settings.language = preference;
            app.has_searched = true;
            app.catalog = vec![PublicNetwork {
                name: "Synthetic LAN".into(),
                reported_count: 4,
            }];
            let language = app.language();
            let ctx = egui::Context::default();
            configure(&ctx);
            ctx.enable_accesskit();
            click_button(&ctx, app, language.text("Add favorite"));
            assert!(app
                .paths
                .network_preferences()
                .unwrap()
                .favorites
                .contains("Synthetic LAN"));
            click_button(&ctx, app, language.text("Remove favorite"));
            assert!(app
                .paths
                .network_preferences()
                .unwrap()
                .favorites
                .is_empty());
            app.page = Page::AutoJoin;
            app.select_join_network(
                JoinNetwork {
                    name: "Synthetic LAN".into(),
                    private: false,
                },
                true,
            );
            app.join_config_name = "Friends".into();
            click_button(&ctx, app, language.text("Save configuration"));
            assert!(app
                .paths
                .network_preferences()
                .unwrap()
                .join_configurations
                .contains_key("Friends"));
            app.join_draft.clear();
            click_button(&ctx, app, language.text("Load configuration"));
            assert_eq!(app.join_draft.len(), 1);
            click_button(&ctx, app, language.text("Join selected networks"));
            assert!(
                matches!(actions.try_recv().unwrap(), Action::Engine(Command::Tagged { command, .. }) if matches!(&*command, Command::Network(NetworkRequest::Join { name, password: None }) if name == "Synthetic LAN"))
            );
        }
    }
    #[test]
    fn invalid_saved_network_preferences_are_preserved_for_repair() {
        let mut fixture = Fixture::new();
        drop(fixture.app.take());
        let paths = Paths::new(Some(fixture.directory.clone())).unwrap();
        let file = paths.directory.join("network-preferences.json");
        std::fs::write(&file, b"invalid json").unwrap();
        let lock = paths.lock().unwrap();
        let mut app = App::from_parts(Backend::fixture(), paths, lock, Settings::default());
        assert!(app.network_preferences_error.is_some());
        assert!(app
            .persist_network_preferences(NetworkPreferences::default())
            .is_err());
        assert_eq!(std::fs::read(file).unwrap(), b"invalid json");
    }

    #[test]
    fn live_search_debounces_typing_and_discards_stale_results() {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        let (backend, actions) = Backend::recording_fixture();
        app.backend = backend;
        app.phase = Phase::Connected;
        app.page = Page::Discover;
        app.settings.language = LanguagePreference::English;
        let ctx = egui::Context::default();
        configure(&ctx);
        ctx.enable_accesskit();
        app.search_due = Some(Instant::now() + Duration::from_secs(1));
        app.focus_search = true;
        frame(&ctx, app, vec![]);
        frame(&ctx, app, vec![egui::Event::Text("mine".into())]);
        assert_eq!(app.query, "mine");
        assert!(
            actions.try_recv().is_err(),
            "typing never submits before the debounce"
        );
        assert!(
            app.search_due
                .unwrap()
                .saturating_duration_since(Instant::now())
                <= SEARCH_DEBOUNCE
        );
        app.search_due = Some(Instant::now() - Duration::from_millis(1));
        frame(&ctx, app, vec![]);
        assert!(
            matches!(actions.try_recv().unwrap(), Action::Engine(Command::Search { query, cursor: 0 }) if query == "mine")
        );
        frame(&ctx, app, vec![egui::Event::Text("craft".into())]);
        assert_eq!(app.query, "minecraft");
        assert!(
            actions.try_recv().is_err(),
            "the input remains editable during the previous search"
        );
        app.backend.notices.send(Notice::Engine(Update::Catalog {
            query: "mine".into(),
            networks: vec![PublicNetwork {
                name: "stale result".into(),
                reported_count: 1,
            }],
            cursor: 2,
            append: false,
        }));
        app.consume(&ctx);
        assert!(
            app.catalog.is_empty(),
            "old search results never replace the current query"
        );
        app.search_due = Some(Instant::now() - Duration::from_millis(1));
        frame(&ctx, app, vec![]);
        assert!(
            matches!(actions.try_recv().unwrap(), Action::Engine(Command::Search { query, cursor: 0 }) if query == "minecraft")
        );
        app.backend.notices.send(Notice::Engine(Update::Catalog {
            query: "minecraft".into(),
            networks: vec![PublicNetwork {
                name: "Minecraft friends".into(),
                reported_count: 4,
            }],
            cursor: 0,
            append: false,
        }));
        app.consume(&ctx);
        assert_eq!(app.catalog[0].name, "Minecraft friends");
        assert!(!labels(&frame(&ctx, app, vec![]))
            .iter()
            .any(|label| label == "Search"));
    }

    #[test]
    fn update_notice_survives_navigation_and_snapshots_until_manually_dismissed() {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        app.settings.language = LanguagePreference::English;
        let release = openrad::releases::Release {
            version: "v1.0.0".into(),
            url: "https://github.com/gringoestrangeiro/openrad/releases/tag/v1.0.0".into(),
        };
        let ctx = egui::Context::default();
        configure(&ctx);
        ctx.enable_accesskit();
        app.backend.notices.send(Notice::Release(release.clone()));
        app.consume(&ctx);
        for page in [
            Page::Networks,
            Page::Discover,
            Page::AutoJoin,
            Page::Settings,
        ] {
            app.page = page;
            frame(&ctx, app, vec![]);
            assert!(app.release.is_some());
        }
        app.backend
            .notices
            .send(Notice::Engine(Update::State(Snapshot::default())));
        app.consume(&ctx);
        app.toast = Some((
            "Settings saved".into(),
            false,
            Instant::now() - Duration::from_secs(30),
        ));
        app.page = Page::Networks;
        frame(&ctx, app, vec![]);
        assert!(app.release.is_some());
        click_button(&ctx, app, "×");
        assert!(app.release.is_none());
        app.backend.notices.send(Notice::Release(release));
        app.consume(&ctx);
        assert!(
            app.release.is_none(),
            "periodic checks do not reopen a dismissed version"
        );
    }

    #[test]
    fn peer_rtt_button_and_retained_failure_are_translated_in_every_language() {
        for preference in [
            LanguagePreference::English,
            LanguagePreference::Portuguese,
            LanguagePreference::Russian,
            LanguagePreference::Vietnamese,
        ] {
            let mut fixture = Fixture::new();
            let app = fixture.app();
            let (backend, actions) = Backend::recording_fixture();
            app.backend = backend;
            app.settings.language = preference;
            let language = app.language();
            app.phase = Phase::Connected;
            app.snapshot.networks.push(openrad::protocol::Network {
                name: "Friends".into(),
                network_id: "a".into(),
            });
            app.snapshot.peers.insert(
                2,
                PeerView {
                    peer: Peer {
                        rid: 2,
                        name: "Synthetic friend".into(),
                        vip: Ipv4Addr::new(26, 0, 0, 2),
                        server: None,
                        state: 1,
                        network_ids: Default::default(),
                    },
                    status: PeerState::Connected,
                    detail: String::new(),
                    transport: Some(openrad::peer::TransportPath::Relay),
                },
            );
            let ctx = egui::Context::default();
            configure(&ctx);
            ctx.enable_accesskit();
            click_button(&ctx, app, language.text("Test RTT"));
            assert!(matches!(
                actions.try_recv().unwrap(),
                Action::Engine(Command::Tagged { id: 1, command }) if matches!(*command, Command::Ping { peer: 2 })
            ));
            assert!(matches!(app.pings.get(&2), Some(PingState::Pending { .. })));
            app.backend.notices.send(Notice::Engine(Update::Ping {
                peer: 2,
                id: Some(1),
                rtt_ms: Some(12.5),
                error: None,
            }));
            app.consume(&ctx);
            let output = tall_frame(&ctx, app, vec![]);
            assert!(labels(&output)
                .iter()
                .any(|label| label == &language.format("RTT: {ms} ms", &[("ms", "12.5")])));
            app.pings.insert(
                2,
                PingState::Pending {
                    id: 2,
                    started: Instant::now(),
                },
            );
            app.backend.notices.send(Notice::Engine(Update::Ping {
                peer: 2,
                id: Some(2),
                rtt_ms: None,
                error: Some(runtime::PING_TIMEOUT_MESSAGE.into()),
            }));
            app.consume(&ctx);
            tall_frame(&ctx, app, vec![]);
            let current_labels = labels(&tall_frame(&ctx, app, vec![]));
            assert!(
                current_labels
                    .iter()
                    .any(|label| label == language.text(runtime::PING_TIMEOUT_MESSAGE)),
                "missing ping error in {language:?}: {current_labels:?}"
            );
        }
    }
    #[cfg(target_os = "linux")]
    #[test]
    #[ignore = "renders synthetic many-network layouts with a Vulkan CPU driver"]
    fn capture_many_networks_without_profiles_credentials_or_a_display() {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        many_joined_networks(app);
        app.settings.language = LanguagePreference::Portuguese;
        app.phase = Phase::Connected;
        app.snapshot.vip = Some(Ipv4Addr::new(26, 0, 0, 1));
        app.snapshot.latency_ms = 140;
        app.snapshot.networks[0].name = "Minecraft [Português 000]".into();
        app.snapshot.networks[1].name = format!("Minecraft [nome longo] {}", "á".repeat(230));
        app.network_preferences
            .favorites
            .insert(app.snapshot.networks[0].name.clone());
        app.network_preferences
            .favorites
            .insert(app.snapshot.networks[2].name.clone());
        app.snapshot.peers.insert(
            2,
            PeerView {
                peer: Peer {
                    rid: 2,
                    name: "Synthetic friend".into(),
                    vip: Ipv4Addr::new(26, 0, 0, 2),
                    server: None,
                    state: 1,
                    network_ids: ["synthetic-0".into()].into_iter().collect(),
                },
                status: PeerState::Connected,
                detail: String::new(),
                transport: Some(openrad::peer::TransportPath::DirectUdp),
            },
        );
        let directory =
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../reports/network-overflow");
        std::fs::create_dir_all(&directory).unwrap();
        for (filename, width, height, scale) in [
            ("many-networks", 1600, 864, 1.),
            ("compact-networks", 832, 640, 1.),
            ("scaled-networks", 832, 800, 1.5),
        ] {
            let ctx = egui::Context::default();
            configure(&ctx);
            ctx.set_zoom_factor(scale);
            let mut textures = egui::TexturesDelta::default();
            let mut final_output = None;
            for _ in 0..3 {
                let mut output = ctx.run_ui(
                    egui::RawInput {
                        screen_rect: Some(egui::Rect::from_min_size(
                            egui::Pos2::ZERO,
                            Vec2::new(width as f32, height as f32) / scale,
                        )),
                        ..Default::default()
                    },
                    |ui| app.show(ui),
                );
                textures.append(std::mem::take(&mut output.textures_delta));
                output.textures_delta.clear();
                final_output = Some(output);
            }
            let mut output = final_output.unwrap();
            output.textures_delta = textures;
            let pixels =
                crate::graphics::software_test::render_offscreen(&ctx, output, width, height);
            let mut ppm = format!("P6\n{width} {height}\n255\n").into_bytes();
            for pixel in pixels.as_chunks::<4>().0 {
                ppm.extend(&pixel[..3]);
            }
            std::fs::write(directory.join(format!("{filename}.ppm")), ppm).unwrap();
        }
    }
    #[cfg(target_os = "linux")]
    #[test]
    #[ignore = "renders synthetic UI screenshots with a Vulkan CPU driver"]
    fn capture_new_features_without_profiles_credentials_or_a_display() {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        app.settings.language = LanguagePreference::English;
        app.phase = Phase::Connected;
        app.settings.show_traffic = false;
        app.identity = Some((1, "my-openrad-device".into()));
        app.settings.node_name = "my-openrad-device".into();
        app.saved_settings = app.settings.clone();
        app.snapshot.vip = Some(Ipv4Addr::new(26, 0, 0, 1));
        app.snapshot.interface_ready = true;
        app.snapshot.networks.push(openrad::protocol::Network {
            name: "Friends LAN".into(),
            network_id: "a".into(),
        });
        for (rid, name, transport) in [
            (2, "Alex", openrad::peer::TransportPath::Relay),
            (3, "Maya", openrad::peer::TransportPath::DirectUdp),
        ] {
            app.snapshot.peers.insert(
                rid,
                PeerView {
                    peer: Peer {
                        rid,
                        name: name.into(),
                        vip: Ipv4Addr::new(26, 0, 0, rid as u8),
                        server: None,
                        state: 1,
                        network_ids: ["a".into()].into_iter().collect(),
                    },
                    status: PeerState::Connected,
                    detail: String::new(),
                    transport: Some(transport),
                },
            );
        }
        app.pings.insert(2, PingState::Measured(18.4));
        app.pings
            .insert(3, PingState::Failed(runtime::PING_TIMEOUT_MESSAGE.into()));
        app.release = Some(openrad::releases::Release {
            version: "v1.0.0".into(),
            url: "https://github.com/gringoestrangeiro/openrad/releases/tag/v1.0.0".into(),
        });
        app.has_searched = true;
        app.query = "minecraft".into();
        app.catalog_query = app.query.clone();
        app.catalog.push(PublicNetwork {
            name: "Minecraft friends".into(),
            reported_count: 12,
        });
        app.snapshot.latency_ms = 143;
        app.release = None;
        app.network_preferences.favorites = ["Friends LAN".into(), "Minecraft friends".into()]
            .into_iter()
            .collect();
        app.join_draft = vec![
            JoinNetwork {
                name: "Friends LAN".into(),
                private: true,
            },
            JoinNetwork {
                name: "Minecraft friends".into(),
                private: false,
            },
            JoinNetwork {
                name: "Weekend LAN".into(),
                private: true,
            },
        ];
        app.join_config_name = "Evening games".into();
        app.join_config_to_load = app.join_config_name.clone();
        app.network_preferences
            .join_configurations
            .insert(app.join_config_name.clone(), app.join_draft.clone());
        let directory =
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../reports/network-features");
        std::fs::create_dir_all(&directory).unwrap();
        for (page, filename, language, width) in [
            (
                Page::Networks,
                "favorites-and-alignment",
                LanguagePreference::English,
                1152,
            ),
            (
                Page::Discover,
                "public-favorites",
                LanguagePreference::English,
                1152,
            ),
            (
                Page::AutoJoin,
                "auto-join",
                LanguagePreference::English,
                1152,
            ),
            (
                Page::Networks,
                "alignment-portuguese",
                LanguagePreference::Portuguese,
                768,
            ),
            (
                Page::AutoJoin,
                "auto-join-portuguese",
                LanguagePreference::Portuguese,
                768,
            ),
            (
                Page::Networks,
                "tap-authorization-portuguese",
                LanguagePreference::Portuguese,
                768,
            ),
        ] {
            app.snapshot.interface_ready = filename != "tap-authorization-portuguese";
            app.snapshot.interface_error = (filename == "tap-authorization-portuguese").then(||
                "TAP authorization failed; allow the system permission dialog and retry. A running Polkit authentication agent is required.".into());
            app.page = page;
            app.settings.language = language;
            let ctx = egui::Context::default();
            configure(&ctx);
            let mut textures = egui::TexturesDelta::default();
            let mut final_output = None;
            for _ in 0..2 {
                let mut output = ctx.run_ui(
                    egui::RawInput {
                        screen_rect: Some(egui::Rect::from_min_size(
                            egui::Pos2::ZERO,
                            Vec2::new(width as f32, 1000.),
                        )),
                        ..Default::default()
                    },
                    |ui| app.show(ui),
                );
                textures.append(std::mem::take(&mut output.textures_delta));
                output.textures_delta.clear();
                final_output = Some(output);
            }
            let mut output = final_output.unwrap();
            output.textures_delta = textures;
            let pixels =
                crate::graphics::software_test::render_offscreen(&ctx, output, width, 1000);
            let mut ppm = format!("P6\n{width} 1000\n255\n").into_bytes();
            for pixel in pixels.as_chunks::<4>().0 {
                ppm.extend(&pixel[..3]);
            }
            std::fs::write(directory.join(format!("{filename}.ppm")), ppm).unwrap();
        }
    }
    #[test]
    fn registered_device_name_remains_editable_and_relay_toggle_is_saved_in_every_language() {
        for preference in [
            LanguagePreference::English,
            LanguagePreference::Portuguese,
            LanguagePreference::Russian,
            LanguagePreference::Vietnamese,
        ] {
            let mut fixture = Fixture::new();
            let app = fixture.app();
            let (backend, actions) = Backend::recording_fixture();
            app.backend = backend;
            app.page = Page::Settings;
            app.settings.language = preference;
            app.settings.node_name = "old-device".into();
            app.saved_settings = app.settings.clone();
            app.identity = Some((123, "old-device".into()));
            let language = app.language();
            let ctx = egui::Context::default();
            configure(&ctx);
            ctx.enable_accesskit();
            tall_frame(&ctx, app, vec![]);
            let output = tall_frame(&ctx, app, vec![]);
            let bounds = output
                .platform_output
                .accesskit_update
                .unwrap()
                .nodes
                .into_iter()
                .find(|(_, node)| {
                    node.role() == egui::accesskit::Role::TextInput
                        && node.value() == Some("old-device")
                })
                .unwrap()
                .1
                .bounds()
                .unwrap();
            let pos = egui::pos2(
                ((bounds.x0 + bounds.x1) / 2.) as f32,
                ((bounds.y0 + bounds.y1) / 2.) as f32,
            );
            for pressed in [true, false] {
                tall_frame(
                    &ctx,
                    app,
                    vec![
                        egui::Event::PointerMoved(pos),
                        egui::Event::PointerButton {
                            pos,
                            button: egui::PointerButton::Primary,
                            pressed,
                            modifiers: Default::default(),
                        },
                    ],
                );
            }
            tall_frame(
                &ctx,
                app,
                vec![
                    egui::Event::Key {
                        key: egui::Key::A,
                        physical_key: None,
                        pressed: true,
                        repeat: false,
                        modifiers: egui::Modifiers {
                            ctrl: true,
                            command: true,
                            ..Default::default()
                        },
                    },
                    egui::Event::Text("renamed-device".into()),
                ],
            );
            assert_eq!(
                app.settings.node_name, "renamed-device",
                "{preference:?}: {bounds:?}"
            );
            let output = tall_frame(&ctx, app, vec![]);
            let bounds = output
                .platform_output
                .accesskit_update
                .unwrap()
                .nodes
                .into_iter()
                .find(|(_, node)| node.label() == Some(language.text("Force Relay")))
                .unwrap()
                .1
                .bounds()
                .unwrap();
            let pos = egui::pos2(
                ((bounds.x0 + bounds.x1) / 2.) as f32,
                ((bounds.y0 + bounds.y1) / 2.) as f32,
            );
            for pressed in [true, false] {
                tall_frame(
                    &ctx,
                    app,
                    vec![
                        egui::Event::PointerMoved(pos),
                        egui::Event::PointerButton {
                            pos,
                            button: egui::PointerButton::Primary,
                            pressed,
                            modifiers: Default::default(),
                        },
                    ],
                );
            }
            assert!(app.settings.force_relay);
            click_button(&ctx, app, language.text("Save preferences"));
            let Action::Save(saved) = actions.try_recv().unwrap() else {
                panic!("expected saved preferences");
            };
            assert_eq!(saved.node_name, "renamed-device");
            assert!(saved.force_relay);
            app.paths.save_settings(&saved).unwrap();
            let persisted = app.paths.settings().unwrap();
            assert_eq!(persisted.node_name, "renamed-device");
            assert!(persisted.force_relay);
        }
    }
    #[test]
    fn failed_identity_reset_leaves_busy_phase_and_keeps_storage_retry_available() {
        for pending in [false, true] {
            let mut fixture = Fixture::new();
            let app = fixture.app();
            let ctx = egui::Context::default();
            app.identity = Some((123, "synthetic-device".into()));
            app.phase = Phase::Resetting;
            app.busy = true;
            app.backend
                .notices
                .send(Notice::ReplacementPending(pending));
            app.backend.notices.send(Notice::Phase(
                Phase::Error,
                "Identity reset failed: credential store unavailable".into(),
            ));
            app.consume(&ctx);
            assert_eq!(app.phase, Phase::Error);
            assert!(!app.busy);
            assert_eq!(app.identity.as_ref().unwrap().0, 123);
            assert_eq!(app.replacement_pending, pending);
            assert!(app.message.contains("Identity reset failed"));
        }
    }

    #[cfg(target_os = "linux")]
    #[test]
    #[ignore = "renders synthetic reset UI screenshots with a Vulkan CPU driver"]
    fn capture_identity_reset_states_without_profiles_credentials_or_a_display() {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        app.settings.language = LanguagePreference::English;
        app.settings.show_traffic = false;
        app.identity = Some((123, "synthetic-device".into()));
        let directory = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../reports/identity-reset");
        std::fs::create_dir_all(&directory).unwrap();
        for (filename, phase, page, message, pending) in [
            ("reset-progress", Phase::Resetting, Page::Networks, "Connecting to identity server 192.0.2.1 (attempt 2/3)…", false),
            ("reset-error", Phase::Error, Page::Networks, "Identity reset failed: VPN service did not stop within 10 seconds. Try again shortly.", false),
            ("reset-save-retry", Phase::Error, Page::Settings, "Identity reset failed: Could not save the new identity after 3 attempts. Keep the window open and retry saving.", true),
        ] {
            app.phase = phase;
            app.page = page;
            app.message = message.into();
            app.replacement_pending = pending;
            app.activity.clear();
            app.log(message.to_owned());
            let ctx = egui::Context::default();
            configure(&ctx);
            let (width, height) = (1152, 864);
            let mut textures = egui::TexturesDelta::default();
            let mut final_output = None;
            for frame in 0..4 {
                let events = if page == Page::Settings && frame >= 1 { vec![
                    egui::Event::PointerMoved(egui::pos2(900., 400.)),
                    egui::Event::MouseWheel { unit: egui::MouseWheelUnit::Point, delta: egui::vec2(0., -10000.), modifiers: Default::default(), phase: egui::TouchPhase::Move },
                ] } else { vec![] };
                let mut output = ctx.run_ui(egui::RawInput {
                    screen_rect: Some(egui::Rect::from_min_size(egui::Pos2::ZERO, Vec2::new(width as f32, height as f32))),
                    events,
                    ..Default::default()
                }, |ui| app.show(ui));
                textures.append(std::mem::take(&mut output.textures_delta));
                output.textures_delta.clear();
                final_output = Some(output);
            }
            let mut output = final_output.unwrap();
            output.textures_delta = textures;
            let pixels = crate::graphics::software_test::render_offscreen(&ctx, output, width, height);
            let mut ppm = format!("P6\n{width} {height}\n255\n").into_bytes();
            for pixel in pixels.as_chunks::<4>().0 { ppm.extend(&pixel[..3]); }
            std::fs::write(directory.join(format!("{filename}.ppm")), ppm).unwrap();
        }
    }

    #[test]
    fn rtt_timeout_remains_bounded_when_control_stalls_and_old_replies_cannot_replace_a_new_probe()
    {
        let mut fixture = Fixture::new();
        let app = fixture.app();
        let ctx = egui::Context::default();
        app.pings.insert(
            2,
            PingState::Pending {
                id: 1,
                started: Instant::now() - runtime::PING_TIMEOUT,
            },
        );
        app.backend.notices.send(Notice::Engine(Update::Ping {
            peer: 2,
            id: Some(1),
            rtt_ms: Some(3001.),
            error: None,
        }));
        app.consume(&ctx);
        assert!(
            matches!(app.pings.get(&2), Some(PingState::Failed(message)) if message == runtime::PING_TIMEOUT_MESSAGE)
        );
        app.pings.insert(
            2,
            PingState::Pending {
                id: 2,
                started: Instant::now(),
            },
        );
        app.backend.notices.send(Notice::Engine(Update::Ping {
            peer: 2,
            id: Some(1),
            rtt_ms: Some(14.),
            error: None,
        }));
        app.consume(&ctx);
        assert!(matches!(
            app.pings.get(&2),
            Some(PingState::Pending { id: 2, .. })
        ));
        app.backend.notices.send(Notice::Engine(Update::Ping {
            peer: 2,
            id: Some(2),
            rtt_ms: Some(17.),
            error: None,
        }));
        app.consume(&ctx);
        assert!(matches!(app.pings.get(&2), Some(PingState::Measured(ms)) if *ms == 17.));
    }
}
