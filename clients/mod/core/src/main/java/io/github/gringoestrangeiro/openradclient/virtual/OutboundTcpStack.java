package io.github.gringoestrangeiro.openradclient.virtual;

import static io.github.gringoestrangeiro.openradplugin.link.TcpSegment.*;
import static io.github.gringoestrangeiro.openradplugin.virtual.TcpSequence.*;

import io.github.gringoestrangeiro.openradplugin.link.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/**
 * An active, bounded TCP endpoint for authenticated OpenRad Ethernet channels. This class never
 * opens an OS socket, installs routes or forwards arbitrary traffic. The caller authenticates the
 * peer before passing frames, and the endpoint additionally binds every tuple to its VIP and MAC.
 * Frame delivery only enqueues work: a slow game reader never blocks a VPN receive thread.
 */
public final class OutboundTcpStack implements AutoCloseable {
    @FunctionalInterface
    public interface FrameSink {
        /** Called on the stack's sender thread. Must have a bounded transport write timeout. */
        void send(Inet4Address destination, byte[] ethernet) throws IOException;
    }

    private record Key(int peerIp, int peerPort, int localPort) {}
    private record Frame(Inet4Address destination, byte[] bytes) {}
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] MSS_OPTION = {2, 4, 5, (byte) 0xb4};
    private static final int CAPACITY = 65536, WIRE_WINDOW = 65535, MAX_TIME_WAIT = 64;
    private static final long SECOND = 1_000_000_000L;
    private final Inet4Address localVip;
    private final int localIp, maxConnections;
    private final byte[] localMac;
    private final FrameSink sink;
    private final LongSupplier clock;
    private final ConcurrentMap<Key, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentMap<Inet4Address, byte[]> negotiatedMacs = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(1024);
    private final Object admission = new Object();
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService timer;
    private final Thread sender;
    private volatile boolean closed;
    private int nextPort = 49152;

    public OutboundTcpStack(Inet4Address localVip, FrameSink sink) {
        this(localVip, sink, 16);
    }

    public OutboundTcpStack(Inet4Address localVip, FrameSink sink, int maxConnections) {
        this(localVip, sink, maxConnections, System::nanoTime, true);
    }

    // The manual clock/worker variant allows deterministic packet-level tests without sleeping.
    OutboundTcpStack(Inet4Address localVip, FrameSink sink, int maxConnections,
                     LongSupplier clock, boolean workers) {
        this.localVip = Objects.requireNonNull(localVip);
        localIp = ip(localVip);
        requireVip(localVip);
        if (maxConnections < 1 || maxConnections > 64)
            throw new IllegalArgumentException("maxConnections must be between 1 and 64");
        this.maxConnections = maxConnections;
        localMac = OpenRadAddress.mac(localIp);
        this.sink = Objects.requireNonNull(sink);
        this.clock = Objects.requireNonNull(clock);
        nextPort += random.nextInt(16384);
        if (workers) {
            timer = Executors.newSingleThreadScheduledExecutor(r ->
                    Thread.ofPlatform().daemon().name("openrad-client-tcp-timer").unstarted(r));
            sender = Thread.ofPlatform().daemon().name("openrad-client-tcp-frames").unstarted(() -> {
                while (!closed) {
                    try {
                        Frame frame = frames.poll(100, TimeUnit.MILLISECONDS);
                        if (frame != null) deliver(frame);
                    } catch (InterruptedException e) {
                        if (closed) return;
                    }
                }
            });
            sender.start();
            timer.scheduleWithFixedDelay(this::tick, 20, 20, TimeUnit.MILLISECONDS);
        } else {
            timer = null;
            sender = null;
        }
    }

    /** Blocks only the caller until SYN/ACK, timeout, cancellation or peer revocation. */
    public Connection connect(Inet4Address destination, int port, Duration timeout) throws IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(timeout);
        requireVip(destination);
        if (destination.equals(localVip) || port < 1 || port > 65535
                || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("invalid virtual TCP destination/timeout");
        Connection connection;
        synchronized (admission) {
            if (closed) throw new SocketException("OpenRad TCP stack is closed");
            // TIME_WAIT tuples only reserve their port: closed server-list pings must not keep
            // a player from joining. They are bounded separately and the oldest is reused.
            List<Connection> timeWait = connections.values().stream()
                    .filter(c -> c.timeWaitUntil != 0).toList();
            if (connections.size() - timeWait.size() >= maxConnections)
                throw new SocketException("OpenRad connection capacity reached");
            if (timeWait.size() >= MAX_TIME_WAIT) {
                Connection oldest = timeWait.stream()
                        .min(Comparator.comparingLong(c -> c.timeWaitUntil)).orElseThrow();
                synchronized (oldest) { oldest.dispose(); }
            }
            int localPort = allocatePort();
            Key key = new Key(ip(destination), port, localPort);
            connection = new Connection(key, destination, timeout.toNanos());
            connections.put(key, connection);
        }
        synchronized (connection) {
            if (closed || connection.disposed) {
                connection.fail(new SocketException("OpenRad TCP stack is closed"), false);
            } else {
                connection.track(SYN, EMPTY);
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!connection.established && connection.failure == null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    connection.fail(new SocketTimeoutException("OpenRad TCP handshake timed out"), true);
                    break;
                }
                connection.await(remaining);
            }
            if (connection.failure != null) throw connection.failure;
            return connection;
        }
    }

    /** Invalid, unrelated and unsupported frames are dropped; this never waits for game I/O. */
    public void acceptEthernet(Inet4Address authenticatedPeerVip, byte[] raw) {
        if (closed || authenticatedPeerVip == null || raw == null) return;
        try {
            requireVip(authenticatedPeerVip);
            int peerIp = ip(authenticatedPeerVip);
            EthernetFrame ethernet = EthernetFrame.parse(raw);
            byte[] peerMac = peerMac(authenticatedPeerVip);
            if (!Arrays.equals(ethernet.sourceMac(), peerMac)) return;
            if (ethernet.etherType() == EthernetFrame.ARP) {
                if (!Arrays.equals(ethernet.destinationMac(), localMac)
                        && !broadcast(ethernet.destinationMac())) return;
                ArpPacket arp = ArpPacket.parse(ethernet.payload());
                // Only answer an authenticated peer that already has an admitted outgoing tuple.
                if (arp.opcode() == ArpPacket.REQUEST && arp.targetIp() == localIp
                        && arp.senderIp() == peerIp && Arrays.equals(arp.senderMac(), peerMac)
                        && connections.keySet().stream().anyMatch(k -> k.peerIp == peerIp)) {
                    byte[] reply = new ArpPacket(ArpPacket.REPLY, localMac, localIp, peerMac, peerIp).encode();
                    enqueue(authenticatedPeerVip,
                            new EthernetFrame(peerMac, localMac, EthernetFrame.ARP, reply).encode());
                }
                return;
            }
            if (!Arrays.equals(ethernet.destinationMac(), localMac)) return;
            Ipv4Packet packet = Ipv4Packet.parse(ethernet.payload());
            if (packet.sourceIp() != peerIp || packet.destinationIp() != localIp) return;
            TcpSegment tcp = TcpSegment.parse(packet.tcpBytes(), peerIp, localIp);
            Connection connection = connections.get(new Key(peerIp, tcp.sourcePort(), tcp.destinationPort()));
            if (connection != null) synchronized (connection) {
                connection.receive(tcp);
            }
        } catch (IllegalArgumentException ignored) {
            // Includes checksums, fragments, options, length and address validation.
        }
    }

    public void closePeer(Inet4Address destination) {
        Objects.requireNonNull(destination);
        int peerIp = ip(destination);
        synchronized (admission) {
            for (Connection c : List.copyOf(connections.values())) {
                if (c.key.peerIp == peerIp) synchronized (c) {
                    c.fail(new SocketException("OpenRad peer disconnected or membership was revoked"), false);
                }
            }
            frames.removeIf(frame -> frame.destination.equals(destination));
            negotiatedMacs.remove(destination);
        }
    }

    /** Bind the MAC authenticated by the encrypted tunnel handshake before publishing its channel. */
    public void setPeerMac(Inet4Address destination, byte[] mac) {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(mac);
        requireVip(destination);
        boolean any = false;
        for (byte value : mac) any |= value != 0;
        if (destination.equals(localVip) || mac.length != 6 || (mac[0] & 1) != 0 || !any)
            throw new IllegalArgumentException("invalid authenticated peer MAC");
        synchronized (admission) {
            if (closed) throw new IllegalStateException("OpenRad TCP stack is closed");
            byte[] previous = negotiatedMacs.get(destination);
            if (previous != null && Arrays.equals(previous, mac)) return;
            if (previous == null && negotiatedMacs.size() >= 4096)
                throw new IllegalStateException("OpenRad peer MAC capacity reached");
            if (!Arrays.equals(peerMac(destination), mac)) closePeer(destination);
            negotiatedMacs.put(destination, mac.clone());
        }
    }

    private byte[] peerMac(Inet4Address destination) {
        byte[] negotiated = negotiatedMacs.get(destination);
        return negotiated == null ? OpenRadAddress.mac(ip(destination)) : negotiated;
    }

    public int connectionCount() { return connections.size(); }

    /** Includes handshakes and half-closed tuples: discovery eviction must preserve all of them. */
    public boolean hasConnections(Inet4Address destination) {
        Objects.requireNonNull(destination);
        int peerIp = ip(destination);
        return connections.keySet().stream().anyMatch(key -> key.peerIp == peerIp);
    }

    @Override public void close() {
        synchronized (admission) {
            if (closed) return;
            closed = true;
            for (Connection c : List.copyOf(connections.values())) synchronized (c) {
                c.fail(new SocketException("OpenRad TCP stack is closed"), false);
            }
            frames.clear();
            negotiatedMacs.clear();
        }
        if (timer != null) timer.shutdownNow();
        if (sender != null) sender.interrupt();
    }

    private int allocatePort() throws SocketException {
        for (int i = 0; i < 16384; i++) {
            int candidate = nextPort;
            nextPort = nextPort == 65535 ? 49152 : nextPort + 1;
            if (connections.keySet().stream().noneMatch(k -> k.localPort == candidate)) return candidate;
        }
        throw new SocketException("OpenRad ephemeral ports exhausted");
    }

    private void enqueue(Inet4Address destination, byte[] frame) {
        // Queue pressure is packet loss, recovered by TCP; never block a VPN receive thread.
        if (!closed) frames.offer(new Frame(destination, frame));
    }

    private void deliver(Frame frame) {
        try {
            sink.send(frame.destination, frame.bytes);
        } catch (IOException | RuntimeException ignored) {
            // Transient channel write errors are recovered by retransmission or bounded timeout.
        }
    }

    void pumpFrames() {
        for (int n = 0; n < 1024; n++) {
            Frame frame = frames.poll();
            if (frame == null) break;
            deliver(frame);
        }
    }

    void tick() {
        long now = clock.getAsLong();
        for (Connection c : connections.values()) synchronized (c) {
            c.tick(now);
        }
    }

    private static int ip(Inet4Address address) {
        byte[] b = address.getAddress();
        return (b[0] & 255) << 24 | (b[1] & 255) << 16 | (b[2] & 255) << 8 | b[3] & 255;
    }

    private static void requireVip(Inet4Address address) {
        int value = ip(address);
        if ((value >>> 24) != 26 || (value & 0xffffff) == 0 || (value & 0xffffff) == 0xffffff)
            throw new IllegalArgumentException("virtual TCP requires an OpenRad 26.x IPv4 VIP");
    }

    private static boolean broadcast(byte[] mac) {
        for (byte b : mac) if ((b & 255) != 255) return false;
        return true;
    }

    private static int mss(byte[] options) {
        int result = 536;
        for (int i = 0; i < options.length;) {
            int kind = options[i] & 255;
            if (kind == 0) break;
            if (kind == 1) { i++; continue; }
            if (i + 1 >= options.length) throw new IllegalArgumentException("truncated TCP option");
            int size = options[i + 1] & 255;
            if (size < 2 || i + size > options.length)
                throw new IllegalArgumentException("invalid TCP option");
            if (kind == 2) {
                if (size != 4) throw new IllegalArgumentException("invalid MSS option");
                int advertised = Checksum.u16(options, i + 2);
                if (advertised < 64) throw new IllegalArgumentException("MSS below supported minimum");
                result = Math.min(1460, advertised);
            }
            // No scaling/SACK/timestamps are negotiated; unknown well-formed options are ignored.
            i += size;
        }
        return result;
    }

    private static final class Flight {
        long sequence, sentAt;
        final long firstSent;
        final int flags;
        int retries;
        byte[] data;
        Flight(long sequence, int flags, byte[] data, long now) {
            this.sequence = sequence;
            this.flags = flags;
            this.data = data;
            sentAt = firstSent = now;
        }
        long end() { return sequence + data.length + ((flags & (SYN | FIN)) != 0 ? 1 : 0); }
    }

    public final class Connection implements AutoCloseable {
        private final Key key;
        private final Inet4Address destination;
        private final byte[] peerMac, receiveRing = new byte[CAPACITY];
        private final BitSet present = new BitSet(CAPACITY);
        private final ArrayDeque<ByteBuffer> unsent = new ArrayDeque<>();
        private final ArrayDeque<Flight> flights = new ArrayDeque<>();
        private final long initialSequence, createdAt, handshakeTimeout;
        private long sendUna, sendNext, receiveNext, readSequence, remoteSyn;
        private long windowSequence, windowAck, pendingFin = -1, lastProgress, closeAt;
        private volatile long timeWaitUntil;
        private long rto = SECOND, lastProbe;
        private int peerWindow, peerMss = 536, bufferedSend, unsentBytes, duplicateAcks;
        private int congestionWindow = 5360, slowStartThreshold = 65535;
        private double smoothedRtt, rttVariance;
        private boolean established, receivedFin, outputClosed, applicationClosed, finSent, finAcked, disposed;
        private boolean peerClosedFirst;
        private IOException failure;
        private final InputStream input = new TcpInput();
        private final OutputStream output = new TcpOutput();

        private Connection(Key key, Inet4Address destination, long timeout) {
            this.key = key;
            this.destination = destination;
            peerMac = peerMac(destination).clone();
            initialSequence = Integer.toUnsignedLong(random.nextInt());
            sendUna = sendNext = initialSequence;
            createdAt = lastProgress = clock.getAsLong();
            handshakeTimeout = timeout;
        }

        public InputStream input() { return input; }
        public OutputStream output() { return output; }

        /** Sends FIN after queued data, retaining the independent inbound half of the stream. */
        public synchronized void shutdownOutput() throws IOException {
            checkOpen();
            outputClosed = true;
            if (closeAt == 0) closeAt = clock.getAsLong();
            flushPending();
            notifyAll();
        }

        /** Nonblocking graceful close. The bounded FIN retransmission state remains until timeout. */
        @Override public synchronized void close() {
            if (applicationClosed) return;
            applicationClosed = outputClosed = true;
            if (closeAt == 0) closeAt = clock.getAsLong();
            if (!disposed) flushPending();
            notifyAll();
        }

        private void await(long nanos) throws IOException {
            try {
                TimeUnit.NANOSECONDS.timedWait(this, Math.max(1, nanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException("OpenRad TCP operation cancelled");
                fail(interrupted, true);
                throw interrupted;
            }
        }

        private void checkOpen() throws IOException {
            if (failure != null) throw failure;
            if (applicationClosed || disposed || closed) throw new SocketException("OpenRad TCP connection is closed");
        }

        private int receiveWindow() {
            if (receivedFin) return 0;
            return (int) Math.max(0, readSequence + WIRE_WINDOW - receiveNext);
        }

        private boolean acceptable(long sequence, int length) {
            int window = receiveWindow();
            if (length == 0)
                return window == 0 ? sequence == receiveNext
                        : sequence >= receiveNext && sequence < receiveNext + window;
            return window > 0 && sequence < receiveNext + window && sequence + length > receiveNext;
        }

        private void receive(TcpSegment tcp) {
            if (disposed) return;
            if (!established) {
                if ((tcp.flags() & RST) != 0) {
                    if ((tcp.flags() & ACK) != 0 && unwrap(tcp.acknowledgement(), sendNext) == sendNext)
                        fail(new ConnectException("OpenRad destination refused the connection"), false);
                    return;
                }
                if ((tcp.flags() & ~(ECE | CWR)) != (SYN | ACK)
                        || unwrap(tcp.acknowledgement(), sendNext) != sendNext
                        || tcp.payload().length != 0) return;
                int negotiatedMss;
                try { negotiatedMss = mss(tcp.options()); }
                catch (IllegalArgumentException malformed) { return; }
                peerMss = negotiatedMss;
                congestionWindow = Math.min(10 * peerMss, 14600);
                remoteSyn = tcp.sequence();
                receiveNext = readSequence = remoteSyn + 1;
                windowSequence = remoteSyn;
                windowAck = sendNext;
                peerWindow = tcp.window();
                established = true;
                acknowledge(sendNext, false);
                ack();
                notifyAll();
                return;
            }
            long sequence = unwrap(tcp.sequence(), receiveNext);
            if (timeWaitUntil != 0) {
                if ((tcp.flags() & FIN) != 0 && sequence + tcp.payload().length == receiveNext - 1) {
                    ack();
                    timeWaitUntil = clock.getAsLong() + 30 * SECOND;
                }
                return;
            }
            if ((tcp.flags() & RST) != 0) {
                if (sequence == receiveNext) fail(new SocketException("OpenRad peer reset the connection"), false);
                else if (acceptable(sequence, 0)) ack();
                return;
            }
            if ((tcp.flags() & SYN) != 0) {
                // A lost third handshake packet makes the passive endpoint repeat SYN/ACK.
                if (sequence == remoteSyn && (tcp.flags() & ACK) != 0) ack();
                return;
            }
            if ((tcp.flags() & ACK) == 0) return;
            int length = tcp.payload().length + ((tcp.flags() & FIN) != 0 ? 1 : 0);
            if (!acceptable(sequence, length)) { ack(); return; }
            long acknowledgement = unwrap(tcp.acknowledgement(), sendUna);
            if (acknowledgement > sendNext) { ack(); return; }
            if (acknowledgement >= sendUna) {
                boolean windowChanged = false;
                if (sequence > windowSequence || (sequence == windowSequence && acknowledgement >= windowAck)) {
                    windowChanged = peerWindow != tcp.window();
                    peerWindow = tcp.window();
                    windowSequence = sequence;
                    windowAck = acknowledgement;
                }
                acknowledge(acknowledgement, length == 0 && !windowChanged);
            }
            if (length > 0) receiveData(tcp, sequence);
            flushPending();
        }

        private void receiveData(TcpSegment tcp, long sequence) {
            if (receivedFin) { ack(); return; }
            byte[] bytes = tcp.payload();
            long right = readSequence + WIRE_WINDOW;
            if (pendingFin >= 0) right = Math.min(right, pendingFin);
            int from = (int) Math.min(bytes.length, Math.max(0, receiveNext - sequence));
            int end = (int) Math.min(bytes.length, Math.max(0, right - sequence));
            for (int i = from; i < end; i++) {
                int index = (int) (sequence + i) & 65535;
                if (!present.get(index)) {
                    receiveRing[index] = bytes[i];
                    present.set(index);
                }
            }
            long finSequence = sequence + bytes.length;
            if ((tcp.flags() & FIN) != 0 && finSequence >= receiveNext
                    && finSequence < readSequence + WIRE_WINDOW && pendingFin < 0)
                pendingFin = finSequence;
            long before = receiveNext;
            while (receiveNext < readSequence + WIRE_WINDOW
                    && (pendingFin < 0 || receiveNext < pendingFin)
                    && present.get((int) receiveNext & 65535)) receiveNext++;
            if (pendingFin == receiveNext) {
                receivedFin = true;
                peerClosedFirst = !finSent;
                receiveNext++;
                if (closeAt == 0) closeAt = clock.getAsLong();
            }
            if (receiveNext != before) lastProgress = clock.getAsLong();
            ack();
            maybeTimeWait();
            notifyAll();
        }

        private void acknowledge(long acknowledgement, boolean duplicateCandidate) {
            if (acknowledgement == sendUna) {
                if (duplicateCandidate && !flights.isEmpty() && ++duplicateAcks == 3) {
                    slowStartThreshold = Math.max(2 * peerMss, (int) (sendNext - sendUna) / 2);
                    congestionWindow = slowStartThreshold;
                    retransmit(flights.peekFirst(), false);
                }
                return;
            }
            long now = clock.getAsLong();
            int acknowledgedBytes = 0;
            while (!flights.isEmpty() && acknowledgement > flights.peekFirst().sequence) {
                Flight first = flights.peekFirst();
                if (acknowledgement >= first.end()) {
                    flights.removeFirst();
                    acknowledgedBytes += first.data.length;
                    if ((first.flags & FIN) != 0) finAcked = true;
                    if (first.retries == 0) updateRtt(Math.max(1, now - first.firstSent));
                } else {
                    int consumed = (int) (acknowledgement - first.sequence);
                    acknowledgedBytes += consumed;
                    first.data = Arrays.copyOfRange(first.data, consumed, first.data.length);
                    first.sequence = acknowledgement;
                    break;
                }
            }
            bufferedSend -= acknowledgedBytes;
            sendUna = acknowledgement;
            duplicateAcks = 0;
            lastProgress = now;
            if (acknowledgedBytes > 0) {
                int increment = congestionWindow < slowStartThreshold ? Math.min(peerMss, acknowledgedBytes)
                        : Math.max(1, peerMss * acknowledgedBytes / Math.max(1, congestionWindow));
                congestionWindow = Math.min(WIRE_WINDOW, congestionWindow + increment);
            }
            maybeTimeWait();
            notifyAll();
        }

        private void updateRtt(long sample) {
            if (smoothedRtt == 0) {
                smoothedRtt = sample;
                rttVariance = sample / 2.0;
            } else {
                rttVariance = .75 * rttVariance + .25 * Math.abs(smoothedRtt - sample);
                smoothedRtt = .875 * smoothedRtt + .125 * sample;
            }
            rto = (long) Math.max(200_000_000L, Math.min(8 * SECOND, smoothedRtt + 4 * rttVariance));
        }

        private void flushPending() {
            if (!established || disposed || timeWaitUntil != 0) return;
            long capacity = Math.min(peerWindow, congestionWindow) - (sendNext - sendUna);
            while (capacity > 0 && !unsent.isEmpty()) {
                ByteBuffer buffer = unsent.peekFirst();
                int size = (int) Math.min(Math.min(peerMss, buffer.remaining()), capacity);
                byte[] bytes = new byte[size];
                buffer.get(bytes);
                if (!buffer.hasRemaining()) unsent.removeFirst();
                unsentBytes -= size;
                track(ACK | PSH, bytes);
                capacity -= size;
            }
            if (outputClosed && unsent.isEmpty() && !finSent && capacity > 0) {
                finSent = true;
                track(FIN | ACK, EMPTY);
            }
        }

        private void track(int flags, byte[] data) {
            Flight flight = new Flight(sendNext, flags, data, clock.getAsLong());
            flights.add(flight);
            sendNext = flight.end();
            send(flight);
        }

        private void send(Flight flight) {
            emit(flight.sequence, receiveNext, flight.flags,
                    (flight.flags & SYN) != 0 ? MSS_OPTION : EMPTY, flight.data);
        }

        private void emit(long sequence, long acknowledgement, int flags, byte[] options, byte[] data) {
            TcpSegment tcp = new TcpSegment(key.localPort, key.peerPort, wire(sequence), wire(acknowledgement),
                    flags, receiveWindow(), options, data);
            byte[] packet = new Ipv4Packet(localIp, key.peerIp, 64, tcp.encode(localIp, key.peerIp)).encode();
            enqueue(destination, new EthernetFrame(peerMac, localMac, EthernetFrame.IPV4, packet).encode());
        }

        private void ack() { emit(sendNext, receiveNext, ACK, EMPTY, EMPTY); }

        private void retransmit(Flight flight, boolean timeout) {
            if (flight.retries >= 8) {
                fail(new SocketTimeoutException("OpenRad TCP retransmission limit reached"), true);
                return;
            }
            flight.retries++;
            flight.sentAt = clock.getAsLong();
            if (timeout) {
                slowStartThreshold = Math.max(2 * peerMss, (int) (sendNext - sendUna) / 2);
                congestionWindow = peerMss;
                rto = Math.min(8 * SECOND, rto * 2);
            }
            send(flight);
        }

        private void tick(long now) {
            if (disposed) return;
            if (timeWaitUntil != 0) {
                if (now >= timeWaitUntil) dispose();
                return;
            }
            if (!established && now - createdAt >= handshakeTimeout) {
                fail(new SocketTimeoutException("OpenRad TCP handshake timed out"), true);
                return;
            }
            if (established && (now - lastProgress >= 3 * 60 * SECOND
                    || (closeAt != 0 && now - closeAt >= 30 * SECOND))) {
                fail(new SocketTimeoutException("OpenRad TCP idle/close timeout"), true);
                return;
            }
            if (!flights.isEmpty() && (!established || peerWindow != 0)
                    && now - flights.peekFirst().sentAt >= rto)
                retransmit(flights.peekFirst(), true);
            if (disposed) return;
            if (established && peerWindow == 0
                    && (unsentBytes > 0 || !flights.isEmpty() || (outputClosed && !finSent))
                    && now - lastProbe >= SECOND) {
                lastProbe = now;
                Flight first = flights.peekFirst();
                boolean hasData = first != null && first.data.length > 0;
                emit(hasData ? first.sequence : sendUna - 1, receiveNext, ACK, EMPTY,
                        new byte[] {hasData ? first.data[0] : 0});
            }
            flushPending();
        }

        private void maybeTimeWait() {
            if (!receivedFin || !finAcked || timeWaitUntil != 0) return;
            // Passive close (LAST-ACK acknowledged) ends the connection; only the active closer
            // keeps the tuple to answer a retransmitted FIN.
            if (peerClosedFirst) dispose();
            else timeWaitUntil = clock.getAsLong() + 30 * SECOND;
        }

        private void fail(IOException error, boolean reset) {
            if (disposed) return;
            if (reset) emit(sendNext, receiveNext, established ? RST | ACK : RST, EMPTY, EMPTY);
            failure = error;
            dispose();
        }

        private void dispose() {
            if (disposed) return;
            disposed = true;
            connections.remove(key, this);
            flights.clear();
            unsent.clear();
            bufferedSend = unsentBytes = 0;
            // Preserve already-received bytes for a reader after an orderly FIN exchange.
            if (failure != null || applicationClosed) present.clear();
            notifyAll();
        }

        private final class TcpInput extends InputStream {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] target, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, target.length);
                if (length == 0) return 0;
                synchronized (Connection.this) {
                    while (true) {
                        if (failure != null) throw failure;
                        if (applicationClosed) throw new SocketException("OpenRad TCP input is closed");
                        long readableEnd = receivedFin ? pendingFin : receiveNext;
                        int count = (int) Math.min(length, Math.max(0, readableEnd - readSequence));
                        if (count > 0) {
                            int previousWindow = receiveWindow();
                            for (int i = 0; i < count; i++) {
                                int index = (int) readSequence++ & 65535;
                                target[offset + i] = receiveRing[index];
                                present.clear(index);
                            }
                            if (!disposed && !receivedFin && receiveWindow() > previousWindow) ack();
                            return count;
                        }
                        if (receivedFin && readSequence >= pendingFin) return -1;
                        if (disposed || closed) throw new SocketException("OpenRad TCP input is closed");
                        await(SECOND);
                    }
                }
            }
            @Override public int available() throws IOException {
                synchronized (Connection.this) {
                    if (failure != null) throw failure;
                    return (int) Math.max(0, (receivedFin ? pendingFin : receiveNext) - readSequence);
                }
            }
            @Override public void close() { Connection.this.close(); }
        }

        private final class TcpOutput extends OutputStream {
            @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
            @Override public void write(byte[] source, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, source.length);
                synchronized (Connection.this) {
                    checkWritable();
                    while (length > 0) {
                        while (bufferedSend == CAPACITY) {
                            await(SECOND);
                            checkWritable();
                        }
                        checkWritable();
                        int size = Math.min(length, CAPACITY - bufferedSend);
                        unsent.add(ByteBuffer.wrap(Arrays.copyOfRange(source, offset, offset + size)));
                        bufferedSend += size;
                        unsentBytes += size;
                        offset += size;
                        length -= size;
                        flushPending();
                    }
                }
            }
            private void checkWritable() throws IOException {
                checkOpen();
                if (outputClosed) throw new SocketException("OpenRad TCP output is closed");
            }
            /** Like a socket: written bytes are already queued for sending; never waits for ACKs. */
            @Override public void flush() throws IOException {
                synchronized (Connection.this) {
                    checkWritable();
                    flushPending();
                }
            }
            @Override public void close() throws IOException { shutdownOutput(); }
        }
    }
}
