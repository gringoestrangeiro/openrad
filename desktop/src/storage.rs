//! Persistent settings contain no credentials. The whole reusable identity is
//! stored in the OS credential store, never in an egui persistence file or log.
use anyhow::{bail, ensure, Context, Result};
use openrad::{i18n::LanguagePreference, protocol::Identity};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;

const PENDING: &[u8] = b"openrad-provisioning-pending-v1";
pub const MAX_JOIN_NETWORKS: usize = 128;

/// Network names and access types survive identity replacement. Passwords are
/// deliberately absent from this file and from saved join configurations.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct JoinNetwork {
    pub name: String,
    pub private: bool,
}

#[derive(Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct NetworkPreferences {
    pub favorites: BTreeSet<String>,
    pub known_networks: BTreeMap<String, bool>,
    pub join_configurations: BTreeMap<String, Vec<JoinNetwork>>,
}
impl NetworkPreferences {
    pub fn validate(&self) -> Result<()> {
        ensure!(
            self.favorites.len() <= 512 && self.known_networks.len() <= 512,
            "Too many saved networks"
        );
        for name in self.favorites.iter().chain(self.known_networks.keys()) {
            openrad::network::validate_name(name)?;
        }
        ensure!(
            self.join_configurations.len() <= 32,
            "Too many saved join configurations"
        );
        for (name, networks) in &self.join_configurations {
            ensure!(
                (1..=80).contains(&name.chars().count())
                    && !name.trim().is_empty()
                    && !name.chars().any(char::is_control),
                "Configuration name must contain 1–80 characters without control characters"
            );
            ensure!(
                !networks.is_empty() && networks.len() <= MAX_JOIN_NETWORKS,
                "Select between 1 and 128 networks"
            );
            let mut names = BTreeSet::new();
            for network in networks {
                openrad::network::validate_name(&network.name)?;
                ensure!(
                    names.insert(&network.name),
                    "Join configuration contains a duplicate network"
                );
            }
        }
        Ok(())
    }
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StartPage {
    #[default]
    Networks,
    Discover,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PeerSort {
    #[default]
    Name,
    Status,
    Address,
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub language: LanguagePreference,
    pub auto_connect: bool,
    pub auto_reconnect: bool,
    pub reconnect_attempts: u32,
    pub reconnect_base_delay_seconds: u64,
    pub node_name: String,
    pub scale: f32,
    pub start_page: StartPage,
    pub show_traffic: bool,
    pub show_traffic_graphs: bool,
    pub decimal_units: bool,
    pub show_offline_peers: bool,
    pub peer_sort: PeerSort,
    pub show_recent_activity: bool,
    pub recent_activity_count: usize,
    pub show_peer_details: bool,
    pub show_internal_ids: bool,
    pub show_diagnostics: bool,
    pub force_relay: bool,
}
impl Default for Settings {
    fn default() -> Self {
        Self {
            language: LanguagePreference::System,
            auto_connect: true,
            auto_reconnect: true,
            reconnect_attempts: 3,
            reconnect_base_delay_seconds: 2,
            node_name: if cfg!(windows) {
                "openrad-windows"
            } else {
                "openrad-linux"
            }
            .into(),
            scale: 1.0,
            start_page: StartPage::Networks,
            show_traffic: true,
            show_traffic_graphs: true,
            decimal_units: false,
            show_offline_peers: true,
            peer_sort: PeerSort::Name,
            show_recent_activity: true,
            recent_activity_count: 12,
            show_peer_details: false,
            show_internal_ids: false,
            show_diagnostics: false,
            force_relay: false,
        }
    }
}
impl Settings {
    fn normalized(mut self) -> Self {
        if !self.scale.is_finite() {
            self.scale = 1.0;
        }
        self.scale = self.scale.clamp(0.8, 1.5);
        self.reconnect_attempts = self.reconnect_attempts.clamp(1, 10);
        self.reconnect_base_delay_seconds = self.reconnect_base_delay_seconds.clamp(1, 30);
        self.recent_activity_count = self.recent_activity_count.clamp(5, 40);
        self
    }
}
#[derive(Clone)]
pub struct Paths {
    pub directory: PathBuf,
}
impl Paths {
    pub fn new(override_path: Option<PathBuf>) -> Result<Self> {
        let directory = openrad::daemon::DataDir::open(override_path)?.path;
        if !directory.exists() {
            let mut builder = fs::DirBuilder::new();
            builder.recursive(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::DirBuilderExt;
                builder.mode(0o700);
            }
            builder.create(&directory)?;
        }
        let directory = directory.canonicalize()?;
        openrad::output::secure_directory(&directory)?;
        Ok(Self { directory })
    }
    pub fn lock(&self) -> Result<File> {
        let file = private_file(&self.directory.join("desktop.lock"), false)?;
        file.try_lock()
            .context("OpenRad is already running with this profile")?;
        Ok(file)
    }
    pub fn settings(&self) -> Result<Settings> {
        let path = self.directory.join("settings.json");
        let Some(bytes) = openrad::file_io::read_optional_bounded(&path, 64 * 1024 - 1)? else {
            return Ok(Settings::default());
        };
        let settings: Settings = serde_json::from_slice(&bytes)?;
        Ok(settings.normalized())
    }
    pub fn save_settings(&self, settings: &Settings) -> Result<()> {
        openrad::protocol::validate_node_name(&settings.node_name)?;
        ensure!(
            settings == &settings.clone().normalized(),
            "Settings are out of range"
        );
        let target = self.directory.join("settings.json");
        let tmp = self
            .directory
            .join(format!("settings-{}.tmp", std::process::id()));
        let mut file = private_file(&tmp, true)?;
        file.write_all(&serde_json::to_vec_pretty(settings)?)?;
        file.sync_all()?;
        fs::rename(tmp, target)?;
        Ok(())
    }
    pub fn network_preferences(&self) -> Result<NetworkPreferences> {
        let path = self.directory.join("network-preferences.json");
        let Some(bytes) = openrad::file_io::read_optional_bounded(&path, 1024 * 1024)? else {
            return Ok(NetworkPreferences::default());
        };
        let preferences: NetworkPreferences = serde_json::from_slice(&bytes)?;
        preferences.validate()?;
        Ok(preferences)
    }
    pub fn save_network_preferences(&self, preferences: &NetworkPreferences) -> Result<()> {
        preferences.validate()?;
        let bytes = serde_json::to_vec_pretty(preferences)?;
        ensure!(
            bytes.len() <= 1024 * 1024,
            "Network preferences file is too large"
        );
        let target = self.directory.join("network-preferences.json");
        let tmp = self
            .directory
            .join(format!("network-preferences-{}.tmp", std::process::id()));
        let result = (|| -> Result<()> {
            let mut file = private_file(&tmp, true)?;
            file.write_all(&bytes)?;
            file.sync_all()?;
            fs::rename(&tmp, target)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&tmp);
        }
        result
    }
    pub fn entry(&self) -> Result<keyring::Entry> {
        keyring::Entry::new("org.openrad.desktop", &self.directory.to_string_lossy())
            .map_err(|_| anyhow::anyhow!(if cfg!(windows) {
                "Windows Credential Manager is unavailable. Check your Windows account's credential store, then restart OpenRad."
            } else {
                "Credential store unavailable. Start and unlock Secret Service (GNOME Keyring or KWallet), then restart OpenRad."
            }))
    }
}
fn private_file(path: &Path, exclusive: bool) -> Result<File> {
    let mut options = OpenOptions::new();
    options.write(true).read(true);
    if exclusive {
        options.create_new(true);
    } else {
        options.create(true);
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600).custom_flags(libc_no_follow());
    }
    Ok(options.open(path)?)
}
#[cfg(unix)]
fn libc_no_follow() -> i32 {
    // std has no cross-platform open-without-following-symlinks flag.
    #[cfg(target_os = "linux")]
    {
        0x20000
    }
    #[cfg(not(target_os = "linux"))]
    {
        0
    }
}

pub trait Vault {
    fn get(&self) -> Result<Option<Zeroizing<Vec<u8>>>>;
    fn set(&self, secret: &[u8]) -> Result<()>;
}
impl Vault for keyring::Entry {
    fn get(&self) -> Result<Option<Zeroizing<Vec<u8>>>> {
        match self.get_secret() {
            Ok(bytes) => Ok(Some(Zeroizing::new(bytes))),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(_) => bail!("Could not read the saved identity. Unlock your credential store; your identity has not been replaced."),
        }
    }
    fn set(&self, bytes: &[u8]) -> Result<()> {
        self.set_secret(bytes).map_err(|_| anyhow::anyhow!(if cfg!(windows) {
            "Could not save the identity in Windows Credential Manager. Keep this window open, check your Windows account's credential store, then retry."
        } else {
            "Could not save the identity in the credential store. Keep this window open, unlock your keyring, then retry."
        }))
    }
}
pub fn load(vault: &impl Vault) -> Result<Option<Identity>> {
    match vault.get()? {
        None => Ok(None),
        Some(bytes) if bytes.as_slice() == PENDING => bail!("An earlier registration did not finish saving. Import a saved identity to recover; OpenRad will not create another identity automatically."),
        Some(bytes) => Ok(Some(Identity::from_secret(&bytes)?)),
    }
}
pub fn save(vault: &impl Vault, id: &Identity) -> Result<()> {
    let bytes = Zeroizing::new(serde_json::to_vec(id)?);
    vault.set(&bytes)
}
pub fn provision_once(
    vault: &impl Vault,
    provision: impl FnOnce(&mut dyn FnMut() -> Result<()>) -> Result<Identity>,
) -> Result<Identity> {
    if let Some(id) = load(vault)? {
        return Ok(id);
    }
    // Persist the intent right before the registering message is sent. A crash or
    // ambiguous failure after that point must never turn a later launch into an
    // extra identity registration; a failure before it (no network yet) leaves
    // nothing behind, so the next attempt can provision normally.
    provision(&mut || vault.set(PENDING))
}

/// An explicitly confirmed replacement. Keep the issued identity in memory when
/// the vault is locked, so retrying storage never registers another device.
#[derive(Default)]
pub struct Replacement {
    pending: Option<Identity>,
}
impl Replacement {
    pub fn is_pending(&self) -> bool {
        self.pending.is_some()
    }
    pub fn prepare(&mut self, provision: impl FnOnce() -> Result<Identity>) -> Result<()> {
        if self.pending.is_none() {
            self.pending = Some(provision()?);
        }
        Ok(())
    }
    pub fn commit(&mut self, vault: &impl Vault, current: &mut Option<Identity>) -> Result<()> {
        let next = self
            .pending
            .as_ref()
            .context("No replacement identity to save")?;
        save(vault, next)?;
        *current = self.pending.take();
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::{Cell, RefCell};
    #[derive(Default)]
    struct MemoryVault {
        data: RefCell<Option<Vec<u8>>>,
        fail: Cell<bool>,
    }
    impl Vault for MemoryVault {
        fn get(&self) -> Result<Option<Zeroizing<Vec<u8>>>> {
            Ok(self.data.borrow().clone().map(Zeroizing::new))
        }
        fn set(&self, v: &[u8]) -> Result<()> {
            ensure!(!self.fail.get(), "locked");
            *self.data.borrow_mut() = Some(v.to_vec());
            Ok(())
        }
    }
    fn identity() -> Identity {
        Identity::from_secret(br#"{"format":"openrad-identity-v1","rid":123,"vip":"26.0.0.5","node_name":"test","address":"00000000000000000000000000000000","credential":"010203040506","server_address":"192.0.2.1"}"#).unwrap()
    }
    #[test]
    fn identity_is_saved_and_reused_without_provisioning_again() {
        let vault = MemoryVault::default();
        let id = provision_once(&vault, |commit| {
            commit()?;
            Ok(identity())
        })
        .unwrap();
        assert_eq!(vault.data.borrow().as_deref(), Some(PENDING));
        save(&vault, &id).unwrap();
        assert_eq!(
            provision_once(&vault, |_| panic!("must reuse"))
                .unwrap()
                .rid,
            123
        );
    }
    #[test]
    fn interrupted_registration_never_provisions_a_second_identity() {
        let vault = MemoryVault::default();
        assert!(provision_once(&vault, |commit| {
            commit()?;
            bail!("connection lost")
        })
        .is_err());
        assert!(provision_once(&vault, |_| panic!("must not register twice")).is_err());
    }
    #[test]
    fn failure_before_registration_is_sent_allows_a_later_attempt() {
        let vault = MemoryVault::default();
        assert!(provision_once(&vault, |_| bail!("network unreachable")).is_err());
        assert!(vault.data.borrow().is_none());
        let id = provision_once(&vault, |commit| {
            commit()?;
            Ok(identity())
        })
        .unwrap();
        assert_eq!(id.rid, 123);
        assert_eq!(vault.data.borrow().as_deref(), Some(PENDING));
    }
    #[test]
    fn locked_store_prevents_registration_and_corruption_is_not_first_use() {
        let vault = MemoryVault::default();
        vault.fail.set(true);
        assert!(provision_once(&vault, |commit| {
            commit()?;
            panic!("no durable storage")
        })
        .is_err());
        *vault.data.borrow_mut() = Some(b"broken".to_vec());
        assert!(load(&vault).is_err());
    }
    #[test]
    fn settings_never_serialize_credentials() {
        let json = serde_json::to_string(&Settings::default()).unwrap();
        assert!(!json.contains("credential") && !json.contains("password"));
    }
    #[test]
    fn saved_network_lists_survive_restart_and_identity_replacement_without_passwords() {
        let directory = std::env::temp_dir().join(format!(
            "openrad-network-preferences-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let paths = Paths::new(Some(directory.clone())).unwrap();
        assert!(paths.network_preferences().unwrap() == NetworkPreferences::default());
        let preferences = NetworkPreferences {
            favorites: ["Public LAN".into(), "Private LAN".into()]
                .into_iter()
                .collect(),
            known_networks: [("Public LAN".into(), false), ("Private LAN".into(), true)]
                .into_iter()
                .collect(),
            join_configurations: [(
                "Friends".into(),
                vec![
                    JoinNetwork {
                        name: "Public LAN".into(),
                        private: false,
                    },
                    JoinNetwork {
                        name: "Private LAN".into(),
                        private: true,
                    },
                ],
            )]
            .into_iter()
            .collect(),
        };
        paths.save_network_preferences(&preferences).unwrap();
        // An update must also replace an existing file on supported platforms.
        paths.save_network_preferences(&preferences).unwrap();
        let vault = MemoryVault::default();
        let mut current = Some(identity());
        save(&vault, current.as_ref().unwrap()).unwrap();
        let mut replacement = Replacement::default();
        replacement
            .prepare(|| {
                let mut next = identity();
                next.rid = 456;
                Ok(next)
            })
            .unwrap();
        replacement.commit(&vault, &mut current).unwrap();
        let reopened = Paths::new(Some(directory.clone())).unwrap();
        assert!(reopened.network_preferences().unwrap() == preferences);
        let json = fs::read_to_string(directory.join("network-preferences.json")).unwrap();
        assert!(!json.contains("password") && !json.contains("credential"));
        let mut invalid = preferences.clone();
        invalid
            .join_configurations
            .get_mut("Friends")
            .unwrap()
            .push(JoinNetwork {
                name: "Private LAN".into(),
                private: true,
            });
        assert!(paths.save_network_preferences(&invalid).is_err());
        assert!(paths.network_preferences().unwrap() == preferences);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(directory.join("network-preferences.json"))
                    .unwrap()
                    .permissions()
                    .mode()
                    & 0o777,
                0o600
            );
        }
        fs::remove_dir_all(directory).unwrap();
    }
    #[test]
    fn malformed_and_oversized_join_configurations_are_rejected() {
        let network = JoinNetwork {
            name: "Synthetic LAN".into(),
            private: true,
        };
        for (name, networks) in [
            ("".into(), vec![network.clone()]),
            ("x".repeat(81), vec![network.clone()]),
            ("bad\nname".into(), vec![network.clone()]),
            ("Empty".into(), vec![]),
            ("Duplicate".into(), vec![network.clone(), network]),
            (
                "Too many".into(),
                (0..129)
                    .map(|index| JoinNetwork {
                        name: format!("Network {index}"),
                        private: false,
                    })
                    .collect(),
            ),
        ] {
            let preferences = NetworkPreferences {
                join_configurations: [(name, networks)].into_iter().collect(),
                ..Default::default()
            };
            assert!(preferences.validate().is_err());
        }
    }
    #[test]
    fn old_settings_receive_new_defaults_and_customization_round_trips() {
        let old = r#"{"auto_connect":false,"auto_reconnect":true,"node_name":"test","scale":1.25}"#;
        let settings: Settings = serde_json::from_str(old).unwrap();
        assert!(!settings.auto_connect);
        assert_eq!(settings.reconnect_attempts, 3);
        assert_eq!(settings.start_page, StartPage::Networks);
        assert_eq!(settings.language, LanguagePreference::System);
        assert!(settings.show_offline_peers);
        let mut changed = settings;
        changed.start_page = StartPage::Discover;
        changed.peer_sort = PeerSort::Status;
        changed.show_diagnostics = true;
        changed.language = LanguagePreference::Vietnamese;
        let restored: Settings =
            serde_json::from_str(&serde_json::to_string(&changed).unwrap()).unwrap();
        assert!(restored == changed);
    }
    #[test]
    fn settings_ranges_are_normalized_on_load() {
        let settings = Settings {
            scale: f32::NAN,
            reconnect_attempts: 99,
            reconnect_base_delay_seconds: 0,
            recent_activity_count: 100,
            ..Default::default()
        };
        let settings = settings.normalized();
        assert_eq!(settings.scale, 1.0);
        assert_eq!(settings.reconnect_attempts, 10);
        assert_eq!(settings.reconnect_base_delay_seconds, 1);
        assert_eq!(settings.recent_activity_count, 40);
    }
    #[test]
    fn replacement_preserves_old_identity_until_success_and_retries_only_storage() {
        let vault = MemoryVault::default();
        let mut current = Some(identity());
        save(&vault, current.as_ref().unwrap()).unwrap();
        let old = vault.data.borrow().clone();
        let mut replacement = Replacement::default();
        assert!(replacement
            .prepare(|| bail!("provisioning failed"))
            .is_err());
        assert_eq!(*vault.data.borrow(), old);
        assert!(!replacement.is_pending());
        replacement
            .prepare(|| {
                let mut i = identity();
                i.rid = 456;
                Ok(i)
            })
            .unwrap();
        assert_eq!(*vault.data.borrow(), old);
        vault.fail.set(true);
        assert!(replacement.commit(&vault, &mut current).is_err());
        assert_eq!(current.as_ref().unwrap().rid, 123);
        assert_eq!(*vault.data.borrow(), old);
        replacement
            .prepare(|| panic!("must not provision again"))
            .unwrap();
        vault.fail.set(false);
        replacement.commit(&vault, &mut current).unwrap();
        assert_eq!(current.unwrap().rid, 456);
        assert_eq!(load(&vault).unwrap().unwrap().rid, 456);
        assert!(!replacement.is_pending());
    }
}
