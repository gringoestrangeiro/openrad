package io.github.gringoestrangeiro.openradclient.interop;

import io.github.gringoestrangeiro.openradclient.virtual.OutboundTcpStack;
import io.github.gringoestrangeiro.openradclient.interop.reference.ReferenceServerTcpBridge;
import io.github.gringoestrangeiro.openradclient.interop.reference.ReferenceInboundFirewall;
import io.github.gringoestrangeiro.openradplugin.crypto.Channel;
import io.github.gringoestrangeiro.openradplugin.link.*;
import io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-check the new client against a separately implemented inbound server endpoint. */
@Timeout(35)
class MinecraftTcpInteropTest {
    private static final int CLIENT = 0x1a0a0001, SERVER = 0x1a0a0002;
    private static final Inet4Address CLIENT_IP = ip(CLIENT), SERVER_IP = ip(SERVER);
    private static final ReferenceInboundFirewall.PeerGrant GRANT =
            new ReferenceInboundFirewall.PeerGrant(1234, true, Set.of("isolated-test"),
                    CLIENT, OpenRadAddress.mac(CLIENT));

    private static Inet4Address ip(int value) {
        try { return (Inet4Address) InetAddress.getByAddress(new byte[]{
                (byte)(value >>> 24), (byte)(value >>> 16), (byte)(value >>> 8), (byte)value}); }
        catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
    }

    private static final class EncryptedLink implements AutoCloseable {
        final AtomicReference<ReferenceServerTcpBridge> server = new AtomicReference<>();
        final AtomicReference<OutboundTcpStack> client = new AtomicReference<>();
        final Channel clientCipher, serverCipher;
        final AtomicBoolean loseSyn = new AtomicBoolean(), loseData = new AtomicBoolean();
        final AtomicInteger records = new AtomicInteger(), losses = new AtomicInteger();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        EncryptedLink(boolean loss) {
            byte[] testKey = new byte[32]; Arrays.fill(testKey, (byte)0x53);
            clientCipher = new Channel(testKey); serverCipher = new Channel(testKey);
            loseSyn.set(loss); loseData.set(loss);
        }
        // Each CBC direction is serialized, just as one peer-channel writer owns each direction.
        synchronized void fromClient(Inet4Address destination, byte[] frame) throws IOException {
            assertEquals(SERVER_IP, destination);
            EthernetFrame eth = EthernetFrame.parse(frame);
            if (eth.etherType() == EthernetFrame.IPV4) {
                Ipv4Packet packet = Ipv4Packet.parse(eth.payload());
                TcpSegment tcp = TcpSegment.parse(packet.tcpBytes(), CLIENT, SERVER);
                if ((tcp.flags() & TcpSegment.SYN) != 0 && loseSyn.compareAndSet(true, false)
                        || tcp.payload().length > 0 && loseData.compareAndSet(true, false)) {
                    losses.incrementAndGet(); return;
                }
            }
            byte[] wire = clientCipher.encrypt(TunnelCodec.encodeFrame(frame));
            TunnelCodec.Frames decoded = (TunnelCodec.Frames)TunnelCodec.decode(serverCipher.decrypt(wire));
            records.incrementAndGet();
            for (byte[] received : decoded.frames()) {
                if (!server.get().accept(GRANT, received)) throw new IOException("reference receive queue full");
            }
        }
        synchronized void fromServer(long rid, byte[] frame) {
            try {
                assertEquals(GRANT.rid(), rid);
                byte[] wire = serverCipher.encrypt(TunnelCodec.encodeFrame(frame));
                TunnelCodec.Frames decoded = (TunnelCodec.Frames)TunnelCodec.decode(clientCipher.decrypt(wire));
                records.incrementAndGet();
                for (byte[] received : decoded.frames()) client.get().acceptEthernet(SERVER_IP, received);
            } catch (Throwable failure) { failures.add(failure); }
        }
        public void close() { clientCipher.close(); serverCipher.close(); }
    }

