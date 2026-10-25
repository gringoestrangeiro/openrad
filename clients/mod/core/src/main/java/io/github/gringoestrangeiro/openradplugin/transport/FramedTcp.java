package io.github.gringoestrangeiro.openradplugin.transport;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded four-byte big-endian framing, including the direct TCP rendezvous. */
public final class FramedTcp implements RecordTransport {
    private static final ScheduledThreadPoolExecutor WRITE_TIMER = writeTimer();

    private static ScheduledThreadPoolExecutor writeTimer() {
        ScheduledThreadPoolExecutor timer =
                new ScheduledThreadPoolExecutor(
                        1,
                        r -> {
                            Thread t = new Thread(r, "openrad-tcp-write-deadlines");
                            t.setDaemon(true);
                            t.setContextClassLoader(ClassLoader.getPlatformClassLoader());
                            return t;
                        });
        timer.setRemoveOnCancelPolicy(true);
        timer.setKeepAliveTime(1, TimeUnit.SECONDS);
        timer.allowCoreThreadTimeOut(true);
        return timer;
    }

    private final Socket socket;
    private final PushbackInputStream input;
    private final OutputStream output;
    private long deadline;
    private int records;
    private long received;
    /** Receive-side scratch for length prefixes; only the receiving owner touches it. */
    private final byte[] header = new byte[4];
    private static final long RECEIVE_SLICE = Duration.ofSeconds(10).toNanos();
    private static final int COALESCE_LIMIT = 128 * 1024;

