package io.github.gringoestrangeiro.openradplugin.util;

import java.util.Objects;

public final class HexFormatCompat {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();
    private static final HexFormatCompat INSTANCE = new HexFormatCompat();

    private HexFormatCompat() {}

    public static HexFormatCompat of() {
        return INSTANCE;
    }

    public byte[] parseHex(CharSequence value) {
        Objects.requireNonNull(value, "value");
        int length = value.length();
        if ((length & 1) != 0) {
            throw new IllegalArgumentException("odd-length hexadecimal value");
        }
        byte[] result = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            int high = digit(value.charAt(i));
            int low = digit(value.charAt(i + 1));
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid hexadecimal value");
            }
            result[i / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    public String formatHex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        char[] output = new char[Math.multiplyExact(bytes.length, 2)];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255;
            output[2 * i] = DIGITS[value >>> 4];
            output[2 * i + 1] = DIGITS[value & 15];
        }
        return new String(output);
    }

    private static int digit(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    }
}
