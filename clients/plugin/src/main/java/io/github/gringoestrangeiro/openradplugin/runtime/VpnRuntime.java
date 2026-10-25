package io.github.gringoestrangeiro.openradplugin.runtime;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.bridge.VirtualTcpBridge;
import io.github.gringoestrangeiro.openradplugin.config.*;
import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.discovery.LanDiscovery;
import io.github.gringoestrangeiro.openradplugin.network.*;
import io.github.gringoestrangeiro.openradplugin.peer.*;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradplugin.security.InboundFirewall.PeerGrant;
import io.github.gringoestrangeiro.openradplugin.session.*;
import io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** One attachment generation. No Bukkit calls; all sockets/state belong to workers. */
public final class VpnRuntime implements AutoCloseable {
    /** A reload may legitimately keep the previous generation's lock region for a few seconds. */
    private static final Duration IDENTITY_LOCK_TIMEOUT = Duration.ofSeconds(30);

    public record PeerView(long rid, String name, String vip, String path, boolean connected) {}

    public record MinecraftCatalog(
            String state,
            List<MinecraftNetworks.Entry> entries,
            Set<String> selected,
            List<String> problems) {
        public MinecraftCatalog {
            entries = List.copyOf(entries);
            selected = Set.copyOf(selected);
            problems = List.copyOf(problems);
        }
    }

    public record Snapshot(
            String phase,
            String vip,
            List<String> networks,
            List<PeerView> peers,
            String lastFailure,
            VirtualTcpBridge.Snapshot bridge) {
        public Snapshot {
            networks = List.copyOf(networks);
            peers = List.copyOf(peers);
        }
    }

    private final VpnSettings settings;
    private final Path directory;
    private final NetworkSecrets secrets;
    private final Consumer<String> log;
    private final byte[] serviceModulus;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong lastIncomingRejectLog = new AtomicLong();
    private final AtomicReference<ControlSession> control = new AtomicReference<>();
    private final ConcurrentHashMap<Long, PeerWorker> workers = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<byte[]> controlOutbound = new ArrayBlockingQueue<>(128);
    private final ThreadPoolExecutor peerExecutor;
    private final Thread worker;
    private volatile VirtualTcpBridge bridge;
    private volatile LanDiscovery discovery;
    private volatile String discoveryState = "OFFLINE";
    private volatile Snapshot snapshot =
            new Snapshot("OFFLINE", "", List.of(), List.of(), "", null);
    private volatile Map<Long, Membership.Peer> eligible = Map.of();
    private volatile Map<Long, Set<String>> authorizedNetworks = Map.of();
    private volatile Map<String, Membership.Network> currentNetworks = Map.of();
    private volatile Map<String, VpnSettings.Network> activeTargets = Map.of();
    private volatile PeerReachability reachability;
    private volatile List<String> networkProblems = List.of();
    private volatile MinecraftCatalog minecraftCatalog =
            new MinecraftCatalog("OFFLINE", List.of(), Set.of(), List.of());

    public VpnRuntime(
            VpnSettings settings, Path directory, NetworkSecrets secrets, Consumer<String> log) {
        this(settings, directory, secrets, log, ServiceConfig.modulus());
    }

    VpnRuntime(
            VpnSettings settings,
            Path directory,
            NetworkSecrets secrets,
            Consumer<String> log,
            byte[] serviceModulus) {
        this.settings = settings;
        this.directory = directory;
        this.secrets = secrets;
        this.log = log;
        this.serviceModulus = serviceModulus.clone();
        peerExecutor =
                new ThreadPoolExecutor(
                        0,
                        settings.peerLimit(),
                        0,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        Thread.ofVirtual().name("orb-peer-", 0).factory(),
                        new ThreadPoolExecutor.AbortPolicy());
        worker = Thread.ofPlatform().name("OpenRad Plugin-control").daemon().unstarted(this::run);
    }

