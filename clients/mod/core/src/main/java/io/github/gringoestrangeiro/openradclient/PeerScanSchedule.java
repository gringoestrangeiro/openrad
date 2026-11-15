package io.github.gringoestrangeiro.openradclient;

import java.time.Duration;

/**
 * When the control thread rescans the membership roster for peers to dial, drop or accept. Scans are
 * O(roster size), so sustained membership traffic must not trigger one per record: a record schedules
 * a prompt rescan, but never closer than {@link #MIN_GAP} after the previous scan. Times are
 * System.nanoTime() values.
 */
final class PeerScanSchedule {
    static final long PERIODIC = Duration.ofMillis(100).toNanos();
    static final long PROMPT = Duration.ofMillis(10).toNanos();
    static final long MIN_GAP = Duration.ofMillis(100).toNanos();
    private long next, lastScan;

    /** The first scan is due immediately. */
    PeerScanSchedule(long now) {
        next = now;
        lastScan = now - MIN_GAP;
    }

    boolean due(long now) { return now - next >= 0; }

    /** Nanoseconds until the next scan; zero or negative when due. */
    long untilNext(long now) { return next - now; }

    /** A control record changed (or may have changed) what a scan would decide. */
    void recordHandled(long now) {
        long earliest = Math.max(now + PROMPT, lastScan + MIN_GAP);
        if (earliest - next < 0) next = earliest;
    }

    void scanned(long now) {
        lastScan = now;
        next = now + PERIODIC;
    }
}
