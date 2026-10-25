package io.github.gringoestrangeiro.openradclient;

import io.github.gringoestrangeiro.openradclient.virtual.OutboundTcpStack;
import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.network.Membership;
import io.github.gringoestrangeiro.openradplugin.peer.*;
import io.github.gringoestrangeiro.openradplugin.session.Attachment;
import io.github.gringoestrangeiro.openradplugin.session.ServiceConfig;
import io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One attachment generation. A single worker owns each encrypted channel and its cipher state. */
final class ClientPeerService implements AutoCloseable {
    private static final PeerConnector.Policy TRANSPORTS = new PeerConnector.Policy(true, true, true);
    private final Identity identity;
    private final Attachment attachment;
    private final int maxPeers;
    private volatile Set<String> selectedNames;
    private final IncomingHub incoming = new IncomingHub();
    private final ArrayBlockingQueue<byte[]> controlOutbound = new ArrayBlockingQueue<>(128);
    private final ConcurrentHashMap<Long, PeerWorker> workers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Attempt> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> requested = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final AsyncResourceCloser resourceCloser;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object changed = new Object();
    private final LanServerRegistry lan = new LanServerRegistry();
    private final OutboundTcpStack stack;
    private volatile Map<Long, PeerAuthorization.Grant> authorized = Map.of();

    private record Attempt(int failures, long retryAt, long lastStarted) {}

    ClientPeerService(Identity identity, Attachment attachment, ClientConfig config) {
        this.identity = identity;
        this.attachment = attachment;
        this.maxPeers = config.maxPeers();
        this.selectedNames = Set.copyOf(config.networks());
        resourceCloser = new AsyncResourceCloser(maxPeers);
        executor = new ThreadPoolExecutor(0, maxPeers, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofVirtual().name("openrad-client-peer-", 0).factory());
        stack = new OutboundTcpStack(attachment.vip(), this::sendFrame, config.maxConnections());
    }

    void ingest(byte[] record) { incoming.ingest(record); }
    void selectNetworks(Set<String> selectedNames) { this.selectedNames = Set.copyOf(selectedNames); }
    int connectedCount() { return (int) workers.values().stream().filter(w -> w.channel != null).count(); }
    List<ClientVpnRuntime.LanServer> lanServers() { return lan.snapshot(); }

    /** Called by the authenticated control worker; membership itself never crosses threads. */
    void tick(Membership membership) throws IOException {
        if (closed.get()) return;
        Map<Long, PeerAuthorization.Grant> next = PeerAuthorization.select(membership, selectedNames);
        authorized = next;
        attempts.keySet().retainAll(next.keySet());
        for (PeerWorker worker : List.copyOf(workers.values())) {
            if (!Objects.equals(next.get(worker.grant.peer().rid()), worker.grant)) {
                if (workers.remove(worker.grant.peer().rid(), worker)) {
                    worker.close();
                    incoming.finish(worker.grant.peer().rid());
                    stack.closePeer(worker.grant.peer().vip());
                    lan.removePeer(worker.grant.peer().vip());
                }
            }
        }
        incoming.expire();
        Set<Long> offers = incoming.pendingRids();
        for (long rid : offers) {
            if (!next.containsKey(rid)) { incoming.reject(rid); continue; }
            PeerWorker worker = workers.get(rid);
            if (worker == null) continue;
            if (worker.channel != null) incoming.reject(rid);
            else if (Long.compareUnsigned(identity.rid(), rid) > 0 && workers.remove(rid, worker))
                worker.close();
        }
        synchronized (changed) {
            for (long rid : requested.keySet()) {
                if (!next.containsKey(rid) || workers.containsKey(rid) || workers.size() < maxPeers) continue;
                List<PeerCapacityPlanner.Candidate> residents = workers.values().stream()
                        .map(worker -> new PeerCapacityPlanner.Candidate(worker.grant.peer().rid(),
                                worker.grant.peer().vip(), attempts.getOrDefault(worker.grant.peer().rid(),
                                new Attempt(0, 0, 0)).lastStarted())).toList();
                var victim = PeerCapacityPlanner.victim(residents, requested.keySet(), stack::hasConnections);
                if (victim.isEmpty()) break;
                long retiringRid = victim.getAsLong();
                PeerWorker retiring = workers.remove(retiringRid);
                if (retiring != null) {
                    retiring.close();
                    incoming.finish(retiringRid);
                    stack.closePeer(retiring.grant.peer().vip());
                    lan.removePeer(retiring.grant.peer().vip());
                    attempts.computeIfPresent(retiringRid, (key, old) -> new Attempt(old.failures(),
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(30), old.lastStarted()));
                }
            }
        }
        int connecting = (int) workers.values().stream().filter(w -> w.channel == null).count();
        int slots = Math.max(0, Math.min(Math.min(8, maxPeers) - connecting, maxPeers - workers.size()));
        long now = System.nanoTime();
        List<Long> candidates = next.keySet().stream().filter(rid -> !workers.containsKey(rid))
                .filter(rid -> offers.contains(rid) || now >= attempts.getOrDefault(rid, new Attempt(0, 0, 0)).retryAt())
                .sorted(Comparator.<Long>comparingInt(rid -> requested.containsKey(rid) ? 0 : offers.contains(rid) ? 1 : 2)
                        .thenComparingLong(rid -> attempts.getOrDefault(rid, new Attempt(0, 0, 0)).lastStarted())
                        .thenComparing(Long::compareUnsigned))
                .limit(slots).toList();
        for (long rid : candidates) {
            AsyncResourceCloser.Lease cleanup = resourceCloser.reserve();
            if (cleanup == null) break;
            IncomingHub.Setup setup = offers.contains(rid) ? incoming.take(rid).orElse(null) : null;
            PeerWorker worker = new PeerWorker(next.get(rid), setup, cleanup);
            workers.put(rid, worker);
            attempts.compute(rid, (key, old) -> new Attempt(old == null ? 0 : old.failures(), 0, now));
            try { executor.execute(worker); }
            catch (RejectedExecutionException shutdown) { workers.remove(rid, worker); worker.close(); }
        }
        for (int i = 0; i < 64; i++) {
            byte[] record = controlOutbound.poll();
            if (record == null) break;
            attachment.session().send(record);
        }
        signalChanged();
    }

