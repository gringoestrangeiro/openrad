//! Elevated installer worker using the SetupAPI sequence documented by tapctl.
//! Stages a signed TAP package, creates one device, and installs the best stored
//! driver on that device. Other VPN adapters and driver packages are retained.
use crate::{
    setup::{self, Adapter, AdapterState, Manifest, Selection},
    windows_io,
};
use anyhow::{ensure, Context, Result};
use std::{
    ffi::c_void,
    os::windows::{ffi::OsStrExt, process::CommandExt},
    path::Path,
    time::{Duration, Instant},
};
use windows_sys::{
    core::{w, GUID},
    Win32::{
        Devices::DeviceAndDriverInstallation::*,
        Foundation::{
            FreeLibrary, ERROR_FILE_NOT_FOUND, ERROR_NO_MORE_ITEMS, HMODULE, INVALID_HANDLE_VALUE,
        },
        NetworkManagement::{
            IpHelper::{ConvertInterfaceAliasToLuid, GetIfEntry2, MIB_IF_ROW2},
            Ndis::{NET_IF_ADMIN_STATUS_UP, NET_LUID_LH},
        },
        System::{
            LibraryLoader::{GetProcAddress, LoadLibraryExW, LOAD_LIBRARY_SEARCH_SYSTEM32},
            Registry::*,
            Threading::CREATE_NO_WINDOW,
        },
    },
};

const NET_CLASS: GUID = GUID {
    data1: 0x4d36e972,
    data2: 0xe325,
    data3: 0x11ce,
    data4: [0xbf, 0xc1, 0x08, 0x00, 0x2b, 0xe1, 0x03, 0x18],
};
const STATE: &str = "ADAPTER-STATE.json";

fn wide_path(path: &Path) -> Vec<u16> {
    path.as_os_str().encode_wide().chain(Some(0)).collect()
}
fn check_bool(success: i32) -> std::io::Result<()> {
    if success == 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}
