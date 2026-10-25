package io.github.gringoestrangeiro.openradplugin.transport;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.UnsupportedAddressTypeException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/** OpenRad's ENET subset: authenticated-candidate punching, CRC, ACKs, order and fragmentation. */
public final class ReliableUdp implements RecordTransport {
    private static final long RETRY = 400_000_000L, LIVENESS = 35_000_000_000L;
    private static final int[] SIZES = {0, 8, 40, 36, 8, 4, 6, 8, 24, 8, 12, 16};
    private final DatagramSocket socket;
    private final InetSocketAddress endpoint;
    private final long started = System.nanoTime();
    private long deadline, lastReceive = started;
    private byte[] cookie = new byte[4];
    private int remoteId = 0x7fff, mtu = 1400, outgoing, incoming;
    private boolean connected;
    private IOException terminalFailure;
    private Integer incomingNonce;
    private final Map<Integer, Pending> pending = new LinkedHashMap<>();
    private final Map<Integer, Ordered> ordered = new HashMap<>();
    private final Map<Integer, Fragment> fragments = new HashMap<>();
    private final ArrayDeque<byte[]> ready = new ArrayDeque<>();

    private static final class Pending {
        final byte[] command;
        final long queued = System.nanoTime();
        long last = queued;
        int tries = 1;

        Pending(byte[] command) {
            this.command = command;
        }
    }

    private record Ordered(int count, byte[] bytes) {}

    private static final class Fragment {
        final int total, count;
        final byte[] data;
        final int[] offsets, lengths;
        final BitSet received;

        Fragment(int total, int count) {
            this.total = total;
            this.count = count;
            data = new byte[total];
            offsets = new int[count];
            lengths = new int[count];
            received = new BitSet(count);
        }
    }

    private ReliableUdp(
            DatagramSocket socket, InetSocketAddress endpoint, long deadline, Integer nonce)
            throws IOException {
        this.socket = socket;
        this.endpoint = endpoint;
        this.deadline = deadline;
        incomingNonce = nonce;
        new SecureRandom().nextBytes(cookie);
        socket.connect(endpoint);
    }

    private static int u16(byte[] b, int at) {
        return Short.toUnsignedInt(ByteBuffer.wrap(b, at, 2).getShort());
    }

