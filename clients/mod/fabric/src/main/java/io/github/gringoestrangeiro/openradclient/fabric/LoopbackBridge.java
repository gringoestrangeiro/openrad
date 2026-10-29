package io.github.gringoestrangeiro.openradclient.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-destination, single-use sockets. No listener ever accepts a target from its client. */
public final class LoopbackBridge implements AutoCloseable {
    public interface Tunnel extends AutoCloseable {
        InputStream input();
        OutputStream output();
        /** Send FIN after queued bytes while retaining the input stream. */
        default void shutdownOutput() throws IOException { output().close(); }
        @Override void close() throws IOException;
    }

    @FunctionalInterface
    public interface TunnelOpener {
        Tunnel open(Inet4Address address, int port) throws IOException;
    }

    private static final InetAddress LOOPBACK;
    static {
        try { LOOPBACK = InetAddress.getByAddress(new byte[]{127, 0, 0, 1}); }
        catch (IOException impossible) { throw new ExceptionInInitializerError(impossible); }
    }
    /** Port 0 cannot accept connections: a connect attempt fails immediately, on loopback only. */
    public static final InetSocketAddress UNREACHABLE = new InetSocketAddress(LOOPBACK, 0);
    private final TunnelOpener opener;
    private final Semaphore capacity;
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public LoopbackBridge(TunnelOpener opener) { this(opener, 16); }
    public LoopbackBridge(TunnelOpener opener, int maximumConnections) {
        if (maximumConnections < 1 || maximumConnections > 64) throw new IllegalArgumentException("connection limit");
        this.opener = java.util.Objects.requireNonNull(opener);
        this.capacity = new Semaphore(maximumConnections);
    }

    /** Performs no DNS lookup or VPN I/O. Non-VPN addresses retain their original object. */
    public InetSocketAddress route(InetSocketAddress destination) {
        InetAddress resolved = destination.getAddress();
        if (resolved == null) resolved = ipv4Literal(destination.getHostString());
        if (!(resolved instanceof Inet4Address address) || (address.getAddress()[0] & 255) != 26)
            return destination;
        if (closed.get() || !capacity.tryAcquire())
            throw new IllegalStateException("OpenRad connection capacity unavailable");
        Lease lease = null;
        try {
            ServerSocket listener = new ServerSocket();
            try {
                listener.bind(new InetSocketAddress(LOOPBACK, 0), 1);
                listener.setSoTimeout(5_000);
                lease = new Lease(listener);
            } catch (IOException failure) {
                listener.close();
                throw failure;
            }
            leases.add(lease);
            if (closed.get()) throw new IOException("OpenRad bridge closed");
            Lease running = lease;
            Thread worker = io.github.gringoestrangeiro.openradplugin.util.ThreadCompat.virtualUnstarted("openrad-minecraft-bridge",() -> serve(running, address, destination.getPort()));
            lease.start(worker);
            return new InetSocketAddress(LOOPBACK, listener.getLocalPort());
        } catch (IOException | RuntimeException failure) {
            if (lease == null) capacity.release();
            else lease.close();
            if (failure instanceof IOException io) throw new UncheckedIOException("OpenRad local bridge unavailable", io);
            throw (RuntimeException) failure;
        }
    }

    private static InetAddress ipv4Literal(String host) {
        // InetAddress.getByName would perform DNS and must never run in this hook.
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int index = 0; index < 4; index++) {
            String part = parts[index];
            if (part.isEmpty() || part.length() > 3) return null;
            int value = 0;
            for (int digit = 0; digit < part.length(); digit++) {
                char ch = part.charAt(digit);
                if (ch < '0' || ch > '9') return null;
                value = value * 10 + ch - '0';
            }
            if (value > 255) return null;
            bytes[index] = (byte) value;
        }
        try { return InetAddress.getByAddress(bytes); }
        catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    private void serve(Lease lease, Inet4Address address, int port) {
        boolean pumpsStarted = false;
        try {
            Socket socket = lease.listener.accept();
            if (!lease.attach(socket)) return;
            // Consume one connection only; an unused or consumed endpoint cannot become a general proxy.
            lease.listener.close();
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(16_384);
            socket.setSendBufferSize(16_384);
            socket.setSoTimeout(60_000);
            Tunnel tunnel = opener.open(address, port);
            if (!lease.attach(tunnel)) return;
            pumpsStarted = true;
            io.github.gringoestrangeiro.openradplugin.util.ThreadCompat.virtualStart("openrad-minecraft-upload",() -> {
                try {
                    copy(socket.getInputStream(), tunnel.output());
                    tunnel.shutdownOutput();
                    lease.pumpFinished();
                } catch (IOException | RuntimeException ignored) { lease.close(); }
            });
            copy(tunnel.input(), socket.getOutputStream());
            socket.shutdownOutput();
            lease.pumpFinished();
        } catch (IOException | RuntimeException ignored) {
            // Minecraft reports closed connections through its ordinary disconnect screen.
            lease.close();
        } finally { if (!pumpsStarted) lease.close(); }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16_384];
        for (int count; (count = input.read(buffer)) >= 0;) {
            if (count == 0) continue;
            output.write(buffer, 0, count);
            output.flush();
        }
    }

    public void closeActive() { for (Lease lease : leases) lease.close(); }
    @Override public void close() { closed.set(true); closeActive(); }

    private final class Lease {
        private final ServerSocket listener;
        private boolean finished;
        private Socket socket;
        private Tunnel tunnel;
        private Thread worker;
        private int finishedPumps;
        private Lease(ServerSocket listener) { this.listener = listener; }
        private synchronized void start(Thread value) throws IOException {
            if (finished) throw new IOException("OpenRad bridge closed");
            worker = value;
            value.start();
        }
        private synchronized boolean attach(Socket value) throws IOException {
            if (finished) { value.close(); return false; }
            socket = value;
            return true;
        }
        private synchronized boolean attach(Tunnel value) throws IOException {
            if (finished) { value.close(); return false; }
            tunnel = value;
            return true;
        }
        private synchronized void pumpFinished() { if (++finishedPumps == 2) close(); }
        private synchronized void close() {
            if (finished) return;
            finished = true;
            if (worker != null && worker != Thread.currentThread()) worker.interrupt();
            try { listener.close(); } catch (IOException ignored) { }
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            if (tunnel != null) try { tunnel.close(); } catch (IOException ignored) { }
            leases.remove(this);
            capacity.release();
        }
    }
}
