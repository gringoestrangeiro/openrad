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

    public static TcpSegment parse(byte[] bytes, int sourceIp, int destinationIp) {
        if (bytes.length < 20 || Checksum.tcpChecksum(sourceIp, destinationIp, bytes) != 0) {
            throw new IllegalArgumentException("TCP checksum/length invalid");
        }
        int words = (bytes[12] & 0xf0) >>> 4;
        int headerSize = words * 4;
        if (words < 5 || headerSize > bytes.length || (bytes[12] & 0x0f) != 0) {
            throw new IllegalArgumentException("TCP header invalid");
        }
        if ((bytes[13] & URG) != 0)
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

    public byte[] encode(int sourceIp, int destinationIp) {
        byte[] bytes = new byte[20 + options.length + payload.length];
        Checksum.put16(bytes, 0, sourcePort);
        Checksum.put16(bytes, 2, destinationPort);
        Checksum.put32(bytes, 4, (int) sequence);
        Checksum.put32(bytes, 8, (int) acknowledgement);
        bytes[12] = (byte) (((20 + options.length) / 4) << 4);
        bytes[13] = (byte) flags;
        Checksum.put16(bytes, 14, window);
        System.arraycopy(options, 0, bytes, 20, options.length);
        System.arraycopy(payload, 0, bytes, 20 + options.length, payload.length);
        Checksum.put16(bytes, 16, Checksum.tcpChecksum(sourceIp, destinationIp, bytes));
        return bytes;
    }
}
