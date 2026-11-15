package io.github.gringoestrangeiro.openradplugin.transport;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

class WireChecksumTest {
    /** The original bit-at-a-time definition. */
    private static int reference(byte[] bytes, boolean zeroPrefix) {
        int crc = -1;
        for (int i = 0; i < Math.max(bytes.length, zeroPrefix ? 4 : 0); i++) {
            int top = crc & 0xff000000;
            for (int bit = 0; bit < 8; bit++) top = (top << 1) ^ (top < 0 ? 0x04c11db7 : 0);
            crc = top ^ ((crc << 8) | (zeroPrefix && i < 4 ? 0 : bytes[i] & 255));
        }
        return ~crc;
    }

    @Test
    void tableDrivenChecksumMatchesTheBitwiseDefinition() {
        Random random = new Random(0xc7c);
        for (int length = 0; length < 1500; length += length < 40 ? 1 : 37) {
            byte[] bytes = new byte[length + 5];
            random.nextBytes(bytes);
            byte[] exact = Arrays.copyOf(bytes, length);
            assertEquals(reference(exact, false), WireChecksum.checksum(exact));
            assertEquals(reference(exact, true), WireChecksum.enet(exact));
            assertEquals(reference(exact, true), WireChecksum.enet(bytes, length));
            assertEquals(
                    reference(Arrays.copyOfRange(bytes, 3, 3 + length), false),
                    WireChecksum.checksum(bytes, 3, length));
        }
    }
}
