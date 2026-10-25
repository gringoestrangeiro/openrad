package io.github.gringoestrangeiro.openradplugin.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** RFC 1320 MD4, used only for the protocol's eight byte CBC trailer. */
final class Md4 {
    private Md4() {}

    static byte[] digest(byte[] input) {
        int length = Math.addExact(input.length, 9 + 63) / 64 * 64;
        byte[] data = Arrays.copyOf(input, length);
        data[input.length] = (byte) 0x80;
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong(length - 8, input.length * 8L);
        int[] state = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
        int[] words = new int[16];
        int[] order2 = {0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15};
        int[] order3 = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};
        int[][] shifts = {{3, 7, 11, 19}, {3, 5, 9, 13}, {3, 9, 11, 15}};
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            for (int i = 0; i < 16; i++) words[i] = buffer.getInt();
            int[] s = state.clone();
            for (int round = 0; round < 3; round++) {
                for (int step = 0; step < 16; step++) {
                    int a = (4 - step % 4) % 4, b = (a + 1) % 4, c = (a + 2) % 4, d = (a + 3) % 4;
                    int f =
                            round == 0
                                    ? ((s[b] & s[c]) | (~s[b] & s[d]))
                                    : round == 1
                                            ? ((s[b] & s[c]) | (s[b] & s[d]) | (s[c] & s[d]))
                                            : (s[b] ^ s[c] ^ s[d]);
                    int index = round == 0 ? step : round == 1 ? order2[step] : order3[step];
                    int constant = round == 0 ? 0 : round == 1 ? 0x5a827999 : 0x6ed9eba1;
                    s[a] =
                            Integer.rotateLeft(
                                    s[a] + f + words[index] + constant, shifts[round][step % 4]);
                }
            }
            for (int i = 0; i < 4; i++) state[i] += s[i];
            Arrays.fill(s, 0);
        }
        ByteBuffer result = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : state) result.putInt(value);
        Arrays.fill(data, (byte) 0);
        Arrays.fill(words, 0);
        return result.array();
    }
}
