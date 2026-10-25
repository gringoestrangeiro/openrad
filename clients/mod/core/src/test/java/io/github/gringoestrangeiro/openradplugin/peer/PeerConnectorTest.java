package io.github.gringoestrangeiro.openradplugin.peer;

import static io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.SERVER_OP;
import static io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.operation;
import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.network.Membership;
import io.github.gringoestrangeiro.openradplugin.session.SyntheticControlService;
import io.github.gringoestrangeiro.openradplugin.transport.FramedTcp;
import io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

class PeerConnectorTest {
    static Inet4Address ip(String value) throws Exception {
        return (Inet4Address) InetAddress.getByName(value);
    }

    static byte[] server(int op) {
        return u32(SERVER_OP, op);
    }

    static byte[] offer(long rid) {
        return concat(
                server(11),
                u64(0x020001c1, 99),
                tlv(
                        0x1235,
                        concat(u64(0x020001e1, rid), tlv(0x0a0001cd, "peer-password".getBytes()))));
    }

    static byte[] candidates(int operation, int root, byte[] value) {
        return concat(server(operation), u64(0x020001c1, 99), tlv(root, value));
    }

    static void closeAll(List<AutoCloseable> resources) {
        for (AutoCloseable c : resources)
            try {
                c.close();
            } catch (Exception ignored) {
            }
    }

    @Test
    void coordinatorCandidateAddressesRejectInternalNetworks() throws Exception {
        InetAddress publicRoute = ip("148.113.190.78");
        assertEquals(ip("8.8.8.8"),
                PeerConnector.candidateAddress("8.8.8.8", publicRoute));
        for (String denied : List.of("10.0.0.1", "192.168.1.7", "172.16.2.3",
                "169.254.1.1", "100.64.0.1", "127.0.0.1",
                "198.18.0.1", "192.0.2.1", "224.0.0.1", "255.255.255.255")) {
            assertThrows(IllegalArgumentException.class,
                    () -> PeerConnector.candidateAddress(denied, publicRoute), denied);
        }
        InetAddress lan = ip("192.168.1.10");
        assertEquals(ip("192.168.1.20"),
                PeerConnector.candidateAddress("192.168.1.20", lan));
        assertThrows(IllegalArgumentException.class,
                () -> PeerConnector.candidateAddress("192.168.2.20", lan));
        assertEquals(ip("127.0.0.1"),
                PeerConnector.candidateAddress("127.0.0.1", ip("127.0.0.1")));
    }

    @Test
    void incomingPartialAndWrongRendezvousCannotBlockValidPeerAndLosersClose() throws Exception {
        List<AutoCloseable> resources = new CopyOnWriteArrayList<>();
        BlockingQueue<byte[]> adverts = new LinkedBlockingQueue<>();
        try (IncomingHub hub = new IncomingHub();
                ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
                Identity remote =
                        new Identity(
                                22,
                                ip("26.0.0.2"),
                                "Remote",
                                new byte[16],
                                "remote-password".getBytes(),
                                "127.0.0.1")) {
            hub.ingest(offer(11));
            var setup = hub.take(11).orElseThrow();
            var peer =
                    new Membership.Peer(
                            11, "Local", ip("26.0.0.1"), "127.0.0.1", 1, Set.of("network"));
            Future<PeerChannel> incoming =
                    jobs.submit(
                            () ->
                                    PeerConnector.accept(
                                            setup,
                                            remote,
                                            remote.vip(),
                                            peer,
                                            ip("127.0.0.1"),
                                            new PeerConnector.Policy(true, false, false),
                                            adverts::add,
                                            resources::add));
            byte[] advert = adverts.poll(3, TimeUnit.SECONDS);
            assertNotNull(advert);
            var entry = fields(field(fields(field(fields(advert), 0x1230)), 0x127d));
            int port = (int) number(entry, 0x010001c3);
            try (Socket stalled = new Socket("127.0.0.1", port);
                    Socket wrong = new Socket("127.0.0.1", port);
                    FramedTcp stream =
                            FramedTcp.connect(
                                    new InetSocketAddress("127.0.0.1", port),
                                    Duration.ofSeconds(5))) {
                stalled.getOutputStream().write(0);
                wrong.getOutputStream()
                        .write(
                                java.nio.ByteBuffer.allocate(24)
                                        .putInt(0x00140001)
                                        .putLong(19)
                                        .putLong(99)
                                        .putInt(7)
                                        .array());
                stream.peerRendezvous(11, 99, 7);
                try (PeerChannel channel =
                                PeerChannel.connect(
                                        stream,
                                        PeerChannel.Path.DIRECT_TCP,
                                        "peer-password".getBytes(),
                                        11,
                                        peer.vip(),
                                        22);
                        PeerChannel accepted = incoming.get(3, TimeUnit.SECONDS)) {
                    assertTrue(channel.send(new byte[] {42}));
                    assertArrayEquals(new byte[] {42}, accepted.receive());
                    stalled.setSoTimeout(1000);
                    assertEquals(-1, stalled.getInputStream().read());
                }
            }
        } finally {
            closeAll(resources);
        }
    }