struct DeviceSet(HDEVINFO);
impl DeviceSet {
    fn present() -> Result<Self> {
        let raw = unsafe {
            SetupDiGetClassDevsW(
                &NET_CLASS,
                std::ptr::null(),
                std::ptr::null_mut(),
                DIGCF_PRESENT,
            )
        };
        ensure!(
            raw != INVALID_HANDLE_VALUE as isize,
            "Cannot enumerate Windows network devices: {}",
            std::io::Error::last_os_error()
        );
        Ok(Self(raw))
    }
    fn empty() -> Result<Self> {
        let raw = unsafe { SetupDiCreateDeviceInfoList(&NET_CLASS, std::ptr::null_mut()) };
        ensure!(
            raw != INVALID_HANDLE_VALUE as isize,
            "Cannot create a network device set: {}",
            std::io::Error::last_os_error()
        );
        Ok(Self(raw))
    }
}
impl Drop for DeviceSet {
    fn drop(&mut self) {
        unsafe {
            SetupDiDestroyDeviceInfoList(self.0);
        }
    }
}
struct Key(HKEY);
impl Drop for Key {
    fn drop(&mut self) {
        unsafe {
            RegCloseKey(self.0);
        }
    }
}
fn string(key: HKEY, name: &str) -> Result<Option<String>> {
    let mut data = [0u16; 2048];
    let mut size = std::mem::size_of_val(&data) as u32;
    let name = windows_io::wide(name);
    let code = unsafe {
        RegGetValueW(
            key,
            std::ptr::null(),
            name.as_ptr(),
            RRF_RT_REG_SZ,
            std::ptr::null_mut(),
            data.as_mut_ptr().cast(),
            &mut size,
        )
    };
    if code == ERROR_FILE_NOT_FOUND {
        return Ok(None);
    }
    windows_io::status(code)?;
    let end = data
        .iter()
        .position(|c| *c == 0)
        .context("Invalid network device registry string")?;
    Ok(Some(String::from_utf16(&data[..end])?))
}
fn device_strings(
    set: &DeviceSet,
    device: &SP_DEVINFO_DATA,
) -> Result<Option<(String, String, String)>> {
    let raw = unsafe {
        SetupDiOpenDevRegKey(
            set.0,
            device,
            DICS_FLAG_GLOBAL,
            0,
            DIREG_DRV,
            KEY_QUERY_VALUE,
        )
    };
    if raw == INVALID_HANDLE_VALUE {
        return Ok(None);
    }
    let key = Key(raw);
    let Some(guid) = string(key.0, "NetCfgInstanceId")? else {
        return Ok(None);
    };
    let component = string(key.0, "ComponentId")?.unwrap_or_default();
    let version = string(key.0, "DriverVersion")?.unwrap_or_default();
    Ok(Some((guid, component, version)))
}
fn data() -> SP_DEVINFO_DATA {
    SP_DEVINFO_DATA {
        cbSize: std::mem::size_of::<SP_DEVINFO_DATA>() as u32,
        ..Default::default()
    }
}
fn connection_name(guid: &str) -> Result<String> {
    let path = windows_io::wide(&format!(
        r"SYSTEM\CurrentControlSet\Control\Network\{{4D36E972-E325-11CE-BFC1-08002BE10318}}\{guid}\Connection"
    ));
    let mut raw = std::ptr::null_mut();
    windows_io::status(unsafe {
        RegOpenKeyExW(HKEY_LOCAL_MACHINE, path.as_ptr(), 0, KEY_READ, &mut raw)
    })?;
    let key = Key(raw);
    string(key.0, "Name")?.context("Network adapter has no connection name")
}
fn enabled(name: &str) -> bool {
    let name = windows_io::wide(name);
    let mut luid = NET_LUID_LH::default();
    if unsafe { ConvertInterfaceAliasToLuid(name.as_ptr(), &mut luid) } != 0 {
        return false;
    }
    let mut row = MIB_IF_ROW2 {
        InterfaceLuid: luid,
        ..Default::default()
    };
    unsafe { GetIfEntry2(&mut row) == 0 && row.AdminStatus == NET_IF_ADMIN_STATUS_UP }
}
fn inventory() -> Result<Vec<Adapter>> {
    let set = DeviceSet::present()?;
    let mut adapters = Vec::new();
    for index in 0..65536 {
        let mut device = data();
        if unsafe { SetupDiEnumDeviceInfo(set.0, index, &mut device) } == 0 {
            let error = std::io::Error::last_os_error();
            if error.raw_os_error() == Some(ERROR_NO_MORE_ITEMS as i32) {
                break;
            }
            return Err(error.into());
        }
        let Some((guid, component_id, version)) = device_strings(&set, &device)? else {
            continue;
        };
        let name = match connection_name(&guid) {
            Ok(name) => name,
            Err(_) => continue,
        };
        let mut driver_version = [0u16; 4];
        for (target, value) in driver_version.iter_mut().zip(version.split('.')) {
            *target = value.parse().unwrap_or(0);
        }
        adapters.push(Adapter {
            enabled: enabled(&name),
            guid,
            name,
            component_id,
            driver_version,
        });
    }
    Ok(adapters)
}
/// Runtime discovery uses SetupAPI device keys, never enumerates protected
/// Control\Class registry siblings belonging to unrelated network components.
pub fn runtime_adapter_guid(name: &str) -> Result<String> {
    let mut matches = inventory()?
        .into_iter()
        .filter(|adapter| {
            adapter.component_id.eq_ignore_ascii_case("tap0901")
                && adapter.name.eq_ignore_ascii_case(name)
        })
        .collect::<Vec<_>>();
    ensure!(matches.len() == 1,
        "Install official TAP-Windows6 and name one dedicated TAP-Windows Adapter V9 'OpenRad'; see docs/windows.md (found {} matching adapters)", matches.len());
    Ok(matches.remove(0).guid)
}

