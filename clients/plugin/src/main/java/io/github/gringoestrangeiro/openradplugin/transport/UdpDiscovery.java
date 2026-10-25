package io.github.gringoestrangeiro.openradplugin.transport;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/** UES mapping discovery queries two distinct authenticated servers on the same socket. */
public final class UdpDiscovery {
    public enum Agreement {
        AGREE,
        ONE_SERVER,
        VARIES
    }

    public record Mapping(InetSocketAddress endpoint, Agreement agreement) {}

    private UdpDiscovery() {}

    public static List<InetSocketAddress> localCandidates(InetAddress routeIp, int port)
            throws IOException {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid candidate port");
        NetworkInterface nic = NetworkInterface.getByInetAddress(routeIp);
        if (nic == null) throw new IOException("route has no local interface");
        List<InetSocketAddress> result =
                Collections.list(nic.getInetAddresses()).stream()
                        .filter(
                                ip ->
                                        !ip.isAnyLocalAddress()
                                                && !ip.isMulticastAddress()
                                                && !ip.isLoopbackAddress()
                                                && !(ip instanceof Inet6Address
                                                        && ip.isLinkLocalAddress()))
                        .map(ip -> new InetSocketAddress(ip, port))
                        .distinct()
                        .limit(32)
                        .toList();
        if (result.isEmpty()) throw new IOException("no routable local UDP candidates");
        return result;
    }

    public static Mapping discover(DatagramSocket socket, List<Inet4Address> authenticatedHosts)
            throws IOException {
        List<Inet4Address> hosts = authenticatedHosts.stream().distinct().toList();
        if (hosts.size() < 2)
            throw new IllegalArgumentException("insufficient authenticated UES hosts");
        SecureRandom random = new SecureRandom();
        int start = random.nextInt(hosts.size());
        return discoverAt(
                socket,
                List.of(
                        new InetSocketAddress(hosts.get(start), 17301 + random.nextInt(99)),
                        new InetSocketAddress(
                                hosts.get((start + 1) % hosts.size()), 17301 + random.nextInt(99))),
                Duration.ofMillis(2500));
    }

    public static Mapping discoverAt(
            DatagramSocket socket, List<InetSocketAddress> servers, Duration timeout)
            throws IOException {
        if (servers.size() != 2 || servers.get(0).equals(servers.get(1)))
            throw new IllegalArgumentException("two distinct UES endpoints required");
        byte[][] transactions = {new byte[16], new byte[16]};
        SecureRandom random = new SecureRandom();
        for (byte[] t : transactions) random.nextBytes(t);
        InetSocketAddress[] addresses = new InetSocketAddress[2];
        long began = System.nanoTime(), until = began + timeout.toNanos();
        long[] offsets = {0, 400_000_000L, 900_000_000L, 1_500_000_000L};
        int sends = 0;
        while (System.nanoTime() < until) {
            if (Thread.currentThread().isInterrupted())
                throw new IOException("UES discovery cancelled");
            if (sends < offsets.length && System.nanoTime() - began >= offsets[sends]) {
                for (int i = 0; i < 2; i++)
                    if (addresses[i] == null) {
                        byte[] request =
                                ByteBuffer.allocate(20)
                                        .putInt(0x00010000)
                                        .put(transactions[i])
                                        .array();
                        socket.send(new DatagramPacket(request, request.length, servers.get(i)));
                    }
                sends++;
            }
            socket.setSoTimeout(
                    (int) Math.max(1, Math.min(25, (until - System.nanoTime()) / 1_000_000)));
            DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            int i = servers.indexOf(p.getSocketAddress());
            if (i >= 0 && addresses[i] == null)
                try {
                    addresses[i] =
                            parseResponse(
                                    Arrays.copyOf(p.getData(), p.getLength()), transactions[i]);
                } catch (IllegalArgumentException ignored) {
                }
            if (addresses[0] != null && addresses[1] != null)
                return new Mapping(
                        addresses[0],
                        addresses[0].equals(addresses[1]) ? Agreement.AGREE : Agreement.VARIES);
        }
        if (addresses[0] != null || addresses[1] != null)
            return new Mapping(
                    addresses[0] != null ? addresses[0] : addresses[1], Agreement.ONE_SERVER);
        throw new SocketTimeoutException("UES mapping timeout");
    }

    public static InetSocketAddress parseResponse(byte[] data, byte[] transaction) {
        if (data.length < 20 || transaction.length != 16)
            throw new IllegalArgumentException("UES response header");
        ByteBuffer b = ByteBuffer.wrap(data);
        if (Short.toUnsignedInt(b.getShort()) != 0x0101
                || Short.toUnsignedInt(b.getShort()) != data.length - 20)
            throw new IllegalArgumentException("UES response type/length");
        byte[] id = new byte[16];
        b.get(id);
        if (!Arrays.equals(id, transaction))
            throw new IllegalArgumentException("UES transaction mismatch");
        InetSocketAddress mapped = null;
        while (b.hasRemaining()) {
            if (b.remaining() < 4) throw new IllegalArgumentException("truncated UES attribute");
            int kind = Short.toUnsignedInt(b.getShort()), len = Short.toUnsignedInt(b.getShort());
            if (len > b.remaining()) throw new IllegalArgumentException("truncated UES value");
            int end = b.position() + len;
            if (kind == 1) {
                if (mapped != null || len != 8 || b.get() != 0 || b.get() != 1)
                    throw new IllegalArgumentException("invalid UES mapped address");
                int port = Short.toUnsignedInt(b.getShort());
                byte[] ip = new byte[4];
                b.get(ip);
                try {
                    InetAddress a = InetAddress.getByAddress(ip);
                    if (port == 0
                            || a.isAnyLocalAddress()
                            || a.isMulticastAddress()
                            || Arrays.equals(ip, new byte[] {-1, -1, -1, -1}))
                        throw new IllegalArgumentException("invalid UES endpoint");
                    mapped = new InetSocketAddress(a, port);
                } catch (UnknownHostException impossible) {
                    throw new AssertionError(impossible);
                }
            }
            b.position(end);
        }
        if (mapped == null) throw new IllegalArgumentException("missing UES mapping");
        return mapped;
    }
}
