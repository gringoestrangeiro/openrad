package io.github.gringoestrangeiro.openradplugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.config.BridgeSettings;
import io.github.gringoestrangeiro.openradplugin.transport.FramedTcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Peer worker pacing and the bridge limits derived from configuration. */
@Timeout(60)
class PeerPollingTest {
    private static final long SECOND = 1_000_000_000L;
    private static final long LONG_AGO = 3600 * SECOND;

    @Test
    void pollIntervalIsShortOnlyWhileTheBridgeCanProduceFramesForThePeer() {
        // Frames queued for the peer or recent inbound traffic: shortest poll.
        assertEquals(Duration.ofMillis(2), VpnRuntime.pollTimeout(true, false, LONG_AGO, 15 * SECOND));
        assertEquals(Duration.ofMillis(2), VpnRuntime.pollTimeout(false, false, 100_000_000L, 15 * SECOND));
        assertEquals(Duration.ofMillis(2), VpnRuntime.pollTimeout(false, true, 0, 15 * SECOND));
        // Live flows but a quiet moment: the historical 20 ms.
        assertEquals(Duration.ofMillis(20), VpnRuntime.pollTimeout(false, true, LONG_AGO, 15 * SECOND));
        // No flow, nothing queued, no recent traffic: long poll, bounded by the next duty.
        assertEquals(Duration.ofMillis(200), VpnRuntime.pollTimeout(false, false, LONG_AGO, 15 * SECOND));
        assertEquals(Duration.ofMillis(40), VpnRuntime.pollTimeout(false, false, LONG_AGO, 40_000_000L));
        // An overdue duty must not turn into a zero or negative wait.
        assertEquals(Duration.ofMillis(2), VpnRuntime.pollTimeout(false, false, LONG_AGO, -5 * SECOND));
        assertEquals(Duration.ofMillis(2), VpnRuntime.pollTimeout(false, false, LONG_AGO, 0));
    }

    @Test
    void bridgeLimitsScalePerPeerCapWithTheGlobalCapAndBoundHalfOpenFlows() {
        record Case(int players, int perPeer) {}
        for (Case c :
                List.of(
                        new Case(1, 1),
                        new Case(2, 2),
                        new Case(3, 2),
                        new Case(8, 2),
                        new Case(16, 4),
                        new Case(30, 7),
                        new Case(64, 8),
                        new Case(1024, 8))) {
            var limits = VpnRuntime.bridgeLimits(settings(c.players(), 60, 15));
            assertEquals(c.players(), limits.maxConnections());
            assertEquals(c.perPeer(), limits.maxConnectionsPerPeer(), "players " + c.players());
            assertEquals(Math.min(2, c.perPeer()), limits.maxHalfOpenPerPeer());
            if (c.players() >= 8)
                assertTrue(
                        limits.maxConnections() / limits.maxConnectionsPerPeer() >= 4,
                        "at least four members are needed to fill the bridge");
        }
        var defaults = VpnRuntime.bridgeLimits(settings(30, 60, 15));
        assertEquals(Duration.ofSeconds(8), defaults.handshakeTimeout());
        assertEquals(Duration.ofSeconds(15), defaults.connectTimeout());
        assertEquals(Duration.ofSeconds(3), VpnRuntime.bridgeLimits(settings(30, 60, 3)).handshakeTimeout());
    }

    private static BridgeSettings settings(int players, int pending, int timeout) {
        return new BridgeSettings(
                true, "127.0.0.1", 25565, 25565, players, pending, timeout, "", 0, "", 0, 100);
    }

    private record Pair(FramedTcp worker, FramedTcp remote) implements AutoCloseable {
        @Override
        public void close() {
            try {
                worker.close();
                remote.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static List<Pair> pairs(int count) throws IOException {
        List<Pair> result = new ArrayList<>();
        try (ServerSocket listener = new ServerSocket(0, count, InetAddress.getLoopbackAddress())) {
            for (int i = 0; i < count; i++) {
                Socket remote = new Socket(listener.getInetAddress(), listener.getLocalPort());
                Socket worker = listener.accept();
                FramedTcp a = new FramedTcp(worker, Duration.ofMinutes(5));
                FramedTcp b = new FramedTcp(remote, Duration.ofMinutes(5));
                a.sustain();
                b.sustain();
                result.add(new Pair(a, b));
            }
        }
        return result;
    }

    /** ready() calls per second per idle peer when every worker polls with {@code wait}. */
    private static double callsPerSecond(List<Pair> peers, Supplier<Duration> wait, int millis)
            throws Exception {
        AtomicLong calls = new AtomicLong();
        AtomicBoolean running = new AtomicBoolean(true);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> loops = new ArrayList<>();
            for (Pair peer : peers)
                loops.add(
                        pool.submit(
                                () -> {
                                    while (running.get()) {
                                        assertFalse(peer.worker().ready(wait.get()));
                                        calls.incrementAndGet();
                                    }
                                    return null;
                                }));
            Thread.sleep(500); // let every loop settle
            long before = calls.get(), began = System.nanoTime();
            Thread.sleep(millis);
            long after = calls.get(), ended = System.nanoTime();
            running.set(false);
            for (Future<?> loop : loops) loop.get(5, TimeUnit.SECONDS);
            return (after - before) / peers.size() / ((ended - began) / 1e9);
        }
    }

    @Test
    void idlePeersWithoutFlowsPollAnOrderOfMagnitudeLessThanBefore() throws Exception {
        int peerCount = 100;
        List<Pair> peers = pairs(peerCount);
        try {
            double before = callsPerSecond(peers, () -> Duration.ofMillis(20), 3000);
            double after =
                    callsPerSecond(
                            peers,
                            () -> VpnRuntime.pollTimeout(false, false, LONG_AGO, 15 * SECOND),
                            3000);
            System.out.printf(
                    "idle peers=%d: ready() calls/s/peer before=%.1f after=%.1f (total %.0f/s -> %.0f/s)%n",
                    peerCount, before, after, before * peerCount, after * peerCount);
            assertTrue(after * 5 < before, "before " + before + " after " + after);
        } finally {
            for (Pair peer : peers) peer.close();
        }
    }

    @Test
    void inboundDataWakesAnIdlePeerLongBeforeItsPollInterval() throws Exception {
        List<Pair> peers = pairs(1);
        Pair peer = peers.get(0);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            long worst = 0;
            for (int round = 0; round < 5; round++) {
                Future<Long> waiter =
                        pool.submit(
                                () -> {
                                    long began = System.nanoTime();
                                    while (!peer.worker()
                                            .ready(
                                                    VpnRuntime.pollTimeout(
                                                            false, false, LONG_AGO, 15 * SECOND))) {}
                                    return System.nanoTime() - began;
                                });
                Thread.sleep(60);
                peer.remote().send(new byte[] {1, 2, 3, 4});
                long total = waiter.get(5, TimeUnit.SECONDS);
                assertArrayEquals(new byte[] {1, 2, 3, 4}, peer.worker().receive(16));
                worst = Math.max(worst, TimeUnit.NANOSECONDS.toMillis(total) - 60);
            }
            System.out.println("idle-peer wake-up delay after inbound data (worst of 5): " + worst + " ms");
            assertTrue(worst < 100, "an idle worker must wake on inbound data, not on its poll: " + worst);
        } finally {
            peer.close();
        }
    }
}