fn state(directory: &Path) -> Result<Option<AdapterState>> {
    crate::file_io::read_optional_bounded(&directory.join(STATE), 4096)?
        .map(|data| serde_json::from_slice(&data).map_err(Into::into))
        .transpose()
}
fn find_device(guid: &str) -> Result<Option<(DeviceSet, SP_DEVINFO_DATA)>> {
    let set = DeviceSet::present()?;
    for index in 0..65536 {
        let mut device = data();
        if unsafe { SetupDiEnumDeviceInfo(set.0, index, &mut device) } == 0 {
            let error = std::io::Error::last_os_error();
            if error.raw_os_error() == Some(ERROR_NO_MORE_ITEMS as i32) {
                return Ok(None);
            }
            return Err(error.into());
        }
        if let Some((candidate, component, _)) = device_strings(&set, &device)? {
            if candidate.eq_ignore_ascii_case(guid) {
                ensure!(
                    component.eq_ignore_ascii_case("tap0901"),
                    "The recorded adapter is not TAP-Windows6"
                );
                return Ok(Some((set, device)));
            }
        }
    }
    anyhow::bail!("Network device enumeration exceeded its bound")
}
fn remove_device(set: &DeviceSet, device: &SP_DEVINFO_DATA) -> Result<bool> {
    let params = SP_REMOVEDEVICE_PARAMS {
        ClassInstallHeader: SP_CLASSINSTALL_HEADER {
            cbSize: std::mem::size_of::<SP_CLASSINSTALL_HEADER>() as u32,
            InstallFunction: DIF_REMOVE,
        },
        Scope: DI_REMOVEDEVICE_GLOBAL,
        HwProfile: 0,
    };
    check_bool(unsafe {
        SetupDiSetClassInstallParamsW(
            set.0,
            device,
            &params.ClassInstallHeader,
            std::mem::size_of_val(&params) as u32,
        )
    })?;
    check_bool(unsafe { SetupDiCallClassInstaller(DIF_REMOVE, set.0, device) })?;
    let mut install = SP_DEVINSTALL_PARAMS_W {
        cbSize: std::mem::size_of::<SP_DEVINSTALL_PARAMS_W>() as u32,
        ..Default::default()
    };
    check_bool(unsafe { SetupDiGetDeviceInstallParamsW(set.0, device, &mut install) })?;
    Ok(install.Flags & (DI_NEEDREBOOT | DI_NEEDRESTART) != 0)
}
struct NewDevice {
    set: DeviceSet,
    device: SP_DEVINFO_DATA,
    registered: bool,
    keep: bool,
}
impl Drop for NewDevice {
    fn drop(&mut self) {
        if self.registered && !self.keep {
            let _ = remove_device(&self.set, &self.device);
        }
    }
}
struct Module(HMODULE);
impl Drop for Module {
    fn drop(&mut self) {
        unsafe {
            FreeLibrary(self.0);
        }
    }
}
fn install_device(set: &DeviceSet, device: &SP_DEVINFO_DATA) -> Result<bool> {
    // tapctl uses DiInstallDevice on precisely this devnode, selecting the best
    // driver already staged in the store. Do not globally update other adapters.
    let raw = unsafe {
        LoadLibraryExW(
            w!("newdev.dll"),
            std::ptr::null_mut(),
            LOAD_LIBRARY_SEARCH_SYSTEM32,
        )
    };
    ensure!(
        !raw.is_null(),
        "Cannot load Windows device installation APIs"
    );
    let library = Module(raw);
    let address = unsafe { GetProcAddress(library.0, c"DiInstallDevice".as_ptr().cast()) }
        .context("Windows DiInstallDevice is unavailable")?;
    type Install = unsafe extern "system" fn(
        *mut c_void,
        HDEVINFO,
        *const SP_DEVINFO_DATA,
        *const SP_DRVINFO_DATA_V2_W,
        u32,
        *mut i32,
    ) -> i32;
    // SAFETY: documented newdev.dll ABI; module and all inputs outlive the call.
    let install: Install = unsafe { std::mem::transmute(address) };
    let mut reboot = 0;
    check_bool(unsafe {
        install(
            std::ptr::null_mut(),
            set.0,
            device,
            std::ptr::null(),
            0,
            &mut reboot,
        )
    })
    .context("Windows could not install the signed TAP-Windows6 driver on the OpenRad adapter")?;
    Ok(reboot != 0)
}
fn create_device() -> Result<NewDevice> {
    let set = DeviceSet::empty()?;
    let mut class = [0u16; 256];
    check_bool(unsafe {
        SetupDiClassNameFromGuidW(
            &NET_CLASS,
            class.as_mut_ptr(),
            class.len() as u32,
            std::ptr::null_mut(),
        )
    })?;
    let mut device = data();
    check_bool(unsafe {
        SetupDiCreateDeviceInfoW(
            set.0,
            class.as_ptr(),
            &NET_CLASS,
            w!("OpenRad TAP-Windows6"),
            std::ptr::null_mut(),
            DICD_GENERATE_ID,
            &mut device,
        )
    })?;
    let mut new = NewDevice {
        set,
        device,
        registered: false,
        keep: false,
    };
    check_bool(unsafe { SetupDiSetSelectedDevice(new.set.0, &new.device) })?;
    let hardware = windows_io::wide("tap0901\0"); // REG_MULTI_SZ, double NUL.
    check_bool(unsafe {
        SetupDiSetDeviceRegistryPropertyW(
            new.set.0,
            &mut new.device,
            SPDRP_HARDWAREID,
            hardware.as_ptr().cast(),
            std::mem::size_of_val(hardware.as_slice()) as u32,
        )
    })?;
    check_bool(unsafe { SetupDiCallClassInstaller(DIF_REGISTERDEVICE, new.set.0, &new.device) })?;
    new.registered = true;
    Ok(new)
}
fn adapter_guid(set: &DeviceSet, device: &SP_DEVINFO_DATA) -> Result<String> {
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        if let Some((guid, component, _)) = device_strings(set, device)? {
            ensure!(
                component.eq_ignore_ascii_case("tap0901"),
                "Windows selected an unexpected driver for the OpenRad adapter"
            );
            return Ok(guid);
        }
        ensure!(Instant::now() < until, "Windows has not finished registering the TAP adapter. Restart Windows and run setup again");
        std::thread::sleep(Duration::from_millis(250));
    }
}
fn driver_key(guid: &str) -> Result<String> {
    let (set, device) = find_device(guid)?.context("The selected TAP adapter disappeared")?;
    let mut buffer = [0u16; 256];
    let mut kind = 0;
    let mut required = 0;
    // SPDRP_DRIVER returns this device's software-key identifier. Do not scan
    // Control\Class: sibling keys can require SYSTEM even for an administrator.
    check_bool(unsafe {
        SetupDiGetDeviceRegistryPropertyW(
            set.0,
            &device,
            SPDRP_DRIVER,
            &mut kind,
            buffer.as_mut_ptr().cast(),
            std::mem::size_of_val(&buffer) as u32,
            &mut required,
        )
    })
    .context("Cannot obtain the selected TAP adapter's driver key")?;
    ensure!(
        kind == REG_SZ && required <= std::mem::size_of_val(&buffer) as u32,
        "Invalid TAP driver key property"
    );
    let end = buffer
        .iter()
        .position(|c| *c == 0)
        .context("Invalid TAP driver key string")?;
    let key = String::from_utf16(&buffer[..end])?;
    setup::validate_driver_key(&key)?;
    Ok(key)
}
fn name_and_enable(guid: &str) -> Result<()> {
    // Execute the build's immutable script rather than a file that could be
    // replaced after manifest verification in a writable custom installation.
    let driver_key = driver_key(guid)?;
    let script = setup::adapter_setup_script(guid, &driver_key)?;
    let output = crate::windows_security::powershell_command(&script)?
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .context("Cannot run the Windows adapter setup command")?;
    ensure!(
        output.status.success(),
        "Windows could not name/enable the OpenRad adapter: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    Ok(())
}
pub fn check(directory: &Path, manifest_path: &Path) -> Result<i32> {
    let manifest = Manifest::read(manifest_path)?;
    if !manifest.matches(directory)? {
        return Ok(10);
    }
    let adapters = inventory()?;
    let state = state(directory)?;
    let selected = setup::select_adapter(&adapters, state.as_ref())?;
    Ok(if setup::ready(true, &adapters, &selected) {
        0
    } else {
        10
    })
}
pub fn prepare_radmin() -> Result<i32> {
    crate::windows_radmin::prepare_installation()?;
    Ok(0)
}

pub fn configure(directory: &Path) -> Result<i32> {
    let manifest = Manifest::read(&directory.join("INSTALL-MANIFEST.json"))?;
    ensure!(
        manifest.matches(directory)?,
        "An installed file failed its integrity check. Run setup again"
    );
    // Also cover direct worker invocation and a Radmin restart while the
    // installer was copying files. Never create/repair TAP before this succeeds.
    prepare_radmin()?;
    let adapters = inventory()?;
    let previous = state(directory)?;
    let selected = setup::select_adapter(&adapters, previous.as_ref())?;
    let mut new = None;
    let mut reboot = false;
    let (guid, created_by_setup) = match selected {
        Selection::Existing(index) => {
            let adapter = &adapters[index];
            if adapter.driver_version < setup::MIN_DRIVER_VERSION {
                let inf = wide_path(&directory.join("driver/OemVista.inf"));
                check_bool(unsafe {
                    SetupCopyOEMInfW(
                        inf.as_ptr(),
                        std::ptr::null(),
                        SPOST_PATH,
                        0,
                        std::ptr::null_mut(),
                        0,
                        std::ptr::null_mut(),
                        std::ptr::null_mut(),
                    )
                })
                .context("Windows rejected the bundled signed TAP-Windows6 package")?;
                let (set, device) = find_device(&adapter.guid)?
                    .context("The OpenRad adapter disappeared during setup")?;
                reboot |= install_device(&set, &device)?;
            }
            let owned = previous
                .as_ref()
                .is_some_and(|s| s.created_by_setup && s.guid.eq_ignore_ascii_case(&adapter.guid));
            (adapter.guid.clone(), owned)
        }
        Selection::Create => {
            let inf = wide_path(&directory.join("driver/OemVista.inf"));
            check_bool(unsafe {
                SetupCopyOEMInfW(
                    inf.as_ptr(),
                    std::ptr::null(),
                    SPOST_PATH,
                    0,
                    std::ptr::null_mut(),
                    0,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                )
            })
            .context("Windows rejected the bundled signed TAP-Windows6 package")?;
            let device = create_device()?;
            reboot |= install_device(&device.set, &device.device)?;
            let guid = adapter_guid(&device.set, &device.device)?;
            new = Some(device);
            (guid, true)
        }
    };
    name_and_enable(&guid)?;
    let state = AdapterState {
        guid,
        created_by_setup,
    };
    std::fs::write(directory.join(STATE), serde_json::to_vec_pretty(&state)?)?;
    if let Some(device) = &mut new {
        device.keep = true;
    }
    let adapters = inventory()?;
    let selected = setup::select_adapter(&adapters, Some(&state))?;
    ensure!(
        reboot || setup::ready(true, &adapters, &selected),
        "The TAP adapter is not ready yet. Restart Windows and run setup again"
    );
    Ok(if reboot { 3010 } else { 0 })
}
pub fn remove_adapter(directory: &Path) -> Result<i32> {
    let Some(state) = state(directory)?.filter(|s| s.created_by_setup) else {
        return Ok(0);
    };
    let Some((set, device)) = find_device(&state.guid)? else {
        return Ok(0);
    };
    // Exact recorded device only. Retain shared TAP driver packages and profiles.
    Ok(if remove_device(&set, &device)? {
        3010
    } else {
        0
    })
}
