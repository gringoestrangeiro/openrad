package io.github.gringoestrangeiro.openradclient;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.network.Membership;
import io.github.gringoestrangeiro.openradplugin.network.NetworkJoin;
import io.github.gringoestrangeiro.openradplugin.network.MinecraftNetworks;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradplugin.session.*;
import io.github.gringoestrangeiro.openradplugin.peer.PeerConnector;
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
    private final AtomicReference<ControlSession> active = new AtomicReference<>();
    private volatile Snapshot state = new Snapshot("OFFLINE", "", List.of(), 0, "");
    private volatile Thread thread;
    private volatile ClientPeerService peers;
    private volatile Set<String> activeNetworkNames = Set.of();
    private volatile CatalogSnapshot catalog = new CatalogSnapshot("OFFLINE", List.of(), Set.of(), List.of());

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
        if (stopped.get()) {
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
        thread = Thread.ofPlatform().name("openrad-client-control")
                .daemon().unstarted(this::worker);
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
            Files.createDirectories(dataDirectory);
            if (Files.isSymbolicLink(dataDirectory.resolve("identity.lock")))
                throw new IOException("identity lock symlink not permitted");
            try (FileChannel lockFile = FileChannel.open(dataDirectory.resolve("identity.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = lockFile.tryLock()) {
                if (lock == null) throw new IOException("OpenRad identity is already in use");
                workerLocked();
            }
        } catch (Exception error) {
            if (!stopped.get()) state = new Snapshot("FAILED", "", List.of(), 0,
                    "Unable to own OpenRad identity (" + error.getClass().getSimpleName() + ")");
        }
    }

    private void workerLocked() {
        int retrySeconds = 3;
        while (!stopped.get()) {
            try {
                Files.createDirectories(dataDirectory);
                try (Identity identity = Provisioner.loadOrRegister(
                        dataDirectory, "OpenRad Minecraft", ServiceConfig.BOOTSTRAP,
                        ServiceConfig.PORT, this::own);
                     Attachment connection = Attachment.connect(
                        identity, ServiceConfig.PORT, identity.nodeName(), this::own)) {
                    own(connection.session());
                    retrySeconds = 3;
                    update("JOINING_NETWORKS", connection.vip().getHostAddress(),
                            connection.membership(), "");
                    runConnected(identity, connection);
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
                update("RETRY_WAIT", "", null, "Reconnect in " + retrySeconds
                        + " s (" + ex.getClass().getSimpleName() + ")");
                logger.accept("Control connection interrupted ("
                        + ex.getClass().getSimpleName() + "), retry in " + retrySeconds + "s");
                try {
                    Thread.sleep(retrySeconds * 1_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                retrySeconds = Math.min(retrySeconds * 2, 60);
            } finally {
                active.set(null);
            }
        }
        if (!state.phase().equals("FAILED"))
            state = new Snapshot("STOPPED", "", List.of(), 0, "");
    }

    private void handle(byte[] record, Membership membership, ClientPeerService peerService) throws IOException {
        int op = TlvCodec.operation(record);
        switch (op) {
            case 38 -> membership.snapshot(record);
            case 41, 42 -> membership.changes(record);
            case 16, 44 -> {
                PeerConnector.serverObjected();
                throw new AttachmentClosed();
            }
            default -> peerService.ingest(record);
        }
    }

    private static final class AttachmentClosed extends IOException {
        AttachmentClosed() { super("service closed VPN attachment"); }
    }

    private void runConnected(Identity identity, Attachment connection) throws IOException {
        try (ClientPeerService peerService = new ClientPeerService(identity, connection, config)) {
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
        List<MinecraftNetworks.Entry> entries;
        try {
            entries = MinecraftCatalogQuery.fetch(connection.session(),
                    record -> handle(record, connection.membership(), peerService), stopped::get);
        } catch (IOException | IllegalArgumentException failure) {
            if (stopped.get()) throw new InterruptedIOException("OpenRad catalog cancelled");
            if (failure instanceof AttachmentClosed || failure instanceof EOFException
                    || failure instanceof java.net.SocketException)
                throw (IOException) failure;
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
        for (String network : targets.stream().sorted().toList()) {
            if (stopped.get()) return;
            if (membership.networks().values().stream()
                    .anyMatch(n -> n.name().equals(network))) continue;
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
            if (session.ready(Duration.ofMillis(100))) {
                handle(session.receive(), membership, peerService);
                lastControl = System.nanoTime();
                snapshot = 0;
                nextPeerScan = 0;
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
        ClientPeerService current = peers;
        if (current != null) current.close();
        ControlSession session = active.getAndSet(null);
        if (session != null) {
            try { session.close(); } catch (IOException ignored) { }
        }
        running.interrupt();
        state = new Snapshot("STOPPED", "", List.of(), 0, "");
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
