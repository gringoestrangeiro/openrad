package io.github.gringoestrangeiro.openradclient.interop;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradclient.interop.reference.ReferenceInboundFirewall;
import io.github.gringoestrangeiro.openradclient.interop.reference.ReferenceServerTcpBridge;
import io.github.gringoestrangeiro.openradclient.virtual.OutboundTcpStack;
import io.github.gringoestrangeiro.openradplugin.link.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression for the bulk-transfer stall of the E2E scenario S5: 8 MiB echoed through one virtual
 * connection made no progress for minutes. The peer worker hands frames over through a bounded
 * queue that it drains between reads; with a coarse OS timer (15.6 ms on Windows) one drain period
 * outgrew the queue, a burst of frames was dropped, and a stack that recovers one segment per
 * (doubling) RTO needed minutes. These tests reproduce both halves without the E2E harness: a
 * link that drops a whole burst, and a bounded queue drained by a slow worker.
 */
@Timeout(90)
class BulkTransferUnderLossTest {
    private static final int CLIENT = 0x1a0a0001, SERVER = 0x1a0a0002;
    private static final ReferenceInboundFirewall.PeerGrant GRANT =
            new ReferenceInboundFirewall.PeerGrant(1234, true, Set.of("isolated-test"), CLIENT, OpenRadAddress.mac(CLIENT));
    private static final Inet4Address SERVER_IP = ip(SERVER), CLIENT_IP = ip(CLIENT);
    private static final int MIB = 2;

    private static Inet4Address ip(int value) {
        try { return (Inet4Address) InetAddress.getByAddress(new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value}); }
        catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
    }

    /** Client-to-server link: optionally loses a burst of data frames, optionally queues them for a slow worker. */
    private static final class Link implements AutoCloseable {
        final AtomicReference<ReferenceServerTcpBridge> server = new AtomicReference<>();
        final AtomicReference<OutboundTcpStack> client = new AtomicReference<>();
        final ArrayBlockingQueue<byte[]> workerQueue;
        final int drainMillis, dropFrom, dropCount;
        final AtomicInteger dataFrames = new AtomicInteger(), dropped = new AtomicInteger();
        volatile boolean stopped;
        Thread worker;

        Link(int queueCapacity, int drainMillis, int dropFrom, int dropCount) {
            workerQueue = queueCapacity > 0 ? new ArrayBlockingQueue<>(queueCapacity) : null;
            this.drainMillis = drainMillis;
            this.dropFrom = dropFrom;
            this.dropCount = dropCount;
            if (workerQueue != null) {
                worker = new Thread(() -> {
                    while (!stopped) {
                        for (byte[] frame; (frame = workerQueue.poll()) != null; ) deliverToServer(frame);
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(this.drainMillis));
                    }
                }, "test-slow-worker");
                worker.setDaemon(true);
                worker.start();
            }
        }

        void fromClient(Inet4Address destination, byte[] frame) {
            assertEquals(SERVER_IP, destination);
            if (frame.length > 14 + 20 + 20 + 100) { // a data segment, not a bare ACK
                int n = dataFrames.incrementAndGet();
                if (n > dropFrom && n <= dropFrom + dropCount) { dropped.incrementAndGet(); return; }
            }
            if (workerQueue == null) deliverToServer(frame);
            else if (!workerQueue.offer(frame)) dropped.incrementAndGet();
        }

        void deliverToServer(byte[] frame) { server.get().accept(GRANT, frame); }

        void fromServer(long rid, byte[] frame) { client.get().acceptEthernet(SERVER_IP, frame); }

        @Override public void close() { stopped = true; }
    }

    private record Outcome(double seconds, boolean intact, Link link) {}

    private static Outcome echo(Link link) throws Exception {
        byte[] payload = new byte[MIB << 20];
        new Random(11).nextBytes(payload);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
             ServerSocket backend = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             Link l = link;
             OutboundTcpStack client = new OutboundTcpStack(CLIENT_IP, l::fromClient);
             ReferenceServerTcpBridge server = new ReferenceServerTcpBridge(SERVER, OpenRadAddress.mac(SERVER), 25565,
                     new InetSocketAddress(backend.getInetAddress(), backend.getLocalPort()), l::fromServer)) {
            l.client.set(client);
            l.server.set(server);
            workers.submit(() -> {
                try (Socket accepted = backend.accept()) {
                    accepted.setSoTimeout(60_000);
                    InputStream in = accepted.getInputStream();
                    OutputStream out = accepted.getOutputStream();
                    byte[] buffer = new byte[16384];
                    for (int n; (n = in.read(buffer)) >= 0; ) { out.write(buffer, 0, n); out.flush(); }
                    accepted.shutdownOutput();
                } catch (IOException ignored) { }
                return null;
            });
            long begin = System.nanoTime();
            try (OutboundTcpStack.Connection connection = client.connect(SERVER_IP, 25565, Duration.ofSeconds(20))) {
                Future<?> writer = workers.submit(() -> {
                    try {
                        connection.output().write(payload);
                        connection.output().flush();
                        connection.shutdownOutput();
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                });
                byte[] echoed = connection.input().readAllBytes();
                writer.get(10, TimeUnit.SECONDS);
                double seconds = (System.nanoTime() - begin) / 1e9;
                return new Outcome(seconds, Arrays.equals(digest, MessageDigest.getInstance("SHA-256").digest(echoed)), l);
            }
        }
    }

    @Test void aLostBurstOfSegmentsIsRecoveredInRoundTripsNotInDoublingTimeouts() throws Exception {
        // Thirty consecutive data segments vanish once: one per backed-off RTO this took minutes.
        Outcome outcome = echo(new Link(0, 0, 200, 30));
        assertEquals(30, outcome.link.dropped.get(), "the test must lose the burst it claims to");
        assertTrue(outcome.intact, "echoed bytes must match");
        assertTrue(outcome.seconds < 20, "burst loss recovery took " + outcome.seconds + " s");
    }

    @Test void aSlowWorkerWithABoundedQueueStillMovesBulkData() throws Exception {
        // 48 slots drained every 15 ms: smaller than a window of segments, so frames are dropped.
        Outcome outcome = echo(new Link(48, 15, Integer.MAX_VALUE, 0));
        assertTrue(outcome.link.dropped.get() > 0, "the test must overflow the queue to be meaningful");
        assertTrue(outcome.intact, "echoed bytes must match");
        assertTrue(outcome.seconds < 40, "bulk transfer behind a slow worker took " + outcome.seconds + " s");
    }

    @Test void aSlowWorkerQueueOfProductionSizeNeverOverflowsForOneSaturatedFlow() throws Exception {
        Outcome outcome = echo(new Link(512, 15, Integer.MAX_VALUE, 0));
        assertEquals(0, outcome.link.dropped.get(), "a saturated flow must fit one 15 ms drain period");
        assertTrue(outcome.intact);
        assertTrue(outcome.seconds < 20, "took " + outcome.seconds + " s");
    }
}
