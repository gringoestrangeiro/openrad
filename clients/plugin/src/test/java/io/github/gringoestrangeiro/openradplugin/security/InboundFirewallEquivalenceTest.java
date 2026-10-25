package io.github.gringoestrangeiro.openradplugin.security;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.link.ArpPacket;
import io.github.gringoestrangeiro.openradplugin.link.Checksum;
import io.github.gringoestrangeiro.openradplugin.link.EthernetFrame;
import io.github.gringoestrangeiro.openradplugin.link.Ipv4Packet;
import io.github.gringoestrangeiro.openradplugin.link.TcpSegment;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.Set;

/** The in-place inspector must decide every frame exactly like the layered record parsers. */
class InboundFirewallEquivalenceTest {
    private static final int LOCAL = 0x1a0a0002, REMOTE = 0x1a0a0003;
    private static final byte[] LOCAL_MAC = {2, 9, 8, 7, 6, 5};
    private static final byte[] REMOTE_MAC = {2, 1, 2, 3, 4, 5};

    /** The original layered TCP parser (before the offset overload existed). */
    private static TcpSegment referenceTcp(byte[] bytes, int sourceIp, int destinationIp) {
        if (bytes.length < 20 || Checksum.tcpChecksum(sourceIp, destinationIp, bytes) != 0)
            throw new IllegalArgumentException("TCP checksum/length invalid");
        int words = (bytes[12] & 0xf0) >>> 4;
        int headerSize = words * 4;
        if (words < 5 || headerSize > bytes.length || (bytes[12] & 0x0f) != 0)
            throw new IllegalArgumentException("TCP header invalid");
        if ((bytes[13] & TcpSegment.URG) != 0)
            throw new IllegalArgumentException("TCP urgent data unsupported");
        return new TcpSegment(
                Checksum.u16(bytes, 0),
                Checksum.u16(bytes, 2),
                Integer.toUnsignedLong(Checksum.u32(bytes, 4)),
                Integer.toUnsignedLong(Checksum.u32(bytes, 8)),
                bytes[13] & 0xff,
                Checksum.u16(bytes, 14),
                Arrays.copyOfRange(bytes, 20, headerSize),
                Arrays.copyOfRange(bytes, headerSize, bytes.length));
    }

    /** The original record-by-record inspector. */
    private static InboundFirewall.Result reference(
            byte[] raw, InboundFirewall.PeerGrant grant, int localIp, byte[] localMac, int port) {
        if (!grant.eligible())
            return new InboundFirewall.Rejected("peer not authenticated in an allowed network");
        try {
            EthernetFrame frame = EthernetFrame.parse(raw);
            if (!Arrays.equals(frame.sourceMac(), grant.sourceMac()))
                return new InboundFirewall.Rejected("source MAC mismatch");
            if (frame.etherType() == EthernetFrame.ARP) {
                boolean broadcast = true;
                for (byte octet : frame.destinationMac()) broadcast &= (octet & 0xff) == 0xff;
                if (!broadcast && !Arrays.equals(frame.destinationMac(), localMac))
                    return new InboundFirewall.Rejected("ARP destination MAC invalid");
                ArpPacket arp = ArpPacket.parse(frame.payload());
                if (arp.opcode() != ArpPacket.REQUEST
                        || arp.targetIp() != localIp
                        || arp.senderIp() != grant.sourceIp()
                        || !Arrays.equals(arp.senderMac(), grant.sourceMac()))
                    return new InboundFirewall.Rejected("ARP origin/target invalid");
                ArpPacket reply =
                        new ArpPacket(
                                ArpPacket.REPLY,
                                localMac,
                                localIp,
                                grant.sourceMac(),
                                grant.sourceIp());
                return new InboundFirewall.ArpResponse(
                        new EthernetFrame(
                                        grant.sourceMac(),
                                        localMac,
                                        EthernetFrame.ARP,
                                        reply.encode())
                                .encode());
            }
            if (!Arrays.equals(frame.destinationMac(), localMac))
                return new InboundFirewall.Rejected("unicast MAC mismatch");
            Ipv4Packet ip = Ipv4Packet.parse(frame.payload());
            if (ip.sourceIp() != grant.sourceIp() || ip.destinationIp() != localIp)
                return new InboundFirewall.Rejected("IPv4 address mismatch");
            TcpSegment tcp = referenceTcp(ip.tcpBytes(), ip.sourceIp(), ip.destinationIp());
            if (tcp.destinationPort() != port)
                return new InboundFirewall.Rejected("TCP port not exposed");
            return new InboundFirewall.AdmittedTcp(grant.rid(), tcp);
        } catch (IllegalArgumentException ex) {
            return new InboundFirewall.Rejected("malformed or unsupported packet");
        }
    }

