//! Service negotiation and uncompressed Ethernet envelopes.
use crate::protocol::*;
use anyhow::{ensure, Result};
use std::net::Ipv4Addr;
pub fn mac(ip: Ipv4Addr) -> [u8; 6] {
    let a = ip.octets();
    [2, 0x1a, a[0], a[1], a[2], a[3]]
}
pub fn syn(rid: u64, mac: &[u8; 6]) -> Vec<u8> {
    [
        u32v(0x01000302, 8),
        tlv(0x09000304, mac),
        u64v(0x020001e1, rid),
    ]
    .concat()
}
pub fn synack(data: &[u8]) -> Result<(Vec<u8>, [u8; 6], u32)> {
    let f = records(data)?;
    let status = field(&f, 0x02000303)?;
    ensure!(
        status == [0; 8],
        "peer service refused: {}",
        hex::encode(status)
    );
    let version = int32(field(&f, 0x01000302)?)?.min(8);
    let mac = field(&f, 0x09000304)?;
    ensure!(
        version >= 5 && mac.len() == 6 && mac[0] & 1 == 0,
        "invalid service version/MAC"
    );
    Ok((
        [u64v(0x02000303, 0), u32v(0x01000302, version)].concat(),
        mac.try_into()?,
        version,
    ))
}
pub fn accept_syn(
    data: &[u8],
    expected_rid: u64,
    own_mac: &[u8; 6],
) -> Result<(Vec<u8>, [u8; 6], u32)> {
    let f = records(data)?;
    ensure!(
        int64(field(&f, 0x020001e1)?)? == expected_rid,
        "service peer identity mismatch"
    );
    let version = int32(field(&f, 0x01000302)?)?.min(8);
    let mac = field(&f, 0x09000304)?;
    ensure!(
        version >= 5 && mac.len() == 6 && mac[0] & 1 == 0,
        "invalid service version/MAC"
    );
    Ok((
        [
            u64v(0x02000303, 0),
            u32v(0x01000302, version),
            tlv(0x09000304, own_mac),
        ]
        .concat(),
        mac.try_into()?,
        version,
    ))
}
pub fn accept_ack(data: &[u8], version: u32) -> Result<()> {
    let f = records(data)?;
    ensure!(
        field(&f, 0x02000303)? == [0; 8] && int32(field(&f, 0x01000302)?)? == version,
        "service acknowledgement mismatch"
    );
    Ok(())
}
pub fn encode(frame: &[u8]) -> Result<Vec<u8>> {
    ensure!(
        (14..=65535).contains(&frame.len()),
        "invalid Ethernet length"
    );
    let mut packet = Vec::with_capacity(10 + frame.len());
    packet.extend_from_slice(&[0; 6]);
    packet.extend_from_slice(&(frame.len() as u32).to_le_bytes());
    packet.extend_from_slice(frame);
    Ok(packet)
}
pub enum Packet<'a> {
    Frames(Vec<&'a [u8]>),
    Keepalive { sequence: u32, reply: bool },
    Other(u16),
}
pub fn decode(data: &[u8]) -> Result<Packet<'_>> {
    ensure!(data.len() >= 2, "truncated tunnel packet");
    let kind = u16::from_be_bytes(data[..2].try_into()?);
    match kind {
        0 => {
            let mut at = 2;
            let mut frames = vec![];
            while at < data.len() {
                ensure!(data.len() - at >= 22, "truncated Ethernet envelope");
                let flags = u32::from_le_bytes(data[at..at + 4].try_into()?);
                let len = u32::from_le_bytes(data[at + 4..at + 8].try_into()?) as usize;
                at += 8;
                ensure!(
                    (14..=65535).contains(&len) && len <= data.len() - at,
                    "invalid Ethernet envelope length"
                );
                ensure!(flags == 0, "unsupported Ethernet offload/compression flags");
                frames.push(&data[at..at + len]);
                at += len;
            }
            ensure!(!frames.is_empty(), "empty Ethernet packet");
            Ok(Packet::Frames(frames))
        }
        3 | 7 => {
            ensure!(data.len() == 6, "invalid keepalive length");
            Ok(Packet::Keepalive {
                sequence: u32::from_be_bytes(data[2..].try_into()?),
                reply: kind == 7,
            })
        }
        _ => Ok(Packet::Other(kind)),
    }
}
pub fn keepalive(seq: u32, reply: bool) -> Vec<u8> {
    [
        (if reply { 7u16 } else { 3 }).to_be_bytes().to_vec(),
        seq.to_be_bytes().to_vec(),
    ]
    .concat()
}
pub fn checksum(data: &[u8]) -> u16 {
    let mut sum: u32 = data
        .chunks(2)
        .map(|b| u16::from_be_bytes([b[0], *b.get(1).unwrap_or(&0)]) as u32)
        .sum();
    while sum >> 16 != 0 {
        sum = (sum & 65535) + (sum >> 16);
    }
    !(sum as u16)
}
pub fn ipv4_endpoints(frame: &[u8]) -> Option<(Ipv4Addr, Ipv4Addr)> {
    if frame.len() < 34 || frame[12..14] != [8, 0] || frame[14] >> 4 != 4 {
        return None;
    }
    let h = (frame[14] & 15) as usize * 4;
    let n = u16::from_be_bytes([frame[16], frame[17]]) as usize;
    if h < 20 || n < h || n + 14 > frame.len() || checksum(&frame[14..14 + h]) != 0 {
        return None;
    }
    Some((
        Ipv4Addr::from(<[u8; 4]>::try_from(&frame[26..30]).ok()?),
        Ipv4Addr::from(<[u8; 4]>::try_from(&frame[30..34]).ok()?),
    ))
}
pub fn arp_endpoints(frame: &[u8]) -> Option<(Ipv4Addr, Ipv4Addr)> {
    if frame.len() < 42
        || frame[12..20] != [8, 6, 0, 1, 8, 0, 6, 4]
        || ![1, 2].contains(&frame[21])
        || frame[20] != 0
    {
        return None;
    }
    Some((
        Ipv4Addr::from(<[u8; 4]>::try_from(&frame[28..32]).ok()?),
        Ipv4Addr::from(<[u8; 4]>::try_from(&frame[38..42]).ok()?),
    ))
}
pub fn endpoints(frame: &[u8]) -> Option<(Ipv4Addr, Ipv4Addr)> {
    ipv4_endpoints(frame).or_else(|| arp_endpoints(frame))
}

