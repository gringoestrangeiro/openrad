package io.github.gringoestrangeiro.openradplugin.crypto;

import static io.github.gringoestrangeiro.openradplugin.crypto.Crypto.*;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

/** Incoming peer authentication. A failed proof consumes the handshake. */
public final class ShServer implements AutoCloseable {
    private final long rid;
    private final byte[] password, salt;
    private BigInteger privateValue;
    private int state = 1;
    private byte[] key = new byte[0], m1 = new byte[0], m2 = new byte[0];

    public ShServer(long rid, byte[] password) {
        this(rid, password, random(16), privateExponent());
    }

    public ShServer(long rid, byte[] password, byte[] salt, BigInteger privateValue) {
        require(
                rid != 0 && password.length > 5 && salt.length == 16 && privateValue.signum() > 0,
                "invalid SH server parameters");
        this.rid = rid;
        this.password = password.clone();
        this.salt = salt.clone();
        this.privateValue = privateValue;
    }

    public byte[] hello(byte[] data) {
        require(state == 1, "SH unexpected hello");
        require(
                Arrays.equals(ShRecord.parse(data, 1, 0x20000000).get(0), le64(rid)),
                "SH identity mismatch");
        state = 3;
        return concat(
                ShRecord.message(2, 0x30000000, PRIME),
                ShRecord.record(0x40000000, new byte[] {5}),
                ShRecord.record(0x50000000, salt));
    }

    public byte[] clientPublic(byte[] data) {
        require(state == 3, "SH unexpected client public");
        byte[] a = ShRecord.parse(data, 3, 0x60000000).get(0);
        BigInteger av = ShMath.peerPublic(a), x = ShMath.x(rid, password, salt), v = G.modPow(x, N);
        byte[] b = serial(ShMath.multiplier().multiply(v).add(G.modPow(privateValue, N)).mod(N));
        BigInteger u = ShMath.scrambling(a, b);
        require(u.signum() != 0, "zero SH scrambling parameter");
        key = ShMath.key(av.multiply(v.modPow(u, N)).mod(N).modPow(privateValue, N));
        m1 = ShMath.m1(rid, salt, a, b, key);
        m2 = hash(concat(a, m1, key));
        state = 5;
        return ShRecord.message(4, 0x60000000, b);
    }

    public Proof proof(byte[] data) {
        require(state == 5, "SH unexpected client proof");
        state = 7;
        require(
                MessageDigest.isEqual(ShRecord.parse(data, 5, 0x70000000).get(0), m1),
                "SH client proof rejected");
        return new Proof(ShRecord.message(6, 0x70000000, m2), key);
    }

    public record Proof(byte[] message, byte[] key) implements AutoCloseable {
        public Proof {
            message = message.clone();
            key = key.clone();
        }

        @Override
        public byte[] message() {
            return message.clone();
        }

        @Override
        public byte[] key() {
            return key.clone();
        }

        @Override
        public String toString() {
            return "Proof[redacted]";
        }

        @Override
        public void close() {
            clear(message);
            clear(key);
        }
    }

    @Override
    public void close() {
        clear(password);
        clear(salt);
        clear(key);
        clear(m1);
        clear(m2);
        privateValue = BigInteger.ZERO;
        state = -1;
    }
}
