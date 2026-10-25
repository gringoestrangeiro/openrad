package io.github.gringoestrangeiro.openradplugin.link;

public final class Checksum {
    private Checksum() {}

    public static int onesComplement(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException("invalid checksum bounds");
        }
        long sum = 0;
        for (int i = offset; i < offset + length; i += 2) {
            int high = bytes[i] & 0xff;
            int low = i + 1 < offset + length ? bytes[i + 1] & 0xff : 0;
            sum += (high << 8) | low;
        }
        return finish(sum);
    }

    public static int tcpChecksum(int sourceIp, int destinationIp, byte[] segment) {
        long sum =
                ((sourceIp >>> 16) & 0xffff)
                        + (sourceIp & 0xffffL)
                        + ((destinationIp >>> 16) & 0xffff)
                        + (destinationIp & 0xffffL)
                        + 6
                        + segment.length;
        for (int i = 0; i < segment.length; i += 2) {
            int high = segment[i] & 0xff;
            int low = i + 1 < segment.length ? segment[i + 1] & 0xff : 0;
            sum += (high << 8) | low;
        }
        return finish(sum);
    }

    private static int finish(long sum) {
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return (int) (~sum) & 0xffff;
    }

    public static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    public static int u32(byte[] bytes, int offset) {
        return (u16(bytes, offset) << 16) | u16(bytes, offset + 2);
    }

    public static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    public static void put32(byte[] bytes, int offset, int value) {
        put16(bytes, offset, value >>> 16);
        put16(bytes, offset + 2, value);
    }
}
