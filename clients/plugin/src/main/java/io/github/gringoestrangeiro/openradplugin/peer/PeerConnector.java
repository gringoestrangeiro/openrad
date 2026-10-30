package io.github.gringoestrangeiro.openradplugin.peer;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.network.Membership;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradplugin.session.ControlSession;
import io.github.gringoestrangeiro.openradplugin.transport.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Bounded candidate negotiation. Coordinator records alone authorize endpoints and shared secrets.
 */
public final class PeerConnector {
    public record Policy(boolean tcp, boolean udp, boolean relay) {
        public Policy {
            require(tcp || udp || relay, "no enabled peer transport");
        }
    }

    public record Connected(PeerChannel channel, AutoCloseable coordinator)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                channel.close();
            } finally {
                try {
                    coordinator.close();
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("coordinator close failed", e);
                }
            }
        }
    }

    private PeerConnector() {}

    private static final long SECOND = 1_000_000_000L;
    private static final AtomicBoolean ACCEPTOR_RELAY_ALLOWED = new AtomicBoolean(true);
    private static final AtomicLong LAST_ACCEPTOR_RELAY_REQUEST = new AtomicLong();

    /** Disable acceptor-initiated relay requests if its attachment objects within five seconds. */
    public static boolean serverObjected() {
        long requested = LAST_ACCEPTOR_RELAY_REQUEST.get();
        if (requested != 0 && System.nanoTime() - requested < 5 * SECOND) {
            ACCEPTOR_RELAY_ALLOWED.set(false);
            return true;
        }
        return false;
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    private static Duration remaining(long until, int seconds) throws IOException {
        long n = Math.min(seconds * SECOND, until - System.nanoTime());
        if (n <= 0 || Thread.currentThread().isInterrupted())
            throw new IOException("peer setup cancelled/deadline");
        return Duration.ofNanos(n);
    }

    /**
     * A resource owner exists before workers open sockets; closing it also covers later
     * registrations.
     */
    private static final class Scope implements AutoCloseable {
        private final Set<AutoCloseable> resources = new HashSet<>();
        private boolean closed;

        synchronized <T extends AutoCloseable> T own(T item) {
            if (closed) closeQuietly(item);
            else resources.add(item);
            return item;
        }

        synchronized void release(AutoCloseable item) {
            resources.remove(item);
        }

        synchronized void releaseAll() {
            resources.clear();
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            for (AutoCloseable item : resources) closeQuietly(item);
            resources.clear();
        }
    }

    private interface Work {
        PeerChannel run(Scope resources) throws Exception;
    }

    private record Success(PeerChannel channel, Scope resources) {}

    private static final class Race implements AutoCloseable {
        final Scope resources = new Scope();
        final ExecutorService jobs = io.github.gringoestrangeiro.openradplugin.util.ThreadCompat.virtualExecutor();
        final BlockingQueue<Success> successes = new LinkedBlockingQueue<>();
        final AtomicInteger active = new AtomicInteger(),
                mappingActive = new AtomicInteger(),
                progress = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();

        void spawn(Work work) {
            Scope child = resources.own(new Scope());
            active.incrementAndGet();
            jobs.submit(
                    () -> {
                        boolean success = false;
                        try {
                            PeerChannel p = child.own(work.run(child));
                            if (!closed.get()) {
                                successes.offer(new Success(p, child));
                                success = true;
                            }
                        } catch (Exception ignored) {
                        } finally {
                            if (!success) child.close();
                            active.decrementAndGet();
                        }
                    });
        }

        void spawnUdp(DatagramSocket socket, Work work) {
            resources.release(socket);
            spawn(
                    scope -> {
                        scope.own(socket);
                        return work.run(scope);
                    });
        }

        PeerChannel winner() {
            Success s = successes.poll();
            if (s == null) return null;
            s.resources.releaseAll();
            return s.channel;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            resources.close();
            jobs.shutdownNow();
            for (Success s : successes) closeQuietly(s.channel);
            successes.clear();
            try {
                jobs.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static byte[] request(long cid, int operation) {
        return concat(op(operation), u64(0x020001c1, cid));
    }

    private static byte[] endpoints(
            long cid, int operation, int root, List<InetSocketAddress> endpoints, int nonce) {
        require(!endpoints.isEmpty() && endpoints.size() <= 32, "advertisement bounds");
        List<byte[]> body = new ArrayList<>();
        for (var a : endpoints)
            body.add(
                    tlv(
                            0x127d,
                            concat(
                                    text(0x030001c2, a.getAddress().getHostAddress()),
                                    u32(0x010001c3, a.getPort()))));
        if (nonce != 0) body.add(u32(0x0100020a, nonce));
        return concat(request(cid, operation), tlv(root, concat(body.toArray(byte[][]::new))));
    }

    private static byte[] mapping(long cid, InetSocketAddress endpoint, int nonce) {
        return concat(
                request(cid, 3),
                tlv(
                        0x1231,
                        concat(
                                text(0x030001c4, endpoint.getAddress().getHostAddress()),
                                u32(0x010001c5, endpoint.getPort()),
                                u32(0x0b0001dc, 0),
                                u32(0x0b0001db, 0),
                                nonce == 0 ? new byte[0] : u32(0x0100020a, nonce))));
    }

    /** Mirrors Rust candidate_address: only unusable transport destinations are rejected. */
    static InetAddress candidateAddress(String text, InetAddress route) {
        InetAddress ip = unmapped(literal(text));
        byte[] b = ip.getAddress();
        boolean broadcastOrZero =
                ip instanceof Inet4Address
                        && ((b[0] & 255) == 0 || Arrays.equals(b, new byte[] {-1, -1, -1, -1}));
        require(
                !ip.isAnyLocalAddress()
                        && !ip.isMulticastAddress()
                        && (!ip.isLoopbackAddress() || route.isLoopbackAddress())
                        && !broadcastOrZero
                        && !(ip instanceof Inet6Address && ip.isLinkLocalAddress()),
                "invalid peer endpoint");
        return ip;
    }

    /** Validate the actual IPv4 destination of an IPv4-mapped IPv6 address. */
    private static InetAddress unmapped(InetAddress ip) {
        if (!(ip instanceof Inet6Address)) return ip;
        byte[] b = ip.getAddress();
        for (int i = 0; i < 10; i++) if (b[i] != 0) return ip;
        if (b[10] != -1 || b[11] != -1) return ip;
        try {
            return InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid mapped address", e);
        }
    }

    private static final Duration COORDINATOR_DNS_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Resolve the authenticated coordinator. Numeric literals keep their address family;
     * hostnames get one bounded daemon lookup so shutdown and hostile names cannot stall.
     */
    static InetAddress coordinatorAddress(String host) throws IOException {
        require(
                host != null
                        && !host.isBlank()
                        && host.length() <= 253
                        && host.codePoints().noneMatch(Character::isISOControl),
                "invalid peer coordinator host");
        try {
            return literal(host);
        } catch (IllegalArgumentException notLiteral) {
            // A hostname; resolved below under a deadline.
        }
        CompletableFuture<InetAddress> resolved = new CompletableFuture<>();
        Thread resolver =
                io.github.gringoestrangeiro.openradplugin.util.ThreadCompat.virtualStart("openrad-coordinator-resolve",
                                () -> {
                                    try {
                                        resolved.complete(InetAddress.getByName(host));
                                    } catch (Exception failure) {
                                        resolved.completeExceptionally(failure);
                                    }
                                });
        try {
            return resolved.get(COORDINATOR_DNS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            resolver.interrupt();
            throw new IOException("peer coordinator resolution timed out", e);
        } catch (InterruptedException e) {
            resolver.interrupt();
            Thread.currentThread().interrupt();
            throw new IOException("peer coordinator resolution cancelled", e);
        } catch (ExecutionException e) {
            throw new IOException("peer coordinator resolution failed", e.getCause());
        }
    }

    private static List<InetSocketAddress> candidates(
            byte[] data, long cid, int operation, int root, InetAddress route) {
        var outer = fields(data);
        require(
                TlvCodec.operation(data) == operation && wide(outer, 0x020001c1) == cid,
                "candidate correlation mismatch");
        var body = TlvCodec.optional(outer, root);
        if (body.isEmpty()) return List.of();
        List<InetSocketAddress> result = new ArrayList<>();
        int count = 0;
        for (var r : fields(body.get()))
            if (r.tag() == 0x127d) {
                require(++count <= 32, "candidate count limit");
                try {
                    var f = fields(r.value());
                    InetAddress ip = candidateAddress(TlvCodec.text(field(f, 0x030001c2)), route);
                    long port = number(f, 0x010001c3);
                    require(port >= 1 && port <= 65535, "candidate port");
                    var a = new InetSocketAddress(ip, (int) port);
                    if (!result.contains(a)) result.add(a);
                } catch (IllegalArgumentException excluded) {
                }
            }
        return result;
    }

    private record MappedCandidate(InetSocketAddress endpoint, int nonce) {}

    private static MappedCandidate mapped(
            byte[] data, long cid, InetAddress route, boolean nonceRequired) {
        var f = fields(data);
        require(
                TlvCodec.operation(data) == 7 && wide(f, 0x020001c1) == cid,
                "mapped candidate correlation");
        f = fields(field(f, 0x1237));
        InetAddress ip = candidateAddress(TlvCodec.text(field(f, 0x030001c4)), route);
        require(ip instanceof Inet4Address, "mapped address family");
        long port = number(f, 0x010001c5),
                nonce = TlvCodec.optional(f, 0x0100020a).map(TlvCodec::int32).orElse(0L);
        require(
                port >= 1 && port <= 65535 && (!nonceRequired || nonce >= 1 && nonce <= 65535),
                "mapped port/nonce");
        return new MappedCandidate(new InetSocketAddress(ip, (int) port), (int) nonce);
    }

    private static List<InetSocketAddress> localCandidates(InetAddress route, int port)
            throws IOException {
        // Local control deployments are used by the synthetic interoperability server.
        return route.isLoopbackAddress()
                ? List.of(new InetSocketAddress(route, port))
                : UdpDiscovery.localCandidates(route, port);
    }

    private static List<Inet4Address> ues(byte[] data) {
        var root = TlvCodec.optional(fields(data), 0x1340);
        if (root.isEmpty()) return List.of();
        List<Inet4Address> hosts = new ArrayList<>();
        int count = 0;
        for (var r : fields(root.get()))
            if (r.tag() == 0x0e00036c) {
                byte[] value = r.value();
                require(
                        ++count <= 32
                                && value.length >= 2
                                && value.length <= 16
                                && value[value.length - 1] == 0,
                        "UES host bounds");
                String text = new String(value, 0, value.length - 1, StandardCharsets.US_ASCII);
                require(
                        text.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}"),
                        "invalid UES host text");
                InetAddress ip = literal(text);
                require(
                        ip instanceof Inet4Address
                                && !ip.isLoopbackAddress()
                                && !ip.isAnyLocalAddress()
                                && !ip.isMulticastAddress()
                                && !Arrays.equals(ip.getAddress(), new byte[] {-1, -1, -1, -1}),
                        "invalid UES host");
                if (!hosts.contains(ip)) hosts.add((Inet4Address) ip);
            }
        return hosts;
    }

    private static final class Relay {
        final long arrived = System.nanoTime();
        final InetSocketAddress endpoint;
        final byte[] ticket;
        final CountDownLatch auth = new CountDownLatch(1);

        Relay(byte[] data, long cid, InetAddress route) {
            var f = fields(data);
            require(wide(f, 0x020001c1) == cid, "relay CID mismatch");
            f = fields(field(f, 0x123f));
            InetAddress host = candidateAddress(TlvCodec.text(field(f, 0x030001cb)), route);
            long port = number(f, 0x010001cc);
            ticket = field(f, 0x090001ca);
            require(port >= 1 && port <= 65535 && ticket.length == 256, "relay ticket/port");
            endpoint = new InetSocketAddress(host, (int) port);
        }

        boolean ready(long lastStart, boolean exhausted, boolean progress) {
            long now = System.nanoTime();
            return exhausted
                    || now
                            >= arrived
                                    + (progress
                                            ? 16 * SECOND
                                            : Math.min(
                                                    8 * SECOND,
                                                    Math.max(0, lastStart - arrived) + 4 * SECOND));
        }
    }

    private record MappingResult(DatagramSocket socket, UdpDiscovery.Mapping mapping) {}

    private static void startMapping(
            Race race, List<Inet4Address> hosts, BlockingQueue<MappingResult> results) {
        if (hosts.size() < 2) return;
        Scope child = race.resources.own(new Scope());
        race.mappingActive.incrementAndGet();
        race.jobs.submit(
                () -> {
                    try {
                        DatagramSocket s = child.own(ReliableUdp.bind());
                        UdpDiscovery.Mapping m = UdpDiscovery.discover(s, hosts);
                        if (!race.closed.get()) {
                            child.release(s);
                            race.resources.own(s);
                            results.offer(new MappingResult(s, m));
                        }
                    } catch (Exception ignored) {
                        child.close();
                    } finally {
                        race.mappingActive.decrementAndGet();
                    }
                });
    }

    private static PeerChannel authenticateUdp(
            Scope scope,
            Race race,
            DatagramSocket socket,
            List<InetSocketAddress> endpoints,
            int nonce,
            Duration timeout,
            boolean incoming,
            byte[] password,
            Identity identity,
            Inet4Address ownIp,
            long peerRid)
            throws IOException {
        AtomicBoolean progressed = new AtomicBoolean();
        Runnable progress =
                () -> {
                    if (progressed.compareAndSet(false, true)) race.progress.incrementAndGet();
                };
        try {
            ReliableUdp stream =
                    scope.own(
                            incoming
                                    ? ReliableUdp.accept(
                                            socket, endpoints, nonce, timeout, progress)
                                    : ReliableUdp.connect(
                                            socket, endpoints, nonce, timeout, progress));
            return incoming
                    ? PeerChannel.accept(
                            stream,
                            PeerChannel.Path.DIRECT_UDP,
                            password,
                            identity.rid(),
                            ownIp,
                            peerRid)
                    : PeerChannel.connect(
                            stream,
                            PeerChannel.Path.DIRECT_UDP,
                            password,
                            identity.rid(),
                            ownIp,
                            peerRid);
        } finally {
            if (progressed.get()) race.progress.decrementAndGet();
        }
    }

    public static Connected connect(
            Identity identity,
            Inet4Address ownIp,
            Membership.Peer peer,
            int servicePort,
            Policy policy,
            Consumer<AutoCloseable> registerResource)
            throws IOException {
        return connect(
                identity,
                ownIp,
                peer,
                servicePort,
                policy,
                registerResource,
                io.github.gringoestrangeiro.openradplugin.session.ServiceConfig.modulus());
    }

    public static Connected connect(
            Identity identity,
            Inet4Address ownIp,
            Membership.Peer peer,
            int servicePort,
            Policy policy,
            Consumer<AutoCloseable> registerResource,
            byte[] modulus)
            throws IOException {
        // The purpose-5 RSA/SH handshake authenticates the coordinator; private
        // addresses and hostnames are admitted exactly where Rust admits them.
        InetAddress coordinator = coordinatorAddress(peer.server());
        long until = System.nanoTime() + 40 * SECOND;
        ControlSession coord =
                ControlSession.authenticate(
                        coordinator.getHostAddress(),
                        servicePort,
                        identity,
                        5,
                        Duration.ofSeconds(40),
                        modulus,
                        registerResource::accept);
        registerResource.accept(coord);
        try (Race race = new Race()) {
            registerResource.accept(race);
            coord.send(login(identity.nodeName(), coord.latency(), 5, peer.rid()));
            InetAddress route = coord.localAddress();
            Long cid = null;
            byte[] password = null;
            Integer serverId = null;
            List<Inet4Address> hosts = List.of();
            List<InetSocketAddress> tcp = List.of(), udp = List.of();
            boolean tcpStarted = false, udpStarted = false, mappedStarted = false;
            boolean tcpSeen = !policy.tcp, udpSeen = !policy.udp;
            int nonce = 0, records = 0;
            long recordsWindow = System.nanoTime();
            long lastStart = 0;
            DatagramSocket udpSocket = null, mappedSocket = null;
            MappedCandidate mapped = null;
            Relay relay = null;
            BlockingQueue<MappingResult> mappings = new ArrayBlockingQueue<>(1);
            try {
                while (System.nanoTime() < until
                        && !Thread.currentThread().isInterrupted()
                        && !race.closed.get()) {
                    PeerChannel won = race.winner();
                    if (won != null) {
                        coord.sustain();
                        return new Connected(won, coord);
                    }
                    MappingResult discovered = mappings.poll();
                    if (discovered != null && cid != null) {
                        mappedSocket = discovered.socket;
                        coord.send(mapping(cid, discovered.mapping.endpoint(), 0));
                    }
                    if (cid != null) {
                        long connection = cid;
                        byte[] secret = password;
                        if (policy.tcp && !tcpStarted && serverId != null && !tcp.isEmpty()) {
                            tcpStarted = true;
                            lastStart = System.nanoTime();
                            int sid = serverId;
                            List<InetSocketAddress> ordered = orderTcp(tcp);
                            int lanes = Math.min(4, ordered.size());
                            for (int lane = 0; lane < lanes; lane++) {
                                List<InetSocketAddress> batch = new ArrayList<>();
                                for (int i = lane; i < ordered.size(); i += lanes)
                                    batch.add(ordered.get(i));
                                race.spawn(
                                        scope -> {
                                            IOException last =
                                                    new IOException("TCP candidates failed");
                                            long laneUntil =
                                                    Math.min(until, System.nanoTime() + 8 * SECOND);
                                            for (var a : batch) {
                                                boolean progress = false;
                                                FramedTcp stream = null;
                                                try {
                                                    stream =
                                                            scope.own(
                                                                    FramedTcp.connect(
                                                                            a,
                                                                            remaining(laneUntil, 8),
                                                                            Duration.ofSeconds(2)));
                                                    stream.peerRendezvous(
                                                            identity.rid(), connection, sid);
                                                    race.progress.incrementAndGet();
                                                    progress = true;
                                                    return PeerChannel.connect(
                                                            stream,
                                                            PeerChannel.Path.DIRECT_TCP,
                                                            secret,
                                                            identity.rid(),
                                                            ownIp,
                                                            peer.rid());
                                                } catch (IOException | RuntimeException e) {
                                                    if (stream != null) {
                                                        scope.release(stream);
                                                        closeQuietly(stream);
                                                    }
                                                    last =
                                                            new IOException(
                                                                    "direct TCP candidate failed",
                                                                    e);
                                                } finally {
                                                    if (progress) race.progress.decrementAndGet();
                                                }
                                            }
                                            throw last;
                                        });
                            }
                        }
                        if (policy.udp
                                && !udpStarted
                                && udpSocket != null
                                && nonce != 0
                                && !udp.isEmpty()) {
                            udpStarted = true;
                            lastStart = System.nanoTime();
                            DatagramSocket socket = udpSocket;
                            int n = nonce;
                            List<InetSocketAddress> endpoints = udp;
                            race.spawnUdp(
                                    socket,
                                    scope ->
                                            authenticateUdp(
                                                    scope,
                                                    race,
                                                    socket,
                                                    endpoints,
                                                    n,
                                                    remaining(until, 14),
                                                    false,
                                                    secret,
                                                    identity,
                                                    ownIp,
                                                    peer.rid()));
                        }
                        if (policy.udp
                                && !mappedStarted
                                && mappedSocket != null
                                && mapped != null) {
                            mappedStarted = true;
                            lastStart = System.nanoTime();
                            DatagramSocket socket = mappedSocket;
                            MappedCandidate m = mapped;
                            race.spawnUdp(
                                    socket,
                                    scope ->
                                            authenticateUdp(
                                                    scope,
                                                    race,
                                                    socket,
                                                    List.of(m.endpoint),
                                                    m.nonce,
                                                    remaining(until, 14),
                                                    false,
                                                    secret,
                                                    identity,
                                                    ownIp,
                                                    peer.rid()));
                        }
                        if (relay != null
                                && relay.ready(
                                        lastStart,
                                        race.active.get() <= 1
                                                && race.mappingActive.get() == 0
                                                && (mappedSocket == null || mappedStarted)
                                                && tcpSeen
                                                && udpSeen,
                                        race.progress.get() > 0)) relay.auth.countDown();
                    }
                    if (System.nanoTime() - recordsWindow >= SECOND) {
                        recordsWindow = System.nanoTime();
                        records = 0;
                    }
                    if (records >= 64) {
                        java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
                        continue;
                    }
                    if (!coord.ready(Duration.ofMillis(10))) continue;
                    byte[] data = coord.receive();
                    records++;
                    var f = fields(data);
                    int operation = TlvCodec.operation(data);
                    switch (operation) {
                        case 21 -> {
                            serverId =
                                    TlvCodec.optional(f, 0x0100025b)
                                            .map(TlvCodec::int32)
                                            .map(Long::intValue)
                                            .orElse(null);
                            hosts = ues(data);
                        }
                        case 11 -> {
                            require(cid == null, "duplicate NewConnection");
                            cid = wide(f, 0x020001c1);
                            var credentials = fields(field(f, 0x1235));
                            require(
                                    wide(credentials, 0x020001e1) == peer.rid(),
                                    "NewConnection target mismatch");
                            password = field(credentials, 0x0a0001cd);
                            require(
                                    cid != 0 && password.length >= 6 && password.length <= 1024,
                                    "invalid connection credentials");
                            if (policy.tcp) coord.send(request(cid, 2));
                            if (policy.udp) {
                                try {
                                    udpSocket = race.resources.own(ReliableUdp.bind());
                                    coord.send(
                                            endpoints(
                                                    cid,
                                                    28,
                                                    0x127b,
                                                    localCandidates(
                                                            route, udpSocket.getLocalPort()),
                                                    0));
                                } catch (IOException e) {
                                    if (udpSocket != null) udpSocket.close();
                                    udpSocket = null;
                                    udpSeen = true;
                                }
                                startMapping(race, hosts, mappings);
                            }
                            if (policy.relay) coord.send(request(cid, 22));
                        }
                        case 6 -> {
                            if (policy.tcp) {
                                require(cid != null, "TCP candidates before offer");
                                tcp = candidates(data, cid, 6, 0x1236, route);
                                tcpSeen = true;
                            }
                        }
                        case 29 -> {
                            if (policy.udp) {
                                require(cid != null, "UDP candidates before offer");
                                udp = candidates(data, cid, 29, 0x127c, route);
                                var body = TlvCodec.optional(f, 0x127c);
                                long n =
                                        body.isEmpty()
                                                ? 0
                                                : TlvCodec.optional(fields(body.get()), 0x0100020a)
                                                        .map(TlvCodec::int32)
                                                        .orElse(0L);
                                nonce = n >= 1 && n <= 65535 ? (int) n : 0;
                                udpSeen = true;
                            }
                        }
                        case 7 -> {
                            if (policy.udp) {
                                require(cid != null, "mapped candidate before offer");
                                mapped = mapped(data, cid, route, true);
                            }
                        }
                        case 23 -> {
                            if (policy.relay && relay == null) {
                                require(cid != null, "relay before offer");
                                relay = new Relay(data, cid, route);
                                Relay offer = relay;
                                byte[] secret = password;
                                race.spawn(
                                        scope -> {
                                            FramedTcp stream =
                                                    scope.own(
                                                            FramedTcp.connect(
                                                                    offer.endpoint,
                                                                    remaining(until, 40)));
                                            pair(stream, offer.ticket);
                                            offer.auth.await(
                                                    Math.max(1, until - System.nanoTime()),
                                                    TimeUnit.NANOSECONDS);
                                            return PeerChannel.connect(
                                                    stream,
                                                    PeerChannel.Path.RELAY,
                                                    secret,
                                                    identity.rid(),
                                                    ownIp,
                                                    peer.rid());
                                        });
                            }
                        }
                        case 16, 44 -> throw new IOException("coordinator refused peer connection");
                        default -> {}
                    }
                }
                throw new IOException("peer setup deadline or cancelled");
            } finally {
                if (password != null) Arrays.fill(password, (byte) 0);
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(coord);
            throw e;
        }
    }

    private static void pair(FramedTcp stream, byte[] ticket) throws IOException {
        stream.send(concat(u32(0x010001df, 1), u32(0x0100032b, 2), tlv(0x090001ca, ticket)));
        long until = System.nanoTime() + 33 * SECOND;
        while (!stream.ready(Duration.ofMillis(50)))
            if (System.nanoTime() >= until) throw new IOException("relay pairing timeout");
        require(number(fields(stream.receive(65536)), 0x010001df) == 2, "relay ticket rejected");
    }

    private static List<InetSocketAddress> orderTcp(List<InetSocketAddress> endpoints) {
        List<ArrayDeque<InetSocketAddress>> groups =
                List.of(
                        new ArrayDeque<>(),
                        new ArrayDeque<>(),
                        new ArrayDeque<>(),
                        new ArrayDeque<>());
        for (var a : endpoints) {
            InetAddress ip = a.getAddress();
            boolean local =
                    ip.isSiteLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress();
            groups.get((local ? 2 : 0) + (ip instanceof Inet6Address ? 1 : 0)).add(a);
        }
        List<InetSocketAddress> ordered = new ArrayList<>();
        while (ordered.size() < endpoints.size())
            for (var g : groups) if (!g.isEmpty()) ordered.add(g.remove());
        return ordered;
    }

    public static PeerChannel accept(
            IncomingHub.Setup setup,
            Identity identity,
            Inet4Address ownIp,
            Membership.Peer peer,
            InetAddress routeIp,
            Policy policy,
            Consumer<byte[]> controlSend,
            Consumer<AutoCloseable> registerResource)
            throws IOException {
        return accept(
                setup,
                identity,
                ownIp,
                peer,
                routeIp,
                List.of(),
                policy,
                controlSend,
                registerResource);
    }

    public static PeerChannel accept(
            IncomingHub.Setup setup,
            Identity identity,
            Inet4Address ownIp,
            Membership.Peer peer,
            InetAddress routeIp,
            Policy policy,
            Consumer<byte[]> controlSend,
            Consumer<AutoCloseable> registerResource,
            List<Inet4Address> uesHosts)
            throws IOException {
        return accept(
                setup,
                identity,
                ownIp,
                peer,
                routeIp,
                uesHosts,
                policy,
                controlSend,
                registerResource);
    }

    public static PeerChannel accept(
            IncomingHub.Setup setup,
            Identity identity,
            Inet4Address ownIp,
            Membership.Peer peer,
            InetAddress routeIp,
            List<Inet4Address> uesHosts,
            Policy policy,
            Consumer<byte[]> controlSend,
            Consumer<AutoCloseable> registerResource)
            throws IOException {
        require(setup.peerRid() == peer.rid(), "incoming setup peer mismatch");
        long until = System.nanoTime() + 40 * SECOND, began = System.nanoTime();
        byte[] password = setup.password();
        int nonce = new java.security.SecureRandom().nextInt(65535) + 1;
        try (Race race = new Race()) {
            registerResource.accept(race);
            ServerSocket listener = null;
            DatagramSocket udpSocket = null, mappedSocket = null;
            MappedCandidate mapped = null;
            Relay relay = null;
            long lastStart = 0;
            boolean relayRequested = false, udpStarted = false, mappedStarted = false;
            int accepted = 0;
            BlockingQueue<MappingResult> mappings = new ArrayBlockingQueue<>(1);
            BlockingQueue<FramedTcp> validated = new ArrayBlockingQueue<>(8);
            AtomicInteger validating = new AtomicInteger();
            AtomicBoolean tcpAuth = new AtomicBoolean();
            int recordsSeen = 0;
            if (policy.tcp) {
                try {
                    listener = race.resources.own(new ServerSocket(0));
                    listener.setSoTimeout(2);
                    controlSend.accept(
                            endpoints(
                                    setup.cid(),
                                    2,
                                    0x1230,
                                    localCandidates(routeIp, listener.getLocalPort()),
                                    0));
                } catch (IOException e) {
                    if (listener != null) listener.close();
                    listener = null;
                }
            }
            if (policy.udp) {
                try {
                    udpSocket = race.resources.own(ReliableUdp.bind());
                    controlSend.accept(
                            endpoints(
                                    setup.cid(),
                                    28,
                                    0x127b,
                                    localCandidates(routeIp, udpSocket.getLocalPort()),
                                    nonce));
                } catch (IOException e) {
                    if (udpSocket != null) udpSocket.close();
                    udpSocket = null;
                }
                startMapping(race, uesHosts, mappings);
            }
            while (System.nanoTime() < until
                    && !Thread.currentThread().isInterrupted()
                    && !race.closed.get()) {
                PeerChannel won = race.winner();
                if (won != null) return won;
                if (recordsSeen == 0 && accepted == 0 && System.nanoTime() - began > 15 * SECOND)
                    throw new IOException("incoming offer abandoned");
                if (listener != null && accepted < 8 && validating.get() < 4) {
                    try {
                        Socket socket = listener.accept();
                        accepted++;
                        Scope validation = race.resources.own(new Scope());
                        validation.own(socket);
                        validating.incrementAndGet();
                        race.jobs.submit(
                                () -> {
                                    try {
                                        FramedTcp stream =
                                                validation.own(
                                                        new FramedTcp(socket, remaining(until, 3)));
                                        stream.acceptRendezvous(peer.rid(), setup.cid());
                                        validation.releaseAll();
                                        race.resources.own(stream);
                                        if (!validated.offer(stream)) closeQuietly(stream);
                                    } catch (Exception ignored) {
                                        validation.close();
                                    } finally {
                                        validating.decrementAndGet();
                                    }
                                });
                    } catch (SocketTimeoutException ignored) {
                    }
                }
                if (!tcpAuth.get()) {
                    FramedTcp stream = validated.poll();
                    if (stream != null) {
                        lastStart = System.nanoTime();
                        tcpAuth.set(true);
                        race.resources.release(stream);
                        race.spawn(
                                scope -> {
                                    scope.own(stream);
                                    stream.handshakeDeadline(remaining(until, 10));
                                    race.progress.incrementAndGet();
                                    try {
                                        return PeerChannel.accept(
                                                stream,
                                                PeerChannel.Path.DIRECT_TCP,
                                                password,
                                                identity.rid(),
                                                ownIp,
                                                peer.rid());
                                    } finally {
                                        race.progress.decrementAndGet();
                                        tcpAuth.set(false);
                                    }
                                });
                    }
                }
                MappingResult result = mappings.poll();
                if (result != null) {
                    mappedSocket = result.socket;
                    controlSend.accept(mapping(setup.cid(), result.mapping.endpoint(), nonce));
                }
                if (!mappedStarted && mappedSocket != null && mapped != null) {
                    mappedStarted = true;
                    lastStart = System.nanoTime();
                    DatagramSocket socket = mappedSocket;
                    MappedCandidate m = mapped;
                    race.spawnUdp(
                            socket,
                            scope ->
                                    authenticateUdp(
                                            scope,
                                            race,
                                            socket,
                                            List.of(m.endpoint),
                                            nonce,
                                            remaining(until, 14),
                                            true,
                                            password,
                                            identity,
                                            ownIp,
                                            peer.rid()));
                }
                if (policy.relay
                        && !relayRequested
                        && relay == null
                        && System.nanoTime() - began
                                >= (policy.tcp || policy.udp ? 2 * SECOND : 300_000_000L)) {
                    if (ACCEPTOR_RELAY_ALLOWED.get()) {
                        controlSend.accept(request(setup.cid(), 22));
                        LAST_ACCEPTOR_RELAY_REQUEST.set(System.nanoTime());
                    }
                    relayRequested = true;
                }
                if (relay != null
                        && relay.ready(
                                lastStart, !policy.tcp && !policy.udp, race.progress.get() > 0))
                    relay.auth.countDown();
                byte[] data;
                try {
                    data = setup.records().poll(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("incoming cancelled", e);
                }
                if (data == null) continue;
                var f = fields(data);
                int operation = TlvCodec.operation(data);
                require(wide(f, 0x020001c1) == setup.cid(), "incoming CID mismatch");
                recordsSeen++;
                switch (operation) {
                    case 29 -> {
                        if (policy.udp && !udpStarted && udpSocket != null) {
                            List<InetSocketAddress> endpoints =
                                    candidates(data, setup.cid(), 29, 0x127c, routeIp);
                            if (!endpoints.isEmpty()) {
                                udpStarted = true;
                                lastStart = System.nanoTime();
                                DatagramSocket socket = udpSocket;
                                race.spawnUdp(
                                        socket,
                                        scope ->
                                                authenticateUdp(
                                                        scope,
                                                        race,
                                                        socket,
                                                        endpoints,
                                                        nonce,
                                                        remaining(until, 14),
                                                        true,
                                                        password,
                                                        identity,
                                                        ownIp,
                                                        peer.rid()));
                            }
                        }
                    }
                    case 7 -> {
                        if (policy.udp) mapped = mapped(data, setup.cid(), routeIp, false);
                    }
                    case 23 -> {
                        if (policy.relay && relay == null) {
                            relay = new Relay(data, setup.cid(), routeIp);
                            Relay offer = relay;
                            race.spawn(
                                    scope -> {
                                        FramedTcp stream =
                                                scope.own(
                                                        FramedTcp.connect(
                                                                offer.endpoint,
                                                                remaining(until, 40)));
                                        pair(stream, offer.ticket);
                                        offer.auth.await(
                                                Math.max(1, until - System.nanoTime()),
                                                TimeUnit.NANOSECONDS);
                                        return PeerChannel.accept(
                                                stream,
                                                PeerChannel.Path.RELAY,
                                                password,
                                                identity.rid(),
                                                ownIp,
                                                peer.rid());
                                    });
                        }
                    }
                    default -> {}
                }
            }
            throw new IOException("incoming peer setup deadline or cancelled");
        } finally {
            Arrays.fill(password, (byte) 0);
            setup.close();
        }
    }
}
