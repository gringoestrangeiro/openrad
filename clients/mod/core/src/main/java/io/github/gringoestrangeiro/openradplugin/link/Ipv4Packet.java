package io.github.gringoestrangeiro.openradplugin.link;

import java.util.Arrays;

public record Ipv4Packet(int sourceIp, int destinationIp, int ttl, byte[] tcpBytes) {
    public Ipv4Packet {
        if (ttl < 1 || ttl > 255) throw new IllegalArgumentException("invalid IPv4 TTL");
        tcpBytes = tcpBytes.clone();
    }

    @Override
    public byte[] tcpBytes() {
        return tcpBytes.clone();
    }

    public static Ipv4Packet parse(byte[] bytes) {
        if (bytes.length < 20 || (bytes[0] & 0xff) != 0x45) {
            throw new IllegalArgumentException("unsupported IPv4 version/IHL/options");
        }
        int total = Checksum.u16(bytes, 2);
        if (total > bytes.length || total < 40) {
            throw new IllegalArgumentException("IPv4 total length mismatch");
        }
        int flagsOffset = Checksum.u16(bytes, 6);
        if ((flagsOffset & 0xbfff) != 0)
            throw new IllegalArgumentException("IPv4 fragments not supported");
        int ttl = bytes[8] & 0xff;
        if (ttl == 0 || (bytes[9] & 0xff) != 6) {
            throw new IllegalArgumentException("unsupported IPv4 TTL/protocol");
        }
        if (Checksum.onesComplement(bytes, 0, 20) != 0)
            throw new IllegalArgumentException("IPv4 checksum invalid");
        return new Ipv4Packet(
                Checksum.u32(bytes, 12),
                Checksum.u32(bytes, 16),
                ttl,
                Arrays.copyOfRange(bytes, 20, total));
    }

    public byte[] encode() {
        int total = 20 + tcpBytes.length;
        if (total > 65535) throw new IllegalArgumentException("IPv4 packet too large");
        byte[] bytes = new byte[total];
        bytes[0] = 0x45;
        Checksum.put16(bytes, 2, total);
        Checksum.put16(bytes, 6, 0x4000);
        bytes[8] = (byte) ttl;
        bytes[9] = 6;
        Checksum.put32(bytes, 12, sourceIp);
        Checksum.put32(bytes, 16, destinationIp);
        Checksum.put16(bytes, 10, Checksum.onesComplement(bytes, 0, 20));
        System.arraycopy(tcpBytes, 0, bytes, 20, tcpBytes.length);
        return bytes;
    }
}
