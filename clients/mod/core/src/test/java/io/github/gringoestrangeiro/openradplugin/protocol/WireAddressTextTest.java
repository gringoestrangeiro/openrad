package io.github.gringoestrangeiro.openradplugin.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Candidate addresses must be written exactly as Rust's IpAddr Display writes them. */
class WireAddressTextTest {
    private static String text(String literal) throws Exception {
        return Wire.addressText(InetAddress.getByName(literal));
    }

    @Test
    void ipv4IsDotted() throws Exception {
        assertEquals("192.0.2.10", text("192.0.2.10"));
        assertEquals("255.255.255.255", text("255.255.255.255"));
    }

    @Test
    void ipv6IsCompressedLikeRust() throws Exception {
        assertEquals("::", text("0:0:0:0:0:0:0:0"));
        assertEquals("::1", text("0:0:0:0:0:0:0:1"));
        assertEquals("2001:db8::1", text("2001:0db8:0:0:0:0:0:1"));
        assertEquals("2001:db8::1:0:0:1", text("2001:db8:0:0:1:0:0:1"));
        assertEquals("2001:0:0:1::1", text("2001:0:0:1:0:0:0:1"));
        // A single zero group is never compressed; the first of equal runs wins.
        assertEquals("2001:db8:0:1:1:1:1:1", text("2001:db8:0:1:1:1:1:1"));
        assertEquals("1::2:0:0:3:4", text("1:0:0:2:0:0:3:4"));
        assertEquals("fe80::", text("fe80:0:0:0:0:0:0:0"));
        assertEquals("2804:7f0:6801:55bc:d5a2:3a99:23f8:b882", text("2804:07f0:6801:55bc:d5a2:3a99:23f8:b882"));
    }

    @Test
    void interfaceZoneIsNeverWritten() throws Exception {
        byte[] bytes = InetAddress.getByName("2001:db8::abcd").getAddress();
        Inet6Address scoped = Inet6Address.getByAddress(null, bytes, 3);
        assertTrue(scoped.getHostAddress().contains("%"), "precondition: Java appends the zone");
        assertEquals("2001:db8::abcd", Wire.addressText(scoped));
    }

    @Test
    void textRoundTripsThroughTheStrictLiteralParser() throws Exception {
        for (String literal : List.of("2001:db8::1:0:0:1", "::1", "192.0.2.1", "fe80::1"))
            assertEquals(InetAddress.getByName(literal), Wire.literal(text(literal)));
        assertEquals(
                new InetSocketAddress(InetAddress.getByName("2001:db8::5"), 1).getAddress(),
                Wire.literal(Wire.addressText(InetAddress.getByName("2001:db8::5"))));
    }
}