    @Test
    void cancellationClosesIncomingListenersAndJoinsSetupWorkers() throws Exception {
        List<AutoCloseable> resources = new CopyOnWriteArrayList<>();
        BlockingQueue<byte[]> adverts = new LinkedBlockingQueue<>();
        try (IncomingHub hub = new IncomingHub();
                ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
                Identity remote =
                        new Identity(
                                22,
                                ip("26.0.0.2"),
                                "Remote",
                                new byte[16],
                                "remote-password".getBytes(),
                                "127.0.0.1")) {
            hub.ingest(offer(11));
            var setup = hub.take(11).orElseThrow();
            var peer =
                    new Membership.Peer(
                            11, "Local", ip("26.0.0.1"), "127.0.0.1", 1, Set.of("network"));
            Future<PeerChannel> pending =
                    jobs.submit(
                            () ->
                                    PeerConnector.accept(
                                            setup,
                                            remote,
                                            remote.vip(),
                                            peer,
                                            ip("127.0.0.1"),
                                            new PeerConnector.Policy(true, true, false),
                                            adverts::add,
                                            resources::add));
            assertNotNull(adverts.poll(3, TimeUnit.SECONDS));
            long began = System.nanoTime();
            closeAll(resources);
            assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
            assertTrue(System.nanoTime() - began < 3_000_000_000L);
        } finally {
            closeAll(resources);
        }
    }

