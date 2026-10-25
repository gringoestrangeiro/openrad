package io.github.gringoestrangeiro.openradplugin.link;

import java.util.Arrays;

public record EthernetFrame(
        byte[] destinationMac, byte[] sourceMac, int etherType, byte[] payload) {
    public static final int IPV4 = 0x0800;
    public static final int ARP = 0x0806;
    public static final int MAX_FRAME_BYTES = 1518;

    public EthernetFrame {
        if (destinationMac.length != 6 || sourceMac.length != 6) {
            throw new IllegalArgumentException("MAC addresses must have 6 bytes");
        }
        if (etherType != IPV4 && etherType != ARP)
            throw new IllegalArgumentException("unsupported EtherType");
        if (payload.length > MAX_FRAME_BYTES - 14)
            throw new IllegalArgumentException("Ethernet payload too large");
        destinationMac = destinationMac.clone();
        sourceMac = sourceMac.clone();
        payload = payload.clone();
    }

    @Override
    public byte[] destinationMac() {
        return destinationMac.clone();
    }

    @Override
    public byte[] sourceMac() {
        return sourceMac.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public static EthernetFrame parse(byte[] packet) {
        if (packet.length < 14 || packet.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("invalid Ethernet length");
        }
        return new EthernetFrame(
                Arrays.copyOfRange(packet, 0, 6),
                Arrays.copyOfRange(packet, 6, 12),
                Checksum.u16(packet, 12),
                Arrays.copyOfRange(packet, 14, packet.length));
    }

    public byte[] encode() {
        byte[] packet = new byte[14 + payload.length];
        System.arraycopy(destinationMac, 0, packet, 0, 6);
        System.arraycopy(sourceMac, 0, packet, 6, 6);
        Checksum.put16(packet, 12, etherType);
        System.arraycopy(payload, 0, packet, 14, payload.length);
        return packet;
    }
}
