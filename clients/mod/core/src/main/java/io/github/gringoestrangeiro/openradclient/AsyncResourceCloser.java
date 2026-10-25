package io.github.gringoestrangeiro.openradclient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Admission reserves cleanup capacity before a peer can own sockets, so cancellation never blocks UI. */
final class AsyncResourceCloser implements AutoCloseable {
    private final int capacity;
    private final ThreadPoolExecutor executor;
    private int reserved;
    private boolean closed;

    AsyncResourceCloser(int capacity) {
        this.capacity = capacity;
        executor = new ThreadPoolExecutor(8, 8, 1, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),
                Thread.ofVirtual().name("openrad-client-cleanup-", 0).factory());
        executor.allowCoreThreadTimeOut(true);
    }

    synchronized Lease reserve() {
        if (closed || reserved >= capacity) return null;
        reserved++;
        return new Lease();
    }

    final class Lease {
        private final AtomicBoolean finished = new AtomicBoolean();
        void finish(List<AutoCloseable> resources) {
            if (!finished.compareAndSet(false, true)) return;
            // At most capacity leases exist; executor has capacity queue slots in addition to workers.
            executor.execute(() -> {
                try {
                    for (AutoCloseable resource : resources)
                        try { resource.close(); } catch (Exception ignored) { }
                } finally { released(); }
            });
        }
    }

    private synchronized void released() {
        reserved--;
        if (closed && reserved == 0) executor.shutdown();
    }

    @Override public synchronized void close() {
        closed = true;
        if (reserved == 0) executor.shutdown();
    }

    boolean awaitStopped(Duration timeout) throws InterruptedException {
        return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
}
