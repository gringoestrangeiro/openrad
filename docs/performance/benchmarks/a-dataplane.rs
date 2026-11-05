use anyhow::{ensure, Context, Result};
use std::{
    alloc::{GlobalAlloc, Layout, System},
    hint::black_box,
    net::UdpSocket,
    sync::atomic::{AtomicBool, AtomicUsize, Ordering},
    time::{Duration, Instant},
};
// CRYPTO_MODULE
// CHECKSUM_MODULE
// REFUSED_FUNCTION
// ENCODE_FUNCTION
struct Sender;
impl Sender {
    // TRANSMIT_FUNCTION
}
struct Allocator;
static ALLOCS: AtomicUsize = AtomicUsize::new(0);
static COUNT_ALLOCS: AtomicBool = AtomicBool::new(false);
unsafe impl GlobalAlloc for Allocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        if COUNT_ALLOCS.load(Ordering::Relaxed) {
            ALLOCS.fetch_add(1, Ordering::Relaxed);
        }
        System.alloc(layout)
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        System.dealloc(ptr, layout)
    }
}
#[global_allocator]
static ALLOCATOR: Allocator = Allocator;
fn measure(name: &str, size: usize, peers: usize, batches: usize, mut operation: impl FnMut()) {
    const BATCH: usize = 64;
    for _ in 0..512 {
        operation();
    }
    let mut timings = Vec::with_capacity(batches);
    let allocations = ALLOCS.load(Ordering::Relaxed);
    COUNT_ALLOCS.store(true, Ordering::Relaxed);
    for _ in 0..BATCH {
        operation();
    }
    COUNT_ALLOCS.store(false, Ordering::Relaxed);
    let allocations_per_64 = ALLOCS.load(Ordering::Relaxed) - allocations;
    let start = Instant::now();
    for _ in 0..batches {
        let tick = Instant::now();
        for _ in 0..BATCH {
            operation();
        }
        timings.push(tick.elapsed().as_nanos() as f64 / BATCH as f64);
    }
    let ns = start.elapsed().as_nanos() as f64 / (batches * BATCH) as f64;
    timings.sort_by(f64::total_cmp);
    println!("{{\"case\":\"{}\",\"size\":{},\"peers\":{},\"operations\":{},\"ns_op\":{:.3},\"p50_batch_ns_op\":{:.3},\"p95_batch_ns_op\":{:.3},\"p99_batch_ns_op\":{:.3},\"allocations_per_64\":{}}}", name, size, peers, batches*BATCH, ns, timings[batches/2], timings[batches*95/100], timings[batches*99/100], allocations_per_64);
}
fn main() {
    for size in [100, 1514] {
        let frame: Vec<u8> = (0..size).map(|n| n as u8).collect();
        measure("crc", size, 1, 2000, || {
            black_box(session::rendezvous_checksum(black_box(&frame)));
        });
        let mut enc = crypto::Channel::new(&[42; 32]).unwrap();
        let mut dec = crypto::Channel::new(&[42; 32]).unwrap();
        let mut buffer = Vec::with_capacity(size + 32);
        measure("frame_crypto_roundtrip", size, 1, 500, || {
            encode_into(black_box(&frame), &mut buffer).unwrap();
            enc.encrypt_in_place(&mut buffer).unwrap();
            let plain = dec.decrypt_owned(buffer.clone()).unwrap();
            assert_eq!(&plain[10..], frame);
            black_box(plain);
        });
    }
    for size in [100, 1392] {
        for peers in [1, 10, 100] {
            let pairs: Vec<_> = (0..peers)
                .map(|_| {
                    let tx = UdpSocket::bind("127.0.0.1:0").unwrap();
                    let rx = UdpSocket::bind("127.0.0.1:0").unwrap();
                    tx.connect(rx.local_addr().unwrap()).unwrap();
                    tx.set_nonblocking(true).unwrap();
                    rx.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
                    (tx, rx)
                })
                .collect();
            let command: Vec<u8> = (0..size).map(|n| n as u8).collect();
            let started = Instant::now();
            let mut received = [0; 1600];
            let mut index = 0;
            measure("udp_transmit_drain", size, peers, 500, || {
                let (tx, rx) = &pairs[index];
                assert!(Sender::transmit_to(tx, 1, started, black_box(&command)).unwrap());
                let len = rx.recv(&mut received).unwrap();
                assert_eq!(len, 8 + command.len());
                assert_eq!(&received[8..len], command);
                assert_eq!(
                    u32::from_be_bytes(received[..4].try_into().unwrap()),
                    session::enet_checksum(&received[..len])
                );
                index = (index + 1) % peers;
            });
        }
    }
}
