package io.github.gringoestrangeiro.openradplugin.transport;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

class UdpDiscoveryTest {
    static byte[] response(byte[] transaction, int port) {
        return ByteBuffer.allocate(32)
                .putShort((short) 0x0101)
                .putShort((short) 12)
                .put(transaction)
                .putShort((short) 1)
                .putShort((short) 8)
                .put((byte) 0)
                .put((byte) 1)
                .putShort((short) port)
                .put(new byte[] {1, 2, 3, 4})
                .array();
    }

    @Test
    void twoServersAreQueriedBeforeWaitingAndReorderedResponsesStayCorrelated() throws Exception {
        try (DatagramSocket local = new DatagramSocket(0);
                DatagramSocket a = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                DatagramSocket b = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            a.setSoTimeout(2000);
            b.setSoTimeout(2000);
            Future<UdpDiscovery.Mapping> task =
                    jobs.submit(
                            () ->
                                    UdpDiscovery.discoverAt(
                                            local,
                                            List.of(
                                                    (InetSocketAddress) a.getLocalSocketAddress(),
                                                    (InetSocketAddress) b.getLocalSocketAddress()),
                                            Duration.ofSeconds(2)));
            DatagramPacket first = new DatagramPacket(new byte[100], 100),
                    second = new DatagramPacket(new byte[100], 100);
            a.receive(first);
            b.receive(second);
            byte[] bad = response(Arrays.copyOfRange(first.getData(), 4, 20), 1234);
            b.send(new DatagramPacket(bad, bad.length, second.getSocketAddress()));
            byte[] rb = response(Arrays.copyOfRange(second.getData(), 4, 20), 1234);
            b.send(new DatagramPacket(rb, rb.length, second.getSocketAddress()));
            byte[] ra = response(Arrays.copyOfRange(first.getData(), 4, 20), 4321);
            a.send(new DatagramPacket(ra, ra.length, first.getSocketAddress()));
            var mapping = task.get(3, TimeUnit.SECONDS);
            assertEquals(UdpDiscovery.Agreement.VARIES, mapping.agreement());
            assertEquals(4321, mapping.endpoint().getPort());
        }
    }

    @Test
    void lostRequestsAreResentAndLoneObservationWaitsUntilDeadline() throws Exception {
        try (DatagramSocket local = new DatagramSocket(0);
                DatagramSocket a = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                DatagramSocket b = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            a.setSoTimeout(1500);
            Future<UdpDiscovery.Mapping> task =
                    jobs.submit(
                            () ->
                                    UdpDiscovery.discoverAt(
                                            local,
                                            List.of(
                                                    (InetSocketAddress) a.getLocalSocketAddress(),
                                                    (InetSocketAddress) b.getLocalSocketAddress()),
                                            Duration.ofMillis(700)));
            long began = System.nanoTime();
            DatagramPacket p = new DatagramPacket(new byte[100], 100);
            a.receive(p);
            a.receive(p);
            byte[] reply = response(Arrays.copyOfRange(p.getData(), 4, 20), 1234);
            a.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
            assertFalse(task.isDone());
            assertEquals(
                    UdpDiscovery.Agreement.ONE_SERVER, task.get(2, TimeUnit.SECONDS).agreement());
            assertTrue(System.nanoTime() - began >= 650_000_000L);
        }
    }

    @Test
    void malformedMappingCannotBecomeACandidate() {
        byte[] transaction = new byte[16];
        assertEquals(
                1234,
                UdpDiscovery.parseResponse(response(transaction, 1234), transaction).getPort());
        byte[] bad = response(transaction, 0);
        assertThrows(
                IllegalArgumentException.class, () -> UdpDiscovery.parseResponse(bad, transaction));
        bad[0] = 0;
        assertThrows(
                IllegalArgumentException.class, () -> UdpDiscovery.parseResponse(bad, transaction));
    }
}
