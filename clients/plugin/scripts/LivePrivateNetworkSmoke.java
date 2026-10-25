import static io.github.gringoestrangeiro.openradbridge.protocol.Wire.*;

import io.github.gringoestrangeiro.openradbridge.crypto.*;
import io.github.gringoestrangeiro.openradbridge.network.*;
import io.github.gringoestrangeiro.openradbridge.peer.*;
import io.github.gringoestrangeiro.openradbridge.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradbridge.session.*;
import io.github.gringoestrangeiro.openradbridge.tunnel.TunnelCodec;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Explicit developer-only live test. Operates only on named disposable profiles and a new private
 * network.
 */
public final class LivePrivateNetworkSmoke {
    private static final List<String> EVIDENCE = new ArrayList<>();

    private static void report(String line) {
        EVIDENCE.add(line);
        System.out.println(line);
    }

    static final class Resources implements AutoCloseable {
        private final Set<AutoCloseable> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean closed;

        synchronized void own(AutoCloseable item) {
            if (closed) quietClose(item);
            else owned.add(item);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            for (var item : owned) quietClose(item);
            owned.clear();
        }
    }

    static final class Endpoint implements AutoCloseable {
        final Identity identity;
        final Attachment attachment;
        final IncomingHub hub = new IncomingHub();
        final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(64);
        long heartbeat = System.nanoTime();

        Endpoint(Identity identity) throws Exception {
            this.identity = identity;
            attachment = attach(identity);
        }

        byte[] pump() throws Exception {
            for (int i = 0; i < 16; i++) {
                byte[] next = outbound.poll();
                if (next == null) break;
                attachment.session().send(next);
            }
            if (System.nanoTime() >= heartbeat) {
                attachment.session().send(op(4));
                heartbeat = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            }
            if (!attachment.session().ready(Duration.ofMillis(20))) return null;
            byte[] data = attachment.session().receive();
            int operation = TlvCodec.operation(data);
            switch (operation) {
                case 38 -> attachment.membership().snapshot(data);
                case 41, 42 -> attachment.membership().changes(data);
                case 16 -> throw new IOException("attachment disconnected");
                default -> hub.ingest(data);
            }
            return data;
        }

        @Override
        public void close() {
            hub.close();
            quietClose(attachment);
        }
    }

