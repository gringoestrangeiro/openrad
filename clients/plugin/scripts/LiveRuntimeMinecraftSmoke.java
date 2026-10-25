import static io.github.gringoestrangeiro.openradbridge.protocol.Wire.*;

import io.github.gringoestrangeiro.openradbridge.config.*;
import io.github.gringoestrangeiro.openradbridge.crypto.*;
import io.github.gringoestrangeiro.openradbridge.network.*;
import io.github.gringoestrangeiro.openradbridge.peer.*;
import io.github.gringoestrangeiro.openradbridge.runtime.VpnRuntime;
import io.github.gringoestrangeiro.openradbridge.session.*;

import java.io.IOException;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Explicit developer-only live service -> production runtime -> real Paper status/online-login
 * check.
 */
public final class LiveRuntimeMinecraftSmoke {
    private static final List<String> EVIDENCE = new CopyOnWriteArrayList<>();

    private static void report(String line) {
        EVIDENCE.add(line);
        System.out.println(line);
    }

    private static void close(AutoCloseable item) {
        if (item != null) LivePrivateNetworkSmoke.quietClose(item);
    }

    private static void exercise(
            VpnRuntime runtime,
            LivePrivateNetworkSmoke.Endpoint peer,
            String network,
            long creatorRid)
            throws Exception {
        try (var resources = new LivePrivateNetworkSmoke.Resources();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PeerChannel> accepted = null;
            long until = System.nanoTime() + Duration.ofSeconds(65).toNanos();
            while (System.nanoTime() < until) {
                peer.pump();
                if (accepted == null && peer.hub.pendingRids().contains(creatorRid)) {
                    Membership.Peer creator =
                            LivePrivateNetworkSmoke.eligible(peer, creatorRid, network);
                    if (creator != null) {
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
                                                            if (!peer.outbound.offer(record))
                                                                throw new IllegalStateException(
                                                                        "control advertisement"
                                                                            + " queue full");
                                                        },
                                                        resources::own));
                    }
                }
                var snapshot = runtime.snapshot();
                if (snapshot.phase().equals("FAILED")
                        || snapshot.phase().equals("REGISTRATION_RECOVERY_REQUIRED"))
                    throw new IOException("runtime stopped before peer authentication");
                if (accepted != null && accepted.isDone()) break;
            }
            if (accepted == null) throw new IOException("runtime outgoing peer offer deadline");
            try (PeerChannel peerChannel = accepted.get(1, TimeUnit.SECONDS)) {
                until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
                boolean runtimePeerConnected = false;
                while (System.nanoTime() < until) {
                    peer.pump();
                    var snapshot = runtime.snapshot();
                    runtimePeerConnected =
                            snapshot.phase().equals("CONNECTED")
                                    && snapshot.peers().stream()
                                            .anyMatch(
                                                    p ->
                                                            p.rid() == peer.identity.rid()
                                                                    && p.connected());
                    if (runtimePeerConnected) break;
                }
                if (!runtimePeerConnected)
                    throw new IOException("runtime peer did not publish connected");
                var snapshot = runtime.snapshot();
                report(
                        "LIVE_RUNTIME_AUTHENTICATED_PEER_OK path="
                                + peerChannel.path()
                                + " phase="
                                + snapshot.phase());
                VirtualMinecraftSmoke.configure(
                        ipInt((Inet4Address) literal(snapshot.vip())),
                        ipInt(peer.attachment.vip()));
                int status = VirtualMinecraftSmoke.exchange(peerChannel, 49152, 1, 0);
                report("LIVE_RUNTIME_PAPER_STATUS_OK bytes=" + status);
                int encryption = VirtualMinecraftSmoke.exchange(peerChannel, 49153, 2, 1);
                report("LIVE_RUNTIME_PAPER_ONLINE_ENCRYPTION_REQUEST_OK bytes=" + encryption);
                Thread.sleep(150);
                var metrics = runtime.snapshot().bridge();
                if (metrics == null
                        || metrics.acceptedFrames() == 0
                        || metrics.backendFailures() != 0)
                    throw new IOException("runtime bridge metrics rejected");
                report("LIVE_RUNTIME_BRIDGE_METRICS " + metrics);
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
                || !creatorDirectory.getParent().equals(peerDirectory.getParent())
                || !Files.isRegularFile(peerDirectory.resolve(".openradbridge-devtest"))) {
            throw new IllegalArgumentException("only designated disposable profiles are allowed");
        }
        String network = "OpenRadBridge-runtime-" + UUID.randomUUID().toString().substring(0, 18),
                networkId = null,
                phase = "LOAD_TEST_IDENTITIES";
        char[] password = HexFormat.of().formatHex(Crypto.random(24)).toCharArray();
        Identity creatorIdentity = null, peerIdentity = null;
        LivePrivateNetworkSmoke.Endpoint creator = null, peer = null;
        VpnRuntime runtime = null;
        boolean success = false, createdRequested = false;
        try {
            creatorIdentity = Identity.load(creatorDirectory.resolve("identity.json"));
            peerIdentity = Identity.load(peerDirectory.resolve("identity.json"));
            creator = new LivePrivateNetworkSmoke.Endpoint(creatorIdentity);
            peer = new LivePrivateNetworkSmoke.Endpoint(peerIdentity);
            report("LIVE_RUNTIME_TWO_TEST_ATTACHMENTS_OK");
            phase = "CREATE_PRIVATE";
            try (var operation =
                    NetworkManagement.createPrivate(network, password.clone(), 301, 301)) {
                createdRequested = true;
                var result = LivePrivateNetworkSmoke.manage(creator, peer, operation);
                if (!result.success()) {
                    report("LIVE_RUNTIME_CREATE_REFUSED code=" + result.errorCode());
                    throw new IOException("network create refused");
                }
                networkId = result.networkId();
            }
            report("LIVE_RUNTIME_PRIVATE_NETWORK_CREATED name=" + network + " guid=" + networkId);
            phase = "PRIVATE_JOIN";
            LivePrivateNetworkSmoke.join(creator, peer, network, password.clone());
            report("LIVE_RUNTIME_PEER_PRIVATE_JOIN_OK");
            phase = "START_PRODUCTION_RUNTIME";
            creator.close();
            creator = null;
            BridgeSettings bridge =
                    new BridgeSettings(
                            true, "127.0.0.1", 25575, 25565, 4, 8, 15, "", 0, "", 0, 500);
            VpnSettings settings =
                    new VpnSettings(
                            bridge,
                            creatorIdentity.nodeName(),
                            ServiceConfig.BOOTSTRAP,
                            ServiceConfig.PORT,
                            List.of(
                                    new VpnSettings.Network(
                                            network, "", Set.of(peerIdentity.rid()), Set.of())),
                            Set.of(peerIdentity.rid()),
                            Set.of(),
                            true,
                            true,
                            true,
                            4,
                            1,
                            3);
            runtime =
                    new VpnRuntime(
                            settings,
                            creatorDirectory,
                            NetworkSecrets.load(creatorDirectory),
                            message -> report("LIVE_RUNTIME_EVENT " + message));
            runtime.start();
            phase = "LIVE_RUNTIME_PAPER_PIPELINE";
            exercise(runtime, peer, network, creatorIdentity.rid());
            report("LIVE_RUNTIME_MINECRAFT_PIPELINE_OK");
            success = true;
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            report(
                    "LIVE_RUNTIME_MINECRAFT_PIPELINE_FAILED phase="
                            + phase
                            + " exception="
                            + cause.getClass().getSimpleName());
        } finally {
            Arrays.fill(password, '\0');
            if (runtime != null) {
                try {
                    runtime.close();
                    boolean stopped = runtime.awaitStopped(Duration.ofSeconds(12));
                    report("LIVE_RUNTIME_STOPPED " + stopped);
                    if (!stopped) success = false;
                } catch (Throwable cleanup) {
                    success = false;
                    report(
                            "LIVE_RUNTIME_STOP_FAILED exception="
                                    + cleanup.getClass().getSimpleName());
                }
            }
            if (creator == null && creatorIdentity != null && createdRequested) {
                try {
                    creator = new LivePrivateNetworkSmoke.Endpoint(creatorIdentity);
                } catch (Exception cleanup) {
                    report(
                            "LIVE_RUNTIME_CLEANUP_ATTACH_FAILED exception="
                                    + cleanup.getClass().getSimpleName());
                }
            }
            if (networkId == null && createdRequested && creator != null)
                networkId =
                        creator.attachment.membership().networks().values().stream()
                                .filter(n -> n.name().equals(network))
                                .map(Membership.Network::id)
                                .findFirst()
                                .orElse(null);
            if (peer != null && networkId != null) {
                try (var leave = NetworkManagement.leave(networkId, 398, 398)) {
                    var result = LivePrivateNetworkSmoke.manage(peer, creator, leave);
                    report(
                            result.success()
                                    ? "LIVE_RUNTIME_TEST_PEER_LEFT_OK"
                                    : "LIVE_RUNTIME_TEST_PEER_LEAVE_REFUSED code="
                                            + result.errorCode());
                } catch (Exception cleanup) {
                    report(
                            "LIVE_RUNTIME_TEST_PEER_LEAVE_FAILED exception="
                                    + cleanup.getClass().getSimpleName());
                }
            }
            if (creator != null && networkId != null) {
                try (var delete = NetworkManagement.delete(networkId, 399, 399)) {
                    var result = LivePrivateNetworkSmoke.manage(creator, peer, delete);
                    report(
                            result.success()
                                    ? "LIVE_RUNTIME_TEST_NETWORK_DELETED_OK"
                                    : "LIVE_RUNTIME_TEST_NETWORK_DELETE_REFUSED code="
                                            + result.errorCode());
                    if (!result.success()) success = false;
                } catch (Exception cleanup) {
                    success = false;
                    report(
                            "LIVE_RUNTIME_TEST_NETWORK_CLEANUP_REQUIRED guid="
                                    + networkId
                                    + " exception="
                                    + cleanup.getClass().getSimpleName());
                }
            } else if (createdRequested) {
                success = false;
                report("LIVE_RUNTIME_TEST_NETWORK_CLEANUP_LOOKUP_REQUIRED name=" + network);
            }
            close(peer);
            close(creator);
            close(peerIdentity);
            close(creatorIdentity);
            Files.createDirectories(evidence.getParent());
            Files.writeString(
                    evidence,
                    String.join(System.lineSeparator(), EVIDENCE) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }
        if (!success) System.exit(1);
    }
}
