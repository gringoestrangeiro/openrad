//! TAP-Windows6 Layer 2 backend. Requires a dedicated, enabled `tap0901`
//! adapter named OpenRad and an elevated process for IP Helper configuration.
//! See docs/windows.md for upstream ABI references and installation.
use super::windows_contract as contract;
use crate::{
    early_log::{self, TapStage},
    tunnel,
    windows_io::{self, Operation},
};
use anyhow::{bail, ensure, Context, Result};
use std::{net::Ipv4Addr, path::Path, sync::Arc};
use windows_sys::Win32::{
    Foundation::{ERROR_FILE_NOT_FOUND, ERROR_OBJECT_ALREADY_EXISTS, GENERIC_READ, GENERIC_WRITE},
    NetworkManagement::{
        IpHelper::*,
        Ndis::{NET_IF_ADMIN_STATUS_UP, NET_LUID_LH},
    },
    Networking::WinSock::{
        IpPrefixOriginManual, IpSuffixOriginManual, AF_INET, SOCKADDR_IN, SOCKADDR_INET,
    },
    Storage::FileSystem::{
        CreateFileW, FILE_ATTRIBUTE_SYSTEM, FILE_FLAG_OVERLAPPED, OPEN_EXISTING,
    },
};

pub const NAME: &str = "OpenRad";

fn adapter_guid() -> Result<String> {
    let guid = crate::windows_setup::runtime_adapter_guid(NAME)?;
    contract::device_path(&guid)?;
    Ok(guid)
}

fn sockaddr(ip: Ipv4Addr) -> SOCKADDR_INET {
    let mut address = SOCKADDR_IN {
        sin_family: AF_INET,
        ..Default::default()
    };
    address.sin_addr.S_un.S_addr = u32::from_ne_bytes(ip.octets());
    SOCKADDR_INET { Ipv4: address }
}

struct MibTable(*mut std::ffi::c_void);
impl Drop for MibTable {
    fn drop(&mut self) {
        unsafe {
            FreeMibTable(self.0);
        }
    }
}
fn check_addresses(luid: NET_LUID_LH) -> Result<()> {
    crate::windows_radmin::prepare_addresses(
        unsafe { luid.Value },
        interface_addresses,
        crate::windows_radmin::recover,
        || std::thread::sleep(std::time::Duration::from_millis(250)),
    )
}

fn interface_addresses() -> Result<Vec<crate::windows_radmin::Address>> {
    let mut table = std::ptr::null_mut();
    // SAFETY: OS allocates table; RAII guard frees after inspection.
    windows_io::status(unsafe { GetUnicastIpAddressTable(AF_INET, &mut table) })?;
    let _guard = MibTable(table.cast());
    let mut addresses = Vec::new();
    unsafe {
        let rows =
            std::slice::from_raw_parts((*table).Table.as_ptr(), (*table).NumEntries as usize);
        for row in rows {
            let ip = Ipv4Addr::from(row.Address.Ipv4.sin_addr.S_un.S_addr.to_ne_bytes());
            let mut link = MIB_IF_ROW2 {
                InterfaceLuid: row.InterfaceLuid,
                ..Default::default()
            };
            let status = GetIfEntry2(&mut link);
            // The adapter may disappear between the table snapshot and this
            // lookup, especially just after the SYSTEM worker disables it.
            if status == ERROR_FILE_NOT_FOUND {
                continue;
            }
            windows_io::status(status)?;
            let end = link
                .Description
                .iter()
                .position(|c| *c == 0)
                .context("Invalid network adapter description")?;
            addresses.push(crate::windows_radmin::Address {
                interface: row.InterfaceLuid.Value,
                index: link.InterfaceIndex,
                description: String::from_utf16(&link.Description[..end])?,
                enabled: link.AdminStatus == NET_IF_ADMIN_STATUS_UP,
                ip,
            });
        }
    }
    Ok(addresses)
}