    public void start() {
        worker.start();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public MinecraftCatalog minecraftCatalog() {
        return minecraftCatalog;
    }

    public PeerReachability.Snapshot reachabilitySnapshot() {
        var current = reachability;
        return current == null
                ? new PeerReachability.Snapshot(0, 0, 0, 0, 0, Map.of())
                : current.snapshot();
    }

    public LanDiscovery.Snapshot lanSnapshot() {
        LanDiscovery current = discovery;
        return current == null
                ? new LanDiscovery.Snapshot(discoveryState, 0, 0, 0)
                : current.snapshot();
    }

    private void own(ControlSession session) {
        control.set(session);
        if (stopped.get()) closeQuietly(session);
    }

    @SuppressWarnings("try") // The lock resource is held open by the channel; close releases it.
    private void run() {
        int failures = 0;
        try {
            Files.createDirectories(directory);
            try (FileChannel lockFile =
                            FileChannel.open(
                                    directory.resolve("identity.lock"),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE);
                    FileLock lock =
                            awaitIdentityLock(
                                    lockFile,
                                    stopped,
                                    () ->
                                            publish(
                                                    "RETRY_WAIT",
                                                    "",
                                                    List.of(),
                                                    "identity lock busy"))) {
                while (!stopped.get()) {
                    long attachedAt = 0;
                    try {
                        publish("AUTHENTICATING", "", List.of(), "");
                        try (Identity identity =
                                        Provisioner.loadOrRegister(
                                                directory,
                                                settings.nodeName(),
                                                settings.bootstrap(),
                                                settings.controlPort(),
                                                this::own,
                                                serviceModulus);
                                Attachment attached =
                                        Attachment.connect(
                                                identity,
                                                settings.controlPort(),
                                                settings.nodeName(),
                                                this::own,
                                                serviceModulus)) {
                            own(attached.session());
                            attachedAt = System.nanoTime();
                            runAttached(identity, attached);
                            failures = 0;
                        }
                    } catch (Exception e) {
                        if (stopped.get()) break;
                        String reason = safeFailure(e);
                        publish("RETRY_WAIT", "", List.of(), reason);
                        log.accept("VPN session interrupted: " + reason);
                        cleanupSession();
                        if (Files.exists(
                                        directory.resolve("registration.pending"),
                                        LinkOption.NOFOLLOW_LINKS)
                                && !Files.exists(
                                        directory.resolve("identity.json"),
                                        LinkOption.NOFOLLOW_LINKS)) {
                            publish("REGISTRATION_RECOVERY_REQUIRED", "", List.of(), reason);
                            break;
                        }
                        // A session that stayed up for a minute was healthy: restart the
                        // backoff from the base delay, as the Rust daemon does.
                        if (attachedAt != 0
                                && System.nanoTime() - attachedAt
                                        >= TimeUnit.SECONDS.toNanos(60)) failures = 0;
                        failures = Math.min(10, failures + 1);
                        long seconds =
                                Math.min(
                                        settings.retryMaxSeconds(),
                                        (long) settings.retryBaseSeconds() << (failures - 1));
                        long until =
                                System.nanoTime()
                                        + TimeUnit.SECONDS.toNanos(seconds)
                                        + ThreadLocalRandom.current().nextLong(250_000_000L);
                        while (!stopped.get() && System.nanoTime() < until) Thread.sleep(100);
                    } finally {
                        cleanupSession();
                    }
                }
            }
        } catch (Exception e) {
            if (!stopped.get()) {
                String reason = safeFailure(e);
                publish("FAILED", "", List.of(), reason);
                log.accept("VPN stopped: " + reason);
            }
        } finally {
            cleanupSession();
            peerExecutor.shutdownNow();
            secrets.close();
            if (stopped.get()) publish("STOPPED", "", List.of(), "");
        }
    }

    /** A join deadline is scoped to one public discovery target, not the VPN attachment. */
    private static final class JoinTimeout extends IOException {
        private static final long serialVersionUID = 1L;
        JoinTimeout() {
            super("network join timeout");
        }
    }

    /** The profile lock stayed busy for the full budget; actionable and safe to log. */
    private static final class IdentityLockBusy extends IOException {
        private static final long serialVersionUID = 1L;
        IdentityLockBusy() {
            super("identity lock is held by another generation");
        }
    }

    private void runAttached(Identity identity, Attachment attached) throws Exception {
        ControlSession session = attached.session();
        Membership membership = attached.membership();
        try (IncomingHub hub = new IncomingHub()) {
            Map<String, VpnSettings.Network> targets = new LinkedHashMap<>();
            for (var network : settings.networks()) targets.put(network.name(), network);
            Set<String> automatic = new HashSet<>();
            if (settings.minecraftNetworks().enabled()) {
                minecraftCatalog = new MinecraftCatalog("LOADING", List.of(), Set.of(), List.of());
                try {
                    var entries = discoverMinecraft(session, membership, hub);
                    var selected =
                            MinecraftNetworks.select(
                                    entries,
                                    settings.minecraftNetworks().select(),
                                    settings.minecraftNetworks().maxNetworks());
                    for (var entry : selected.networks()) {
                        if (!targets.containsKey(entry.name())) automatic.add(entry.name());
                        targets.putIfAbsent(
                                entry.name(),
                                new VpnSettings.Network(entry.name(), "", Set.of(), Set.of()));
                    }
                    List<String> problems =
                            selected.unmatched().stream()
                                    .map(s -> "Selector absent from catalog: " + s)
                                    .toList();
                    minecraftCatalog =
                            new MinecraftCatalog(
                                    "JOINING",
                                    entries,
                                    selected.networks().stream()
                                            .map(MinecraftNetworks.Entry::name)
                                            .collect(java.util.stream.Collectors.toSet()),
                                    problems);
                    try {
                        saveMinecraftCatalog(entries);
                    } catch (IOException e) {
                        log.accept(
                                "Could not save minecraft-networks.txt ("
                                        + e.getClass().getSimpleName()
                                        + ")");
                    }
                    log.accept(
                            "Minecraft catalog: "
                                    + entries.size()
                                    + " networks; "
                                    + selected.networks().size()
                                    + " selected.");
                } catch (IOException | IllegalArgumentException e) {
                    if (stopped.get()) return;
                    minecraftCatalog =
                            new MinecraftCatalog(
                                    "FAILED",
                                    minecraftCatalog.entries(),
                                    Set.of(),
                                    List.of(safeFailure(e)));
                    log.accept(
                            "Minecraft catalog unavailable: "
                                    + safeFailure(e)
                                    + "; explicit networks remain active.");
                }
            } else
                minecraftCatalog = new MinecraftCatalog("DISABLED", List.of(), Set.of(), List.of());
            activeTargets = Map.copyOf(targets);
            networkProblems = List.of();
            long id = 1;
            int seq = 1;
            long lastJoinHeartbeat = System.nanoTime();
            for (var n : targets.values()) {
                if (stopped.get()) return;
                if (membership.networks().values().stream()
                        .anyMatch(v -> v.name().equals(n.name()))) continue;
                try (NetworkJoin join =
                        new NetworkJoin(n.name(), secrets.password(n.secret()), id++, seq++)) {
                    session.send(join.start());
                    publish(
                            "JOINING_NETWORKS",
                            attached.vip().getHostAddress(),
                            membership.networks().values().stream()
                                    .map(Membership.Network::name)
                                    .toList(),
                            "");
                    long until = System.nanoTime()
                            + Duration.ofSeconds(automatic.contains(n.name()) ? 8 : 25).toNanos();
                    while (!join.complete()) {
                        if (stopped.get()) return;
                        if (System.nanoTime() > until)
                            throw new JoinTimeout();
                        if (System.nanoTime() - lastJoinHeartbeat
                                >= Duration.ofSeconds(10).toNanos()) {
                            session.send(op(4));
                            lastJoinHeartbeat = System.nanoTime();
                        }
                        if (!session.ready(Duration.ofMillis(100))) continue;
                        byte[] data = session.receive();
                        dispatchMembership(data, membership, hub);
                        byte[] reply = join.handle(data, membership);
                        if (reply != null) session.send(reply);
                    }
                    if (join.pendingApproval())
                        log.accept(
                                "Configured network awaits administrator approval; traffic stays"
                                        + " denied.");
                } catch (JoinTimeout e) {
                    if (!automatic.contains(n.name())) throw e;
                    var catalog = minecraftCatalog;
                    List<String> problems = new ArrayList<>(catalog.problems());
                    problems.add(n.name() + ": join timeout");
                    minecraftCatalog =
                            new MinecraftCatalog(
                                    catalog.state(),
                                    catalog.entries(),
                                    catalog.selected(),
                                    problems);
                    log.accept("Public Minecraft network timed out: " + n.name()
                            + "; continuing with remaining networks.");
                } catch (NetworkJoin.Refused e) {
                    if (!automatic.contains(n.name())) {
                        // Retrying cannot fix a refusal (wrong password, unknown network): keep
                        // the session and every other network instead of reconnecting forever.
                        List<String> problems = new ArrayList<>(networkProblems);
                        problems.add(n.name() + ": " + e.getMessage());
                        networkProblems = List.copyOf(problems);
                        log.accept(
                                "Configured network not joined: "
                                        + n.name()
                                        + " - "
                                        + e.getMessage());
                        continue;
                    }
                    var catalog = minecraftCatalog;
                    List<String> problems = new ArrayList<>(catalog.problems());
                    problems.add(n.name() + ": join refused (" + e.code() + ")");
                    minecraftCatalog =
                            new MinecraftCatalog(
                                    catalog.state(),
                                    catalog.entries(),
                                    catalog.selected(),
                                    problems);
                    log.accept(
                            "Public Minecraft network refused join: "
                                    + n.name()
                                    + " ("
                                    + e.code()
                                    + ")");
                }
                Thread.sleep(50);
            }
            if (settings.minecraftNetworks().enabled()
                    && minecraftCatalog.state().equals("JOINING")) {
                var catalog = minecraftCatalog;
                minecraftCatalog =
                        new MinecraftCatalog(
                                catalog.problems().isEmpty() ? "READY" : "READY_WITH_WARNINGS",
                                catalog.entries(),
                                catalog.selected(),
                                catalog.problems());
            }
            int ip = ipInt(attached.vip());
            var b = settings.bridge();
            var limits =
                    new VirtualTcpBridge.Limits(
                            b.maxPlayers(),
                            Math.min(b.maxPlayers(), 8),
                            2048,
                            256 * 1024,
                            64L * 1024 * 1024,
                            Duration.ofSeconds(b.timeoutSeconds()),
                            Duration.ofSeconds(b.timeoutSeconds()),
                            Duration.ofMinutes(3),
                            Duration.ofMinutes(2),
                            Duration.ofSeconds(30),
                            8,
                            b.maxPendingTcp());
            bridge =
                    new VirtualTcpBridge(
                            ip,
                            TunnelCodec.mac(attached.vip()),
                            b.virtualPort(),
                            new InetSocketAddress(literal(b.backendHost()), b.backendPort()),
                            limits,
                            (rid, frame) -> {
                                PeerWorker w = workers.get(rid);
                                if (w != null && w.channel != null) w.frames.offer(frame);
                            });
            discoveryState = settings.lanDiscovery() ? "UNAVAILABLE" : "DISABLED";
            if (settings.lanDiscovery()) {
                try {
                    discovery = new LanDiscovery(attached.vip(), b.backendPort(), b.virtualPort());
                } catch (IOException e) {
                    log.accept(
                            "LAN discovery unavailable ("
                                    + e.getClass().getSimpleName()
                                    + "); check local IPv4 multicast/UDP 4445. VPN remains"
                                    + " connected.");
                }
            }
            long heartbeat = System.nanoTime() + Duration.ofSeconds(10).toNanos(),
                    lastControl = System.nanoTime();
            PeerReachability attempts = new PeerReachability();
            reachability = attempts;
            long discoveryReport = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            long nextPeerScan = 0L, nextSnapshot = 0L;
            while (!stopped.get()) {
                long now = System.nanoTime();
                if (now >= nextPeerScan) {
                    nextPeerScan = now + Duration.ofMillis(200).toNanos();
                Map<String, Membership.Network> knownNetworks = membership.networks();
                Map<Long, Membership.Peer> next = new HashMap<>();
                Map<Long, Set<String>> nextNetworks = new HashMap<>();
                for (var p :
                        membership.eligible(
                                activeTargets.keySet(),
                                settings.allowedRids(),
                                settings.deniedRids())) {
                    Set<String> admitted = new HashSet<>();
                    for (String nid : p.networks()) {
                        var n = knownNetworks.get(nid);
                        if (n != null
                                && networkAllows(n.name(), p.rid())
                                && !Integer.valueOf(0).equals(membership.role(nid, identity.rid()))
                                && !Integer.valueOf(0).equals(membership.role(nid, p.rid())))
                            admitted.add(nid);
                    }
                    if (!admitted.isEmpty()) {
                        next.put(p.rid(), p);
                        nextNetworks.put(p.rid(), Set.copyOf(admitted));
                    }
                }
                Map<Inet4Address, Integer> vipCounts = new HashMap<>();
                for (var p : next.values()) vipCounts.merge(p.vip(), 1, Integer::sum);
                next.values()
                        .removeIf(
                                p ->
                                        p.rid() == 0
                                                || (p.vip().getAddress()[0] & 255) != 26
                                                || p.vip().equals(attached.vip())
                                                || vipCounts.get(p.vip()) != 1);
                nextNetworks.keySet().retainAll(next.keySet());
                eligible = Map.copyOf(next);
                authorizedNetworks = Map.copyOf(nextNetworks);
                currentNetworks = knownNetworks;
                attempts.retain(next.keySet());
                for (var w : List.copyOf(workers.values()))
                    if (!stillBound(
                            w.peer,
                            next.get(w.peer.rid()),
                            w.allowedNetworks,
                            nextNetworks.get(w.peer.rid()))) {
                        workers.remove(w.peer.rid(), w);
                        w.close();
                        hub.finish(w.peer.rid());
                        bridge.revokePeer(w.peer.rid());
                    }
                hub.expire();
                Set<Long> incoming = hub.pendingRids();
                for (long rid : incoming) {
                    if (!next.containsKey(rid)) {
                        hub.reject(rid);
                        continue;
                    }
                    PeerWorker existing = workers.get(rid);
                    if (existing != null) {
                        if (existing.channel != null) {
                            hub.reject(rid);
                        } else if (Long.compareUnsigned(identity.rid(), rid) > 0) {
                            workers.remove(rid, existing);
                            existing.close();
                        }
                    }
                }
                int connecting =
                        (int) workers.values().stream().filter(w -> w.channel == null).count();
                int slots =
                        Math.max(
                                0,
                                Math.min(
                                        settings.handshakeLimit() - connecting,
                                        settings.peerLimit() - workers.size()));
                for (long rid :
                        slots == 0
                                ? List.<Long>of()
                                : attempts.candidates(workers.keySet(), incoming, slots)) {
                    var p = next.get(rid);
                    boolean offer = incoming.contains(p.rid());
                    IncomingHub.Setup setup = offer ? hub.take(p.rid()).orElse(null) : null;
                    // Every setup route is busy: keep the offer for a later scan instead of
                    // dialling out, which the pending offer would cancel again immediately.
                    if (offer && setup == null) continue;
                    PeerWorker w =
                            new PeerWorker(
                                    identity,
                                    attached.vip(),
                                    p,
                                    session.localAddress(),
                                    setup,
                                    hub,
                                    attached.uesHosts(),
                                    nextNetworks.get(p.rid()));
                    workers.put(p.rid(), w);
                    attempts.started(p.rid());
                    try {
                        peerExecutor.execute(w);
                    } catch (RejectedExecutionException e) {
                        workers.remove(p.rid(), w);
                        w.close();
                        attempts.failed(p.rid(), PeerReachability.Failure.OTHER);
                    }
                }
                } // A network roster is stable between scans; control records are still handled promptly.
                for (int i = 0; i < 64; i++) {
                    byte[] record = controlOutbound.poll();
                    if (record == null) break;
                    session.send(record);
                }
                if (System.nanoTime() >= heartbeat) {
                    session.send(op(4));
                    heartbeat = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                }
                if (settings.discoveryLogIntervalMinutes() > 0
                        && System.nanoTime() >= discoveryReport) {
                    var progress = attempts.snapshot();
                    log.accept(
                            "VPN discovery: eligible="
                                    + progress.eligible()
                                    + " attempted="
                                    + progress.attempted()
                                    + " authenticated="
                                    + progress.authenticated()
                                    + " retry-wait="
                                    + progress.backingOff()
                                    + " failed-attempts="
                                    + progress.failures()
                                    + " "
                                    + progress.reasons());
                    discoveryReport = System.nanoTime()
                            + Duration.ofMinutes(settings.discoveryLogIntervalMinutes()).toNanos();
                }
                if (session.ready(Duration.ofMillis(25))) {
                    byte[] data = session.receive();
                    lastControl = System.nanoTime();
                    dispatchMembership(data, membership, hub);
                }
                if (System.nanoTime() - lastControl > Duration.ofSeconds(90).toNanos())
                    throw new IOException("control heartbeat timeout");
                if (System.nanoTime() >= nextSnapshot) {
                    nextSnapshot = System.nanoTime() + Duration.ofMillis(500).toNanos();
                    publish(
                            "CONNECTED",
                            attached.vip().getHostAddress(),
                            membership.networks().values().stream()
                                    .map(Membership.Network::name)
                                    .toList(),
                            "");
                }
            }
        }
    }

    /**
     * A channel stays valid while the peer's identity binding is unchanged (RID, VIP, coordinator
     * and an online state) and it keeps every network it was admitted through. A rename, a 1/5
     * presence refresh or an additional shared network must not disconnect Minecraft players.
     */
    static boolean stillBound(
            Membership.Peer bound,
            Membership.Peer current,
            Set<String> boundNetworks,
            Set<String> currentNetworks) {
        return current != null
                && current.rid() == bound.rid()
                && current.vip().equals(bound.vip())
                && Objects.equals(current.server(), bound.server())
                && (current.state() == 1 || current.state() == 5)
                && currentNetworks != null
                && boundNetworks != null
                && currentNetworks.containsAll(boundNetworks);
    }

    private void dispatchMembership(byte[] data, Membership membership, IncomingHub hub)
            throws IOException {
        switch (TlvCodec.operation(data)) {
            case 38 -> membership.snapshot(data);
            case 41, 42 -> membership.changes(data);
            case 16 -> {
                PeerConnector.serverObjected();
                throw new IOException("service disconnected session");
            }
            // A refusal on the shared session (for example of an acceptor relay request) is
            // not a disconnect: keep the session, its channels and Minecraft players (Rust).
            case 44 -> {
                if (PeerConnector.serverObjected())
                    log.accept("Service refused an acceptor relay request; using direct paths.");
            }
            default -> {
                try {
                    hub.ingest(data);
                } catch (IllegalArgumentException e) {
                    // One malformed peer setup record must not end the shared session.
                    long now = System.nanoTime(), last = lastIncomingRejectLog.get();
                    if ((last == 0 || now - last >= Duration.ofSeconds(10).toNanos())
                            && lastIncomingRejectLog.compareAndSet(last, now))
                        log.accept("Incoming peer request rejected: " + e.getMessage());
                }
            }
        }
    }

    private boolean networkAllows(String name, long rid) {
        var n = activeTargets.get(name);
        return n != null
                && !n.deniedRids().contains(rid)
                && (n.allowedRids().isEmpty() || n.allowedRids().contains(rid));
    }

    private List<MinecraftNetworks.Entry> discoverMinecraft(
            ControlSession session, Membership membership, IncomingHub hub) throws IOException {
        List<PublicNetworks.Network> results = new ArrayList<>();
        Set<Long> cursors = new HashSet<>();
        long cursor = 0,
                id = 0x4d430000L,
                deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        int records = 0;
        for (int page = 0; page < 16; page++) {
            if (stopped.get()) throw new java.io.InterruptedIOException("catalog cancelled");
            session.send(PublicNetworks.request("Minecraft", ++id, cursor));
            PublicNetworks.Page result = null;
            while (result == null) {
                if (stopped.get()) throw new java.io.InterruptedIOException("catalog cancelled");
                if (System.nanoTime() > deadline)
                    throw new java.net.SocketTimeoutException("Minecraft catalog timeout");
                if (!session.ready(Duration.ofMillis(100))) continue;
                if (++records > 512) throw new IOException("Minecraft catalog message limit");
                byte[] data = session.receive();
                if (TlvCodec.operation(data) == 45) result = PublicNetworks.parse(data, id);
                else dispatchMembership(data, membership, hub);
            }
            results.addAll(result.networks());
            if (results.size() > 1024) throw new IOException("Minecraft catalog entry limit");
            if (result.cursor() == 0) return MinecraftNetworks.catalog(results);
            if (!cursors.add(result.cursor()))
                throw new IOException("Minecraft catalog cursor loop");
            cursor = result.cursor();
            session.send(op(4));
        }
        throw new IOException("Minecraft catalog page limit");
    }

    private void saveMinecraftCatalog(List<MinecraftNetworks.Entry> entries) throws IOException {
        StringBuilder text =
                new StringBuilder(
                        "# Public Minecraft networks from the authenticated service. Reported"
                                + " counts are network members, not Minecraft players.\n");
        text.append(
                "# Configure vpn.minecraft-networks.select with all, all-<language> or individual"
                        + " selectors.\n");
        for (var entry : entries)
            text.append(entry.selector())
                    .append("\t")
                    .append(entry.reportedCount())
                    .append("\t")
                    .append(entry.name())
                    .append('\n');
        Files.writeString(
                directory.resolve("minecraft-networks.txt"),
                text,
                java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        Files.writeString(
                directory.resolve("minecraft-network-options.yml"),
                MinecraftNetworks.optionsYaml(entries),
                java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
    }

    /** Configured networks the service refused during the current attachment. */
    public List<String> networkProblems() {
        return networkProblems;
    }

    private void publish(String phase, String vip, List<String> networks, String failure) {
        List<PeerView> views = new ArrayList<>();
        for (var p : eligible.values()) {
            PeerWorker w = workers.get(p.rid());
            PeerChannel c = w == null ? null : w.channel;
            views.add(
                    new PeerView(
                            p.rid(),
                            p.name(),
                            p.vip().getHostAddress(),
                            c == null ? "CONNECTING" : c.path().name(),
                            c != null));
        }
        VirtualTcpBridge stack = bridge;
        snapshot =
                new Snapshot(
                        phase,
                        vip,
                        networks,
                        views,
                        failure,
                        stack == null ? null : stack.snapshot());
    }

    private final class PeerWorker implements Runnable, AutoCloseable {
        private final Identity identity;
        private final Inet4Address ownIp;
        private final Membership.Peer peer;
        private final InetAddress route;
        private final IncomingHub.Setup setup;
        private final IncomingHub hub;
        private final VirtualTcpBridge stack;
        private final LanDiscovery lan;
        private final PeerReachability attempts;
        private final List<Inet4Address> uesHosts;
        private final Set<String> allowedNetworks;
        private final Set<AutoCloseable> resources =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(64);
        private boolean closed;
        private volatile PeerChannel channel;

        PeerWorker(
                Identity identity,
                Inet4Address ownIp,
                Membership.Peer peer,
                InetAddress route,
                IncomingHub.Setup setup,
                IncomingHub hub,
                List<Inet4Address> uesHosts,
                Set<String> allowedNetworks) {
            this.identity = identity;
            this.ownIp = ownIp;
            this.peer = peer;
            this.route = route;
            this.setup = setup;
            this.hub = hub;
            this.stack = bridge;
            this.lan = discovery;
            this.attempts = reachability;
            this.uesHosts = List.copyOf(uesHosts);
            this.allowedNetworks = allowedNetworks;
        }

        private synchronized void ownResource(AutoCloseable resource) {
            if (closed || stopped.get()) closeQuietly(resource);
            else resources.add(resource);
        }

        @Override
        public void run() {
            try {
                var policy =
                        new PeerConnector.Policy(
                                settings.directTcp(), settings.directUdp(), settings.relay());
                PeerChannel connected;
                if (setup == null) {
                    var result =
                            PeerConnector.connect(
                                    identity,
                                    ownIp,
                                    peer,
                                    settings.controlPort(),
                                    policy,
                                    this::ownResource,
                                    serviceModulus);
                    ownResource(result);
                    connected = result.channel();
                } else
                    connected =
                            PeerConnector.accept(
                                    setup,
                                    identity,
                                    ownIp,
                                    peer,
                                    route,
                                    policy,
                                    b -> {
                                        if (!controlOutbound.offer(b))
                                            throw new IllegalStateException(
                                                    "control advertisement queue full");
                                    },
                                    this::ownResource,
                                    uesHosts);
                ownResource(connected);
                synchronized (this) {
                    if (closed) return;
                    channel = connected;
                }
                // Release the incoming route now, as Rust does on Connected, instead of holding
                // one of the bounded setup routes for its whole 45 s lifetime.
                hub.finish(peer.rid());
                attempts.authenticated(peer.rid());
                int seq = 0, verified = 0;
                long lastLan = 0;
                long keepalive = System.nanoTime() + Duration.ofSeconds(15).toNanos(),
                        lastPacket = System.nanoTime();
                while (!isClosed()
                        && !stopped.get()
                        && stillBound(
                                peer,
                                eligible.get(peer.rid()),
                                allowedNetworks,
                                authorizedNetworks.get(peer.rid()))) {
                    long now = System.nanoTime();
                    if (lan != null
                            && (lastLan == 0 || now - lastLan >= LanDiscovery.SEND_INTERVAL)) {
                        byte[] advertisement = lan.frame(now);
                        if (advertisement != null && connected.sendFrame(advertisement)) {
                            lan.sent();
                            lastLan = now;
                        }
                    }
                    for (int i = 0; i < 32; i++) {
                        byte[] frame = frames.peek();
                        if (frame == null) break;
                        if (!connected.sendFrame(frame)) break;
                        frames.remove();
                    }
                    if (System.nanoTime() >= keepalive) {
                        if (connected.send(TunnelCodec.keepalive(seq + 1, false))) {
                            seq++;
                            keepalive = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                        }
                    }
                    if (connected.ready(Duration.ofMillis(20))) {
                        var packet = connected.receivePacket();
                        lastPacket = System.nanoTime();
                        if (packet instanceof TunnelCodec.Keepalive k) {
                            if (!k.reply())
                                connected.send(TunnelCodec.keepalive(k.sequence(), true));
                            else if (k.sequence() == seq && k.sequence() > verified)
                                verified = k.sequence();
                        } else if (packet instanceof TunnelCodec.Frames f) {
                            if (stack == null) break;
                            if (!Objects.equals(eligible.get(peer.rid()), peer)
                                    || !Objects.equals(
                                            authorizedNetworks.get(peer.rid()), allowedNetworks))
                                break;
                            var grant =
                                    new PeerGrant(
                                            peer.rid(),
                                            true,
                                            allowedNetworks,
                                            ipInt(peer.vip()),
                                            connected.peerMac());
                            for (byte[] frame : f.frames()) stack.accept(grant, frame);
                        }
                    }
                    if (System.nanoTime() - lastPacket > Duration.ofSeconds(50).toNanos())
                        throw new IOException("peer liveness timeout");
                }
            } catch (Exception e) {
                if (!stopped.get() && !isClosed())
                    attempts.failed(peer.rid(), PeerReachability.classify(e));
            } finally {
                boolean removed = workers.remove(peer.rid(), this);
                if (removed && stack != null) stack.revokePeer(peer.rid());
                close();
                if (removed) hub.finish(peer.rid());
            }
        }

        private synchronized boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            List<AutoCloseable> owned;
            synchronized (this) {
                if (closed) return;
                closed = true;
                channel = null;
                owned = List.copyOf(resources);
                resources.clear();
            }
            owned.forEach(VpnRuntime::closeQuietly);
            frames.clear();
            if (setup != null) setup.close();
        }
    }

    private void cleanupSession() {
        LanDiscovery lan = discovery;
        discovery = null;
        if (lan != null) lan.close();
        discoveryState = stopped.get() ? "STOPPED" : "OFFLINE";
        ControlSession s = control.getAndSet(null);
        if (s != null) closeQuietly(s);
        workers.values().forEach(PeerWorker::close);
        workers.clear();
        eligible = Map.of();
        authorizedNetworks = Map.of();
        currentNetworks = Map.of();
        reachability = null;
        activeTargets = Map.of();
        var catalog = minecraftCatalog;
        minecraftCatalog =
                new MinecraftCatalog(
                        stopped.get() ? "STOPPED" : "OFFLINE",
                        catalog.entries(),
                        Set.of(),
                        List.of());
        controlOutbound.clear();
        VirtualTcpBridge stack = bridge;
        bridge = null;
        if (stack != null) stack.close();
    }

    @Override
    public void close() {
        if (stopped.getAndSet(true)) return;
        ControlSession s = control.getAndSet(null);
        if (s != null) closeQuietly(s);
        workers.values().forEach(PeerWorker::close);
        peerExecutor.shutdownNow();
        worker.interrupt();
    }

    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        return awaitStopped(worker, peerExecutor, timeout);
    }

    /** One absolute deadline covers both the control worker and the peer executor. */
    static boolean awaitStopped(Thread worker, ExecutorService peers, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return !worker.isAlive() && peers.isTerminated();
        worker.join(Duration.ofNanos(remaining));
        if (worker.isAlive()) return false;
        remaining = deadline - System.nanoTime();
        if (remaining <= 0) return peers.isTerminated();
        return peers.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    }

    /**
     * Acquires the profile lock, retrying while another generation in this JVM still owns it.
     * {@link OverlappingFileLockException} is expected on reload and must stay transient.
     */
    static FileLock awaitIdentityLock(
            FileChannel channel, AtomicBoolean stopped, Runnable waiting)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + IDENTITY_LOCK_TIMEOUT.toNanos();
        while (true) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock != null) return lock;
            if (stopped.get()) throw new InterruptedIOException("identity lock wait cancelled");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0)
                throw new IdentityLockBusy();
            waiting.run();
            Thread.sleep(
                    Math.min(250L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))));
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            /* Socket cleanup cannot expose secrets or mask an earlier protocol failure. */
        }
    }

    private static String safeFailure(Exception e) {
        if (e instanceof IdentityLockBusy) return e.getMessage();
        return e instanceof java.net.SocketTimeoutException
                ? "network timeout"
                : e instanceof java.net.ConnectException
                        ? "endpoint refused connection"
                        : e instanceof IOException
                                ? "I/O failure (" + e.getClass().getSimpleName() + detail(e) + ")"
                                : e instanceof IllegalArgumentException
                                        ? (detail(e).isEmpty()
                                                ? "protocol/configuration validation rejected"
                                                : "rejected" + detail(e))
                                        : e.getClass().getSimpleName();
    }

    /**
     * This plugin's own messages never include passwords or credentials; show them so an
     * administrator can act (registration recovery, refused joins, limits), bounded in length.
     */
    private static String detail(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) return "";
        m = m.replaceAll("\\p{Cntrl}", " ");
        return ": " + (m.length() > 200 ? m.substring(0, 200) + "..." : m);
    }
}
