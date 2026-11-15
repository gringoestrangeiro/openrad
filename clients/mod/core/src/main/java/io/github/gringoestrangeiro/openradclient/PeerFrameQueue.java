package io.github.gringoestrangeiro.openradclient;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Bounded hand-off of outbound Ethernet frames from the TCP stack's sender thread to one peer
 * worker. The worker drains it between reads, so under a coarse OS timer (15.6 ms on Windows) a
 * single drain period must absorb every frame two full windows of the active flows produce. A frame
 * that does not fit is lost to TCP, and the stack recovers a burst loss only one RTO per segment,
 * which stalls a bulk transfer; therefore a full queue first applies a short, bounded back-pressure
 * to the producer and only then drops. After a drop the queue stops waiting for a while, so one
 * wedged peer cannot hold up the sender thread that serves all peers.
 */
final class PeerFrameQueue {
    /** Two windows (45 segments plus their acknowledgements each) for several flows. */
    static final int CAPACITY = 512;
    static final long PRODUCER_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
    static final long PENALTY_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final ArrayBlockingQueue<byte[]> queue;
    private final LongSupplier clock;
    private final LongAdder dropped = new LongAdder(), waited = new LongAdder();
    private volatile long penaltyUntil;
    private volatile boolean closed;

    PeerFrameQueue() {
        this(CAPACITY, System::nanoTime);
    }

    PeerFrameQueue(int capacity, LongSupplier clock) {
        queue = new ArrayBlockingQueue<>(capacity);
        this.clock = clock;
    }

    /** False when closed or when the frame had to be dropped. Called from the sender thread. */
    boolean offer(byte[] frame) throws InterruptedException {
        if (closed) return false;
        if (queue.offer(frame)) return true;
        long now = clock.getAsLong();
        if (now - penaltyUntil >= 0 && penaltyUntil != 0) penaltyUntil = 0;
        if (penaltyUntil == 0) {
            waited.increment();
            if (queue.offer(frame, PRODUCER_WAIT_NANOS, TimeUnit.NANOSECONDS)) return !closed;
            penaltyUntil = clock.getAsLong() + PENALTY_NANOS;
        }
        dropped.increment();
        return false;
    }

    byte[] peek() { return queue.peek(); }
    byte[] poll() { return queue.poll(); }
    boolean isEmpty() { return queue.isEmpty(); }
    int size() { return queue.size(); }
    long dropped() { return dropped.sum(); }
    long waited() { return waited.sum(); }

    void close() {
        closed = true;
        queue.clear();
    }
}
