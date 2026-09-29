//! Coalesced local notifications for socket/queue waits. No network bytes.
#[cfg(target_os = "linux")]
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
#[cfg(not(target_os = "linux"))]
use std::sync::{Condvar, Mutex};
use std::{
    io,
    sync::atomic::{AtomicBool, Ordering},
    time::Duration,
};

pub(crate) struct Wake {
    pending: AtomicBool,
    #[cfg(target_os = "linux")]
    fd: OwnedFd,
    #[cfg(not(target_os = "linux"))]
    parked: (Mutex<()>, Condvar),
}
impl Wake {
    pub fn new() -> io::Result<Self> {
        #[cfg(target_os = "linux")]
        let fd = {
            // SAFETY: eventfd takes no pointers; OwnedFd closes it exactly once.
            let raw = unsafe { libc::eventfd(0, libc::EFD_NONBLOCK | libc::EFD_CLOEXEC) };
            if raw < 0 {
                return Err(io::Error::last_os_error());
            }
            unsafe { OwnedFd::from_raw_fd(raw) }
        };
        Ok(Self {
            pending: AtomicBool::new(false),
            #[cfg(target_os = "linux")]
            fd,
            #[cfg(not(target_os = "linux"))]
            parked: (Mutex::new(()), Condvar::new()),
        })
    }
    /// Publish the queue item or cancellation flag before calling this method.
    pub fn notify(&self) {
        if self.pending.swap(true, Ordering::AcqRel) {
            return;
        }
        #[cfg(target_os = "linux")]
        {
            let value = 1u64;
            loop {
                // SAFETY: the pointer describes an initialized eight-byte value.
                let result =
                    unsafe { libc::write(self.fd.as_raw_fd(), (&value as *const u64).cast(), 8) };
                if result >= 0 || io::Error::last_os_error().kind() != io::ErrorKind::Interrupted {
                    break;
                }
            }
        }
        #[cfg(not(target_os = "linux"))]
        {
            let _guard = self.parked.0.lock().unwrap();
            self.parked.1.notify_one();
        }
    }
    /// Clear before checking queues. A racing notification remains pending even
    /// if its eventfd write is drained here, so wait never loses the wakeup.
    pub fn clear(&self) {
        self.pending.store(false, Ordering::Release);
        #[cfg(target_os = "linux")]
        {
            let mut value = 0u64;
            loop {
                // SAFETY: the pointer describes a writable eight-byte value.
                let result =
                    unsafe { libc::read(self.fd.as_raw_fd(), (&mut value as *mut u64).cast(), 8) };
                if result >= 0 || io::Error::last_os_error().kind() != io::ErrorKind::Interrupted {
                    break;
                }
            }
        }
    }
    pub fn is_pending(&self) -> bool {
        self.pending.load(Ordering::Acquire)
    }
    /// Wait for the local notification and, optionally, a socket/TAP descriptor.
    /// Returns true only for network/interface readiness; local work wakes false.
    pub fn wait(&self, network_fd: Option<i32>, timeout: Duration) -> io::Result<bool> {
        if self.is_pending() {
            return Ok(false);
        }
        #[cfg(target_os = "linux")]
        {
            let mut fds = [
                libc::pollfd {
                    fd: self.fd.as_raw_fd(),
                    events: libc::POLLIN,
                    revents: 0,
                },
                libc::pollfd {
                    fd: network_fd.unwrap_or(-1),
                    events: libc::POLLIN,
                    revents: 0,
                },
            ];
            let millis = timeout.as_nanos().div_ceil(1_000_000).min(i32::MAX as u128) as i32;
            // SAFETY: fds is a live array of the supplied length.
            let result = unsafe { libc::poll(fds.as_mut_ptr(), fds.len() as _, millis) };
            if result < 0 {
                let error = io::Error::last_os_error();
                if error.kind() != io::ErrorKind::Interrupted {
                    return Err(error);
                }
            }
            Ok(fds[1].revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0)
        }
        #[cfg(not(target_os = "linux"))]
        {
            let _ = network_fd;
            let guard = self.parked.0.lock().unwrap();
            if !self.is_pending() {
                drop(self.parked.1.wait_timeout(guard, timeout).unwrap());
            }
            Ok(false)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        sync::{atomic::AtomicUsize, Arc},
        thread,
        time::Instant,
    };

    #[test]
    fn queued_and_racing_notifications_do_not_get_lost() {
        let wake = Arc::new(Wake::new().unwrap());
        let count = Arc::new(AtomicUsize::new(0));
        let (signal, produced) = (wake.clone(), count.clone());
        let worker = thread::spawn(move || {
            for n in 1..=20_000 {
                produced.store(n, Ordering::Release);
                signal.notify();
                if n % 16 == 0 {
                    thread::yield_now();
                }
            }
        });
        let until = Instant::now() + Duration::from_secs(3);
        loop {
            wake.clear();
            if count.load(Ordering::Acquire) == 20_000 {
                break;
            }
            assert!(Instant::now() < until, "lost a producer notification");
            wake.wait(None, Duration::from_millis(100)).unwrap();
        }
        worker.join().unwrap();
        wake.clear();
        wake.notify();
        let start = Instant::now();
        assert!(!wake.wait(None, Duration::from_secs(1)).unwrap());
        assert!(start.elapsed() < Duration::from_millis(500));
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn queue_notification_and_socket_readiness_wake_the_same_wait() {
        use std::net::UdpSocket;
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        let remote = UdpSocket::bind("127.0.0.1:0").unwrap();
        let wake = Arc::new(Wake::new().unwrap());
        let signal = wake.clone();
        let worker = thread::spawn(move || {
            thread::sleep(Duration::from_millis(20));
            signal.notify();
        });
        assert!(!wake
            .wait(Some(socket.as_raw_fd()), Duration::from_secs(1))
            .unwrap());
        worker.join().unwrap();
        wake.clear();
        remote
            .send_to(b"unchanged", socket.local_addr().unwrap())
            .unwrap();
        assert!(wake
            .wait(Some(socket.as_raw_fd()), Duration::from_secs(1))
            .unwrap());
        let mut bytes = [0; 16];
        let len = socket.recv(&mut bytes).unwrap();
        assert_eq!(&bytes[..len], b"unchanged");
    }
}
