package io.github.gringoestrangeiro.openradplugin.crypto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Pins the allocation-free channel/auth8 paths to the original straightforward algorithms. */
class ChannelEquivalenceTest {
    /** Original auth8: little-endian word sum, MD4 of its eight bytes, folded halves. */
    private static byte[] referenceAuth8(byte[] region) {
        long sum = 0;
        for (int offset = 0; offset < region.length; offset += 8) {
            long word = 0;
            for (int i = 0; i < Math.min(8, region.length - offset); i++)
                word |= (region[offset + i] & 255L) << (i * 8);
            sum += word;
        }
        byte[] digest =
                Md4.digest(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(sum).array());
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) out[i] = (byte) (digest[i] + digest[i + 8]);
        return out;
    }

    /** The original channel: a fresh AES/CBC instance and copied buffers for every record. */
    private static final class ReferenceChannel {
        byte[] key, encIv = new byte[16], decIv = new byte[16];

        ReferenceChannel(byte[] key) {
            this.key = key.clone();
        }

        byte[] crypt(int mode, byte[] data, byte[] iv) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        }

        byte[] encrypt(byte[] plain) throws Exception {
            int total = (plain.length + 24) / 16 * 16;
            byte[] data = Arrays.copyOf(plain, total);
            Arrays.fill(data, plain.length, total - 9, (byte) 0xcc);
            byte[] tag = referenceAuth8(Arrays.copyOf(data, total - 9));
            System.arraycopy(tag, 0, data, total - 9, 8);
            data[total - 1] = (byte) (total - plain.length);
            byte[] out = crypt(Cipher.ENCRYPT_MODE, data, encIv);
            encIv = Arrays.copyOfRange(out, out.length - 16, out.length);
            return out;
        }

        byte[] decrypt(byte[] ciphertext) throws Exception {
            byte[] data = crypt(Cipher.DECRYPT_MODE, ciphertext, decIv);
            decIv = Arrays.copyOfRange(ciphertext, ciphertext.length - 16, ciphertext.length);
            int pad = data[data.length - 1] & 255;
            if (!(pad >= 9 && pad < 25 && pad < data.length)) throw new IllegalArgumentException();
            if (!MessageDigest.isEqual(
                    referenceAuth8(Arrays.copyOf(data, data.length - 9)),
                    Arrays.copyOfRange(data, data.length - 9, data.length - 1)))
                throw new IllegalArgumentException();
            return Arrays.copyOf(data, data.length - pad);
        }
    }

    @Test
    void auth8MatchesTheMd4ReferenceForEveryLengthAndOffset() {
        Random random = new Random(0xa8);
        for (int length = 0; length < 300; length++) {
            byte[] region = new byte[length + 7];
            random.nextBytes(region);
            int offset = random.nextInt(8);
            int span = Math.min(length, region.length - offset);
            byte[] expected = referenceAuth8(Arrays.copyOfRange(region, offset, offset + span));
            byte[] out = new byte[10];
            Crypto.auth8(region, offset, span, out, 1);
            assertArrayEquals(expected, Arrays.copyOfRange(out, 1, 9), "length " + span);
            assertArrayEquals(
                    expected, Crypto.auth8(Arrays.copyOfRange(region, offset, offset + span)));
        }
    }

    @Test
    void cachedCipherChannelIsByteIdenticalAcrossChainingRekeyAndRejectedRecords()
            throws Exception {
        Random random = new Random(0xc4a1);
        byte[] key = new byte[32];
        random.nextBytes(key);
        ReferenceChannel referenceTx = new ReferenceChannel(key), referenceRx =
                new ReferenceChannel(key);
        try (Channel tx = new Channel(key);
                Channel rx = new Channel(key)) {
            for (int record = 0; record < 600; record++) {
                if (record % 97 == 96) {
                    random.nextBytes(key);
                    tx.rekey(key);
                    rx.rekey(key);
                    // Rekeying keeps each direction's IV chain.
                    referenceTx.key = key.clone();
                    referenceRx.key = key.clone();
                }
                byte[] plain = new byte[1 + random.nextInt(record % 50 == 0 ? 70000 : 1600)];
                random.nextBytes(plain);
                byte[] expected = referenceTx.encrypt(plain);
                byte[] wire;
                if (record % 3 == 0) {
                    int split = random.nextInt(plain.length + 1);
                    wire =
                            tx.encrypt(
                                    Arrays.copyOf(plain, split),
                                    Arrays.copyOfRange(plain, split, plain.length));
                } else wire = tx.encrypt(plain);
                assertArrayEquals(expected, wire, "ciphertext " + record);
                if (record % 11 == 5 && wire.length > 16) {
                    // Tamper before the final block, which seeds the next record's IV.
                    byte[] tampered = wire.clone();
                    tampered[random.nextInt(tampered.length - 16)] ^= 1;
                    assertThrows(Exception.class, () -> referenceRx.decrypt(tampered));
                    assertThrows(IllegalArgumentException.class, () -> rx.decrypt(tampered));
                    continue; // both receive chains consumed the rejected record
                }
                assertArrayEquals(plain, referenceRx.decrypt(wire));
                if (record % 2 == 0) assertArrayEquals(plain, rx.decrypt(wire));
                else
                    assertArrayEquals(
                            plain, rx.decrypt(wire, (data, n) -> Arrays.copyOf(data, n)));
            }
        }
    }

    @Test
    void closedChannelStillRefusesWork() {
        Channel channel = new Channel(new byte[32]);
        byte[] wire = channel.encrypt(new byte[] {1});
        channel.close();
        assertThrows(IllegalStateException.class, () -> channel.encrypt(new byte[] {1}));
        assertThrows(IllegalStateException.class, () -> channel.decrypt(wire));
    }
}
