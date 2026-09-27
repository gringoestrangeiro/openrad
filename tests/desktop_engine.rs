use openrad::{
    output::ReportDirectory,
    protocol::*,
    runtime::{base_peer_state, failure_state, valid_inbound, PeerState},
    session::Framed,
    tunnel,
};
use std::{
    collections::BTreeSet,
    io::Write,
    net::{Ipv4Addr, TcpListener},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    thread,
    time::{Duration, Instant},
};
fn peer(rid: u64, networks: &[&str]) -> Peer {
    Peer {
        rid,
        name: "controlled".into(),
        vip: Ipv4Addr::new(26, 0, 0, 2),
        server: Some("192.0.2.1".into()),
        state: 1,
        network_ids: networks.iter().map(|s| s.to_string()).collect(),
    }
}
#[test]
fn leaving_removes_only_that_membership_and_prunes_unshared_peers() {
    let mut m = Membership::default();
    for id in ["a", "b"] {
        m.networks.insert(
            id.into(),
            Network {
                name: id.into(),
                network_id: id.into(),
            },
        );
    }
    m.peers.insert(1, peer(1, &["a"]));
    m.peers.insert(2, peer(2, &["a", "b"]));
    m.remove_network("a");
    assert!(!m.networks.contains_key("a") && !m.peers.contains_key(&1));
    assert_eq!(m.peers[&2].network_ids, BTreeSet::from(["b".into()]));
    assert_eq!(m.eligible(3, &[]).unwrap().len(), 1);
}
#[test]
fn peer_refusal_offline_and_unavailable_are_distinct() {
    let mut p = peer(1, &["a"]);
    assert_eq!(base_peer_state(&p), PeerState::Online);
    p.server = None;
    assert_eq!(base_peer_state(&p), PeerState::Unavailable);
    p.state = 0;
    assert_eq!(base_peer_state(&p), PeerState::Offline);
    assert_eq!(
        failure_state("peer service refused: 000000060000000a"),
        PeerState::Refused
    );
    assert_eq!(failure_state("frame receive timeout"), PeerState::Failed);
}
#[test]
fn leave_wire_matches_verified_manage_network_schema() {
    // Fixed big-endian TLVs for a successful network-leave response.
    let packet = leave("000102030405060708090a0b0c0d0e0f", 101, 1).unwrap();
    assert_eq!(
        hex::encode(packet),
        concat!(
            "000000040100031f00000034",
            "0000004000001319",
            "000000040100030c00000003",
            "00000008020003400000000000000065",
            "000000040100034a00000001",
            "000000100d000309000102030405060708090a0b0c0d0e0f"
        )
    );
    assert!(leave("00", 1, 1).is_err());
}
#[test]
fn leave_response_command_three_is_success_not_an_error_code() {
    let guid = "000102030405060708090a0b0c0d0e0f";
    let response = |request, sequence, error: Option<u32>, network: &str| {
        let mut fields = [
            u32v(0x0100030c, 3),
            u64v(0x02000340, request),
            u32v(0x0100034a, sequence),
            tlv(0x0d000309, &hex::decode(network).unwrap()),
        ]
        .concat();
        if let Some(code) = error {
            fields.extend(u32v(0x010001d2, code));
        }
        [u32v(SERVER_OP, 37), tlv(0x131a, &fields)].concat()
    };
    assert_eq!(
        leave_result(&response(101, 7, None, guid), 101, 7, guid).unwrap(),
        Some(None)
    );
    for code in [0, 3, 19] {
        assert_eq!(
            leave_result(&response(101, 7, Some(code), guid), 101, 7, guid).unwrap(),
            Some(Some(code))
        );
    }
    assert_eq!(
        leave_result(&response(99, 7, None, guid), 101, 7, guid).unwrap(),
        None
    );
    assert!(leave_result(&response(101, 8, None, guid), 101, 7, guid).is_err());
    assert!(leave_result(
        &response(101, 7, None, "ffffffffffffffffffffffffffffffff"),
        101,
        7,
        guid
    )
    .is_err());
}
#[test]
fn public_join_accepts_legacy_response_without_manage_network_sequence() {
    let body = tlv(
        0x1315,
        &[
            textv(0x03000306, "Example").unwrap(),
            tlv(0x0d000309, &[7; 16]),
        ]
        .concat(),
    );
    let response = [
        u32v(SERVER_OP, 37),
        tlv(
            0x131a,
            &[
                u32v(0x0100030c, 2),
                u64v(0x02000340, 104),
                tlv(0x1316, &body),
            ]
            .concat(),
        ),
    ]
    .concat();
    let Some(JoinResult::Membership(root)) = join_result(&response, 104).unwrap() else {
        panic!("successful join");
    };
    assert_eq!(root, body);
    assert!(join_result(&response, 105).unwrap().is_none());
    let refusal = [
        u32v(SERVER_OP, 37),
        tlv(
            0x131a,
            &[
                u32v(0x0100030c, 2),
                u64v(0x02000340, 104),
                u32v(0x010001d2, 19),
            ]
            .concat(),
        ),
    ]
    .concat();
    assert!(matches!(
        join_result(&refusal, 104).unwrap(),
        Some(JoinResult::Refused(19))
    ));
}

