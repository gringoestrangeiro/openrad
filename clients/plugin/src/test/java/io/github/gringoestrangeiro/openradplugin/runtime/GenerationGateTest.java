package io.github.gringoestrangeiro.openradplugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(10)
class GenerationGateTest {
    private static final class FakeGeneration {
        final CountDownLatch closing = new CountDownLatch(1);
        final CountDownLatch closeStarted = new CountDownLatch(1);
        volatile boolean started;

        void start() {
            started = true;
        }

        void close() throws InterruptedException {
            closeStarted.countDown();
            closing.await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownDoesNotWaitForBlockingCloseAndStillDetachesGeneration() throws Exception {
        GenerationGate<FakeGeneration> gate = new GenerationGate<>();
        FakeGeneration first = new FakeGeneration();
        assertTrue(gate.install(first, FakeGeneration::start));
        assertTrue(first.started);

        AtomicReference<FakeGeneration> retired = new AtomicReference<>();
        CountDownLatch detached = new CountDownLatch(1);
        Thread apply =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    FakeGeneration previous = gate.retire();
                                    retired.set(previous);
                                    detached.countDown();
                                    if (previous == null) return;
                                    try {
                                        previous.close();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
        assertTrue(detached.await(2, TimeUnit.SECONDS));
        assertSame(first, retired.get());
        assertTrue(first.closeStarted.await(2, TimeUnit.SECONDS));

        long started = System.nanoTime();
        gate.stop();
        GenerationGate.Handoff<FakeGeneration> handoff = gate.takeAll();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsed < 200, "shutdown waited for close(): " + elapsed + "ms");
        assertNull(handoff.current());
        assertSame(first, handoff.retiring());
        assertFalse(gate.install(new FakeGeneration(), FakeGeneration::start));
        assertNull(gate.retire());

        first.closing.countDown();
        apply.join(2000);
        assertFalse(apply.isAlive());
    }

    @Test
    void refusedGenerationRemainsRetirableUntilItStops() {
        GenerationGate<FakeGeneration> gate = new GenerationGate<>();
        FakeGeneration first = new FakeGeneration();
        assertTrue(gate.install(first, FakeGeneration::start));

        assertSame(first, gate.retire());
        assertNull(gate.current());
        assertSame(first, gate.retire());
        gate.retired(first);
        assertNull(gate.retire());

        FakeGeneration next = new FakeGeneration();
        assertTrue(gate.install(next, FakeGeneration::start));
        assertSame(next, gate.current());
    }

    @Test
    void shutdownCapturesCurrentAndRetiringGenerations() {
        GenerationGate<FakeGeneration> gate = new GenerationGate<>();
        FakeGeneration retiring = new FakeGeneration();
        FakeGeneration current = new FakeGeneration();
        assertTrue(gate.install(retiring, FakeGeneration::start));
        assertSame(retiring, gate.retire());
        assertTrue(gate.install(current, FakeGeneration::start));

        gate.stop();
        GenerationGate.Handoff<FakeGeneration> handoff = gate.takeAll();
        assertSame(current, handoff.current());
        assertSame(retiring, handoff.retiring());
        assertNull(gate.current());
        assertNull(gate.takeAll().current());
    }
}