pub struct Tap {
    read: Operation,
    write: Operation,
    control: Operation,
    adapter_mac: [u8; 6],
    wire_mac: [u8; 6],
    address: Option<MIB_UNICASTIPADDRESS_ROW>,
    routes: Vec<MIB_IPFORWARD_ROW2>,
    restored_routes: Vec<MIB_IPFORWARD_ROW2>,
    original_interface: Option<MIB_IPINTERFACE_ROW>,
}
impl Tap {
    pub fn create(vip: Ipv4Addr, peers: &[Ipv4Addr]) -> Result<Self> {
        Self::create_configured(vip, peers, false)
    }
    pub fn create_with_helper(vip: Ipv4Addr, peers: &[Ipv4Addr], _: &Path) -> Result<Self> {
        Self::create(vip, peers)
    }
    pub fn create_lan_with_helper(vip: Ipv4Addr, _: &Path) -> Result<Self> {
        Self::create_configured(vip, &[], true)
    }
    fn create_configured(vip: Ipv4Addr, peers: &[Ipv4Addr], lan: bool) -> Result<Self> {
        let result = Self::configure(vip, peers, lan);
        if let Err(error) = &result {
            early_log::fatal_error(error);
        }
        result
    }
    fn configure(vip: Ipv4Addr, peers: &[Ipv4Addr], lan: bool) -> Result<Self> {
        contract::validate_parameters(vip, peers)?;
        early_log::tap_step(TapStage::DiscoverAdapter);
        let guid = adapter_guid()?;
        early_log::tap_step(TapStage::ConvertInterfaceAliasToLuid);
        early_log::tap_adapter(&guid);
        let alias = windows_io::wide(NAME);
        let mut luid = NET_LUID_LH::default();
        // SAFETY: terminated alias and writable output.
        windows_io::status(unsafe { ConvertInterfaceAliasToLuid(alias.as_ptr(), &mut luid) })?;
        let mut link = MIB_IF_ROW2 {
            InterfaceLuid: luid,
            ..Default::default()
        };
        early_log::tap_step(TapStage::GetIfEntry2);
        windows_io::status(unsafe { GetIfEntry2(&mut link) })?;
        ensure!(
            link.AdminStatus == NET_IF_ADMIN_STATUS_UP,
            "Enable the OpenRad TAP-Windows6 adapter before connecting"
        );
        early_log::tap_step(TapStage::CheckAddresses);
        check_addresses(luid)?;
        early_log::tap_step(TapStage::CreateFileW);
        let path = windows_io::wide(&contract::device_path(&guid)?);
        // SAFETY: exclusive, non-inheritable overlapped handle, owned immediately.
        let handle = Arc::new(unsafe { windows_io::owned(CreateFileW(path.as_ptr(), GENERIC_READ | GENERIC_WRITE, 0, std::ptr::null(), OPEN_EXISTING, FILE_ATTRIBUTE_SYSTEM | FILE_FLAG_OVERLAPPED, std::ptr::null_mut())) }
            .context("Cannot open the OpenRad TAP-Windows6 adapter; close other users of this adapter and run OpenRad as administrator")?);
        let mut tap = Self {
            read: Operation::new(handle.clone(), 65536)?,
            write: Operation::new(handle.clone(), tunnel::MAX_FRAME)?,
            control: Operation::new(handle, 16)?,
            adapter_mac: [0; 6],
            wire_mac: tunnel::mac(vip),
            address: None,
            routes: Vec::new(),
            restored_routes: Vec::new(),
            original_interface: None,
        };
        early_log::tap_step(TapStage::GetVersion);
        tap.control.ioctl(contract::GET_VERSION, &[])?;
        ensure!(
            tap.control.finish(1000)? == 12,
            "invalid TAP-Windows6 version response"
        );
        let version = tap.control.data(12);
        let major = u32::from_ne_bytes(version[..4].try_into()?);
        let minor = u32::from_ne_bytes(version[4..8].try_into()?);
        ensure!(
            major == 9 && minor >= 21,
            "TAP-Windows6 9.21 or newer is required"
        );
        early_log::tap_step(TapStage::GetMac);
        tap.control.ioctl(contract::GET_MAC, &[])?;
        ensure!(
            tap.control.finish(1000)? == 6,
            "invalid TAP-Windows6 MAC response"
        );
        tap.adapter_mac.copy_from_slice(tap.control.data(6));
        early_log::tap_mac(tap.adapter_mac);
        ensure!(
            tap.adapter_mac != [0; 6] && tap.adapter_mac[0] & 1 == 0,
            "invalid TAP-Windows6 adapter MAC"
        );
        early_log::tap_step(TapStage::GetMtu);
        tap.control.ioctl(contract::GET_MTU, &[])?;
        ensure!(
            tap.control.finish(1000)? == 4
                && u32::from_ne_bytes(tap.control.data(4).try_into()?) >= 1500,
            "TAP-Windows6 driver MTU must be at least 1500"
        );
        early_log::tap_step(TapStage::SetMediaStatus);
        tap.media(true)?;
        let mut interface = MIB_IPINTERFACE_ROW {
            Family: AF_INET,
            InterfaceLuid: luid,
            ..Default::default()
        };
        early_log::tap_step(TapStage::GetIpInterfaceEntry);
        windows_io::status(unsafe { GetIpInterfaceEntry(&mut interface) })?;
        let original = interface;
        interface.NlMtu = 1500;
        interface.UseAutomaticMetric = false;
        // Windows installs group routes on every adapter. Prefer the VPN for
        // unbound LAN discovery without changing physical-interface metrics.
        interface.Metric = 5;
        interface.SitePrefixLength = 0; // Required by SetIpInterfaceEntry for IPv4.
        early_log::tap_step(TapStage::SetIpInterfaceEntry);
        windows_io::status(unsafe { SetIpInterfaceEntry(&mut interface) })
            .context("Cannot configure the OpenRad IPv4 interface; run OpenRad as administrator")?;
        tap.original_interface = Some(original);
        let mut address = MIB_UNICASTIPADDRESS_ROW::default();
        unsafe {
            InitializeUnicastIpAddressEntry(&mut address);
        }
        address.InterfaceLuid = luid;
        address.Address = sockaddr(vip);
        address.OnLinkPrefixLength = if lan { 8 } else { 32 };
        address.PrefixOrigin = IpPrefixOriginManual;
        address.SuffixOrigin = IpSuffixOriginManual;
        address.SkipAsSource = false;
        early_log::tap_step(TapStage::CreateUnicastIpAddressEntry);
        windows_io::status(unsafe { CreateUnicastIpAddressEntry(&address) })
            .context("Cannot assign the OpenRad VPN address; run OpenRad as administrator")?;
        tap.address = Some(address);
        early_log::tap_step(TapStage::ConfigureRoutes);
        for (destination, prefix) in contract::routes(lan, peers) {
            let mut route = MIB_IPFORWARD_ROW2::default();
            unsafe {
                InitializeIpForwardEntry(&mut route);
            }
            route.InterfaceLuid = luid;
            route.DestinationPrefix.Prefix = sockaddr(destination);
            route.DestinationPrefix.PrefixLength = prefix;
            route.NextHop = sockaddr(Ipv4Addr::UNSPECIFIED);
            route.Metric = 5;
            let code = unsafe { CreateIpForwardEntry2(&route) };
            // Windows normally already owns group routes on this adapter.
            // Save and restore their metrics; never delete an existing row.
            if code == ERROR_OBJECT_ALREADY_EXISTS {
                windows_io::status(unsafe { GetIpForwardEntry2(&mut route) })?;
                let original = route;
                route.Metric = 5;
                windows_io::status(unsafe { SetIpForwardEntry2(&route) })?;
                tap.restored_routes.push(original);
                continue;
            }
            windows_io::status(code).context("Cannot add an OpenRad interface route")?;
            tap.routes.push(route);
        }
        // Leave TAP mode enabled: never call CONFIG_TUN or DHCP masquerading.
        // A pending read also captures ARP/DAD traffic emitted during setup.
        early_log::tap_step(TapStage::StartRead);
        tap.read.read()?;
        early_log::tap_step(TapStage::Ready);
        Ok(tap)
    }
    fn media(&mut self, connected: bool) -> Result<()> {
        self.control.ioctl(
            contract::SET_MEDIA_STATUS,
            &u32::from(connected).to_ne_bytes(),
        )?;
        self.control.finish(1000)?;
        Ok(())
    }
    pub fn ready(&self, timeout: i32) -> Result<bool> {
        Ok(self.read.ready(if timeout < 0 {
            u32::MAX
        } else {
            timeout as u32
        })?)
    }
    pub(crate) fn poll_fd(&self) -> Option<crate::wake::WaitSource> {
        Some(self.read.event_handle())
    }
    pub fn receive(&mut self) -> Result<Vec<u8>> {
        if !self.ready(0)? {
            return Err(std::io::Error::from(std::io::ErrorKind::WouldBlock).into());
        }
        let len = self.read.finish(0)?;
        ensure!(
            (14..=tunnel::MAX_FRAME).contains(&len),
            "invalid TAP-Windows6 Ethernet frame length"
        );
        let mut frame = self.read.data(len).to_vec();
        self.read.read()?;
        contract::translate_mac(&mut frame, self.adapter_mac, self.wire_mac)?;
        Ok(frame)
    }
    pub fn send(&mut self, frame: &[u8]) -> Result<()> {
        let mut local = frame.to_vec();
        contract::translate_mac(&mut local, self.wire_mac, self.adapter_mac)?;
        self.write.write(&local)?;
        ensure!(
            self.write.finish(1000)? == local.len(),
            "short TAP-Windows6 packet write"
        );
        Ok(())
    }
}
impl Drop for Tap {
    fn drop(&mut self) {
        self.read.cancel();
        self.write.cancel();
        // ActiveStore entries disappear at reboot; clean up exactly the rows
        // we created on disconnect and on every partial-setup failure.
        for route in self.routes.iter().rev() {
            unsafe {
                DeleteIpForwardEntry2(route);
            }
        }
        for route in self.restored_routes.iter().rev() {
            unsafe {
                SetIpForwardEntry2(route);
            }
        }
        if let Some(address) = &self.address {
            unsafe {
                DeleteUnicastIpAddressEntry(address);
            }
        }
        if let Some(mut interface) = self.original_interface.take() {
            interface.SitePrefixLength = 0;
            unsafe {
                SetIpInterfaceEntry(&mut interface);
            }
        }
        let _ = self.media(false);
    }
}
pub fn helper(_: Ipv4Addr, _: u32, _: &[Ipv4Addr]) -> Result<()> {
    bail!("Windows configures TAP-Windows6 in the elevated application; the descriptor helper is Linux only")
}
pub fn helper_with_lan(vip: Ipv4Addr, owner: u32, peers: &[Ipv4Addr], _: bool) -> Result<()> {
    helper(vip, owner, peers)
}