    public FramedTcp(Socket socket, Duration timeout) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        this.input =
                new PushbackInputStream(new BufferedInputStream(socket.getInputStream(), 8192), 1);
        this.output = socket.getOutputStream();
        this.deadline = System.nanoTime() + timeout.toNanos();
    }

    public static FramedTcp connect(InetSocketAddress endpoint, Duration timeout)
            throws IOException {
        return connect(endpoint, timeout, Duration.ofSeconds(8));
    }

    public static FramedTcp connect(
            InetSocketAddress endpoint, Duration timeout, Duration connectTimeout)
            throws IOException {
        long began = System.nanoTime();
        if (endpoint.isUnresolved())
            throw new IOException("TCP endpoint must be resolved before bounded connect");
        SocketChannel channel = SocketChannel.open();
        Socket socket = channel.socket();
        try {
            long connectUntil =
                    began
                            + Math.min(
                                    timeout.toNanos(),
                                    Math.min(
                                            Duration.ofSeconds(8).toNanos(),
                                            connectTimeout.toNanos()));
            channel.configureBlocking(false);
            try (Selector selector = Selector.open()) {
                channel.register(selector, SelectionKey.OP_CONNECT);
                if (!channel.connect(endpoint))
                    while (!channel.finishConnect()) {
                        long budget = connectUntil - System.nanoTime();
                        if (budget <= 0 || Thread.currentThread().isInterrupted())
                            throw new SocketTimeoutException("TCP connect deadline/cancelled");
                        selector.select(Math.max(1, Math.min(100, budget / 1_000_000)));
                        selector.selectedKeys().clear();
                    }
            }
            channel.configureBlocking(true);
            Duration remaining = timeout.minusNanos(System.nanoTime() - began);
            if (remaining.isNegative() || remaining.isZero())
                throw new SocketTimeoutException("TCP connection deadline");
            return new FramedTcp(socket, remaining);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private long until() {
        return Math.min(deadline, System.nanoTime() + RECEIVE_SLICE);
    }

    private byte[] read(int count, long until) throws IOException {
        return read(new byte[count], count, until);
    }

    private byte[] read(byte[] bytes, int count, long until) throws IOException {
        int at = 0;
        while (at < count) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= until)
                throw new SocketTimeoutException("TCP receive deadline");
            socket.setSoTimeout(
                    (int) Math.max(1, Math.min(200, (until - System.nanoTime()) / 1_000_000)));
            try {
                int n = input.read(bytes, at, count - at);
                if (n < 0) throw new EOFException("TCP peer closed");
                at += n;
            } catch (SocketTimeoutException e) {
                if (System.nanoTime() >= until) throw e;
            }
        }
        return bytes;
    }

    private void checkDeadline() throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new SocketTimeoutException("TCP session deadline");
    }

    @Override
    public void send(byte[] data) throws IOException {
        checkDeadline();
        if (data.length == 0 || data.length > 4 * 1024 * 1024)
            throw new IOException("TCP frame size");
        if (data.length > COALESCE_LIMIT) {
            // Large (control) records keep two writes rather than duplicating megabytes.
            writeRaw(ByteBuffer.allocate(4).putInt(data.length).array(), data);
            return;
        }
        // One write per record: the byte stream is unchanged, but the four-byte prefix no
        // longer leaves as its own TCP_NODELAY segment and system call.
        byte[] record = new byte[4 + data.length];
        record[0] = (byte) (data.length >>> 24);
        record[1] = (byte) (data.length >>> 16);
        record[2] = (byte) (data.length >>> 8);
        record[3] = (byte) data.length;
        System.arraycopy(data, 0, record, 4, data.length);
        writeRaw(record);
    }

    private void writeRaw(byte[]... parts) throws IOException {
        checkDeadline();
        AtomicBoolean active = new AtomicBoolean(true);
        long budget = Math.min(5_000_000_000L, deadline - System.nanoTime());
        ScheduledFuture<?> timer =
                WRITE_TIMER.schedule(
                        () -> {
                            if (active.compareAndSet(true, false))
                                try {
                                    socket.close();
                                } catch (IOException ignored) {
                                }
                        },
                        Math.max(1, budget),
                        TimeUnit.NANOSECONDS);
        try {
            for (byte[] part : parts) output.write(part);
            output.flush();
            if (!active.compareAndSet(true, false))
                throw new SocketTimeoutException("TCP write deadline");
        } finally {
            active.set(false);
            timer.cancel(false);
        }
    }

    @Override
    public byte[] receive(int maximum) throws IOException {
        checkDeadline();
        if (maximum < 1 || (deadline != Long.MAX_VALUE && records >= 4096))
            throw new IOException("TCP record budget");
        long until = until();
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(read(header, 4, until)).getInt());
        if (length == 0
                || length > maximum
                || (deadline != Long.MAX_VALUE && received + length > 32 * 1024 * 1024))
            throw new IOException("TCP frame/byte limit");
        byte[] result = read((int) length, until);
        records++;
        received += length;
        return result;
    }

    @Override
    public boolean ready(Duration timeout) throws IOException {
        checkDeadline();
        if (input.available() > 0) return true;
        if (timeout.isZero() || timeout.isNegative()) return false;
        long until = Math.min(deadline, System.nanoTime() + timeout.toNanos());
        while (System.nanoTime() < until) {
            socket.setSoTimeout(
                    (int) Math.max(1, Math.min(200, (until - System.nanoTime()) / 1_000_000)));
            try {
                int value = input.read();
                if (value < 0) throw new EOFException("TCP peer closed");
                input.unread(value);
                return true;
            } catch (SocketTimeoutException e) {
                checkDeadline();
            }
        }
        return false;
    }

    public void peerRendezvous(long ownRid, long cid, int serverId) throws IOException {
        if (ownRid == 0 || cid == 0 || serverId == 0)
            throw new IllegalArgumentException("zero rendezvous identity");
        byte[] body = ByteBuffer.allocate(20).putLong(ownRid).putLong(cid).putInt(serverId).array();
        byte[] record = new byte[24];
        record[1] = 20;
        record[3] = 1;
        System.arraycopy(body, 0, record, 4, 20);
        writeRaw(record);
        if (ByteBuffer.wrap(read(header, 4, until())).getInt() != WireChecksum.checksum(body))
            throw new IOException("TCP rendezvous checksum mismatch");
    }

    public void acceptRendezvous(long peerRid, long cid) throws IOException {
        byte[] data = read(24, until());
        ByteBuffer body = ByteBuffer.wrap(data);
        if (body.getInt() != 0x00140001
                || peerRid == 0
                || cid == 0
                || body.getLong() != peerRid
                || body.getLong() != cid
                || body.getInt() == 0) throw new IOException("TCP rendezvous identity mismatch");
        writeRaw(
                ByteBuffer.allocate(4)
                        .putInt(WireChecksum.checksum(data, 4, 20))
                        .array());
    }

    @Override
    public void sustain() {
        deadline = Long.MAX_VALUE;
    }

    /** Start the service handshake budget after an independently bounded rendezvous. */
    public void handshakeDeadline(Duration timeout) {
        deadline = System.nanoTime() + timeout.toNanos();
    }

    public Socket socket() {
        return socket;
    }

    @Override
    public InetSocketAddress endpoint() {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
