package io.github.gringoestrangeiro.openradplugin.runtime;

import static io.github.gringoestrangeiro.openradplugin.link.TcpSegment.*;
import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.config.*;
import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.link.*;
import io.github.gringoestrangeiro.openradplugin.network.Membership;
import io.github.gringoestrangeiro.openradplugin.peer.*;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradplugin.session.SyntheticControlService;
import io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** End-to-end runtime checks use synthetic RSA/SRP services and only loopback sockets. */
@Timeout(30)
class VpnRuntimeTest {
    @TempDir Path directory;
    private static final long RID = 11, REMOTE_RID = 22;
    private static final int LOCAL_IP = 0x1a000001, REMOTE_IP = 0x1a000002, VIRTUAL_PORT = 25565;
    private static final String NAME = "Synthetic runtime", A = "Synthetic A", B = "Synthetic B";
    private static final byte[] PASSWORD =
            "synthetic-local-password".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PEER_PASSWORD =
            "synthetic-peer-password".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EMPTY = new byte[0], GUID_A = new byte[16], GUID_B = new byte[16];

    static {
        Arrays.fill(GUID_A, (byte) 0x11);
        Arrays.fill(GUID_B, (byte) 0x22);
    }

    private static Inet4Address ip(String value) throws UnknownHostException {
        return (Inet4Address) InetAddress.getByName(value);
    }

    private static byte[] server(int operation) {
        return u32(TlvCodec.SERVER_OP, operation);
    }

    private static int clientOperation(byte[] record) {
        return (int) number(fields(record), TlvCodec.CLIENT_OP);
    }

    private static byte[] network(byte[] guid, String name) {
        return tlv(
                0x1315, concat(tlv(0x0d000309, guid), text(0x03000306, name), u32(0x0100030a, 1)));
    }

    private static byte[] member(byte[] guid, int role) {
        return tlv(
                0x1318,
                concat(u64(0x020001e1, REMOTE_RID), tlv(0x0d000309, guid), u32(0x0100030a, role)));
    }

    private static byte[] membership(
            int localIp, boolean includePeer, boolean twoNetworks, int roleA, int roleB) {
        List<byte[]> values = new ArrayList<>();
        values.add(u32(0x01000305, localIp));
        values.add(tlv(0x09000305, new byte[16]));
        values.add(network(GUID_A, A));
        if (twoNetworks) values.add(network(GUID_B, B));
        if (includePeer) {
            values.add(
                    tlv(
                            0x1317,
                            concat(
                                    u64(0x020001e1, REMOTE_RID),
                                    text(0x03000304, "Synthetic remote"),
                                    u32(0x01000305, REMOTE_IP),
                                    text(0x030001c9, "127.0.0.1"),
                                    u32(0x010003a0, 1))));
            values.add(member(GUID_A, roleA));
            if (twoNetworks) values.add(member(GUID_B, roleB));
        }
        return concat(server(38), tlv(0x1316, concat(values.toArray(byte[][]::new))));
    }

    private static byte[] roleChange(byte[] guid, int role) {
        return concat(server(42), tlv(0x131f, member(guid, role)));
    }

    private static byte[] offer(long peerRid) {
        return concat(
                server(11),
                u64(0x020001c1, 99),
                tlv(0x1235, concat(u64(0x020001e1, peerRid), tlv(0x0a0001cd, PEER_PASSWORD))));
    }

