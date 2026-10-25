package io.github.gringoestrangeiro.openradplugin.tunnel;

import static io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.util.*;

class TunnelCodecTest {
    @Test
    void fixedEnvelopeAndKeepaliveWireBytes() {
        byte[] frame = new byte[14];
        Arrays.fill(frame, (byte) 0xaa);
        byte[] encoded = TunnelCodec.encodeFrame(frame);
        assertEquals(
                "0000000000000e000000aaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                HexFormat.of().formatHex(encoded));
        assertArrayEquals(
                frame, ((TunnelCodec.Frames) TunnelCodec.decode(encoded)).frames().getFirst());
        assertEquals("0003ffffffff", HexFormat.of().formatHex(TunnelCodec.keepalive(-1, false)));
        assertEquals(
                new TunnelCodec.Keepalive(-1, true),
                TunnelCodec.decode(TunnelCodec.keepalive(-1, true)));
    }

    @Test
    void headerPathsAndPrefixDecodeMatchTheOriginalEnvelope() {
        Random random = new Random(0x7c);
        for (int length = 14; length <= 65535; length += length < 100 ? 1 : 997) {
            byte[] frame = new byte[length];
            random.nextBytes(frame);
            byte[] reference =
                    java.nio.ByteBuffer.allocate(length + 10)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .putShort((short) 0)
                            .putInt(0)
                            .putInt(length)
                            .put(frame)
                            .array();
            assertArrayEquals(reference, TunnelCodec.encodeFrame(frame));
            assertArrayEquals(
                    Arrays.copyOf(reference, 10), TunnelCodec.frameHeader(frame.length));
            byte[] padded = Arrays.copyOf(reference, reference.length + 1 + random.nextInt(24));
            var decoded = (TunnelCodec.Frames) TunnelCodec.decode(padded, reference.length);
            assertArrayEquals(frame, decoded.ownedFrames().getFirst());
            assertArrayEquals(frame, decoded.frames().getFirst());
        }
        assertThrows(IllegalArgumentException.class, () -> TunnelCodec.frameHeader(13));
        assertThrows(IllegalArgumentException.class, () -> TunnelCodec.frameHeader(65536));
        assertEquals(
                new TunnelCodec.Keepalive(9, false),
                TunnelCodec.decode(Arrays.copyOf(TunnelCodec.keepalive(9, false), 20), 6));
    }

    @Test
    void framesCopyDefensivelyOnlyAtThePublicBoundary() {
        byte[] frame = new byte[14];
        var frames = new TunnelCodec.Frames(List.of(frame));
        frame[0] = 1;
        assertEquals(0, frames.frames().getFirst()[0]);
        frames.frames().getFirst()[1] = 1;
        assertEquals(0, frames.frames().getFirst()[1]);
        assertThrows(UnsupportedOperationException.class, () -> frames.frames().clear());
        assertThrows(UnsupportedOperationException.class, () -> frames.ownedFrames().clear());
        assertEquals(frames, frames);
    }

    @Test
    void invalidLaterEnvelopeRejectsEntireRecord() {
        byte[] first = TunnelCodec.encodeFrame(new byte[14]),
                second = TunnelCodec.encodeFrame(new byte[14]);
        byte[] both = TunnelCodec.concat(first, Arrays.copyOfRange(second, 2, second.length));
        assertEquals(2, ((TunnelCodec.Frames) TunnelCodec.decode(both)).frames().size());
        both[first.length] = 1;
        assertThrows(IllegalArgumentException.class, () -> TunnelCodec.decode(both));
        for (int end = 1; end < first.length; end++) {
            byte[] truncated = Arrays.copyOf(first, end);
            assertThrows(IllegalArgumentException.class, () -> TunnelCodec.decode(truncated));
        }
    }

    @Test
    void serviceChecksIdentityVersionStatusAndMac() throws Exception {
        Inet4Address own = (Inet4Address) InetAddress.getByName("26.1.2.3");
        byte[] mac = TunnelCodec.mac(own);
        assertArrayEquals(new byte[] {2, 26, 26, 1, 2, 3}, mac);
        var accepted = TunnelCodec.acceptSyn(TunnelCodec.syn(11, mac), 11, mac);
        var ack = TunnelCodec.synack(accepted.response());
        TunnelCodec.acceptAck(ack.response(), 8);
        assertThrows(
                IllegalArgumentException.class,
                () -> TunnelCodec.acceptSyn(TunnelCodec.syn(11, mac), 12, mac));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TunnelCodec.synack(
                                TunnelCodec.concat(
                                        u64(0x02000303, 1),
                                        u32(0x01000302, 8),
                                        encode(0x09000304, mac))));
    }
}
