//! Shared limits for blocking peer setup workers, independent of wire protocol.

// Most setup time is spent waiting for network I/O. Keep enough attempts in
// flight for large networks, with room for incoming offers even when outgoing
// attempts are stalled. Established channels do not consume setup slots.
const MAX_HANDSHAKES: usize = 32;
const MAX_OUTGOING_HANDSHAKES: usize = 24;

/// Spread recovery of a large roster over time, with a bounded exponential delay.
pub(crate) fn peer_retry_delay(rid: u64, failures: u32) -> std::time::Duration {
    let base_ms = (2_000u64 << failures.saturating_sub(1).min(5)).min(60_000);
    let mixed = rid
        .wrapping_mul(0x9e3779b97f4a7c15)
        .rotate_left(failures % 64);
    std::time::Duration::from_millis(base_ms + mixed % (base_ms / 4 + 1))
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
            assert!(peer_retry_delay(rid, 1) >= std::time::Duration::from_secs(2));
            assert!(peer_retry_delay(rid, 2) > peer_retry_delay(rid, 1));
            assert!(peer_retry_delay(rid, u32::MAX) <= std::time::Duration::from_secs(75));
        }
    }

    #[test]
    fn a_hundred_outgoing_peers_are_scheduled_in_five_batches() {
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
        assert_eq!(batches, [24, 24, 24, 24, 4]);
    }

    #[test]
    fn stalled_outgoing_peers_leave_room_for_incoming_offers() {
        let mut budget = HandshakeBudget::new(std::iter::repeat_n(false, 24));
        assert!(!budget.try_start(false));
        for _ in 0..8 {
            assert!(budget.try_start(true));
        }
        assert!(!budget.try_start(true));
        assert!(!budget.try_start(false));
    }

    #[test]
    fn all_slots_can_accept_incoming_peers_and_exits_free_capacity() {
        let mut budget = HandshakeBudget::new(std::iter::repeat_n(true, 32));
        assert!(!budget.try_start(true));
        assert!(!budget.try_start(false));
        let mut budget = HandshakeBudget::new(std::iter::repeat_n(true, 31));
        assert!(budget.try_start(false));
        assert!(!budget.try_start(true));
    }

    #[test]
    fn outgoing_exit_frees_one_slot_without_resetting_the_batch() {
        let mut budget = HandshakeBudget::new(
            std::iter::repeat_n(false, 23).chain(std::iter::repeat_n(true, 8)),
        );
        assert!(budget.try_start(false));
        assert!(!budget.try_start(false));
        assert!(!budget.try_start(true));
    }
}
