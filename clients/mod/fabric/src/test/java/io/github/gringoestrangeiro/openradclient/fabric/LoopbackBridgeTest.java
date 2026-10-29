package io.github.gringoestrangeiro.openradclient.fabric;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LoopbackBridgeTest {
    private static InetSocketAddress vpn() throws IOException {
        return new InetSocketAddress(InetAddress.getByAddress(new byte[]{26, 4, 5, 6}), 25565);
    }

    @Test void ordinaryAndUnresolvedAddressesAreUntouched() throws IOException {
        try (var bridge = new LoopbackBridge((ip, port) -> { fail("ordinary socket intercepted"); return null; })) {
            var internet = new InetSocketAddress(InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), 25565);
            var hostname = InetSocketAddress.createUnresolved("minecraft.example", 25565);
            var ipv6 = new InetSocketAddress(InetAddress.getByName("::1"), 25565);
            assertSame(internet, bridge.route(internet));
            assertSame(hostname, bridge.route(hostname));
            assertSame(ipv6, bridge.route(ipv6));
        }
    }

    @Test void transfersBothWaysWithFixedDestinationAndConsumesEndpoint() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);
        try (var bridge = new LoopbackBridge((ip, port) -> {
            assertEquals(vpn().getAddress(), ip);
            assertEquals(25565, port);
            opened.incrementAndGet();
            var response = new PipedInputStream(32_768);
            var output = new PipedOutputStream(response);
            return new LoopbackBridge.Tunnel() {
                public InputStream input() { return response; }
                public OutputStream output() { return output; }
                public void close() throws IOException {
                    try { response.close(); output.close(); }
                    finally { closed.countDown(); }
                }
            };
        })) {
            InetSocketAddress local = bridge.route(vpn());
            assertEquals("127.0.0.1", local.getAddress().getHostAddress());
            assertNotEquals(25565, local.getPort());
            try (Socket minecraft = new Socket()) {
                minecraft.connect(local, 2_000);
                minecraft.setSoTimeout(8_000);
                byte[] data = new byte[65_537];
                for (int i = 0; i < data.length; i++) data[i] = (byte) i;
                Thread writer = Thread.ofVirtual().start(() -> {
                    try { minecraft.getOutputStream().write(data); }
                    catch (IOException failure) { throw new AssertionError(failure); }
                });
                assertArrayEquals(data, minecraft.getInputStream().readNBytes(data.length));
                writer.join(8_000);
                assertFalse(writer.isAlive());
                assertEquals(1, opened.get());
                assertThrows(IOException.class, () -> {
                    try (Socket second = new Socket()) { second.connect(local, 500); }
                });
            }
            assertTrue(closed.await(8, TimeUnit.SECONDS));
        }
    }

    @Test void tunnelFailureClosesLocalConnectionWithoutSystemFallback() throws Exception {
        try (var bridge = new LoopbackBridge((ip, port) -> { throw new IOException("VPN offline"); })) {
            try (Socket minecraft = new Socket()) {
                minecraft.connect(bridge.route(vpn()), 2_000);
                minecraft.setSoTimeout(2_000);
                assertEquals(-1, minecraft.getInputStream().read());
            }
        }
    }

    @Test void localHalfCloseRetainsServerResponse() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        try (var bridge = new LoopbackBridge((ip, port) -> {
            var input = new PipedInputStream(32_768);
            var reply = new PipedOutputStream(input);
            var request = new ByteArrayOutputStream();
            return new LoopbackBridge.Tunnel() {
                public InputStream input() { return input; }
                public OutputStream output() { return request; }
                public void shutdownOutput() throws IOException {
                    reply.write(("response:" + request.toString(java.nio.charset.StandardCharsets.UTF_8))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    reply.close();
                }
                public void close() throws IOException {
                    try { input.close(); reply.close(); }
                    finally { completed.countDown(); }
                }
            };
        })) {
            try (Socket minecraft = new Socket()) {
                minecraft.connect(bridge.route(vpn()), 2_000);
                minecraft.setSoTimeout(2_000);
                minecraft.getOutputStream().write("request".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                minecraft.shutdownOutput();
                assertEquals("response:request", new String(minecraft.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
            assertTrue(completed.await(2, TimeUnit.SECONDS));
        }
    }

    @Test void unusedEndpointExpiresAndReleasesCapacity() throws Exception {
        try (var bridge = new LoopbackBridge((ip, port) -> { fail("unused listener accepted"); return null; }, 1)) {
            InetSocketAddress expired = bridge.route(vpn());
            Thread.sleep(5_200);
            assertThrows(IOException.class, () -> {
                try (Socket socket = new Socket()) { socket.connect(expired, 500); }
            });
            assertTrue(bridge.route(vpn()).getAddress().isLoopbackAddress());
        }
    }

    @Test void disconnectCancelsPendingTunnelSetup() throws Exception {
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (var bridge = new LoopbackBridge((ip, port) -> {
            opening.countDown();
            try { Thread.sleep(30_000); }
            catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IOException(expected);
            }
            throw new IOException("not cancelled");
        })) {
            try (Socket minecraft = new Socket()) {
                minecraft.connect(bridge.route(vpn()), 2_000);
                minecraft.setSoTimeout(2_000);
                assertTrue(opening.await(2, TimeUnit.SECONDS));
                bridge.closeActive();
                assertEquals(-1, minecraft.getInputStream().read());
                assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test void boundedPendingConnectionsReleaseCapacityWhenDisconnected() throws Exception {
        try (var bridge = new LoopbackBridge((ip, port) -> { fail("no accepted socket"); return null; }, 1)) {
            var unused = bridge.route(vpn());
            assertThrows(IllegalStateException.class, () -> bridge.route(vpn()));
            bridge.closeActive();
            // A completed TCP handshake may briefly remain queued after a listener
            // closes on Linux; verify the abandoned port stops accepting within a
            // bounded deadline instead of depending on an immediate refusal.
            boolean refused = false;
            for (int attempt = 0; attempt < 20; attempt++) {
                try (Socket socket = new Socket()) {
                    socket.connect(unused, 150);
                } catch (IOException closedPort) {
                    refused = true;
                    break;
                }
                Thread.sleep(25);
            }
            assertTrue(refused, "cancelled local listener must stop accepting clients");
            assertTrue(bridge.route(vpn()).getAddress().isLoopbackAddress());
        }
    }

    @Test void closingBridgeRefusesSubsequentVpnConnections() throws Exception {
        var bridge = new LoopbackBridge((ip, port) -> { throw new IOException(); });
        bridge.close();
        assertThrows(IllegalStateException.class, () -> bridge.route(vpn()));
    }

    @Test void unresolvedIpv4LiteralIsInterceptedWithoutDns() throws Exception {
        try (var bridge = new LoopbackBridge((ip, port) -> { throw new IOException("VPN offline"); })) {
            InetSocketAddress literal = InetSocketAddress.createUnresolved("26.4.5.6", 25565);
            assertTrue(bridge.route(literal).getAddress().isLoopbackAddress());
            InetSocketAddress ordinary = InetSocketAddress.createUnresolved("192.168.1.8", 25565);
            InetSocketAddress hostname = InetSocketAddress.createUnresolved("26.example.invalid", 25565);
            assertSame(ordinary, bridge.route(ordinary));
            assertSame(hostname, bridge.route(hostname));
        }
    }
}
