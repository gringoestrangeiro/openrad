package io.github.gringoestrangeiro.openradplugin.crypto;

import static io.github.gringoestrangeiro.openradplugin.crypto.Crypto.*;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.random.RandomGenerator;

/** RSAES-PKCS1-v1_5 registration envelope with the service's little-endian purpose header. */
public final class RsaSession {
    private RsaSession() {}

    public static Result create(byte[] modulus, int purpose) {
        return create(modulus, purpose, new java.security.SecureRandom());
    }

    static Result create(byte[] modulus, int purpose, RandomGenerator random) {
        BigInteger n = number(modulus), e = BigInteger.valueOf(65537);
        require(
                (purpose == 3 || purpose == 4 || purpose == 5)
                        && n.compareTo(e) > 0
                        && n.testBit(0),
                "invalid RSA parameters");
        int width = (n.bitLength() + 7) / 8;
        require(width >= 87 && width <= 1024, "unsupported RSA width");
        byte[] secret = new byte[64];
        random.nextBytes(secret);
        byte[] message = concat(le32(3), le32(0x4b), le32(purpose), secret);
        int paddingLength = width - message.length - 3;
        require(paddingLength >= 8, "RSA message too long");
        byte[] encoded = new byte[width];
        encoded[1] = 2;
        int offset = 2;
        for (int round = 0; round < 128 && offset < paddingLength + 2; round++) {
            byte[] candidates = new byte[paddingLength + 2 - offset];
            random.nextBytes(candidates);
            for (byte value : candidates) if (value != 0) encoded[offset++] = value;
            clear(candidates);
        }
        try {
            require(offset == paddingLength + 2, "RSA randomness failure");
            System.arraycopy(message, 0, encoded, offset + 1, message.length);
            BigInteger value = number(encoded);
            require(value.compareTo(n) < 0, "RSA encoded message exceeds modulus");
            return new Result(padded(serial(value.modPow(e, n)), width), secret);
        } finally {
            clear(encoded);
            clear(message);
            clear(secret);
        }
    }

    public record Result(byte[] ciphertext, byte[] secret) implements AutoCloseable {
        public Result {
            ciphertext = ciphertext.clone();
            secret = secret.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public byte[] secret() {
            return secret.clone();
        }

        @Override
        public String toString() {
            return "RsaSession.Result[redacted]";
        }

        @Override
        public void close() {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
