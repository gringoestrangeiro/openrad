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
