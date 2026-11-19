#![no_main]

use libfuzzer_sys::fuzz_target;
use openrad::tunnel::{self, Packet};
use std::net::Ipv4Addr;

fuzz_target!(|input: &[u8]| {
    let _ = tunnel::endpoints(input);
    if let Ok(Packet::Frames(frames)) = tunnel::decode(input) {
        for frame in frames {
            assert!((14..=65535).contains(&frame.len()));
            let _ = tunnel::endpoints(frame);
        }
    }
    let source = Ipv4Addr::new(26, 0, 0, 2);
    let _ = tunnel::forwarding(input, source, tunnel::mac(source));
});
