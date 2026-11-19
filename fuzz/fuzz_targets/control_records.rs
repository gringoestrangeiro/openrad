#![no_main]

use libfuzzer_sys::fuzz_target;
use openrad::protocol::{self, Membership};

fuzz_target!(|input: &[u8]| {
    if input.len() > 65536 {
        return;
    }
    if let Ok(records) = protocol::records(input) {
        assert_eq!(
            records
                .iter()
                .map(|record| record.value.len() + 8)
                .sum::<usize>(),
            input.len()
        );
        for record in records {
            let _ = protocol::display_text(record.value);
        }
    }
    let _ = protocol::op(input);
    let mut membership = Membership::default();
    let _ = membership.snapshot(input);
    let _ = membership.changes(input);
    assert!(membership.peers.len() <= protocol::MAX_ROSTER_PEERS);
    assert!(membership.networks.len() <= protocol::MAX_ROSTER_NETWORKS);
});