    private static void assertSame(InboundFirewall.Result expected, InboundFirewall.Result actual) {
        assertEquals(expected.getClass(), actual.getClass());
        if (expected instanceof InboundFirewall.Rejected r)
            assertEquals(r.reason(), ((InboundFirewall.Rejected) actual).reason());
        else if (expected instanceof InboundFirewall.ArpResponse a)
            assertArrayEquals(a.frame(), ((InboundFirewall.ArpResponse) actual).frame());
        else {
            TcpSegment e = ((InboundFirewall.AdmittedTcp) expected).segment();
            TcpSegment g = ((InboundFirewall.AdmittedTcp) actual).segment();
            assertEquals(((InboundFirewall.AdmittedTcp) expected).rid(), ((InboundFirewall.AdmittedTcp) actual).rid());
            assertEquals(e.sourcePort(), g.sourcePort());
            assertEquals(e.destinationPort(), g.destinationPort());
            assertEquals(e.sequence(), g.sequence());
            assertEquals(e.acknowledgement(), g.acknowledgement());
            assertEquals(e.flags(), g.flags());
            assertEquals(e.window(), g.window());
            assertArrayEquals(e.options(), g.options());
            assertArrayEquals(e.payload(), g.payload());
        }
    }

    private static byte[] validTcpFrame(Random random) {
        byte[] options = new byte[4 * random.nextInt(4)];
        random.nextBytes(options);
        byte[] payload = new byte[random.nextInt(200)];
        random.nextBytes(payload);
        int flags = random.nextInt(256) & ~TcpSegment.URG;
        TcpSegment tcp =
                new TcpSegment(
                        1 + random.nextInt(65535),
                        random.nextInt(4) == 0 ? 7777 : 25565,
                        random.nextLong() & 0xffff_ffffL,
                        random.nextLong() & 0xffff_ffffL,
                        flags,
                        random.nextInt(65536),
                        options,
                        payload);
        int destination = random.nextInt(8) == 0 ? 0x1a0a0004 : LOCAL;
        byte[] frame = tcp.encodeIpv4Frame(LOCAL_MAC, REMOTE_MAC, REMOTE, destination, 64);
        if (random.nextInt(5) == 0) {
            // Ethernet padding beyond the IPv4 total length is legal and ignored.
            frame = Arrays.copyOf(frame, Math.min(1518, frame.length + random.nextInt(30)));
        }
        return frame;
    }

    @Test
    void inPlaceInspectionMatchesLayeredParsingOnHostileFrames() {
        Random random = new Random(0xf1e);
        var grant = new InboundFirewall.PeerGrant(42, true, Set.of("net"), REMOTE, REMOTE_MAC);
        var other =
                new InboundFirewall.PeerGrant(
                        43, true, Set.of("net"), REMOTE + 1, new byte[] {2, 1, 2, 3, 4, 6});
        var ineligible = new InboundFirewall.PeerGrant(44, false, Set.of(), REMOTE, REMOTE_MAC);
        for (int i = 0; i < 30000; i++) {
            byte[] raw;
            switch (i % 5) {
                case 0 -> {
                    raw = new byte[random.nextInt(1600)];
                    random.nextBytes(raw);
                    if (raw.length >= 14 && random.nextBoolean()) {
                        System.arraycopy(REMOTE_MAC, 0, raw, 6, 6);
                        Checksum.put16(
                                raw,
                                12,
                                random.nextBoolean() ? EthernetFrame.IPV4 : EthernetFrame.ARP);
                    }
                }
                case 1 -> raw = validTcpFrame(random);
                case 2 -> {
                    raw = validTcpFrame(random);
                    int at = random.nextInt(raw.length);
                    raw[at] = (byte) random.nextInt(256);
                    if (random.nextBoolean() && at >= 14 && at < 34) {
                        Checksum.put16(raw, 24, 0);
                        Checksum.put16(raw, 24, Checksum.onesComplement(raw, 14, 20));
                    }
                }
                case 3 -> {
                    byte[] full = validTcpFrame(random);
                    raw = Arrays.copyOf(full, random.nextInt(full.length + 1));
                }
                default -> {
                    byte[] dst =
                            random.nextBoolean()
                                    ? new byte[] {-1, -1, -1, -1, -1, -1}
                                    : random.nextBoolean() ? LOCAL_MAC : new byte[] {2, 7, 7, 7, 7, 7};
                    raw =
                            new EthernetFrame(
                                            dst,
                                            REMOTE_MAC,
                                            EthernetFrame.ARP,
                                            new ArpPacket(
                                                            1 + random.nextInt(2),
                                                            REMOTE_MAC,
                                                            random.nextBoolean() ? REMOTE : LOCAL,
                                                            new byte[6],
                                                            random.nextBoolean() ? LOCAL : REMOTE)
                                                    .encode())
                                    .encode();
                    if (random.nextInt(4) == 0) raw[14 + random.nextInt(28)] ^= 1;
                }
            }
            var g = i % 17 == 0 ? other : i % 23 == 0 ? ineligible : grant;
            assertSame(
                    reference(raw, g, LOCAL, LOCAL_MAC, 25565),
                    InboundFirewall.inspect(raw, g, LOCAL, LOCAL_MAC, 25565));
        }
    }
}
