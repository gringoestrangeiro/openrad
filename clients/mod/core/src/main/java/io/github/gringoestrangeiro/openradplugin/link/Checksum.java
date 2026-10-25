package io.github.gringoestrangeiro.openradplugin.link;

public final class Checksum {
    private Checksum() {}

    public static int onesComplement(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException("invalid checksum bounds");
        }
        return finish(sum16(bytes, offset, length));
    }

    public static int tcpChecksum(int sourceIp, int destinationIp, byte[] segment) {
        return tcpChecksum(sourceIp, destinationIp, segment, 0, segment.length);
    }

    /** TCP checksum of segment bytes[offset, offset + length) with the IPv4 pseudo-header. */
    public static int tcpChecksum(
            int sourceIp, int destinationIp, byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException("invalid checksum bounds");
        }
        long sum =
                ((sourceIp >>> 16) & 0xffff)
                        + (sourceIp & 0xffffL)
                        + ((destinationIp >>> 16) & 0xffff)
                        + (destinationIp & 0xffffL)
                        + 6
                        + length;
        return finish(sum + sum16(bytes, offset, length));
    }

    /** Sum of big-endian 16-bit words; an odd final byte is padded with zero. */
    private static long sum16(byte[] bytes, int offset, int length) {
        long sum = 0;
        int end = offset + length, i = offset;
        for (; end - i >= 2; i += 2) sum += ((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff);
        if (i < end) sum += (bytes[i] & 0xff) << 8;
        return sum;
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
