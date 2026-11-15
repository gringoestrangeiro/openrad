package io.github.gringoestrangeiro.openradplugin.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Ensure that the Java17 compatibility layer does not penalize Java21+ users. */
class ThreadCompatTest {
    @Test
    void platformFactoryCreatesDaemonThread() {
        Thread thread = ThreadCompat.platformFactory("orb-test-").newThread(() -> {});
        assertTrue(thread.isDaemon());
        assertTrue(thread.getName().startsWith("orb-test-"));
    }

    @Test
    void virtualFactoryKeepsVirtualThreadsOnModernJdks() throws Exception {
        Thread thread = ThreadCompat.virtualFactory("orb-virtual-").newThread(() -> {});
        if (Runtime.version().feature() >= 21) assertTrue(thread.isVirtual());
        thread.start();
        thread.join(1_000);
        assertFalse(thread.isAlive());
    }

    @Test
    void raceExecutorKeepsVirtualThreadsOnModernJdks() throws Exception {
        var executor = ThreadCompat.virtualExecutor();
        try {
            var task = executor.submit(() -> Thread.currentThread().isVirtual());
            if (Runtime.version().feature() >= 21)
                assertTrue(task.get(3, TimeUnit.SECONDS));
            else
                assertFalse(task.get(3, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
