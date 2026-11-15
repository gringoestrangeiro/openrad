package io.github.gringoestrangeiro.openradplugin.transport;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

class ReliableUdpWireTest {
    /** A minimal independent wire peer, without the ReliableUdp receiver under test. */
    static final class Raw implements AutoCloseable {
        final DatagramSocket socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        final DatagramSocket local = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
        final ReliableUdp enet;

        Raw() throws Exception {
            socket.setSoTimeout(2000);
            Future<ReliableUdp> connected =
                    jobs.submit(
                            () ->
                                    ReliableUdp.connect(
                                            local,
                                            List.of(
                                                    (InetSocketAddress)
                                                            socket.getLocalSocketAddress()),
                                            71,
                                            Duration.ofSeconds(3)));
            byte[] punch = receive();
            assertEquals(7, punch.length);
            send(ReliableUdp.punch(71, local.getLocalPort(), socket.getLocalPort(), 'C'));
            byte[] connect;
            do {
                connect = receive();
            } while (connect.length == 7);
            assertEquals(48, connect.length);
            assertEquals(0x82, connect[8] & 255);
            assertEquals(WireChecksum.enet(connect), ByteBuffer.wrap(connect).getInt());
            byte[] verify = Arrays.copyOfRange(connect, 8, 44);
            verify[0] = (byte) 0x83;
            send(packet(verify));
            enet = connected.get(4, TimeUnit.SECONDS);
            enet.sustain();
            byte[] ack = receive();
            assertEquals(1, ack[8]);
        }

        byte[] receive() throws IOException {
            DatagramPacket p = new DatagramPacket(new byte[4096], 4096);
            socket.receive(p);
            return Arrays.copyOf(p.getData(), p.getLength());
        }

        void send(byte[] bytes) throws IOException {
            socket.send(new DatagramPacket(bytes, bytes.length, local.getLocalSocketAddress()));
        }

        void command(byte[] command) throws IOException {
            send(packet(command));
        }

        static byte[] packet(byte[] command) {
            byte[] b =
                    ByteBuffer.allocate(8 + command.length)
                            .putInt(0)
                            .putShort((short) 0x8000)
                            .putShort((short) 7)
                            .put(command)
                            .array();
            ByteBuffer.wrap(b).putInt(WireChecksum.enet(b));
            return b;
        }

        static byte[] reliable(int sequence, String text) {
            byte[] b = text.getBytes();
            return ByteBuffer.allocate(6 + b.length)
                    .put((byte) 0x86)
                    .put((byte) 0)
                    .putShort((short) sequence)
                    .putShort((short) b.length)
                    .put(b)
                    .array();
        }

        static byte[] fragment(
                int start, int count, int index, int total, int offset, String text) {
            byte[] b = text.getBytes();
            return ByteBuffer.allocate(24 + b.length)
                    .put((byte) 0x88)
                    .put((byte) 0)
                    .putShort((short) (start + index))
                    .putShort((short) start)
                    .putShort((short) b.length)
                    .putInt(count)
                    .putInt(index)
                    .putInt(total)
                    .putInt(offset)
                    .put(b)
                    .array();
        }

        void ack(int sequence) throws IOException {
            command(
                    ByteBuffer.allocate(8)
                            .put((byte) 1)
                            .put((byte) 0)
                            .putShort((short) 0)
                            .putShort((short) sequence)
                            .putShort((short) 7)
                            .array());
        }

        @Override
        public void close() {
            enet.close();
            socket.close();
            local.close();
            jobs.shutdownNow();
        }
    }

    @Test
    void wireFragmentsAndRetransmissionsPreserveSequencePayloadAndChecksum() throws Exception {
        try (Raw remote = new Raw()) {
            byte[] message = new byte[3000];
            for (int i = 0; i < message.length; i++) message[i] = (byte) i;
            remote.enet.send(message);
            List<byte[]> commands = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                byte[] packet = remote.receive();
                assertEquals(WireChecksum.enet(packet), ByteBuffer.wrap(packet).getInt());
                commands.add(Arrays.copyOfRange(packet, 8, packet.length));
            }
            for (int i = 0; i < 3; i++) {
                ByteBuffer c = ByteBuffer.wrap(commands.get(i));
                assertEquals(0x88, c.get() & 255);
                assertEquals(0, c.get());
                assertEquals(i + 1, c.getShort());
                assertEquals(1, c.getShort());
                int n = Short.toUnsignedInt(c.getShort());
                assertEquals(3, c.getInt());
                assertEquals(i, c.getInt());
                assertEquals(3000, c.getInt());
                assertEquals(i * 1368, c.getInt());
                byte[] part = new byte[n];
                c.get(part);
                assertArrayEquals(
                        Arrays.copyOfRange(
                                message, i * 1368, Math.min(message.length, (i + 1) * 1368)),
                        part);
            }
            remote.ack(2);
            remote.ack(3);
            assertFalse(remote.enet.ready(Duration.ofMillis(450)));
            byte[] retry = remote.receive();
            assertArrayEquals(commands.getFirst(), Arrays.copyOfRange(retry, 8, retry.length));
            remote.ack(1);
            assertFalse(remote.enet.ready(Duration.ofMillis(20)));
            assertTrue(remote.enet.hasRoom(65536));
        }
    }

    @Test
    void reliableReordersDeduplicatesAndRejectsCorruptDatagrams() throws Exception {
        try (Raw remote = new Raw()) {
            remote.command(Raw.reliable(2, "second"));
            remote.command(Raw.reliable(1, "first"));
            assertArrayEquals("first".getBytes(), remote.enet.receive(100));
            assertArrayEquals("second".getBytes(), remote.enet.receive(100));
            remote.command(Raw.reliable(1, "replay"));
            byte[] bad = Raw.packet(Raw.reliable(3, "corrupt"));
            bad[bad.length - 1] ^= 1;
            remote.send(bad);
            assertFalse(remote.enet.ready(Duration.ofMillis(20)));
            remote.command(Raw.reliable(3, "third"));
            assertArrayEquals("third".getBytes(), remote.enet.receive(100));
        }
    }

    @Test
    void fragmentsAssembleOutOfOrderAndRejectGapsBeforeDelivery() throws Exception {
        try (Raw remote = new Raw()) {
            remote.command(Raw.fragment(1, 2, 1, 8, 5, "678"));
            remote.command(Raw.fragment(1, 2, 0, 8, 0, "12345"));
            assertArrayEquals("12345678".getBytes(), remote.enet.receive(100));
            remote.command(Raw.fragment(3, 2, 0, 8, 0, "12"));
            remote.command(Raw.fragment(3, 2, 1, 8, 4, "5678"));
            assertThrows(IOException.class, () -> remote.enet.receive(100));
        }
    }

    @Test
    void fullWindowPreservesOldestMissingCommandAcrossAckHoles() throws Exception {
        try (Raw remote = new Raw()) {
            for (int i = 0; i < 256; i++) remote.enet.send(new byte[] {(byte) i});
            assertFalse(remote.enet.hasRoom(1));
            remote.ack(200);
            assertFalse(remote.enet.ready(Duration.ofMillis(10)));
            assertFalse(remote.enet.hasRoom(1));
            remote.ack(1);
            assertFalse(remote.enet.ready(Duration.ofMillis(10)));
            assertTrue(remote.enet.hasRoom(1));
        }
    }
}
