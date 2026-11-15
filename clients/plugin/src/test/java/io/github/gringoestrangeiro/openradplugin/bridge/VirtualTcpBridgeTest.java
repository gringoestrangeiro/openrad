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
            this(backend, limits, null);
        }

        Client(
                InetSocketAddress backend,
                VirtualTcpBridge.Limits limits,
                VirtualOrigins origins)
                throws IOException {
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
                            },
                            origins);
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
    void backendSourcePortMapsToTheVirtualPlayerAddressUntilClose() throws Exception {
        VirtualOrigins origins = new VirtualOrigins();
        BlockingQueue<Optional<InetSocketAddress>> seen = new LinkedBlockingQueue<>();
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    seen.add(
                                            Optional.ofNullable(
                                                    origins.lookup(
                                                            socket.getRemoteSocketAddress())));
                                    socket.getInputStream().readAllBytes();
                                });
                Client client =
                        new Client(backend.address(), VirtualTcpBridge.Limits.defaults(), origins)) {
            client.handshake();
            Optional<InetSocketAddress> origin = seen.poll(5, TimeUnit.SECONDS);
            assertNotNull(origin, "backend never accepted");
            assertEquals(
                    new InetSocketAddress(
                            InetAddress.getByAddress(new byte[] {26, 1, 1, 3}), CLIENT_PORT),
                    origin.orElseThrow());
            assertEquals(1, origins.size());
            // Unrelated loopback clients and non-loopback addresses are never mapped.
            assertNull(origins.lookup(new InetSocketAddress(InetAddress.getLoopbackAddress(), 1)));
            assertNull(
                    origins.lookup(
                            new InetSocketAddress(
                                    InetAddress.getByAddress(new byte[] {10, 0, 0, 1}),
                                    ((InetSocketAddress) backend.clients.get(0)
                                                    .getRemoteSocketAddress())
                                            .getPort())));
        }
        assertEquals(0, origins.size());
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
                        Duration.ofSeconds(15),
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
            while (probes < 4) {
                TcpSegment packet = client.take();
                assertEquals(0, packet.flags() & RST);
                if (packet.payload().length == 1 && packet.sequence() == wire(client.serverNext)) {
                    probes++;
                    // A real zero-window receiver responds to every persist probe. These
                    // repeated ACKs must not trigger fast retransmit or spend its retry budget.
                    client.ack();
                } else
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
    void freshSynReusingATimeWaitPortOpensANewFlow() throws Exception {
        BlockingQueue<TcpSegment> responses = new LinkedBlockingQueue<>();
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                VirtualTcpBridge bridge =
                        new VirtualTcpBridge(
                                LOCAL,
                                LOCAL_MAC,
                                PORT,
                                backend.address(),
                                VirtualTcpBridge.Limits.defaults(),
                                (rid, frame) -> {
                                    var ip = Ipv4Packet.parse(EthernetFrame.parse(frame).payload());
                                    responses.add(
                                            TcpSegment.parse(
                                                    ip.tcpBytes(),
                                                    ip.sourceIp(),
                                                    ip.destinationIp()));
                                })) {
            long rid = 9100;
            int port = 44100;
            var grant =
                    new InboundFirewall.PeerGrant(rid, true, Set.of("synthetic"), PEER, PEER_MAC);
            closeCleanly(bridge, responses, rid, port);
            // A duplicate of the old SYN stays absorbed by the tombstone.
            sendTcp(bridge, grant, port, 7000, 0, SYN, EMPTY);
            assertNull(responses.poll(300, TimeUnit.MILLISECONDS));
            assertEquals(VirtualTcpBridge.TcpState.TIME_WAIT, bridge.state(rid, port));
            // A new connection from the same source port is accepted immediately.
            sendTcp(bridge, grant, port, 90_000, 0, SYN, EMPTY);
            TcpSegment synAck = take(responses);
            assertEquals(SYN | ACK, synAck.flags());
            assertEquals(90_001, synAck.acknowledgement());
            await(() -> backend.accepted.get() == 2);
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

    private static VirtualTcpBridge.Limits halfOpenLimits(int perPeer, Duration handshake) {
        return new VirtualTcpBridge.Limits(
                8,
                perPeer,
                64,
                65536,
                2 * 1024 * 1024,
                Duration.ofSeconds(2),
                handshake,
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                Duration.ofMillis(100),
                2,
                8);
    }

    private static VirtualTcpBridge routedBridge(
            Backend backend,
            VirtualTcpBridge.Limits limits,
            Map<Long, BlockingQueue<TcpSegment>> replies)
            throws IOException {
        return routedBridge(backend, limits, replies, line -> {});
    }

    private static VirtualTcpBridge routedBridge(
            Backend backend,
            VirtualTcpBridge.Limits limits,
            Map<Long, BlockingQueue<TcpSegment>> replies,
            java.util.function.Consumer<String> log)
            throws IOException {
        return new VirtualTcpBridge(
                LOCAL,
                LOCAL_MAC,
                PORT,
                backend.address(),
                limits,
                (rid, frame) -> {
                    var ip = Ipv4Packet.parse(EthernetFrame.parse(frame).payload());
                    replies.computeIfAbsent(rid, key -> new LinkedBlockingQueue<>())
                            .add(TcpSegment.parse(ip.tcpBytes(), ip.sourceIp(), ip.destinationIp()));
                },
                null,
                log);
    }

    /** Completes a handshake and returns the next client sequence and server sequence. */
    private static long[] establish(
            VirtualTcpBridge bridge,
            BlockingQueue<TcpSegment> wire,
            InboundFirewall.PeerGrant grant,
            int port)
            throws InterruptedException {
        sendTcp(bridge, grant, port, 7000, 0, SYN, EMPTY);
        TcpSegment synAck = take(wire);
        assertEquals(SYN | ACK, synAck.flags());
        long serverNext = synAck.sequence() + 1;
        sendTcp(bridge, grant, port, 7001, serverNext, ACK, EMPTY);
        return new long[] {7001, serverNext};
    }

    private static TcpSegment takeWhere(
            BlockingQueue<TcpSegment> wire, java.util.function.Predicate<TcpSegment> wanted)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            TcpSegment segment = wire.poll(50, TimeUnit.MILLISECONDS);
            if (segment != null && wanted.test(segment)) return segment;
        }
        return fail("expected segment never arrived");
    }

    @Test
    void faultsInOneConnectionResetOnlyThatConnectionAreLoggedAndLeaveOthersRunning()
            throws Exception {
        Map<Long, BlockingQueue<TcpSegment>> replies = new ConcurrentHashMap<>();
        List<String> logs = new CopyOnWriteArrayList<>();
        try (Backend backend =
                        new Backend(socket -> socket.getInputStream().transferTo(socket.getOutputStream()));
                VirtualTcpBridge bridge =
                        routedBridge(backend, VirtualTcpBridge.Limits.defaults(), replies, logs::add)) {
            var grant = grantFor(GRANT.rid());
            var wire = replies.computeIfAbsent(GRANT.rid(), k -> new LinkedBlockingQueue<>());
            long[] healthy = establish(bridge, wire, grant, 44000);
            long[] doomed = establish(bridge, wire, grant, 44001);
            await(
                    () ->
                            bridge.state(GRANT.rid(), 44000) == VirtualTcpBridge.TcpState.ESTABLISHED
                                    && bridge.state(GRANT.rid(), 44001)
                                            == VirtualTcpBridge.TcpState.ESTABLISHED);
            bridge.faultProbe =
                    (where, rid, port) -> {
                        if (where.equals("segment") && (port == 44001 || port >= 45000))
                            throw new IllegalStateException("injected segment fault");
                    };
            sendTcp(bridge, grant, 44001, doomed[0], doomed[1], ACK | PSH, new byte[] {1, 2, 3});
            TcpSegment reset = takeWhere(wire, s -> (s.flags() & RST) != 0);
            assertEquals(44001, reset.destinationPort());
            await(() -> bridge.snapshot().connections() == 1);
            assertEquals(1, logs.size(), logs.toString());
            assertTrue(logs.get(0).contains("IllegalStateException"), logs.get(0));
            assertTrue(logs.get(0).contains("injected segment fault"), logs.get(0));
            assertTrue(logs.get(0).contains("port 44001"), logs.get(0));
            assertTrue(bridge.isRunning());

            // The untouched connection still echoes through the real backend.
            sendTcp(
                    bridge,
                    grant,
                    44000,
                    healthy[0],
                    healthy[1],
                    ACK | PSH,
                    "still alive".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            TcpSegment echo =
                    takeWhere(wire, s -> s.destinationPort() == 44000 && s.payloadLength() > 0);
            assertEquals(
                    "still alive",
                    new String(echo.payload(), java.nio.charset.StandardCharsets.US_ASCII));

            // Faults on a fresh SYN reset the peer too; reports are rate limited, not lost.
            for (int port = 45000; port < 45005; port++)
                sendTcp(bridge, grant, port, 100, 0, SYN, EMPTY);
            for (int i = 0; i < 5; i++) {
                TcpSegment answer = takeWhere(wire, s -> s.destinationPort() >= 45000);
                assertEquals(RST | ACK, answer.flags());
            }
            assertEquals(6, bridge.faults());
            assertEquals(1, logs.size(), "reports within the interval are rate limited");
            assertTrue(bridge.isRunning());
        }
    }

    @Test
    void timerFaultResetsOnlyTheAffectedConnection() throws Exception {
        Map<Long, BlockingQueue<TcpSegment>> replies = new ConcurrentHashMap<>();
        List<String> logs = new CopyOnWriteArrayList<>();
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                VirtualTcpBridge bridge =
                        routedBridge(backend, VirtualTcpBridge.Limits.defaults(), replies, logs::add)) {
            var grant = grantFor(GRANT.rid());
            var wire = replies.computeIfAbsent(GRANT.rid(), k -> new LinkedBlockingQueue<>());
            establish(bridge, wire, grant, 44000);
            establish(bridge, wire, grant, 44001);
            await(
                    () ->
                            bridge.state(GRANT.rid(), 44000) == VirtualTcpBridge.TcpState.ESTABLISHED
                                    && bridge.state(GRANT.rid(), 44001)
                                            == VirtualTcpBridge.TcpState.ESTABLISHED);
            bridge.faultProbe =
                    (where, rid, port) -> {
                        if (where.equals("timer") && port == 44001)
                            throw new IllegalArgumentException("injected timer fault");
                    };
            TcpSegment reset = takeWhere(wire, s -> (s.flags() & RST) != 0);
            assertEquals(44001, reset.destinationPort());
            await(() -> bridge.snapshot().connections() == 1);
            assertEquals(VirtualTcpBridge.TcpState.ESTABLISHED, bridge.state(GRANT.rid(), 44000));
            assertEquals(1, logs.size(), logs.toString());
            assertTrue(logs.get(0).contains("injected timer fault"), logs.get(0));
            assertTrue(bridge.isRunning());
        }
    }

    @Test
    void fatalErrorStopsTheBridgeVisiblyAndIsReported() throws Exception {
        Map<Long, BlockingQueue<TcpSegment>> replies = new ConcurrentHashMap<>();
        List<String> logs = new CopyOnWriteArrayList<>();
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                VirtualTcpBridge bridge =
                        routedBridge(backend, VirtualTcpBridge.Limits.defaults(), replies, logs::add)) {
            var grant = grantFor(GRANT.rid());
            var wire = replies.computeIfAbsent(GRANT.rid(), k -> new LinkedBlockingQueue<>());
            establish(bridge, wire, grant, 44000);
            assertTrue(bridge.isRunning());
            assertNull(bridge.failure());
            bridge.faultProbe =
                    (where, rid, port) -> {
                        throw new InternalError("injected fatal error");
                    };
            sendTcp(bridge, grant, 44000, 7001, 1, ACK, EMPTY);
            await(() -> !bridge.isRunning());
            assertInstanceOf(InternalError.class, bridge.failure());
            assertEquals(1, logs.size(), logs.toString());
            assertTrue(logs.get(0).contains("injected fatal error"), logs.get(0));
            assertTrue(logs.get(0).contains("stopped"), logs.get(0));
            assertEquals(0, bridge.snapshot().connections());
            assertFalse(
                    bridge.accept(
                            grant,
                            ethernet(
                                    PEER_MAC,
                                    PEER,
                                    new TcpSegment(1, PORT, 1, 0, SYN, 1, EMPTY, EMPTY))));
        }
    }

    @Test
    void bridgeWithOnlyTimeWaitTombstonesSleepsUntilTheirExpiryInsteadOfPolling()
            throws Exception {
        Map<Long, BlockingQueue<TcpSegment>> replies = new ConcurrentHashMap<>();
        var d = VirtualTcpBridge.Limits.defaults();
        var limits =
                new VirtualTcpBridge.Limits(
                        8,
                        8,
                        64,
                        65536,
                        2 * 1024 * 1024,
                        d.connectTimeout(),
                        d.handshakeTimeout(),
                        d.idleTimeout(),
                        d.halfCloseTimeout(),
                        Duration.ofMillis(1200),
                        4);
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                VirtualTcpBridge bridge = routedBridge(backend, limits, replies)) {
            var wire = replies.computeIfAbsent(GRANT.rid(), k -> new LinkedBlockingQueue<>());
            closeCleanly(bridge, wire, GRANT.rid(), 46000);
            await(() -> bridge.state(GRANT.rid(), 46000) == VirtualTcpBridge.TcpState.TIME_WAIT);
            long before = bridge.selectorPasses();
            Thread.sleep(500);
            long passes = bridge.selectorPasses() - before;
            System.out.println("TIME_WAIT-only selector passes in 500 ms: " + passes);
            assertTrue(
                    passes <= 5,
                    "selector polled " + passes + " times while only TIME_WAIT remained");
            long expiryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (bridge.snapshot().connections() != 0 && System.nanoTime() < expiryDeadline)
                Thread.sleep(10);
            assertEquals(0, bridge.snapshot().connections(), "TIME_WAIT tombstone never expired");
            assertEquals(0, bridge.snapshot().bufferedBytes());
        }
    }

    @Test
    void ordinaryPingLoginAndImmediateReconnectFlowsSucceedBesideHalfOpenAttackers()
            throws Exception {
        Map<Long, BlockingQueue<TcpSegment>> replies = new ConcurrentHashMap<>();
        var limits =
                new VirtualTcpBridge.Limits(
                        8,
                        2,
                        64,
                        65536,
                        2 * 1024 * 1024,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(100),
                        2,
                        8,
                        2);
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                VirtualTcpBridge bridge = routedBridge(backend, limits, replies)) {
            for (long rid = 100; rid < 103; rid++)
                for (int port = 40000; port < 40002; port++)
                    sendTcp(bridge, grantFor(rid), port, 500, 0, SYN, EMPTY);
            for (long rid = 100; rid < 103; rid++)
                for (int i = 0; i < 2; i++)
                    assertEquals(SYN | ACK, take(replies.computeIfAbsent(rid, k -> new LinkedBlockingQueue<>())).flags());
            assertEquals(6, bridge.snapshot().connections());
            var player = replies.computeIfAbsent(GRANT.rid(), k -> new LinkedBlockingQueue<>());
            closeCleanly(bridge, player, GRANT.rid(), 43000); // server-list ping
            closeCleanly(bridge, player, GRANT.rid(), 43001); // login
            closeCleanly(bridge, player, GRANT.rid(), 43002); // reconnect right away
            assertEquals(0, bridge.snapshot().resets(), "player flows must never be reset");
        }
    }

    private static InboundFirewall.PeerGrant grantFor(long rid) {
        return new InboundFirewall.PeerGrant(rid, true, Set.of("synthetic"), PEER, PEER_MAC);
    }

    @Test
    void halfOpenSynsPerPeerAreCappedSoFewMembersCannotExhaustTheBridge() throws Exception {
        // Eight slots, four per peer: before the half-open cap two members that never finish a
        // handshake held every slot. Each now keeps two, so a third and a fourth member still
        // get in.
        Map<Long, BlockingQueue<TcpSegment>> replies = new ConcurrentHashMap<>();
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                VirtualTcpBridge bridge =
                        routedBridge(backend, halfOpenLimits(4, Duration.ofSeconds(5)), replies)) {
            for (long rid = 100; rid < 103; rid++)
                for (int port = 40000; port < 40004; port++)
                    sendTcp(bridge, grantFor(rid), port, 500, 0, SYN, EMPTY);
            for (long rid = 100; rid < 103; rid++) {
                // Resets are immediate while SYN-ACKs follow the backend connect: compare as a set.
                List<Integer> flags = new ArrayList<>();
                for (int i = 0; i < 4; i++) flags.add(take(replies.computeIfAbsent(rid, k -> new LinkedBlockingQueue<>())).flags());
                assertEquals(2, flags.stream().filter(f -> f == (SYN | ACK)).count(), "member " + rid);
                assertEquals(2, flags.stream().filter(f -> f == (RST | ACK)).count(), "member " + rid);
            }
            assertEquals(6, bridge.snapshot().connections());
            sendTcp(bridge, grantFor(200), 41000, 700, 0, SYN, EMPTY);
            assertEquals(SYN | ACK, take(replies.computeIfAbsent(200L, k -> new LinkedBlockingQueue<>())).flags());
            await(() -> backend.accepted.get() == 7);
        }
    }

    @Test
    void completingAHandshakeFreesTheHalfOpenAllowanceOfThatMember() throws Exception {
        Map<Long, BlockingQueue<TcpSegment>> replies = new ConcurrentHashMap<>();
        try (Backend backend = new Backend(socket -> socket.getInputStream().readAllBytes());
                VirtualTcpBridge bridge =
                        routedBridge(backend, halfOpenLimits(4, Duration.ofSeconds(5)), replies)) {
            var grant = grantFor(300);
            var queue = replies.computeIfAbsent(300L, k -> new LinkedBlockingQueue<>());
            sendTcp(bridge, grant, 42000, 900, 0, SYN, EMPTY);
            TcpSegment first = take(queue);
            sendTcp(bridge, grant, 42001, 900, 0, SYN, EMPTY);
            assertEquals(SYN | ACK, take(queue).flags());
            sendTcp(bridge, grant, 42002, 900, 0, SYN, EMPTY);
            assertEquals(RST | ACK, take(queue).flags());
            sendTcp(bridge, grant, 42000, 901, wire(first.sequence() + 1), ACK, EMPTY);
            await(() -> bridge.state(300, 42000) == VirtualTcpBridge.TcpState.ESTABLISHED);
            sendTcp(bridge, grant, 42002, 900, 0, SYN, EMPTY);
            assertEquals(SYN | ACK, take(queue).flags());
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

    /** Data segments of the bridge until the wire stays quiet for {@code quietMillis}. */
    private static List<TcpSegment> dataSegments(Client client, long quietMillis)
            throws InterruptedException {
        List<TcpSegment> data = new ArrayList<>();
        for (TcpSegment segment;
                (segment = client.wire.poll(quietMillis, TimeUnit.MILLISECONDS)) != null; )
            if (segment.payloadLength() > 0) data.add(segment);
        return data;
    }

    @Test
    void partialAcknowledgementDuringLossRecoveryResendsTheNextHoleAtOnce() throws Exception {
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.getOutputStream().write(new byte[12000]);
                                    socket.getOutputStream().flush();
                                    socket.getInputStream().readAllBytes();
                                });
                Client client = new Client(backend.address())) {
            client.handshake();
            List<TcpSegment> burst = dataSegments(client, 300);
            assertTrue(burst.size() >= 6, "the initial window carries most of the 12000 bytes");
            TcpSegment first = burst.get(0), second = burst.get(1), third = burst.get(2);
            // Nothing is acknowledged: the retransmission timer resends the first hole.
            TcpSegment resent;
            do resent = client.take();
            while (resent.sequence() != first.sequence());
            long secondSequence = unwrap(second.sequence(), client.serverNext);
            // The first hole is filled, the second was lost as well: the bridge must not wait for
            // another (backed-off) timeout.
            client.send(client.next, secondSequence, ACK, EMPTY, EMPTY);
            long begin = System.nanoTime();
            TcpSegment next;
            do next = client.take();
            while (next.sequence() != second.sequence());
            // After the first timeout the RTO is at least 400 ms: only a partial ACK is this fast.
            assertTrue(
                    System.nanoTime() - begin < TimeUnit.MILLISECONDS.toNanos(300),
                    "the next hole was resent only after a timeout");
            client.send(client.next, unwrap(third.sequence(), client.serverNext), ACK, EMPTY, EMPTY);
            do next = client.take();
            while (next.sequence() != third.sequence());
            assertTrue(client.bridge.snapshot().retransmissions() >= 3);
        }
    }

    @Test
    void aWindowThatOpenedByLessThanASegmentWaitsWhileDataIsInFlight() throws Exception {
        try (Backend backend =
                        new Backend(
                                socket -> {
                                    socket.getOutputStream().write(new byte[5000]);
                                    socket.getOutputStream().flush();
                                    socket.getInputStream().readAllBytes();
                                });
                Client client = new Client(backend.address())) {
            client.advertisedWindow = 3000;
            client.handshake();
            // Quiet for less than the 200 ms minimum retransmission timeout: originals only.
            List<TcpSegment> burst = dataSegments(client, 120);
            assertEquals(
                    List.of(1460, 1460),
                    burst.stream().map(TcpSegment::payloadLength).toList(),
                    "the 80 bytes of window left over must not leave as a sliver");
        }
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
