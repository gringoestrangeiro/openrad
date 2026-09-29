use num_bigint::BigUint;
use openrad::{
    crypto::{rsa_session, sh_message, Channel, ShClient},
    protocol::*,
    tunnel,
};
use serde_json::Value;
use std::net::Ipv4Addr;
fn fixture() -> Value {
    serde_json::from_str(include_str!("fixtures/reference.json")).unwrap()
}
fn bytes(v: &Value) -> Vec<u8> {
    hex::decode(v.as_str().unwrap()).unwrap()
}

#[test]
fn channel_matches_fixed_vectors_across_lengths_chaining_and_rekey() {
    let f = fixture();
    let key: Vec<u8> = (0..32).collect();
    let (mut tx, mut rx) = (Channel::new(&key).unwrap(), Channel::new(&key).unwrap());
    for r in f["channel"].as_array().unwrap() {
        let pt = bytes(&r["pt"]);
        let ct = bytes(&r["ct"]);
        assert_eq!(tx.encrypt(&pt).unwrap(), ct);
        assert_eq!(rx.decrypt(&ct).unwrap(), pt);
    }
    let key: Vec<u8> = (32..64).collect();
    tx.rekey(&key).unwrap();
    rx.rekey(&key).unwrap();
    assert_eq!(
        tx.encrypt(&bytes(&f["rekey"]["pt"])).unwrap(),
        bytes(&f["rekey"]["ct"])
    );
    assert_eq!(
        rx.decrypt(&bytes(&f["rekey"]["ct"])).unwrap(),
        bytes(&f["rekey"]["pt"])
    );
}
#[test]
fn channel_rejects_tamper_and_consumes_ciphertext_iv() {
    let (mut tx, mut rx) = (
        Channel::new(&[0; 32]).unwrap(),
        Channel::new(&[0; 32]).unwrap(),
    );
    let mut bad = tx.encrypt(b"authenticated message long enough").unwrap();
    bad[0] ^= 1;
    assert!(rx.decrypt(&bad).is_err());
    assert_eq!(rx.decrypt(&tx.encrypt(b"next").unwrap()).unwrap(), b"next");
    assert!(rx.decrypt(&[0; 15]).is_err());
    assert!(tx.encrypt(&[]).is_err());
}
#[test]
fn reused_channel_buffers_match_independent_vectors_and_rekey_without_reallocation() {
    let f = fixture();
    let key: Vec<u8> = (0..32).collect();
    let (mut tx, mut rx) = (Channel::new(&key).unwrap(), Channel::new(&key).unwrap());
    let mut buffer = Vec::with_capacity(4 * 1024 * 1024 + 32);
    let allocation = buffer.as_ptr();
    for r in f["channel"].as_array().unwrap() {
        let pt = bytes(&r["pt"]);
        let ct = bytes(&r["ct"]);
        buffer.clear();
        buffer.extend_from_slice(&pt);
        tx.encrypt_in_place(&mut buffer).unwrap();
        assert_eq!(buffer, ct);
        assert_eq!(buffer.as_ptr(), allocation);
        let received = ct;
        let storage = received.as_ptr();
        let decrypted = rx.decrypt_owned(received).unwrap();
        assert_eq!(decrypted, pt);
        assert_eq!(decrypted.as_ptr(), storage);
    }
    let key: Vec<u8> = (32..64).collect();
    tx.rekey(&key).unwrap();
    rx.rekey(&key).unwrap();
    buffer.clear();
    buffer.extend(bytes(&f["rekey"]["pt"]));
    tx.encrypt_in_place(&mut buffer).unwrap();
    assert_eq!(buffer, bytes(&f["rekey"]["ct"]));
    assert_eq!(rx.decrypt_owned(buffer).unwrap(), bytes(&f["rekey"]["pt"]));
}
#[test]
fn reused_channel_buffers_keep_tamper_rejection_and_ciphertext_iv_consumption() {
    let (mut tx, mut rx) = (
        Channel::new(&[0; 32]).unwrap(),
        Channel::new(&[0; 32]).unwrap(),
    );
    let mut bad = tx.encrypt(b"authenticated message long enough").unwrap();
    bad[0] ^= 1;
    assert!(rx.decrypt_owned(bad).is_err());
    let mut next = b"next".to_vec();
    tx.encrypt_in_place(&mut next).unwrap();
    assert_eq!(rx.decrypt_owned(next).unwrap(), b"next");
    assert!(rx.decrypt_owned(vec![0; 15]).is_err());
    assert!(tx.encrypt_in_place(&mut Vec::new()).is_err());
}
#[test]
fn sh_matches_fixed_responder_vectors_and_rejects_wrong_m2() {
    let f = fixture();
    let s = &f["sh"];
    let m: Vec<Vec<u8>> = s["messages"]
        .as_array()
        .unwrap()
        .iter()
        .map(bytes)
        .collect();
    let mut c =
        ShClient::with_private(12345, b"controlled-test-password", BigUint::from(2000u32)).unwrap();
    assert_eq!(c.start().unwrap(), m[0]);
    assert_eq!(c.parameters(&m[1]).unwrap(), m[2]);
    assert_eq!(c.challenge(&m[3]).unwrap(), m[4]);
    assert!(c.confirm(&sh_message(6, 0x70000000, &[0; 20])).is_err());
    assert_eq!(c.confirm(&m[5]).unwrap(), bytes(&s["key"]));
    assert!(c.confirm(&m[5]).is_err());
}
#[test]
fn sh_rejects_wrong_group_peer_width_state_and_trailing_data() {
    let f = fixture();
    let m: Vec<Vec<u8>> = f["sh"]["messages"]
        .as_array()
        .unwrap()
        .iter()
        .map(bytes)
        .collect();
    let mut c =
        ShClient::with_private(12345, b"controlled-test-password", BigUint::from(2000u32)).unwrap();
    assert!(c.parameters(&m[1]).is_err());
    c.start().unwrap();
    let mut wrong = m[1].clone();
    wrong[12] ^= 1;
    assert!(c.parameters(&wrong).is_err());
    c.parameters(&m[1]).unwrap();
    assert!(c.challenge(&sh_message(4, 0x60000000, &[0])).is_err());
    let mut bad = m[3].clone();
    bad.push(0);
    assert!(c.challenge(&bad).is_err());
}
#[test]
fn rsa_recovers_verified_header_with_independent_private_operation() {
    let p = (BigUint::from(1u8) << 521usize) - BigUint::from(1u8);
    let q = (BigUint::from(1u8) << 607usize) - BigUint::from(1u8);
    let n = &p * &q;
    let phi = (&p - BigUint::from(1u8)) * (&q - BigUint::from(1u8));
    let d = BigUint::from(65537u32).modinv(&phi).unwrap();
    for purpose in [3, 4, 5] {
        let (ct, secret) = rsa_session(&n.to_bytes_be(), purpose).unwrap();
        let mut em = BigUint::from_bytes_be(&ct).modpow(&d, &n).to_bytes_be();
        em.insert(0, 0);
        assert_eq!(&em[..2], &[0, 2]);
        let offset = em[2..].iter().position(|b| *b == 0).unwrap() + 2;
        assert!(offset >= 10);
        let expected = [
            3u32.to_le_bytes().to_vec(),
            0x4bu32.to_le_bytes().to_vec(),
            purpose.to_le_bytes().to_vec(),
            secret,
        ]
        .concat();
        assert_eq!(&em[offset + 1..], expected);
    }
}
#[test]
fn builders_match_fixed_wire_vectors() {
    let f = fixture();
    let p = &f["protocol"];
    assert_eq!(
        login("openrad-test", 143, 4, None).unwrap(),
        bytes(&p["login"])
    );
    assert_eq!(
        login("openrad-test", 143, 5, Some(12345)).unwrap(),
        bytes(&p["connect_login"])
    );
    assert_eq!(
        public_list("Minecraft", 1, 0).unwrap(),
        bytes(&p["public_list"])
    );
    assert_eq!(
        join("Minecraft [Português 16]", 2, 1).unwrap(),
        bytes(&p["join"])
    );
    assert_eq!(tunnel::syn(12345, &[2, 0, 192, 0, 2, 1]), bytes(&p["syn"]));
}
#[test]
fn tlvs_reject_truncation_duplicate_and_wrong_width() {
    let b = u32v(123, 45);
    assert!(records(&b[..b.len() - 1]).is_err());
    assert!(field(&records(&[b.clone(), b].concat()).unwrap(), 123).is_err());
    assert!(int64(&[0; 4]).is_err());
    assert!(text(&[0, 0]).is_err());
}
#[test]
fn ethernet_multiple_frames_and_offload_rejection() {
    let f = bytes(&fixture()["protocol"]["ethernet"]);
    let mut two = f.clone();
    two.extend(&f[2..]);
    match tunnel::decode(&two).unwrap() {
        tunnel::Packet::Frames(frames) => assert_eq!(frames.len(), 2),
        _ => panic!(),
    }
    assert!(tunnel::decode(&two[..two.len() - 1]).is_err());
    let mut bad = f;
    bad[2] = 1;
    assert!(tunnel::decode(&bad).is_err());
}
#[test]
fn membership_deduplicates_shared_peers_and_excludes_offline() {
    let mut m = Membership::default();
    for (name, id) in [("first", "a"), ("second", "b")] {
        m.networks.insert(
            id.into(),
            Network {
                name: name.into(),
                network_id: id.into(),
            },
        );
    }
    m.peers.insert(
        5,
        Peer {
            rid: 5,
            name: "test".into(),
            vip: Ipv4Addr::new(192, 0, 2, 5),
            server: Some("192.0.2.1".into()),
            state: 1,
            network_ids: ["a".into(), "b".into()].into(),
        },
    );
    assert_eq!(
        m.eligible(1, &["first".into(), "second".into()])
            .unwrap()
            .len(),
        1
    );
    assert!(m.eligible(5, &[]).unwrap().is_empty());
    m.peers.get_mut(&5).unwrap().state = 0;
    assert!(m.eligible(1, &[]).unwrap().is_empty());
    assert!(m.eligible(1, &["missing".into()]).is_err());
}

