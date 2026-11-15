package io.github.gringoestrangeiro.openradclient;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PeerShutdownTest {
    /** A generation whose worker ignores interruption until released, like a thread stuck in a native read. */
    private static final class StuckGeneration implements PeerShutdown.Stoppable {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final Thread worker = new Thread(() -> {
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    // Deliberately uninterruptible.
                }
            }
            finished.countDown();
        });

        StuckGeneration() { worker.setDaemon(true); worker.start(); }

        @Override public boolean awaitStopped(Duration timeout) throws InterruptedException {
            return finished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        @Override public String describeRunning() { return "1 peer worker"; }
    }

    @Test void aWorkerThatIgnoresInterruptionIsWaitedForOnlyUpToTheBound() throws Exception {
        StuckGeneration stuck = new StuckGeneration();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        long started = System.nanoTime();
        boolean stopped = PeerShutdown.await(stuck, Duration.ofMillis(300), log::add);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertFalse(stopped);
        assertTrue(elapsed >= 290 && elapsed < 3000, "bounded wait: " + elapsed + " ms");
        assertEquals(1, log.size());
        assertTrue(log.getFirst().contains("1 peer worker"), log.getFirst());
        stuck.release.countDown();
    }

    @Test void interruptsDoNotShortenTheWaitButAreRestored() throws Exception {
        StuckGeneration stuck = new StuckGeneration();
        AtomicBoolean restored = new AtomicBoolean();
        AtomicBoolean stopped = new AtomicBoolean(true);
        long[] elapsed = new long[1];
        Thread waiter = new Thread(() -> {
            long started = System.nanoTime();
            stopped.set(PeerShutdown.await(stuck, Duration.ofMillis(400), message -> { }));
            elapsed[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            restored.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        Thread.sleep(50);
        waiter.interrupt();
        waiter.join(5000);
        assertFalse(waiter.isAlive());
        assertFalse(stopped.get());
        assertTrue(elapsed[0] >= 390, "the interrupt must not cut the wait short: " + elapsed[0]);
        assertTrue(restored.get(), "the interrupt flag is restored for the caller");
        stuck.release.countDown();
    }

    @Test void aGenerationThatStopsEndsTheWaitEarly() {
        StuckGeneration quick = new StuckGeneration();
        quick.release.countDown();
        List<String> log = new ArrayList<>();
        assertTrue(PeerShutdown.await(quick, Duration.ofSeconds(5), log::add));
        assertTrue(log.isEmpty());
    }

    @Test void theIdentityLockIsHeldUntilTheStuckWorkerFinishes() throws Exception {
        StuckGeneration stuck = new StuckGeneration();
        CountDownLatch lockClosed = new CountDownLatch(1);
        Thread keeper = PeerShutdown.holdUntilStopped(List.of(stuck), lockClosed::countDown, message -> { });
        assertFalse(lockClosed.await(1200, TimeUnit.MILLISECONDS), "never released while a worker still runs");
        stuck.release.countDown();
        assertTrue(lockClosed.await(5, TimeUnit.SECONDS), "released once the worker has finished");
        keeper.join(5000);
        assertFalse(keeper.isAlive(), "the keeper exits after releasing");
    }
}