    @Test void minecraftStatusAndPingCrossEncryptedTunnelAfterPacketLoss() throws Exception {
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
             ServerSocket backend = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             EncryptedLink link = new EncryptedLink(true);
             OutboundTcpStack client = new OutboundTcpStack(CLIENT_IP, link::fromClient);
             ReferenceServerTcpBridge server = new ReferenceServerTcpBridge(SERVER,
                     OpenRadAddress.mac(SERVER), 25565,
                     new InetSocketAddress(backend.getInetAddress(), backend.getLocalPort()), link::fromServer)) {
            link.client.set(client); link.server.set(server);
            String json = "{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},"
                    + "\"players\":{\"max\":20,\"online\":0},\"description\":{\"text\":\"OpenRad isolated TCP test\"}}";
            Future<?> service = workers.submit(() -> {
                try (Socket accepted = backend.accept()) {
                    accepted.setSoTimeout(15_000);
                    DataInputStream input = new DataInputStream(accepted.getInputStream());
                    DataInputStream handshake = new DataInputStream(new ByteArrayInputStream(readPacket(input)));
                    assertEquals(0, readVarInt(handshake)); assertEquals(767, readVarInt(handshake));
                    assertEquals("26.10.0.2", readString(handshake));
                    assertEquals(25565, handshake.readUnsignedShort()); assertEquals(1, readVarInt(handshake));
                    assertArrayEquals(new byte[]{0}, readPacket(input));
                    ByteArrayOutputStream status = new ByteArrayOutputStream();
                    writeVarInt(status, 0); writeString(status, json);
                    writePacket(accepted.getOutputStream(), status.toByteArray());
                    byte[] ping = readPacket(input); assertEquals(9, ping.length); assertEquals(1, ping[0]);
                    writePacket(accepted.getOutputStream(), ping);
                } catch (IOException error) { throw new UncheckedIOException(error); }
            });
            try (OutboundTcpStack.Connection connection = client.connect(SERVER_IP, 25565, Duration.ofSeconds(12))) {
                ByteArrayOutputStream hello = new ByteArrayOutputStream();
                writeVarInt(hello, 0); writeVarInt(hello, 767); writeString(hello, "26.10.0.2");
                new DataOutputStream(hello).writeShort(25565); writeVarInt(hello, 1);
                writePacket(connection.output(), hello.toByteArray());
                writePacket(connection.output(), new byte[]{0});
                DataInputStream response = new DataInputStream(new ByteArrayInputStream(readPacket(connection.input())));
                assertEquals(0, readVarInt(response)); assertEquals(json, readString(response));
                ByteArrayOutputStream ping = new ByteArrayOutputStream(); ping.write(1);
                new DataOutputStream(ping).writeLong(0x123456789abcdef0L);
                writePacket(connection.output(), ping.toByteArray());
                assertArrayEquals(ping.toByteArray(), readPacket(connection.input()));
                assertEquals(-1, connection.input().read(), "server FIN must reach the game stream");
            }
            service.get(5, TimeUnit.SECONDS);
            assertEquals(2, link.losses.get(), "test must exercise SYN and data retransmission");
            assertTrue(link.records.get() > 8); assertTrue(link.failures.isEmpty(), link.failures.toString());
        }
    }

    @Test void halfCloseKeepsServerReplyAndLargeStreamsRemainIntact() throws Exception {
        byte[] payload = new byte[512 * 1024]; new Random(42).nextBytes(payload);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
             ServerSocket backend = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             EncryptedLink link = new EncryptedLink(false);
             OutboundTcpStack client = new OutboundTcpStack(CLIENT_IP, link::fromClient);
             ReferenceServerTcpBridge server = new ReferenceServerTcpBridge(SERVER,
                     OpenRadAddress.mac(SERVER), 25565,
                     new InetSocketAddress(backend.getInetAddress(), backend.getLocalPort()), link::fromServer)) {
            link.client.set(client); link.server.set(server);
            Future<?> service = workers.submit(() -> {
                try (Socket accepted = backend.accept()) {
                    accepted.setSoTimeout(15_000);
                    assertArrayEquals(payload, accepted.getInputStream().readAllBytes());
                    accepted.getOutputStream().write(digest); accepted.getOutputStream().flush();
                } catch (IOException error) { throw new UncheckedIOException(error); }
            });
            try (OutboundTcpStack.Connection connection = client.connect(SERVER_IP, 25565, Duration.ofSeconds(10))) {
                connection.output().write(payload); connection.output().flush(); connection.shutdownOutput();
                assertArrayEquals(digest, connection.input().readAllBytes());
            }
            service.get(5, TimeUnit.SECONDS);
            assertTrue(link.records.get() > 400); assertTrue(link.failures.isEmpty(), link.failures.toString());
        }
    }
    private static int readVarInt(InputStream in) throws IOException {
        int result = 0;
        for (int n = 0; n < 5; n++) {
            int value = in.read(); if (value < 0) throw new EOFException();
            result |= (value & 127) << (7*n); if ((value & 128) == 0) return result;
        }
        throw new IOException("invalid VarInt");
    }
    private static void writeVarInt(OutputStream out, int value) throws IOException {
        do { int next = value & 127; value >>>= 7; out.write(value == 0 ? next : next | 128); } while (value != 0);
    }
    private static byte[] readPacket(InputStream in) throws IOException {
        int size = readVarInt(in); if (size < 0 || size > 65536) throw new IOException("test packet too large");
        byte[] packet = in.readNBytes(size); if (packet.length != size) throw new EOFException(); return packet;
    }
    private static void writePacket(OutputStream out, byte[] packet) throws IOException {
        writeVarInt(out, packet.length); out.write(packet); out.flush();
    }
    private static String readString(InputStream in) throws IOException {
        int size = readVarInt(in); return new String(in.readNBytes(size), StandardCharsets.UTF_8);
    }
    private static void writeString(OutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); writeVarInt(out, bytes.length); out.write(bytes);
    }
}
