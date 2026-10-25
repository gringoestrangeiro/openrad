package io.github.gringoestrangeiro.openradplugin.crypto;

import static io.github.gringoestrangeiro.openradplugin.crypto.Crypto.*;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/** OpenRad's SHA-1 based SRP variant, including its nonstandard identity byte ordering. */
public final class ShClient implements AutoCloseable {
    private final long rid;
    private final byte[] password;
    private BigInteger privateValue;
    private int state;
    private byte[] salt = new byte[0],
            publicValue = new byte[0],
            key = new byte[0],
            expected = new byte[0];

    public ShClient(long rid, byte[] password) {
        this(rid, password, privateExponent());
    }

    /** Deterministic exponent injection for synthetic interoperability vectors. */
    public ShClient(long rid, byte[] password, BigInteger privateValue) {
        require(
                password.length > 5 && privateValue.signum() > 0,
                "invalid SH credentials/exponent");
        this.rid = rid;
        this.password = password.clone();
        this.privateValue = privateValue;
    }

    public byte[] start() {
        require(state == 0, "SH already started");
        state = 2;
        return ShRecord.message(1, 0x20000000, le64(rid));
    }

    public byte[] parameters(byte[] data) {
        require(state == 2, "SH unexpected parameters");
        List<byte[]> values = ShRecord.parse(data, 2, 0x30000000, 0x40000000, 0x50000000);
        require(
                Arrays.equals(values.get(0), PRIME) && Arrays.equals(values.get(1), new byte[] {5}),
                "unsupported SH group");
        salt = values.get(2).clone();
        publicValue = serial(G.modPow(privateValue, N));
        state = 4;
        return ShRecord.message(3, 0x60000000, publicValue);
    }

    public byte[] challenge(byte[] data) {
        require(state == 4, "SH unexpected challenge");
        byte[] b = ShRecord.parse(data, 4, 0x60000000).getFirst();
        BigInteger bv = ShMath.peerPublic(b),
                x = ShMath.x(rid, password, salt),
                k = ShMath.multiplier();
        require(k.signum() != 0, "zero SH multiplier");
        BigInteger u = ShMath.scrambling(publicValue, b), v = G.modPow(x, N);
        BigInteger base = bv.subtract(k.multiply(v).mod(N)).mod(N);
        key = ShMath.key(base.modPow(privateValue.add(u.multiply(x)), N));
        byte[] proof = ShMath.m1(rid, salt, publicValue, b, key);
        expected = hash(concat(publicValue, proof, key));
        state = 6;
        return ShRecord.message(5, 0x70000000, proof);
    }

    public byte[] confirm(byte[] data) {
        require(state == 6, "SH unexpected confirmation");
        byte[] proof = ShRecord.parse(data, 6, 0x70000000).getFirst();
        require(MessageDigest.isEqual(proof, expected), "SH server proof rejected");
        state = 7;
        return key.clone();
    }

    @Override
    public void close() {
        clear(password);
        clear(salt);
        clear(publicValue);
        clear(key);
        clear(expected);
        privateValue = BigInteger.ZERO;
        state = -1;
    }
}