    OutboundTcpStack.Connection openTcp(Inet4Address destination, int port, Duration timeout) throws IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(timeout);
        if ((destination.getAddress()[0] & 255) != 26 || destination.equals(attachment.vip())
                || port < 1 || port > 65535 || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("invalid virtual TCP destination or timeout");
        long until = System.nanoTime() + timeout.toNanos();
        PeerAuthorization.Grant grant = authorized.values().stream()
                .filter(g -> g.peer().vip().equals(destination)).findFirst()
                .orElseThrow(() -> new ConnectException("destination is not an authorized online member of a selected network"));
        long rid = grant.peer().rid();
        PeerChannel selectedChannel;
        try {
            synchronized (changed) {
                requested.merge(rid, 1, Integer::sum);
                while (true) {
                    if (closed.get()) throw new IOException("OpenRad session closed");
                    if (!Objects.equals(authorized.get(rid), grant)) throw new IOException("OpenRad peer authorization revoked");
                    PeerWorker worker = workers.get(rid);
                    PeerChannel candidate = worker == null ? null : worker.channel;
                    if (candidate != null) {
                        selectedChannel = candidate;
                        break;
                    }
                    long remaining = until - System.nanoTime();
                    if (remaining <= 0) throw new SocketTimeoutException("OpenRad authenticated peer connection timed out");
                    try { TimeUnit.NANOSECONDS.timedWait(changed, remaining); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("OpenRad connection interrupted");
                    }
                }
            }
            long remaining = until - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("OpenRad connection timed out");
            OutboundTcpStack.Connection connection = stack.connect(destination, port, Duration.ofNanos(remaining));
            // Revocation may close the old tuples just before connect creates a new one. Bind
            // application admission to the same authenticated channel, even if a VIP/MAC is reused.
            PeerWorker current = workers.get(rid);
            if (closed.get() || !Objects.equals(authorized.get(rid), grant)
                    || current == null || current.channel != selectedChannel) {
                connection.close();
                throw new IOException("OpenRad peer authorization or channel changed during TCP admission");
            }
            return connection;
        } finally {
            requested.computeIfPresent(rid, (key, value) -> value <= 1 ? null : value - 1);
        }
    }

    private void sendFrame(Inet4Address destination, byte[] frame) throws IOException {
        if (closed.get()) throw new IOException("OpenRad session closed");
        for (PeerAuthorization.Grant grant : authorized.values()) {
            if (!grant.peer().vip().equals(destination)) continue;
            PeerWorker worker = workers.get(grant.peer().rid());
            if (worker == null || worker.channel == null || !worker.offer(frame))
                throw new IOException("OpenRad peer unavailable or outbound frame queue full");
            return;
        }
        throw new IOException("OpenRad destination authorization revoked");
    }

    private void signalChanged() { synchronized (changed) { changed.notifyAll(); } }

    private final class PeerWorker implements Runnable, AutoCloseable {
        final PeerAuthorization.Grant grant;
        final IncomingHub.Setup setup;
        final AsyncResourceCloser.Lease cleanup;
        final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(128);
        final Set<AutoCloseable> resources = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean stopped;
        volatile PeerChannel channel;

