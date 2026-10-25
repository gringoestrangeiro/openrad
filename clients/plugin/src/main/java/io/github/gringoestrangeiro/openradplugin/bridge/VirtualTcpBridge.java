package io.github.gringoestrangeiro.openradplugin.bridge;

import static io.github.gringoestrangeiro.openradplugin.link.TcpSegment.*;
import static io.github.gringoestrangeiro.openradplugin.virtual.TcpSequence.*;

import io.github.gringoestrangeiro.openradplugin.link.*;
import io.github.gringoestrangeiro.openradplugin.security.InboundFirewall;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * A deliberately scoped, passive IPv4/TCP endpoint. It never opens a TAP adapter or routes IP.
 * Every admitted connection opens exactly one nonblocking channel to the configured loopback
 * service. The selector thread owns all TCP state. Callbacks must enqueue without blocking.
 */
public final class VirtualTcpBridge implements AutoCloseable {
    @FunctionalInterface
    public interface FrameSender {
        void send(long rid, byte[] ethernetFrame);
    }

    public enum TcpState {
        LISTEN,
        CONNECTING_BACKEND,
        SYN_RECEIVED,
        ESTABLISHED,
        FIN_WAIT_1,
        FIN_WAIT_2,
        CLOSE_WAIT,
        CLOSING,
        LAST_ACK,
        TIME_WAIT,
        CLOSED
    }

    public record Limits(
            int maxConnections,
            int maxConnectionsPerPeer,
            int inboundQueueFrames,
            int bufferBytesPerConnection,
            long globalBufferedBytes,
            Duration connectTimeout,
            Duration handshakeTimeout,
            Duration idleTimeout,
            Duration halfCloseTimeout,
            Duration timeWait,
            int maxRetransmissions,
            int maxPendingConnections) {
        /** Compatibility overload; total flow capacity was historically the pending-SYN bound. */
        public Limits(
                int maxConnections,
                int maxConnectionsPerPeer,
                int inboundQueueFrames,
                int bufferBytesPerConnection,
                long globalBufferedBytes,
                Duration connectTimeout,
                Duration handshakeTimeout,
                Duration idleTimeout,
                Duration halfCloseTimeout,
                Duration timeWait,
                int maxRetransmissions) {
            this(
                    maxConnections,
                    maxConnectionsPerPeer,
                    inboundQueueFrames,
                    bufferBytesPerConnection,
                    globalBufferedBytes,
                    connectTimeout,
                    handshakeTimeout,
                    idleTimeout,
                    halfCloseTimeout,
                    timeWait,
                    maxRetransmissions,
                    maxConnections);
        }

        public Limits {
            if (maxConnections < 1
                    || maxConnections > 65536
                    || maxConnectionsPerPeer < 1
                    || maxConnectionsPerPeer > maxConnections
                    || inboundQueueFrames < 1
                    || inboundQueueFrames > 65536
                    || bufferBytesPerConnection < 65536
                    || bufferBytesPerConnection > 16 * 1024 * 1024
                    || globalBufferedBytes < 65536
                    || maxRetransmissions < 1
                    || maxRetransmissions > 20
                    || maxPendingConnections < 1
                    || maxPendingConnections > 65536) {
                throw new IllegalArgumentException("invalid bridge limits");
            }
            for (Duration duration :
                    List.of(
                            connectTimeout,
                            handshakeTimeout,
                            idleTimeout,
                            halfCloseTimeout,
                            timeWait)) {
                if (duration.isNegative()
                        || duration.isZero()
                        || duration.compareTo(Duration.ofDays(1)) > 0)
                    throw new IllegalArgumentException("invalid bridge timeout");
            }
        }

        public static Limits defaults() {
            return new Limits(
                    128,
                    8,
                    2048,
                    256 * 1024,
                    64L * 1024 * 1024,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(15),
                    Duration.ofMinutes(3),
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(30),
                    8,
                    60);
        }
    }

    public record Snapshot(
            int connections,
            int pendingFrames,
            long bufferedBytes,
            long acceptedFrames,
            long rejectedFrames,
            long resets,
            long retransmissions,
            long backendFailures) {}

    private record Input(InboundFirewall.PeerGrant grant, byte[] frame, long epoch) {}

    private record Key(long rid, int port) {}

