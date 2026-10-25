package io.github.gringoestrangeiro.openradplugin.discovery;

import static io.github.gringoestrangeiro.openradplugin.link.Checksum.*;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.link.EthernetFrame;
import io.github.gringoestrangeiro.openradplugin.protocol.Wire;
import io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Timeout(15)
class LanDiscoveryTest {
    private static final Inet4Address VIP = (Inet4Address) Wire.literal("26.1.2.3");

    private static LanAdvertisement parse(String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        return LanAdvertisement.parse(data, 0, data.length);
    }

    @Test
    void preservesMotdAndBuildsValidMulticastWithVirtualEndpoint() {
        var ad = parse("[MOTD]§aENTRE AQUI!!! — ação[/MOTD][AD]25575[/AD]");
        byte[] raw = ad.frame(VIP, 25565, 1234);
        var eth = EthernetFrame.parse(raw);
        assertArrayEquals(new byte[] {1, 0, 0x5e, 0, 2, 0x3c}, eth.destinationMac());
        assertArrayEquals(TunnelCodec.mac(VIP), eth.sourceMac());
        byte[] ip = eth.payload();
        assertEquals(0x45, ip[0]);
        assertEquals(1, ip[8]);
        assertEquals(17, ip[9]);
        assertEquals(ip.length, u16(ip, 2));
        assertEquals(0x4000, u16(ip, 6));
        assertEquals(0x1a010203, u32(ip, 12));
        assertEquals(0xe000023c, u32(ip, 16));
        assertEquals(0, onesComplement(ip, 0, 20));
        assertEquals(4445, u16(ip, 20));
        assertEquals(4445, u16(ip, 22));
        assertEquals(ip.length - 20, u16(ip, 24));
        assertNotEquals(0, u16(ip, 26));
        byte[] pseudo = new byte[ip.length - 8];
        System.arraycopy(ip, 12, pseudo, 0, 8);
        pseudo[9] = 17;
        put16(pseudo, 10, ip.length - 20);
        System.arraycopy(ip, 20, pseudo, 12, ip.length - 20);
        assertEquals(0, onesComplement(pseudo, 0, pseudo.length));
        assertEquals(
                "[MOTD]§aENTRE AQUI!!! — ação[/MOTD][AD]25565[/AD]",
                new String(ip, 28, ip.length - 28, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsMalformedOversizedAndAmbiguousMessages() {
        for (String value :
                List.of(
                        "",
                        "[MOTD]x[/MOTD][AD]0[/AD]",
                        "[MOTD]x[/MOTD][AD]65536[/AD]",
                        "[MOTD]x[/MOTD][AD]-1[/AD]",
                        "[MOTD]x[/MOTD][AD]25565[/AD]garbage",
                        "[MOTD]x[/MOTD][AD]1[/AD][MOTD]y[/MOTD][AD]2[/AD]",
                        "[MOTD]x\u0000y[/MOTD][AD]25565[/AD]",
                        "[MOTD]" + "a".repeat(1400) + "[/MOTD][AD]25565[/AD]"))
            assertThrows(IllegalArgumentException.class, () -> parse(value));
        byte[] invalid = "[MOTD]x[/MOTD][AD]25565[/AD]".getBytes(StandardCharsets.UTF_8);
        invalid[6] = (byte) 0xc0;
        assertThrows(
                IllegalArgumentException.class,
                () -> LanAdvertisement.parse(invalid, 0, invalid.length));
        assertEquals("x y", parse("[MOTD]x\r\ny[/MOTD][AD]25565[/AD]").motd());
        var odd = parse("[MOTD]odd[/MOTD][AD]1[/AD]");
        assertTrue(odd.frame(VIP, 65535, 0).length <= 1514);
    }

    @SuppressWarnings(
            "try") // Deliberately verify early close before try-with-resources repeats it.
    @Test
    void capturesRealLocalMulticastSharesSocketRejectsOtherPortsAndCloses() throws Exception {
        int port;
        try (DatagramSocket reservation = new DatagramSocket(0)) {
            port = reservation.getLocalPort();
        }
        try (var receiver = new LanDiscovery(VIP, 25575, 25565, port);
                var shared = new LanDiscovery(VIP, 25575, 25565, port);
                DatagramSocket sender = new DatagramSocket()) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            byte[] valid =
                    "[MOTD]ENTRE AQUI!!![/MOTD][AD]25575[/AD]".getBytes(StandardCharsets.UTF_8);
            while ((receiver.snapshot().captured() == 0 || shared.snapshot().captured() == 0)
                    && System.nanoTime() < deadline) {
                sender.send(
                        new DatagramPacket(
                                valid,
                                valid.length,
                                InetAddress.getByName(LanAdvertisement.GROUP),
                                port));
                Thread.sleep(100);
            }
            assertTrue(
                    receiver.snapshot().captured() > 0, "local multicast never reached receiver");
            assertTrue(shared.snapshot().captured() > 0, "SO_REUSEADDR listener missed multicast");
            byte[] frame = receiver.frame(System.nanoTime());
            assertNotNull(frame);
            assertEquals(
                    "[MOTD]ENTRE AQUI!!![/MOTD][AD]25565[/AD]",
                    new String(frame, 42, frame.length - 42, StandardCharsets.UTF_8));
            assertNull(receiver.frame(System.nanoTime() + Duration.ofSeconds(6).toNanos()));
            long count = receiver.snapshot().captured();
            // A unicast datagram to a shared UDP port reaches one socket; close the
            // second listener so both rejection checks deterministically reach receiver.
            shared.close();
            for (String invalid : List.of("[MOTD]other server[/MOTD][AD]25576[/AD]", "invalid")) {
                byte[] bytes = invalid.getBytes(StandardCharsets.UTF_8);
                sender.send(
                        new DatagramPacket(
                                bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
            }
            deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (receiver.snapshot().rejected() < 2 && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(2, receiver.snapshot().rejected());
            assertEquals(count, receiver.snapshot().captured());
            receiver.close();
            assertNull(receiver.frame(System.nanoTime()));
            assertEquals("STOPPED", receiver.snapshot().state());
        }
        try (var reopened = new LanDiscovery(VIP, 25575, 25565, port)) {
            assertEquals("LISTENING", reopened.snapshot().state());
        }
    }
}
