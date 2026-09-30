//! Best-effort native exception metadata, without process memory dumps.
use crate::early_log;
use std::{
    backtrace::Backtrace,
    sync::{
        atomic::{AtomicBool, Ordering},
        OnceLock,
    },
};
use windows_sys::Win32::System::{
    Diagnostics::Debug::{
        SetUnhandledExceptionFilter, EXCEPTION_CONTINUE_SEARCH, EXCEPTION_POINTERS,
        LPTOP_LEVEL_EXCEPTION_FILTER,
    },
    LibraryLoader::{
        GetModuleFileNameW, GetModuleHandleExW, GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
        GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
    },
    Threading::GetCurrentThreadId,
};

static LOGGED: AtomicBool = AtomicBool::new(false);
static PREVIOUS: OnceLock<LPTOP_LEVEL_EXCEPTION_FILTER> = OnceLock::new();

unsafe extern "system" fn exception_filter(info: *const EXCEPTION_POINTERS) -> i32 {
    // Windows owns these records for the duration of this callback. Avoid
    // recursion if logging itself fails in a damaged native process.
    if !info.is_null()
        && !unsafe { (*info).ExceptionRecord }.is_null()
        && !LOGGED.swap(true, Ordering::Relaxed)
    {
        let record = unsafe { &*(*info).ExceptionRecord };
        let address = record.ExceptionAddress as usize;
        let mut module = std::ptr::null_mut();
        let mut name = [0u16; 2048];
        let length = if unsafe {
            GetModuleHandleExW(
                GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                    | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                record.ExceptionAddress.cast(),
                &mut module,
            )
        } != 0
        {
            (unsafe { GetModuleFileNameW(module, name.as_mut_ptr(), name.len() as u32) }) as usize
        } else {
            0
        };
        let path = String::from_utf16_lossy(&name[..length.min(name.len())]);
        early_log::event(format_args!(
            "Unhandled native exception; code=0x{:08X}; flags=0x{:08X}; windows_thread_id={}; instruction=0x{address:X}; module={path}; module_offset=0x{:X}",
            record.ExceptionCode as u32, record.ExceptionFlags, unsafe { GetCurrentThreadId() },
            address.saturating_sub(module as usize),
        ));
        // Stack symbols/addresses only; no registers, exception parameters,
        // credential values, packet bytes or memory image are recorded.
        early_log::event(format_args!(
            "Native exception handler backtrace:\n{}",
            Backtrace::force_capture()
        ));
    }
    // Preserve the existing exception policy. Do not claim recovery from a
    // native fault or suppress its error code/Windows error reporting.
    if let Some(Some(previous)) = PREVIOUS.get() {
        unsafe { previous(info) }
    } else {
        EXCEPTION_CONTINUE_SEARCH
    }
}

pub fn install() {
    let previous = unsafe { SetUnhandledExceptionFilter(Some(exception_filter)) };
    let _ = PREVIOUS.set(previous);
    early_log::event(format_args!("Native Windows exception logging installed"));
}
