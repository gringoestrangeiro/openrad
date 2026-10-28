package io.github.gringoestrangeiro.openradplugin.discovery;

import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Shared local multicast receiver. One latest-value slot bounds memory and peer congestion. */
public final class LanDiscovery implements AutoCloseable {
    public record Snapshot(String state, long captured, long rejected, long sent) {}

    private record Announcement(LanAdvertisement value, long received) {}

    private static final long MAX_AGE = Duration.ofSeconds(5).toNanos();
    public static final long SEND_INTERVAL = Duration.ofMillis(1500).toNanos();
    private final MulticastSocket socket;
    private final Thread worker;
    private final Inet4Address vip;
    private final int backendPort, virtualPort;
    private final Set<InetAddress> localAddresses;
    private final AtomicReference<Announcement> latest = new AtomicReference<>();
    private final AtomicLong captured = new AtomicLong(),
            rejected = new AtomicLong(),
            sent = new AtomicLong();
    private volatile String state = "LISTENING";
    private volatile boolean closed;

    public LanDiscovery(Inet4Address vip, int backendPort, int virtualPort) throws IOException {
        this(vip, backendPort, virtualPort, LanAdvertisement.PORT);
    }

    // Package-private port override isolates socket tests from a user's Minecraft discovery
    // listener.
    LanDiscovery(Inet4Address vip, int backendPort, int virtualPort, int listenPort)
            throws IOException {
        this.vip = vip;
        this.backendPort = backendPort;
        this.virtualPort = virtualPort;
        Set<InetAddress> addresses = new HashSet<>();
        List<NetworkInterface> interfaces = new ArrayList<>();
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp()) continue;
            var v4 =
                    Collections.list(nic.getInetAddresses()).stream()
                            .filter(a -> a instanceof Inet4Address)
                            .toList();
            addresses.addAll(v4);
            if (!v4.isEmpty() && nic.supportsMulticast()) interfaces.add(nic);
        }
        addresses.add(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        localAddresses = Set.copyOf(addresses);
        socket = new MulticastSocket(null);
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(listenPort));
            socket.setSoTimeout(250);
            socket.setReceiveBufferSize(16 * 1024);
            var group = new InetSocketAddress(LanAdvertisement.GROUP, listenPort);
            int joined = 0;
            for (NetworkInterface nic : interfaces) {
                try {
                    socket.joinGroup(group, nic);
                    joined++;
                } catch (IOException ignored) {
                    /* Other usable interfaces may still receive. */
                }
            }
            if (joined == 0) throw new SocketException("no IPv4 multicast interface available");
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
        worker = io.github.gringoestrangeiro.openradplugin.util.ThreadCompat.platformStart("OpenRad Plugin-LAN", this::receive);
    }

    private void receive() {
        byte[] buffer = new byte[LanAdvertisement.MAX_PAYLOAD + 1];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                if (!localAddresses.contains(packet.getAddress())) {
                    rejected.incrementAndGet();
                    continue;
                }
                var advert =
                        LanAdvertisement.parse(
                                packet.getData(), packet.getOffset(), packet.getLength());
                if (advert.port() != backendPort) {
                    rejected.incrementAndGet();
                    continue;
                }
                // Validate the rewritten size as well (virtual port may have more digits).
                new LanAdvertisement(advert.motd(), virtualPort);
                latest.set(new Announcement(advert, System.nanoTime()));
                captured.incrementAndGet();
            } catch (SocketTimeoutException ignored) {
                // Closing the owning attachment interrupts this bounded receive.
            } catch (IllegalArgumentException invalid) {
                rejected.incrementAndGet();
            } catch (IOException failure) {
                if (!closed) state = "IO_FAILED";
                break;
            }
        }
    }

    /** Called by the authenticated peer worker, never forwards captured IP/MAC/port metadata. */
    public byte[] frame(long now) {
        var entry = latest.get();
        if (closed || entry == null || now - entry.received() > MAX_AGE) return null;
        return entry.value().frame(vip, virtualPort, (int) captured.get());
    }

    public void sent() {
        sent.incrementAndGet();
    }

    public Snapshot snapshot() {
        return new Snapshot(state, captured.get(), rejected.get(), sent.get());
    }

    @Override
    public void close() {
        closed = true;
        latest.set(null);
        state = "STOPPED";
        socket.close();
        try {
            worker.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
