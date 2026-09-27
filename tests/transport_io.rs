use openrad::{crypto::Channel, session::Framed, tunnel};
use std::{
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc,
    },
    thread,
    time::{Duration, Instant},
};

fn pair(stop: Option<Arc<AtomicBool>>) -> (Framed, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let socket = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    let (remote, _) = listener.accept().unwrap();
    remote
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    (
        Framed::from_socket(socket, Duration::from_secs(10), stop).unwrap(),
        remote,
    )
}

#[test]
fn vectored_tcp_send_preserves_every_ciphertext_and_framing_byte() {
    let fixtures: serde_json::Value =
        serde_json::from_str(include_str!("fixtures/reference.json")).unwrap();
    let key: Vec<u8> = (0..32).collect();
    let mut channel = Channel::new(&key).unwrap();
    let (mut framed, mut remote) = pair(None);
    for entry in fixtures["channel"].as_array().unwrap() {
        let plaintext = hex::decode(entry["pt"].as_str().unwrap()).unwrap();
        let ciphertext = hex::decode(entry["ct"].as_str().unwrap()).unwrap();
        framed.send(&channel.encrypt(&plaintext).unwrap()).unwrap();
        let mut wire = vec![0; 4 + ciphertext.len()];
        remote.read_exact(&mut wire).unwrap();
        assert_eq!(wire[..4], (ciphertext.len() as u32).to_be_bytes());
        assert_eq!(wire[4..], ciphertext);
    }
}

#[test]
fn ready_preserves_partial_headers_payloads_and_eof() {
    let (mut framed, mut remote) = pair(None);
    assert!(!framed.ready(0).unwrap());
    assert!(!framed.ready(10).unwrap());
    remote.write_all(&[0, 0]).unwrap();
    assert!(framed.ready(1000).unwrap());
    assert!(framed.ready(0).unwrap());
    remote.write_all(&[0, 3, 1, 2, 3, 0, 0, 0, 1, 42]).unwrap();
    assert_eq!(framed.receive(32).unwrap(), [1, 2, 3]);
    assert!(framed.ready(0).unwrap());
    assert_eq!(framed.receive(32).unwrap(), [42]);
    drop(remote);
    assert!(framed.ready(1000).unwrap());
    assert!(framed.receive(32).is_err());
}

#[test]
fn long_readiness_wait_can_be_cancelled() {
    let stop = Arc::new(AtomicBool::new(false));
    let (framed, _remote) = pair(Some(stop.clone()));
    let (entered, waiting) = mpsc::channel();
    let worker = thread::spawn(move || {
        entered.send(()).unwrap();
        framed.ready(5000)
    });
    waiting.recv().unwrap();
    thread::sleep(Duration::from_millis(20));
    let start = Instant::now();
    stop.store(true, Ordering::Relaxed);
    assert!(worker.join().unwrap().is_err());
    assert!(start.elapsed() < Duration::from_secs(1));
}

#[test]
fn ethernet_envelope_matches_original_bytes_at_length_boundaries() {
    for size in [14, 60, 1414, 1500, 65535] {
        let frame: Vec<u8> = (0..size).map(|n| (n % 251) as u8).collect();
        let packet = tunnel::encode(&frame).unwrap();
        assert_eq!(&packet[..6], &[0; 6]);
        assert_eq!(packet[6..10], (size as u32).to_le_bytes());
        assert_eq!(packet[10..], frame);
    }
    assert!(tunnel::encode(&[0; 13]).is_err());
    assert!(tunnel::encode(&[0; 65536]).is_err());
}
