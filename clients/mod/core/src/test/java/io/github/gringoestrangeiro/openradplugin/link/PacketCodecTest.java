package io.github.gringoestrangeiro.openradplugin.link;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PacketCodecTest {
    private static final int SRC = 0x1a010101;
    private static final int DST = 0x1a010102;

    @Test
    void canonicalMacAndWrappedSerialArithmeticMatchOpenRad() {
        assertArrayEquals(new byte[] {2, 0x1a, 26, 1, 1, 2}, OpenRadAddress.mac(DST));
        assertEquals(
                16,
                io.github.gringoestrangeiro.openradplugin.virtual.TcpSequence.wire(0x1_0000_0010L));
        assertEquals(
                0x1_0000_0008L,
                io.github.gringoestrangeiro.openradplugin.virtual.TcpSequence.unwrap(
                        8, 0xffff_fff8L));
        assertEquals(
                0xffff_fff8L,
                io.github.gringoestrangeiro.openradplugin.virtual.TcpSequence.unwrap(
                        0xffff_fff8L, 0x1_0000_0008L));
    }

    @Test
    void tcpIpv4EthernetRoundtripAndChecksum() {
        TcpSegment syn =
                new TcpSegment(
                        54321,
                        25565,
                        0x1234abcdL,
                        0,
                        TcpSegment.SYN,
                        4096,
                        new byte[] {2, 4, 5, (byte) 0xb4},
                        new byte[0]);
        byte[] tcp = syn.encode(SRC, DST);
        assertEquals(0, Checksum.tcpChecksum(SRC, DST, tcp));
        byte[] ip = new Ipv4Packet(SRC, DST, 64, tcp).encode();
        assertEquals(0, Checksum.onesComplement(ip, 0, 20));
        byte[] eth =
                new EthernetFrame(
                                new byte[] {2, 3, 4, 5, 6, 7},
                                new byte[] {8, 9, 10, 11, 12, 13},
                                EthernetFrame.IPV4,
                                ip)
                        .encode();
        Ipv4Packet parsed = Ipv4Packet.parse(EthernetFrame.parse(eth).payload());
        TcpSegment segment =
                TcpSegment.parse(parsed.tcpBytes(), parsed.sourceIp(), parsed.destinationIp());
        assertEquals(syn.sequence(), segment.sequence());
        assertArrayEquals(syn.options(), segment.options());
        assertEquals(25565, segment.destinationPort());
    }

    @Test
    void corruptedTcpAndIpv4AreRejected() {
        TcpSegment t =
                new TcpSegment(3333, 25565, 4, 0, TcpSegment.SYN, 2048, new byte[0], new byte[0]);
        byte[] ip = new Ipv4Packet(SRC, DST, 20, t.encode(SRC, DST)).encode();
        byte[] badTcp = ip.clone();
        badTcp[badTcp.length - 1] ^= 1;
        Ipv4Packet parsed = Ipv4Packet.parse(badTcp);
        assertThrows(
                IllegalArgumentException.class,
                () -> TcpSegment.parse(parsed.tcpBytes(), SRC, DST));
        byte[] badIp = ip.clone();
        badIp[8] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> Ipv4Packet.parse(badIp));
        byte[] fragmented = ip.clone();
        Checksum.put16(fragmented, 6, 0x2000);
        Checksum.put16(fragmented, 10, 0);
        Checksum.put16(fragmented, 10, Checksum.onesComplement(fragmented, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> Ipv4Packet.parse(fragmented));
    }

    @Test
    void arpRoundtrip() {
        byte[] mac = {2, 0, 0, 0, 0, 1};
        ArpPacket arp = new ArpPacket(ArpPacket.REQUEST, mac, SRC, new byte[6], DST);
        ArpPacket decoded = ArpPacket.parse(arp.encode());
        assertEquals(DST, decoded.targetIp());
        assertArrayEquals(mac, decoded.senderMac());
        assertThrows(IllegalArgumentException.class, () -> EthernetFrame.parse(new byte[13]));
    }
}