        PeerWorker(PeerAuthorization.Grant grant, IncomingHub.Setup setup, AsyncResourceCloser.Lease cleanup) {
            this.grant = grant;
            this.setup = setup;
            this.cleanup = cleanup;
        }
        void own(AutoCloseable resource) {
            boolean reject;
            synchronized (this) {
                reject = stopped || closed.get();
                if (!reject) resources.add(resource);
            }
            // Called by a networking worker. Never hold the monitor while a transport joins its jobs.
            if (reject) closeQuietly(resource);
        }
        synchronized boolean offer(byte[] frame) { return !stopped && frames.offer(frame.clone()); }
        synchronized boolean stopped() { return stopped; }
        boolean authorized() { return Objects.equals(authorized.get(grant.peer().rid()), grant); }

        @Override public void run() {
            long rid = grant.peer().rid();
            try {
                if (stopped() || closed.get() || !authorized()) return;
                PeerChannel connected;
                if (setup == null) {
                    var result = PeerConnector.connect(identity, attachment.vip(), grant.peer(),
                            ServiceConfig.PORT, TRANSPORTS, this::own);
                    own(result);
                    connected = result.channel();
                } else {
                    connected = PeerConnector.accept(setup, identity, attachment.vip(), grant.peer(),
                            attachment.session().localAddress(), TRANSPORTS, record -> {
                                if (!controlOutbound.offer(record)) throw new IllegalStateException("control advertisement queue full");
                            }, this::own, attachment.uesHosts());
                    own(connected);
                }
                synchronized (this) {
                    if (stopped || closed.get() || !authorized()) return;
                    stack.setPeerMac(grant.peer().vip(), connected.peerMac());
                    channel = connected;
                }
                attempts.computeIfPresent(rid, (key, old) -> new Attempt(0, 0, old.lastStarted()));
                signalChanged();
                int sequence = 0;
                long keepalive = System.nanoTime() + Duration.ofSeconds(15).toNanos(), lastPacket = System.nanoTime();
                while (!stopped() && !closed.get() && authorized()) {
                    for (int i = 0; i < 32; i++) {
                        if (stopped() || closed.get() || !authorized()) break;
                        byte[] frame = frames.peek();
                        if (frame == null || !connected.sendFrame(frame)) break;
                        frames.poll();
                    }
                    if (System.nanoTime() >= keepalive && connected.send(TunnelCodec.keepalive(sequence + 1, false))) {
                        sequence++;
                        keepalive = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                    }
                    if (connected.ready(Duration.ofMillis(20))) {
                        TunnelCodec.Packet packet = connected.receivePacket();
                        lastPacket = System.nanoTime();
                        if (packet instanceof TunnelCodec.Keepalive alive && !alive.reply())
                            connected.send(TunnelCodec.keepalive(alive.sequence(), true));
                        else if (packet instanceof TunnelCodec.Frames batch && authorized()) {
                            for (byte[] frame : batch.frames()) {
                                if (!authorized()) break;
                                if (!lan.accept(grant.peer().vip(), connected.peerMac(), frame))
                                    stack.acceptEthernet(grant.peer().vip(), frame);
                            }
                        }
                    }
                    if (System.nanoTime() - lastPacket > Duration.ofSeconds(50).toNanos())
                        throw new SocketTimeoutException("peer heartbeat timeout");
                }
            } catch (Exception failure) {
                if (!closed.get() && !stopped()) attempts.computeIfPresent(rid, (key, old) -> {
                    int failures = Math.min(8, old.failures() + 1);
                    long delay = TimeUnit.SECONDS.toNanos(Math.min(300, 3L << (failures - 1)));
                    return new Attempt(failures, System.nanoTime() + delay, old.lastStarted());
                });
            } finally {
                if (workers.remove(rid, this)) {
                    stack.closePeer(grant.peer().vip());
                    lan.removePeer(grant.peer().vip());
                    incoming.finish(rid);
                }
                close();
                signalChanged();
            }
        }

        @Override public void close() {
            List<AutoCloseable> owned;
            synchronized (this) {
                if (stopped) return;
                stopped = true;
                channel = null;
                owned = List.copyOf(resources);
                resources.clear();
                frames.clear();
            }
            if (setup != null) setup.close();
            cleanup.finish(owned);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        authorized = Map.of();
        stack.close();
        workers.values().forEach(PeerWorker::close);
        workers.clear();
        executor.shutdownNow();
        resourceCloser.close();
        incoming.close();
        controlOutbound.clear();
        lan.clear();
        signalChanged();
    }

    void awaitStopped() throws InterruptedException {
        while (!executor.awaitTermination(1, TimeUnit.SECONDS)) { }
        while (!resourceCloser.awaitStopped(Duration.ofSeconds(1))) { }
    }
    private static void closeQuietly(AutoCloseable value) {
        try { value.close(); } catch (Exception ignored) { }
    }
}
