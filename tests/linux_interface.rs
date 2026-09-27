#![cfg(target_os = "linux")]
use openrad::tap::Tap;
use serde_json::Value;
use std::{net::Ipv4Addr, path::Path, process::Command};

fn ip(arguments: &[&str]) -> Value {
    let result = Command::new("/usr/bin/ip")
        .args(arguments)
        .output()
        .unwrap();
    assert!(result.status.success());
    serde_json::from_slice(&result.stdout).unwrap()
}

#[test]
#[ignore = "requires sudo -n and no existing radminvpn0; no network traffic is sent"]
fn linux_desktop_lan_routes_and_descriptor_cleanup() {
    let interface = Path::new("/sys/class/net/radminvpn0");
    assert!(
        !interface.exists(),
        "disconnect the GUI first; existing TAP is preserved"
    );
    let helper = Path::new(env!("CARGO_BIN_EXE_openrad"));
    let tap = Tap::create_lan_with_helper(Ipv4Addr::new(26, 0, 0, 1), helper).unwrap();
    let address = ip(&["-j", "address", "show", "dev", "radminvpn0"]);
    let inet = address[0]["addr_info"]
        .as_array()
        .unwrap()
        .iter()
        .find(|v| v["family"] == "inet")
        .unwrap();
    assert_eq!(inet["prefixlen"], 8);
    assert_eq!(inet["broadcast"], "26.255.255.255");
    let index = std::fs::read_to_string(interface.join("ifindex")).unwrap();
    assert!(Tap::create_lan_with_helper(Ipv4Addr::new(26, 0, 0, 2), helper).is_err());
    assert_eq!(
        std::fs::read_to_string(interface.join("ifindex")).unwrap(),
        index
    );
    for destination in [
        "26.0.0.3",
        "26.255.255.255",
        "255.255.255.255",
        "239.255.42.42",
    ] {
        let route = ip(&["-j", "route", "get", destination]);
        assert_eq!(route[0]["dev"], "radminvpn0");
    }
    drop(tap);
    assert!(
        !interface.exists(),
        "last descriptor close must remove TAP and routes"
    );
    let routes = ip(&["-j", "route", "show", "table", "all"]);
    assert!(routes
        .as_array()
        .unwrap()
        .iter()
        .all(|r| r["dev"] != "radminvpn0"));
}
