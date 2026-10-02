//! Current-user-only ACLs for profile directories and local control pipes.
use crate::windows_io;
use std::{
    ffi::OsString,
    io,
    os::windows::{
        ffi::{OsStrExt, OsStringExt},
        io::AsRawHandle,
    },
    path::{Path, PathBuf},
    process::Command,
};
use windows_sys::Win32::{
    Foundation::LocalFree,
    Security::{
        Authorization::{
            ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
            SetNamedSecurityInfoW, SE_FILE_OBJECT,
        },
        GetSecurityDescriptorDacl, GetTokenInformation, TokenElevation, TokenUser,
        DACL_SECURITY_INFORMATION, PROTECTED_DACL_SECURITY_INFORMATION, SECURITY_ATTRIBUTES,
        TOKEN_ELEVATION, TOKEN_QUERY, TOKEN_USER,
    },
    System::{
        SystemInformation::GetSystemDirectoryW,
        Threading::{GetCurrentProcess, OpenProcessToken},
    },
};

/// Resolve trusted OS tools without consulting PATH, SystemRoot or the working
/// directory, all of which an unelevated launcher can influence.
pub fn system_directory() -> io::Result<PathBuf> {
    let mut directory = [0u16; 32768];
    // SAFETY: GetSystemDirectoryW writes at most the supplied buffer length.
    let length = unsafe { GetSystemDirectoryW(directory.as_mut_ptr(), directory.len() as u32) };
    if length == 0 {
        return Err(io::Error::last_os_error());
    }
    if length as usize >= directory.len() {
        return Err(io::ErrorKind::InvalidData.into());
    }
    Ok(PathBuf::from(OsString::from_wide(
        &directory[..length as usize],
    )))
}

/// PowerShell cmdlets must also come from protected system modules, not a
/// caller-supplied PSModulePath or modules in the current user's directory.
pub(crate) fn powershell_command(script: &str) -> io::Result<Command> {
    use base64::Engine;
    let directory = system_directory()?;
    let powershell = directory.join("WindowsPowerShell/v1.0");
    // Windows PowerShell can rebuild PSModulePath at startup. Reset it inside
    // the command too, before invoking any autoloaded cmdlet or module.
    let script = format!("$env:PSModulePath = [IO.Path]::Combine([Environment]::SystemDirectory, 'WindowsPowerShell\\v1.0\\Modules');\n{script}");
    let encoded = base64::engine::general_purpose::STANDARD.encode(
        script
            .encode_utf16()
            .flat_map(u16::to_le_bytes)
            .collect::<Vec<_>>(),
    );
    let mut command = Command::new(powershell.join("powershell.exe"));
    command
        .current_dir(directory)
        .env("PSModulePath", powershell.join("Modules"))
        .args([
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-EncodedCommand",
        ])
        .arg(encoded);
    Ok(command)
}

