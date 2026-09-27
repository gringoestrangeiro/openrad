//! Shared limits for blocking peer setup workers, independent of wire protocol.

use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc,
    },
    thread,
    time::{Duration, Instant},
};

// Most setup time is spent waiting for network I/O. Keep enough attempts in
// flight for large networks, with room for incoming offers even when outgoing
// attempts are stalled. Established channels do not consume setup slots.
pub(crate) const MAX_HANDSHAKES: usize = 80;
const MAX_OUTGOING_HANDSHAKES: usize = 64;
pub(crate) const MAX_PENDING_OFFERS: usize = 256;
// Every incoming setup can advertise TCP, local UDP and mapped UDP at once.
pub(crate) const ADVERTISEMENT_QUEUE: usize = MAX_HANDSHAKES * 4;

/// Spread recovery of a large roster over time, with a bounded exponential delay.
pub(crate) fn peer_retry_delay(rid: u64, failures: u32) -> std::time::Duration {
    let base_ms = (500u64 << failures.saturating_sub(1).min(7)).min(60_000);
    let mixed = rid
        .wrapping_mul(0x9e3779b97f4a7c15)
        .rotate_left(failures % 64);
    std::time::Duration::from_micros(base_ms * 1_000 + mixed % (base_ms * 250 + 1))
}

/// Incoming offers have short rendezvous windows. Within each traffic priority,
/// let every new peer try before an already-failed peer takes another slot.
pub(crate) fn peer_priority(
    incoming: bool,
    traffic: bool,
    failures: u32,
    rid: u64,
) -> (bool, bool, u32, u64) {
    (!incoming, !traffic, failures, rid)
}

pub(crate) struct SetupResult<T> {
    pub name: &'static str,
    pub elapsed: Duration,
    pub result: anyhow::Result<T>,
}

/// Collect bounded, scoped transport workers without waiting for slow losers
/// before noticing a winner. Drop runs before the enclosing scope joins them.
pub(crate) struct SetupTasks<T> {
    sender: mpsc::Sender<SetupResult<T>>,
    receiver: mpsc::Receiver<SetupResult<T>>,
    cancel: Arc<AtomicBool>,
    active: usize,
}
impl<T: Send> SetupTasks<T> {
    pub(crate) fn new() -> Self {
        let (sender, receiver) = mpsc::channel();
        Self {
            sender,
            receiver,
            cancel: Arc::new(AtomicBool::new(false)),
            active: 0,
        }
    }

    pub(crate) fn spawn<'scope, 'env, F>(
        &mut self,
        scope: &'scope thread::Scope<'scope, 'env>,
        name: &'static str,
        work: F,
    ) -> std::io::Result<()>
    where
        F: FnOnce(Arc<AtomicBool>) -> anyhow::Result<T> + Send + 'scope,
        T: 'scope,
    {
        let sender = self.sender.clone();
        let cancel = self.cancel.clone();
        thread::Builder::new()
            .name(name.into())
            .spawn_scoped(scope, move || {
                let started = Instant::now();
                let result = work(cancel);
                let _ = sender.send(SetupResult {
                    name,
                    elapsed: started.elapsed(),
                    result,
                });
            })?;
        self.active += 1;
        Ok(())
    }

    pub(crate) fn poll(&mut self) -> Option<SetupResult<T>> {
        let result = self.receiver.try_recv().ok()?;
        self.active -= 1;
        Some(result)
    }

    pub(crate) fn is_idle(&self) -> bool {
        self.active == 0
    }
}
impl<T> Drop for SetupTasks<T> {
    fn drop(&mut self) {
        self.cancel.store(true, Ordering::Relaxed);
    }
}

pub(crate) struct HandshakeBudget {
    total: usize,
    outgoing: usize,
}

impl HandshakeBudget {
    /// Include cancelled workers until they exit, so replacement attempts cannot
    /// exceed the limits while an old socket is still unwinding.
    pub(crate) fn new(incoming: impl Iterator<Item = bool>) -> Self {
        let mut budget = Self {
            total: 0,
            outgoing: 0,
        };
        for is_incoming in incoming {
            budget.total += 1;
            budget.outgoing += usize::from(!is_incoming);
        }
        budget
    }

    pub(crate) fn try_start(&mut self, incoming: bool) -> bool {
        if self.total >= MAX_HANDSHAKES || (!incoming && self.outgoing >= MAX_OUTGOING_HANDSHAKES) {
            return false;
        }
        self.total += 1;
        self.outgoing += usize::from(!incoming);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retries_back_off_are_bounded_and_spread_large_rosters() {
        let delays: std::collections::BTreeSet<_> =
            (1..=150).map(|rid| peer_retry_delay(rid, 1)).collect();
        assert!(delays.len() > 100);
        for rid in 1..=150 {
            assert!(peer_retry_delay(rid, 1) >= std::time::Duration::from_millis(500));
            assert!(peer_retry_delay(rid, 1) <= std::time::Duration::from_millis(625));
            assert!(peer_retry_delay(rid, 2) > peer_retry_delay(rid, 1));
            assert!(peer_retry_delay(rid, u32::MAX) <= std::time::Duration::from_secs(75));
        }
    }

    #[test]
    fn a_hundred_outgoing_peers_are_scheduled_in_two_batches() {
        let mut queued = 100;
        let mut batches = Vec::new();
        while queued > 0 {
            let mut budget = HandshakeBudget::new(std::iter::empty());
            let mut started = 0;
            while queued > 0 && budget.try_start(false) {
                queued -= 1;
                started += 1;
            }
            batches.push(started);
        }
        assert_eq!(batches, [64, 36]);
    }

    #[test]
    fn stalled_outgoing_peers_leave_room_for_incoming_offers() {
        let mut budget = HandshakeBudget::new(std::iter::repeat_n(false, 64));
        assert!(!budget.try_start(false));
        for _ in 0..16 {
            assert!(budget.try_start(true));
        }
        assert!(!budget.try_start(true));
        assert!(!budget.try_start(false));
    }

    #[test]
    fn all_slots_can_accept_incoming_peers_and_exits_free_capacity() {
        let mut budget = HandshakeBudget::new(std::iter::repeat_n(true, 80));
        assert!(!budget.try_start(true));
        assert!(!budget.try_start(false));
        let mut budget = HandshakeBudget::new(std::iter::repeat_n(true, 79));
        assert!(budget.try_start(false));
        assert!(!budget.try_start(true));
    }

    #[test]
    fn outgoing_exit_frees_one_slot_without_resetting_the_batch() {
        let mut budget = HandshakeBudget::new(
            std::iter::repeat_n(false, 63).chain(std::iter::repeat_n(true, 16)),
        );
        assert!(budget.try_start(false));
        assert!(!budget.try_start(false));
        assert!(!budget.try_start(true));
    }

    #[test]
    fn incoming_then_traffic_then_first_attempts_precede_retries() {
        let mut queued = [
            (false, true, 3, 1),
            (false, true, 0, 99),
            (false, false, 0, 2),
            (true, false, 5, 100),
        ];
        queued.sort_by_key(|&(incoming, traffic, failures, rid)| {
            peer_priority(incoming, traffic, failures, rid)
        });
        assert_eq!(queued.map(|p| p.3), [100, 99, 1, 2]);
    }
}