    @Test
    void incomingRelayRejectsPrivateEndpointAfterRouteLoss() throws Exception {
        // A malicious coordinator must not turn a failed public route into an
        // arbitrary connection to local services, even as a relay fallback.
        Inet4Address missingRoute = ip("192.0.2.1");
        assertNull(NetworkInterface.getByInetAddress(missingRoute));
        List<AutoCloseable> resources = new CopyOnWriteArrayList<>();
        BlockingQueue<byte[]> advertisements = new LinkedBlockingQueue<>();
        try (ServerSocket relay = new ServerSocket(0, 16, ip("127.0.0.1"));
                IncomingHub hub = new IncomingHub();
                ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
                Identity remote =
                        new Identity(
                                22, ip("26.0.0.2"), "Remote", new byte[16],
                                "remote-password".getBytes(), "127.0.0.1")) {
            relay.setSoTimeout(800);
            hub.ingest(offer(11));
            IncomingHub.Setup setup = hub.take(11).orElseThrow();
            var peer = new Membership.Peer(
                    11, "Local", ip("26.0.0.1"), "127.0.0.1", 1, Set.of("network"));
            Future<PeerChannel> incoming = jobs.submit(
                    () -> PeerConnector.accept(
                            setup, remote, remote.vip(), peer, missingRoute,
                            new PeerConnector.Policy(true, true, true),
                            advertisements::add, resources::add));
            byte[] request = advertisements.poll(5, TimeUnit.SECONDS);
            assertNotNull(request, "relay fallback must still be requested");
            assertEquals(22,
                    (int) number(fields(request),
                            io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.CLIENT_OP));
            byte[] ticket = new byte[256];
            Arrays.fill(ticket, (byte) 17);
            hub.ingest(concat(
                    server(23), u64(0x020001c1, 99),
                    tlv(0x123f, concat(
                            text(0x030001cb, "127.0.0.1"),
                            u32(0x010001cc, relay.getLocalPort()),
                            tlv(0x090001ca, ticket)))));
            var error = assertThrows(ExecutionException.class,
                    () -> incoming.get(4, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
            assertThrows(SocketTimeoutException.class, relay::accept,
                    "untrusted local relay address must not be connected");
        } finally {
            closeAll(resources);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tcp", "udp", "relay"})
    void authenticatedCoordinatorHubAndSelectedTransportExchangeEthernet(String path)
            throws Exception {
        var policy =
                new PeerConnector.Policy(
                        path.equals("tcp"), path.equals("udp"), path.equals("relay"));
        List<AutoCloseable> resources = new CopyOnWriteArrayList<>();
        BlockingQueue<byte[]> advertisements = new LinkedBlockingQueue<>();
        CountDownLatch finished = new CountDownLatch(1);
        try (ServerSocket coordinator = new ServerSocket(0, 16, ip("127.0.0.1"));
                ServerSocket relay = new ServerSocket(0, 16, ip("127.0.0.1"));
                IncomingHub hub = new IncomingHub();
                ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
                Identity local =
                        new Identity(
                                11,
                                ip("26.0.0.1"),
                                "Local",
                                new byte[16],
                                "local-password".getBytes(),
                                "127.0.0.1");
                Identity remote =
                        new Identity(
                                22,
                                ip("26.0.0.2"),
                                "Remote",
                                new byte[16],
                                "remote-password".getBytes(),
                                "127.0.0.1")) {
            hub.ingest(offer(11));
            IncomingHub.Setup setup = hub.take(11).orElseThrow();
            List<ServerSocket> stalled = new ArrayList<>();
            if (path.equals("tcp"))
                for (int i = 0; i < 3; i++) {
                    ServerSocket listener = new ServerSocket(0, 16, ip("127.0.0.1"));
                    stalled.add(listener);
                    resources.add(listener);
                }
            var localPeer =
                    new Membership.Peer(
                            11, "Local", local.vip(), "127.0.0.1", 1, Set.of("network"));
            var remotePeer =
                    new Membership.Peer(
                            22, "Remote", remote.vip(), "127.0.0.1", 1, Set.of("network"));
            Future<PeerChannel> incoming =
                    jobs.submit(
                            () ->
                                    PeerConnector.accept(
                                            setup,
                                            remote,
                                            remote.vip(),
                                            localPeer,
                                            ip("127.0.0.1"),
                                            policy,
                                            advertisements::add,
                                            resources::add));
            Future<?> pairing =
                    jobs.submit(
                            () -> {
                                if (!path.equals("relay")) return null;
                                try (Socket a = relay.accept();
                                        Socket b = relay.accept();
                                        FramedTcp first = new FramedTcp(a, Duration.ofSeconds(20));
                                        FramedTcp second =
                                                new FramedTcp(b, Duration.ofSeconds(20))) {
                                    assertEquals(
                                            256,
                                            field(fields(first.receive(65536)), 0x090001ca).length);
                                    assertEquals(
                                            256,
                                            field(fields(second.receive(65536)), 0x090001ca)
                                                    .length);
                                    first.send(u32(0x010001df, 2));
                                    second.send(u32(0x010001df, 2));
                                    Future<?> forward =
                                            jobs.submit(
                                                    () -> {
                                                        try {
                                                            a.getInputStream()
                                                                    .transferTo(
                                                                            b.getOutputStream());
                                                        } catch (IOException ignored) {
                                                        }
                                                    });
                                    try {
                                        b.getInputStream().transferTo(a.getOutputStream());
                                    } catch (IOException ignored) {
                                    }
                                    forward.get(5, TimeUnit.SECONDS);
                                }
                                return null;
                            });
            Future<?> service =
                    jobs.submit(
                            () -> {
                                try (var authenticated =
                                        SyntheticControlService.authenticate(
                                                coordinator.accept(),
                                                5,
                                                11,
                                                "local-password".getBytes())) {
                                    byte[] login = authenticated.receive();
                                    assertEquals(
                                            20,
                                            (int)
                                                    number(
                                                            fields(login),
                                                            io.github.gringoestrangeiro
                                                                    .openradplugin.protocol.TlvCodec
                                                                    .CLIENT_OP));
                                    assertEquals(22, wide(fields(login), 0x0200030b));
                                    authenticated.send(concat(server(21), u32(0x0100025b, 7)));
                                    authenticated.send(offer(22));
                                    byte[] request = authenticated.receive();
                                    int requestOp =
                                            (int)
                                                    number(
                                                            fields(request),
                                                            io.github.gringoestrangeiro
                                                                    .openradplugin.protocol.TlvCodec
                                                                    .CLIENT_OP);
                                    if (path.equals("tcp")) {
                                        assertEquals(2, requestOp);
                                        byte[] advert = advertisements.poll(5, TimeUnit.SECONDS);
                                        assertNotNull(advert);
                                        List<byte[]> entries = new ArrayList<>();
                                        for (ServerSocket listener : stalled)
                                            entries.add(
                                                    tlv(
                                                            0x127d,
                                                            concat(
                                                                    text(0x030001c2, "127.0.0.1"),
                                                                    u32(
                                                                            0x010001c3,
                                                                            listener
                                                                                    .getLocalPort()))));
                                        entries.add(field(fields(advert), 0x1230));
                                        authenticated.send(
                                                candidates(
                                                        6,
                                                        0x1236,
                                                        concat(entries.toArray(byte[][]::new))));
                                    } else if (path.equals("udp")) {
                                        assertEquals(28, requestOp);
                                        byte[] advert = advertisements.poll(5, TimeUnit.SECONDS);
                                        assertNotNull(advert);
                                        hub.ingest(
                                                candidates(
                                                        29,
                                                        0x127c,
                                                        field(fields(request), 0x127b)));
                                        authenticated.send(
                                                candidates(
                                                        29, 0x127c, field(fields(advert), 0x127b)));
                                    } else {
                                        assertEquals(22, requestOp);
                                        byte[] advert = advertisements.poll(5, TimeUnit.SECONDS);
                                        assertNotNull(advert);
                                        assertEquals(
                                                22,
                                                (int)
                                                        number(
                                                                fields(advert),
                                                                io.github.gringoestrangeiro
                                                                        .openradplugin.protocol
                                                                        .TlvCodec.CLIENT_OP));
                                        assertTrue(advertisements.isEmpty());
                                        byte[] offer =
                                                concat(
                                                        server(23),
                                                        u64(0x020001c1, 99),
                                                        tlv(
                                                                0x123f,
                                                                concat(
                                                                        text(
                                                                                0x030001cb,
                                                                                "127.0.0.1"),
                                                                        u32(
                                                                                0x010001cc,
                                                                                relay
                                                                                        .getLocalPort()),
                                                                        tlv(
                                                                                0x090001ca,
                                                                                new byte[256]))));
                                        hub.ingest(offer);
                                        authenticated.send(offer);
                                    }
                                    assertTrue(finished.await(15, TimeUnit.SECONDS));
                                }
                                return null;
                            });
            long began = System.nanoTime();
            try (PeerConnector.Connected connected =
                            PeerConnector.connect(
                                    local,
                                    local.vip(),
                                    remotePeer,
                                    coordinator.getLocalPort(),
                                    policy,
                                    resources::add,
                                    SyntheticControlService.modulus());
                    PeerChannel accepted = incoming.get(15, TimeUnit.SECONDS)) {
                if (path.equals("tcp"))
                    assertTrue(
                            System.nanoTime() - began < 3_000_000_000L,
                            "four lanes must bypass three stalled candidates");
                assertEquals(
                        path.equals("tcp")
                                ? PeerChannel.Path.DIRECT_TCP
                                : path.equals("udp")
                                        ? PeerChannel.Path.DIRECT_UDP
                                        : PeerChannel.Path.RELAY,
                        connected.channel().path());
                byte[] frame = new byte[1514];
                Arrays.fill(frame, (byte) 42);
                assertTrue(connected.channel().sendFrame(frame));
                assertArrayEquals(
                        frame, ((TunnelCodec.Frames) accepted.receivePacket()).frames().getFirst());
                assertTrue(accepted.sendFrame(frame));
                assertArrayEquals(
                        frame,
                        ((TunnelCodec.Frames) connected.channel().receivePacket())
                                .frames()
                                .getFirst());
                connected.channel().send(new byte[] {3});
                assertArrayEquals(new byte[] {3}, accepted.receive());
                finished.countDown();
            }
            service.get(10, TimeUnit.SECONDS);
            if (path.equals("relay")) pairing.get(10, TimeUnit.SECONDS);
        } finally {
            finished.countDown();
            closeAll(resources);
        }
    }
}
