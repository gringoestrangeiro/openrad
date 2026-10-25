package io.github.gringoestrangeiro.openradclient;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

class AsyncResourceCloserTest {
    @Test void slowPhysicalSocketCleanupDoesNotBlockCancellationAndItsCapacityStaysBounded() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AsyncResourceCloser closer = new AsyncResourceCloser(1);
        try {
            var lease = closer.reserve();
            assertNotNull(lease);
            assertNull(closer.reserve());
            AutoCloseable stalled = () -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            };
            long began = System.nanoTime();
            lease.finish(List.of(stalled));
            assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(1));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertNull(closer.reserve(), "closing resources still consume bounded ownership capacity");
            closer.close();
            assertFalse(closer.awaitStopped(Duration.ofMillis(20)));
            assertNull(closer.reserve());
            release.countDown();
            assertTrue(closer.awaitStopped(Duration.ofSeconds(2)));
            lease.finish(List.of(() -> fail("a lease can only close its resources once")));
        } finally {
            release.countDown();
            closer.close();
        }
    }
}