    private static Attachment attach(Identity identity) throws Exception {
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                return Attachment.connect(
                        identity, ServiceConfig.PORT, identity.nodeName(), s -> {});
            } catch (IOException e) {
                last = e;
                Thread.sleep(500L * (i + 1));
            }
        }
        throw last;
    }

    static void quietClose(AutoCloseable item) {
        try {
            item.close();
        } catch (Exception ignored) {
        }
    }

    static NetworkManagement.Result manage(
            Endpoint target, Endpoint other, NetworkManagement operation) throws Exception {
        target.attachment.session().send(operation.start());
        long until = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < until) {
            if (other != null) other.pump();
            byte[] data = target.pump();
            if (data != null) {
                var result = operation.handle(data, target.attachment.membership());
                if (result != null) return result;
            }
        }
        throw new SocketTimeoutException("management deadline");
    }

    static void join(Endpoint creator, Endpoint peer, String network, char[] password)
            throws Exception {
        try (NetworkJoin operation = new NetworkJoin(network, password, 101, 101)) {
            peer.attachment.session().send(operation.start());
            long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!operation.complete() && System.nanoTime() < until) {
                creator.pump();
                byte[] data = peer.pump();
                if (data != null) {
                    byte[] reply = operation.handle(data, peer.attachment.membership());
                    if (reply != null) peer.attachment.session().send(reply);
                }
            }
            if (!operation.complete() || operation.pendingApproval())
                throw new IOException("private join not approved");
        }
    }

    static Membership.Peer eligible(Endpoint endpoint, long rid, String network) {
        return endpoint
                .attachment
                .membership()
                .eligible(Set.of(network), Set.of(), Set.of())
                .stream()
                .filter(peer -> peer.rid() == rid)
                .findFirst()
                .orElse(null);
    }

    private static byte[] ethernet(byte[] destination, byte[] source, String text) {
        byte[] body = text.getBytes(StandardCharsets.US_ASCII), frame = new byte[14 + body.length];
        System.arraycopy(destination, 0, frame, 0, 6);
        System.arraycopy(source, 0, frame, 6, 6);
        frame[12] = (byte) 0x88;
        frame[13] = (byte) 0xb5;
        System.arraycopy(body, 0, frame, 14, body.length);
        return frame;
    }

    private static byte[] receiveFrame(PeerChannel peer) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < until) {
            if (!peer.ready(Duration.ofMillis(100))) continue;
            var packet = peer.receivePacket();
            if (packet instanceof TunnelCodec.Frames frames && frames.frames().size() == 1)
                return frames.frames().getFirst();
            if (packet instanceof TunnelCodec.Keepalive k && !k.reply())
                peer.send(TunnelCodec.keepalive(k.sequence(), true));
        }
        throw new SocketTimeoutException("Ethernet deadline");
    }

    private static void peerExchange(Endpoint creator, Endpoint peer, String network)
            throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        Membership.Peer receiver = null, sender = null;
        while (System.nanoTime() < until) {
            creator.pump();
            peer.pump();
            receiver = eligible(creator, peer.identity.rid(), network);
            sender = eligible(peer, creator.identity.rid(), network);
            if (receiver != null && sender != null) break;
        }
        if (receiver == null || sender == null)
            throw new IOException("authenticated roster discovery deadline");
        report("LIVE_DISCOVER_BOTH_AUTHENTICATED_OK");
        Membership.Peer target = receiver, source = sender;
        try (Resources resources = new Resources();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var outgoing =
                    executor.submit(
                            () ->
                                    PeerConnector.connect(
                                            creator.identity,
                                            creator.attachment.vip(),
                                            target,
                                            ServiceConfig.PORT,
                                            new PeerConnector.Policy(true, true, true),
                                            resources::own));
            Future<PeerChannel> incoming = null;
            until = System.nanoTime() + Duration.ofSeconds(48).toNanos();
            while (System.nanoTime() < until) {
                creator.pump();
                peer.pump();
                if (incoming == null && peer.hub.pendingRids().contains(creator.identity.rid())) {
                    var setup = peer.hub.take(creator.identity.rid()).orElseThrow();
                    resources.own(setup);
                    incoming =
                            executor.submit(
                                    () ->
                                            PeerConnector.accept(
                                                    setup,
                                                    peer.identity,
                                                    peer.attachment.vip(),
                                                    source,
                                                    peer.attachment.session().localAddress(),
                                                    peer.attachment.uesHosts(),
                                                    new PeerConnector.Policy(true, true, true),
                                                    record -> {
                                                        if (!peer.outbound.offer(record))
                                                            throw new IllegalStateException(
                                                                    "control advertisement queue"
                                                                        + " full");
                                                    },
                                                    resources::own));
                }
                if (outgoing.isDone() && incoming != null && incoming.isDone()) break;
            }
            if (incoming == null) throw new IOException("incoming offer not observed");
            try (var connected = outgoing.get(1, TimeUnit.SECONDS);
                    var accepted = incoming.get(1, TimeUnit.SECONDS)) {
                report(
                        "LIVE_PEER_AUTH_SERVICE_OK outgoing="
                                + connected.channel().path()
                                + " incoming="
                                + accepted.path());
                byte[] forward =
                        ethernet(
                                connected.channel().peerMac(),
                                TunnelCodec.mac(creator.attachment.vip()),
                                "OpenRadBridge synthetic forward Ethernet");
                if (!connected.channel().sendFrame(forward)
                        || !Arrays.equals(forward, receiveFrame(accepted)))
                    throw new IOException("forward Ethernet mismatch");
                byte[] reverse =
                        ethernet(
                                accepted.peerMac(),
                                TunnelCodec.mac(peer.attachment.vip()),
                                "OpenRadBridge synthetic reverse Ethernet");
                if (!accepted.sendFrame(reverse)
                        || !Arrays.equals(reverse, receiveFrame(connected.channel())))
                    throw new IOException("reverse Ethernet mismatch");
                report("LIVE_ETHERNET_BIDIRECTIONAL_OK");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !args[0].equals("--isolated-profiles"))
            throw new IllegalArgumentException("explicit isolated profile paths required");
        Path creatorDirectory = Path.of(args[1]).toAbsolutePath().normalize(),
                peerDirectory = Path.of(args[2]).toAbsolutePath().normalize(),
                evidence = Path.of(args[3]).toAbsolutePath().normalize();
        if (!creatorDirectory.getFileName().toString().equals("live-control-2")
                || !peerDirectory.getFileName().toString().equals("live-peer")
                || !creatorDirectory.getParent().equals(peerDirectory.getParent()))
            throw new IllegalArgumentException(
                    "only the designated disposable profiles are allowed");
        Path marker = peerDirectory.resolve(".openradbridge-devtest");
        if (Files.exists(peerDirectory) && !Files.isRegularFile(marker))
            throw new IllegalArgumentException("peer directory is not a marked disposable profile");
        Files.createDirectories(peerDirectory);
        if (!Files.exists(marker)) Files.createFile(marker);
        String name = "OpenRadBridge-test-" + UUID.randomUUID().toString().substring(0, 18),
                networkId = null;
        char[] password = HexFormat.of().formatHex(Crypto.random(24)).toCharArray();
        Endpoint creator = null, peer = null;
        Identity creatorIdentity = null, peerIdentity = null;
        String phase = "LOAD_ISOLATED_IDENTITIES";
        boolean createRequested = false;
        try {
            creatorIdentity = Identity.load(creatorDirectory.resolve("identity.json"));
            peerIdentity =
                    Provisioner.loadOrRegister(
                            peerDirectory,
                            "OpenRadBridge-DevPeer",
                            ServiceConfig.BOOTSTRAP,
                            ServiceConfig.PORT,
                            s -> {});
            report("LIVE_ISOLATED_IDENTITIES_READY");
            phase = "ATTACH_BOTH";
            creator = new Endpoint(creatorIdentity);
            peer = new Endpoint(peerIdentity);
            report(
                    "LIVE_ATTACH_BOTH_OK creatorVip="
                            + creator.attachment.vip().getHostAddress()
                            + " peerVip="
                            + peer.attachment.vip().getHostAddress());
            phase = "CREATE_PRIVATE";
            try (var create = NetworkManagement.createPrivate(name, password.clone(), 100, 100)) {
                createRequested = true;
                var result = manage(creator, peer, create);
                if (!result.success()) {
                    report("LIVE_CREATE_PRIVATE_REFUSED code=" + result.errorCode());
                    throw new IOException("network creation refused");
                }
                networkId = result.networkId();
            }
            report("LIVE_CREATE_PRIVATE_OK name=" + name + " guid=" + networkId);
            phase = "JOIN_PRIVATE";
            join(creator, peer, name, password.clone());
            report("LIVE_JOIN_PRIVATE_AUTHENTICATED_OK");
            phase = "PEER_ETHERNET";
            peerExchange(creator, peer, name);
            report("LIVE_PRIVATE_NETWORK_SMOKE_OK");
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            report(
                    "LIVE_PRIVATE_NETWORK_SMOKE_FAILED phase="
                            + phase
                            + " exception="
                            + cause.getClass().getSimpleName());
        } finally {
            Arrays.fill(password, '\0');
            if (createRequested && networkId == null && creatorIdentity != null) {
                try {
                    if (creator != null) quietClose(creator);
                    creator = new Endpoint(creatorIdentity);
                    networkId =
                            creator.attachment.membership().networks().values().stream()
                                    .filter(n -> n.name().equals(name))
                                    .map(Membership.Network::id)
                                    .findFirst()
                                    .orElse(null);
                    if (networkId == null) report("LIVE_TEST_NETWORK_NOT_PRESENT_AFTER_REATTACH");
                } catch (Exception cleanup) {
                    report(
                            "LIVE_TEST_NETWORK_LOOKUP_REQUIRED name="
                                    + name
                                    + " exception="
                                    + cleanup.getClass().getSimpleName());
                }
            }
            if (peer != null && networkId != null) {
                try (var leave = NetworkManagement.leave(networkId, 199, 199)) {
                    var result = manage(peer, creator, leave);
                    report(
                            result.success()
                                    ? "LIVE_TEST_PEER_LEFT_NETWORK_OK"
                                    : "LIVE_TEST_PEER_LEAVE_REFUSED code=" + result.errorCode());
                } catch (Exception cleanup) {
                    report(
                            "LIVE_TEST_PEER_LEAVE_FAILED exception="
                                    + cleanup.getClass().getSimpleName());
                }
            }
            if (creator != null && networkId != null) {
                try (var delete = NetworkManagement.delete(networkId, 200, 200)) {
                    var result = manage(creator, peer, delete);
                    report(
                            result.success()
                                    ? "LIVE_TEST_NETWORK_DELETED_OK"
                                    : "LIVE_TEST_NETWORK_DELETE_REFUSED code="
                                            + result.errorCode());
                } catch (Exception failure) {
                    quietClose(creator);
                    creator = null;
                    try {
                        creator = new Endpoint(creatorIdentity);
                        try (var delete = NetworkManagement.delete(networkId, 201, 201)) {
                            var result = manage(creator, null, delete);
                            report(
                                    result.success()
                                            ? "LIVE_TEST_NETWORK_DELETED_AFTER_REATTACH_OK"
                                            : "LIVE_TEST_NETWORK_DELETE_REFUSED code="
                                                    + result.errorCode());
                        }
                    } catch (Exception cleanup) {
                        report(
                                "LIVE_TEST_NETWORK_CLEANUP_REQUIRED exception="
                                        + cleanup.getClass().getSimpleName());
                    }
                }
            }
            if (peer != null) quietClose(peer);
            if (creator != null) quietClose(creator);
            if (peerIdentity != null) peerIdentity.close();
            if (creatorIdentity != null) creatorIdentity.close();
            Files.createDirectories(evidence.getParent());
            Files.writeString(
                    evidence,
                    String.join(System.lineSeparator(), EVIDENCE) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }
    }
}
