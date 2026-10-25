package io.github.gringoestrangeiro.openradplugin.network;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import org.junit.jupiter.api.Test;

class PublicNetworksTest {
    @Test
    void requestAndResponseMatchCanonicalTagsAndCorrelation() {
        byte[] request = PublicNetworks.request("Minecraft", 17, 4);
        var f = fields(request);
        assertEquals(43, number(f, TlvCodec.CLIENT_OP));
        var body = fields(field(f, 0x1332));
        assertEquals(17, wide(body, 0x02000340));
        assertEquals(4, wide(body, 0x0200030f));
        byte[] response =
                concat(
                        u32(TlvCodec.SERVER_OP, 45),
                        tlv(
                                0x1334,
                                concat(
                                        u64(0x02000340, 17),
                                        u64(0x0200030f, 9),
                                        tlv(
                                                0x1333,
                                                concat(
                                                        text(0x03000306, "Synthetic"),
                                                        u32(0x01000341, 12))))));
        var page = PublicNetworks.parse(response, 17);
        assertEquals(9, page.cursor());
        assertEquals(new PublicNetworks.Network("Synthetic", 12), page.networks().getFirst());
        assertThrows(IllegalArgumentException.class, () -> PublicNetworks.parse(response, 18));
    }

    @Test
    void addressesAndTextRejectAmbiguousOrMalformedInput() {
        for (String ip :
                new String[] {
                    "127.1",
                    "1.2.3",
                    "0127.0.0.1",
                    "256.0.0.1",
                    "localhost",
                    "1..2.3",
                    "::ffff:localhost"
                }) assertThrows(IllegalArgumentException.class, () -> literal(ip), ip);
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.textValue(4, "\ud800"));
        assertEquals("127.0.0.1", literal("127.0.0.1").getHostAddress());
    }
}
