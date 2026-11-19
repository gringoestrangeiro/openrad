#![no_main]

use libfuzzer_sys::fuzz_target;
use openrad::protocol::Identity;

fuzz_target!(|input: &[u8]| {
    let _ = Identity::from_secret(input);
});
