import static io.github.gringoestrangeiro.openradbridge.protocol.Wire.*;

import io.github.gringoestrangeiro.openradbridge.crypto.Identity;
import io.github.gringoestrangeiro.openradbridge.link.*;
import io.github.gringoestrangeiro.openradbridge.network.*;
import io.github.gringoestrangeiro.openradbridge.peer.*;
import io.github.gringoestrangeiro.openradbridge.tunnel.TunnelCodec;

import java.io.*;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Explicit developer smoke: actual Paper-loaded plugin owns the creator; only the disposable peer
 * runs here.
 */
public final class LivePluginMinecraftSmoke {
    private static final List<String> EVIDENCE = new CopyOnWriteArrayList<>();
    private static boolean expectLan;

    private static void verifyLan(PeerChannel channel, Inet4Address creatorVip) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (!channel.ready(Duration.ofMillis(100))) continue;
            var packet = channel.receivePacket();
            if (packet instanceof TunnelCodec.Keepalive k && !k.reply())
                channel.send(TunnelCodec.keepalive(k.sequence(), true));
            if (!(packet instanceof TunnelCodec.Frames frames)) continue;
            for (byte[] raw : frames.frames()) {
                var ethernet = EthernetFrame.parse(raw);
                byte[] ip = ethernet.payload();
                if (ethernet.etherType() != EthernetFrame.IPV4 || ip.length < 28 || ip[9] != 17)
                    continue;
                if (Checksum.u32(ip, 12) != ipInt(creatorVip)
                        || Checksum.u32(ip, 16) != 0xe000023c
                        || Checksum.u16(ip, 22) != 4445
                        || Checksum.onesComplement(ip, 0, 20) != 0
                        || !Arrays.equals(ethernet.sourceMac(), TunnelCodec.mac(creatorVip)))
                    throw new IOException("LAN virtual endpoint/header mismatch");
                String message = new String(ip, 28, ip.length - 28, StandardCharsets.UTF_8);
                if (!message.equals("[MOTD]ENTRE AQUI!!![/MOTD][AD]25565[/AD]"))
                    throw new IOException("LAN MOTD or rewritten port mismatch");
                report("LIVE_LANBROADCASTER_MULTICAST_OVER_AUTHENTICATED_TUNNEL_OK");
                report(
                        "LIVE_LAN_SOURCE_VIP_OK destination=224.0.2.60:4445 backendPort=25575"
                            + " virtualPort=25565");
                report("LIVE_LAN_MOTD_OK value=ENTRE AQUI!!!");
                return;
            }
        }
        throw new IOException("LANBroadcaster advertisement deadline");
    }

    private static void report(String line) {
        EVIDENCE.add(line);
        System.out.println(line);
    }

    private static void join(LivePrivateNetworkSmoke.Endpoint peer, String network, char[] password)
            throws Exception {
        try (NetworkJoin operation = new NetworkJoin(network, password, 701, 701)) {
            peer.attachment.session().send(operation.start());
            long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!operation.complete() && System.nanoTime() < until) {
                byte[] data = peer.pump();
                if (data != null) {
                    byte[] reply = operation.handle(data, peer.attachment.membership());
                    if (reply != null) peer.attachment.session().send(reply);
                }
            }
            if (!operation.complete() || operation.pendingApproval()) {
                throw new IOException("disposable peer private join not approved");
            }
        }
    }

    private static void exercise(
            LivePrivateNetworkSmoke.Endpoint peer,
            String network,
            long creatorRid,
            Inet4Address creatorVip)
            throws Exception {
        try (var resources = new LivePrivateNetworkSmoke.Resources();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PeerChannel> accepted = null;
            long until = System.nanoTime() + Duration.ofSeconds(70).toNanos();
            while (System.nanoTime() < until) {
                peer.pump();
                if (accepted == null && peer.hub.pendingRids().contains(creatorRid)) {
                    Membership.Peer creator =
                            LivePrivateNetworkSmoke.eligible(peer, creatorRid, network);
                    if (creator != null) {
                        if (!creator.vip().equals(creatorVip)) {
                            throw new IOException(
                                    "plugin creator VIP differs from private test state");
                        }
                        var setup = peer.hub.take(creatorRid).orElseThrow();
                        resources.own(setup);
                        accepted =
                                executor.submit(
                                        () ->
                                                PeerConnector.accept(
                                                        setup,
                                                        peer.identity,
                                                        peer.attachment.vip(),
                                                        creator,
                                                        peer.attachment.session().localAddress(),
                                                        peer.attachment.uesHosts(),
                                                        new PeerConnector.Policy(true, true, true),
                                                        record -> {
                                                            if (!peer.outbound.offer(record)) {
                                                                throw new IllegalStateException(
                                                                        "test advertisement queue"
                                                                                + " full");
                                                            }
                                                        },
                                                        resources::own));
                    }
                }
                if (accepted != null && accepted.isDone()) break;
            }
            if (accepted == null) throw new IOException("plugin outgoing peer offer deadline");
            try (PeerChannel channel = accepted.get(1, TimeUnit.SECONDS)) {
                report("LIVE_PAPER_PLUGIN_AUTHENTICATED_PEER_OK path=" + channel.path());
                // Give Paper's worker time to publish its authenticated channel before TCP SYN.
                long ready = System.nanoTime() + Duration.ofMillis(200).toNanos();
                while (System.nanoTime() < ready) peer.pump();
                if (expectLan) verifyLan(channel, creatorVip);
                VirtualMinecraftSmoke.configure(ipInt(creatorVip), ipInt(peer.attachment.vip()));
                int status = VirtualMinecraftSmoke.exchange(channel, 49152, 1, 0);
                report("LIVE_PAPER_PLUGIN_STATUS_OK bytes=" + status);
                int encryption = VirtualMinecraftSmoke.exchange(channel, 49153, 2, 1);
                report("LIVE_PAPER_PLUGIN_ONLINE_ENCRYPTION_REQUEST_OK bytes=" + encryption);
                report("LIVE_PAPER_LOADED_PLUGIN_MINECRAFT_PIPELINE_OK");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if ((args.length != 5 && args.length != 6) || !args[0].equals("--isolated-plugin-test")) {
            throw new IllegalArgumentException("explicit isolated plugin test paths required");
        }
        expectLan = args.length == 6 && args[5].equals("--expect-lan");
        if (args.length == 6 && !expectLan) throw new IllegalArgumentException("unknown test mode");
        Path peerDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        Path creatorDirectory = Path.of(args[2]).toAbsolutePath().normalize();
        Path state = Path.of(args[3]).toAbsolutePath().normalize();
        Path evidence = Path.of(args[4]).toAbsolutePath().normalize();
        if (!peerDirectory.getFileName().toString().equals("live-peer")
                || !creatorDirectory.getFileName().toString().equals("live-control-2")
                || !peerDirectory.getParent().equals(creatorDirectory.getParent())
                || !Files.isRegularFile(peerDirectory.resolve(".openradbridge-devtest"))
                || !Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)
                || Files.size(state) > 65536) {
            throw new IllegalArgumentException("designated isolated test paths required");
        }
        String network = null, networkId = null;
        char[] password = null;
        Identity peerIdentity = null;
        LivePrivateNetworkSmoke.Endpoint peer = null;
        boolean joined = false, success = false;
        String phase = "LOAD_PRIVATE_TEST_STATE";
        try {
            Properties values = new Properties();
            try (Reader reader =
                    new InputStreamReader(
                            Files.newInputStream(state, LinkOption.NOFOLLOW_LINKS),
                            StandardCharsets.UTF_8)) {
                values.load(reader);
            }
            network = values.getProperty("network");
            networkId = values.getProperty("guid");
            String secret = values.getProperty("password");
            if (network == null
                    || networkId == null
                    || secret == null
                    || !"25565".equals(values.getProperty("port"))) {
                throw new IOException("private test state missing required fields");
            }
            password = secret.toCharArray();
            Inet4Address creatorVip = (Inet4Address) literal(values.getProperty("vip", ""));
            values.clear();
            long creatorRid;
            // Read only the disposable creator identity; Paper owns its active control session.
            try (Identity creator = Identity.load(creatorDirectory.resolve("identity.json"))) {
                creatorRid = creator.rid();
            }
            peerIdentity = Identity.load(peerDirectory.resolve("identity.json"));
            phase = "ATTACH_DISPOSABLE_PEER";
            peer = new LivePrivateNetworkSmoke.Endpoint(peerIdentity);
            report("LIVE_PAPER_PLUGIN_TEST_PEER_ATTACHED_OK");
            phase = "JOIN_EXISTING_PRIVATE_NETWORK";
            final String expectedNetworkId = networkId;
            joined = true;
            if (peer.attachment.membership().networks().values().stream()
                    .noneMatch(n -> n.id().equals(expectedNetworkId))) {
                join(peer, network, password.clone());
            }
            report("LIVE_PAPER_PLUGIN_TEST_PEER_PRIVATE_JOIN_OK");
            phase = "PAPER_LOADED_PLUGIN_PIPELINE";
            exercise(peer, network, creatorRid, creatorVip);
            success = true;
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            report(
                    "LIVE_PAPER_PLUGIN_PIPELINE_FAILED phase="
                            + phase
                            + " exception="
                            + cause.getClass().getSimpleName());
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (peer != null && networkId != null && joined) {
                try (NetworkManagement operation = NetworkManagement.leave(networkId, 702, 702)) {
                    var result = LivePrivateNetworkSmoke.manage(peer, null, operation);
                    report(
                            result.success()
                                    ? "LIVE_PAPER_PLUGIN_TEST_PEER_LEFT_OK"
                                    : "LIVE_PAPER_PLUGIN_TEST_PEER_LEAVE_REFUSED code="
                                            + result.errorCode());
                    if (!result.success()) success = false;
                } catch (Exception cleanup) {
                    success = false;
                    report(
                            "LIVE_PAPER_PLUGIN_TEST_PEER_LEAVE_FAILED exception="
                                    + cleanup.getClass().getSimpleName());
                }
            }
            if (peer != null) peer.close();
            if (peerIdentity != null) peerIdentity.close();
            report("LIVE_PAPER_PLUGIN_FRIEND_NETWORK_PRESERVED");
            Files.createDirectories(evidence.getParent());
            Files.writeString(
                    evidence,
                    String.join(System.lineSeparator(), EVIDENCE) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }
        if (!success) System.exit(1);
    }
}
