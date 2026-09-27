//! Persistent settings contain no credentials. The whole reusable identity is
//! stored in the OS credential store, never in an egui persistence file or log.
use anyhow::{bail, ensure, Context, Result};
use directories::ProjectDirs;
use openrad::protocol::Identity;
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;

const PENDING: &[u8] = b"openrad-provisioning-pending-v1";
#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub auto_connect: bool,
    pub auto_reconnect: bool,
    pub node_name: String,
    pub scale: f32,
}
impl Default for Settings {
    fn default() -> Self {
        Self {
            auto_connect: true,
            auto_reconnect: true,
            node_name: "openrad-linux".into(),
            scale: 1.0,
        }
    }
}
#[derive(Clone)]
pub struct Paths {
    pub directory: PathBuf,
}
impl Paths {
    pub fn new(override_path: Option<PathBuf>) -> Result<Self> {
        let directory = if let Some(path) = override_path {
            path
        } else {
            ProjectDirs::from("org", "OpenRad", "openrad")
                .context("Cannot locate the user data directory")?
                .data_local_dir()
                .to_owned()
        };
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
        Ok(Self {
            directory: directory.canonicalize()?,
        })
    }
    pub fn lock(&self) -> Result<File> {
        let file = private_file(&self.directory.join("desktop.lock"), false)?;
        file.try_lock()
            .context("OpenRad is already running with this profile")?;
        Ok(file)
    }
    pub fn settings(&self) -> Result<Settings> {
        let path = self.directory.join("settings.json");
        if !path.exists() {
            return Ok(Settings::default());
        }
        ensure!(
            fs::metadata(&path)?.len() < 64 * 1024,
            "Settings file is too large"
        );
        let mut settings: Settings = serde_json::from_slice(&fs::read(path)?)?;
        settings.scale = settings.scale.clamp(0.8, 1.5);
        Ok(settings)
    }
    pub fn save_settings(&self, settings: &Settings) -> Result<()> {
        ensure!(
            !settings.node_name.trim().is_empty(),
            "Device name cannot be empty"
        );
        openrad::protocol::textv(0x03000304, &settings.node_name)?;
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
    pub fn entry(&self) -> Result<keyring::Entry> {
        keyring::Entry::new("org.openrad.desktop", &self.directory.to_string_lossy())
            .map_err(|_| anyhow::anyhow!("Credential store unavailable. Start and unlock Secret Service (GNOME Keyring or KWallet), then restart OpenRad."))
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
        self.set_secret(bytes).map_err(|_| anyhow::anyhow!("Could not save the identity in the credential store. Keep this window open, unlock your keyring, then retry."))
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
    provision: impl FnOnce() -> Result<Identity>,
) -> Result<Identity> {
    if let Some(id) = load(vault)? {
        return Ok(id);
    }
    // Persist the intent before contacting the server. A crash or ambiguous network
    // failure must never turn a later launch into an extra identity registration.
    vault.set(PENDING)?;
    provision()
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
        let id = provision_once(&vault, || Ok(identity())).unwrap();
        save(&vault, &id).unwrap();
        assert_eq!(
            provision_once(&vault, || panic!("must reuse")).unwrap().rid,
            123
        );
    }
    #[test]
    fn interrupted_registration_never_provisions_a_second_identity() {
        let vault = MemoryVault::default();
        assert!(provision_once(&vault, || bail!("connection lost")).is_err());
        assert!(provision_once(&vault, || panic!("must not register twice")).is_err());
    }
    #[test]
    fn locked_store_prevents_registration_and_corruption_is_not_first_use() {
        let vault = MemoryVault::default();
        vault.fail.set(true);
        assert!(provision_once(&vault, || panic!("no durable storage")).is_err());
        *vault.data.borrow_mut() = Some(b"broken".to_vec());
        assert!(load(&vault).is_err());
    }
    #[test]
    fn settings_never_serialize_credentials() {
        let json = serde_json::to_string(&Settings::default()).unwrap();
        assert!(!json.contains("credential") && !json.contains("password"));
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
