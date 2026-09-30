//! TAP-Windows6 ABI and packet adaptation, exercised without a driver on Linux.
//! Constants follow OpenVPN/tap-windows6 src/tap-windows.h (MIT licensed).
use anyhow::{ensure, Result};
use std::net::Ipv4Addr;

#[cfg(test)]
pub const ADAPTER_KEY: &str =
    r"SYSTEM\CurrentControlSet\Control\Class\{4D36E972-E325-11CE-BFC1-08002BE10318}";
#[cfg(test)]
pub const CONNECTION_KEY: &str =
    r"SYSTEM\CurrentControlSet\Control\Network\{4D36E972-E325-11CE-BFC1-08002BE10318}";
pub const GET_MAC: u32 = (0x22 << 16) | (1 << 2);
pub const GET_VERSION: u32 = (0x22 << 16) | (2 << 2);
pub const GET_MTU: u32 = (0x22 << 16) | (3 << 2);
pub const SET_MEDIA_STATUS: u32 = (0x22 << 16) | (6 << 2);

pub fn device_path(guid: &str) -> Result<String> {
    let b = guid.as_bytes();
    ensure!(
        b.len() == 38
            && b[0] == b'{'
            && b[37] == b'}'
            && b[1..37].iter().enumerate().all(|(i, c)| {
                if [8, 13, 18, 23].contains(&i) {
                    *c == b'-'
                } else {
                    c.is_ascii_hexdigit()
                }
            }),
        "TAP-Windows6 has an invalid adapter GUID"
    );
    Ok(format!(r"\\.\Global\{guid}.tap"))
}

pub fn validate_parameters(vip: Ipv4Addr, peers: &[Ipv4Addr]) -> Result<()> {
    ensure!(
        vip.octets()[0] == 26 && peers.len() <= 1024,
        "invalid VPN interface parameters"
    );
    ensure!(
        peers.iter().all(|p| p.octets()[0] == 26 && *p != vip),
        "invalid peer host route"
    );
    Ok(())
}

pub fn routes(lan: bool, peers: &[Ipv4Addr]) -> Vec<(Ipv4Addr, u8)> {
    let mut routes = Vec::new();
    if lan {
        // The /8 connected route is created by the unicast address API.
        routes.extend([(Ipv4Addr::new(224, 0, 0, 0), 4), (Ipv4Addr::BROADCAST, 32)]);
    }
    routes.extend(peers.iter().copied().map(|peer| (peer, 32)));
    routes.sort();
    routes.dedup();
    routes
}

/// Translate only our local MAC in Ethernet/IPv4 ARP headers. Peer MACs,
/// addresses, group destinations and all IP payload/checksums stay unchanged.
pub fn translate_mac(frame: &mut [u8], from: [u8; 6], to: [u8; 6]) -> Result<()> {
    ensure!(
        (14..=crate::tunnel::MAX_FRAME).contains(&frame.len()),
        "TAP Ethernet frame exceeds configured MTU"
    );
    for at in [0, 6] {
        if frame[at..at + 6] == from {
            frame[at..at + 6].copy_from_slice(&to);
        }
    }
    if frame.len() >= 42
        && frame[12..20] == [8, 6, 0, 1, 8, 0, 6, 4]
        && frame[20] == 0
        && [1, 2].contains(&frame[21])
    {
        for at in [22, 32] {
            if frame[at..at + 6] == from {
                frame[at..at + 6].copy_from_slice(&to);
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    const GUID: &str = "{01234567-89ab-cdef-0123-456789abcdef}";
    #[test]
    fn official_tap_windows6_abi_and_device_path_match() {
        assert_eq!(
            [GET_MAC, GET_VERSION, GET_MTU, SET_MEDIA_STATUS],
            [0x220004, 0x220008, 0x22000c, 0x220018]
        );
        assert!(ADAPTER_KEY.ends_with("{4D36E972-E325-11CE-BFC1-08002BE10318}"));
        assert!(CONNECTION_KEY.contains("Control\\Network"));
        assert_eq!(
            device_path(GUID).unwrap(),
            format!(r"\\.\Global\{GUID}.tap")
        );
        for invalid in [
            "",
            "tap0901",
            "{../../other}",
            "{01234567-89ab-cdef-0123-456789abcdeg}",
        ] {
            assert!(device_path(invalid).is_err());
        }
    }
    #[test]
    fn windows_interface_parameters_and_routes_are_bounded() {
        let vip = Ipv4Addr::new(26, 0, 0, 5);
        let peer = Ipv4Addr::new(26, 0, 0, 6);
        assert!(validate_parameters(vip, &[peer]).is_ok());
        for peers in [vec![vip], vec![Ipv4Addr::LOCALHOST], vec![peer; 1025]] {
            assert!(validate_parameters(vip, &peers).is_err());
        }
        assert!(validate_parameters(Ipv4Addr::LOCALHOST, &[]).is_err());
        assert_eq!(routes(false, &[peer, peer]), vec![(peer, 32)]);
        assert_eq!(
            routes(true, &[]),
            vec![(Ipv4Addr::new(224, 0, 0, 0), 4), (Ipv4Addr::BROADCAST, 32)]
        );
    }
    #[test]
    fn arp_from_windows_keeps_the_wire_identity_and_round_trips() {
        let vip = Ipv4Addr::new(26, 0, 0, 5);
        let wire = crate::tunnel::mac(vip);
        let local = [2, 3, 4, 5, 6, 7];
        let original = crate::tunnel::gratuitous_arp(vip);
        let mut frame = original.clone();
        translate_mac(&mut frame, wire, local).unwrap();
        assert_eq!(&frame[6..12], &local);
        assert_eq!(&frame[22..28], &local);
        assert_eq!(&frame[..6], &[255; 6]);
        translate_mac(&mut frame, local, wire).unwrap();
        assert_eq!(frame, original);
        assert!(crate::tunnel::forwarding(&frame, vip, wire).is_some());
    }
    #[test]
    fn inbound_arp_reply_translates_only_the_local_destination() {
        let vip = Ipv4Addr::new(26, 0, 0, 5);
        let peer = Ipv4Addr::new(26, 0, 0, 6);
        let wire = crate::tunnel::mac(vip);
        let local = [2, 3, 4, 5, 6, 7];
        let mut frame = crate::tunnel::gratuitous_arp(peer);
        frame[..6].copy_from_slice(&wire);
        frame[21] = 2;
        frame[32..38].copy_from_slice(&wire);
        frame[38..42].copy_from_slice(&vip.octets());
        let original = frame.clone();
        translate_mac(&mut frame, wire, local).unwrap();
        assert_eq!(&frame[..6], &local);
        assert_eq!(&frame[32..38], &local);
        assert_eq!(&frame[6..32], &original[6..32]);
        assert_eq!(&frame[38..], &original[38..]);
    }
    #[test]
    fn ipv4_payload_is_unchanged_and_invalid_frame_lengths_are_rejected() {
        let from = [2, 3, 4, 5, 6, 7];
        let to = [2, 8, 9, 10, 11, 12];
        let mut frame = vec![17; crate::tunnel::MAX_FRAME];
        frame[6..12].copy_from_slice(&from);
        frame[12..14].copy_from_slice(&[8, 0]);
        frame[40..46].copy_from_slice(&from);
        let payload = frame[12..].to_vec();
        translate_mac(&mut frame, from, to).unwrap();
        assert_eq!(&frame[6..12], &to);
        assert_eq!(&frame[12..], payload);
        for len in [0, 13, crate::tunnel::MAX_FRAME + 1] {
            assert!(translate_mac(&mut vec![0; len], from, to).is_err());
        }
    }
}
