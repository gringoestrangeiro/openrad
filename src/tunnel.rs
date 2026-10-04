//! Service negotiation and uncompressed Ethernet envelopes.
use crate::protocol::*;
use anyhow::{ensure, Result};
use std::{
    net::Ipv4Addr,
    ops::{Deref, Range},
    sync::Arc,
};
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
    let mut packet = Vec::new();
    encode_into(frame, &mut packet)?;
    Ok(packet)
}
pub fn encode_into(frame: &[u8], packet: &mut Vec<u8>) -> Result<()> {
    ensure!(
        (14..=65535).contains(&frame.len()),
        "invalid Ethernet length"
    );
    packet.clear();
    packet.reserve(10 + frame.len());
    packet.extend_from_slice(&[0; 6]);
    packet.extend_from_slice(&(frame.len() as u32).to_le_bytes());
    packet.extend_from_slice(frame);
    Ok(())
}
pub enum Packet<'a> {
    Frames(Vec<&'a [u8]>),
    Keepalive { sequence: u32, reply: bool },
    Other(u16),
}

/// An Ethernet frame retaining its decrypted record allocation. A single-frame
/// record moves its Vec directly; multi-frame records share it until drained.
pub(crate) struct OwnedFrame {
    data: FrameStorage,
    range: Range<usize>,
}
enum FrameStorage {
    Single(Vec<u8>),
    Shared(Arc<Vec<u8>>),
}
impl FrameStorage {
    fn bytes(&self) -> &[u8] {
        match self {
            Self::Single(data) => data,
            Self::Shared(data) => data,
        }
    }
}
impl Deref for OwnedFrame {
    type Target = [u8];

    fn deref(&self) -> &Self::Target {
        &self.data.bytes()[self.range.clone()]
    }
}

pub(crate) enum OwnedPacket {
    Frames(OwnedFrames),
    Keepalive { sequence: u32, reply: bool },
    Other,
}

pub(crate) struct OwnedFrames {
    data: Option<FrameStorage>,
    at: usize,
    remaining: usize,
}
impl Iterator for OwnedFrames {
    type Item = OwnedFrame;

    fn next(&mut self) -> Option<Self::Item> {
        let data = self.data.as_ref()?;
        // decode_owned validated every envelope before creating this iterator.
        // The backing bytes are immutable and never exposed mutably afterwards.
        let len =
            u32::from_le_bytes(data.bytes()[self.at + 4..self.at + 8].try_into().unwrap()) as usize;
        let start = self.at + 8;
        self.at = start + len;
        self.remaining -= 1;
        let data = if self.remaining == 0 {
            self.data.take().unwrap()
        } else {
            match data {
                FrameStorage::Shared(data) => FrameStorage::Shared(Arc::clone(data)),
                FrameStorage::Single(_) => unreachable!(),
            }
        };
        Some(OwnedFrame {
            data,
            range: start..self.at,
        })
    }

    fn size_hint(&self) -> (usize, Option<usize>) {
        (self.remaining, Some(self.remaining))
    }
}
impl ExactSizeIterator for OwnedFrames {}

/// Validate the entire record before releasing any frame. A malformed later
/// envelope must reject the record without delivering its valid prefix.
pub(crate) fn decode_owned(data: Vec<u8>) -> Result<OwnedPacket> {
    ensure!(data.len() >= 2, "truncated tunnel packet");
    let kind = u16::from_be_bytes(data[..2].try_into()?);
    match kind {
        0 => {
            let mut at = 2;
            let mut count = 0;
            while at < data.len() {
                let range = frame_range(&data, at)?;
                at = range.end;
                count += 1;
            }
            ensure!(count != 0, "empty Ethernet packet");
            let storage = if count == 1 {
                FrameStorage::Single(data)
            } else {
                FrameStorage::Shared(Arc::new(data))
            };
            Ok(OwnedPacket::Frames(OwnedFrames {
                data: Some(storage),
                at: 2,
                remaining: count,
            }))
        }
        3 | 7 => {
            ensure!(data.len() == 6, "invalid keepalive length");
            Ok(OwnedPacket::Keepalive {
                sequence: u32::from_be_bytes(data[2..].try_into()?),
                reply: kind == 7,
            })
        }
        _ => Ok(OwnedPacket::Other),
    }
}

fn frame_range(data: &[u8], at: usize) -> Result<Range<usize>> {
    ensure!(data.len() - at >= 22, "truncated Ethernet envelope");
    let flags = u32::from_le_bytes(data[at..at + 4].try_into()?);
    let len = u32::from_le_bytes(data[at + 4..at + 8].try_into()?) as usize;
    let start = at + 8;
    ensure!(
        (14..=65535).contains(&len) && len <= data.len() - start,
        "invalid Ethernet envelope length"
    );
    ensure!(flags == 0, "unsupported Ethernet offload/compression flags");
    Ok(start..start + len)
}

