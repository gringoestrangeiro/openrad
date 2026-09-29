use openrad::tunnel;
use std::net::Ipv4Addr;

// Frozen forwarding rules from before source validation was shared across peers.
fn original_deliver_to(
    frame: &[u8],
    source: Ipv4Addr,
    source_mac: [u8; 6],
    target: Ipv4Addr,
    target_mac: [u8; 6],
) -> bool {
    if !(14..=tunnel::MAX_FRAME).contains(&frame.len()) || frame[6..12] != source_mac {
        return false;
    }
    if let Some((src, dst)) = tunnel::ipv4_endpoints(frame) {
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
    if let Some((src, dst)) = tunnel::arp_endpoints(frame) {
        return src == source
            && frame[22..28] == source_mac
            && (dst == target && (frame[..6] == target_mac || frame[..6] == [255; 6])
                || dst == source && frame[..6] == [255; 6]);
    }
    false
}

fn ipv4(source: Ipv4Addr, destination: Ipv4Addr, mac: [u8; 6], header: usize) -> Vec<u8> {
    let mut frame = vec![0; 14 + header + 8];
    frame[..6].copy_from_slice(&mac);
    frame[6..12].copy_from_slice(&tunnel::mac(source));
    frame[12..14].copy_from_slice(&[8, 0]);
    frame[14] = 0x40 | (header / 4) as u8;
    frame[16..18].copy_from_slice(&((header + 8) as u16).to_be_bytes());
    frame[22] = 64;
    frame[23] = 17;
    frame[26..30].copy_from_slice(&source.octets());
    frame[30..34].copy_from_slice(&destination.octets());
    let checksum = tunnel::checksum(&frame[14..14 + header]);
    frame[24..26].copy_from_slice(&checksum.to_be_bytes());
    frame
}

fn arp(source: Ipv4Addr, destination: Ipv4Addr, mac: [u8; 6], operation: u8) -> Vec<u8> {
    [
        mac.as_slice(),
        &tunnel::mac(source),
        &[8, 6, 0, 1, 8, 0, 6, 4, 0, operation],
        &tunnel::mac(source),
        &source.octets(),
        &[0; 6],
        &destination.octets(),
    ]
    .concat()
}

#[test]
fn shared_validation_preserves_forwarding_for_valid_mutated_and_truncated_frames() {
    let source = Ipv4Addr::new(26, 1, 2, 3);
    let targets = [
        source,
        Ipv4Addr::new(26, 4, 5, 6),
        Ipv4Addr::new(26, 7, 8, 9),
        Ipv4Addr::new(192, 0, 2, 1),
        Ipv4Addr::new(26, 255, 255, 255),
        Ipv4Addr::BROADCAST,
        Ipv4Addr::new(239, 255, 42, 42),
    ];
    let mut frames = Vec::new();
    for destination in targets {
        for mac in [
            tunnel::mac(destination),
            [255; 6],
            [1, 0, 0x5e, 127, 42, 42],
        ] {
            for header in [20, 24, 60] {
                frames.push(ipv4(source, destination, mac, header));
            }
            for operation in [1, 2] {
                frames.push(arp(source, destination, mac, operation));
            }
        }
    }
    let compare = |frame: &[u8]| {
        let before = frame.to_vec();
        for sender_mac in [tunnel::mac(source), [2; 6]] {
            let route = tunnel::forwarding(frame, source, sender_mac);
            for target in targets {
                for mac in [tunnel::mac(target), [255; 6], [2; 6]] {
                    let expected = original_deliver_to(frame, source, sender_mac, target, mac);
                    assert_eq!(
                        route.as_ref().is_some_and(|r| r.deliver_to(target, mac)),
                        expected,
                        "frame={frame:?}, source_mac={sender_mac:?}, target={target}, mac={mac:?}"
                    );
                    assert_eq!(
                        tunnel::deliver_to(frame, source, sender_mac, target, mac),
                        expected
                    );
                }
            }
        }
        assert_eq!(frame, before);
    };
    for frame in frames {
        compare(&frame);
        for len in 0..frame.len() {
            compare(&frame[..len]);
        }
        for index in 0..frame.len() {
            for mask in [1, 0x80, 0xff] {
                let mut changed = frame.clone();
                changed[index] ^= mask;
                compare(&changed);
            }
        }
        for len in [tunnel::MAX_FRAME, tunnel::MAX_FRAME + 1] {
            let mut padded = frame.clone();
            padded.resize(len, 0);
            compare(&padded);
        }
    }
}
