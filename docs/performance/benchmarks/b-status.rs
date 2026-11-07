use anyhow::Result;
use openrad::{daemon::{DataDir, ServicePreferences}, protocol::{Identity, Peer, Network}, runtime::{self, Command, Snapshot, Update}};
use serde::{Serialize, Deserialize};
use serde_json::{json, Value};
use std::{alloc::{GlobalAlloc, Layout, System}, collections::BTreeMap, hint::black_box, sync::{atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering}, mpsc::{Sender, SyncSender}, Arc, Mutex, MutexGuard, PoisonError}, time::Instant};
use windows_sys::Win32::{Foundation::{FILETIME, HANDLE}, System::Threading::{GetCurrentProcess, GetProcessTimes}};

// STATUS_SOURCE

struct Allocator;
static TRACK: AtomicBool = AtomicBool::new(false);
static ALLOCS: AtomicUsize = AtomicUsize::new(0);
static BYTES: AtomicUsize = AtomicUsize::new(0);
static LIVE: AtomicUsize = AtomicUsize::new(0);
static PEAK: AtomicUsize = AtomicUsize::new(0);
unsafe impl GlobalAlloc for Allocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let ptr = System.alloc(layout);
        if TRACK.load(Ordering::Relaxed) && !ptr.is_null() {
            ALLOCS.fetch_add(1, Ordering::Relaxed);
            BYTES.fetch_add(layout.size(), Ordering::Relaxed);
            let live = LIVE.fetch_add(layout.size(), Ordering::Relaxed) + layout.size();
            PEAK.fetch_max(live, Ordering::Relaxed);
        }
        ptr
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        if TRACK.load(Ordering::Relaxed) {
            LIVE.fetch_sub(layout.size(), Ordering::Relaxed);
        }
        System.dealloc(ptr, layout)
    }
    unsafe fn realloc(&self, ptr: *mut u8, old: Layout, size: usize) -> *mut u8 {
        let new = System.realloc(ptr, old, size);
        if TRACK.load(Ordering::Relaxed) && !new.is_null() {
            ALLOCS.fetch_add(1, Ordering::Relaxed);
            BYTES.fetch_add(size, Ordering::Relaxed);
            let live = LIVE.fetch_add(size, Ordering::Relaxed) + size - old.size();
            LIVE.fetch_sub(old.size(), Ordering::Relaxed);
            PEAK.fetch_max(live, Ordering::Relaxed);
        }
        new
    }
}
#[global_allocator] static ALLOCATOR: Allocator = Allocator;
fn cpu_ns() -> u64 {
    unsafe {
        let mut creation: FILETIME = std::mem::zeroed();
        let mut exit: FILETIME = std::mem::zeroed();
        let mut kernel: FILETIME = std::mem::zeroed();
        let mut user: FILETIME = std::mem::zeroed();
        assert_ne!(GetProcessTimes(GetCurrentProcess(), &mut creation, &mut exit, &mut kernel, &mut user), 0);
        (((kernel.dwHighDateTime as u64) << 32 | kernel.dwLowDateTime as u64) + ((user.dwHighDateTime as u64) << 32 | user.dwLowDateTime as u64)) * 100
    }
}
#[repr(C)] struct Counters { cb:u32, page_faults:u32, peak_working:usize, working:usize, peak_paged:usize, paged:usize, peak_nonpaged:usize, nonpaged:usize, pagefile:usize, peak_pagefile:usize, private:usize }
#[link(name="psapi")] unsafe extern "system" { fn GetProcessMemoryInfo(process:HANDLE, counters:*mut Counters, cb:u32)->i32; }
fn memory() -> (usize, usize) { unsafe { let mut counters:Counters = std::mem::zeroed(); counters.cb=std::mem::size_of::<Counters>() as u32; assert_ne!(GetProcessMemoryInfo(GetCurrentProcess(), &mut counters,counters.cb),0); (counters.working,counters.peak_working) } }
fn snapshot(peers:usize) -> Snapshot {
    let mut result = Snapshot { networks:vec![Network{name:"Synthetic LAN".into(),network_id:"synthetic-lan".into()}], roles:BTreeMap::from([("synthetic-lan".into(),BTreeMap::from([(123,2)]))]), ..Default::default() };
    for index in 0..peers {
        let rid = index as u64 + 1000;
        result.peers.insert(rid,runtime::PeerView {peer:Peer {rid,name:format!("Synthetic peer {index:05} 漢字"),vip:std::net::Ipv4Addr::from(0x1a000001 + index as u32),server:Some("192.0.2.1:1234".parse().unwrap()),state:1,network_ids:["synthetic-lan".into()].into_iter().collect()},status:runtime::PeerState::Connected,detail:"Synthetic connected direct channel".into(),transport:Some(openrad::peer::TransportPath::DirectUdp),endpoint:Some("192.0.2.2:1234".parse().unwrap())});
    }
    result
}
fn main() {
    let arguments:Vec<_> = std::env::args().collect();
    let iterations:usize = arguments.get(1).map(|value|value.parse().unwrap()).unwrap_or(300);
    let mode = arguments.get(2).map(String::as_str).unwrap_or("stable");
    // Percentiles index the samples; an unknown mode would be reported under its own label.
    assert!(iterations > 0, "iterations must be at least 1");
    assert!(matches!(mode, "stable" | "updated" | "clone"), "mode must be stable, updated or clone");
    for count in [0,10,100,1000,5000,10000] {
        let mut identity=Identity::bootstrap("Synthetic local","127.0.0.1").unwrap();
        identity.rid=123;
        let shared=Shared::new(&identity,true);
        let template=snapshot(count);
        { let mut state=shared.lock_state(); state.phase="connected"; state.snapshot=Some(Arc::new(template.clone())); }
        assert_eq!(serde_json::from_slice::<Value>(&shared.status_payload().unwrap()).unwrap(),serde_json::to_value(shared.status()).unwrap());
        let mut bytes = 0;
        let mut operation = || {
            if mode == "clone" { black_box(template.clone()); return; }
            if mode == "updated" {
                let mut snapshot = template.clone();
                snapshot.elapsed_secs=42;
                snapshot.traffic.sent_bytes=4321;
                shared.lock_state().snapshot=Some(Arc::new(snapshot));
            }
            let payload=shared.status_payload().unwrap();
            bytes=payload.len();
            black_box(payload);
        };
        for _ in 0..10 { operation(); }
        // Count temporary allocations separately: updated includes replacement
        // snapshots, and keeps one equal-sized snapshot alive between calls.
        for counter in [&ALLOCS,&BYTES,&LIVE,&PEAK] { counter.store(0,Ordering::Relaxed); }
        TRACK.store(true,Ordering::Relaxed);
        for _ in 0..10 { operation(); }
        TRACK.store(false,Ordering::Relaxed);
        let allocs=ALLOCS.load(Ordering::Relaxed) as f64/10.0;
        let alloc_bytes=BYTES.load(Ordering::Relaxed) as f64/10.0;
        let peak=PEAK.load(Ordering::Relaxed);
        let mut samples=Vec::with_capacity(iterations);
        let cpu=cpu_ns(); let start=Instant::now();
        for _ in 0..iterations { let tick=Instant::now(); operation(); samples.push(tick.elapsed().as_nanos() as u64); }
        let elapsed=start.elapsed().as_nanos() as u64; let cpu=cpu_ns()-cpu;
        samples.sort_unstable();
        let (rss,peak_rss)=memory();
        println!("{{\"case\":\"{mode}\",\"peers\":{count},\"iterations\":{iterations},\"p50_ns\":{},\"p95_ns\":{},\"p99_ns\":{},\"elapsed_ns\":{elapsed},\"cpu_ns\":{cpu},\"payload_bytes\":{bytes},\"allocations_op\":{allocs},\"allocated_bytes_op\":{alloc_bytes},\"peak_temp_bytes\":{peak},\"rss_bytes\":{rss},\"peak_rss_bytes\":{peak_rss}}}",samples[iterations/2],samples[iterations*95/100],samples[iterations*99/100]);
    }
}