    private static final class Service implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 16, ip("127.0.0.1"));
        final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
        final List<AutoCloseable> resources = new CopyOnWriteArrayList<>();
        final AtomicInteger accepts = new AtomicInteger();
        final BlockingQueue<byte[]> updates = new LinkedBlockingQueue<>();
        final CountDownLatch attachmentClosed = new CountDownLatch(1);

        Service() throws IOException {
            listener.setSoTimeout(15000);
        }

        Socket socket() throws IOException {
            Socket socket = listener.accept();
            accepts.incrementAndGet();
            resources.add(socket);
            return socket;
        }

        SyntheticControlService.Authenticated authenticate(int purpose) throws IOException {
            return SyntheticControlService.authenticate(
                    socket(),
                    purpose,
                    purpose == 3 ? 0 : RID,
                    purpose == 3 ? "rpassword".getBytes(StandardCharsets.US_ASCII) : PASSWORD);
        }

        void attach(SyntheticControlService.Authenticated authenticated, byte[] snapshot)
                throws IOException {
            assertEquals(20, clientOperation(authenticated.receive()));
            authenticated.send(server(21));
            authenticated.send(snapshot);
        }

        void serve(SyntheticControlService.Authenticated attached) throws IOException {
            try (attached) {
                while (!listener.isClosed()) {
                    byte[] update;
                    while ((update = updates.poll()) != null) attached.send(update);
                    if (attached.stream().ready(Duration.ofMillis(20))) {
                        int operation = clientOperation(attached.receive());
                        if (operation == 4) attached.send(server(4));
                        else fail("unexpected runtime control operation " + operation);
                    }
                }
            } catch (EOFException | SocketException expected) {
                // Closing the runtime closes this real socket and unblocks the service.
            } finally {
                attachmentClosed.countDown();
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
            for (AutoCloseable item : resources)
                try {
                    item.close();
                } catch (Exception ignored) {
                }
            jobs.shutdownNow();
            try {
                assertTrue(jobs.awaitTermination(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("synthetic service shutdown interrupted", e);
            }
        }
    }

    private void saveIdentity() throws IOException {
        try (Identity identity =
                new Identity(RID, ip("26.0.0.1"), NAME, new byte[16], PASSWORD, "127.0.0.1")) {
            identity.save(directory.resolve("identity.json"));
        }
    }

    private VpnSettings settings(int servicePort, int backendPort, boolean aclIntersection) {
        BridgeSettings bridge =
                new BridgeSettings(
                        true, "127.0.0.1", backendPort, VIRTUAL_PORT, 8, 8, 3, "", 0, "", 0, 100);
        List<VpnSettings.Network> networks =
                aclIntersection
                        ? List.of(
                                new VpnSettings.Network(A, "", Set.of(), Set.of(REMOTE_RID)),
                                new VpnSettings.Network(B, "", Set.of(), Set.of()))
                        : List.of(new VpnSettings.Network(A, "", Set.of(), Set.of()));
        return new VpnSettings(
                bridge,
                NAME,
                "127.0.0.1",
                servicePort,
                networks,
                Set.of(),
                Set.of(),
                true,
                false,
                false,
                4,
                1,
                1);
    }

    private VpnRuntime runtime(Service service, int backendPort, boolean acl, List<String> logs)
            throws IOException {
        return new VpnRuntime(
                settings(service.listener.getLocalPort(), backendPort, acl),
                directory,
                NetworkSecrets.load(directory),
                logs::add,
                SyntheticControlService.modulus());
    }

    private static void stopped(VpnRuntime runtime) throws Exception {
        runtime.close();
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(5)), "runtime/socket worker leaked");
        assertEquals("STOPPED", runtime.snapshot().phase());
        Thread.sleep(75);
        assertEquals(
                "STOPPED", runtime.snapshot().phase(), "stale worker republished after shutdown");
    }

    private VpnRuntime catalogRuntime(Service service, List<String> selectors) throws IOException {
        var s = settings(service.listener.getLocalPort(), 25575, false);
        var configured =
                new VpnSettings(
                        s.bridge(),
                        s.nodeName(),
                        s.bootstrap(),
                        s.controlPort(),
                        s.networks(),
                        s.allowedRids(),
                        s.deniedRids(),
                        s.directTcp(),
                        s.directUdp(),
                        s.relay(),
                        s.maxPeers(),
                        s.retryBaseSeconds(),
                        s.retryMaxSeconds(),
                        false,
                        new MinecraftNetworkSettings(true, selectors, 256));
        return new VpnRuntime(
                configured,
                directory,
                NetworkSecrets.load(directory),
                line -> {},
                SyntheticControlService.modulus());
    }

    private static byte[] catalogPage(long id, long cursor, String name) {
        return concat(
                server(45),
                tlv(
                        0x1334,
                        concat(
                                u64(0x02000340, id),
                                u64(0x0200030f, cursor),
                                tlv(0x1333, concat(text(0x03000306, name), u32(0x01000341, 12))))));
    }

    @Test
    void automaticPeerCapacityCreatesAWorkingRuntimeAndStopsCleanly() throws Exception {
        saveIdentity();
        try (Service service = new Service()) {
            var s = settings(service.listener.getLocalPort(), 25575, false);
            var automatic =
                    new VpnSettings(
                            s.bridge(),
                            s.nodeName(),
                            s.bootstrap(),
                            s.controlPort(),
                            s.networks(),
                            s.allowedRids(),
                            s.deniedRids(),
                            true,
                            false,
                            false,
                            0,
                            1,
                            1,
                            false);
            try (VpnRuntime runtime =
                    new VpnRuntime(
                            automatic,
                            directory,
                            NetworkSecrets.load(directory),
                            line -> {},
                            SyntheticControlService.modulus())) {
                var job =
                        service.jobs.submit(
                                () -> {
                                    var attached = service.authenticate(4);
                                    service.attach(
                                            attached, membership(LOCAL_IP, false, false, 0, 0));
                                    service.serve(attached);
                                    return null;
                                });
                runtime.start();
                await(() -> runtime.snapshot().phase().equals("CONNECTED"));
                assertEquals(0, runtime.reachabilitySnapshot().eligible());
                stopped(runtime);
                job.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void publicCatalogPagesUseRealNamesAndRefusedJoinsPreserveExplicitNetworks() throws Exception {
        saveIdentity();
        try (Service service = new Service();
                VpnRuntime runtime = catalogRuntime(service, List.of("ptbr-1", "english-2"))) {
            var job =
                    service.jobs.submit(
                            () -> {
                                var attached = service.authenticate(4);
                                service.attach(attached, membership(LOCAL_IP, false, false, 0, 0));
                                int pages = 0, joins = 0;
                                Set<String> names = new HashSet<>();
                                while (joins < 2) {
                                    byte[] data = attached.receive();
                                    int operation = clientOperation(data);
                                    if (operation == 4) {
                                        attached.send(server(4));
                                        continue;
                                    }
                                    if (operation == 43) {
                                        var query = fields(field(fields(data), 0x1332));
                                        assertEquals(
                                                "Minecraft", display(field(query, 0x03000306)));
                                        assertEquals(pages == 0 ? 0 : 99, wide(query, 0x0200030f));
                                        attached.send(
                                                catalogPage(
                                                        wide(query, 0x02000340),
                                                        pages++ == 0 ? 99 : 0,
                                                        pages == 1
                                                                ? "Minecraft [Português 1]"
                                                                : "Minecraft [English 2]"));
                                    } else {
                                        assertEquals(39, operation);
                                        var join = fields(field(fields(data), 0x131c));
                                        names.add(display(field(join, 0x03000306)));
                                        attached.send(
                                                concat(
                                                        server(37),
                                                        tlv(
                                                                0x131a,
                                                                concat(
                                                                        u64(
                                                                                0x02000340,
                                                                                wide(
                                                                                        join,
                                                                                        0x02000340)),
                                                                        u32(0x0100030c, 2),
                                                                        u32(0x010001d2, 7)))));
                                        joins++;
                                    }
                                }
                                assertEquals(2, pages);
                                assertEquals(
                                        Set.of("Minecraft [Português 1]", "Minecraft [English 2]"),
                                        names);
                                service.serve(attached);
                                return null;
                            });
            runtime.start();
            await(() -> runtime.snapshot().phase().equals("CONNECTED"));
            assertEquals("READY_WITH_WARNINGS", runtime.minecraftCatalog().state());
            assertEquals(2, runtime.minecraftCatalog().entries().size());
            assertEquals(2, runtime.minecraftCatalog().problems().size());
            assertEquals(List.of(A), runtime.snapshot().networks());
            String exported = Files.readString(directory.resolve("minecraft-networks.txt"));
            assertTrue(exported.contains("ptbr-1\t12\tMinecraft [Português 1]"));
            assertTrue(
                    Files.readString(directory.resolve("minecraft-network-options.yml"))
                            .contains("all-ptbr"));
            assertEquals(1, service.accepts.get());
            stopped(runtime);
            job.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void stalledAutomaticPublicJoinDoesNotRestartExistingAttachment() throws Exception {
        saveIdentity();
        try (Service service = new Service();
                VpnRuntime runtime = catalogRuntime(service, List.of("english-2"))) {
            var sawJoin = new CountDownLatch(1);
            var job = service.jobs.submit(() -> {
                try (var attached = service.authenticate(4)) {
                    // Explicit network A is already present, only the public auto-join stalls.
                    service.attach(attached, membership(LOCAL_IP, false, false, 0, 0));
                    while (!service.listener.isClosed()) {
                        byte[] data = attached.receive();
                        int operation = clientOperation(data);
                        if (operation == 43) {
                            var request = fields(field(fields(data), 0x1332));
                            attached.send(catalogPage(
                                    wide(request, 0x02000340), 0,
                                    "Minecraft [English 2]"));
                        } else if (operation == 39) {
                            sawJoin.countDown();
                            // Simulate an unresponsive selected public network.
                        } else if (operation == 4) {
                            attached.send(server(4));
                        } else {
                            fail("unexpected control operation " + operation);
                        }
                    }
                } catch (EOFException | SocketException expected) {
                    // Closing the test runtime terminates the synthetic attachment.
                }
                return null;
            });
            runtime.start();
            assertTrue(sawJoin.await(5, TimeUnit.SECONDS));
            await(() -> runtime.snapshot().phase().equals("CONNECTED")
                    && runtime.minecraftCatalog().state().equals("READY_WITH_WARNINGS"));
            assertEquals(1, service.accepts.get(),
                    "one failed public join must not restart the authenticated VPN session");
            assertEquals(List.of(A), runtime.snapshot().networks(),
                    "existing explicit network must survive a public join timeout");
            assertTrue(runtime.minecraftCatalog().problems().stream()
                    .anyMatch(text -> text.contains("join timeout")));
            stopped(runtime);
            job.get(4, TimeUnit.SECONDS);
        }
    }

    @Test
    void repeatedCatalogCursorFailsClosedWithoutInventingAutomaticNetworkNames() throws Exception {
        saveIdentity();
        try (Service service = new Service();
                VpnRuntime runtime = catalogRuntime(service, List.of("all"))) {
            var job =
                    service.jobs.submit(
                            () -> {
                                var attached = service.authenticate(4);
                                service.attach(attached, membership(LOCAL_IP, false, false, 0, 0));
                                for (int page = 0; page < 2; ) {
                                    byte[] data = attached.receive();
                                    if (clientOperation(data) == 4) {
                                        attached.send(server(4));
                                        continue;
                                    }
                                    assertEquals(43, clientOperation(data));
                                    var query = fields(field(fields(data), 0x1332));
                                    attached.send(
                                            catalogPage(
                                                    wide(query, 0x02000340),
                                                    99,
                                                    "Minecraft [English 1]"));
                                    page++;
                                }
                                service.serve(attached);
                                return null;
                            });
            runtime.start();
            await(() -> runtime.snapshot().phase().equals("CONNECTED"));
            assertEquals("FAILED", runtime.minecraftCatalog().state());
            assertTrue(runtime.minecraftCatalog().selected().isEmpty());
            assertEquals(List.of(A), runtime.snapshot().networks());
            stopped(runtime);
            job.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void attachedRuntimeKeepsHealthyPeerAndBridgesRealBackendThenRevokesAndStops()
            throws Exception {
        saveIdentity();
        List<String> logs = new CopyOnWriteArrayList<>();
        CountDownLatch backendClosed = new CountDownLatch(1);
        AtomicInteger backendAccepts = new AtomicInteger();
        try (Service service = new Service();
                ServerSocket backend = new ServerSocket(0, 8, ip("127.0.0.1"));
                IncomingHub hub = new IncomingHub();
                Identity remote =
                        new Identity(
                                REMOTE_RID,
                                ip("26.0.0.2"),
                                "Remote",
                                new byte[16],
                                PEER_PASSWORD,
                                "127.0.0.1");
                VpnRuntime runtime = runtime(service, backend.getLocalPort(), false, logs)) {
            var echo =
                    service.jobs.submit(
                            () -> {
                                try (Socket socket = backend.accept()) {
                                    service.resources.add(socket);
                                    backendAccepts.incrementAndGet();
                                    socket.getInputStream().transferTo(socket.getOutputStream());
                                } finally {
                                    backendClosed.countDown();
                                }
                                return null;
                            });
            BlockingQueue<byte[]> advertisements = new LinkedBlockingQueue<>();
            hub.ingest(offer(RID));
            IncomingHub.Setup setup = hub.take(RID).orElseThrow();
            var localPeer =
                    new Membership.Peer(
                            RID, NAME, ip("26.0.0.1"), "127.0.0.1", 1, Set.of("synthetic"));
            Future<PeerChannel> peer =
                    service.jobs.submit(
                            () ->
                                    PeerConnector.accept(
                                            setup,
                                            remote,
                                            remote.vip(),
                                            localPeer,
                                            ip("127.0.0.1"),
                                            new PeerConnector.Policy(true, false, false),
                                            advertisements::add,
                                            service.resources::add));
            var controlService =
                    service.jobs.submit(
                            () -> {
                                var attached = service.authenticate(4);
                                service.attach(attached, membership(LOCAL_IP, true, false, 1, 0));
                                Future<?> attachmentJob =
                                        service.jobs.submit(
                                                () -> {
                                                    service.serve(attached);
                                                    return null;
                                                });
                                try (var coordinator = service.authenticate(5)) {
                                    assertEquals(20, clientOperation(coordinator.receive()));
                                    coordinator.send(concat(server(21), u32(0x0100025b, 7)));
                                    coordinator.send(offer(REMOTE_RID));
                                    assertEquals(2, clientOperation(coordinator.receive()));
                                    byte[] advert = advertisements.poll(5, TimeUnit.SECONDS);
                                    assertNotNull(advert);
                                    coordinator.send(
                                            concat(
                                                    server(6),
                                                    u64(0x020001c1, 99),
                                                    tlv(0x1236, field(fields(advert), 0x1230))));
                                    attachmentJob.get(15, TimeUnit.SECONDS);
                                }
                                return null;
                            });
            runtime.start();
            try (PeerChannel remoteChannel = peer.get(10, TimeUnit.SECONDS)) {
                await(
                        () ->
                                runtime.snapshot().peers().stream()
                                        .anyMatch(VpnRuntime.PeerView::connected));
                assertEquals("CONNECTED", runtime.snapshot().phase());
                assertEquals("26.0.0.1", runtime.snapshot().vip());
                assertEquals(List.of(A), runtime.snapshot().networks());
                assertEquals("LISTENING", runtime.lanSnapshot().state());
                // A real LANBroadcaster-shaped local multicast crosses the authenticated channel.
                try (DatagramSocket advertiser = new DatagramSocket()) {
                    byte[] lan =
                            ("[MOTD]runtime LAN test[/MOTD][AD]" + backend.getLocalPort() + "[/AD]")
                                    .getBytes(StandardCharsets.UTF_8);
                    advertiser.send(new DatagramPacket(lan, lan.length, ip("224.0.2.60"), 4445));
                    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    byte[] receivedLan = null;
                    while (receivedLan == null && System.nanoTime() < deadline) {
                        if (!remoteChannel.ready(Duration.ofMillis(50))) continue;
                        var record = remoteChannel.receivePacket();
                        if (record instanceof TunnelCodec.Frames frames)
                            for (byte[] frame : frames.frames())
                                if (frame.length >= 42 && frame[23] == 17) receivedLan = frame;
                    }
                    assertNotNull(receivedLan, "authenticated peer missed local LAN advertisement");
                    assertEquals(LOCAL_IP, Checksum.u32(receivedLan, 26));
                    assertEquals(0xe000023c, Checksum.u32(receivedLan, 30));
                    assertEquals(4445, Checksum.u16(receivedLan, 36));
                    assertEquals(
                            "[MOTD]runtime LAN test[/MOTD][AD]25565[/AD]",
                            new String(
                                    receivedLan,
                                    42,
                                    receivedLan.length - 42,
                                    StandardCharsets.UTF_8));
                    // Delivery can reach the peer before the sender worker increments
                    // its accounting counter. Assert eventual accounting, not the race.
                    await(() -> runtime.lanSnapshot().sent() == 1);
                }
                VirtualClient client = new VirtualClient(remoteChannel);
                client.handshake();
                client.echo("hello synthetic backend".getBytes(StandardCharsets.UTF_8));
                Thread.sleep(350);
                assertTrue(runtime.snapshot().peers().getFirst().connected(), logs.toString());
                client.echo(
                        "same authenticated connection survives repeated control ticks"
                                .getBytes(StandardCharsets.UTF_8));
                assertEquals(
                        2, service.accepts.get(), "healthy peer was replaced by a new coordinator");
                assertEquals(1, backendAccepts.get());
                service.updates.add(roleChange(GUID_A, 0));
                await(() -> runtime.snapshot().peers().isEmpty());
                long advertisementsAfterRevoke = runtime.lanSnapshot().sent();
                Thread.sleep(100);
                assertEquals(advertisementsAfterRevoke, runtime.lanSnapshot().sent());
                assertTrue(backendClosed.await(5, TimeUnit.SECONDS));
                await(
                        () ->
                                runtime.snapshot().bridge() != null
                                        && runtime.snapshot().bridge().connections() == 0);
                stopped(runtime);
                assertEquals("STOPPED", runtime.lanSnapshot().state());
                assertTrue(service.attachmentClosed.await(3, TimeUnit.SECONDS));
                echo.get(3, TimeUnit.SECONDS);
                controlService.get(3, TimeUnit.SECONDS);
            }
        }
        assertTrue(
                logs.stream()
                        .noneMatch(
                                line ->
                                        line.contains("synthetic-local-password")
                                                || line.contains("synthetic-peer-password")));
    }

    @Test
    void roleAndRidAclMustAllowTheSameNetworkBeforeCoordinatorCanStart() throws Exception {
        saveIdentity();
        List<String> logs = new CopyOnWriteArrayList<>();
        try (Service service = new Service();
                VpnRuntime runtime = runtime(service, 25565, true, logs)) {
            var control =
                    service.jobs.submit(
                            () -> {
                                var attached = service.authenticate(4);
                                service.attach(attached, membership(LOCAL_IP, true, true, 1, 0));
                                service.serve(attached);
                                return null;
                            });
            runtime.start();
            await(() -> runtime.snapshot().phase().equals("CONNECTED"));
            Thread.sleep(250);
            assertTrue(
                    runtime.snapshot().peers().isEmpty(),
                    "ACL-denied A must not combine with role-denied B");
            assertEquals(1, service.accepts.get());
            try (DatagramSocket advertiser = new DatagramSocket()) {
                byte[] lan =
                        "[MOTD]ACL blocked[/MOTD][AD]25565[/AD]".getBytes(StandardCharsets.UTF_8);
                advertiser.send(new DatagramPacket(lan, lan.length, ip("224.0.2.60"), 4445));
                await(() -> runtime.lanSnapshot().captured() > 0);
                assertEquals(
                        0, runtime.lanSnapshot().sent(), "unauthorized member received LAN data");
            }
            // Grant B's role: now both conditions hold in B and the peer becomes eligible.
            var incoming =
                    service.jobs.submit(
                            () -> {
                                Socket socket = service.socket();
                                socket.close();
                                return null;
                            });
            service.updates.add(roleChange(GUID_B, 1));
            incoming.get(5, TimeUnit.SECONDS);
            await(() -> !runtime.snapshot().peers().isEmpty());
            assertEquals(REMOTE_RID, runtime.snapshot().peers().getFirst().rid());
            stopped(runtime);
            control.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void attachmentLossReconnectsOnceAndPublishesOnlyTheNewVip() throws Exception {
        saveIdentity();
        List<String> logs = new CopyOnWriteArrayList<>();
        CountDownLatch disconnect = new CountDownLatch(1);
        try (Service service = new Service();
                VpnRuntime runtime = runtime(service, 25565, false, logs)) {
            var control =
                    service.jobs.submit(
                            () -> {
                                try (var first = service.authenticate(4)) {
                                    service.attach(first, membership(LOCAL_IP, false, false, 0, 0));
                                    assertTrue(disconnect.await(5, TimeUnit.SECONDS));
                                }
                                var second = service.authenticate(4);
                                service.attach(second, membership(0x1a000003, false, false, 0, 0));
                                service.serve(second);
                                return null;
                            });
            runtime.start();
            await(() -> runtime.snapshot().phase().equals("CONNECTED"));
            assertEquals("26.0.0.1", runtime.snapshot().vip());
            disconnect.countDown();
            await(
                    () ->
                            runtime.snapshot().phase().equals("CONNECTED")
                                    && runtime.snapshot().vip().equals("26.0.0.3"));
            assertEquals(2, service.accepts.get());
            assertEquals(0, runtime.snapshot().bridge().connections());
            stopped(runtime);
            control.get(3, TimeUnit.SECONDS);
        } finally {
            disconnect.countDown();
        }
    }

    @Test
    void stoppingPendingAuthenticationClosesSocketAndDoesNotPublishStaleConnectedState()
            throws Exception {
        saveIdentity();
        List<String> logs = new CopyOnWriteArrayList<>();
        CountDownLatch receivedRsa = new CountDownLatch(1), socketClosed = new CountDownLatch(1);
        try (Service service = new Service();
                VpnRuntime runtime = runtime(service, 25565, false, logs)) {
            var pending =
                    service.jobs.submit(
                            () -> {
                                try (Socket socket = service.socket()) {
                                    var stream =
                                            new io.github.gringoestrangeiro.openradplugin.transport
                                                    .FramedTcp(socket, Duration.ofSeconds(5));
                                    assertArrayEquals(new byte[] {3, 0, 0, 0}, stream.receive(4));
                                    assertTrue(stream.receive(1024).length > 0);
                                    receivedRsa.countDown();
                                    assertEquals(-1, socket.getInputStream().read());
                                    socketClosed.countDown();
                                }
                                return null;
                            });
            runtime.start();
            assertTrue(receivedRsa.await(5, TimeUnit.SECONDS));
            stopped(runtime);
            assertTrue(socketClosed.await(3, TimeUnit.SECONDS));
            pending.get(3, TimeUnit.SECONDS);
            assertEquals(1, service.accepts.get());
            assertTrue(runtime.snapshot().peers().isEmpty());
            assertNull(runtime.snapshot().bridge());
        }
    }

    @Test
    void stoppingAmbiguousRegistrationPreservesIntentAndCannotCreateLateIdentity()
            throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        CountDownLatch requested = new CountDownLatch(1), socketClosed = new CountDownLatch(1);
        try (Service service = new Service();
                VpnRuntime runtime = runtime(service, 25565, false, logs)) {
            var pending =
                    service.jobs.submit(
                            () -> {
                                try (var registered = service.authenticate(3)) {
                                    assertEquals(20, clientOperation(registered.receive()));
                                    requested.countDown();
                                    assertThrows(IOException.class, registered::receive);
                                    socketClosed.countDown();
                                }
                                return null;
                            });
            runtime.start();
            assertTrue(requested.await(5, TimeUnit.SECONDS));
            stopped(runtime);
            assertTrue(socketClosed.await(3, TimeUnit.SECONDS));
            pending.get(3, TimeUnit.SECONDS);
            assertTrue(Files.exists(directory.resolve("registration.pending")));
            assertFalse(Files.exists(directory.resolve("identity.json")));
            assertEquals(1, service.accepts.get());
        }
    }

    @Test
    void stoppingPendingPeerCoordinatorClosesBothAttachmentAndOwnedHandshakeSocket()
            throws Exception {
        saveIdentity();
        List<String> logs = new CopyOnWriteArrayList<>();
        CountDownLatch coordinatorPending = new CountDownLatch(1),
                coordinatorClosed = new CountDownLatch(1);
        try (Service service = new Service();
                VpnRuntime runtime = runtime(service, 25565, false, logs)) {
            var control =
                    service.jobs.submit(
                            () -> {
                                var attached = service.authenticate(4);
                                service.attach(attached, membership(LOCAL_IP, true, false, 1, 0));
                                var attachmentJob =
                                        service.jobs.submit(
                                                () -> {
                                                    service.serve(attached);
                                                    return null;
                                                });
                                try (Socket socket = service.socket()) {
                                    var stream =
                                            new io.github.gringoestrangeiro.openradplugin.transport
                                                    .FramedTcp(socket, Duration.ofSeconds(5));
                                    assertArrayEquals(new byte[] {3, 0, 0, 0}, stream.receive(4));
                                    assertTrue(stream.receive(1024).length > 0);
                                    coordinatorPending.countDown();
                                    assertEquals(-1, socket.getInputStream().read());
                                    coordinatorClosed.countDown();
                                }
                                attachmentJob.get(3, TimeUnit.SECONDS);
                                return null;
                            });
            runtime.start();
            assertTrue(coordinatorPending.await(5, TimeUnit.SECONDS));
            await(() -> runtime.snapshot().peers().size() == 1);
            assertFalse(runtime.snapshot().peers().getFirst().connected());
            stopped(runtime);
            assertTrue(coordinatorClosed.await(3, TimeUnit.SECONDS));
            control.get(3, TimeUnit.SECONDS);
            assertEquals(2, service.accepts.get());
            assertTrue(runtime.snapshot().peers().isEmpty());
            assertNull(runtime.snapshot().bridge());
        }
    }

    @Test
    void awaitStoppedHonoursOneOverallDeadline() throws Exception {
        Thread worker =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    try {
                                        Thread.sleep(300);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
        var peers = Executors.newSingleThreadExecutor();
        try {
            long start = System.nanoTime();
            assertFalse(
                    VpnRuntime.awaitStopped(worker, peers, Duration.ofSeconds(1)),
                    "non-terminated peer executor must not report a stopped runtime");
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed >= 900, "overall deadline was not used: " + elapsed + "ms");
            assertTrue(elapsed < 1250, "awaitStopped spent a second deadline: " + elapsed + "ms");
        } finally {
            peers.shutdownNow();
            worker.join(1000);
        }
    }

    @Test
    void profileLockWaitAcquiresAfterAnotherGenerationReleasesIt() throws Exception {
        Path lockPath = directory.resolve("identity.lock");
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicReference<FileLock> acquired = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch attempting = new CountDownLatch(1), outcome = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (FileChannel first =
                        FileChannel.open(
                                lockPath,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                FileLock held = first.tryLock()) {
            assertNotNull(held);
            Thread waiter =
                    Thread.ofPlatform()
                            .daemon()
                            .start(
                                    () -> {
                                        try (FileChannel channel =
                                                FileChannel.open(
                                                        lockPath,
                                                        StandardOpenOption.CREATE,
                                                        StandardOpenOption.WRITE)) {
                                            attempting.countDown();
                                            FileLock lock =
                                                    VpnRuntime.awaitIdentityLock(
                                                            channel, stopped, () -> {});
                                            acquired.set(lock);
                                            outcome.countDown();
                                            release.await(5, TimeUnit.SECONDS);
                                            lock.release();
                                        } catch (Throwable t) {
                                            failure.set(t);
                                            outcome.countDown();
                                        }
                                    });
            assertTrue(attempting.await(2, TimeUnit.SECONDS));
            assertFalse(
                    outcome.await(400, TimeUnit.MILLISECONDS),
                    "helper returned while another generation held the lock");
            assertNull(failure.get());
            held.release();
            assertTrue(outcome.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertNotNull(acquired.get());
            release.countDown();
            waiter.join(2000);
            assertFalse(waiter.isAlive());
        } finally {
            release.countDown();
        }
    }

    @Test
    void profileLockWaitAbortsWhenRuntimeStops() throws Exception {
        Path lockPath = directory.resolve("identity.lock");
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1), done = new CountDownLatch(1);
        try (FileChannel first =
                        FileChannel.open(
                                lockPath,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                FileLock held = first.tryLock()) {
            assertNotNull(held);
            Thread waiter =
                    Thread.ofPlatform()
                            .daemon()
                            .start(
                                    () -> {
                                        try (FileChannel channel =
                                                FileChannel.open(
                                                        lockPath,
                                                        StandardOpenOption.CREATE,
                                                        StandardOpenOption.WRITE)) {
                                            VpnRuntime.awaitIdentityLock(
                                                    channel, stopped, waiting::countDown);
                                        } catch (Throwable t) {
                                            failure.set(t);
                                        } finally {
                                            done.countDown();
                                        }
                                    });
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            stopped.set(true);
            assertTrue(done.await(1, TimeUnit.SECONDS), "stopped lock wait did not abort");
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed < 500, "stopped lock wait took " + elapsed + "ms");
            assertInstanceOf(IOException.class, failure.get());
            waiter.join(1000);
            assertFalse(waiter.isAlive());
        }
    }

    private static final class VirtualClient {
        final PeerChannel peer;
        final ArrayDeque<TcpSegment> queued = new ArrayDeque<>();
        long next = 1234567, expected;

        VirtualClient(PeerChannel peer) {
            this.peer = peer;
        }

        void send(int flags, byte[] payload) throws IOException {
            var tcp =
                    new TcpSegment(
                            49152,
                            VIRTUAL_PORT,
                            next & 0xffff_ffffL,
                            expected & 0xffff_ffffL,
                            flags,
                            32768,
                            (flags & SYN) != 0 ? new byte[] {2, 4, 5, (byte) 0xb4} : EMPTY,
                            payload);
            byte[] raw =
                    new EthernetFrame(
                                    OpenRadAddress.mac(LOCAL_IP),
                                    OpenRadAddress.mac(REMOTE_IP),
                                    EthernetFrame.IPV4,
                                    new Ipv4Packet(
                                                    REMOTE_IP,
                                                    LOCAL_IP,
                                                    64,
                                                    tcp.encode(REMOTE_IP, LOCAL_IP))
                                            .encode())
                            .encode();
            assertTrue(peer.sendFrame(raw));
        }

        TcpSegment take() throws IOException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (queued.isEmpty()) {
                assertTrue(System.nanoTime() < deadline, "runtime stopped delivering peer frames");
                if (!peer.ready(Duration.ofMillis(50))) continue;
                var packet = peer.receivePacket();
                if (packet instanceof TunnelCodec.Keepalive k) {
                    if (!k.reply())
                        assertTrue(peer.send(TunnelCodec.keepalive(k.sequence(), true)));
                } else if (packet instanceof TunnelCodec.Frames frames)
                    for (byte[] raw : frames.frames()) {
                        byte[] payload = EthernetFrame.parse(raw).payload();
                        if (payload.length < 20 || payload[9] != 6) continue;
                        var ip = Ipv4Packet.parse(payload);
                        queued.add(
                                TcpSegment.parse(ip.tcpBytes(), ip.sourceIp(), ip.destinationIp()));
                    }
            }
            TcpSegment tcp = queued.removeFirst();
            assertEquals(0, tcp.flags() & RST);
            return tcp;
        }

        void handshake() throws IOException {
            send(SYN, EMPTY);
            next++;
            var reply = take();
            assertEquals(SYN | ACK, reply.flags());
            assertEquals(next, reply.acknowledgement());
            expected = reply.sequence() + 1;
            send(ACK, EMPTY);
        }

        void echo(byte[] payload) throws IOException {
            send(ACK | PSH, payload);
            next += payload.length;
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            while (received.size() < payload.length) {
                var tcp = take();
                if (tcp.payload().length == 0) continue;
                assertEquals(expected & 0xffff_ffffL, tcp.sequence());
                received.write(tcp.payload());
                expected += tcp.payload().length;
                send(ACK, EMPTY);
            }
            assertArrayEquals(payload, received.toByteArray());
        }
    }

    @FunctionalInterface
    interface Check {
        boolean ok();
    }

    private static void await(Check condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.ok() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.ok(), "runtime did not reach expected state");
    }
}
