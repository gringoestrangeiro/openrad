import static io.github.gringoestrangeiro.openradbridge.link.TcpSegment.*;

import io.github.gringoestrangeiro.openradbridge.bridge.VirtualTcpBridge;
import io.github.gringoestrangeiro.openradbridge.link.*;
import io.github.gringoestrangeiro.openradbridge.peer.PeerChannel;
import io.github.gringoestrangeiro.openradbridge.security.InboundFirewall.PeerGrant;
import io.github.gringoestrangeiro.openradbridge.transport.FramedTcp;
import io.github.gringoestrangeiro.openradbridge.tunnel.TunnelCodec;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic authenticated peer -> real user-mode TCP -> real Paper. No official Radmin client. */
public final class VirtualMinecraftSmoke {
    static int SERVER_IP = 0x1a010102, CLIENT_IP = 0x1a010103;
    static final int PORT = 25565;

    public static void configure(int serverIp, int clientIp) {
        SERVER_IP = serverIp;
        CLIENT_IP = clientIp;
    }

    static final byte[] PASSWORD = "synthetic-smoke-password".getBytes(StandardCharsets.US_ASCII),
            EMPTY = new byte[0];

    static java.net.Inet4Address address(int ip) throws Exception {
        return (java.net.Inet4Address)
                InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip).array());
    }

    static byte[] vi(int n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int b = n & 127;
            n >>>= 7;
            out.write(n == 0 ? b : b | 128);
        } while (n != 0);
        return out.toByteArray();
    }

    static byte[] cat(byte[]... a) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] v : a) out.writeBytes(v);
        return out.toByteArray();
    }

    static byte[] str(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return cat(vi(b.length), b);
    }

    static byte[] packet(byte[] b) {
        return cat(vi(b.length), b);
    }

    static int[] readVi(byte[] b, int offset) {
        int n = 0;
        for (int i = 0; i < 5; i++) {
            if (offset + i >= b.length) return null;
            int v = b[offset + i] & 255;
            n |= (v & 127) << (7 * i);
            if ((v & 128) == 0) return new int[] {n, offset + i + 1};
        }
        throw new IllegalArgumentException("oversized VarInt");
    }

    static void tcp(PeerChannel p, int clientPort, long seq, long ack, int flags, byte[] payload)
            throws Exception {
        TcpSegment t =
                new TcpSegment(
                        clientPort,
                        PORT,
                        seq & 0xffffffffL,
                        ack & 0xffffffffL,
                        flags,
                        32768,
                        EMPTY,
                        payload);
        byte[] ip =
                new Ipv4Packet(CLIENT_IP, SERVER_IP, 64, t.encode(CLIENT_IP, SERVER_IP)).encode();
        if (!p.sendFrame(
                new EthernetFrame(
                                TunnelCodec.mac(address(SERVER_IP)),
                                TunnelCodec.mac(address(CLIENT_IP)),
                                EthernetFrame.IPV4,
                                ip)
                        .encode())) throw new IOException("transport full");
    }

    static int exchange(PeerChannel peer, int port, int nextState, int expectedPacket)
            throws Exception {
        long ownSeq = 1_000_000L + port, serverSeq = 0;
        tcp(peer, port, ownSeq++, 0, SYN, EMPTY);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean sent = false;
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < until) {
            if (!peer.ready(Duration.ofMillis(100))) continue;
            var record = peer.receivePacket();
            if (!(record instanceof TunnelCodec.Frames f)) continue;
            for (byte[] frame : f.frames()) {
                EthernetFrame eth = EthernetFrame.parse(frame);
                if (eth.etherType() != EthernetFrame.IPV4
                        || eth.payload().length < 20
                        || eth.payload()[9] != 6) continue;
                Ipv4Packet ip = Ipv4Packet.parse(eth.payload());
                TcpSegment tcp = TcpSegment.parse(ip.tcpBytes(), SERVER_IP, CLIENT_IP);
                if (tcp.destinationPort() != port) continue;
                if ((tcp.flags() & RST) != 0) throw new IOException("virtual TCP reset");
                if ((tcp.flags() & SYN) != 0) {
                    serverSeq = (tcp.sequence() + 1) & 0xffffffffL;
                    tcp(peer, port, ownSeq, serverSeq, ACK, EMPTY);
                    byte[] hello =
                            packet(
                                    cat(
                                            vi(0),
                                            vi(767),
                                            str("26.1.1.2"),
                                            new byte[] {0x63, (byte) 0xdd},
                                            vi(nextState)));
                    byte[] request =
                            nextState == 1
                                    ? packet(vi(0))
                                    : packet(cat(vi(0), str("OrbSmoke"), new byte[16]));
                    byte[] payload = cat(hello, request);
                    tcp(peer, port, ownSeq, serverSeq, ACK | PSH, payload);
                    ownSeq += payload.length;
                    sent = true;
                }
                if (tcp.payload().length > 0) {
                    if (tcp.sequence() == serverSeq) {
                        bytes.writeBytes(tcp.payload());
                        serverSeq = (serverSeq + tcp.payload().length) & 0xffffffffL;
                    }
                    tcp(peer, port, ownSeq, serverSeq, ACK, EMPTY);
                }
                byte[] data = bytes.toByteArray();
                int[] length = readVi(data, 0);
                if (sent && length != null && data.length >= length[0] + length[1]) {
                    int[] id = readVi(data, length[1]);
                    if (id == null || id[0] != expectedPacket)
                        throw new IOException("Minecraft packet ID mismatch");
                    if (nextState == 1) {
                        int[] size = readVi(data, id[1]);
                        String json = new String(data, size[1], size[0], StandardCharsets.UTF_8);
                        if (!json.contains("1.21.1")) throw new IOException("Paper version absent");
                    }
                    tcp(peer, port, ownSeq, serverSeq, ACK | FIN, EMPTY);
                    return length[0];
                }
            }
        }
        throw new SocketTimeoutException("Minecraft virtual TCP response timeout");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("fixed loopback Paper port required");
        InetSocketAddress backend =
                new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), Integer.parseInt(args[0]));
        BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(128);
        CountDownLatch finished = new CountDownLatch(1);
        try (ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> worker =
                    jobs.submit(
                            () -> {
                                try (PeerChannel accepted =
                                                PeerChannel.accept(
                                                        new FramedTcp(
                                                                listener.accept(),
                                                                Duration.ofSeconds(30)),
                                                        PeerChannel.Path.DIRECT_TCP,
                                                        PASSWORD,
                                                        11,
                                                        address(SERVER_IP),
                                                        22);
                                        VirtualTcpBridge bridge =
                                                new VirtualTcpBridge(
                                                        SERVER_IP,
                                                        TunnelCodec.mac(address(SERVER_IP)),
                                                        PORT,
                                                        backend,
                                                        (rid, frame) -> outbound.offer(frame))) {
                                    PeerGrant grant =
                                            new PeerGrant(
                                                    22,
                                                    true,
                                                    Set.of("synthetic-smoke"),
                                                    CLIENT_IP,
                                                    accepted.peerMac());
                                    while (finished.getCount() > 0) {
                                        for (int i = 0; i < 32; i++) {
                                            byte[] frame = outbound.poll();
                                            if (frame == null) break;
                                            if (!accepted.sendFrame(frame))
                                                throw new IOException("outbound transport full");
                                        }
                                        if (accepted.ready(Duration.ofMillis(10))) {
                                            var record = accepted.receivePacket();
                                            if (record instanceof TunnelCodec.Frames frames)
                                                for (byte[] frame : frames.frames())
                                                    bridge.accept(grant, frame);
                                        }
                                    }
                                    System.out.println(
                                            "VIRTUAL_BRIDGE_METRICS " + bridge.snapshot());
                                } catch (Exception e) {
                                    if (finished.getCount() > 0) throw new CompletionException(e);
                                }
                                return null;
                            });
            try (PeerChannel peer =
                    PeerChannel.connect(
                            FramedTcp.connect(
                                    new InetSocketAddress("127.0.0.1", listener.getLocalPort()),
                                    Duration.ofSeconds(30)),
                            PeerChannel.Path.DIRECT_TCP,
                            PASSWORD,
                            22,
                            address(CLIENT_IP),
                            11)) {
                int status = exchange(peer, 49152, 1, 0);
                System.out.println(
                        "PAPER_STATUS_THROUGH_AUTHENTICATED_ETHERNET_AND_VIRTUAL_TCP_OK bytes="
                                + status);
                int login = exchange(peer, 49153, 2, 1);
                System.out.println("PAPER_ONLINE_MODE_ENCRYPTION_REQUEST_OK bytes=" + login);
                finished.countDown();
                worker.get(10, TimeUnit.SECONDS);
            } finally {
                finished.countDown();
            }
        }
    }
}
