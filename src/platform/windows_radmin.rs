//! Recover only conflicts belonging to the official Famatech adapter.
use anyhow::{bail, ensure, Result};
use std::net::Ipv4Addr;

const DESCRIPTION: &str = "Famatech Radmin VPN Ethernet Adapter";
#[cfg(test)]
const CONFLICT: &str = "An existing 26.0.0.0/8 address conflicts with OpenRad; disconnect other VPNs and clear stale OpenRad addresses (docs/windows.md)";

fn official_description(description: &str) -> bool {
    description.eq_ignore_ascii_case(DESCRIPTION)
        || description.rsplit_once(" #").is_some_and(|(base, suffix)| {
            base.eq_ignore_ascii_case(DESCRIPTION)
                && !suffix.is_empty()
                && suffix.bytes().all(|byte| byte.is_ascii_digit())
        })
}

pub(crate) struct Address {
    pub interface: u64,
    pub index: u32,
    pub description: String,
    pub enabled: bool,
    pub ip: Ipv4Addr,
}

fn conflicting_adapters(openrad: u64, addresses: &[Address]) -> Result<Vec<u32>> {
    let mut targets = Vec::new();
    for address in addresses.iter().filter(|address| address.enabled) {
        if address.ip.octets()[0] == 26 {
            // The user-visible alias can be renamed. Match the driver description
            // and exclude our own TAP, even if its description were spoofed.
            ensure!(
                address.interface != openrad
                    && official_description(&address.description),
                "An existing 26.0.0.0/8 address conflicts with OpenRad; disconnect other VPNs and clear stale OpenRad addresses (docs/windows.md): interface {0} ({1}, {2}), address {3}; {hint}",
                address.index,
                address.description,
                address.interface,
                address.ip,
                hint = if address.interface == openrad { "OpenRad TAP" } else { "another adapter" }
            );
            if !targets.contains(&address.index) {
                targets.push(address.index);
            }
        } else if address.interface == openrad {
            ensure!(address.ip.is_link_local() || address.ip.is_unspecified(), "The OpenRad adapter already has a configured IPv4 address; use a dedicated adapter with no static address");
        }
    }
    Ok(targets)
}

pub(crate) fn prepare_addresses(
    openrad: u64,
    mut inventory: impl FnMut() -> Result<Vec<Address>>,
    recover: impl FnOnce(&[u32]) -> Result<()>,
    mut wait: impl FnMut(),
) -> Result<()> {
    let targets = conflicting_adapters(openrad, &inventory()?)?;
    if targets.is_empty() {
        return Ok(());
    }
    recover(&targets)?;
    // Recheck all interfaces before configuring TAP. A disabled adapter can
    // retain addresses; only administratively enabled interfaces can conflict.
    // Never retry SYSTEM recovery in a loop.
    for attempt in 0..40 {
        if attempt != 0 {
            wait();
        }
        if conflicting_adapters(openrad, &inventory()?)?.is_empty() {
            return Ok(());
        }
    }
    bail!("The official Radmin VPN adapter is still active after SYSTEM recovery; close Radmin VPN and retry the OpenRad connection (docs/windows.md)")
}

#[cfg(windows)]
pub(crate) fn recover(indices: &[u32]) -> Result<()> {
    invoke_recovery(Some(indices))
}

#[cfg(windows)]
pub(crate) fn prepare_installation() -> Result<()> {
    invoke_recovery(None)
}

