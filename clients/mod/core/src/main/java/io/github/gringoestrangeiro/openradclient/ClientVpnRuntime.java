package io.github.gringoestrangeiro.openradclient;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.network.Membership;
import io.github.gringoestrangeiro.openradplugin.network.NetworkJoin;
import io.github.gringoestrangeiro.openradplugin.network.MinecraftNetworks;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradplugin.session.*;
import io.github.gringoestrangeiro.openradplugin.peer.AcceptorRelayGuard;
import io.github.gringoestrangeiro.openradclient.virtual.OutboundTcpStack;

import java.io.*;
import java.net.Inet4Address;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Client-only OpenRad attachment and authorized outbound game tunnels, without a system adapter.
 * Registration, attachment, membership and heartbeat processing belong to one daemon worker.
 */
public final class ClientVpnRuntime implements AutoCloseable {
    public record LanServer(String motd, String address, int port, long lastSeenMillis) {}
    public record CatalogSnapshot(String state, List<MinecraftNetworks.Entry> entries,
                                  Set<String> selectedNames, List<String> problems) {
        public CatalogSnapshot {
            entries = List.copyOf(entries);
            selectedNames = Set.copyOf(selectedNames);
            problems = List.copyOf(problems);
        }
    }
    public record Snapshot(String phase, String vip, List<String> networks,
                           int peers, String detail) {
        public Snapshot {
            networks = List.copyOf(networks);
        }
    }
    private final ClientConfig config;
    private final Path dataDirectory;
    private final Consumer<String> logger;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    /** Set while Provisioner may hold registration.pending; disconnect must not tear it down. */
    private final AtomicBoolean provisioning = new AtomicBoolean();
    private final AtomicReference<ControlSession> active = new AtomicReference<>();
    private volatile Snapshot state = new Snapshot("OFFLINE", "", List.of(), 0, "");
    private volatile Thread thread;
    private volatile ClientPeerService peers;
    private volatile Set<String> activeNetworkNames = Set.of();
    private volatile CatalogSnapshot catalog = new CatalogSnapshot("OFFLINE", List.of(), Set.of(), List.of());
    private final AtomicLong lastIncomingRejectLog = new AtomicLong();
    /** What the attachment was doing; reported with a service disconnect to explain it. */
    private volatile String controlStage = "authenticating";

    public ClientVpnRuntime(ClientConfig config, Path dataDirectory, Consumer<String> logger) {
        this.config = Objects.requireNonNull(config);
        this.dataDirectory = Objects.requireNonNull(dataDirectory);
        this.logger = Objects.requireNonNull(logger);
    }

    public Snapshot status() { return state; }
    public CatalogSnapshot minecraftCatalog() { return catalog; }

    public List<LanServer> lanServers() {
        if (stopped.get() || shutdown.get()) return List.of();
        ClientPeerService current = peers;
        return current == null ? List.of() : current.lanServers();
    }

    /** Blocking API: invoke only on a networking worker, never Minecraft's render thread. */
    public OutboundTcpStack.Connection openTcp(Inet4Address destination, int port, Duration timeout)
            throws IOException {
        ClientPeerService current = peers;
        if (current == null || stopped.get()) throw new IOException("OpenRad is not connected");
        return current.openTcp(destination, port, timeout);
    }

    private void own(ControlSession session) {
        active.set(session);
        if (stopped.get() && !provisioning.get()) {
            active.compareAndSet(session, null);
            try { session.close(); } catch (IOException ignored) { }
        }
    }

    public synchronized boolean connect() {
        if (shutdown.get()) return false;
        if (thread != null && thread.isAlive()) return false;
        if (config.networks().isEmpty() && config.minecraftSelectors().isEmpty()) {
            state = new Snapshot("OFFLINE", "", List.of(), 0,
                    "Select Minecraft networks in the OpenRad menu or configuration");
            return false;
        }
        stopped.set(false);
        state = new Snapshot("CONNECTING", "", List.of(), 0, "");
        thread = io.github.gringoestrangeiro.openradplugin.util.ThreadCompat.platformUnstarted("openrad-client-control", this::worker);
        thread.start();
        return true;
    }