    private static long u32(byte[] b, int at) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(b, at, 4).getInt());
    }

    private static int wrap(int n) {
        return n & 65535;
    }

    /** Channel-backed socket permits an exact nonblocking send-buffer admission result. */
    public static DatagramSocket bind() throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        try {
            channel.bind(new InetSocketAddress(0));
            return channel.socket();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static void require(boolean ok, String message) throws IOException {
        if (!ok) throw new IOException(message);
    }

    public static byte[] punch(int nonce, int remotePort, int localPort, int kind) {
        return ByteBuffer.allocate(7)
                .putShort((short) nonce)
                .putShort((short) remotePort)
                .putShort((short) localPort)
                .put((byte) kind)
                .array();
    }

    public static boolean validPunch(byte[] b, int nonce) {
        return b.length == 7
                && u16(b, 0) == nonce
                && u16(b, 2) != 0
                && u16(b, 4) != 0
                && (b[6] == 'P' || b[6] == 'A' || b[6] == 'C');
    }

    public static ReliableUdp connect(
            DatagramSocket socket, List<InetSocketAddress> endpoints, int nonce, Duration timeout)
            throws IOException {
        return connect(socket, endpoints, nonce, timeout, () -> {});
    }

    public static ReliableUdp accept(
            DatagramSocket socket, List<InetSocketAddress> endpoints, int nonce, Duration timeout)
            throws IOException {
        return accept(socket, endpoints, nonce, timeout, () -> {});
    }

    public static ReliableUdp connect(
            DatagramSocket socket,
            List<InetSocketAddress> endpoints,
            int nonce,
            Duration timeout,
            Runnable progress)
            throws IOException {
        return rendezvous(socket, endpoints, nonce, timeout, false, progress);
    }

    public static ReliableUdp accept(
            DatagramSocket socket,
            List<InetSocketAddress> endpoints,
            int nonce,
            Duration timeout,
            Runnable progress)
            throws IOException {
        return rendezvous(socket, endpoints, nonce, timeout, true, progress);
    }

    private static ReliableUdp rendezvous(
            DatagramSocket socket,
            List<InetSocketAddress> candidates,
            int nonce,
            Duration timeout,
            boolean accept,
            Runnable progress)
            throws IOException {
        if (candidates.isEmpty()
                || candidates.size() > 32
                || nonce < 1
                || nonce > 65535
                || candidates.stream().anyMatch(a -> a.isUnresolved() || a.getPort() == 0))
            throw new IllegalArgumentException("UDP candidates/nonce");
        long began = System.nanoTime(), until = began + timeout.toNanos(), next = 0;
        List<InetSocketAddress> learned = new ArrayList<>();
        try {
            InetSocketAddress endpoint;
            for (; ; ) {
                long now = System.nanoTime();
                require(
                        now < until && !Thread.currentThread().isInterrupted(),
                        "UDP rendezvous deadline");
                if (now >= next) {
                    for (InetSocketAddress a : concat(candidates, learned)) {
                        // Best effort, as in Rust: one unroutable candidate (for example IPv6
                        // on an IPv4-only host) must not abort the other candidates.
                        try {
                            sendTo(socket, punch(nonce, a.getPort(), socket.getLocalPort(), 'P'), a);
                        } catch (IOException | UnsupportedAddressTypeException ignored) {
                            // Try the next candidate.
                        }
                    }
                    next = now + (now - began < 2_000_000_000L ? 100_000_000L : 400_000_000L);
                }
                socket.setSoTimeout(10);
                DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                InetSocketAddress source = (InetSocketAddress) packet.getSocketAddress();
                if (!validPunch(data, nonce)
                        || candidates.stream()
                                .noneMatch(a -> a.getAddress().equals(source.getAddress())))
                    continue;
                if (!candidates.contains(source) && !learned.contains(source)) {
                    if (learned.size() >= 8) continue;
                    learned.add(source);
                }
                progress.run();
                if (accept && (data[6] == 'P' || data[6] == 'A')) {
                    sendTo(
                            socket,
                            punch(nonce, source.getPort(), socket.getLocalPort(), 'C'),
                            source);
                    endpoint = source;
                    break;
                }
                if (!accept && data[6] == 'C') {
                    endpoint = source;
                    break;
                }
                if (!accept && data[6] == 'P') {
                    try {
                        sendTo(
                                socket,
                                punch(nonce, source.getPort(), socket.getLocalPort(), 'A'),
                                source);
                    } catch (IOException e) {
                        if (!sendRefused(e)) throw e;
                    }
                }
            }
            ReliableUdp e = new ReliableUdp(socket, endpoint, until, accept ? nonce : null);
            if (!accept) {
                ByteBuffer c = e.connectionCommand(0x82, 40);
                c.put(e.cookie);
                e.queue(c.array());
            }
            while (!e.connected) e.pump(20);
            return e;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /**
     * ENOBUFS/WSAENOBUFS: datagrams wait in the device queue. This is congestion, not a dead peer;
     * unacknowledged commands are retransmitted, so the channel must not fail (Rust send_refused).
     */
    public static boolean sendRefused(IOException e) {
        String m = e.getMessage();
        return m != null
                && (m.contains("No buffer space available")
                        || m.contains("lacked sufficient buffer space"));
    }

    private static List<InetSocketAddress> concat(
            List<InetSocketAddress> a, List<InetSocketAddress> b) {
        List<InetSocketAddress> c = new ArrayList<>(a);
        c.addAll(b);
        return c;
    }

    private static void sendTo(DatagramSocket s, byte[] b, InetSocketAddress a) throws IOException {
        s.send(new DatagramPacket(b, b.length, a));
    }

    private ByteBuffer connectionCommand(int kind, int length) {
        ByteBuffer c =
                ByteBuffer.allocate(length)
                        .put((byte) kind)
                        .put((byte) 255)
                        .putShort((short) 1)
                        .putShort((short) 0)
                        .putShort((short) mtu);
        for (int v : new int[] {32768, 1, 0, 0, 5000, 2, 2}) c.putInt(v);
        return c;
    }

    private boolean transmit(byte[] command) throws IOException {
        byte[] packet =
                ByteBuffer.allocate(command.length + 8)
                        .putInt(0)
                        .putShort((short) (remoteId | 0x8000))
                        .putShort((short) ((System.nanoTime() - started) / 1_000_000))
                        .put(command)
                        .array();
        ByteBuffer.wrap(packet).putInt(WireChecksum.enet(packet));
        DatagramChannel channel = socket.getChannel();
        if (channel != null) {
            channel.configureBlocking(false);
            try {
                int n;
                try {
                    n = channel.write(ByteBuffer.wrap(packet));
                } catch (IOException e) {
                    if (sendRefused(e)) return false;
                    throw e;
                }
                if (n == 0) return false;
                require(n == packet.length, "short UDP datagram write");
                return true;
            } finally {
                if (channel.isOpen()) channel.configureBlocking(true);
            }
        }
        socket.send(new DatagramPacket(packet, packet.length));
        return true;
    }

    private void queue(byte[] command) throws IOException {
        require(command.length >= 4 && pending.size() < 256, "UDP send window/command limit");
        int key = ((command[1] & 255) << 16) | u16(command, 2);
        require(!pending.containsKey(key), "duplicate pending UDP sequence");
        boolean sent = transmit(command);
        Pending p = new Pending(command);
        if (!sent) {
            p.tries = 0;
            p.last -= RETRY - 10_000_000L;
        }
        pending.put(key, p);
    }

    private int inFlight() {
        int max = 0;
        for (int key : pending.keySet())
            if ((key >>> 16) == 0) max = Math.max(max, wrap(outgoing - (key & 65535)) + 1);
        return max;
    }

    @Override
    public boolean hasRoom(int length) {
        return length > 0
                && length <= 65536
                && inFlight() + (length + mtu - 33) / (mtu - 32) <= 256;
    }

    @Override
    public void send(byte[] data) throws IOException {
        if (terminalFailure != null) throw terminalFailure;
        require(connected && data.length > 0 && data.length <= 65536, "UDP message size/state");
        require(hasRoom(data.length), "UDP send window exhausted");
        int size = mtu - 32, count = (data.length + size - 1) / size, start = wrap(outgoing + 1);
        for (int index = 0, offset = 0; offset < data.length; index++, offset += size) {
            int length = Math.min(size, data.length - offset);
            outgoing = wrap(outgoing + 1);
            ByteBuffer c =
                    ByteBuffer.allocate((count == 1 ? 6 : 24) + length)
                            .put((byte) (count == 1 ? 0x86 : 0x88))
                            .put((byte) 0)
                            .putShort((short) outgoing);
            if (count > 1) c.putShort((short) start);
            c.putShort((short) length);
            if (count > 1) c.putInt(count).putInt(index).putInt(data.length).putInt(offset);
            c.put(data, offset, length);
            queue(c.array());
        }
    }

    @Override
    public byte[] receive(int maximum) throws IOException {
        long until = System.nanoTime() + 10_000_000_000L;
        while (ready.isEmpty()) {
            require(System.nanoTime() < until, "UDP receive timeout");
            pump(50);
        }
        byte[] data = ready.remove();
        require(data.length <= maximum, "UDP message limit");
        return data;
    }

    @Override
    public boolean ready(Duration timeout) throws IOException {
        if (!ready.isEmpty()) return true;
        long until = System.nanoTime() + Math.max(0, timeout.toNanos());
        do {
            pump((int) Math.max(1, Math.min(50, (until - System.nanoTime()) / 1_000_000)));
            if (!ready.isEmpty()) return true;
        } while (System.nanoTime() < until);
        return false;
    }

    private void pump(int waitMillis) throws IOException {
        if (terminalFailure != null) throw terminalFailure;
        long now = System.nanoTime();
        require(
                now < deadline && !Thread.currentThread().isInterrupted(),
                "UDP handshake deadline/cancelled");
        long next = now + Math.max(1, waitMillis) * 1_000_000L;
        for (Pending p : pending.values()) next = Math.min(next, p.last + RETRY);
        next = Math.min(next, Math.min(deadline, lastReceive + LIVENESS));
        for (int i = 0; i < 64 && ready.size() < 128; i++) {
            socket.setSoTimeout(
                    i == 0
                            ? (int)
                                    Math.max(
                                            1, Math.min(50, (next - System.nanoTime()) / 1_000_000))
                            : 1);
            DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
            try {
                socket.receive(packet);
                ingest(Arrays.copyOf(packet.getData(), packet.getLength()));
            } catch (SocketTimeoutException e) {
                break;
            } catch (PortUnreachableException e) {
                // A remote close can make its final ACK fail on Windows. Deliver already
                // validated records first, then expose the terminal transport error.
                if (ready.isEmpty()) throw e;
                terminalFailure = e;
                return;
            }
        }
        now = System.nanoTime();
        require(now - lastReceive < LIVENESS, "UDP liveness timeout");
        for (Pending p : pending.values())
            if (now - p.last >= RETRY) {
                require(
                        (p.tries < 20 || now - lastReceive < 5_000_000_000L)
                                && now - p.queued < LIVENESS,
                        "UDP acknowledgement timeout");
                boolean sent = transmit(p.command);
                if (sent) p.tries++;
                p.last = sent ? now : now - RETRY + 10_000_000L;
            }
    }

    private void ingest(byte[] b) throws IOException {
        if (incomingNonce != null && validPunch(b, incomingNonce)) {
            if (b[6] == 'P' || b[6] == 'A') transmitPunch();
            return;
        }
        if (b.length < 8 || ByteBuffer.wrap(b).getInt() != WireChecksum.enet(b)) return;
        int id = u16(b, 4);
        if ((id & 0x7fff) != 0 && !(incomingNonce != null && (id & 0x7fff) == 0x7fff)) return;
        int time = (id & 0x8000) != 0 ? u16(b, 6) : 0, at = (id & 0x8000) != 0 ? 8 : 6;
        for (int commandCount = 0; commandCount < 32 && at < b.length; commandCount++) {
            require(b.length - at >= 4, "truncated ENET command");
            int kind = b[at] & 15;
            require(kind > 0 && kind < SIZES.length, "unknown ENET command");
            int size = SIZES[kind];
            require(b.length - at >= size, "truncated ENET command body");
            byte[] c = Arrays.copyOfRange(b, at, at + size);
            at += size;
            int channel = c[1] & 255,
                    seq = u16(c, 2),
                    length = kind == 6 ? u16(c, 4) : (kind >= 7 && kind <= 9 ? u16(c, 6) : 0);
            require(length <= b.length - at, "truncated ENET payload");
            byte[] payload = Arrays.copyOfRange(b, at, at + length);
            at += length;
            if (kind == 2) {
                require(
                        incomingNonce != null
                                && (id & 0x8000) != 0
                                && channel == 255
                                && seq == 1
                                && (c[0] & 255) == 0x82
                                && u16(c, 4) < 0x7fff
                                && u32(c, 12) == 1
                                && u32(c, 24) == 5000
                                && u32(c, 28) == 2
                                && u32(c, 32) == 2,
                        "ENET incoming CONNECT mismatch");
                if (remoteId == 0x7fff) {
                    remoteId = u16(c, 4);
                    mtu = Math.max(576, Math.min(mtu, u16(c, 6)));
                    cookie = Arrays.copyOfRange(c, 36, 40);
                    queue(connectionCommand(0x83, 36).array());
                } else
                    require(
                            remoteId == u16(c, 4)
                                    && Arrays.equals(cookie, Arrays.copyOfRange(c, 36, 40)),
                            "ENET changed CONNECT");
            }
            if ((c[0] & 128) != 0) {
                require((id & 0x8000) != 0, "ENET reliable command without timestamp");
                if (kind == 3) {
                    require(
                            incomingNonce == null
                                    && channel == 255
                                    && seq == 1
                                    && (c[0] & 255) == 0x83
                                    && u32(c, 12) == 1
                                    && u32(c, 24) == 5000
                                    && u32(c, 28) == 2
                                    && u32(c, 32) == 2,
                            "ENET verification mismatch");
                    remoteId = u16(c, 4);
                    require(remoteId < 0x7fff, "ENET peer id");
                    mtu = Math.max(576, Math.min(mtu, u16(c, 6)));
                    pending.remove((255 << 16) | 1);
                    connected = true;
                }
            }
            boolean accepted = true;
            switch (kind) {
                case 1 -> {
                    Pending p = pending.remove((channel << 16) | u16(c, 4));
                    if (incomingNonce != null && channel == 255 && u16(c, 4) == 1 && p != null)
                        connected = true;
                }
                case 2, 3, 5, 10, 11 -> {}
                case 4 -> throw new IOException("UDP peer disconnected");
                case 6 -> {
                    require(channel == 0, "unsupported ENET channel");
                    accepted = acceptOrdered(seq, 1, payload);
                }
                case 8 -> {
                    require(channel == 0, "unsupported ENET channel");
                    accepted = fragment(c, payload);
                }
                default -> throw new IOException("unsupported ENET command");
            }
            if ((c[0] & 128) != 0 && accepted)
                transmit(
                        ByteBuffer.allocate(8)
                                .put((byte) 1)
                                .put((byte) channel)
                                .putShort((short) 0)
                                .putShort((short) seq)
                                .putShort((short) time)
                                .array());
            lastReceive = System.nanoTime();
        }
        require(at == b.length, "ENET command budget");
    }

    private void transmitPunch() throws IOException {
        sendTo(
                socket,
                punch(incomingNonce, endpoint.getPort(), socket.getLocalPort(), 'C'),
                endpoint);
    }

    private boolean orderedHasRoom(int start) {
        int ahead = wrap(start - incoming);
        return ahead <= 512 && (ahead == 1 || (ordered.size() < 256 && ready.size() < 256));
    }

    private boolean acceptOrdered(int start, int count, byte[] data) {
        int ahead = wrap(start - incoming);
        if (ahead == 0 || ahead > 0x8000) return true;
        if (!orderedHasRoom(start)) return false;
        if (ahead == 1) {
            Ordered first = ordered.remove(start);
            if (first == null) first = new Ordered(count, data);
            incoming = wrap(incoming + first.count);
            ready.add(first.bytes);
        } else ordered.putIfAbsent(start, new Ordered(count, data));
        Ordered next;
        while ((next = ordered.remove(wrap(incoming + 1))) != null) {
            incoming = wrap(incoming + next.count);
            ready.add(next.bytes);
        }
        fragments
                .keySet()
                .removeIf(
                        s -> {
                            int d = wrap(s - incoming);
                            return d == 0 || d > 0x8000;
                        });
        return true;
    }

    private boolean fragment(byte[] c, byte[] payload) throws IOException {
        int start = u16(c, 4), ahead = wrap(start - incoming);
        if (ahead == 0 || ahead > 0x8000 || ordered.containsKey(start)) return true;
        long countL = u32(c, 8), indexL = u32(c, 12), totalL = u32(c, 16), offsetL = u32(c, 20);
        require(
                countL >= 1
                        && countL <= 128
                        && indexL < countL
                        && totalL >= 1
                        && totalL <= 65536
                        && offsetL <= totalL
                        && payload.length <= totalL - offsetL,
                "UDP fragment bounds");
        int count = (int) countL,
                index = (int) indexL,
                total = (int) totalL,
                offset = (int) offsetL;
        require(u16(c, 2) == wrap(start + index), "UDP fragment sequence");
        if (ahead > 512 || (!fragments.containsKey(start) && fragments.size() >= 16 && ahead != 1))
            return false;
        Fragment f = fragments.computeIfAbsent(start, s -> new Fragment(total, count));
        require(f.total == total && f.count == count, "inconsistent UDP fragments");
        if (!f.received.get(index)) {
            System.arraycopy(payload, 0, f.data, offset, payload.length);
            f.offsets[index] = offset;
            f.lengths[index] = payload.length;
            f.received.set(index);
        }
        if (f.received.cardinality() == count) {
            int assembled = 0;
            for (int i = 0; i < count; i++) {
                require(f.offsets[i] == assembled, "overlapping/gapped UDP fragments");
                assembled += f.lengths[i];
            }
            require(assembled == total, "UDP fragment total mismatch");
            if (!orderedHasRoom(start)) return false;
            fragments.remove(start);
            return acceptOrdered(start, count, f.data);
        }
        return true;
    }

    @Override
    public void sustain() {
        deadline = Long.MAX_VALUE;
    }

    @Override
    public InetSocketAddress endpoint() {
        return endpoint;
    }

    @Override
    public void close() {
        socket.close();
    }
}
