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
    let base_ms = 7_500u64 << failures.saturating_sub(1).min(2);
    let mixed = rid
        .wrapping_mul(0x9e3779b97f4a7c15)
        .rotate_left(failures % 64);
    Duration::from_micros(base_ms * 1_000 + mixed % (base_ms * 2_000 + 1))
}

const MAX_RETRY_HANDSHAKES: usize = 4;
const RETRY_SETTLE_DELAY: Duration = Duration::from_secs(5);

/// Recovery has its own slow lane: due timers must not become a burst when
/// initial handshakes release their slots, the engine stalls, or Retry is clicked.
pub(crate) struct RetryPacer {
    settled_after: Instant,
    next_start: Instant,
}
impl RetryPacer {
    pub(crate) fn new(now: Instant) -> Self {
        Self {
            settled_after: now + RETRY_SETTLE_DELAY,
            next_start: now,
        }
    }

    pub(crate) fn observe_initial_work(&mut self, now: Instant, busy: bool) {
        if busy {
            self.settled_after = now + RETRY_SETTLE_DELAY;
        }
    }

    pub(crate) fn ready(&self, now: Instant, active: usize) -> bool {
        active < MAX_RETRY_HANDSHAKES && now >= self.settled_after && now >= self.next_start
    }

    pub(crate) fn started(&mut self, now: Instant, rid: u64, failures: u32) {
        let mixed = rid
            .wrapping_mul(0x9e3779b97f4a7c15)
            .rotate_left(failures % 64);
        self.next_start = now + Duration::from_micros(2_000_000 + mixed % 2_000_001);
    }
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
        let earliest = *delays.first().unwrap();
        let latest = *delays.last().unwrap();
        assert!(latest - earliest > Duration::from_millis(12_500));
        for rid in 1..=150 {
            for (failures, min, max) in [
                (1, 7_500, 22_500),
                (2, 15_000, 45_000),
                (3, 30_000, 90_000),
                (u32::MAX, 30_000, 90_000),
            ] {
                let delay = peer_retry_delay(rid, failures);
                assert!(delay >= Duration::from_millis(min));
                assert!(delay <= Duration::from_millis(max));
            }
        }
    }

    #[test]
    fn retries_wait_for_initial_work_to_settle_and_keep_a_small_concurrency_limit() {
        let start = Instant::now();
        let mut pacer = RetryPacer::new(start);
        let busy = start + Duration::from_secs(40);
        pacer.observe_initial_work(busy, true);
        pacer.observe_initial_work(busy + Duration::from_secs(1), false);
        assert!(!pacer.ready(busy + Duration::from_secs(4), 0));
        assert!(pacer.ready(busy + Duration::from_secs(5), 3));
        assert!(!pacer.ready(busy + Duration::from_secs(5), 4));
        pacer.observe_initial_work(busy + Duration::from_secs(6), true);
        assert!(!pacer.ready(busy + Duration::from_secs(10), 0));
    }

    #[test]
    fn a_hundred_overdue_retries_remain_spaced_even_after_an_engine_stall() {
        let start = Instant::now();
        let mut pacer = RetryPacer::new(start);
        let mut now = start + Duration::from_secs(300);
        let first = now;
        for rid in 1..=100 {
            assert!(pacer.ready(now, 0));
            pacer.started(now, rid, 1);
            assert!(!pacer.ready(now, 0));
            assert!(!pacer.ready(now + Duration::from_millis(1999), 0));
            assert!(pacer.ready(now + Duration::from_secs(4), 0));
            now = pacer.next_start;
        }
        assert!(now - first >= Duration::from_secs(200));
        assert!(now - first <= Duration::from_secs(400));
        now += Duration::from_secs(60);
        assert!(pacer.ready(now, 0));
        pacer.started(now, 101, 1);
        assert!(!pacer.ready(now, 0), "no catch-up burst after a stall");
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
