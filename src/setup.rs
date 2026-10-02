//! Installer decisions and payload integrity, testable without Windows privileges.
use anyhow::{ensure, Context, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{collections::BTreeSet, fs::File, io::Read, path::Path};

pub const ADAPTER_NAME: &str = "OpenRad";
pub const MIN_DRIVER_VERSION: [u16; 4] = [9, 27, 0, 0];

/// Only validated OS identifiers are interpolated into the embedded script.
#[cfg(any(windows, test))]
pub(crate) fn adapter_setup_script(guid: &str, driver_key: &str) -> Result<String> {
    let value = guid
        .strip_prefix('{')
        .and_then(|s| s.strip_suffix('}'))
        .unwrap_or(guid);
    ensure!(
        value.len() == 36
            && value.bytes().enumerate().all(|(index, byte)| {
                if [8, 13, 18, 23].contains(&index) {
                    byte == b'-'
                } else {
                    byte.is_ascii_hexdigit()
                }
            }),
        "Invalid TAP adapter GUID"
    );
    validate_driver_key(driver_key)?;
    Ok(format!(
        "& {{\n{}\n}} -AdapterGuid '{guid}' -DriverKey '{driver_key}'",
        include_str!("../packaging/windows/setup-adapter.ps1")
    ))
}

/// Accept only a network device's exact software-key identifier from SetupAPI.
pub fn validate_driver_key(key: &str) -> Result<()> {
    let (class, instance) = key.split_once('\\').context("Invalid TAP driver key")?;
    ensure!(
        class.eq_ignore_ascii_case("{4d36e972-e325-11ce-bfc1-08002be10318}")
            && instance.len() == 4
            && instance.bytes().all(|b| b.is_ascii_digit()),
        "The selected device has an unexpected network driver key"
    );
    Ok(())
}

#[derive(Debug, Deserialize, Serialize)]
pub struct FileRecord {
    pub path: String,
    pub sha256: String,
}
#[derive(Debug, Deserialize, Serialize)]
pub struct Manifest {
    pub format: String,
    pub version: String,
    pub files: Vec<FileRecord>,
}
impl Manifest {
    pub fn read(path: &Path) -> Result<Self> {
        let data = crate::file_io::read_bounded(path, 1024 * 1024)
            .context("Cannot read the installation manifest")?;
        let manifest: Self = serde_json::from_slice(&data)?;
        ensure!(
            manifest.format == "openrad-install-v1",
            "Unsupported installation manifest"
        );
        ensure!(
            !manifest.files.is_empty() && manifest.files.len() <= 4096,
            "Invalid installation manifest"
        );
        let mut seen = BTreeSet::new();
        for record in &manifest.files {
            let path = record.path.replace('\\', "/");
            ensure!(
                !path.is_empty()
                    && !path.starts_with('/')
                    && !path.contains(':')
                    && path
                        .split('/')
                        .all(|p| !p.is_empty() && p != "." && p != "..")
                    && seen.insert(path.to_ascii_lowercase()),
                "Unsafe or duplicate installation path"
            );
            ensure!(
                record.sha256.len() == 64 && record.sha256.bytes().all(|b| b.is_ascii_hexdigit()),
                "Invalid installation file hash"
            );
        }
        for required in [
            "openrad.exe",
            "openrad-desktop.exe",
            "openrad-setup-helper.exe",
            "driver/OemVista.inf",
            "driver/tap0901.cat",
            "driver/tap0901.sys",
        ] {
            ensure!(
                seen.contains(&required.to_ascii_lowercase()),
                "Installation manifest is missing a required file"
            );
        }
        Ok(manifest)
    }
    pub fn matches(&self, directory: &Path) -> Result<bool> {
        for record in &self.files {
            let path = directory.join(record.path.replace('\\', "/"));
            let mut file = match File::open(&path) {
                Ok(file) => file,
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(false),
                Err(e) => return Err(e).context("Cannot verify installed application files"),
            };
            let mut hash = Sha256::new();
            let mut buffer = [0u8; 32768];
            loop {
                let len = file.read(&mut buffer)?;
                if len == 0 {
                    break;
                }
                hash.update(&buffer[..len]);
            }
            if !hex::encode(hash.finalize()).eq_ignore_ascii_case(&record.sha256) {
                return Ok(false);
            }
        }
        Ok(true)
    }
}

#[derive(Debug, Clone)]
pub struct Adapter {
    pub guid: String,
    pub name: String,
    pub component_id: String,
    pub driver_version: [u16; 4],
    pub enabled: bool,
}
#[derive(Debug, Deserialize, Serialize)]
pub struct AdapterState {
    pub guid: String,
    pub created_by_setup: bool,
}
#[derive(Debug, PartialEq, Eq)]
pub enum Selection {
    Existing(usize),
    Create,
}
pub fn select_adapter(adapters: &[Adapter], state: Option<&AdapterState>) -> Result<Selection> {
    let named: Vec<_> = adapters
        .iter()
        .enumerate()
        .filter(|(_, a)| a.name.eq_ignore_ascii_case(ADAPTER_NAME))
        .collect();
    ensure!(
        named.len() <= 1,
        "More than one adapter is named OpenRad; resolve the duplicate before setup"
    );
    if let Some((index, adapter)) = named.first() {
        ensure!(adapter.component_id.eq_ignore_ascii_case("tap0901"), "An adapter named OpenRad belongs to another driver. Rename that adapter before installing OpenRad");
        return Ok(Selection::Existing(*index));
    }
    if let Some(state) = state.filter(|s| s.created_by_setup) {
        if let Some((index, adapter)) = adapters
            .iter()
            .enumerate()
            .find(|(_, a)| a.guid.eq_ignore_ascii_case(&state.guid))
        {
            ensure!(
                adapter.component_id.eq_ignore_ascii_case("tap0901"),
                "The recorded OpenRad adapter no longer belongs to TAP-Windows6"
            );
            return Ok(Selection::Existing(index));
        }
    }
    Ok(Selection::Create)
}
pub fn ready(files_match: bool, adapters: &[Adapter], selection: &Selection) -> bool {
    files_match
        && match selection {
            Selection::Existing(index) => {
                let a = &adapters[*index];
                a.enabled
                    && a.name.eq_ignore_ascii_case(ADAPTER_NAME)
                    && a.driver_version >= MIN_DRIVER_VERSION
            }
            Selection::Create => false,
        }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn embedded_adapter_script_rejects_identifiers_that_could_inject_source() {
        let guid = "{01234567-89ab-cdef-0123-456789abcdef}";
        let driver = "{4d36e972-e325-11ce-bfc1-08002be10318}\\0001";
        let script = adapter_setup_script(guid, driver).unwrap();
        assert!(script.contains("function Test-AdapterGuid"));
        assert!(script.ends_with(&format!("-AdapterGuid '{guid}' -DriverKey '{driver}'")));
        for invalid in [
            "",
            "' ; exit 0; #",
            "{01234567-89ab-cdef-0123-456789abcdeg}",
            "01234567_89ab-cdef-0123-456789abcdef",
        ] {
            assert!(adapter_setup_script(invalid, driver).is_err());
        }
        assert!(adapter_setup_script(guid, "' ; exit 0; #").is_err());
    }
    #[test]
    fn driver_key_rejects_other_classes_and_registry_traversal() {
        assert!(validate_driver_key("{4D36E972-E325-11CE-BFC1-08002BE10318}\\0001").is_ok());
        for key in [
            "{4d36e972-e325-11ce-bfc1-08002be10318}\\Properties",
            "{4d36e972-e325-11ce-bfc1-08002be10318}\\0001\\Properties",
            "{4d36e972-e325-11ce-bfc1-08002be10318}\\..",
            "{4d36e973-e325-11ce-bfc1-08002be10318}\\0001",
            "SYSTEM\\CurrentControlSet",
        ] {
            assert!(validate_driver_key(key).is_err(), "{key}");
        }
    }
    fn adapter(name: &str, component: &str) -> Adapter {
        Adapter {
            guid: "{01234567-89ab-cdef-0123-456789abcdef}".into(),
            name: name.into(),
            component_id: component.into(),
            driver_version: MIN_DRIVER_VERSION,
            enabled: true,
        }
    }
    #[test]
    fn completed_setup_is_reused_without_creating_another_adapter() {
        let adapters = [
            adapter("OpenRad", "tap0901"),
            adapter("Another VPN", "tap0901"),
        ];
        let selected = select_adapter(&adapters, None).unwrap();
        assert_eq!(selected, Selection::Existing(0));
        assert!(ready(true, &adapters, &selected));
        assert!(!ready(false, &adapters, &selected));
    }
    #[test]
    fn disabled_and_old_driver_setups_need_repair() {
        for (enabled, version) in [(false, MIN_DRIVER_VERSION), (true, [9, 24, 7, 601])] {
            let mut a = adapter("OpenRad", "tap0901");
            a.enabled = enabled;
            a.driver_version = version;
            assert!(!ready(true, &[a], &Selection::Existing(0)));
        }
    }
    #[test]
    fn another_vpn_adapter_is_never_taken_over() {
        assert_eq!(
            select_adapter(&[adapter("Other VPN", "tap0901")], None).unwrap(),
            Selection::Create
        );
        assert!(select_adapter(&[adapter("OpenRad", "physical")], None).is_err());
        assert!(select_adapter(
            &[adapter("OpenRad", "tap0901"), adapter("OpenRad", "tap0901")],
            None
        )
        .is_err());
    }
    #[test]
    fn only_a_recorded_owned_adapter_can_be_recovered_by_guid() {
        let a = adapter("Renamed by user", "tap0901");
        for created_by_setup in [false, true] {
            let state = AdapterState {
                guid: a.guid.clone(),
                created_by_setup,
            };
            assert_eq!(
                select_adapter(std::slice::from_ref(&a), Some(&state)).unwrap(),
                if created_by_setup {
                    Selection::Existing(0)
                } else {
                    Selection::Create
                }
            );
        }
    }
    #[test]
    fn payload_hashes_detect_missing_and_modified_files() {
        let dir = std::env::temp_dir().join(format!(
            "openrad-setup-test-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        std::fs::create_dir(&dir).unwrap();
        let manifest = Manifest {
            format: "openrad-install-v1".into(),
            version: "synthetic".into(),
            files: vec![FileRecord {
                path: "synthetic.exe".into(),
                sha256: hex::encode(Sha256::digest(b"synthetic")),
            }],
        };
        assert!(!manifest.matches(&dir).unwrap());
        std::fs::write(dir.join("synthetic.exe"), b"synthetic").unwrap();
        assert!(manifest.matches(&dir).unwrap());
        std::fs::write(dir.join("synthetic.exe"), b"tampered").unwrap();
        assert!(!manifest.matches(&dir).unwrap());
        std::fs::remove_dir_all(&dir).unwrap();
    }
    #[test]
    fn manifest_rejects_traversal_duplicate_paths_and_incomplete_payloads() {
        let path = std::env::temp_dir().join(format!(
            "openrad-manifest-test-{}-{}.json",
            std::process::id(),
            rand::random::<u64>()
        ));
        for names in [
            vec!["../escape"],
            vec!["C:\\escape"],
            vec!["/escape"],
            vec!["a", "A"],
            vec!["openrad.exe"],
        ] {
            let manifest = Manifest {
                format: "openrad-install-v1".into(),
                version: "synthetic".into(),
                files: names
                    .into_iter()
                    .map(|name| FileRecord {
                        path: name.into(),
                        sha256: "0".repeat(64),
                    })
                    .collect(),
            };
            std::fs::write(&path, serde_json::to_vec(&manifest).unwrap()).unwrap();
            assert!(Manifest::read(&path).is_err());
        }
        std::fs::remove_file(path).unwrap();
    }
}