struct LocalAllocation(*mut std::ffi::c_void);
impl Drop for LocalAllocation {
    fn drop(&mut self) {
        unsafe {
            LocalFree(self.0);
        }
    }
}
pub struct Security {
    descriptor: LocalAllocation,
}
pub fn token_is_elevated() -> io::Result<bool> {
    let mut raw = std::ptr::null_mut();
    // SAFETY: borrowed process pseudo-handle and immediately owned token.
    if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut raw) } == 0 {
        return Err(io::Error::last_os_error());
    }
    let token = unsafe { windows_io::owned(raw) }?;
    let mut elevation = TOKEN_ELEVATION::default();
    let mut size = 0;
    if unsafe {
        GetTokenInformation(
            token.as_raw_handle(),
            TokenElevation,
            (&mut elevation as *mut TOKEN_ELEVATION).cast(),
            std::mem::size_of::<TOKEN_ELEVATION>() as u32,
            &mut size,
        )
    } == 0
    {
        return Err(io::Error::last_os_error());
    }
    Ok(elevation.TokenIsElevated != 0)
}
pub fn user_sid() -> io::Result<String> {
    let mut raw = std::ptr::null_mut();
    // SAFETY: borrowed process pseudo-handle; token output owned immediately.
    if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut raw) } == 0 {
        return Err(io::Error::last_os_error());
    }
    let token = unsafe { windows_io::owned(raw) }?;
    let mut size = 0;
    unsafe {
        GetTokenInformation(
            token.as_raw_handle(),
            TokenUser,
            std::ptr::null_mut(),
            0,
            &mut size,
        );
    }
    if size == 0 || size > 65536 {
        return Err(io::Error::last_os_error());
    }
    let mut buffer = vec![0usize; (size as usize).div_ceil(std::mem::size_of::<usize>())];
    if unsafe {
        GetTokenInformation(
            token.as_raw_handle(),
            TokenUser,
            buffer.as_mut_ptr().cast(),
            size,
            &mut size,
        )
    } == 0
    {
        return Err(io::Error::last_os_error());
    }
    let mut text = std::ptr::null_mut();
    // SAFETY: TOKEN_USER and its SID reside in the live, aligned token buffer.
    if unsafe {
        ConvertSidToStringSidW(
            (*(buffer.as_ptr().cast::<TOKEN_USER>())).User.Sid,
            &mut text,
        )
    } == 0
    {
        return Err(io::Error::last_os_error());
    }
    let _allocation = LocalAllocation(text.cast());
    let mut len = 0;
    unsafe {
        while *text.add(len) != 0 {
            len += 1;
        }
    }
    String::from_utf16(unsafe { std::slice::from_raw_parts(text, len) })
        .map_err(|_| io::ErrorKind::InvalidData.into())
}
impl Security {
    pub fn new(inherit: bool) -> io::Result<Self> {
        let inheritance = if inherit { "OICI" } else { "" };
        let sid = user_sid()?;
        // Opt-in SYSTEM diagnostics have their own profile. Administrators
        // need to read its logs; ordinary per-user profile ACLs stay private.
        let administrators = if sid == "S-1-5-18" {
            format!("(A;{inheritance};GA;;;BA)")
        } else {
            String::new()
        };
        let sddl = windows_io::wide(&format!(
            "D:P(A;{inheritance};GA;;;{sid})(A;{inheritance};GA;;;SY){administrators}"
        ));
        let mut raw = std::ptr::null_mut();
        // SAFETY: conversion allocates a self-relative security descriptor.
        if unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                1,
                &mut raw,
                std::ptr::null_mut(),
            )
        } == 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(Self {
            descriptor: LocalAllocation(raw),
        })
    }
    pub fn attributes(&self) -> SECURITY_ATTRIBUTES {
        SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: self.descriptor.0,
            bInheritHandle: 0,
        }
    }
}
pub fn private_directory(path: &Path) -> io::Result<()> {
    let security = Security::new(true)?;
    let mut dacl = std::ptr::null_mut();
    let mut present = 0;
    let mut defaulted = 0;
    // SAFETY: descriptor/ACL remain alive for both calls; API copies the ACL.
    if unsafe {
        GetSecurityDescriptorDacl(
            security.descriptor.0,
            &mut present,
            &mut dacl,
            &mut defaulted,
        )
    } == 0
    {
        return Err(io::Error::last_os_error());
    }
    let mut path: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    windows_io::status(unsafe {
        SetNamedSecurityInfoW(
            path.as_mut_ptr(),
            SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            dacl,
            std::ptr::null(),
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;

    #[test]
    fn elevated_powershell_uses_system_paths_and_resets_modules_before_the_script() {
        let directory = system_directory().unwrap();
        let command = powershell_command("Write-Output 'synthetic'").unwrap();
        assert_eq!(
            Path::new(command.get_program()),
            directory.join("WindowsPowerShell/v1.0/powershell.exe")
        );
        assert_eq!(command.get_current_dir(), Some(directory.as_path()));
        let encoded = command.get_args().last().unwrap().to_str().unwrap();
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(encoded)
            .unwrap();
        let utf16: Vec<u16> = bytes
            .as_chunks::<2>()
            .0
            .iter()
            .map(|chunk| u16::from_le_bytes(*chunk))
            .collect();
        let script = String::from_utf16(&utf16).unwrap();
        assert!(script
            .starts_with("$env:PSModulePath = [IO.Path]::Combine([Environment]::SystemDirectory,"));
        assert!(script.ends_with("\nWrite-Output 'synthetic'"));
    }
}
