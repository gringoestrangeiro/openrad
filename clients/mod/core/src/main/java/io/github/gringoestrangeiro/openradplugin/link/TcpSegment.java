package io.github.gringoestrangeiro.openradplugin.link;

import java.util.Arrays;

public record TcpSegment(
        int sourcePort,
        int destinationPort,
        long sequence,
        long acknowledgement,
        int flags,
        int window,
        byte[] options,
        byte[] payload) {
    public static final int FIN = 0x01,
            SYN = 0x02,
            RST = 0x04,
            PSH = 0x08,
            ACK = 0x10,
            URG = 0x20,
            ECE = 0x40,
            CWR = 0x80;

    public TcpSegment {
        if (sourcePort < 1
                || sourcePort > 65535
                || destinationPort < 1
                || destinationPort > 65535) {
            throw new IllegalArgumentException("invalid TCP port");
        }
        if ((sequence & ~0xffff_ffffL) != 0 || (acknowledgement & ~0xffff_ffffL) != 0) {
            throw new IllegalArgumentException("invalid TCP sequence");
        }
        if (window < 0
                || window > 65535
                || (flags & ~0xff) != 0
                || options.length > 40
                || (options.length & 3) != 0) {
            throw new IllegalArgumentException("invalid TCP metadata");
        }
        options = options.clone();
        payload = payload.clone();
    }

    @Override
    public byte[] options() {
        return options.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Payload byte count, without the defensive copy made by {@link #payload()}. */
    public int payloadLength() {
        return payload.length;
    }

    /** Copy of payload bytes [from, to), without first cloning the whole payload. */
    public byte[] copyPayload(int from, int to) {
        return Arrays.copyOfRange(payload, from, to);
    }

    public static TcpSegment parse(byte[] bytes, int sourceIp, int destinationIp) {
        return parse(bytes, 0, bytes.length, sourceIp, destinationIp);
    }

    /** {@link #parse(byte[], int, int)} of the segment at bytes[offset, offset + length). */
    public static TcpSegment parse(
            byte[] bytes, int offset, int length, int sourceIp, int destinationIp) {
        if (offset < 0
                || length < 20
                || offset > bytes.length - length
                || Checksum.tcpChecksum(sourceIp, destinationIp, bytes, offset, length) != 0) {
            throw new IllegalArgumentException("TCP checksum/length invalid");
        }
        int words = (bytes[offset + 12] & 0xf0) >>> 4;
        int headerSize = words * 4;
        if (words < 5 || headerSize > length || (bytes[offset + 12] & 0x0f) != 0) {
            throw new IllegalArgumentException("TCP header invalid");
        }
        if ((bytes[offset + 13] & URG) != 0)
            throw new IllegalArgumentException("TCP urgent data unsupported");
        return new TcpSegment(
                Checksum.u16(bytes, offset),
                Checksum.u16(bytes, offset + 2),
                Integer.toUnsignedLong(Checksum.u32(bytes, offset + 4)),
                Integer.toUnsignedLong(Checksum.u32(bytes, offset + 8)),
                bytes[offset + 13] & 0xff,
                Checksum.u16(bytes, offset + 14),
                Arrays.copyOfRange(bytes, offset + 20, offset + headerSize),
                Arrays.copyOfRange(bytes, offset + headerSize, offset + length));
    }

    public byte[] encode(int sourceIp, int destinationIp) {
        byte[] bytes = new byte[20 + options.length + payload.length];
        encodeInto(bytes, 0, sourceIp, destinationIp);
        return bytes;
    }

    private void encodeInto(byte[] bytes, int at, int sourceIp, int destinationIp) {
        int length = 20 + options.length + payload.length;
        Checksum.put16(bytes, at, sourcePort);
        Checksum.put16(bytes, at + 2, destinationPort);
        Checksum.put32(bytes, at + 4, (int) sequence);
        Checksum.put32(bytes, at + 8, (int) acknowledgement);
        bytes[at + 12] = (byte) (((20 + options.length) / 4) << 4);
        bytes[at + 13] = (byte) flags;
        Checksum.put16(bytes, at + 14, window);
        System.arraycopy(options, 0, bytes, at + 20, options.length);
        System.arraycopy(payload, 0, bytes, at + 20 + options.length, payload.length);
        Checksum.put16(
                bytes, at + 16, Checksum.tcpChecksum(sourceIp, destinationIp, bytes, at, length));
    }

    /**
     * Byte-identical to {@code new EthernetFrame(destinationMac, sourceMac, IPV4, new
     * Ipv4Packet(sourceIp, destinationIp, ttl, encode(sourceIp, destinationIp)).encode())
     * .encode()}, built in one array instead of three intermediate copies.
     */
    public byte[] encodeIpv4Frame(
            byte[] destinationMac, byte[] sourceMac, int sourceIp, int destinationIp, int ttl) {
        if (ttl < 1 || ttl > 255) throw new IllegalArgumentException("invalid IPv4 TTL");
        int tcpLength = 20 + options.length + payload.length;
        int total = 20 + tcpLength;
        if (total > 65535) throw new IllegalArgumentException("IPv4 packet too large");
        if (destinationMac.length != 6 || sourceMac.length != 6) {
            throw new IllegalArgumentException("MAC addresses must have 6 bytes");
        }
        if (total > EthernetFrame.MAX_FRAME_BYTES - 14)
            throw new IllegalArgumentException("Ethernet payload too large");
        byte[] frame = new byte[14 + total];
        System.arraycopy(destinationMac, 0, frame, 0, 6);
        System.arraycopy(sourceMac, 0, frame, 6, 6);
        Checksum.put16(frame, 12, EthernetFrame.IPV4);
        frame[14] = 0x45;
        Checksum.put16(frame, 16, total);
        Checksum.put16(frame, 20, 0x4000);
        frame[22] = (byte) ttl;
        frame[23] = 6;
        Checksum.put32(frame, 26, sourceIp);
        Checksum.put32(frame, 30, destinationIp);
        Checksum.put16(frame, 24, Checksum.onesComplement(frame, 14, 20));
        encodeInto(frame, 34, sourceIp, destinationIp);
        return frame;
    }
}
