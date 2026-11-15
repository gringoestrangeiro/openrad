package io.github.gringoestrangeiro.openradclient;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PeerScanScheduleTest {
    private static final long MILLI = Duration.ofMillis(1).toNanos();

    @Test void aBurstOfMembershipRecordsCausesABoundedNumberOfScans() {
        PeerScanSchedule schedule = new PeerScanSchedule(0);
        long scans = 0, legacyScans = 0, legacyNext = 0;
        for (long t = 0; t <= 5_000 * MILLI; t += MILLI) {
            // One membership record every millisecond for five seconds.
            schedule.recordHandled(t);
            if (schedule.due(t)) { schedule.scanned(t); scans++; }
            // The previous rule: every record pulled the next scan to 10 ms after itself.
            legacyNext = Math.min(legacyNext, t + 10 * MILLI);
            if (t >= legacyNext) { legacyNext = t + 100 * MILLI; legacyScans++; }
        }
        assertTrue(legacyScans > 300, "the unbounded rule scanned " + legacyScans + " times");
        assertTrue(scans <= 51, "5 s of sustained records must cost at most one scan per 100 ms: " + scans);
        assertTrue(scans >= 40, "scans must still run regularly: " + scans);
    }

    @Test void aRecordIsNeverDelayedByMoreThanTheMinimumGapAfterAScan() {
        PeerScanSchedule schedule = new PeerScanSchedule(0);
        schedule.scanned(0);
        long record = 30 * MILLI;
        schedule.recordHandled(record);
        assertTrue(schedule.untilNext(record) <= PeerScanSchedule.MIN_GAP,
                "a record after a scan waits at most the gap");
    }

    @Test void idleSchedulesScanPeriodicallyAndStartWithAnImmediateScan() {
        PeerScanSchedule schedule = new PeerScanSchedule(5);
        assertTrue(schedule.due(5));
        schedule.scanned(5);
        assertFalse(schedule.due(5 + PeerScanSchedule.PERIODIC - 1));
        assertTrue(schedule.due(5 + PeerScanSchedule.PERIODIC));
    }
}
