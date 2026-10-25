package io.github.gringoestrangeiro.openradplugin.link;

import java.util.Arrays;

public record ArpPacket(
        int opcode, byte[] senderMac, int senderIp, byte[] targetMac, int targetIp) {
    public static final int REQUEST = 1;
    public static final int REPLY = 2;

    public ArpPacket {
        if (opcode != REQUEST && opcode != REPLY)
            throw new IllegalArgumentException("unsupported ARP opcode");
        if (senderMac.length != 6 || targetMac.length != 6)
            throw new IllegalArgumentException("invalid ARP MAC");
        senderMac = senderMac.clone();
        targetMac = targetMac.clone();
    }

    @Override
    public byte[] senderMac() {
        return senderMac.clone();
    }

    @Override
    public byte[] targetMac() {
        return targetMac.clone();
    }

    public static ArpPacket parse(byte[] payload) {
        if (payload.length < 28
                || Checksum.u16(payload, 0) != 1
                || Checksum.u16(payload, 2) != EthernetFrame.IPV4
                || (payload[4] & 0xff) != 6
                || (payload[5] & 0xff) != 4) {
            throw new IllegalArgumentException("invalid ARP format");
        }
        return new ArpPacket(
                Checksum.u16(payload, 6),
                Arrays.copyOfRange(payload, 8, 14),
                Checksum.u32(payload, 14),
                Arrays.copyOfRange(payload, 18, 24),
                Checksum.u32(payload, 24));
    }

    public byte[] encode() {
        byte[] data = new byte[28];
        Checksum.put16(data, 0, 1);
        Checksum.put16(data, 2, EthernetFrame.IPV4);
        data[4] = 6;
        data[5] = 4;
        Checksum.put16(data, 6, opcode);
        System.arraycopy(senderMac, 0, data, 8, 6);
        Checksum.put32(data, 14, senderIp);
        System.arraycopy(targetMac, 0, data, 18, 6);
        Checksum.put32(data, 24, targetIp);
        return data;
    }
}
