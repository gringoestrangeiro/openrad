package io.github.gringoestrangeiro.openradplugin.security;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.link.ArpPacket;
import io.github.gringoestrangeiro.openradplugin.link.Checksum;
import io.github.gringoestrangeiro.openradplugin.link.EthernetFrame;
import io.github.gringoestrangeiro.openradplugin.link.Ipv4Packet;
import io.github.gringoestrangeiro.openradplugin.link.TcpSegment;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;

class InboundFirewallTest {
    private static final int LOCAL = 0x1a0a0002, REMOTE = 0x1a0a0003;
    private static final byte[] LOCAL_MAC = {2, 9, 8, 7, 6, 5};
    private static final byte[] REMOTE_MAC = {2, 1, 2, 3, 4, 5};
    private static final byte[] BROADCAST = {
        (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255
    };

    private static InboundFirewall.PeerGrant grant(boolean authenticated) {
        return new InboundFirewall.PeerGrant(42, authenticated, Set.of("rede"), REMOTE, REMOTE_MAC);
    }

    @Test
    void authenticatedArpRequestGetsScopedReply() {
        byte[] request =
                new EthernetFrame(
                                BROADCAST,
                                REMOTE_MAC,
                                EthernetFrame.ARP,
                                new ArpPacket(1, REMOTE_MAC, REMOTE, new byte[6], LOCAL).encode())
                        .encode();
        var result = InboundFirewall.inspect(request, grant(true), LOCAL, LOCAL_MAC, 25565);
        assertInstanceOf(InboundFirewall.ArpResponse.class, result);
        EthernetFrame reply = EthernetFrame.parse(((InboundFirewall.ArpResponse) result).frame());
        assertArrayEquals(REMOTE_MAC, reply.destinationMac());
        assertEquals(ArpPacket.REPLY, ArpPacket.parse(reply.payload()).opcode());
        assertInstanceOf(
                InboundFirewall.Rejected.class,
                InboundFirewall.inspect(request, grant(false), LOCAL, LOCAL_MAC, 25565));
    }

    @Test
    void allowsOnlySpecificTcpServiceFromBoundIdentity() {
        TcpSegment syn =
                new TcpSegment(44444, 25565, 3, 0, TcpSegment.SYN, 4096, new byte[0], new byte[0]);
        byte[] packet = ethernet(REMOTE_MAC, LOCAL_MAC, syn.encode(REMOTE, LOCAL), LOCAL);
        assertInstanceOf(
                InboundFirewall.AdmittedTcp.class,
                InboundFirewall.inspect(packet, grant(true), LOCAL, LOCAL_MAC, 25565));
        assertInstanceOf(
                InboundFirewall.Rejected.class,
                InboundFirewall.inspect(packet, grant(true), LOCAL, LOCAL_MAC, 7777));
        byte[] spoofedMac =
                ethernet(
                        new byte[] {2, 1, 1, 1, 1, 1}, LOCAL_MAC, syn.encode(REMOTE, LOCAL), LOCAL);
        assertInstanceOf(
                InboundFirewall.Rejected.class,
                InboundFirewall.inspect(spoofedMac, grant(true), LOCAL, LOCAL_MAC, 25565));
        byte[] wrongDestination =
                ethernet(REMOTE_MAC, LOCAL_MAC, syn.encode(REMOTE, 0x1a0a0004), 0x1a0a0004);
        assertInstanceOf(
                InboundFirewall.Rejected.class,
                InboundFirewall.inspect(wrongDestination, grant(true), LOCAL, LOCAL_MAC, 25565));
    }

    @Test
    void tenThousandDeterministicMalformedFramesStayInsideTheParserBoundary() {
        Random random = new Random(0x5eed);
        for (int i = 0; i < 10000; i++) {
            byte[] raw;
            if (i % 3 == 0) {
                raw = new byte[random.nextInt(1600)];
                random.nextBytes(raw);
            } else {
                byte[] tcp = new byte[20 + random.nextInt(61)];
                random.nextBytes(tcp);
                Checksum.put16(tcp, 0, 44444);
                Checksum.put16(tcp, 2, 25565);
                // Preserve checksum validity to reach hostile data-offset/options/control fields.
                Checksum.put16(tcp, 16, 0);
                Checksum.put16(tcp, 16, Checksum.tcpChecksum(REMOTE, LOCAL, tcp));
                byte[] ip = new Ipv4Packet(REMOTE, LOCAL, 64, tcp).encode();
                if (i % 3 == 1) {
                    int index = random.nextInt(20);
                    ip[index] = (byte) random.nextInt(256);
                    Checksum.put16(ip, 10, 0);
                    Checksum.put16(ip, 10, Checksum.onesComplement(ip, 0, 20));
                }
                raw = new EthernetFrame(LOCAL_MAC, REMOTE_MAC, EthernetFrame.IPV4, ip).encode();
            }
            assertNotNull(
                    InboundFirewall.inspect(raw, grant(true), LOCAL, LOCAL_MAC, 25565),
                    "case " + i);
        }
    }

    private static byte[] ethernet(
            byte[] sourceMac, byte[] destinationMac, byte[] tcp, int destIp) {
        return new EthernetFrame(
                        destinationMac,
                        sourceMac,
                        EthernetFrame.IPV4,
                        new Ipv4Packet(REMOTE, destIp, 64, tcp).encode())
                .encode();
    }
}