#[cfg(windows)]
fn invoke_recovery(indices: Option<&[u32]>) -> Result<()> {
    use anyhow::Context;

    ensure!(
        crate::windows_security::token_is_elevated()?,
        "Run OpenRad as administrator to disable the conflicting official Radmin VPN adapter"
    );
    let arguments = if let Some(indices) = indices {
        format!(
            "-InterfaceIndex @({})",
            indices
                .iter()
                .map(u32::to_string)
                .collect::<Vec<_>>()
                .join(",")
        )
    } else {
        "-Discover".into()
    };
    // Only decimal OS interface indices enter the immutable embedded script.
    // SYSTEM executes an encoded fixed worker, never a writable installed script
    // or the VPN application. The application keeps its usual user's identity.
    let script = format!(
        "{}\nInvoke-OpenRadRadminRecovery {arguments}",
        include_str!("windows_radmin.ps1")
    );
    crate::early_log::event(format_args!(
        "Preparing official Radmin VPN: administrator recovery with SYSTEM fallback"
    ));
    let output = crate::windows_security::powershell_output(&script)
        .context("Cannot start SYSTEM recovery for the official Radmin VPN adapter")?;
    ensure!(output.status.success(), "Cannot stop RvControlSvc.exe/RvRvpnGui.exe or disable the official Radmin VPN adapter using administrator/SYSTEM recovery: {}", String::from_utf8_lossy(&output.stderr).trim());
    crate::early_log::event(format_args!(
        "Official Radmin VPN processes stopped and adapters disabled"
    ));
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;

    fn address(interface: u64, description: &str, ip: &str) -> Address {
        Address {
            interface,
            index: interface as u32,
            description: description.into(),
            enabled: true,
            ip: ip.parse().unwrap(),
        }
    }

    #[test]
    fn official_radmin_conflict_recovers_once_and_rechecks_before_connecting() {
        let reads = Cell::new(0);
        let recoveries = Cell::new(0);
        let waits = Cell::new(0);
        prepare_addresses(
            1,
            || {
                reads.set(reads.get() + 1);
                let mut official = address(19, DESCRIPTION, "26.1.2.3");
                official.enabled = reads.get() < 3;
                Ok(vec![
                    official,
                    address(1, "TAP-Windows Adapter V9", "169.254.1.2"),
                ])
            },
            |targets| {
                assert_eq!(targets, &[19]);
                recoveries.set(recoveries.get() + 1);
                Ok(())
            },
            || waits.set(waits.get() + 1),
        )
        .unwrap();
        assert_eq!((reads.get(), recoveries.get(), waits.get()), (3, 1, 1));
    }

    #[test]
    fn disabled_official_adapter_with_retained_address_does_not_conflict() {
        let mut official = address(19, DESCRIPTION, "26.1.2.3");
        official.enabled = false;
        assert!(conflicting_adapters(1, &[official]).unwrap().is_empty());
    }

    #[test]
    fn mixed_or_unrelated_conflicts_are_rejected_before_any_mutation() {
        for description in ["Radmin VPN", "Ethernet", "TAP-Windows Adapter V9"] {
            let result = prepare_addresses(
                1,
                || {
                    Ok(vec![
                        address(19, DESCRIPTION, "26.1.2.3"),
                        address(20, description, "26.4.5.6"),
                    ])
                },
                |_| panic!("must not mutate adapters on an unrelated conflict"),
                || panic!("must not wait"),
            );
            assert!(result.unwrap_err().to_string().contains(CONFLICT));
        }
    }

    #[test]
    fn existing_openrad_addresses_remain_rejected() {
        for ip in ["26.1.2.3", "192.168.1.2"] {
            assert!(conflicting_adapters(1, &[address(1, DESCRIPTION, ip)]).is_err());
        }
    }

    #[test]
    fn duplicate_addresses_and_multiple_official_adapters_recover_together() {
        let targets = conflicting_adapters(
            1,
            &[
                address(19, DESCRIPTION, "26.1.2.3"),
                address(19, DESCRIPTION, "26.1.2.4"),
                address(20, &DESCRIPTION.to_lowercase(), "26.4.5.6"),
            ],
        )
        .unwrap();
        assert_eq!(targets, [19, 20]);
    }

    #[test]
    fn numbered_official_driver_descriptions_recover_but_similar_names_do_not() {
        let targets =
            conflicting_adapters(1, &[address(19, &format!("{DESCRIPTION} #2"), "26.1.2.3")])
                .unwrap();
        assert_eq!(targets, [19]);
        for description in [
            "Famatech Radmin VPN Ethernet Adapter #",
            "Famatech Radmin VPN Ethernet Adapter #other",
            "Famatech Radmin VPN Ethernet Adapter fake",
        ] {
            assert!(conflicting_adapters(1, &[address(19, description, "26.1.2.3")]).is_err());
        }
    }

    #[test]
    fn conflict_error_identifies_the_exact_adapter_and_address() {
        let error = conflicting_adapters(1, &[address(20, "Ethernet", "26.1.2.3")])
            .unwrap_err()
            .to_string();
        assert!(error.contains("interface 20 (Ethernet, 20), address 26.1.2.3"));
        let error = conflicting_adapters(1, &[address(1, "TAP-Windows Adapter V9", "26.1.2.3")])
            .unwrap_err()
            .to_string();
        assert!(error.contains("OpenRad TAP"));
    }

    #[test]
    fn no_conflict_does_not_request_system_recovery() {
        prepare_addresses(
            1,
            || Ok(vec![address(19, DESCRIPTION, "192.168.1.2")]),
            |_| panic!("must not request SYSTEM without a conflict"),
            || panic!("must not wait"),
        )
        .unwrap();
    }

    #[test]
    fn failed_system_recovery_does_not_continue_connection() {
        let result = prepare_addresses(
            1,
            || Ok(vec![address(19, DESCRIPTION, "26.1.2.3")]),
            |_| bail!("SYSTEM denied"),
            || panic!("must not retry a denied recovery"),
        );
        assert_eq!(result.unwrap_err().to_string(), "SYSTEM denied");
    }

    #[test]
    fn persistent_radmin_conflict_has_a_bounded_retry() {
        let reads = Cell::new(0);
        let result = prepare_addresses(
            1,
            || {
                reads.set(reads.get() + 1);
                Ok(vec![address(19, DESCRIPTION, "26.1.2.3")])
            },
            |_| Ok(()),
            || {},
        );
        assert!(result.unwrap_err().to_string().contains("still active"));
        assert_eq!(reads.get(), 41);
    }

    #[test]
    fn new_unrelated_conflict_during_recovery_is_rejected() {
        let recovered = Cell::new(false);
        let result = prepare_addresses(
            1,
            || {
                Ok(vec![if recovered.get() {
                    address(20, "Ethernet", "26.1.2.3")
                } else {
                    address(19, DESCRIPTION, "26.1.2.3")
                }])
            },
            |_| {
                recovered.set(true);
                Ok(())
            },
            || {},
        );
        assert!(result.unwrap_err().to_string().contains(CONFLICT));
    }
}
