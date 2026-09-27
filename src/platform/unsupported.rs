use anyhow::{bail, Result};
use std::net::Ipv4Addr;
pub const NAME: &str = "radminvpn0";
pub struct Tap;
impl Tap {
    pub fn create(_: Ipv4Addr, _: &[Ipv4Addr]) -> Result<Self> {
        bail!("The VPN data plane is implemented and tested on Linux only")
    }
    pub fn create_with_helper(_: Ipv4Addr, _: &[Ipv4Addr], _: &std::path::Path) -> Result<Self> {
        bail!("The VPN data plane is implemented and tested on Linux only")
    }
    pub fn create_lan_with_helper(_: Ipv4Addr, _: &std::path::Path) -> Result<Self> {
        bail!("The VPN data plane is implemented and tested on Linux only")
    }
    pub fn ready(&self, _: i32) -> Result<bool> {
        bail!("VPN interface unavailable on this platform")
    }
    pub fn receive(&mut self) -> Result<Vec<u8>> {
        bail!("VPN interface unavailable on this platform")
    }
    pub fn send(&mut self, _: &[u8]) -> Result<()> {
        bail!("VPN interface unavailable on this platform")
    }
}
pub fn helper(_: Ipv4Addr, _: u32, _: &[Ipv4Addr]) -> Result<()> {
    bail!("The TAP helper is Linux only")
}
pub fn helper_with_lan(_: Ipv4Addr, _: u32, _: &[Ipv4Addr], _: bool) -> Result<()> {
    bail!("The TAP helper is Linux only")
}
