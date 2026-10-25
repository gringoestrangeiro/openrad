package io.github.gringoestrangeiro.openradplugin.transport;

/** OpenRad's non-reflected CRC; unlike java.util.zip.CRC32 bytes enter after the shift. */
public final class WireChecksum {
    /** TABLE[t] is the eight-step polynomial reduction of the top byte t (as t << 24). */
    private static final int[] TABLE = new int[256];

    static {
        for (int t = 0; t < 256; t++) {
            int top = t << 24;
            for (int bit = 0; bit < 8; bit++) top = (top << 1) ^ (top < 0 ? 0x04c11db7 : 0);
            TABLE[t] = top;
        }
    }

    private WireChecksum() {}

    public static int checksum(byte[] bytes) {
        return checksum(bytes, 0, bytes.length, false);
    }

    public static int checksum(byte[] bytes, int offset, int length) {
        return checksum(bytes, offset, length, false);
    }

    public static int enet(byte[] bytes) {
        return checksum(bytes, 0, bytes.length, true);
    }

    /** ENET checksum of bytes[0, length): the first four (checksum) bytes enter as zero. */
    public static int enet(byte[] bytes, int length) {
        return checksum(bytes, 0, length, true);
    }

    private static int checksum(byte[] bytes, int offset, int length, boolean zeroPrefix) {
        if (offset < 0 || length < 0 || offset > bytes.length - length)
            throw new ArrayIndexOutOfBoundsException("checksum bounds");
        int crc = -1;
        int i = 0;
        if (zeroPrefix) {
            // The checksum field itself is hashed as four zero bytes (even when absent).
            for (; i < 4; i++) crc = TABLE[crc >>> 24] ^ (crc << 8);
        }
        for (int end = offset + length, at = offset + i; at < end; at++)
            crc = TABLE[crc >>> 24] ^ ((crc << 8) | (bytes[at] & 255));
        return ~crc;
    }
}
