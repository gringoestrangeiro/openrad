package io.github.gringoestrangeiro.openradplugin.session;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.crypto.*;
import io.github.gringoestrangeiro.openradplugin.transport.FramedTcp;

import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Arrays;

/** Entirely synthetic RSA/SRP endpoint shared by local coordinator and control regression tests. */
public final class SyntheticControlService {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(521).subtract(BigInteger.ONE);
    private static final BigInteger Q = BigInteger.ONE.shiftLeft(607).subtract(BigInteger.ONE);
    private static final BigInteger N = P.multiply(Q);
    private static final BigInteger D =
            BigInteger.valueOf(65537)
                    .modInverse(P.subtract(BigInteger.ONE).multiply(Q.subtract(BigInteger.ONE)));

    private SyntheticControlService() {}

    public static byte[] modulus() {
        return unsigned(N);
    }

    public static Authenticated authenticate(
            Socket socket, int purpose, long expectedRid, byte[] password) throws IOException {
        FramedTcp stream = new FramedTcp(socket, Duration.ofSeconds(15));
        Channel cipher = null;
        try {
            assertArrayEquals(new byte[] {3, 0, 0, 0}, stream.receive(4));
            byte[] ciphertext = stream.receive(1024);
            byte[] encoded =
                    padded(
                            unsigned(new BigInteger(1, ciphertext).modPow(D, N)),
                            (N.bitLength() + 7) / 8);
            assertEquals(0, encoded[0]);
            assertEquals(2, encoded[1]);
            int separator = 2;
            while (separator < encoded.length && encoded[separator] != 0) separator++;
            assertTrue(separator >= 10 && separator + 77 == encoded.length);
            ByteBuffer message =
                    ByteBuffer.wrap(encoded, separator + 1, 76).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(3, message.getInt());
            assertEquals(0x4b, message.getInt());
            assertEquals(purpose, message.getInt());
            byte[] key = new byte[32];
            message.get(key);
            cipher = new Channel(key);
            Arrays.fill(key, (byte) 0);
            Arrays.fill(encoded, (byte) 0);
            stream.send(new byte[] {3, 0, 0, 0});
            byte[] hello = cipher.decrypt(stream.receive(65536));
            byte[] identity = ShRecord.parse(hello, 1, 0x20000000).getFirst();
            assertEquals(8, identity.length);
            long rid = ByteBuffer.wrap(identity).order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (expectedRid != 0) assertEquals(expectedRid, rid);
            try (ShServer server = new ShServer(rid, password)) {
                stream.send(cipher.encrypt(server.hello(hello)));
                stream.send(
                        cipher.encrypt(server.clientPublic(cipher.decrypt(stream.receive(65536)))));
                try (ShServer.Proof proof = server.proof(cipher.decrypt(stream.receive(65536)))) {
                    stream.send(cipher.encrypt(proof.message()));
                    byte[] expanded = proof.key();
                    key = Arrays.copyOf(expanded, 32);
                    try {
                        cipher.rekey(key);
                    } finally {
                        Arrays.fill(key, (byte) 0);
                        Arrays.fill(expanded, (byte) 0);
                    }
                }
            }
            return new Authenticated(stream, cipher, rid);
        } catch (IOException | RuntimeException | AssertionError e) {
            stream.close();
            if (cipher != null) cipher.close();
            throw e;
        }
    }

    public record Authenticated(FramedTcp stream, Channel cipher, long rid)
            implements AutoCloseable {
        public byte[] receive() throws IOException {
            return cipher.decrypt(stream.receive(4 * 1024 * 1024));
        }

        public void send(byte[] plain) throws IOException {
            stream.send(cipher.encrypt(plain));
        }

        @Override
        public void close() throws IOException {
            try {
                stream.close();
            } finally {
                cipher.close();
            }
        }
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static byte[] padded(byte[] bytes, int width) {
        byte[] result = new byte[width];
        System.arraycopy(bytes, 0, result, width - bytes.length, bytes.length);
        return result;
    }
}