pub fn decode(data: &[u8]) -> Result<Packet<'_>> {
    ensure!(data.len() >= 2, "truncated tunnel packet");
    let kind = u16::from_be_bytes(data[..2].try_into()?);
    match kind {
        0 => {
            let mut at = 2;
            let mut frames = vec![];
            while at < data.len() {
                let range = frame_range(data, at)?;
                at = range.end;
                frames.push(&data[range]);
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

/// Forwarding decision, reusable across authenticated peers.
/// This contains only routing metadata; the Ethernet frame stays unchanged.
pub struct Forwarding {
    destination: Ipv4Addr,
    destination_mac: [u8; 6],
    group: bool,
    arp: bool,
}
impl Forwarding {
    /// Accepted IPv4 broadcasts also have an Ethernet broadcast destination.
    pub fn is_broadcast(&self) -> bool {
        self.destination_mac == [255; 6]
    }
    /// Group traffic has no single destination; directed ARP remains unicast
    /// here even when its Ethernet destination is the broadcast MAC.
    pub fn target(&self) -> Option<Ipv4Addr> {
        (!self.group).then_some(self.destination)
    }
    pub fn deliver_to(&self, target: Ipv4Addr, target_mac: [u8; 6]) -> bool {
        self.group
            || self.destination == target
                && (self.destination_mac == target_mac
                    || self.arp && self.destination_mac == [255; 6])
    }
}

/// Validate the packet header and, except for gratuitous ARP replies, the source.
pub fn forwarding(frame: &[u8], source: Ipv4Addr, source_mac: [u8; 6]) -> Option<Forwarding> {
    if !(14..=MAX_FRAME).contains(&frame.len()) {
        return None;
    }
    let destination_mac = frame[..6].try_into().ok()?;
    if let Some((src, dst)) = ipv4_endpoints(frame) {
        if src != source || frame[6..12] != source_mac {
            return None;
        }
        let group_mac = if dst == Ipv4Addr::BROADCAST || dst == Ipv4Addr::new(26, 255, 255, 255) {
            Some([255; 6])
        } else if dst.is_multicast() {
            let b = dst.octets();
            Some([1, 0, 0x5e, b[1] & 0x7f, b[2], b[3]])
        } else {
            None
        };
        if group_mac.is_some_and(|mac| destination_mac != mac) {
            return None;
        }
        return Some(Forwarding {
            destination: dst,
            destination_mac,
            group: group_mac.is_some(),
            arp: false,
        });
    }
    let (src, dst) = arp_endpoints(frame)?;
    let gratuitous_reply = frame[21] == 2 && src == dst;
    // Check the ARP sender too: the Ethernet source alone is insufficient.
    if !gratuitous_reply
        && (src != source || frame[6..12] != source_mac || frame[22..28] != source_mac)
    {
        return None;
    }
    Some(Forwarding {
        destination: dst,
        destination_mac,
        group: gratuitous_reply || dst == source && destination_mac == [255; 6],
        arp: true,
    })
}

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
    forwarding(frame, source, source_mac).is_some_and(|route| route.deliver_to(target, target_mac))
}

#[cfg(test)]
mod owned_packet_tests {
    use super::*;

    #[test]
    fn single_received_frame_moves_the_record_buffer_without_copying() {
        let frame = gratuitous_arp(Ipv4Addr::new(26, 0, 0, 1));
        let packet = encode(&frame).unwrap();
        let expected_pointer = packet.as_ptr().wrapping_add(10);
        let OwnedPacket::Frames(mut frames) = decode_owned(packet).unwrap() else {
            panic!("expected Ethernet frames");
        };
        assert_eq!(frames.len(), 1);
        let received = frames.next().unwrap();
        assert_eq!(received.as_ptr(), expected_pointer);
        assert_eq!(&*received, frame);
        assert!(frames.next().is_none());
        assert_eq!(frames.len(), 0);
    }

    #[test]
    fn multiple_received_frames_share_storage_and_outlive_the_iterator() {
        let first = gratuitous_arp(Ipv4Addr::new(26, 0, 0, 1));
        let second = gratuitous_arp(Ipv4Addr::new(26, 0, 0, 2));
        let third = gratuitous_arp(Ipv4Addr::new(26, 0, 0, 3));
        let mut packet = encode(&first).unwrap();
        packet.extend_from_slice(&encode(&second).unwrap()[2..]);
        packet.extend_from_slice(&encode(&third).unwrap()[2..]);
        let expected_pointer = packet.as_ptr().wrapping_add(10);
        let OwnedPacket::Frames(mut frames) = decode_owned(packet).unwrap() else {
            panic!("expected Ethernet frames");
        };
        assert_eq!(frames.len(), 3);
        let a = frames.next().unwrap();
        let b = frames.next().unwrap();
        assert_eq!(a.as_ptr(), expected_pointer);
        assert_eq!(b.as_ptr(), a.as_ptr().wrapping_add(first.len() + 8));
        drop(frames);
        assert_eq!(&*a, first);
        assert_eq!(&*b, second);
        drop(a);
        assert_eq!(&*b, second);
    }

    #[test]
    fn malformed_later_envelope_rejects_the_entire_owned_record() {
        let frame = gratuitous_arp(Ipv4Addr::new(26, 0, 0, 1));
        let first = encode(&frame).unwrap();
        let mut two = first.clone();
        two.extend_from_slice(&first[2..]);
        assert!(decode_owned(two.clone()).is_ok());
        let mut bad_flags = two.clone();
        bad_flags[first.len()] = 1;
        assert!(decode_owned(bad_flags).is_err());
        let mut bad_length = two.clone();
        bad_length[first.len() + 4..first.len() + 8].copy_from_slice(&65535u32.to_le_bytes());
        assert!(decode_owned(bad_length).is_err());
        for end in first.len() + 1..two.len() {
            assert!(decode_owned(two[..end].to_vec()).is_err());
        }
        assert!(decode_owned(vec![0, 0]).is_err());
    }

    #[test]
    fn owned_keepalives_preserve_sequence_reply_and_length_checks() {
        for reply in [false, true] {
            let packet = keepalive(u32::MAX, reply);
            assert!(matches!(
                decode_owned(packet.clone()).unwrap(),
                OwnedPacket::Keepalive { sequence: u32::MAX, reply: actual } if actual == reply
            ));
            assert!(decode_owned(packet[..5].to_vec()).is_err());
        }
        assert!(matches!(
            decode_owned(vec![0, 9]).unwrap(),
            OwnedPacket::Other
        ));
    }
}
