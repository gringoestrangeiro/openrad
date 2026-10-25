package io.github.gringoestrangeiro.openradplugin.crypto;

import static io.github.gringoestrangeiro.openradplugin.crypto.Crypto.*;

import java.math.BigInteger;

final class ShMath {
    private ShMath() {}

    static BigInteger x(long rid, byte[] password, byte[] salt) {
        return number(
                hash(concat(salt, hash(concat(identityBytes(rid), new byte[] {':'}, password)))));
    }

    static BigInteger multiplier() {
        return number(hash(concat(PRIME, padded(new byte[] {5}, PRIME.length))));
    }

    static BigInteger scrambling(byte[] a, byte[] b) {
        return number(hash(concat(padded(a, PRIME.length), padded(b, PRIME.length))));
    }

    static byte[] key(BigInteger shared) {
        byte[] bytes = serial(shared);
        try {
            return concat(hash(concat(bytes, be32(0))), hash(concat(bytes, be32(1))));
        } finally {
            clear(bytes);
        }
    }

    static byte[] m1(long rid, byte[] salt, byte[] a, byte[] b, byte[] key) {
        byte[] prefix = hash(PRIME), generator = hash(new byte[] {5});
        for (int i = 0; i < prefix.length; i++) prefix[i] ^= generator[i];
        return hash(concat(prefix, hash(identityBytes(rid)), salt, a, b, key));
    }

    static BigInteger peerPublic(byte[] bytes) {
        BigInteger value = number(bytes);
        require(
                bytes.length <= PRIME.length && value.signum() != 0 && value.compareTo(N) < 0,
                "invalid SH peer public");
        return value;
    }
}
