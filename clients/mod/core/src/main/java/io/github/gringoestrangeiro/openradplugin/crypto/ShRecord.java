package io.github.gringoestrangeiro.openradplugin.crypto;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** SH has its own packed header, distinct from the control TLV framing. */
public final class ShRecord {
    private ShRecord() {}

    public static byte[] record(int kind, byte[] data) {
        Crypto.require(
                data.length > 0 && data.length <= 0x2800 && (kind & 0x07ffffff) == 0,
                "invalid SH record");
        return Crypto.concat(Crypto.be32(kind | data.length), data);
    }

    public static byte[] message(int index, int kind, byte[] data) {
        return Crypto.concat(record(0x10000000, Crypto.be32(index)), record(kind, data));
    }

    public static List<byte[]> parse(byte[] data, int index, int... kinds) {
        int[] expected = new int[kinds.length + 1];
        expected[0] = 0x10000000;
        System.arraycopy(kinds, 0, expected, 1, kinds.length);
        ByteBuffer input = ByteBuffer.wrap(data);
        List<byte[]> out = new ArrayList<>();
        for (int kind : expected) {
            Crypto.require(input.remaining() >= 4, "truncated SH header");
            int word = input.getInt(), len = word & 0x07ffffff;
            Crypto.require(
                    (word & 0xf8000000) == kind && len >= 1 && len <= 0x2800, "invalid SH record");
            Crypto.require(len <= input.remaining(), "truncated SH body");
            byte[] value = new byte[len];
            input.get(value);
            out.add(value);
        }
        Crypto.require(
                !input.hasRemaining() && Arrays.equals(out.get(0), Crypto.be32(index)),
                "SH state/trailing data mismatch");
        return List.copyOf(out.subList(1, out.size()));
    }
}
