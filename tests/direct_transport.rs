use openrad::protocol::*;

fn candidate(host: &str, port: u32) -> Vec<u8> {
    tlv(
        0x127d,
        &[textv(0x030001c2, host).unwrap(), u32v(0x010001c3, port)].concat(),
    )
}
fn push(body: &[u8]) -> Vec<u8> {
    [u32v(SERVER_OP, 6), u64v(0x020001c1, 42), tlv(0x1236, body)].concat()
}
#[test]
fn tcp_candidates_require_correlated_bounded_authenticated_message_shape() {
    let a = candidate("192.0.2.1", 1234);
    let parsed = tcp_candidates(&push(&[a.clone(), a].concat()), 42).unwrap();
    assert_eq!(parsed.len(), 1);
    assert_eq!(parsed[0].endpoint.to_string(), "192.0.2.1:1234");
    let v6 = tcp_candidates(&push(&candidate("2001:db8::1", 4567)), 42).unwrap();
    assert_eq!(v6[0].endpoint.to_string(), "[2001:db8::1]:4567");
    assert!(tcp_candidates(&push(&candidate("192.0.2.1", 1234)), 43).is_err());
    for (ip, port) in [
        ("0.0.0.0", 1),
        ("255.255.255.255", 1),
        ("239.1.1.1", 1),
        ("192.0.2.1", 0),
        ("192.0.2.1", 65536),
        ("untrusted.example", 1),
    ] {
        assert!(tcp_candidates(&push(&candidate(ip, port)), 42).is_err());
    }
    let duplicate_port = tlv(
        0x127d,
        &[
            textv(0x030001c2, "192.0.2.1").unwrap(),
            u32v(0x010001c3, 1),
            u32v(0x010001c3, 2),
        ]
        .concat(),
    );
    assert!(tcp_candidates(&push(&duplicate_port), 42).is_err());
    assert!(tcp_candidates(&push(&[0; 7]), 42).is_err());
    let too_many: Vec<u8> = (1..=33).flat_map(|p| candidate("192.0.2.1", p)).collect();
    assert!(tcp_candidates(&push(&too_many), 42).is_err());
    let duplicates = candidate("192.0.2.1", 1).repeat(33);
    assert!(tcp_candidates(&push(&duplicates), 42).is_err());
}
#[test]
fn outgoing_tcp_request_is_info_not_attachment_heartbeat() {
    let request = request_tcp_candidates(42);
    let f = records(&request).unwrap();
    assert_eq!(int32(field(&f, CLIENT_OP).unwrap()).unwrap(), 2);
    assert_eq!(int64(field(&f, 0x020001c1).unwrap()).unwrap(), 42);
    assert_eq!(f.len(), 2);
}

#[test]
fn rendezvous_checksum_matches_fixed_vector() {
    let body = hex::decode("000000000000000b000000000000006300000011").unwrap();
    assert_eq!(openrad::session::rendezvous_checksum(&body), 0x8cbdab87);
}

#[test]
fn mixed_candidate_list_preserves_usable_addresses_and_records_exclusions() {
    let data = push(
        &[
            candidate("untrusted.example", 1234),
            candidate("192.0.2.1", 1234),
            candidate("fe80::1%12", 5),
        ]
        .concat(),
    );
    let (valid, excluded) = direct_candidates(&data, 42, 6, 0x1236).unwrap();
    assert_eq!(valid.len(), 1);
    assert_eq!(excluded.len(), 2);
    assert!(direct_candidates(&data, 43, 6, 0x1236).is_err());
}

#[test]
fn udp_nonce_is_owned_by_incoming_role_and_candidates_are_correlated() {
    let endpoint = "192.0.2.1:1234".parse().unwrap();
    let outgoing = advertise_udp(42, &[endpoint], 0).unwrap();
    let outer = records(&outgoing).unwrap();
    assert_eq!(int32(field(&outer, CLIENT_OP).unwrap()).unwrap(), 28);
    let body = records(field(&outer, 0x127b).unwrap()).unwrap();
    assert!(optional(&body, 0x0100020a).unwrap().is_none());
    let incoming = advertise_udp(42, &[endpoint], 123).unwrap();
    let outer = records(&incoming).unwrap();
    let body = records(field(&outer, 0x127b).unwrap()).unwrap();
    assert_eq!(int32(field(&body, 0x0100020a).unwrap()).unwrap(), 123);
}

#[test]
fn ues_mapping_rejects_wrong_transactions_truncation_and_duplicate_addresses() {
    let transaction = [17; 16];
    let attribute = [0, 1, 0, 8, 0, 1, 0x30, 0x39, 192, 0, 2, 5];
    let mut response = [vec![1, 1, 0, 12], transaction.to_vec(), attribute.to_vec()].concat();
    assert_eq!(
        openrad::udp::mapped_response(&response, &transaction)
            .unwrap()
            .to_string(),
        "192.0.2.5:12345"
    );
    assert!(openrad::udp::mapped_response(&response, &[18; 16]).is_err());
    assert!(openrad::udp::mapped_response(&response[..response.len() - 1], &transaction).is_err());
    response[3] = 24;
    response.extend(attribute);
    assert!(openrad::udp::mapped_response(&response, &transaction).is_err());
}
#[test]
fn ues_uses_authenticated_dedicated_list_not_connection_servers() {
    use openrad::protocol::*;
    let configuration = tlv(0x1263, &textv(0x03000237, "192.0.2.200").unwrap());
    let correct = tlv(0x1340, &tlv(0x0e00036c, b"192.0.2.10\0"));
    let login = [u32v(SERVER_OP, 21), configuration.clone(), correct.clone()].concat();
    assert_eq!(
        ues_hosts(&login).unwrap(),
        vec!["192.0.2.10".parse::<std::net::Ipv4Addr>().unwrap()]
    );
    assert!(ues_hosts(&[u32v(SERVER_OP, 21), configuration].concat())
        .unwrap()
        .is_empty());
    for value in [
        b"host.example\0".as_slice(),
        b"192.0.2.10",
        b"192.0.2.10\0x\0",
        b"0.0.0.0\0",
    ] {
        assert!(
            ues_hosts(&[u32v(SERVER_OP, 21), tlv(0x1340, &tlv(0x0e00036c, value))].concat())
                .is_err()
        );
    }
    assert!(ues_hosts(&[login, correct].concat()).is_err());
    assert!(ues_hosts(
        &[
            u32v(SERVER_OP, 21),
            tlv(0x1340, &tlv(0x0e00036c, b"192.0.2.10\0").repeat(33))
        ]
        .concat()
    )
    .is_err());
}