/// Announce our TAP address when an authenticated Ethernet link becomes usable.
/// The kernel may emit its initial broadcast before any peer is connected.
pub fn gratuitous_arp(vip: Ipv4Addr) -> Vec<u8> {
    let mac = mac(vip);
    [
        [255; 6].as_slice(),
        &mac,
        &[8, 6, 0, 1, 8, 0, 6, 4, 0, 2],
        &mac,
        &vip.octets(),
        &[255; 6],
        &vip.octets(),
    ]
    .concat()
}

/// Largest supported Ethernet frame (MTU 1500 plus header).
pub const MAX_FRAME: usize = 1514;

/// Whether an unchanged Ethernet frame belongs on this authenticated link.
/// IPv4 group traffic and gratuitous ARP fan out once per peer; directed ARP is
/// sent only to its target. Received group frames go to the kernel, never re-flooded.
pub fn deliver_to(
    frame: &[u8],
    source: Ipv4Addr,
    source_mac: [u8; 6],
    target: Ipv4Addr,
    target_mac: [u8; 6],
) -> bool {
    if !(14..=MAX_FRAME).contains(&frame.len()) || frame[6..12] != source_mac {
        return false;
    }
    if let Some((src, dst)) = ipv4_endpoints(frame) {
        if src != source {
            return false;
        }
        let destination_mac =
            if dst == Ipv4Addr::BROADCAST || dst == Ipv4Addr::new(26, 255, 255, 255) {
                [255; 6]
            } else if dst.is_multicast() {
                let b = dst.octets();
                [1, 0, 0x5e, b[1] & 0x7f, b[2], b[3]]
            } else if dst == target {
                target_mac
            } else {
                return false;
            };
        return frame[..6] == destination_mac;
    }
    if let Some((src, dst)) = arp_endpoints(frame) {
        // Check the ARP sender too: the Ethernet source alone is insufficient.
        return src == source
            && frame[22..28] == source_mac
            && (dst == target && (frame[..6] == target_mac || frame[..6] == [255; 6])
                || dst == source && frame[..6] == [255; 6]);
    }
    false
}
