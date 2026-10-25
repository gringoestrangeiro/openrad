package io.github.gringoestrangeiro.openradplugin.transport;

/** OpenRad's non-reflected CRC; unlike java.util.zip.CRC32 bytes enter after the shift. */
public final class WireChecksum {
    private WireChecksum() {}

    public static int checksum(byte[] bytes) {
        return checksum(bytes, false);
    }

    public static int enet(byte[] bytes) {
        return checksum(bytes, true);
    }

    private static int checksum(byte[] bytes, boolean zeroPrefix) {
        int crc = -1;
        for (int i = 0; i < Math.max(bytes.length, zeroPrefix ? 4 : 0); i++) {
            int top = crc & 0xff000000;
            for (int bit = 0; bit < 8; bit++) top = (top << 1) ^ (top < 0 ? 0x04c11db7 : 0);
            crc = top ^ ((crc << 8) | (zeroPrefix && i < 4 ? 0 : bytes[i] & 255));
        }
        return ~crc;
    }
}
