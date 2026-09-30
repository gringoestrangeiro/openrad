//! GetAdaptersAddresses inventory; kernel-owned pointers never escape the buffer.
use anyhow::{bail, ensure, Result};
use std::{
    ffi::CStr,
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
};
use windows_sys::Win32::{
    Foundation::{ERROR_BUFFER_OVERFLOW, ERROR_NO_DATA},
    NetworkManagement::IpHelper::*,
    Networking::WinSock::*,
};

pub fn inventory() -> Result<Vec<(Vec<u8>, IpAddr)>> {
    let mut size = 15 * 1024u32;
    for _ in 0..3 {
        ensure!(
            size <= 16 * 1024 * 1024,
            "local interface inventory is too large"
        );
        // u64 storage supplies the documented alignment for the address list.
        let mut buffer = vec![0u64; (size as usize).div_ceil(8)];
        let head = buffer.as_mut_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
        // SAFETY: live aligned writable buffer, pointers copied before release.
        let result = unsafe {
            GetAdaptersAddresses(
                AF_UNSPEC as u32,
                GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
                std::ptr::null(),
                head,
                &mut size,
            )
        };
        if result == ERROR_BUFFER_OVERFLOW {
            continue;
        }
        if result == ERROR_NO_DATA {
            return Ok(Vec::new());
        }
        crate::windows_io::status(result)?;
        let mut entries = Vec::new();
        let mut adapter = head;
        while !adapter.is_null() {
            // SAFETY: successful API result contains a linked list in buffer.
            unsafe {
                let current = &*adapter;
                let name = CStr::from_ptr(current.AdapterName.cast())
                    .to_bytes()
                    .to_vec();
                let mut address = current.FirstUnicastAddress;
                while !address.is_null() {
                    let socket = (*address).Address;
                    if !socket.lpSockaddr.is_null() {
                        let ip = match (*socket.lpSockaddr).sa_family {
                            AF_INET
                                if socket.iSockaddrLength as usize
                                    >= std::mem::size_of::<SOCKADDR_IN>() =>
                            {
                                Some(IpAddr::V4(Ipv4Addr::from(
                                    (*(socket.lpSockaddr.cast::<SOCKADDR_IN>()))
                                        .sin_addr
                                        .S_un
                                        .S_addr
                                        .to_ne_bytes(),
                                )))
                            }
                            AF_INET6
                                if socket.iSockaddrLength as usize
                                    >= std::mem::size_of::<SOCKADDR_IN6>() =>
                            {
                                Some(IpAddr::V6(Ipv6Addr::from(
                                    (*(socket.lpSockaddr.cast::<SOCKADDR_IN6>()))
                                        .sin6_addr
                                        .u
                                        .Byte,
                                )))
                            }
                            _ => None,
                        };
                        if let Some(ip) = ip {
                            entries.push((name.clone(), ip));
                        }
                    }
                    address = (*address).Next;
                }
                adapter = current.Next;
            }
        }
        return Ok(entries);
    }
    bail!("local interface inventory changed repeatedly; retry connection")
}
