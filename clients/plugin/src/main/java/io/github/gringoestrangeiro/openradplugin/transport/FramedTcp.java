package io.github.gringoestrangeiro.openradplugin.transport;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded four-byte big-endian framing, including the direct TCP rendezvous. */
public final class FramedTcp implements RecordTransport {
    private static final long WRITE_LIMIT = Duration.ofSeconds(5).toNanos();
    private static final long NO_WRITE = Long.MAX_VALUE, WRITE_EXPIRED = Long.MIN_VALUE;
    private static final WriteWatchdog WATCHDOG = new WriteWatchdog();

    /**
     * Closes the socket of any write that outlives its deadline. Writers only publish a
     * timestamp; this single daemon thread sweeps the streams that have written, and exits as soon
     * as none is left so that it never outlives its plugin class loader.
     */
    static final class WriteWatchdog implements Runnable {
        private static final long SWEEP = Duration.ofMillis(250).toNanos();
        private final Set<FramedTcp> streams =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean running;

        synchronized void add(FramedTcp stream) {
            streams.add(stream);
            if (running) return;
            Thread thread = new Thread(this, "openrad-tcp-write-watchdog");
            thread.setDaemon(true);
            thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
            thread.start();
            running = true;
        }

        synchronized void remove(FramedTcp stream) {
            streams.remove(stream);
        }

        synchronized int size() {
            return streams.size();
        }

        synchronized boolean running() {
            return running;
        }

        @Override
        public void run() {
            while (true) {
                FramedTcp[] live;
                synchronized (this) {
                    streams.removeIf(stream -> stream.socket.isClosed());
                    if (streams.isEmpty()) {
                        running = false;
                        return;
                    }
                    live = streams.toArray(new FramedTcp[0]);
                }
                long now = System.nanoTime();
                for (FramedTcp stream : live) stream.abortStalledWrite(now);
                LockSupport.parkNanos(SWEEP);
            }
        }
    }

    private final Socket socket;
    private final PushbackInputStream input;
    private final OutputStream output;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong writeDeadline = new AtomicLong(NO_WRITE);
    private volatile boolean watched;
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
        writeLock.lock();
        try {
            long now = System.nanoTime();
            long limit = now + Math.max(1, Math.min(WRITE_LIMIT, deadline - now));
            writeDeadline.set(limit);
            if (!watched) {
                watched = true;
                WATCHDOG.add(this);
            }
            boolean expired;
            try {
                for (byte[] part : parts) output.write(part);
                output.flush();
            } finally {
                expired = !writeDeadline.compareAndSet(limit, NO_WRITE);
            }
            if (expired) throw new SocketTimeoutException("TCP write deadline");
        } finally {
            writeLock.unlock();
        }
    }

    private void abortStalledWrite(long now) {
        long limit = writeDeadline.get();
        if (limit != NO_WRITE
                && limit != WRITE_EXPIRED
                && now >= limit
                && writeDeadline.compareAndSet(limit, WRITE_EXPIRED))
            try {
                socket.close();
            } catch (IOException ignored) {
            }
    }

    static int watchedStreams() {
        return WATCHDOG.size();
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
        try {
            socket.close();
        } finally {
            if (watched) WATCHDOG.remove(this);
        }
    }
}
