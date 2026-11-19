#![no_main]

use libfuzzer_sys::fuzz_target;
use openrad::crypto::sh_parse;

fuzz_target!(|input: &[u8]| {
    if input.len() <= 65536 {
        let _ = sh_parse(input, 1, &[0x20000000]);
        let _ = sh_parse(input, 2, &[0x20000000, 0x30000000]);
    }
});
