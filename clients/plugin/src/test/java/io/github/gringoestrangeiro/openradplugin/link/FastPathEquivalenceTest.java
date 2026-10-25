package io.github.gringoestrangeiro.openradplugin.link;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

class FastPathEquivalenceTest {
    private static int referenceTcpChecksum(int sourceIp, int destinationIp, byte[] segment) {
        long sum =
                ((sourceIp >>> 16) & 0xffff)
                        + (sourceIp & 0xffffL)
                        + ((destinationIp >>> 16) & 0xffff)
                        + (destinationIp & 0xffffL)
                        + 6
                        + segment.length;
        for (int i = 0; i < segment.length; i += 2) {
            int high = segment[i] & 0xff;
            int low = i + 1 < segment.length ? segment[i + 1] & 0xff : 0;
            sum += (high << 8) | low;
        }
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return (int) (~sum) & 0xffff;
    }

    @Test
    void checksumsMatchTheReferenceForOddAndEvenRanges() {
        Random random = new Random(0x51);
        for (int length = 0; length < 400; length++) {
            byte[] bytes = new byte[length + 4];
            random.nextBytes(bytes);
            int a = random.nextInt(), b = random.nextInt();
            byte[] slice = Arrays.copyOfRange(bytes, 2, 2 + length);
            int expected = referenceTcpChecksum(a, b, slice);
            assertEquals(expected, Checksum.tcpChecksum(a, b, slice));
            assertEquals(expected, Checksum.tcpChecksum(a, b, bytes, 2, length));
        }
    }

    @Test
    void singleArrayFrameEncodingMatchesTheLayeredRecords() {
        Random random = new Random(0xe7);
        for (int i = 0; i < 2000; i++) {
            byte[] options = new byte[4 * random.nextInt(3)];
            random.nextBytes(options);
            // Up to the largest Ethernet frame: 1504 payload bytes minus IPv4/TCP headers.
            byte[] payload = new byte[random.nextInt(1465 - options.length)];
            random.nextBytes(payload);
            TcpSegment tcp =
                    new TcpSegment(
                            1 + random.nextInt(65535),
                            1 + random.nextInt(65535),
                            random.nextLong() & 0xffff_ffffL,
                            random.nextLong() & 0xffff_ffffL,
                            random.nextInt(256) & ~TcpSegment.URG,
                            random.nextInt(65536),
                            options,
                            payload);
            byte[] dst = new byte[6], src = new byte[6];
            random.nextBytes(dst);
            random.nextBytes(src);
            int sourceIp = random.nextInt(), destinationIp = random.nextInt();
            byte[] layered =
                    new EthernetFrame(
                                    dst,
                                    src,
                                    EthernetFrame.IPV4,
                                    new Ipv4Packet(
                                                    sourceIp,
                                                    destinationIp,
                                                    64,
                                                    tcp.encode(sourceIp, destinationIp))
                                            .encode())
                            .encode();
            assertArrayEquals(
                    layered, tcp.encodeIpv4Frame(dst, src, sourceIp, destinationIp, 64), "" + i);
            assertEquals(payload.length, tcp.payloadLength());
            int from = random.nextInt(payload.length + 1);
            assertArrayEquals(
                    Arrays.copyOfRange(payload, from, payload.length),
                    tcp.copyPayload(from, payload.length));
            // Parsing the embedded segment in place equals parsing a copied segment.
            byte[] segment = Arrays.copyOfRange(layered, 34, layered.length);
            TcpSegment copied = TcpSegment.parse(segment, sourceIp, destinationIp);
            TcpSegment inPlace =
                    TcpSegment.parse(layered, 34, segment.length, sourceIp, destinationIp);
            assertArrayEquals(copied.payload(), inPlace.payload());
            assertArrayEquals(copied.options(), inPlace.options());
            assertEquals(copied.sequence(), inPlace.sequence());
            assertEquals(copied.flags(), inPlace.flags());
            assertEquals(copied.window(), inPlace.window());
            assertEquals(copied.acknowledgement(), inPlace.acknowledgement());
        }
    }
}