fn ipv4_frame(source: Ipv4Addr, target: Ipv4Addr, dest_mac: [u8; 6]) -> Vec<u8> {
    let mut frame = [
        dest_mac.as_slice(),
        &tunnel::mac(source),
        &[8, 0],
        &[0x45, 0, 0, 28, 0, 0, 0, 0, 64, 17, 0, 0],
        &source.octets(),
        &target.octets(),
        &[0; 8],
    ]
    .concat();
    let checksum = tunnel::checksum(&frame[14..34]);
    frame[24..26].copy_from_slice(&checksum.to_be_bytes());
    frame
}
#[test]
fn broadcast_and_multicast_fan_out_without_changing_frame_or_checksums() {
    let source = Ipv4Addr::new(26, 1, 2, 3);
    let peers = [Ipv4Addr::new(26, 4, 5, 6), Ipv4Addr::new(26, 7, 8, 9)];
    for (destination, mac) in [
        (Ipv4Addr::BROADCAST, [255; 6]),
        (Ipv4Addr::new(26, 255, 255, 255), [255; 6]),
        (Ipv4Addr::new(239, 255, 42, 42), [1, 0, 0x5e, 127, 42, 42]),
    ] {
        let frame = ipv4_frame(source, destination, mac);
        for peer in peers {
            assert!(tunnel::deliver_to(
                &frame,
                source,
                tunnel::mac(source),
                peer,
                tunnel::mac(peer)
            ));
            assert!(valid_inbound(&frame, peer, source, tunnel::mac(source)));
        }
        let packet = tunnel::encode(&frame).unwrap();
        match tunnel::decode(&packet).unwrap() {
            tunnel::Packet::Frames(frames) => assert_eq!(frames, vec![frame.as_slice()]),
            _ => panic!("Ethernet envelope expected"),
        }
        let mut wrong_mac = frame.clone();
        wrong_mac[0] = 2;
        assert!(!valid_inbound(
            &wrong_mac,
            peers[0],
            source,
            tunnel::mac(source)
        ));
        assert!(!valid_inbound(
            &frame,
            peers[0],
            peers[1],
            tunnel::mac(source)
        ));
    }
    let frame = ipv4_frame(source, peers[0], tunnel::mac(peers[0]));
    assert!(valid_inbound(&frame, peers[0], source, tunnel::mac(source)));
    assert!(!valid_inbound(
        &frame,
        peers[1],
        source,
        tunnel::mac(source)
    ));
    assert!(!valid_inbound(
        &ipv4_frame(source, Ipv4Addr::new(192, 0, 2, 1), [255; 6]),
        peers[0],
        source,
        tunnel::mac(source)
    ));
}
#[test]
fn disabled_reports_do_not_write_files() {
    let c = ReportDirectory::disabled();
    assert!(c.directory.as_os_str().is_empty());
    c.json("result.json", &serde_json::json!({"connected": false}))
        .unwrap();
    c.child("peer")
        .unwrap()
        .events("control.jsonl")
        .unwrap()
        .event(serde_json::json!({"event":"disconnected"}))
        .unwrap();
}
#[test]
fn fragmented_receive_is_cancellable_without_waiting_for_socket_timeout() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let cancel = Arc::new(AtomicBool::new(false));
    let stop = cancel.clone();
    let server = thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        s.write_all(&[0, 0]).unwrap();
        thread::sleep(Duration::from_millis(100));
        stop.store(true, Ordering::Relaxed);
        thread::sleep(Duration::from_millis(350));
    });
    let mut framed =
        Framed::connect_with_stop("127.0.0.1", port, Duration::from_secs(30), Some(cancel))
            .unwrap();
    let start = Instant::now();
    assert!(framed.receive(4096).is_err());
    assert!(start.elapsed() < Duration::from_secs(1));
    server.join().unwrap();
}
#[test]
fn authenticated_session_can_exceed_old_record_budget() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        for _ in 0..4100 {
            s.write_all(&[0, 0, 0, 1, 42]).unwrap();
        }
    });
    let mut framed = Framed::connect("127.0.0.1", port, Duration::from_secs(30)).unwrap();
    framed.sustain();
    for _ in 0..4100 {
        assert_eq!(framed.receive(4).unwrap(), [42]);
    }
    server.join().unwrap();
}
#[test]
fn arp_forwarding_rejects_spoofed_peer_and_oversized_frame() {
    let vip = Ipv4Addr::new(26, 0, 0, 1);
    let remote = Ipv4Addr::new(26, 0, 0, 2);
    let mac = tunnel::mac(remote);
    let frame = [
        [255u8; 6].as_slice(),
        &mac,
        &[8, 6, 0, 1, 8, 0, 6, 4, 0, 1],
        &mac,
        &remote.octets(),
        &[0; 6],
        &vip.octets(),
    ]
    .concat();
    assert!(valid_inbound(&frame, vip, remote, mac));
    assert!(!valid_inbound(&frame, vip, Ipv4Addr::new(26, 0, 0, 3), mac));
    assert!(!valid_inbound(&frame, vip, remote, [0; 6]));
    let mut full = frame;
    full.resize(tunnel::MAX_FRAME, 0);
    assert!(valid_inbound(&full, vip, remote, mac));
    let mut large = full;
    large.push(0);
    assert!(!valid_inbound(&large, vip, remote, mac));
}
