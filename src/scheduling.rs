//! Shared limits for blocking peer setup workers, independent of wire protocol.

// Most setup time is spent waiting for network I/O. Keep enough attempts in
// flight for large networks, with room for incoming offers even when outgoing
// attempts are stalled. Established channels do not consume setup slots.
const MAX_HANDSHAKES: usize = 32;
const MAX_OUTGOING_HANDSHAKES: usize = 24;

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