    private void update(String phase, String vip, Membership m, String detail) {
        if (stopped.get()) return;
        List<String> networks = m == null ? List.of()
                : m.networks().values().stream()
                    .filter(network -> activeNetworkNames.contains(network.name()))
                    .map(Membership.Network::name).sorted().toList();
        ClientPeerService current = peers;
        state = new Snapshot(phase, vip, networks,
                current == null ? 0 : current.connectedCount(), detail);
    }

    private void worker() {
        try {
            CoordinatorEndpoint coordinator;
            try {
                coordinator = CoordinatorEndpoint.load(dataDirectory, ServiceConfig.BOOTSTRAP, ServiceConfig.PORT);
            } catch (IOException error) {
                if (!stopped.get()) state = new Snapshot("FAILED", "", List.of(), 0,
                        "Unable to load coordinator.properties or modulus.bin");
                return;
            }
            Path identityDirectory = coordinator.identityDirectory();
            Files.createDirectories(identityDirectory);
            try (FileChannel lockFile = FileChannel.open(identityDirectory.resolve("identity.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = lockFile.tryLock()) {
                if (lock == null) throw new IOException("OpenRad identity is already in use");
                workerLocked(coordinator);
            }
        } catch (Exception error) {
            if (!stopped.get()) state = new Snapshot("FAILED", "", List.of(), 0,
                    "Unable to own OpenRad identity (" + error.getClass().getSimpleName() + ")");
        }
    }

    /** A session that stayed attached this long was healthy: restart backoff (Rust daemon). */
    static final Duration STABLE_SESSION = Duration.ofSeconds(60);
    static final int RETRY_BASE_SECONDS = 3, RETRY_MAX_SECONDS = 60;

    /** Reconnect delay after the given number of consecutive failures (1-based). */
    static long retrySeconds(int failures) {
        int exponent = Math.max(0, Math.min(failures - 1, 5));
        return Math.min(RETRY_MAX_SECONDS, (long) RETRY_BASE_SECONDS << exponent);
    }

    private void workerLocked(CoordinatorEndpoint coordinator) {
        var relayGuard = new AcceptorRelayGuard();
        int failures = 0;
        while (!stopped.get()) {
            long attachedAt = 0;
            try {
                // Closing or interrupting a registration after registration.pending is written
                // would discard an issued identity and block this profile for good. Disconnect
                // only flags it; the worker stops once the bounded registration has finished.
                Identity provisioned;
                controlStage = "authenticating";
                provisioning.set(true);
                try {
                    if (stopped.get()) break;
                    provisioned = Provisioner.loadOrRegister(
                            coordinator.identityDirectory(), "OpenRad Minecraft", coordinator.bootstrap(),
                            coordinator.port(), this::own, coordinator.modulus());
                } finally {
                    provisioning.set(false);
                    active.set(null);
                }
                if (stopped.get()) {
                    provisioned.close();
                    break;
                }
                try (Identity identity = provisioned;
                     Attachment connection = Attachment.connect(
                        identity, coordinator.port(), identity.nodeName(), this::own, coordinator.modulus())) {
                    own(connection.session());
                    attachedAt = System.nanoTime();
                    update("JOINING_NETWORKS", connection.vip().getHostAddress(),
                            connection.membership(), "");
                    runConnected(identity, connection, coordinator, relayGuard);
                }
                if (!stopped.get()) throw new IOException("control session ended");
            } catch (Exception ex) {
                if (stopped.get()) break;
                String description = ex instanceof IOException && ex.getMessage() != null
                        ? ex.getMessage() : ex.getClass().getSimpleName();
                // Do not log remote record content, credentials or keys.
                if (description.contains("registration outcome unknown")) {
                    update("FAILED", "", null,
                            "Registration outcome unknown: keep registration.pending and recover identity.");
                    break;
                }
                if (ex instanceof ServerDisconnect disconnect && disconnect.replaced()) {
                    // Reconnecting would disconnect the other client, which would reconnect and
                    // disconnect this one: both would flap every few seconds. Wait for the user.
                    update("FAILED", "", null,
                            "This OpenRad identity connected from another client. Close the other"
                                    + " copy (or give it its own identity) and press Connect.");
                    logger.accept("OpenRad stopped: the same identity is attached from another"
                            + " client (copied state/identity.json?). Not reconnecting automatically.");
                    break;
                }
                if (attachedAt != 0 && System.nanoTime() - attachedAt >= STABLE_SESSION.toNanos())
                    failures = 0;
                failures = Math.min(failures + 1, 16);
                long delay = retrySeconds(failures);
                String reason = safeReason(ex);
                if (ex instanceof ServerDisconnect) reason += " during " + controlStage;
                update("RETRY_WAIT", "", null, "Reconnect in " + delay + " s (" + reason + ")");
                logger.accept("Control connection interrupted (" + reason + "), retry in " + delay + "s");
                // Jitter spreads reconnects of many clients after a service restart.
                long until = System.nanoTime() + Duration.ofSeconds(delay).toNanos()
                        + java.util.concurrent.ThreadLocalRandom.current().nextLong(250_000_000L);
                try {
                    while (!stopped.get() && System.nanoTime() < until)
                        Thread.sleep(Math.max(1, Math.min(100, (until - System.nanoTime()) / 1_000_000L)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                active.set(null);
            }
        }
        if (!state.phase().equals("FAILED"))
            state = new Snapshot("STOPPED", "", List.of(), 0, "");
    }

    /** Class name plus this client's own bounded message; never remote record content. */
    static String safeReason(Exception error) {
        String name = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (!(error instanceof IOException) || message == null || message.isBlank()) return name;
        message = message.replaceAll("\\p{Cntrl}", " ");
        if (message.length() > 160) message = message.substring(0, 160) + "...";
        return name + ": " + message;
    }

    /** Outcome of one attachment record that is neither a catalog page nor a join reply. */
    enum ControlEvent { MEMBERSHIP, REFUSAL, PEER_RECORD }

    /**
     * Shared attachment dispatch, matching the Rust engine and the Paper plugin: operation 16 ends
     * the session, while a refusal (44) only answers one request (for example an acceptor relay
     * request) and keeps the session, its channels and players.
     */
    static ControlEvent classify(byte[] record) throws ServerDisconnect {
        return switch (TlvCodec.operation(record)) {
            case 38, 41, 42 -> ControlEvent.MEMBERSHIP;
            case ServerDisconnect.OPERATION -> throw ServerDisconnect.of(record);
            case 44 -> ControlEvent.REFUSAL;
            default -> ControlEvent.PEER_RECORD;
        };
    }

    private void handle(byte[] record, Membership membership, ClientPeerService peerService) throws IOException {
        ControlEvent event;
        try {
            event = classify(record);
        } catch (ServerDisconnect disconnect) {
            if (!disconnect.replaced()) peerService.serverObjected();
            throw disconnect;
        }
        switch (event) {
            case MEMBERSHIP -> {
                if (TlvCodec.operation(record) == 38) membership.snapshot(record);
                else membership.changes(record);
            }
            case REFUSAL -> {
                if (peerService.serverObjected())
                    logger.accept(config.forceRelay()
                            ? "Service refused an acceptor relay request; incoming peers now depend on a relay offer of their own."
                            : "Service refused an acceptor relay request; using direct paths.");
            }
            case PEER_RECORD -> {
                try {
                    peerService.ingest(record);
                } catch (IllegalArgumentException malformed) {
                    // One malformed peer setup record must not end the shared session.
                    long now = System.nanoTime(), last = lastIncomingRejectLog.get();
                    if ((last == 0 || now - last >= Duration.ofSeconds(10).toNanos())
                            && lastIncomingRejectLog.compareAndSet(last, now))
                        logger.accept("Incoming peer request rejected (" + safeReason(malformed) + ")");
                }
            }
        }
    }

    private void runConnected(Identity identity, Attachment connection, CoordinatorEndpoint coordinator,
                              AcceptorRelayGuard relayGuard) throws IOException {
        relayGuard.startAttachment();
        try (ClientPeerService peerService = new ClientPeerService(identity, connection, config, coordinator, relayGuard)) {
            peers = peerService;
            Set<String> targets = resolveNetworks(connection, peerService);
            activeNetworkNames = targets;
            peerService.selectNetworks(targets);
            runConnected(connection, peerService, targets);
        } finally {
            ClientPeerService current = peers;
            peers = null;
            activeNetworkNames = Set.of();
            if (current != null) {
                current.close();
                // Keep identity.lock until every old peer coordinator has relinquished this
                // identity. UI handover uses awaitStopped(timeout), so it remains bounded.
                boolean interrupted = Thread.interrupted();
                try {
                    while (true) {
                        try { current.awaitStopped(); break; }
                        catch (InterruptedException shutdownSignal) { interrupted = true; }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    record TargetSelection(Set<String> names, List<String> problems) {
        TargetSelection { names = Set.copyOf(names); problems = List.copyOf(problems); }
    }

    static TargetSelection selectNetworkNames(ClientConfig config, List<MinecraftNetworks.Entry> entries) {
        var selected = MinecraftNetworks.select(entries, config.minecraftSelectors(), config.maxNetworks());
        Set<String> names = new LinkedHashSet<>(config.networks());
        selected.networks().forEach(entry -> names.add(entry.name()));
        if (names.size() > config.maxNetworks())
            throw new IllegalArgumentException("Selected exact and Minecraft networks exceed max-networks");
        return new TargetSelection(names, selected.unmatched().stream()
                .map(selector -> "Selector absent from catalog: " + selector).toList());
    }

    private Set<String> resolveNetworks(Attachment connection, ClientPeerService peerService) throws IOException {
        CatalogSnapshot previous = catalog;
        catalog = new CatalogSnapshot("LOADING", previous.entries(), Set.copyOf(config.networks()), List.of());
        controlStage = "Minecraft catalog query";
        List<MinecraftNetworks.Entry> entries;
        try {
            entries = MinecraftCatalogQuery.fetch(connection.session(),
                    record -> handle(record, connection.membership(), peerService), stopped::get);
        } catch (IOException | IllegalArgumentException failure) {
            if (stopped.get()) throw new InterruptedIOException("OpenRad catalog cancelled");
            if (failure instanceof ServerDisconnect || failure instanceof EOFException
                    || failure instanceof java.net.SocketException)
                throw (IOException) failure;
            // Selector-only profiles have nothing to join without the catalog: reconnect with
            // backoff instead of reporting CONNECTED with zero networks and never retrying.
            if (config.networks().isEmpty() && !config.minecraftSelectors().isEmpty())
                throw new IOException("Minecraft catalog unavailable", failure);
            catalog = new CatalogSnapshot("FAILED", previous.entries(), Set.copyOf(config.networks()),
                    List.of("Minecraft catalog unavailable (" + failure.getClass().getSimpleName() + "); exact network names remain active"));
            return Set.copyOf(config.networks());
        }
        try {
            TargetSelection selection = selectNetworkNames(config, entries);
            catalog = new CatalogSnapshot(selection.problems().isEmpty() ? "READY" : "READY_WITH_WARNINGS",
                    entries, selection.names(), selection.problems());
            return selection.names();
        } catch (IllegalArgumentException limit) {
            catalog = new CatalogSnapshot("SELECTION_REJECTED", entries, Set.copyOf(config.networks()),
                    List.of("Minecraft selection exceeds max-networks; increase the limit or narrow the selectors"));
            return Set.copyOf(config.networks());
        }
    }

    private void runConnected(Attachment connection, ClientPeerService peerService, Set<String> targets) throws IOException {
        ControlSession session = connection.session();
        Membership membership = connection.membership();
        long heartbeat = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        long id = 1;
        int sequence = 1;
        List<String> problems = new ArrayList<>(catalog.problems());
        List<String> ordered = targets.stream().sorted().toList();
        int joinIndex = 0;
        for (String network : ordered) {
            if (stopped.get()) return;
            joinIndex++;
            if (membership.networks().values().stream()
                    .anyMatch(n -> n.name().equals(network))) continue;
            controlStage = "join of network \"" + network + "\" (" + joinIndex + "/" + ordered.size() + ")";
            try (NetworkJoin join = new NetworkJoin(network, null, id++, sequence++)) {
                session.send(join.start());
                long until = System.nanoTime() + Duration.ofSeconds(12).toNanos();
                while (!join.complete() && !stopped.get()) {
                    if (System.nanoTime() >= until) {
                        problems.add(network + ": timed out");
                        break;
                    }
                    if (System.nanoTime() >= heartbeat) {
                        session.send(op(4));
                        heartbeat = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    }
                    if (!session.ready(Duration.ofMillis(100))) continue;
                    byte[] msg = session.receive();
                    handle(msg, membership, peerService);
                    try {
                        byte[] reply = join.handle(msg, membership);
                        if (reply != null) session.send(reply);
                    } catch (NetworkJoin.Refused rejected) {
                        problems.add(network + ": refused (" + rejected.code() + ")");
                        break;
                    }
                }
                if (join.pendingApproval())
                    problems.add(network + ": awaiting administrator approval");
            }
            // Membership and authenticated VIP become visible while joins are
            // still in progress; do not leave the UI stuck showing zero networks.
            update("JOINING_NETWORKS", connection.vip().getHostAddress(),
                    membership, "");
        }
        controlStage = "connected session";
        String ip = connection.vip().getHostAddress();
        update("CONNECTED", ip, membership,
                problems.isEmpty() ? "Control authenticated; " + peerService.connectedCount() + " authenticated peer channel(s)"
                        : String.join("; ", problems));
        long snapshot = System.nanoTime();
        long lastControl = System.nanoTime();
        long nextPeerScan = 0;
        while (!stopped.get()) {
            long now = System.nanoTime();
            if (now >= heartbeat) {
                session.send(op(4));
                heartbeat = now + Duration.ofSeconds(10).toNanos();
            }
            // Wake for a due scan/snapshot instead of always waiting the full 100 ms.
            long wait = Math.max(1_000_000L, Math.min(100_000_000L, Math.min(nextPeerScan, snapshot) - now));
            if (session.ready(Duration.ofNanos(wait))) {
                handle(session.receive(), membership, peerService);
                lastControl = System.nanoTime();
                // Coalesce bursts of membership records into one scan/snapshot.
                long soon = lastControl + Duration.ofMillis(10).toNanos();
                snapshot = Math.min(snapshot, soon);
                nextPeerScan = Math.min(nextPeerScan, soon);
            }
            if (System.nanoTime() >= nextPeerScan) {
                peerService.tick(membership);
                nextPeerScan = System.nanoTime() + Duration.ofMillis(100).toNanos();
            }
            if (System.nanoTime() - lastControl > Duration.ofSeconds(90).toNanos())
                throw new IOException("control heartbeat timeout");
            if (System.nanoTime() >= snapshot) {
                update("CONNECTED", ip, membership,
                        problems.isEmpty() ? "Control authenticated; " + peerService.connectedCount() + " authenticated peer channel(s)" : String.join("; ", problems));
                snapshot = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            }
        }
    }

    public synchronized void disconnect() {
        Thread running = thread;
        if (running == null) return;
        stopped.set(true);
        state = new Snapshot("STOPPED", "", List.of(), 0, "");
        if (provisioning.get()) return;
        ClientPeerService current = peers;
        if (current != null) current.close();
        ControlSession session = active.getAndSet(null);
        if (session != null) {
            try { session.close(); } catch (IOException ignored) { }
        }
        running.interrupt();
    }

    @Override
    public void close() {
        shutdown.set(true);
        disconnect();
    }

    /** Optional worker-side shutdown wait; UI callers should use close() without blocking. */
    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        Thread running = thread;
        if (running == null) return true;
        if (timeout.isNegative() || timeout.isZero()) return !running.isAlive();
        running.join(timeout.toMillis(), timeout.toNanosPart() % 1_000_000);
        return !running.isAlive();
    }
}
