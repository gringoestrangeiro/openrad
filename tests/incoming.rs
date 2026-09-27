use num_bigint::BigUint;
use openrad::{
    crypto::{sh_message, Channel, ShClient, ShServer},
    output::ReportDirectory,
    peer::{PeerChannel, PeerStream, TransportPath},
    protocol::*,
    session::Framed,
    tunnel,
    udp::Enet,
};
use std::{
    net::{TcpListener, TcpStream, UdpSocket},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};

#[test]
fn incoming_sh_matches_fixed_messages_and_rejects_impersonation() {
    let f: serde_json::Value =
        serde_json::from_str(include_str!("fixtures/incoming-sh.json")).unwrap();
    let messages: Vec<Vec<u8>> = f["messages"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| hex::decode(v.as_str().unwrap()).unwrap())
        .collect();
    let server = || {
        ShServer::with_private(
            12345,
            b"controlled-test-password",
            (0..16).collect(),
            BigUint::from(3000u32),
        )
        .unwrap()
    };
    let mut s = server();
    assert!(s.public(&messages[2]).is_err());
    assert!(s
        .hello(&sh_message(1, 0x20000000, &12346u64.to_le_bytes()))
        .is_err());
    assert_eq!(s.hello(&messages[0]).unwrap(), messages[1]);
    assert!(s.public(&sh_message(3, 0x60000000, &[0])).is_err());
    assert!(s.public(&sh_message(3, 0x60000000, &[255; 193])).is_err());
    assert_eq!(s.public(&messages[2]).unwrap(), messages[3]);
    let (m, key) = s.proof(&messages[4]).unwrap();
    assert_eq!(m, messages[5]);
    assert_eq!(hex::encode(key), f["key"].as_str().unwrap());
    assert!(s.proof(&messages[4]).is_err());
    let mut s = server();
    s.hello(&messages[0]).unwrap();
    s.public(&messages[2]).unwrap();
    assert!(s.proof(&sh_message(5, 0x70000000, &[0; 20])).is_err());
    assert!(s.proof(&messages[4]).is_err());
}
#[test]
fn incoming_service_binds_rid_mac_and_negotiated_ack() {
    let mac = tunnel::mac("26.0.0.1".parse().unwrap());
    assert!(tunnel::accept_syn(&tunnel::syn(7, &mac), 8, &mac).is_err());
    assert!(tunnel::accept_syn(&tunnel::syn(7, &[255; 6]), 7, &mac).is_err());
    let (response, _, version) = tunnel::accept_syn(&tunnel::syn(7, &mac), 7, &mac).unwrap();
    let (ack, _, _) = tunnel::synack(&response).unwrap();
    tunnel::accept_ack(&ack, version).unwrap();
    assert!(tunnel::accept_ack(&ack, version - 1).is_err());
    assert!(tunnel::accept_ack(
        &[u64v(0x02000303, 1), u32v(0x01000302, version)].concat(),
        version
    )
    .is_err());
}
fn peer() -> Peer {
    Peer {
        rid: 11,
        name: "owned test initiator".into(),
        vip: "26.0.0.11".parse().unwrap(),
        server: Some("127.0.0.1".into()),
        state: 1,
        network_ids: Default::default(),
    }
}
fn initiator(mut stream: PeerStream) {
    let mut sh = ShClient::new(42, b"synthetic-connection-password").unwrap();
    stream.send(&sh.start().unwrap()).unwrap();
    let r = stream.receive(65536).unwrap();
    stream.send(&sh.parameters(&r).unwrap()).unwrap();
    let r = stream.receive(65536).unwrap();
    stream.send(&sh.challenge(&r).unwrap()).unwrap();
    let key = sh.confirm(&stream.receive(65536).unwrap()).unwrap();
    let mut channel = Channel::new(&key[..32]).unwrap();
    for plain in [
        vec![0xef, 0xbe, 0xad, 0xde],
        vec![7; 16],
        tunnel::syn(11, &tunnel::mac("26.0.0.11".parse().unwrap())),
    ] {
        stream.send(&channel.encrypt(&plain).unwrap()).unwrap();
    }
    let plain = channel.decrypt(&stream.receive(65536).unwrap()).unwrap();
    let (ack, _, _) = tunnel::synack(&plain).unwrap();
    stream.send(&channel.encrypt(&ack).unwrap()).unwrap();
    // Larger than ENET MTU, verifies reliable fragmentation in both directions.
    let data: Vec<u8> = (0..6000).map(|i| (i % 251) as u8).collect();
    stream.send(&channel.encrypt(&data).unwrap()).unwrap();
    assert_eq!(
        channel.decrypt(&stream.receive(65536).unwrap()).unwrap(),
        data
    );
}
fn responder(stream: PeerStream, path: TransportPath) {
    let mut channel = PeerChannel::accept_authenticated(
        stream,
        path,
        b"synthetic-connection-password",
        42,
        "26.0.0.42".parse().unwrap(),
        peer(),
        &ReportDirectory::disabled(),
    )
    .unwrap();
    assert!(
        channel.transport.incoming
            && channel.transport.authenticated
            && channel.transport.service_connected
    );
    let data = channel.receive().unwrap();
    channel.send(&data).unwrap();
    // Pump UDP until the response has been ACKed.
    for _ in 0..15 {
        let _ = channel.stream.ready(10);
    }
}
#[test]
fn incoming_tcp_authenticates_service_and_exchanges_encrypted_payload() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        let mut f = Framed::from_socket(listener.accept().unwrap().0, Duration::from_secs(5), None)
            .unwrap();
        f.accept_rendezvous(11, 99).unwrap();
        responder(PeerStream::Tcp(f), TransportPath::DirectTcp);
    });
    let mut f = Framed::from_socket(
        TcpStream::connect(addr).unwrap(),
        Duration::from_secs(5),
        None,
    )
    .unwrap();
    f.peer_rendezvous(11, 99, 17).unwrap();
    initiator(PeerStream::Tcp(f));
    server.join().unwrap();
}
#[test]
fn incoming_udp_authenticates_service_and_exchanges_fragmented_encrypted_payload() {
    let a = UdpSocket::bind("[::1]:0").unwrap();
    let b = UdpSocket::bind("[::1]:0").unwrap();
    let aa = a.local_addr().unwrap();
    let ba = b.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        responder(
            PeerStream::Udp(Enet::accept(b, &[aa], 987, Duration::from_secs(5), None).unwrap()),
            TransportPath::DirectUdp,
        )
    });
    initiator(PeerStream::Udp(
        Enet::connect(a, &[ba], 987, Duration::from_secs(5), None).unwrap(),
    ));
    server.join().unwrap();
}
#[test]
fn incoming_wait_and_tcp_preamble_are_bounded_and_cancellable() {
    let stop = Arc::new(AtomicBool::new(false));
    let signal = stop.clone();
    let a = UdpSocket::bind("[::1]:0").unwrap();
    let b = UdpSocket::bind("[::1]:0").unwrap();
    let endpoint = b.local_addr().unwrap();
    let started = Instant::now();
    let t = std::thread::spawn(move || {
        Enet::accept(a, &[endpoint], 1, Duration::from_secs(20), Some(signal)).is_err()
    });
    std::thread::sleep(Duration::from_millis(30));
    stop.store(true, Ordering::Relaxed);
    assert!(t.join().unwrap());
    assert!(started.elapsed() < Duration::from_secs(1));
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let t = std::thread::spawn(move || {
        Framed::from_socket(listener.accept().unwrap().0, Duration::from_secs(1), None)
            .unwrap()
            .accept_rendezvous(11, 99)
            .is_err()
    });
    let mut f = Framed::from_socket(
        TcpStream::connect(addr).unwrap(),
        Duration::from_secs(1),
        None,
    )
    .unwrap();
    assert!(f.peer_rendezvous(12, 99, 17).is_err());
    assert!(t.join().unwrap());
}
