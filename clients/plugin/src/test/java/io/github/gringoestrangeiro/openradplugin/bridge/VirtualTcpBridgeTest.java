package io.github.gringoestrangeiro.openradplugin.bridge;

import static io.github.gringoestrangeiro.openradplugin.link.TcpSegment.*;
import static io.github.gringoestrangeiro.openradplugin.virtual.TcpSequence.*;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.link.*;
import io.github.gringoestrangeiro.openradplugin.security.InboundFirewall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Timeout(45)
class VirtualTcpBridgeTest {
    private static final int LOCAL = 0x1a010102,
            PEER = 0x1a010103,
            PORT = 25565,
            CLIENT_PORT = 49152;
    private static final byte[] LOCAL_MAC = OpenRadAddress.mac(LOCAL),
            PEER_MAC = OpenRadAddress.mac(PEER);
    private static final InboundFirewall.PeerGrant GRANT =
            new InboundFirewall.PeerGrant(1234, true, Set.of("synthetic"), PEER, PEER_MAC);
    private static final byte[] EMPTY = new byte[0];

    @FunctionalInterface
    interface BackendHandler {
        void handle(Socket socket) throws Exception;
    }

    private static final class Backend implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        final AtomicInteger accepted = new AtomicInteger();
        final List<Socket> clients = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();

        Backend(BackendHandler handler) throws IOException {
            tasks.submit(
                    () -> {
                        try {
                            while (!server.isClosed()) {
                                Socket socket = server.accept();
                                socket.setSoTimeout(30000);
                                clients.add(socket);
                                accepted.incrementAndGet();
                                tasks.submit(
                                        () -> {
                                            try (socket) {
                                                handler.handle(socket);
                                            } catch (Exception e) {
                                                if (!server.isClosed()) failures.add(e);
                                            }
                                        });
                            }
                        } catch (IOException e) {
                            if (!server.isClosed()) failures.add(e);
                        }
                    });
        }

