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
fn tcp_connect_handles_both_address_families_refusal_and_cancellation() {
    for host in ["127.0.0.1", "::1"] {
        let listener = TcpListener::bind((host, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let mut stream = Framed::connect(host, port, Duration::from_secs(2)).unwrap();
        let (mut remote, _) = listener.accept().unwrap();
        remote
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        stream.send(b"connected").unwrap();
        let mut frame = [0; 13];
        remote.read_exact(&mut frame).unwrap();
        assert_eq!(&frame[4..], b"connected");
        let stop = Arc::new(AtomicBool::new(true));
        assert!(Framed::connect_with_stop(host, port, Duration::from_secs(2), Some(stop)).is_err());
        drop(listener);
        assert!(Framed::connect(host, port, Duration::from_secs(2)).is_err());
    }
}

#[cfg(target_os = "linux")]
#[test]
fn tcp_connect_to_a_full_local_backlog_can_be_cancelled() {
    use std::os::fd::AsRawFd;
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    // A zero backlog holds one unaccepted connection on Linux. The next SYN
    // waits; no external address, firewall rule or privileged setup is needed.
    assert_eq!(unsafe { libc::listen(listener.as_raw_fd(), 0) }, 0);
    let address = listener.local_addr().unwrap();
    let _occupied = TcpStream::connect(address).unwrap();
    let stop = Arc::new(AtomicBool::new(false));
    let signal = stop.clone();
    let worker = thread::spawn(move || {
        Framed::connect_with_stop(
            "127.0.0.1",
            address.port(),
            Duration::from_secs(8),
            Some(signal),
        )
    });
    thread::sleep(Duration::from_millis(50));
    assert!(
        !worker.is_finished(),
        "test must exercise an in-progress connect"
    );
    let started = Instant::now();
    stop.store(true, Ordering::Relaxed);
    assert!(worker.join().unwrap().is_err());
    assert!(started.elapsed() < Duration::from_secs(1));
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