#[test]
fn registered_identity_requires_direct_fields_and_private_persistence() {
    let body = [
        u64v(0x020001e1, 123),
        u32v(0x01000305, 0x1a010203),
        textv(0x03000304, "owned").unwrap(),
        tlv(0x09000305, &[1; 16]),
        tlv(0x0a0001c8, b"synthetic-password"),
        textv(0x030001e2, "192.0.2.1").unwrap(),
    ]
    .concat();
    let response = [u32v(SERVER_OP, 8), tlv(0x1233, &body)].concat();
    let id = Identity::registered(&response).unwrap();
    assert_eq!(id.rid, 123);
    assert_eq!(id.vip, Ipv4Addr::new(26, 1, 2, 3));
    assert_eq!(id.password().unwrap(), b"synthetic-password");
    let bad = [u32v(SERVER_OP, 8), tlv(0x1233, &tlv(0x1317, &body))].concat();
    assert!(Identity::registered(&bad).is_err());
}

#[test]
fn joining_a_second_network_preserves_shared_peer_memberships() {
    let peer = [
        u64v(0x020001e1, 7),
        textv(0x03000304, "owned").unwrap(),
        u32v(0x01000305, 0xc0000207),
        textv(0x030001c9, "192.0.2.1").unwrap(),
        u32v(0x010003a0, 1),
    ]
    .concat();
    let mut membership = Membership::default();
    for (name, network) in [("first", [1; 16]), ("second", [2; 16])] {
        let snapshot = tlv(
            0x1316,
            &[
                tlv(
                    0x1315,
                    &[textv(0x03000306, name).unwrap(), tlv(0x0d000309, &network)].concat(),
                ),
                tlv(0x1317, &peer),
                tlv(
                    0x1318,
                    &[u64v(0x020001e1, 7), tlv(0x0d000309, &network)].concat(),
                ),
            ]
            .concat(),
        );
        membership.snapshot(&snapshot).unwrap();
    }
    assert_eq!(membership.peers[&7].network_ids.len(), 2);
    assert_eq!(membership.eligible(1, &["first".into()]).unwrap().len(), 1);
    assert_eq!(membership.eligible(1, &["second".into()]).unwrap().len(), 1);
}

#[test]
fn framed_transport_handles_fragmented_coalesced_and_truncated_records() {
    use std::{io::Write, net::TcpListener, thread, time::Duration};
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        for byte in [0, 0, 0, 3, b'a', b'b', b'c'] {
            socket.write_all(&[byte]).unwrap();
        }
        socket
            .write_all(&[0, 0, 0, 2, b'd', b'e', 0, 0, 0, 4, b'f'])
            .unwrap();
    });
    let mut client =
        openrad::session::Framed::connect("127.0.0.1", port, Duration::from_secs(3)).unwrap();
    assert_eq!(client.receive(10).unwrap(), b"abc");
    assert_eq!(client.receive(10).unwrap(), b"de");
    assert!(client.receive(10).is_err());
    server.join().unwrap();
}
