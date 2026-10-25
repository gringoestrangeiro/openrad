package io.github.gringoestrangeiro.openradclient;

import io.github.gringoestrangeiro.openradplugin.discovery.LanAdvertisement;
import io.github.gringoestrangeiro.openradplugin.link.Checksum;
import io.github.gringoestrangeiro.openradplugin.link.EthernetFrame;
import io.github.gringoestrangeiro.openradplugin.protocol.Wire;
import java.net.Inet4Address;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Receives only authenticated VPN frames; no operating-system multicast socket exists. */
final class LanServerRegistry {
    static final long TTL_MILLIS = 10_000;
    static final int MAX_SERVERS = 256;
    /** One authenticated member may not crowd every other server out of the list. */
    static final int MAX_SERVERS_PER_PEER = 8;
    private static final byte[] MULTICAST_MAC = {1, 0, 0x5e, 0, 2, 0x3c};
    private final LongSupplier clock;
    private final LongSupplier monotonic;
    private record Entry(ClientVpnRuntime.LanServer server, long receivedNanos) {}
    private final Map<String, Entry> servers = new LinkedHashMap<>();

    LanServerRegistry() { this(System::currentTimeMillis, System::nanoTime); }
    LanServerRegistry(LongSupplier clock) { this(clock, () -> TimeUnit.MILLISECONDS.toNanos(clock.getAsLong())); }
    LanServerRegistry(LongSupplier clock, LongSupplier monotonic) {
        this.clock = clock;
        this.monotonic = monotonic;
    }

    synchronized boolean accept(Inet4Address authenticatedVip, byte[] authenticatedMac, byte[] frame) {
        try {
            if ((authenticatedVip.getAddress()[0] & 255) != 26) return false;
            EthernetFrame ethernet = EthernetFrame.parse(frame);
            if (ethernet.etherType() != EthernetFrame.IPV4
                    || !Arrays.equals(ethernet.sourceMac(), authenticatedMac)
                    || !Arrays.equals(ethernet.destinationMac(), MULTICAST_MAC)) return false;
            byte[] ip = ethernet.payload();
            if (ip.length < 28 || ip[0] != 0x45 || (ip[9] & 255) != 17
                    || ip[8] == 0 || (Checksum.u16(ip, 6) & 0xbfff) != 0
                    || Checksum.onesComplement(ip, 0, 20) != 0
                    || Checksum.u32(ip, 12) != Wire.ipInt(authenticatedVip)
                    || Checksum.u32(ip, 16) != 0xe000023c) return false;
            int total = Checksum.u16(ip, 2), udpLength = Checksum.u16(ip, 24);
            if (total != 20 + udpLength || total > ip.length || udpLength < 9
                    || Checksum.u16(ip, 20) == 0
                    || Checksum.u16(ip, 22) != LanAdvertisement.PORT) return false;
            if (Checksum.u16(ip, 26) != 0) {
                byte[] pseudo = new byte[12 + udpLength];
                System.arraycopy(ip, 12, pseudo, 0, 8);
                pseudo[9] = 17;
                Checksum.put16(pseudo, 10, udpLength);
                System.arraycopy(ip, 20, pseudo, 12, udpLength);
                if (Checksum.onesComplement(pseudo, 0, pseudo.length) != 0) return false;
            }
            LanAdvertisement advertisement = LanAdvertisement.parse(ip, 28, udpLength - 8);
            long now = clock.getAsLong();
            long receivedNanos = monotonic.getAsLong();
            expire(receivedNanos);
            String address = authenticatedVip.getHostAddress();
            String key = address + ":" + advertisement.port();
            if (!servers.containsKey(key)) {
                List<String> own = servers.entrySet().stream()
                        .filter(entry -> entry.getValue().server().address().equals(address))
                        .sorted(Comparator.comparingLong(entry -> entry.getValue().receivedNanos()))
                        .map(Map.Entry::getKey).toList();
                if (own.size() >= MAX_SERVERS_PER_PEER) servers.remove(own.getFirst());
                else if (servers.size() >= MAX_SERVERS) return false;
            }
            servers.put(key, new Entry(new ClientVpnRuntime.LanServer(
                    advertisement.motd(), address, advertisement.port(), now), receivedNanos));
            return true;
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            return false;
        }
    }

    synchronized List<ClientVpnRuntime.LanServer> snapshot() {
        expire(monotonic.getAsLong());
        return servers.values().stream().map(Entry::server).toList();
    }

    synchronized void removePeer(Inet4Address peer) {
        servers.values().removeIf(entry -> entry.server().address().equals(peer.getHostAddress()));
    }

    synchronized void clear() { servers.clear(); }

    private void expire(long now) {
        servers.values().removeIf(entry -> now - entry.receivedNanos() >= TimeUnit.MILLISECONDS.toNanos(TTL_MILLIS));
    }
}