        InetSocketAddress address() {
            return new InetSocketAddress(server.getInetAddress(), server.getLocalPort());
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket client : clients) client.close();
            tasks.shutdownNow();
        }
    }

    private static final class Client implements AutoCloseable {
        final BlockingQueue<TcpSegment> wire = new LinkedBlockingQueue<>();
        final VirtualTcpBridge bridge;
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final TreeMap<Long, byte[]> reordered = new TreeMap<>();
        long next = 0xffff_fff0L, acknowledged = next, serverNext, serverRight;
        long serverFin = -1, lastAckSent;
        int advertisedWindow = 32768;
        boolean dropFirstPayload, dropped, finReceived;
        final AtomicInteger emittedArps = new AtomicInteger();
        volatile Runnable arpHook;

        Client(InetSocketAddress backend) throws IOException {
            this(backend, VirtualTcpBridge.Limits.defaults());
        }

        Client(InetSocketAddress backend, VirtualTcpBridge.Limits limits) throws IOException {
            bridge =
                    new VirtualTcpBridge(
                            LOCAL,
                            LOCAL_MAC,
                            PORT,
                            backend,
                            limits,
                            (rid, frame) -> {
                                assertEquals(GRANT.rid(), rid);
                                EthernetFrame eth = EthernetFrame.parse(frame);
                                assertArrayEquals(PEER_MAC, eth.destinationMac());
                                assertArrayEquals(LOCAL_MAC, eth.sourceMac());
                                if (eth.etherType() == EthernetFrame.ARP) {
                                    if (arpHook != null) arpHook.run();
                                    emittedArps.incrementAndGet();
                                    return;
                                }
                                Ipv4Packet ip = Ipv4Packet.parse(eth.payload());
                                assertEquals(LOCAL, ip.sourceIp());
                                assertEquals(PEER, ip.destinationIp());
                                wire.add(TcpSegment.parse(ip.tcpBytes(), LOCAL, PEER));
                            });
        }

        TcpSegment take() throws InterruptedException {
            TcpSegment packet = wire.poll(5, TimeUnit.SECONDS);
            assertNotNull(packet, "virtual endpoint stopped responding: " + bridge.snapshot());
            return packet;
        }

        void syn() {
            send(next, 0, SYN | ECE | CWR, new byte[] {2, 4, 5, (byte) 0xb4}, EMPTY);
            next++;
        }

        void handshake() throws InterruptedException {
            syn();
            finishHandshake();
        }

        void finishHandshake() throws InterruptedException {
            TcpSegment synack = take();
            assertEquals(SYN | ACK, synack.flags());
            assertEquals(wire(next), synack.acknowledgement());
            assertArrayEquals(new byte[] {2, 4, 5, (byte) 0xb4}, synack.options());
            serverNext = synack.sequence() + 1;
            lastAckSent = serverNext;
            acknowledged = next;
            serverRight = next + synack.window();
            ack();
        }

        void ack() {
            lastAckSent = serverNext;
            send(next, serverNext, ACK, EMPTY, EMPTY);
        }

        void send(long seq, long ack, int flags, byte[] options, byte[] payload) {
            TcpSegment tcp =
                    new TcpSegment(
                            CLIENT_PORT,
                            PORT,
                            wire(seq),
                            wire(ack),
                            flags,
                            advertisedWindow,
                            options,
                            payload);
            assertTrue(bridge.accept(GRANT, ethernet(PEER_MAC, PEER, tcp)));
        }

        void pump() throws InterruptedException {
            process(take());
        }

        void process(TcpSegment tcp) {
            assertEquals(0, tcp.flags() & RST, "unexpected reset: " + bridge.snapshot());
            if ((tcp.flags() & ACK) != 0) {
                long ack = unwrap(tcp.acknowledgement(), acknowledged);
                assertTrue(ack <= next, "ACK acknowledges unsent bytes");
                if (ack >= acknowledged) {
                    acknowledged = ack;
                    serverRight = ack + tcp.window();
                }
            }
            if ((tcp.flags() & SYN) != 0) {
                ack();
                return;
            }
            long sequence = unwrap(tcp.sequence(), serverNext);
            byte[] payload = tcp.payload();
            if (payload.length > 0) {
                assertTrue(
                        sequence + payload.length <= lastAckSent + advertisedWindow
                                || sequence < serverNext,
                        "server exceeded advertised receive window");
                if (dropFirstPayload && !dropped) {
                    dropped = true;
                    return;
                }
                if (sequence + payload.length > serverNext)
                    reordered.putIfAbsent(sequence, payload);
                while (!reordered.isEmpty() && reordered.firstKey() <= serverNext) {
                    var entry = reordered.pollFirstEntry();
                    int skip = (int) Math.min(entry.getValue().length, serverNext - entry.getKey());
                    received.write(entry.getValue(), skip, entry.getValue().length - skip);
                    serverNext += entry.getValue().length - skip;
                }
            }
            if ((tcp.flags() & FIN) != 0) serverFin = sequence + payload.length;
            if (serverFin == serverNext) {
                serverNext++;
                finReceived = true;
            }
            if (payload.length > 0 || (tcp.flags() & FIN) != 0) ack();
        }

        void transfer(byte[] bytes, boolean reorderPairs) throws InterruptedException {
            int offset = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (offset < bytes.length || acknowledged < next || received.size() < bytes.length) {
                assertTrue(
                        System.nanoTime() < deadline,
                        "transfer stalled at " + offset + "/" + received.size());
                int credit = (int) Math.min(Integer.MAX_VALUE, Math.max(0, serverRight - next));
                // Reversed pairs exercise a hole, overlap, duplicate suppression and sequence wrap.
                while (offset < bytes.length && credit > 0) {
                    int size = Math.min(Math.min(1460, credit), bytes.length - offset);
                    byte[] first = Arrays.copyOfRange(bytes, offset, offset + size);
                    if (reorderPairs
                            && credit >= size + 1460
                            && offset + size + 1460 <= bytes.length) {
                        byte[] second =
                                Arrays.copyOfRange(bytes, offset + size, offset + size + 1460);
                        send(next + size, serverNext, ACK | PSH, EMPTY, second);
                        send(next, serverNext, ACK | PSH, EMPTY, first);
                        send(next, serverNext, ACK | PSH, EMPTY, first); // exact retransmission
                        next += size + 1460;
                        offset += size + 1460;
                        credit -= size + 1460;
                    } else {
                        send(next, serverNext, ACK | PSH, EMPTY, first);
                        next += size;
                        offset += size;
                        credit -= size;
                    }
                }
                pump();
            }
        }

        @Override
        public void close() {
            bridge.close();
        }
    }

    @Test
    void multiMegabyteDuplexTransferReordersDeduplicatesWrapsAndRecoversLoss() throws Exception {
        byte[] input = new byte[2 * 1024 * 1024 + 731];
        new Random(9031).nextBytes(input);
        ByteArrayOutputStream backendReceived = new ByteArrayOutputStream();
        CountDownLatch backendEof = new CountDownLatch(1);
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    byte[] buffer = new byte[16384];
                                    int size;
                                    while ((size = socket.getInputStream().read(buffer)) >= 0) {
                                        backendReceived.write(buffer, 0, size);
                                        socket.getOutputStream().write(buffer, 0, size);
                                    }
                                    socket.shutdownOutput();
                                    backendEof.countDown();
                                });
                Client client = new Client(backend.address())) {
            client.dropFirstPayload = true;
            client.handshake();
            client.transfer(input, true);
            assertArrayEquals(input, client.received.toByteArray());
            client.send(client.next, client.serverNext, FIN | ACK, EMPTY, EMPTY);
            client.next++;
            while (!client.finReceived) client.pump();
            assertTrue(backendEof.await(5, TimeUnit.SECONDS));
            assertArrayEquals(input, backendReceived.toByteArray());
            assertTrue(client.bridge.snapshot().retransmissions() > 0);
            assertEquals(1, backend.accepted.get());
            assertTrue(backend.failures.isEmpty(), backend.failures.toString());
            assertTrue(client.bridge.snapshot().bufferedBytes() < 1024 * 1024);
        }
    }

    @Test
    void lostSynAckRetransmitsSameSequenceWithoutSecondBackend() throws Exception {
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                Client client = new Client(backend.address())) {
            client.syn();
            TcpSegment first = client.take();
            await(
                    () ->
                            client.bridge.state(GRANT.rid(), CLIENT_PORT)
                                    == VirtualTcpBridge.TcpState.SYN_RECEIVED);
            TcpSegment retry = client.take();
            assertEquals(first.sequence(), retry.sequence());
            assertEquals(first.acknowledgement(), retry.acknowledgement());
            assertEquals(SYN | ACK, retry.flags());
            assertEquals(1, backend.accepted.get());
            client.serverNext = first.sequence() + 1;
            client.ack();
            await(
                    () ->
                            client.bridge.state(GRANT.rid(), CLIENT_PORT)
                                    == VirtualTcpBridge.TcpState.ESTABLISHED);
            assertTrue(client.bridge.snapshot().retransmissions() >= 1);
        }
    }

    @Test
    void overlappingSegmentsAndOutOfOrderFinDeliverExactlyOneStream() throws Exception {
        byte[] input = new byte[256];
        new Random(91).nextBytes(input);
        try (Backend backend =
                        new Backend(
                                socket ->
                                        socket.getInputStream()
                                                .transferTo(socket.getOutputStream()));
                Client client = new Client(backend.address())) {
            client.handshake();
            long start = client.next;
            client.send(start + 256, client.serverNext, FIN | ACK, EMPTY, EMPTY);
            client.send(
                    start + 128,
                    client.serverNext,
                    ACK | PSH,
                    EMPTY,
                    Arrays.copyOfRange(input, 128, 256));
            client.send(
                    start + 64,
                    client.serverNext,
                    ACK | PSH,
                    EMPTY,
                    Arrays.copyOfRange(input, 64, 192));
            client.send(
                    start, client.serverNext, ACK | PSH, EMPTY, Arrays.copyOfRange(input, 0, 128));
            client.next += 257;
            while (!client.finReceived) client.pump();
            assertEquals(client.next, client.acknowledged);
            assertArrayEquals(input, client.received.toByteArray());
        }
    }

    @Test
    void partialAckRetransmitsOnlyUnacknowledgedSuffix() throws Exception {
        byte[] input = new byte[9000];
        new Random(171).nextBytes(input);
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.getOutputStream().write(input);
                                    socket.shutdownOutput();
                                    socket.getInputStream().readAllBytes();
                                });
                Client client = new Client(backend.address())) {
            client.advertisedWindow = 4096;
            client.handshake();
            TcpSegment first = client.take();
            assertTrue(first.payload().length > 7);
            client.received.write(first.payload(), 0, 7);
            client.serverNext += 7;
            client.ack();
            while (!client.finReceived) client.pump();
            assertArrayEquals(input, client.received.toByteArray());
            assertTrue(client.bridge.snapshot().retransmissions() > 0);
        }
    }

    @Test
    void finRetransmissionAndTimeWaitReleaseAllState() throws Exception {
        var d = VirtualTcpBridge.Limits.defaults();
        var limits =
                new VirtualTcpBridge.Limits(
                        2,
                        1,
                        64,
                        65536,
                        1024 * 1024,
                        d.connectTimeout(),
                        d.handshakeTimeout(),
                        d.idleTimeout(),
                        d.halfCloseTimeout(),
                        Duration.ofMillis(100),
                        3);
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.shutdownOutput();
                                    socket.getInputStream().readAllBytes();
                                });
                Client client = new Client(backend.address(), limits)) {
            client.handshake();
            TcpSegment first = client.take();
            assertEquals(ACK | FIN, first.flags());
            await(
                    () ->
                            client.bridge.state(GRANT.rid(), CLIENT_PORT)
                                    == VirtualTcpBridge.TcpState.FIN_WAIT_1);
            TcpSegment retry = client.take();
            assertEquals(ACK | FIN, retry.flags());
            assertEquals(first.sequence(), retry.sequence());
            client.process(retry);
            await(
                    () ->
                            client.bridge.state(GRANT.rid(), CLIENT_PORT)
                                    == VirtualTcpBridge.TcpState.FIN_WAIT_2);
            client.send(client.next, client.serverNext, FIN | ACK, EMPTY, EMPTY);
            client.next++;
            await(() -> client.bridge.snapshot().connections() == 0);
            assertEquals(0, client.bridge.snapshot().bufferedBytes());
        }
    }

    @Test
    void backendHalfClosePreservesClientToBackendStreamUntilRemoteFin() throws Exception {
        CountDownLatch eof = new CountDownLatch(1);
        ByteArrayOutputStream backendReceived = new ByteArrayOutputStream();
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.getOutputStream().write(new byte[] {9, 8, 7});
                                    socket.shutdownOutput();
                                    backendReceived.write(socket.getInputStream().readAllBytes());
                                    eof.countDown();
                                });
                Client client = new Client(backend.address())) {
            client.handshake();
            while (!client.finReceived) client.pump();
            assertArrayEquals(new byte[] {9, 8, 7}, client.received.toByteArray());
            byte[] afterEof = {1, 2, 3, 4, 5};
            client.send(client.next, client.serverNext, ACK | PSH, EMPTY, afterEof);
            client.next += afterEof.length;
            client.send(client.next, client.serverNext, FIN | ACK, EMPTY, EMPTY);
            client.next++;
            assertTrue(eof.await(5, TimeUnit.SECONDS));
            assertArrayEquals(afterEof, backendReceived.toByteArray());
        }
    }

    @Test
    void halfClosedClientKeepsReceivingWhileBackendProgresses() throws Exception {
        var d = VirtualTcpBridge.Limits.defaults();
        var limits =
                new VirtualTcpBridge.Limits(
                        2,
                        1,
                        64,
                        65536,
                        1024 * 1024,
                        d.connectTimeout(),
                        d.handshakeTimeout(),
                        Duration.ofSeconds(10),
                        Duration.ofMillis(400),
                        d.timeWait(),
                        4);
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    for (int i = 0; i < 200; i++) {
                                        socket.getOutputStream().write(i);
                                        socket.getOutputStream().flush();
                                        Thread.sleep(5);
                                    }
                                });
                Client client = new Client(backend.address(), limits)) {
            client.handshake();
            client.send(client.next, client.serverNext, FIN | ACK, EMPTY, EMPTY);
            client.next++;
            // Windows may round Thread.sleep(5) to the system timer tick (~16 ms).
            // Keep an event-driven completion condition, but allow scheduling delay on
            // a busy Windows test worker (the bridge progress timeout is 10s).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            while (System.nanoTime() < deadline && client.received.size() <= 100) {
                TcpSegment packet = client.wire.poll(20, TimeUnit.MILLISECONDS);
                if (packet != null) client.process(packet);
            }
            assertEquals(0, client.bridge.snapshot().resets());
            assertTrue(client.received.size() > 100, "received " + client.received.size());
            assertEquals(
                    VirtualTcpBridge.TcpState.CLOSE_WAIT,
                    client.bridge.state(GRANT.rid(), CLIENT_PORT));
        }
    }

    @Test
    void halfClosedFlowWithoutProgressStillResetsAfterTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        var d = VirtualTcpBridge.Limits.defaults();
        var limits =
                new VirtualTcpBridge.Limits(
                        2,
                        1,
                        64,
                        65536,
                        1024 * 1024,
                        d.connectTimeout(),
                        d.handshakeTimeout(),
                        Duration.ofSeconds(10),
                        Duration.ofMillis(400),
                        d.timeWait(),
                        4);
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.getInputStream().readAllBytes();
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                });
                Client client = new Client(backend.address(), limits)) {
            client.handshake();
            client.send(client.next, client.serverNext, FIN | ACK, EMPTY, EMPTY);
            client.next++;
            TcpSegment reset = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                TcpSegment packet = client.take();
                if ((packet.flags() & RST) != 0) {
                    reset = packet;
                    break;
                }
                client.process(packet);
            }
            assertNotNull(reset, "half-closed flow was not reset");
            assertEquals(RST | ACK, reset.flags());
            await(() -> client.bridge.snapshot().resets() == 1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void zeroWindowStopsDataAndWindowUpdateResumesBoundedStream() throws Exception {
        byte[] bytes = new byte[100000];
        new Random(7).nextBytes(bytes);
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.getOutputStream().write(bytes);
                                    socket.shutdownOutput();
                                    socket.getInputStream().readAllBytes();
                                });
                Client client = new Client(backend.address())) {
            client.advertisedWindow = 0;
            client.handshake();
            TcpSegment probe = client.take();
            assertEquals(wire(client.serverNext - 1), probe.sequence());
            assertEquals(1, probe.payload().length);
            assertTrue(client.bridge.snapshot().bufferedBytes() <= 65536 + 256 * 1024);
            client.advertisedWindow = 4096;
            client.ack();
            while (!client.finReceived) client.pump();
            assertArrayEquals(bytes, client.received.toByteArray());
            client.send(client.next, client.serverNext, ACK | FIN, EMPTY, EMPTY);
            client.next++;
        }
    }

    @Test
    void zeroWindowWithOutstandingDataUsesPersistWithoutExhaustingRetryBudget() throws Exception {
        byte[] input = new byte[100];
        new Random(66).nextBytes(input);
        var d = VirtualTcpBridge.Limits.defaults();
        var limits =
                new VirtualTcpBridge.Limits(
                        2,
                        1,
                        64,
                        65536,
                        1024 * 1024,
                        d.connectTimeout(),
                        d.handshakeTimeout(),
                        Duration.ofSeconds(5),
                        d.halfCloseTimeout(),
                        d.timeWait(),
                        1);
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.getOutputStream().write(input);
                                    socket.shutdownOutput();
                                    socket.getInputStream().readAllBytes();
                                });
                Client client = new Client(backend.address(), limits)) {
            client.handshake();
            TcpSegment lost = client.take();
            assertArrayEquals(input, lost.payload());
            client.advertisedWindow = 0;
            client.ack(); // No data ACK: the original bytes remain in flight.
            int probes = 0;
            while (probes < 2) {
                TcpSegment packet = client.take();
                assertEquals(0, packet.flags() & RST);
                if (packet.payload().length == 1 && packet.sequence() == wire(client.serverNext))
                    probes++;
                else
                    assertEquals(
                            ACK | FIN, packet.flags(), "ordinary RTO ran against a zero window");
            }
            assertEquals(0, client.bridge.snapshot().retransmissions());
            client.advertisedWindow = 4096;
            client.ack();
            while (!client.finReceived) client.pump();
            assertArrayEquals(input, client.received.toByteArray());
        }
    }

    @Test
    void spoofedAndUnjoinedFramesCannotOpenBackendAndRevocationClosesExistingFlow()
            throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.getInputStream().readAllBytes();
                                    closed.countDown();
                                });
                Client client = new Client(backend.address())) {
            TcpSegment syn = new TcpSegment(CLIENT_PORT, PORT, 7, 0, SYN, 65535, EMPTY, EMPTY);
            assertFalse(
                    client.bridge.accept(
                            new InboundFirewall.PeerGrant(
                                    1234, false, Set.of("synthetic"), PEER, PEER_MAC),
                            ethernet(PEER_MAC, PEER, syn)));
            assertTrue(client.bridge.accept(GRANT, ethernet(LOCAL_MAC, PEER, syn)));
            assertTrue(client.bridge.accept(GRANT, ethernet(PEER_MAC, LOCAL, syn)));
            // An ARP roundtrip is an event-loop barrier after the rejected frames.
            byte[] arp =
                    new EthernetFrame(
                                    new byte[] {-1, -1, -1, -1, -1, -1},
                                    PEER_MAC,
                                    EthernetFrame.ARP,
                                    new ArpPacket(1, PEER_MAC, PEER, new byte[6], LOCAL).encode())
                            .encode();
            assertTrue(client.bridge.accept(GRANT, arp));
            await(() -> client.emittedArps.get() == 1);
            assertEquals(0, backend.accepted.get());
            client.handshake();
            await(() -> backend.accepted.get() == 1);
            client.bridge.revokePeer(GRANT.rid());
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            await(() -> client.bridge.snapshot().connections() == 0);
            assertEquals(0, client.bridge.snapshot().bufferedBytes());
        }
    }

    @Test
    void outOfWindowResetAndAckOfUnsentDataDoNotDestroyFlow() throws Exception {
        try (Backend backend =
                        new Backend(
                                socket ->
                                        socket.getInputStream()
                                                .transferTo(socket.getOutputStream()));
                Client client = new Client(backend.address())) {
            client.handshake();
            client.send(client.next + 1_000_000, 0, RST, EMPTY, EMPTY);
            client.send(client.next, client.serverNext + 1_000_000, ACK, EMPTY, EMPTY);
            byte[] input = new byte[7000];
            new Random(4).nextBytes(input);
            client.transfer(input, false);
            assertArrayEquals(input, client.received.toByteArray());
            assertEquals(1, client.bridge.snapshot().connections());
        }
    }

    @Test
    void queuedOldEpochIsDiscardedAndImmediateNewEpochSurvivesDeferredRevocation()
            throws Exception {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Backend backend =
                        new Backend(
                                socket ->
                                        socket.getInputStream()
                                                .transferTo(socket.getOutputStream()));
                Client client = new Client(backend.address())) {
            client.handshake();
            client.arpHook =
                    () -> {
                        blocked.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            throw new AssertionError(e);
                        }
                    };
            byte[] arp =
                    new EthernetFrame(
                                    new byte[] {-1, -1, -1, -1, -1, -1},
                                    PEER_MAC,
                                    EthernetFrame.ARP,
                                    new ArpPacket(1, PEER_MAC, PEER, new byte[6], LOCAL).encode())
                            .encode();
            assertTrue(client.bridge.accept(GRANT, arp));
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            var oldSyn = new TcpSegment(CLIENT_PORT + 1, PORT, 991, 0, SYN, 65535, EMPTY, EMPTY);
            assertTrue(client.bridge.accept(GRANT, ethernet(PEER_MAC, PEER, oldSyn)));
            client.bridge.revokePeer(GRANT.rid());
            client
                    .syn(); // New transport epoch queued while the selector is still in its input
                            // batch.
            release.countDown();
            client.finishHandshake();
            client.transfer(new byte[] {3, 5, 9, 12}, false);
            await(() -> backend.accepted.get() == 2);
            assertEquals(1, client.bridge.snapshot().connections());
            assertTrue(client.bridge.snapshot().rejectedFrames() >= 1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void handshakeDeadlineReleasesBackendAndMemory() throws Exception {
        var shortLimits =
                new VirtualTcpBridge.Limits(
                        2,
                        1,
                        64,
                        65536,
                        1024 * 1024,
                        Duration.ofSeconds(1),
                        Duration.ofMillis(200),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(100),
                        2);
        CountDownLatch closed = new CountDownLatch(1);
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.getInputStream().readAllBytes();
                                    closed.countDown();
                                });
                Client client = new Client(backend.address(), shortLimits)) {
            client.syn();
            assertEquals(SYN | ACK, client.take().flags());
            TcpSegment reset = client.take();
            assertEquals(RST | ACK, reset.flags());
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            await(() -> client.bridge.snapshot().connections() == 0);
            assertEquals(0, client.bridge.snapshot().bufferedBytes());
        }
    }

    @Test
    void backendRefusalProducesResetAndDoesNotLeakConnection() throws Exception {
        InetSocketAddress unused;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unused =
                    new InetSocketAddress(reservation.getInetAddress(), reservation.getLocalPort());
        }
        try (Client client = new Client(unused)) {
            client.syn();
            assertEquals(RST | ACK, client.take().flags());
            await(() -> client.bridge.snapshot().connections() == 0);
            assertEquals(0, client.bridge.snapshot().bufferedBytes());
            assertEquals(1, client.bridge.snapshot().backendFailures());
        }
    }

    @Test
    void rejectsNonLoopbackBackends() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new VirtualTcpBridge(
                                LOCAL,
                                LOCAL_MAC,
                                PORT,
                                new InetSocketAddress("192.0.2.1", 25565),
                                (rid, frame) -> {}));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new VirtualTcpBridge(
                                LOCAL,
                                LOCAL_MAC,
                                PORT,
                                InetSocketAddress.createUnresolved("localhost", 25565),
                                (rid, frame) -> {}));
    }

    @Test
    void perPeerAndGlobalConnectionLimitsPreventBackendAmplification() throws Exception {
        var limits =
                new VirtualTcpBridge.Limits(
                        2,
                        1,
                        64,
                        65536,
                        1024 * 1024,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(100),
                        2);
        BlockingQueue<TcpSegment> responses = new LinkedBlockingQueue<>();
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                VirtualTcpBridge bridge =
                        new VirtualTcpBridge(
                                LOCAL,
                                LOCAL_MAC,
                                PORT,
                                backend.address(),
                                limits,
                                (rid, frame) -> {
                                    var ip = Ipv4Packet.parse(EthernetFrame.parse(frame).payload());
                                    responses.add(
                                            TcpSegment.parse(
                                                    ip.tcpBytes(),
                                                    ip.sourceIp(),
                                                    ip.destinationIp()));
                                })) {
            java.util.function.BiConsumer<Long, Integer> syn =
                    (rid, port) -> {
                        var grant =
                                new InboundFirewall.PeerGrant(
                                        rid, true, Set.of("synthetic"), PEER, PEER_MAC);
                        var tcp = new TcpSegment(port, PORT, 100, 0, SYN, 32768, EMPTY, EMPTY);
                        assertTrue(bridge.accept(grant, ethernet(PEER_MAC, PEER, tcp)));
                    };
            syn.accept(1234L, 40000);
            assertEquals(
                    SYN | ACK, Objects.requireNonNull(responses.poll(5, TimeUnit.SECONDS)).flags());
            syn.accept(1234L, 40001);
            assertEquals(
                    RST | ACK, Objects.requireNonNull(responses.poll(5, TimeUnit.SECONDS)).flags());
            syn.accept(2345L, 40000);
            assertEquals(
                    SYN | ACK, Objects.requireNonNull(responses.poll(5, TimeUnit.SECONDS)).flags());
            syn.accept(3456L, 40000);
            assertEquals(
                    RST | ACK, Objects.requireNonNull(responses.poll(5, TimeUnit.SECONDS)).flags());
            await(() -> backend.accepted.get() == 2);
            assertEquals(2, bridge.snapshot().connections());
            assertEquals(2 * 65536, bridge.snapshot().bufferedBytes());
            bridge.revokePeer(1234);
            await(() -> bridge.snapshot().connections() == 1);
            syn.accept(3456L, 40000);
            assertEquals(
                    SYN | ACK, Objects.requireNonNull(responses.poll(5, TimeUnit.SECONDS)).flags());
            await(() -> backend.accepted.get() == 3);
        }
    }

    @Test
    void timeWaitFlowsDoNotConsumeAdmissionQuotas() throws Exception {
        var d = VirtualTcpBridge.Limits.defaults();
        var limits =
                new VirtualTcpBridge.Limits(
                        3,
                        2,
                        64,
                        65536,
                        1024 * 1024,
                        d.connectTimeout(),
                        d.handshakeTimeout(),
                        Duration.ofSeconds(10),
                        d.halfCloseTimeout(),
                        Duration.ofSeconds(30),
                        4);
        BlockingQueue<TcpSegment> responses = new LinkedBlockingQueue<>();
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                VirtualTcpBridge bridge =
                        new VirtualTcpBridge(
                                LOCAL,
                                LOCAL_MAC,
                                PORT,
                                backend.address(),
                                limits,
                                (rid, frame) -> {
                                    var ip = Ipv4Packet.parse(EthernetFrame.parse(frame).payload());
                                    responses.add(
                                            TcpSegment.parse(
                                                    ip.tcpBytes(),
                                                    ip.sourceIp(),
                                                    ip.destinationIp()));
                                })) {
            closeCleanly(bridge, responses, GRANT.rid(), 41000);
            closeCleanly(bridge, responses, GRANT.rid(), 41001);
            await(
                    () ->
                            bridge.state(GRANT.rid(), 41000)
                                            == VirtualTcpBridge.TcpState.TIME_WAIT
                                    && bridge.state(GRANT.rid(), 41001)
                                            == VirtualTcpBridge.TcpState.TIME_WAIT);
            assertEquals(2, bridge.snapshot().connections());
            sendTcp(bridge, GRANT, 41002, 7000, 0, SYN, EMPTY);
            assertEquals(SYN | ACK, take(responses).flags());
            await(() -> backend.accepted.get() == 3);
            assertEquals(0, bridge.snapshot().resets());
        }
    }

    @Test
    void timeWaitTombstonesAreBoundedAndStillAnswerFinRetransmission() throws Exception {
        var d = VirtualTcpBridge.Limits.defaults();
        var limits =
                new VirtualTcpBridge.Limits(
                        2,
                        2,
                        64,
                        65536,
                        1024 * 1024,
                        d.connectTimeout(),
                        d.handshakeTimeout(),
                        Duration.ofSeconds(10),
                        d.halfCloseTimeout(),
                        Duration.ofSeconds(30),
                        4);
        BlockingQueue<TcpSegment> responses = new LinkedBlockingQueue<>();
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                VirtualTcpBridge bridge =
                        new VirtualTcpBridge(
                                LOCAL,
                                LOCAL_MAC,
                                PORT,
                                backend.address(),
                                limits,
                                (rid, frame) -> {
                                    var ip = Ipv4Packet.parse(EthernetFrame.parse(frame).payload());
                                    responses.add(
                                            TcpSegment.parse(
                                                    ip.tcpBytes(),
                                                    ip.sourceIp(),
                                                    ip.destinationIp()));
                                })) {
            long lastRid = 0;
            int lastPort = 0;
            long lastFin = 0;
            for (int i = 0; i < 10; i++) {
                lastRid = 8000L + i;
                lastPort = 43000 + i;
                lastFin = closeCleanly(bridge, responses, lastRid, lastPort);
            }
            int retained = 0;
            for (int i = 0; i < 10; i++)
                if (bridge.state(8000L + i, 43000 + i) == VirtualTcpBridge.TcpState.TIME_WAIT)
                    retained++;
            assertEquals(limits.maxConnections(), retained);
            sendTcp(
                    bridge,
                    new InboundFirewall.PeerGrant(
                            lastRid, true, Set.of("synthetic"), PEER, PEER_MAC),
                    lastPort,
                    lastFin,
                    0,
                    FIN | ACK,
                    EMPTY);
            assertEquals(ACK, take(responses).flags());
        }
    }

    @Test
    void stalledBackendClosesReceiveWindowWithinGlobalBufferLimitThenResumes() throws Exception {
        byte[] input = new byte[2 * 1024 * 1024 + 17];
        new Random(812).nextBytes(input);
        CountDownLatch readAllowed = new CountDownLatch(1), readDone = new CountDownLatch(1);
        ByteArrayOutputStream backendReceived = new ByteArrayOutputStream();
        var limits =
                new VirtualTcpBridge.Limits(
                        2,
                        1,
                        2048,
                        65536,
                        131072,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(10),
                        Duration.ofMillis(100),
                        4);
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.setReceiveBufferSize(8192);
                                    assertTrue(readAllowed.await(10, TimeUnit.SECONDS));
                                    socket.getInputStream().transferTo(backendReceived);
                                    readDone.countDown();
                                });
                Client client = new Client(backend.address(), limits)) {
            client.handshake();
            int offset = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean stopped = false;
            while (!stopped) {
                assertTrue(
                        System.nanoTime() < deadline, "backend did not apply bounded backpressure");
                int credit = (int) Math.max(0, client.serverRight - client.next);
                while (offset < input.length && credit > 0) {
                    int size = Math.min(Math.min(1460, credit), input.length - offset);
                    client.send(
                            client.next,
                            client.serverNext,
                            ACK | PSH,
                            EMPTY,
                            Arrays.copyOfRange(input, offset, offset + size));
                    client.next += size;
                    offset += size;
                    credit -= size;
                }
                TcpSegment packet = client.take();
                client.process(packet);
                assertTrue(
                        client.bridge.snapshot().bufferedBytes() <= limits.globalBufferedBytes());
                stopped = packet.window() == 0 && packet.acknowledgement() == wire(client.next);
                assertTrue(offset < input.length, "test payload too small to reach backpressure");
            }
            assertEquals(131072, client.bridge.snapshot().bufferedBytes());
            readAllowed.countDown();
            while (offset < input.length || client.acknowledged < client.next) {
                int credit = (int) Math.max(0, client.serverRight - client.next);
                while (offset < input.length && credit > 0) {
                    int size = Math.min(Math.min(1460, credit), input.length - offset);
                    client.send(
                            client.next,
                            client.serverNext,
                            ACK | PSH,
                            EMPTY,
                            Arrays.copyOfRange(input, offset, offset + size));
                    client.next += size;
                    offset += size;
                    credit -= size;
                }
                client.pump();
                assertTrue(
                        client.bridge.snapshot().bufferedBytes() <= limits.globalBufferedBytes());
            }
            client.send(client.next, client.serverNext, ACK | FIN, EMPTY, EMPTY);
            client.next++;
            assertTrue(readDone.await(10, TimeUnit.SECONDS));
            assertArrayEquals(input, backendReceived.toByteArray());
        }
    }

    @Test
    void pendingSynCapExcludesEstablishedFlowsAndTimeoutReleasesItsSlot() throws Exception {
        var limits =
                new VirtualTcpBridge.Limits(
                        8,
                        8,
                        64,
                        65536,
                        2 * 1024 * 1024,
                        Duration.ofSeconds(1),
                        Duration.ofMillis(400),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(100),
                        2,
                        1);
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                Client client = new Client(backend.address(), limits)) {
            client.handshake();
            await(
                    () ->
                            client.bridge.state(GRANT.rid(), CLIENT_PORT)
                                    == VirtualTcpBridge.TcpState.ESTABLISHED);
            java.util.function.IntConsumer syn =
                    port -> {
                        var tcp = new TcpSegment(port, PORT, 99, 0, SYN, 32768, EMPTY, EMPTY);
                        assertTrue(client.bridge.accept(GRANT, ethernet(PEER_MAC, PEER, tcp)));
                    };
            syn.accept(CLIENT_PORT + 1);
            assertEquals(SYN | ACK, client.take().flags());
            syn.accept(CLIENT_PORT + 2);
            TcpSegment rejected = client.take();
            assertEquals(RST | ACK, rejected.flags());
            assertEquals(CLIENT_PORT + 2, rejected.destinationPort());
            assertEquals(2, client.bridge.snapshot().connections());
            await(() -> backend.accepted.get() == 2);
            TcpSegment expired = client.take();
            assertEquals(RST | ACK, expired.flags());
            assertEquals(CLIENT_PORT + 1, expired.destinationPort());
            await(() -> client.bridge.snapshot().connections() == 1);
            syn.accept(CLIENT_PORT + 2);
            assertEquals(SYN | ACK, client.take().flags());
            await(() -> backend.accepted.get() == 3);
            assertEquals(
                    VirtualTcpBridge.TcpState.ESTABLISHED,
                    client.bridge.state(GRANT.rid(), CLIENT_PORT));
            assertEquals(2, client.bridge.snapshot().connections());
        }
    }

    @Test
    void passiveClosePublishesCloseWaitLastAckTimeWaitAndClosedStates() throws Exception {
        CountDownLatch eof = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.getInputStream().readAllBytes();
                                    eof.countDown();
                                    assertTrue(finish.await(5, TimeUnit.SECONDS));
                                });
                Client client = new Client(backend.address())) {
            assertEquals(
                    VirtualTcpBridge.TcpState.LISTEN,
                    client.bridge.state(GRANT.rid(), CLIENT_PORT));
            client.handshake();
            client.send(client.next, client.serverNext, FIN | ACK, EMPTY, EMPTY);
            client.next++;
            assertTrue(eof.await(5, TimeUnit.SECONDS));
            await(
                    () ->
                            client.bridge.state(GRANT.rid(), CLIENT_PORT)
                                    == VirtualTcpBridge.TcpState.CLOSE_WAIT);
            finish.countDown();
            TcpSegment fin;
            do {
                fin = client.take();
            } while ((fin.flags() & FIN) == 0);
            await(
                    () ->
                            client.bridge.state(GRANT.rid(), CLIENT_PORT)
                                    == VirtualTcpBridge.TcpState.LAST_ACK);
            client.process(fin);
            await(
                    () ->
                            client.bridge.state(GRANT.rid(), CLIENT_PORT)
                                    == VirtualTcpBridge.TcpState.TIME_WAIT);
            client.bridge.close();
            assertEquals(
                    VirtualTcpBridge.TcpState.CLOSED,
                    client.bridge.state(GRANT.rid(), CLIENT_PORT));
            assertEquals(0, client.bridge.snapshot().connections());
        } finally {
            finish.countDown();
        }
    }

    private static void sendTcp(
            VirtualTcpBridge bridge,
            InboundFirewall.PeerGrant grant,
            int port,
            long sequence,
            long acknowledgement,
            int flags,
            byte[] payload) {
        TcpSegment tcp =
                new TcpSegment(port, PORT, sequence, acknowledgement, flags, 32768, EMPTY, payload);
        assertTrue(bridge.accept(grant, ethernet(PEER_MAC, PEER, tcp)));
    }

    private static TcpSegment take(BlockingQueue<TcpSegment> wire) throws InterruptedException {
        TcpSegment packet = wire.poll(5, TimeUnit.SECONDS);
        assertNotNull(packet, "virtual endpoint stopped responding");
        return packet;
    }

    /** Runs a handshake and clean close, returning the sequence number of the client FIN. */
    private static long closeCleanly(
            VirtualTcpBridge bridge, BlockingQueue<TcpSegment> wire, long rid, int port)
            throws InterruptedException {
        var grant = new InboundFirewall.PeerGrant(rid, true, Set.of("synthetic"), PEER, PEER_MAC);
        long sequence = 7000;
        sendTcp(bridge, grant, port, sequence, 0, SYN, EMPTY);
        sequence++;
        TcpSegment synAck = take(wire);
        assertEquals(SYN | ACK, synAck.flags());
        long serverNext = synAck.sequence() + 1;
        sendTcp(bridge, grant, port, sequence, serverNext, ACK, EMPTY);
        long finSequence = sequence;
        sendTcp(bridge, grant, port, finSequence, serverNext, FIN | ACK, EMPTY);
        sequence++;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bridge.state(rid, port) != VirtualTcpBridge.TcpState.TIME_WAIT) {
            assertTrue(System.nanoTime() < deadline, "clean close did not reach TIME_WAIT");
            TcpSegment packet = wire.poll(50, TimeUnit.MILLISECONDS);
            if (packet == null) continue;
            if ((packet.flags() & FIN) != 0) {
                serverNext = packet.sequence() + 1;
                sendTcp(bridge, grant, port, sequence, serverNext, ACK, EMPTY);
            }
        }
        wire.clear();
        return finSequence;
    }

    private static byte[] ethernet(byte[] mac, int ip, TcpSegment tcp) {
        return new EthernetFrame(
                        LOCAL_MAC,
                        mac,
                        EthernetFrame.IPV4,
                        new Ipv4Packet(ip, LOCAL, 64, tcp.encode(ip, LOCAL)).encode())
                .encode();
    }

    @FunctionalInterface
    interface Check {
        boolean ok();
    }

    private static void await(Check check) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.ok() && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(check.ok());
    }
}
