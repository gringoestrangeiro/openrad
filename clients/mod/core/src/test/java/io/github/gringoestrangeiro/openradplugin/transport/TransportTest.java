package io.github.gringoestrangeiro.openradplugin.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.peer.PeerChannel;
import io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

class TransportTest {
    static Inet4Address ip(String value) throws Exception {
        return (Inet4Address) InetAddress.getByName(value);
    }

    static final Duration TIMEOUT = Duration.ofSeconds(8);

    static byte[] frame(int sequence) {
        byte[] f = new byte[1514];
        for (int i = 0; i < f.length; i++) f[i] = (byte) (i + sequence);
        return f;
    }

    @Test
    void tcpMutualAuthenticationNegotiatesAndExchangesEncryptedEthernet() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 16, ip("127.0.0.1"));
                ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Void> remote =
                    jobs.submit(
                            () -> {
                                try (FramedTcp stream = new FramedTcp(listener.accept(), TIMEOUT)) {
                                    stream.acceptRendezvous(11, 99);
                                    try (PeerChannel peer =
                                            PeerChannel.accept(
                                                    stream,
                                                    PeerChannel.Path.DIRECT_TCP,
                                                    "test-password".getBytes(),
                                                    22,
                                                    ip("26.0.0.2"),
                                                    11)) {
                                        assertEquals(8, peer.version());
                                        assertArrayEquals(
                                                TunnelCodec.mac(ip("26.0.0.1")), peer.peerMac());
                                        for (int i = 0; i < 20; i++) {
                                            assertTrue(peer.ready(TIMEOUT));
                                            var packet = (TunnelCodec.Frames) peer.receivePacket();
                                            assertArrayEquals(frame(i), packet.frames().getFirst());
                                            assertTrue(peer.sendFrame(frame(i + 1)));
                                        }
                                    }
                                }
                                return null;
                            });
            try (FramedTcp stream =
                    FramedTcp.connect(
                            new InetSocketAddress(ip("127.0.0.1"), listener.getLocalPort()),
                            TIMEOUT)) {
                stream.peerRendezvous(11, 99, 7);
                try (PeerChannel peer =
                        PeerChannel.connect(
                                stream,
                                PeerChannel.Path.DIRECT_TCP,
                                "test-password".getBytes(),
                                11,
                                ip("26.0.0.1"),
                                22)) {
                    for (int i = 0; i < 20; i++) {
                        assertTrue(peer.sendFrame(frame(i)));
                        assertArrayEquals(
                                frame(i + 1),
                                ((TunnelCodec.Frames) peer.receivePacket()).frames().getFirst());
                    }
                }
            }
            remote.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void udpMutualAuthenticationAndFragmentedFramesKeepOrderAndCipherChain() throws Exception {
        try (DatagramSocket a = new DatagramSocket(new InetSocketAddress(ip("127.0.0.1"), 0));
                DatagramSocket b = new DatagramSocket(new InetSocketAddress(ip("127.0.0.1"), 0));
                ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Void> remote =
                    jobs.submit(
                            () -> {
                                try (ReliableUdp udp =
                                                ReliableUdp.accept(
                                                        b,
                                                        List.of(
                                                                (InetSocketAddress)
                                                                        a.getLocalSocketAddress()),
                                                        81,
                                                        TIMEOUT);
                                        PeerChannel peer =
                                                PeerChannel.accept(
                                                        udp,
                                                        PeerChannel.Path.DIRECT_UDP,
                                                        "udp-password".getBytes(),
                                                        22,
                                                        ip("26.0.0.2"),
                                                        11)) {
                                    for (int i = 0; i < 200; i++) {
                                        assertArrayEquals(
                                                frame(i),
                                                ((TunnelCodec.Frames) peer.receivePacket())
                                                        .frames()
                                                        .getFirst());
                                        while (!peer.sendFrame(frame(i + 1)))
                                            peer.ready(Duration.ofMillis(10));
                                    }
                                    assertArrayEquals(new byte[] {42}, peer.receive());
                                }
                                return null;
                            });
            try (ReliableUdp udp =
                            ReliableUdp.connect(
                                    a,
                                    List.of((InetSocketAddress) b.getLocalSocketAddress()),
                                    81,
                                    TIMEOUT);
                    PeerChannel peer =
                            PeerChannel.connect(
                                    udp,
                                    PeerChannel.Path.DIRECT_UDP,
                                    "udp-password".getBytes(),
                                    11,
                                    ip("26.0.0.1"),
                                    22)) {
                for (int i = 0; i < 200; i++) {
                    while (!peer.sendFrame(frame(i))) peer.ready(Duration.ofMillis(10));
                    assertArrayEquals(
                            frame(i + 1),
                            ((TunnelCodec.Frames) peer.receivePacket()).frames().getFirst());
                }
                assertTrue(peer.send(new byte[] {42}));
            }
            remote.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void partialFramingCannotExceedDeadlineAndCloseInterruptsWait() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
                Socket client = new Socket("127.0.0.1", listener.getLocalPort());
                Socket accepted = listener.accept();
                FramedTcp stream = new FramedTcp(accepted, Duration.ofMillis(100))) {
            client.getOutputStream().write(new byte[] {0, 0, 0, 3, 1});
            long began = System.nanoTime();
            assertThrows(SocketTimeoutException.class, () -> stream.receive(10));
            assertTrue(System.nanoTime() - began < 1_000_000_000L);
        }
    }

    @Test
    void correlatedRendezvousRejectsWrongIdentityBeforeAcknowledgement() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
                Socket client = new Socket("127.0.0.1", listener.getLocalPort());
                Socket accepted = listener.accept();
                FramedTcp stream = new FramedTcp(accepted, TIMEOUT)) {
            client.getOutputStream()
                    .write(
                            ByteBuffer.allocate(24)
                                    .putInt(0x00140001)
                                    .putLong(19)
                                    .putLong(99)
                                    .putInt(7)
                                    .array());
            assertThrows(IOException.class, () -> stream.acceptRendezvous(11, 99));
            assertEquals(0, client.getInputStream().available());
        }
    }

    @Test
    void blockedWriteIsInterruptedByTotalSessionDeadline() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setReceiveBufferSize(1024);
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort());
                    Socket ignored = listener.accept()) {
                assertTrue(ignored.isConnected());
                client.setSendBufferSize(1024);
                try (FramedTcp stream = new FramedTcp(client, Duration.ofMillis(200))) {
                    long began = System.nanoTime();
                    assertThrows(IOException.class, () -> stream.send(new byte[4 * 1024 * 1024]));
                    assertTrue(System.nanoTime() - began < 2_000_000_000L);
                }
            }
        }
    }

    @Test
    void stalledWriteIsAbortedAboutFiveSecondsAfterItStarts() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setReceiveBufferSize(1024);
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort());
                    Socket ignored = listener.accept()) {
                assertTrue(ignored.isConnected());
                client.setSendBufferSize(1024);
                try (FramedTcp stream = new FramedTcp(client, Duration.ofMinutes(5))) {
                    long began = System.nanoTime();
                    assertThrows(IOException.class, () -> stream.send(new byte[4 * 1024 * 1024]));
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
                    assertTrue(elapsed >= 4_900, "aborted early after " + elapsed + " ms");
                    assertTrue(elapsed < 7_000, "aborted late after " + elapsed + " ms");
                }
            }
        }
    }

    @Test
    void completedWritesAreNeverAbortedByTheWatchdog() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
                Socket client = new Socket("127.0.0.1", listener.getLocalPort());
                Socket accepted = listener.accept();
                FramedTcp stream = new FramedTcp(client, Duration.ofMinutes(1));
                FramedTcp receiver = new FramedTcp(accepted, Duration.ofMinutes(1))) {
            for (int i = 0; i < 2_000; i++) {
                stream.send(new byte[] {(byte) i, 1, 2});
                assertEquals((byte) i, receiver.receive(16)[0]);
            }
            Thread.sleep(600);
            stream.send(new byte[] {9});
            assertArrayEquals(new byte[] {9}, receiver.receive(16));
        }
    }

    @Test
    void streamsLeaveTheWriteWatchdogWhenClosed() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            int before = FramedTcp.watchedStreams();
            Socket client = new Socket("127.0.0.1", listener.getLocalPort());
            try (Socket accepted = listener.accept()) {
                assertTrue(accepted.isConnected());
                FramedTcp stream = new FramedTcp(client, Duration.ofMinutes(1));
                stream.send(new byte[] {1});
                assertEquals(before + 1, FramedTcp.watchedStreams());
                stream.close();
                assertEquals(before, FramedTcp.watchedStreams());
            }
        }
    }

    @Test
    void writeWatchdogPrunesClosedSocketsAndExitsWhenIdle() throws Exception {
        FramedTcp.WriteWatchdog watchdog = new FramedTcp.WriteWatchdog();
        try (ServerSocket listener = new ServerSocket(0)) {
            Socket client = new Socket("127.0.0.1", listener.getLocalPort());
            try (Socket accepted = listener.accept();
                    FramedTcp stream = new FramedTcp(client, Duration.ofMinutes(1))) {
                assertTrue(accepted.isConnected());
                watchdog.add(stream);
                assertTrue(watchdog.running());
                assertEquals(1, watchdog.size());
                client.close();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (watchdog.running() && System.nanoTime() < until) Thread.sleep(20);
                assertFalse(watchdog.running(), "an idle watchdog must stop its thread");
                assertEquals(0, watchdog.size());
                watchdog.add(stream);
                assertTrue(watchdog.running(), "a later stream restarts the watchdog");
                watchdog.remove(stream);
            }
        }
    }

    @Test
    void relayPairsTicketBeforeMutualAuthentication() throws Exception {
        byte[] ticket = new byte[256];
        Arrays.fill(ticket, (byte) 19);
        try (ServerSocket listener = new ServerSocket(0);
                ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Void> relay =
                    jobs.submit(
                            () -> {
                                try (FramedTcp s = new FramedTcp(listener.accept(), TIMEOUT)) {
                                    var f =
                                            io.github.gringoestrangeiro.openradplugin.protocol
                                                    .TlvCodec.parse(s.receive(65536));
                                    assertArrayEquals(
                                            ticket,
                                            io.github.gringoestrangeiro.openradplugin.protocol
                                                    .TlvCodec.field(f, 0x090001ca));
                                    s.send(
                                            io.github.gringoestrangeiro.openradplugin.protocol
                                                    .TlvCodec.u32(0x010001df, 2));
                                    try (PeerChannel p =
                                            PeerChannel.accept(
                                                    s,
                                                    PeerChannel.Path.RELAY,
                                                    "relay-password".getBytes(),
                                                    22,
                                                    ip("26.0.0.2"),
                                                    11)) {
                                        assertArrayEquals(
                                                frame(1),
                                                ((TunnelCodec.Frames) p.receivePacket())
                                                        .frames()
                                                        .getFirst());
                                        p.sendFrame(frame(2));
                                    }
                                }
                                return null;
                            });
            try (FramedTcp s =
                            RelayTransport.pair(
                                    new InetSocketAddress("127.0.0.1", listener.getLocalPort()),
                                    ticket,
                                    TIMEOUT);
                    PeerChannel p =
                            PeerChannel.connect(
                                    s,
                                    PeerChannel.Path.RELAY,
                                    "relay-password".getBytes(),
                                    11,
                                    ip("26.0.0.1"),
                                    22)) {
                p.sendFrame(frame(1));
                assertArrayEquals(
                        frame(2), ((TunnelCodec.Frames) p.receivePacket()).frames().getFirst());
            }
            relay.get(10, TimeUnit.SECONDS);
        }
    }
}
