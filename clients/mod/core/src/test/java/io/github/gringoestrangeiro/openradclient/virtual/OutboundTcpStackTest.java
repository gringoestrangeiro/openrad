package io.github.gringoestrangeiro.openradclient.virtual;

import static io.github.gringoestrangeiro.openradplugin.link.TcpSegment.*;
import static io.github.gringoestrangeiro.openradplugin.virtual.TcpSequence.wire;
import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.link.*;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OutboundTcpStackTest {
    private static final Inet4Address LOCAL = address("26.1.2.3"), PEER = address("26.4.5.6");
    private static final int LOCAL_IP = 0x1a010203, PEER_IP = 0x1a040506;
    private static final byte[] EMPTY = new byte[0];

    @Test void handshakeRetransmitsAndRejectsWrongAcknowledgement() throws Exception {
        try (Harness h = new Harness()) {
            h.start();
            TcpSegment syn = h.syn;
            h.advance(Duration.ofSeconds(1));
            assertEquals(syn.sequence(), h.take().getFirst().sequence());
            h.inject(h.remoteSequence, wire(syn.sequence() + 2), SYN | ACK, 65535, EMPTY, h.mss(1460));
            assertFalse(h.connecting.isDone());
            h.establish(65535, 1460);
            TcpSegment ack = h.take().getLast();
            assertEquals(ACK, ack.flags());
            assertEquals(wire(h.remoteSequence), ack.acknowledgement());
            h.inject(h.remoteSequence - 1, h.localSequence, SYN | ACK, 65535, EMPTY, h.mss(1460));
            assertEquals(ACK, h.take().getLast().flags(), "repeated SYN/ACK recovers a lost final ACK");
        }
    }

    @Test void sendsMssSegmentsAndReassemblesOverlapsAcrossSequenceWrap() throws Exception {
        try (Harness h = new Harness(0xfffffff0L)) {
            h.connected(65535, 128);
            byte[] outgoing = pattern(1000);
            h.connection.output().write(outgoing);
            List<TcpSegment> sent = h.take();
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            for (TcpSegment part : sent) {
                assertTrue(part.payload().length <= 128);
                actual.write(part.payload());
            }
            assertArrayEquals(outgoing, actual.toByteArray());
            h.localSequence += outgoing.length;
            h.inject(h.remoteSequence, h.localSequence, ACK, 65535, EMPTY, EMPTY);
            h.connection.output().flush();
            byte[] incoming = pattern(512);
            h.inject(h.remoteSequence + 200, h.localSequence, ACK | PSH, 65535,
                    Arrays.copyOfRange(incoming, 200, 400), EMPTY);
            assertEquals(0, h.connection.input().available());
            h.inject(h.remoteSequence + 200, h.localSequence, ACK | PSH, 65535,
                    Arrays.copyOfRange(incoming, 200, 400), EMPTY);
            h.inject(h.remoteSequence, h.localSequence, ACK | PSH, 65535,
                    Arrays.copyOfRange(incoming, 0, 300), EMPTY);
            assertEquals(400, h.connection.input().available());
            h.inject(h.remoteSequence + 400, h.localSequence, ACK | PSH, 65535,
                    Arrays.copyOfRange(incoming, 400, 512), EMPTY);
            assertArrayEquals(incoming, h.connection.input().readNBytes(512));
            assertEquals(wire(h.remoteSequence + 512), h.take().getLast().acknowledgement());
        }
    }

    @Test void retransmitsUnacknowledgedDataAndTrimsPartialAcknowledgements() throws Exception {
        try (Harness h = new Harness()) {
            h.connected(65535, 1460);
            byte[] bytes = pattern(1000);
            h.connection.output().write(bytes);
            TcpSegment first = h.take().getFirst();
            h.inject(h.remoteSequence, h.localSequence + 400, ACK, 65535, EMPTY, EMPTY);
            h.advance(Duration.ofSeconds(1));
            TcpSegment retry = h.take().getFirst();
            assertEquals(wire(first.sequence() + 400), retry.sequence());
            assertArrayEquals(Arrays.copyOfRange(bytes, 400, 1000), retry.payload());
            h.localSequence += bytes.length;
            h.inject(h.remoteSequence, h.localSequence, ACK, 65535, EMPTY, EMPTY);
            h.connection.output().flush();
        }
    }

    @Test void zeroWindowPersistsAndWindowUpdateResumesPendingData() throws Exception {
        try (Harness h = new Harness()) {
            h.connected(0, 1460);
            h.connection.output().write(new byte[] {1, 2, 3});
            assertTrue(h.take().isEmpty());
            h.advance(Duration.ofSeconds(1));
            TcpSegment probe = h.take().getFirst();
            assertEquals(wire(h.localSequence - 1), probe.sequence());
            assertEquals(1, probe.payload().length);
            h.inject(h.remoteSequence, h.localSequence, ACK, 2, EMPTY, EMPTY);
            TcpSegment first = h.take().getFirst();
            assertArrayEquals(new byte[] {1, 2}, first.payload());
            h.localSequence += 2;
            h.inject(h.remoteSequence, h.localSequence, ACK, 2, EMPTY, EMPTY);
            assertArrayEquals(new byte[] {3}, h.take().getFirst().payload());
        }
    }

    @Test void receiveWindowIsBoundedAndReaderAdvertisesFreedSpace() throws Exception {
        try (Harness h = new Harness()) {
            h.connected(65535, 1460);
            byte[] data = pattern(65535);
            long sequence = h.remoteSequence;
            for (int offset = 0; offset < data.length; offset += 1460) {
                byte[] part = Arrays.copyOfRange(data, offset, Math.min(offset + 1460, data.length));
                h.inject(sequence + offset, h.localSequence, ACK | PSH, 65535, part, EMPTY);
            }
            assertEquals(65535, h.connection.input().available());
            assertEquals(0, h.take().getLast().window());
            h.inject(sequence + 65535, h.localSequence, ACK | PSH, 65535, pattern(1460), EMPTY);
            assertEquals(65535, h.connection.input().available(), "unread bytes cannot grow the receive buffer");
            assertArrayEquals(Arrays.copyOf(data, 1000), h.connection.input().readNBytes(1000));
            assertEquals(1000, h.take().getLast().window());
            assertArrayEquals(Arrays.copyOfRange(data, 1000, data.length),
                    h.connection.input().readNBytes(data.length - 1000));
        }
    }

    @Test void invalidOriginsChecksumsFragmentsAndUnopenedPortsNeverReachStream() throws Exception {
        try (Harness h = new Harness()) {
            h.connected(65535, 1460);
            byte[] valid = h.frame(h.remoteSequence, h.localSequence, ACK | PSH, 65535, new byte[] {42}, EMPTY);
            h.stack.acceptEthernet(address("26.4.5.7"), valid);
            byte[] wrongMac = valid.clone(); wrongMac[6] ^= 1;
            h.stack.acceptEthernet(PEER, wrongMac);
            byte[] wrongDestination = valid.clone(); wrongDestination[0] ^= 1;
            h.stack.acceptEthernet(PEER, wrongDestination);
            byte[] badTcp = valid.clone(); badTcp[badTcp.length - 1] ^= 1;
            h.stack.acceptEthernet(PEER, badTcp);
            byte[] badIp = valid.clone(); badIp[24] ^= 1;
            h.stack.acceptEthernet(PEER, badIp);
            byte[] fragmented = valid.clone(); fragmented[20] = 0x20;
            Checksum.put16(fragmented, 24, 0);
            Checksum.put16(fragmented, 24, Checksum.onesComplement(fragmented, 14, 20));
            h.stack.acceptEthernet(PEER, fragmented);
            TcpSegment tcp = h.parse(valid);
            byte[] unopenedIp = new Ipv4Packet(PEER_IP, LOCAL_IP, 64,
                    new TcpSegment(25566, tcp.destinationPort(), tcp.sequence(), tcp.acknowledgement(),
                            tcp.flags(), tcp.window(), EMPTY, tcp.payload()).encode(PEER_IP, LOCAL_IP)).encode();
            h.stack.acceptEthernet(PEER, new EthernetFrame(OpenRadAddress.mac(LOCAL_IP),
                    OpenRadAddress.mac(PEER_IP), EthernetFrame.IPV4, unopenedIp).encode());
            assertEquals(0, h.connection.input().available());
            h.stack.acceptEthernet(PEER, valid);
            assertEquals(42, h.connection.input().read());
        }
    }

    @Test void finWaitsForMissingBytesAndSupportsIndependentOutputHalfClose() throws Exception {
        try (Harness h = new Harness()) {
            h.connected(65535, 1460);
            h.inject(h.remoteSequence + 3, h.localSequence, ACK | FIN, 65535, EMPTY, EMPTY);
            assertEquals(0, h.connection.input().available());
            assertEquals(wire(h.remoteSequence), h.take().getLast().acknowledgement());
            h.inject(h.remoteSequence, h.localSequence, ACK | PSH, 65535, new byte[] {4, 5, 6}, EMPTY);
            assertArrayEquals(new byte[] {4, 5, 6}, h.connection.input().readNBytes(3));
            assertEquals(-1, h.connection.input().read());
            h.remoteSequence += 4;
            h.take();
            h.connection.output().write(new byte[] {7, 8});
            assertArrayEquals(new byte[] {7, 8}, h.take().getFirst().payload());
            h.localSequence += 2;
            h.inject(h.remoteSequence, h.localSequence, ACK, 65535, EMPTY, EMPTY);
            h.connection.shutdownOutput();
            TcpSegment fin = h.take().getLast();
            assertEquals(ACK | FIN, fin.flags());
            h.inject(h.remoteSequence, ++h.localSequence, ACK, 65535, EMPTY, EMPTY);
            h.advance(Duration.ofSeconds(31));
            assertEquals(0, h.stack.connectionCount());
        }
    }

    @Test void rejectsSpoofedResetButHonorsExactSequenceReset() throws Exception {
        try (Harness h = new Harness()) {
            h.connected(65535, 1460);
            h.inject(h.remoteSequence + 1, h.localSequence, RST | ACK, 65535, EMPTY, EMPTY);
            assertEquals(1, h.stack.connectionCount());
            assertEquals(ACK, h.take().getLast().flags());
            h.inject(h.remoteSequence, h.localSequence, RST | ACK, 65535, EMPTY, EMPTY);
            assertEquals(0, h.stack.connectionCount());
            assertThrows(SocketException.class, () -> h.connection.input().read());
        }
    }

    @Test void boundsConcurrentConnectionsAndRevocationUnblocksPendingWrites() throws Exception {
        try (Harness h = new Harness()) {
            h.connected(0, 1460);
            assertThrows(SocketException.class,
                    () -> h.stack.connect(PEER, 25566, Duration.ofSeconds(1)));
            AtomicReference<Thread> writerThread = new AtomicReference<>();
            Future<?> writer = h.executor.submit(() -> {
                writerThread.set(Thread.currentThread());
                assertThrows(SocketException.class, () -> h.connection.output().write(new byte[65537]));
            });
            await(() -> writerThread.get() != null
                    && writerThread.get().getState() == Thread.State.TIMED_WAITING);
            h.stack.closePeer(PEER);
            writer.get(3, TimeUnit.SECONDS);
            assertEquals(0, h.stack.connectionCount());
            assertThrows(SocketException.class, () -> h.connection.input().read());
        }
    }

    @Test void bindsTheMacAuthenticatedByPeerNegotiationAndRevokesChangedIdentity() throws Exception {
        try (Harness h = new Harness()) {
            byte[] custom = {2, 9, 8, 7, 6, 5};
            h.peerMac = custom.clone();
            h.stack.setPeerMac(PEER, custom);
            custom[1] = 99; // The transport cannot mutate the authenticated binding afterwards.
            h.start();
            assertArrayEquals(h.peerMac, h.lastEthernet.destinationMac());
            byte[] canonicalSynAck = h.frame(h.remoteSequence, h.localSequence, SYN | ACK,
                    65535, EMPTY, h.mss(1460));
            System.arraycopy(OpenRadAddress.mac(PEER_IP), 0, canonicalSynAck, 6, 6);
            h.stack.acceptEthernet(PEER, canonicalSynAck);
            assertFalse(h.connecting.isDone());
            h.establish(65535, 1460);
            h.stack.setPeerMac(PEER, new byte[] {2, 5, 6, 7, 8, 9});
            assertEquals(0, h.stack.connectionCount());
            assertThrows(SocketException.class, () -> h.connection.output().write(1));
            assertThrows(IllegalArgumentException.class,
                    () -> h.stack.setPeerMac(PEER, new byte[] {1, 0, 0, 0, 0, 0}));
            assertThrows(IllegalArgumentException.class,
                    () -> h.stack.setPeerMac(PEER, new byte[6]));
        }
    }

    @Test void timeoutCancellationAndStackShutdownReleaseHandshakes() throws Exception {
        try (Harness h = new Harness()) {
            h.start();
            h.advance(Duration.ofSeconds(21));
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> h.connecting.get(3, TimeUnit.SECONDS));
            assertInstanceOf(SocketTimeoutException.class, error.getCause());
            assertEquals(0, h.stack.connectionCount());
        }
        try (Harness h = new Harness()) {
            h.start();
            h.connecting.cancel(true);
            await(() -> h.stack.connectionCount() == 0);
        }
        try (Harness h = new Harness()) {
            h.start();
            h.stack.close();
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> h.connecting.get(3, TimeUnit.SECONDS));
            assertInstanceOf(SocketException.class, error.getCause());
        }
    }

    @Test void neverAcceptsPhysicalOrInvalidDestinations() throws Exception {
        try (Harness h = new Harness()) {
            for (String value : List.of("127.0.0.1", "192.168.0.1", "1.1.1.1", "26.0.0.0", "26.255.255.255"))
                assertThrows(IllegalArgumentException.class,
                        () -> h.stack.connect(address(value), 25565, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class,
                    () -> h.stack.connect(PEER, 0, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class,
                    () -> h.stack.connect(LOCAL, 25565, Duration.ofSeconds(1)));
        }
    }

    private static byte[] pattern(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 31 + 7);
        return bytes;
    }

    private static Inet4Address address(String value) {
        try { return (Inet4Address) InetAddress.getByName(value); }
        catch (UnknownHostException e) { throw new AssertionError(e); }
    }

    @FunctionalInterface private interface Check { boolean get() throws Exception; }
    private static void await(Check condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.get()) {
            if (System.nanoTime() >= deadline) fail("condition did not become true");
            Thread.sleep(1);
        }
    }

    private static final class Harness implements AutoCloseable {
        final AtomicLong now = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
        final List<byte[]> sent = new ArrayList<>();
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final OutboundTcpStack stack = new OutboundTcpStack(LOCAL,
                (destination, frame) -> { assertEquals(PEER, destination); sent.add(frame); }, 1, now::get, false);
        long remoteSequence, localSequence;
        Future<OutboundTcpStack.Connection> connecting;
        OutboundTcpStack.Connection connection;
        TcpSegment syn;
        byte[] peerMac = OpenRadAddress.mac(PEER_IP);
        EthernetFrame lastEthernet;

        Harness() { this(123456789L); }
        Harness(long remoteSequence) { this.remoteSequence = remoteSequence; }
        void start() throws Exception {
            connecting = executor.submit(() -> stack.connect(PEER, 25565, Duration.ofSeconds(20)));
            await(() -> { stack.pumpFrames(); return !sent.isEmpty(); });
            syn = parse(sent.removeFirst());
            localSequence = wire(syn.sequence() + 1);
        }
        void establish(int window, int mss) throws Exception {
            inject(remoteSequence, localSequence, SYN | ACK, window, EMPTY, mss(mss));
            remoteSequence++;
            connection = connecting.get(3, TimeUnit.SECONDS);
        }
        void connected(int window, int mss) throws Exception {
            start(); establish(window, mss); take();
        }
        byte[] mss(int value) { return new byte[] {2, 4, (byte) (value >>> 8), (byte) value}; }
        void advance(Duration duration) { now.addAndGet(duration.toNanos()); stack.tick(); stack.pumpFrames(); }
        void inject(long sequence, long acknowledgement, int flags, int window, byte[] payload, byte[] options) {
            stack.acceptEthernet(PEER, frame(sequence, acknowledgement, flags, window, payload, options));
        }
        byte[] frame(long sequence, long acknowledgement, int flags, int window, byte[] payload, byte[] options) {
            TcpSegment tcp = new TcpSegment(25565, syn.sourcePort(), wire(sequence), wire(acknowledgement),
                    flags, window, options, payload);
            byte[] packet = new Ipv4Packet(PEER_IP, LOCAL_IP, 64, tcp.encode(PEER_IP, LOCAL_IP)).encode();
            return new EthernetFrame(OpenRadAddress.mac(LOCAL_IP), peerMac,
                    EthernetFrame.IPV4, packet).encode();
        }
        List<TcpSegment> take() {
            stack.pumpFrames();
            List<TcpSegment> result = sent.stream().map(this::parse).toList();
            sent.clear();
            return result;
        }
        TcpSegment parse(byte[] raw) {
            lastEthernet = EthernetFrame.parse(raw);
            Ipv4Packet packet = Ipv4Packet.parse(lastEthernet.payload());
            return TcpSegment.parse(packet.tcpBytes(), packet.sourceIp(), packet.destinationIp());
        }
        @Override public void close() { stack.close(); executor.shutdownNow(); }
    }
}
