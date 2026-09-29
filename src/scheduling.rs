//! Shared limits for blocking peer setup workers, independent of wire protocol.

use std::{
    collections::VecDeque,
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

/// Retry promptly, while retaining jitter so peers do not reconnect in lockstep.
pub(crate) fn peer_retry_delay(rid: u64, failures: u32) -> std::time::Duration {
    let base_ms = 1_500u64 << failures.saturating_sub(1).min(3);
    let mixed = rid
        .wrapping_mul(0x9e3779b97f4a7c15)
        .rotate_left(failures % 64);
    Duration::from_micros(base_ms * 1_000 + mixed % (base_ms * 2_000 + 1))
}

const RETRY_OUTCOME_WINDOW: Duration = Duration::from_secs(30);

#[derive(Clone, Copy)]
pub(crate) struct RetryPolicy {
    pub(crate) active_limit: usize,
    pub(crate) start_interval: Duration,
}

/// Use more setup capacity when many peers need recovery. Reduce pressure when
/// recent attempts mostly fail, without stopping retries altogether.
pub(crate) struct RetryPacer {
    next_start: Instant,
    outcomes: VecDeque<(Instant, bool)>,
}
impl RetryPacer {
    pub(crate) fn new(now: Instant) -> Self {
        Self {
            next_start: now,
            outcomes: VecDeque::new(),
        }
    }

    pub(crate) fn record_outcome(&mut self, now: Instant, success: bool) {
        self.outcomes.push_back((now, success));
        self.prune(now);
    }

    fn prune(&mut self, now: Instant) {
        while self
            .outcomes
            .front()
            .is_some_and(|(at, _)| now.saturating_duration_since(*at) > RETRY_OUTCOME_WINDOW)
        {
            self.outcomes.pop_front();
        }
    }

    pub(crate) fn policy(&mut self, now: Instant, waiting: usize) -> RetryPolicy {
        self.prune(now);
        let mut policy = if waiting >= 32 {
            RetryPolicy {
                active_limit: 48,
                start_interval: Duration::from_millis(50),
            }
        } else if waiting >= 8 {
            RetryPolicy {
                active_limit: 32,
                start_interval: Duration::from_millis(100),
            }
        } else {
            RetryPolicy {
                active_limit: 16,
                start_interval: Duration::from_millis(200),
            }
        };
        let failures = self.outcomes.iter().filter(|(_, success)| !success).count();
        if self.outcomes.len() >= 8 && failures * 4 >= self.outcomes.len() * 3 {
            policy.active_limit = policy.active_limit.min(32);
            policy.start_interval = policy.start_interval.max(Duration::from_millis(150));
        }
        policy
    }

    pub(crate) fn ready(&self, now: Instant, active: usize, policy: RetryPolicy) -> bool {
        active < policy.active_limit && now >= self.next_start
    }

    pub(crate) fn started(&mut self, now: Instant, policy: RetryPolicy) {
        self.next_start = now + policy.start_interval;
    }
}

/// Incoming offers have short rendezvous windows. Recover established channels
/// first, then other failed peers, while leaving setup slots for new peers.
pub(crate) fn peer_priority(
    incoming: bool,
    retry: bool,
    previously_connected: bool,
    traffic: bool,
    failures: u32,
    rid: u64,
) -> (u8, bool, u32, u64) {
    let lane = if incoming {
        0
    } else if previously_connected {
        1
    } else if retry {
        2
    } else {
        3
    };
    (lane, !traffic, failures, rid)
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
        self.try_start_with_reserve(incoming, 0)
    }

    /// Hold outgoing room for retries that are already due. Incoming offers
    /// still use the full total budget and never consume outgoing slots.
    pub(crate) fn try_start_with_reserve(
        &mut self,
        incoming: bool,
        reserved_outgoing: usize,
    ) -> bool {
        if self.total >= MAX_HANDSHAKES
            || (!incoming
                && self.outgoing >= MAX_OUTGOING_HANDSHAKES.saturating_sub(reserved_outgoing))
        {
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
        assert!(latest - earliest > Duration::from_millis(2_500));
        for rid in 1..=150 {
            for (failures, min, max) in [
                (1, 1_500, 4_500),
                (2, 3_000, 9_000),
                (3, 6_000, 18_000),
                (4, 12_000, 36_000),
                (u32::MAX, 12_000, 36_000),
            ] {
                let delay = peer_retry_delay(rid, failures);
                assert!(delay >= Duration::from_millis(min));
                assert!(delay <= Duration::from_millis(max));
            }
        }
    }

    #[test]
    fn recovery_scales_with_backlog_and_never_exceeds_reserved_capacity() {
        let start = Instant::now();
        let mut pacer = RetryPacer::new(start);
        assert_eq!(pacer.policy(start, 1).active_limit, 16);
        assert_eq!(pacer.policy(start, 8).active_limit, 32);
        let burst = pacer.policy(start, 300);
        assert_eq!(burst.active_limit, 48);
        assert!(pacer.ready(start, 47, burst));
        assert!(!pacer.ready(start, 48, burst));
        assert!(burst.active_limit < MAX_OUTGOING_HANDSHAKES);
    }

    #[test]
    fn failures_reduce_pressure_and_recent_success_restores_burst_capacity() {
        let start = Instant::now();
        let mut pacer = RetryPacer::new(start);
        for _ in 0..8 {
            pacer.record_outcome(start, false);
        }
        let restrained = pacer.policy(start, 100);
        assert_eq!(restrained.active_limit, 32);
        assert_eq!(restrained.start_interval, Duration::from_millis(150));
        assert_eq!(
            pacer
                .policy(start + Duration::from_secs(31), 100)
                .active_limit,
            48
        );
    }

    #[test]
    fn overdue_retries_start_quickly_without_an_unbounded_burst() {
        let start = Instant::now();
        let mut pacer = RetryPacer::new(start);
        let policy = pacer.policy(start, 100);
        assert!(pacer.ready(start, 0, policy));
        pacer.started(start, policy);
        assert!(!pacer.ready(start, 0, policy));
        assert!(!pacer.ready(start + Duration::from_millis(49), 0, policy));
        assert!(pacer.ready(start + Duration::from_millis(50), 0, policy));
        let after_stall = start + Duration::from_secs(300);
        pacer.started(after_stall, policy);
        assert!(!pacer.ready(after_stall, 0, policy));
    }

    #[test]
    fn recovery_prioritizes_prior_channels_and_preserves_incoming_offers() {
        let mut peers = [
            peer_priority(false, false, false, true, 0, 1),
            peer_priority(false, true, false, true, 1, 2),
            peer_priority(false, true, true, true, 2, 3),
            peer_priority(true, true, false, true, 2, 4),
        ];
        peers.sort();
        assert_eq!(peers.iter().map(|p| p.3).collect::<Vec<_>>(), [4, 3, 2, 1]);
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
    fn due_retries_keep_outgoing_slots_while_initial_work_continues() {
        let mut budget = HandshakeBudget::new(std::iter::empty());
        for _ in 0..16 {
            assert!(budget.try_start_with_reserve(false, 48));
        }
        assert!(!budget.try_start_with_reserve(false, 48));
        for _ in 0..48 {
            assert!(budget.try_start(false));
        }
        assert!(!budget.try_start(false));
        for _ in 0..16 {
            assert!(budget.try_start(true));
        }
        assert!(!budget.try_start(true));
    }

    #[test]
    fn traffic_priority_applies_within_each_recovery_lane() {
        let mut queued = [
            (false, true, false, true, 3, 1),
            (false, false, false, true, 0, 99),
            (false, true, false, false, 0, 2),
            (true, true, false, false, 5, 100),
        ];
        queued.sort_by_key(|&(incoming, retry, connected, traffic, failures, rid)| {
            peer_priority(incoming, retry, connected, traffic, failures, rid)
        });
        assert_eq!(queued.map(|p| p.5), [100, 1, 2, 99]);
    }
}