    private static final int RECEIVE_RING = 65536;
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] MSS_OPTION = {2, 4, 5, (byte) 0xb4};
    private final int localIp, exposedPort;
    private final byte[] localMac;
    private final InetSocketAddress backend;
    private final Limits limits;
    private final FrameSender sender;
    private final Selector selector;
    private final Thread worker;
    private final ArrayBlockingQueue<Input> incoming;
    private final Object admission = new Object();
    private final ConcurrentHashMap<Long, Long> epochs = new ConcurrentHashMap<>();
    private final Set<Long> revocations = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<Key, Connection> connections = new HashMap<>();
    private final Map<Long, Integer> perPeer = new HashMap<>();
    private final Map<Long, Integer> activePerPeer = new HashMap<>();
    private int activeConnections;
    private volatile Map<Key, TcpState> publishedStates = Map.of();
    private final SecureRandom random = new SecureRandom();
    private final LongAdder accepted = new LongAdder(), rejected = new LongAdder();
    private volatile int connectionCount;
    private volatile long bufferedBytes, resets, retransmissions, backendFailures;

    public VirtualTcpBridge(
            int localIp,
            byte[] localMac,
            int exposedPort,
            InetSocketAddress backend,
            FrameSender sender)
            throws IOException {
        this(localIp, localMac, exposedPort, backend, Limits.defaults(), sender);
    }

    public VirtualTcpBridge(
            int localIp,
            byte[] localMac,
            int exposedPort,
            InetSocketAddress backend,
            Limits limits,
            FrameSender sender)
            throws IOException {
        if (localMac.length != 6
                || (localMac[0] & 1) != 0
                || exposedPort < 1
                || exposedPort > 65535)
            throw new IllegalArgumentException("invalid virtual endpoint");
        if (backend.isUnresolved()
                || !backend.getAddress().isLoopbackAddress()
                || backend.getPort() < 1)
            throw new IllegalArgumentException("backend must be a resolved loopback service");
        this.localIp = localIp;
        this.localMac = localMac.clone();
        this.exposedPort = exposedPort;
        this.backend = backend;
        this.limits = Objects.requireNonNull(limits);
        this.sender = Objects.requireNonNull(sender);
        incoming = new ArrayBlockingQueue<>(limits.inboundQueueFrames());
        selector = Selector.open();
        worker = Thread.ofPlatform().daemon().name("openrad-virtual-tcp").unstarted(this::run);
        worker.start();
    }

    /** Returns false for queue pressure, shutdown, invalid frame length, or ineligible peers. */
    public boolean accept(InboundFirewall.PeerGrant grant, byte[] frame) {
        Objects.requireNonNull(grant);
        Objects.requireNonNull(frame);
        synchronized (admission) {
            if (!running.get()
                    || !grant.eligible()
                    || frame.length < 14
                    || frame.length > EthernetFrame.MAX_FRAME_BYTES) {
                rejected.increment();
                return false;
            }
            // Epochs invalidate frames queued before a membership/transport revocation.
            long epoch = epochs.getOrDefault(grant.rid(), 0L);
            boolean queued = incoming.offer(new Input(grant, frame.clone(), epoch));
            if (queued) selector.wakeup();
            else rejected.increment();
            return queued;
        }
    }

    /** Call whenever authenticated transport or allowed membership is removed. */
    public void revokePeer(long rid) {
        synchronized (admission) {
            if (!running.get()) return;
            epochs.merge(rid, 1L, Long::sum);
            revocations.add(rid);
            selector.wakeup();
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                connectionCount,
                incoming.size(),
                bufferedBytes,
                accepted.sum(),
                rejected.sum(),
                resets,
                retransmissions,
                backendFailures);
    }

    /** Immutable diagnostic view published by the selector; an unused tuple is in LISTEN. */
    public TcpState state(long rid, int peerPort) {
        return publishedStates.getOrDefault(
                new Key(rid, peerPort), running.get() ? TcpState.LISTEN : TcpState.CLOSED);
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) return;
        selector.wakeup();
        if (Thread.currentThread() != worker) {
            try {
                worker.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        try {
            while (running.get()) {
                revokePending();
                for (int i = 0; i < 256; i++) {
                    Input input = incoming.poll();
                    if (input == null) break;
                    if (input.epoch() == epochs.getOrDefault(input.grant().rid(), 0L))
                        process(input);
                    else rejected.increment();
                }
                synchronized (admission) {
                    // Once every queued input and revocation was consumed, epoch tombstones
                    // can be discarded. Otherwise a long-lived attachment would retain every RID.
                    if (incoming.isEmpty() && revocations.isEmpty())
                        epochs.keySet().removeIf(rid -> !perPeer.containsKey(rid));
                }
                selector.select(incoming.isEmpty() ? 10 : 1);
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey selected = keys.next();
                    keys.remove();
                    Connection connection = (Connection) selected.attachment();
                    if (!selected.isValid() || connection.closed) continue;
                    try {
                        if (selected.isConnectable()) connection.connected();
                        if (selected.isValid() && selected.isWritable()) connection.writeBackend();
                        if (selected.isValid() && selected.isReadable()) connection.readBackend();
                    } catch (IOException | CancelledKeyException e) {
                        connection.failBackend();
                    }
                }
                long now = System.nanoTime();
                for (Connection connection : List.copyOf(connections.values())) {
                    connection.tick(now);
                    if (!connection.closed) connection.interests();
                }
                Map<Key, TcpState> states = new HashMap<>();
                connections.forEach((key, connection) -> states.put(key, connection.state()));
                publishedStates = Map.copyOf(states);
            }
        } catch (IOException e) {
            backendFailures++;
        } finally {
            running.set(false);
            for (Connection connection : List.copyOf(connections.values())) connection.dispose();
            incoming.clear();
            epochs.clear();
            revocations.clear();
            publishedStates = Map.of();
            try {
                selector.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void revokePending() {
        for (Long rid : revocations) {
            if (!revocations.remove(rid)) continue;
            long epoch = epochs.getOrDefault(rid, 0L);
            for (Connection c : List.copyOf(connections.values()))
                if (c.key.rid() == rid && c.epoch != epoch) c.dispose();
        }
    }

    private void process(Input input) {
        var result =
                InboundFirewall.inspect(
                        input.frame(), input.grant(), localIp, localMac, exposedPort);
        if (result instanceof InboundFirewall.Rejected) {
            rejected.increment();
            return;
        }
        accepted.increment();
        if (result instanceof InboundFirewall.ArpResponse arp) {
            emit(input.grant().rid(), arp.frame());
            return;
        }
        TcpSegment tcp = ((InboundFirewall.AdmittedTcp) result).segment();
        Key key = new Key(input.grant().rid(), tcp.sourcePort());
        Connection connection = connections.get(key);
        if (connection != null && connection.epoch != input.epoch()) {
            connection.dispose();
            connection = null;
        }
        if (connection != null) {
            if (connection.peerIp != input.grant().sourceIp()
                    || !Arrays.equals(connection.peerMac, input.grant().sourceMac())) {
                rejected.increment();
                return;
            }
            connection.receive(tcp);
            return;
        }
        if ((tcp.flags() & RST) != 0) return;
        if ((tcp.flags() & ~(ECE | CWR)) != SYN) {
            reset(input.grant(), tcp);
            return;
        }
        int mss;
        try {
            mss = peerMss(tcp.options());
        } catch (IllegalArgumentException e) {
            rejected.increment();
            return;
        }
        if (activeConnections >= limits.maxConnections()
                || connections.values().stream().filter(c -> !c.established).count()
                        >= limits.maxPendingConnections()
                || activePerPeer.getOrDefault(key.rid(), 0) >= limits.maxConnectionsPerPeer()
                || bufferedBytes + RECEIVE_RING > limits.globalBufferedBytes()) {
            rejected.increment();
            reset(input.grant(), tcp);
            return;
        }
        try {
            Connection created = new Connection(key, input.grant(), tcp, mss, input.epoch());
            evictOldestTimeWait();
            connections.put(key, created);
            perPeer.merge(key.rid(), 1, Integer::sum);
            activePerPeer.merge(key.rid(), 1, Integer::sum);
            activeConnections++;
            connectionCount = connections.size();
            bufferedBytes += RECEIVE_RING;
            created.start();
        } catch (IOException e) {
            backendFailures++;
            reset(input.grant(), tcp);
        }
    }

    // TIME_WAIT tombstones are retained for late FIN retransmissions but must not grow with churn.
    private void evictOldestTimeWait() {
        Connection oldest = null;
        int retained = 0;
        for (Connection connection : connections.values()) {
            if (connection.timeWaitUntil == 0) continue;
            retained++;
            if (oldest == null || connection.timeWaitUntil < oldest.timeWaitUntil)
                oldest = connection;
        }
        if (retained >= limits.maxConnections() && oldest != null) oldest.dispose();
    }

    private void reset(InboundFirewall.PeerGrant peer, TcpSegment segment) {
        long sequence = (segment.flags() & ACK) != 0 ? segment.acknowledgement() : 0;
        long ack =
                wire(
                        segment.sequence()
                                + segment.payload().length
                                + ((segment.flags() & SYN) != 0 ? 1 : 0)
                                + ((segment.flags() & FIN) != 0 ? 1 : 0));
        int flags = (segment.flags() & ACK) != 0 ? RST : RST | ACK;
        emitTcp(
                peer.rid(),
                peer.sourceIp(),
                peer.sourceMac(),
                segment.sourcePort(),
                sequence,
                ack,
                flags,
                0,
                EMPTY,
                EMPTY);
        resets++;
    }

    private void emitTcp(
            long rid,
            int peerIp,
            byte[] peerMac,
            int peerPort,
            long sequence,
            long ack,
            int flags,
            int window,
            byte[] options,
            byte[] payload) {
        TcpSegment tcp =
                new TcpSegment(
                        exposedPort,
                        peerPort,
                        wire(sequence),
                        wire(ack),
                        flags,
                        window,
                        options,
                        payload);
        byte[] ip = new Ipv4Packet(localIp, peerIp, 64, tcp.encode(localIp, peerIp)).encode();
        emit(rid, new EthernetFrame(peerMac, localMac, EthernetFrame.IPV4, ip).encode());
    }

    private void emit(long rid, byte[] frame) {
        try {
            sender.send(rid, frame);
        } catch (RuntimeException ignored) {
            /* A dropped transport write is recovered by the TCP timer. */
        }
    }

    private static int peerMss(byte[] options) {
        int mss = 536; // RFC 9293 default when peer did not advertise MSS.
        for (int i = 0; i < options.length; ) {
            int kind = options[i] & 255;
            if (kind == 0) break;
            if (kind == 1) {
                i++;
                continue;
            }
            if (i + 1 >= options.length) throw new IllegalArgumentException("truncated TCP option");
            int len = options[i + 1] & 255;
            if (len < 2 || i + len > options.length)
                throw new IllegalArgumentException("invalid TCP option");
            if (kind == 2) {
                if (len != 4) throw new IllegalArgumentException("invalid MSS");
                int advertised = Checksum.u16(options, i + 2);
                if (advertised < 64)
                    throw new IllegalArgumentException("MSS below endpoint minimum");
                mss = Math.min(1460, advertised);
            }
            i += len;
        }
        return mss;
    }

    private static final class Flight {
        long sequence, sentAt;
        final long firstSent;
        int flags, retries;
        byte[] data;

        Flight(long sequence, int flags, byte[] data, long now) {
            this.sequence = sequence;
            this.flags = flags;
            this.data = data;
            sentAt = firstSent = now;
        }

        long end() {
            return sequence + data.length + ((flags & (SYN | FIN)) != 0 ? 1 : 0);
        }
    }

    private final class Connection {
        final Key key;
        final long epoch;
        final int peerIp, mss;
        final byte[] peerMac;
        final long peerSyn, ourSyn, createdAt = System.nanoTime();
        final byte[] receiveRing = new byte[RECEIVE_RING];
        final BitSet present = new BitSet(RECEIVE_RING);
        final ArrayDeque<ByteBuffer> toBackend = new ArrayDeque<>(), toPeer = new ArrayDeque<>();
        final ArrayDeque<Flight> flights = new ArrayDeque<>();
        SocketChannel channel;
        SelectionKey selection;
        long receiveNext, sendUna, sendNext, windowSeq, windowAck, lastProgress = createdAt;
        long pendingFin = -1,
                halfCloseAt,
                timeWaitUntil,
                lastProbe,
                retransmitNanos = 1_000_000_000L;
        double smoothedRtt, rttVariance;
        int peerWindow, backendQueued, reordered, outboundBuffered, unsentBytes, duplicateAcks;
        int congestionWindow, slowStartThreshold = 65535;
        boolean established,
                connecting = true,
                receivedFin,
                backendOutputClosed,
                backendEof,
                finSent,
                finAcked,
                closed;
        boolean peerClosedFirst;
        boolean quotaReleased;

        Connection(Key key, InboundFirewall.PeerGrant grant, TcpSegment syn, int mss, long epoch) {
            this.key = key;
            this.epoch = epoch;
            peerIp = grant.sourceIp();
            peerMac = grant.sourceMac();
            this.mss = mss;
            peerSyn = syn.sequence();
            receiveNext = peerSyn + 1;
            ourSyn = Integer.toUnsignedLong(random.nextInt());
            sendUna = sendNext = ourSyn;
            windowSeq = peerSyn;
            windowAck = ourSyn;
            peerWindow = syn.window();
            congestionWindow = Math.min(10 * mss, 14600);
        }

        TcpState state() {
            if (closed) return TcpState.CLOSED;
            if (timeWaitUntil != 0) return TcpState.TIME_WAIT;
            if (connecting) return TcpState.CONNECTING_BACKEND;
            if (!established) return TcpState.SYN_RECEIVED;
            if (receivedFin) {
                if (!finSent) return TcpState.CLOSE_WAIT;
                return peerClosedFirst ? TcpState.LAST_ACK : TcpState.CLOSING;
            }
            if (finSent) return finAcked ? TcpState.FIN_WAIT_2 : TcpState.FIN_WAIT_1;
            return TcpState.ESTABLISHED;
        }

        void start() throws IOException {
            try {
                channel = SocketChannel.open();
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
                channel.setOption(StandardSocketOptions.SO_SNDBUF, 32768);
                channel.setOption(StandardSocketOptions.SO_RCVBUF, 32768);
                boolean complete = channel.connect(backend);
                selection =
                        channel.register(selector, complete ? 0 : SelectionKey.OP_CONNECT, this);
                if (complete) connected();
            } catch (IOException e) {
                dispose();
                throw e;
            }
        }

        void connected() throws IOException {
            if (!channel.finishConnect()) return;
            connecting = false;
            sendTracked(SYN | ACK, EMPTY);
            interests();
        }

        int receiveWindow() {
            // Account for both directions globally. No scaling is negotiated, so the wire window is
            // <=65535.
            long globalFree = Math.max(0, limits.globalBufferedBytes() - bufferedBytes);
            return (int)
                    Math.min(
                            65535,
                            Math.min(
                                    globalFree,
                                    // Reordered bytes already occupy sequence positions inside this
                                    // window.
                                    // Subtracting them again would unnecessarily move the
                                    // advertised right edge back.
                                    Math.max(
                                            0, limits.bufferBytesPerConnection() - backendQueued)));
        }

        void receive(TcpSegment tcp) {
            if (closed) return;
            long seq = unwrap(tcp.sequence(), receiveNext);
            if (timeWaitUntil != 0) {
                if ((tcp.flags() & FIN) != 0 && seq + tcp.payload().length == receiveNext - 1) {
                    ack();
                    timeWaitUntil = System.nanoTime() + limits.timeWait().toNanos();
                }
                return;
            }
            if ((tcp.flags() & RST) != 0) {
                if (seq == receiveNext) dispose();
                else if (acceptable(seq, 0)) ack();
                return;
            }
            if ((tcp.flags() & SYN) != 0) {
                if (!established && seq == peerSyn && !flights.isEmpty())
                    retransmit(flights.peekFirst(), false);
                else if (established) ack();
                return;
            }
            if (connecting || (tcp.flags() & ACK) == 0) return;
            int length = tcp.payload().length + ((tcp.flags() & FIN) != 0 ? 1 : 0);
            if (!acceptable(seq, length)) {
                ack();
                return;
            }
            long acknowledgement = unwrap(tcp.acknowledgement(), sendUna);
            if (acknowledgement > sendNext) {
                ack();
                return;
            }
            if (!established) {
                if (acknowledgement != ourSyn + 1 || seq != receiveNext) return;
                established = true;
                lastProgress = System.nanoTime();
            }
            if (acknowledgement >= sendUna) {
                boolean windowChanged = false;
                if (seq > windowSeq || (seq == windowSeq && acknowledgement >= windowAck)) {
                    windowChanged = peerWindow != tcp.window();
                    peerWindow = tcp.window();
                    windowSeq = seq;
                    windowAck = acknowledgement;
                }
                acknowledge(acknowledgement, length == 0 && !windowChanged);
            }
            if (closed) return;
            if (length > 0) receiveData(tcp, seq);
            flushPeer();
            try {
                writeBackend();
            } catch (IOException e) {
                failBackend();
            }
            interests();
        }

        boolean acceptable(long seq, int length) {
            int window = receiveWindow();
            if (length == 0)
                return window == 0
                        ? seq == receiveNext
                        : seq >= receiveNext && seq < receiveNext + window;
            return window > 0 && seq < receiveNext + window && seq + length > receiveNext;
        }

        void receiveData(TcpSegment tcp, long seq) {
            if (receivedFin) {
                ack();
                return;
            }
            byte[] bytes = tcp.payload();
            int window = receiveWindow();
            long right = receiveNext + window;
            if (pendingFin >= 0) right = Math.min(right, pendingFin);
            int from = (int) Math.max(0, receiveNext - seq);
            int end = (int) Math.min(bytes.length, Math.max(0, right - seq));
            for (int i = from; i < end; i++) {
                int index = (int) (seq + i) & 65535;
                if (!present.get(index)) {
                    receiveRing[index] = bytes[i];
                    present.set(index);
                    reordered++;
                    bufferedBytes++;
                }
            }
            long finSequence = seq + bytes.length;
            if ((tcp.flags() & FIN) != 0
                    && finSequence >= receiveNext
                    && finSequence < receiveNext + window) {
                if (pendingFin < 0) pendingFin = finSequence;
            }
            // Drain contiguous bytes once, without retaining overlapping retransmissions.
            int contiguous = 0;
            while (contiguous < RECEIVE_RING
                    && present.get((int) (receiveNext + contiguous) & 65535)
                    && (pendingFin < 0 || receiveNext + contiguous < pendingFin)) contiguous++;
            if (contiguous > 0) {
                byte[] data = new byte[contiguous];
                for (int i = 0; i < contiguous; i++) {
                    int index = (int) (receiveNext + i) & 65535;
                    data[i] = receiveRing[index];
                    present.clear(index);
                }
                toBackend.add(ByteBuffer.wrap(data));
                backendQueued += contiguous;
                reordered -= contiguous;
                receiveNext += contiguous;
                lastProgress = System.nanoTime();
            }
            if (pendingFin == receiveNext) {
                peerClosedFirst = !finSent;
                receiveNext++;
                receivedFin = true;
                halfCloseAt = System.nanoTime();
                // Discard speculative bytes beyond FIN, which can never enter the byte stream.
                bufferedBytes -= reordered;
                reordered = 0;
                present.clear();
            }
            ack();
        }

        void acknowledge(long acknowledgement, boolean duplicateCandidate) {
            if (acknowledgement == sendUna) {
                if (duplicateCandidate && !flights.isEmpty() && ++duplicateAcks == 3) {
                    slowStartThreshold = Math.max(2 * mss, (int) (sendNext - sendUna) / 2);
                    congestionWindow = slowStartThreshold;
                    retransmit(flights.peekFirst(), false);
                }
                return;
            }
            long now = System.nanoTime();
            int dataAcknowledged = 0;
            while (!flights.isEmpty() && acknowledgement > flights.peekFirst().sequence) {
                Flight flight = flights.peekFirst();
                if (acknowledgement >= flight.end()) {
                    flights.removeFirst();
                    dataAcknowledged += flight.data.length;
                    if ((flight.flags & FIN) != 0) finAcked = true;
                    if (flight.retries == 0) updateRtt(now - flight.firstSent);
                } else {
                    int consumed = (int) (acknowledgement - flight.sequence);
                    dataAcknowledged += consumed;
                    flight.data = Arrays.copyOfRange(flight.data, consumed, flight.data.length);
                    flight.sequence = acknowledgement;
                    break;
                }
            }
            outboundBuffered -= dataAcknowledged;
            bufferedBytes -= dataAcknowledged;
            sendUna = acknowledgement;
            duplicateAcks = 0;
            lastProgress = now;
            if (dataAcknowledged > 0) {
                // RFC 6298 5.3: new data acknowledged restarts the retransmission timer, so the
                // next lost segment of a burst is not treated as an RTO timeout (which would
                // collapse the window and double the RTO for every segment in recovery).
                if (!flights.isEmpty()) flights.peekFirst().sentAt = now;
                int increase =
                        congestionWindow < slowStartThreshold
                                ? Math.min(dataAcknowledged, mss)
                                : Math.max(
                                        1, mss * dataAcknowledged / Math.max(1, congestionWindow));
                congestionWindow = Math.min(65535, congestionWindow + increase);
            }
            maybeTimeWait();
        }

        void updateRtt(long sample) {
            if (smoothedRtt == 0) {
                smoothedRtt = sample;
                rttVariance = sample / 2.0;
            } else {
                rttVariance = 0.75 * rttVariance + 0.25 * Math.abs(smoothedRtt - sample);
                smoothedRtt = 0.875 * smoothedRtt + 0.125 * sample;
            }
            retransmitNanos =
                    (long)
                            Math.max(
                                    200_000_000L,
                                    Math.min(8_000_000_000L, smoothedRtt + 4 * rttVariance));
        }

        void writeBackend() throws IOException {
            if (connecting || closed || timeWaitUntil != 0) return;
            int oldWindow = receiveWindow();
            int budget = 64 * 1024;
            while (!toBackend.isEmpty() && budget > 0) {
                ByteBuffer data = toBackend.peekFirst();
                int wrote = channel.write(data);
                if (wrote == 0) break;
                backendQueued -= wrote;
                bufferedBytes -= wrote;
                budget -= wrote;
                lastProgress = System.nanoTime();
                if (!data.hasRemaining()) toBackend.removeFirst();
            }
            if (receivedFin && toBackend.isEmpty() && !backendOutputClosed) {
                channel.shutdownOutput();
                backendOutputClosed = true;
            }
            int newWindow = receiveWindow();
            if (established
                    && newWindow > oldWindow
                    && (oldWindow == 0 || newWindow - oldWindow >= 2 * mss)) ack();
            maybeTimeWait();
        }

        int readCapacity() {
            return (int)
                    Math.max(
                            0,
                            Math.min(
                                    16 * 1024L,
                                    Math.min(
                                            limits.bufferBytesPerConnection() - outboundBuffered,
                                            limits.globalBufferedBytes() - bufferedBytes)));
        }

        void readBackend() throws IOException {
            if (!established || backendEof || closed) return;
            int free = readCapacity();
            if (free == 0) return;
            ByteBuffer bytes = ByteBuffer.allocate(free);
            int read = channel.read(bytes);
            if (read < 0) {
                backendEof = true;
                if (halfCloseAt == 0) halfCloseAt = System.nanoTime();
            } else if (read > 0) {
                bytes.flip();
                toPeer.add(bytes);
                unsentBytes += read;
                outboundBuffered += read;
                bufferedBytes += read;
                lastProgress = System.nanoTime();
            }
            flushPeer();
        }

        void flushPeer() {
            if (!established || closed || timeWaitUntil != 0) return;
            long capacity = Math.min(peerWindow, congestionWindow) - (sendNext - sendUna);
            while (!toPeer.isEmpty() && capacity > 0) {
                ByteBuffer next = toPeer.peekFirst();
                int size = (int) Math.min(Math.min(mss, next.remaining()), capacity);
                byte[] data = new byte[size];
                next.get(data);
                unsentBytes -= size;
                if (!next.hasRemaining()) toPeer.removeFirst();
                sendTracked(ACK | PSH, data);
                capacity -= size;
            }
            if (backendEof && toPeer.isEmpty() && !finSent && capacity > 0) {
                finSent = true;
                sendTracked(FIN | ACK, EMPTY);
            }
            maybeTimeWait();
        }

        void sendTracked(int flags, byte[] data) {
            Flight flight = new Flight(sendNext, flags, data, System.nanoTime());
            flights.add(flight);
            sendNext = flight.end();
            send(flight);
        }

        void send(Flight flight) {
            emitTcp(
                    key.rid(),
                    peerIp,
                    peerMac,
                    key.port(),
                    flight.sequence,
                    receiveNext,
                    flight.flags,
                    receiveWindow(),
                    (flight.flags & SYN) != 0 ? MSS_OPTION : EMPTY,
                    flight.data);
        }

        void ack() {
            emitTcp(
                    key.rid(),
                    peerIp,
                    peerMac,
                    key.port(),
                    sendNext,
                    receiveNext,
                    ACK,
                    receiveWindow(),
                    EMPTY,
                    EMPTY);
        }

        void retransmit(Flight flight, boolean timeout) {
            if (flight.retries >= limits.maxRetransmissions()) {
                abort();
                return;
            }
            flight.retries++;
            flight.sentAt = System.nanoTime();
            retransmissions++;
            if (timeout) {
                slowStartThreshold = Math.max(2 * mss, (int) (sendNext - sendUna) / 2);
                congestionWindow = mss;
                retransmitNanos = Math.min(8_000_000_000L, retransmitNanos * 2);
            }
            send(flight);
        }

        void tick(long now) {
            if (closed) return;
            if (timeWaitUntil != 0) {
                if (now >= timeWaitUntil) dispose();
                return;
            }
            if (connecting && now - createdAt >= limits.connectTimeout().toNanos()) {
                failBackend();
                return;
            }
            if (!established && now - createdAt >= limits.handshakeTimeout().toNanos()) {
                abort();
                return;
            }
            if (established && now - lastProgress >= limits.idleTimeout().toNanos()) {
                abort();
                return;
            }
            if (halfCloseAt != 0) {
                long halfCloseDeadline = Math.max(halfCloseAt, lastProgress);
                if (now - halfCloseDeadline >= limits.halfCloseTimeout().toNanos()) {
                    abort();
                    return;
                }
            }
            if (!flights.isEmpty()
                    && (!established || peerWindow != 0)
                    && now - flights.peekFirst().sentAt >= retransmitNanos)
                retransmit(flights.peekFirst(), true);
            if (closed) return;
            if (established
                    && peerWindow == 0
                    && (unsentBytes > 0 || !flights.isEmpty() || (backendEof && !finSent))
                    && now - lastProbe >= 1_000_000_000L) {
                lastProbe = now;
                // Persist is independent of the retransmission retry budget. Probe an outstanding
                // byte if available, otherwise one duplicate byte before SND.UNA, without advancing
                // NXT.
                Flight first = flights.peekFirst();
                boolean hasData = first != null && first.data.length > 0;
                emitTcp(
                        key.rid(),
                        peerIp,
                        peerMac,
                        key.port(),
                        hasData ? first.sequence : sendUna - 1,
                        receiveNext,
                        ACK,
                        receiveWindow(),
                        EMPTY,
                        new byte[] {hasData ? first.data[0] : 0});
            }
            flushPeer();
        }

        void maybeTimeWait() {
            if (receivedFin && finAcked && backendQueued == 0 && timeWaitUntil == 0 && !closed) {
                evictOldestTimeWait();
                timeWaitUntil = System.nanoTime() + limits.timeWait().toNanos();
                releaseQuota();
                closeChannel();
            }
        }

        void interests() {
            if (selection == null || !selection.isValid() || closed || timeWaitUntil != 0) return;
            int operations = connecting ? SelectionKey.OP_CONNECT : 0;
            if (!connecting) {
                if (!toBackend.isEmpty()) operations |= SelectionKey.OP_WRITE;
                if (established && !backendEof && readCapacity() > 0)
                    operations |= SelectionKey.OP_READ;
            }
            selection.interestOps(operations);
        }

        void failBackend() {
            if (!closed) {
                backendFailures++;
                abort();
            }
        }

        void abort() {
            if (closed) return;
            emitTcp(
                    key.rid(),
                    peerIp,
                    peerMac,
                    key.port(),
                    sendNext,
                    receiveNext,
                    RST | ACK,
                    0,
                    EMPTY,
                    EMPTY);
            resets++;
            dispose();
        }

        void closeChannel() {
            if (selection != null) selection.cancel();
            if (channel != null)
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
        }

        void dispose() {
            if (closed) return;
            closed = true;
            releaseQuota();
            closeChannel();
            if (connections.remove(key, this)) {
                bufferedBytes -= RECEIVE_RING + backendQueued + reordered + outboundBuffered;
                perPeer.computeIfPresent(key.rid(), (rid, count) -> count == 1 ? null : count - 1);
                connectionCount = connections.size();
            }
            toPeer.clear();
            toBackend.clear();
            flights.clear();
            present.clear();
        }

        void releaseQuota() {
            if (quotaReleased) return;
            quotaReleased = true;
            activeConnections--;
            activePerPeer.computeIfPresent(
                    key.rid(), (rid, count) -> count == 1 ? null : count - 1);
        }
    }
}
