package io.github.gringoestrangeiro.openradplugin.crypto;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/** The legacy OpenRad wire primitives. These algorithms are mandated by the service. */
public final class Crypto {
    public static final String PRIME_HEX =
            "e634471e75f2d76a0a3f5e252c5a6efb48edad645d31d9ecbc400416e14f5c6c"
                    + "2ed378602d6eb8e9478b99e6474932393538b4be7177e0e05d3aaed17b5ae87a"
                    + "a20f3dad37af019518f45b6dde76b2bfdfb140f379cc15383b304b7975b90796"
                    + "7553cd6fdaf08d68832b603120430a501067d21beb2ce2275a20036a2dc80f1c"
                    + "9d3ab9221afd5baceda5c2a31077c54fc6f274aaeba17ddd0b5c68f4f8e751a8"
                    + "90a58648147a82e6f4f532ccdc939a12942799a713500e17dd4f6ff216cd54c7";
    static final byte[] PRIME = java.util.HexFormat.of().parseHex(PRIME_HEX);
    static final BigInteger N = number(PRIME);
    static final BigInteger G = BigInteger.valueOf(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    public static byte[] random(int length) {
        require(length >= 0 && length <= 4 * 1024 * 1024, "invalid random byte count");
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    public static byte[] hash(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK SHA-1 unavailable", e);
        }
    }

    public static byte[] auth8(byte[] region) {
        byte[] out = new byte[8];
        auth8(region, 0, region.length, out, 0);
        return out;
    }

    private static final VarHandle LE64 =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** Writes auth8(region[offset, offset + length)) to out[outOffset, outOffset + 8). */
    static void auth8(byte[] region, int offset, int length, byte[] out, int outOffset) {
        if (offset < 0 || length < 0 || offset > region.length - length)
            throw new ArrayIndexOutOfBoundsException("auth8 bounds");
        long sum = 0;
        int end = offset + length, at = offset;
        for (; end - at >= 8; at += 8) sum += (long) LE64.get(region, at);
        if (at < end) {
            long word = 0;
            for (int i = 0; at + i < end; i++) word |= (region[at + i] & 255L) << (i * 8);
            sum += word;
        }
        int[] state = Md4.digestLong(sum);
        // digest[i] + digest[i + 8], where the digest is the little-endian state words.
        for (int i = 0; i < 8; i++) {
            int shift = (i & 3) * 8;
            out[outOffset + i] =
                    (byte) ((state[i >> 2] >>> shift) + (state[2 + (i >> 2)] >>> shift));
        }
        Arrays.fill(state, 0);
    }

    /** Constant-time comparison of auth8(region) with the eight bytes at tag[tagOffset]. */
    static boolean auth8Matches(
            byte[] region, int offset, int length, byte[] tag, int tagOffset) {
        byte[] expected = new byte[8];
        auth8(region, offset, length, expected, 0);
        int difference = 0;
        for (int i = 0; i < 8; i++) difference |= expected[i] ^ tag[tagOffset + i];
        return difference == 0;
    }

    static BigInteger number(byte[] bytes) {
        return new BigInteger(1, bytes);
    }

    static byte[] serial(BigInteger n) {
        if (n.signum() == 0) return new byte[0];
        byte[] b = n.toByteArray();
        return b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    static byte[] padded(byte[] value, int width) {
        require(value.length <= width, "integer exceeds group width");
        byte[] out = new byte[width];
        System.arraycopy(value, 0, out, width - value.length, value.length);
        return out;
    }

    public static byte[] concat(byte[]... values) {
        int size = 0;
        for (byte[] value : values) size = Math.addExact(size, value.length);
        byte[] out = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, out, offset, value.length);
            offset += value.length;
        }
        return out;
    }

    static byte[] be32(int n) {
        return ByteBuffer.allocate(4).putInt(n).array();
    }

    static byte[] le32(int n) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array();
    }

    static byte[] le64(long n) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array();
    }

    public static byte[] identityBytes(long rid) {
        byte[] result = le64(rid);
        for (int i = 0; i < result.length; i += 2) {
            byte b = result[i];
            result[i] = result[i + 1];
            result[i + 1] = b;
        }
        return result;
    }

    /** Private-network verifier; networkIdentity is the CRC64-derived unsigned identifier. */
    public static byte[] networkVerifier(
            long networkIdentity, byte[] passwordWireBytes, byte[] salt) {
        require(salt.length == 32, "invalid verifier salt");
        BigInteger x = ShMath.x(networkIdentity, passwordWireBytes, salt);
        return concat(le32(32), salt, serial(G.modPow(x, N)));
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static void clear(byte[] value) {
        Arrays.fill(value, (byte) 0);
    }

    static BigInteger privateExponent() {
        return number(random(32)).add(BigInteger.valueOf(1536));
    }
}
