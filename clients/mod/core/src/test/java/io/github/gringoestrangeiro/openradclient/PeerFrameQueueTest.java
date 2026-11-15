package io.github.gringoestrangeiro.openradclient;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class PeerFrameQueueTest {
    private static byte[] frame(int id) { return new byte[] {(byte) id}; }

    @Test void productionCapacityHoldsTwoWindowsOfSeveralFlows() {
        assertTrue(PeerFrameQueue.CAPACITY >= 4 * 90, "capacity " + PeerFrameQueue.CAPACITY);
    }

    @Test void queuesInOrderAndCountsNothingWhileThereIsRoom() throws Exception {
        PeerFrameQueue queue = new PeerFrameQueue();
        for (int i = 0; i < 100; i++) assertTrue(queue.offer(frame(i)));
        assertEquals(100, queue.size());
        assertEquals(0, queue.peek()[0]);
        assertEquals(0, queue.poll()[0]);
        assertEquals(0, queue.dropped());
        assertEquals(0, queue.waited());
    }

    @Test void aFullQueueMakesTheProducerWaitForTheWorkerInsteadOfDropping() throws Exception {
        PeerFrameQueue queue = new PeerFrameQueue(2, System::nanoTime);
        assertTrue(queue.offer(frame(1)));
        assertTrue(queue.offer(frame(2)));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> third = pool.submit(() -> queue.offer(frame(3)));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (queue.waited() == 0 && System.nanoTime() < end) Thread.sleep(1);
            assertEquals(1, queue.waited(), "producer should be waiting");
            assertNotNull(queue.poll());
            assertTrue(third.get(5, TimeUnit.SECONDS), "space freed by the worker admits the frame");
            assertEquals(0, queue.dropped());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void aWedgedWorkerCostsOneBoundedWaitThenTheQueueDropsWithoutDelayingTheSenderThread() throws Exception {
        AtomicLong now = new AtomicLong(1);
        PeerFrameQueue queue = new PeerFrameQueue(1, now::get);
        assertTrue(queue.offer(frame(1)));
        long begin = System.nanoTime();
        assertFalse(queue.offer(frame(2)), "no space within the bounded wait");
        assertTrue(System.nanoTime() - begin >= PeerFrameQueue.PRODUCER_WAIT_NANOS / 2, "it did wait first");
        assertEquals(1, queue.dropped());
        begin = System.nanoTime();
        for (int i = 0; i < 1000; i++) assertFalse(queue.offer(frame(i)));
        assertTrue(System.nanoTime() - begin < PeerFrameQueue.PRODUCER_WAIT_NANOS,
                "while penalised the producer must not wait per frame");
        assertEquals(1001, queue.dropped());
        // The penalty ends: the producer waits (and drops) again instead of being penalised forever.
        now.addAndGet(PeerFrameQueue.PENALTY_NANOS + 1);
        long waits = queue.waited();
        assertFalse(queue.offer(frame(9)));
        assertEquals(waits + 1, queue.waited());
        queue.poll();
        assertTrue(queue.offer(frame(10)));
    }

    @Test void closingDropsQueuedFramesAndRefusesNewOnes() throws Exception {
        PeerFrameQueue queue = new PeerFrameQueue();
        assertTrue(queue.offer(frame(1)));
        queue.close();
        assertTrue(queue.isEmpty());
        assertFalse(queue.offer(frame(2)));
    }
}
