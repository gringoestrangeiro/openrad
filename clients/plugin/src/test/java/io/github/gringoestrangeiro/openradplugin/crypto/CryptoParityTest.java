package io.github.gringoestrangeiro.openradplugin.crypto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

class CryptoParityTest {
    private static final byte[] PASSWORD =
            "controlled-test-password".getBytes(StandardCharsets.UTF_8);

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    private static String fixture(String name) throws Exception {
        try (var input = CryptoParityTest.class.getResourceAsStream("/crypto/" + name + ".json")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String field(String input, String field) {
        var matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(input);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private static List<byte[]> messages(String input) {
        var array =
                Pattern.compile("\"messages\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(input);
        assertTrue(array.find());
        var values = Pattern.compile("\"([0-9a-f]+)\"").matcher(array.group(1));
        List<byte[]> out = new ArrayList<>();
        while (values.find()) out.add(hex(values.group(1)));
        assertEquals(6, out.size());
        return out;
    }

    @Test
    void md4MatchesIndependentRfc1320Vectors() {
        String[][] cases = {
            {"", "31d6cfe0d16ae931b73c59d7e0c089c0"},
            {"a", "bde52cb31de33e46245e05fbdbd6fb24"},
            {"abc", "a448017aaf21d8525fc10ae87aa6729d"},
            {"message digest", "d9130a8164549fe818874806e1c7014b"},
            {"abcdefghijklmnopqrstuvwxyz", "d79e1c308aa5bbcdeea8ed63df412da9"},
            {
                "12345678901234567890123456789012345678901234567890123456789012345678901234567890",
                "e33b4ddc9c38f2199c3e7b164fcc0536"
            }
        };
        for (String[] test : cases)
            assertArrayEquals(
                    hex(test[1]), Md4.digest(test[0].getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void everyRustCiphertextMatchesAcrossLengthsChainingAndRekey() throws Exception {
        String fixture = fixture("reference");
        var records =
                Pattern.compile(
                                "\"pt\"\\s*:\\s*\"([0-9a-f]+)\"\\s*,\\s*\"ct\"\\s*:\\s*\"([0-9a-f]+)\"")
                        .matcher(fixture);
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        try (Channel tx = new Channel(key);
                Channel rx = new Channel(key)) {
            int count = 0;
            while (records.find()) {
                if (records.start() > fixture.indexOf("\"rekey\"")) {
                    for (int i = 0; i < key.length; i++) key[i] = (byte) (32 + i);
                    tx.rekey(key);
                    rx.rekey(key);
                }
                byte[] plain = hex(records.group(1)), wire = hex(records.group(2));
                assertArrayEquals(wire, tx.encrypt(plain), "Rust ciphertext " + count);
                assertArrayEquals(plain, rx.decrypt(wire), "Rust plaintext " + count);
                count++;
            }
            assertEquals(12, count);
        }
    }

    @Test
    void channelRejectsTamperingButConsumesLastCiphertextIv() {
        try (Channel tx = new Channel(new byte[32]);
                Channel rx = new Channel(new byte[32])) {
            byte[] bad =
                    tx.encrypt(
                            "authenticated message long enough"
                                    .getBytes(StandardCharsets.US_ASCII));
            bad[0] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> rx.decrypt(bad));
            byte[] next = {1, 2, 3};
            assertArrayEquals(next, rx.decrypt(tx.encrypt(next)));
            assertThrows(IllegalArgumentException.class, () -> rx.decrypt(new byte[15]));
            assertThrows(IllegalArgumentException.class, () -> tx.encrypt(new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> tx.rekey(new byte[31]));
        }
    }

    @Test
    void clientMatchesEveryRustMessageAndValidatesServerProofBeforeReleasingKey() throws Exception {
        String fixture = fixture("reference");
        List<byte[]> m = messages(fixture);
        try (ShClient client = new ShClient(12345, PASSWORD, BigInteger.valueOf(2000))) {
            assertArrayEquals(m.get(0), client.start());
            assertArrayEquals(m.get(2), client.parameters(m.get(1)));
            assertArrayEquals(m.get(4), client.challenge(m.get(3)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.confirm(ShRecord.message(6, 0x70000000, new byte[20])));
            assertArrayEquals(hex(field(fixture, "key")), client.confirm(m.get(5)));
            assertThrows(IllegalArgumentException.class, () -> client.confirm(m.get(5)));
        }
    }

    @Test
    void serverMatchesRustIncomingFixtureAndConsumesFailedProof() throws Exception {
        String fixture = fixture("incoming-sh");
        List<byte[]> m = messages(fixture);
        byte[] salt = hex(field(fixture, "salt"));
        try (ShServer server = new ShServer(12345, PASSWORD, salt, BigInteger.valueOf(3000))) {
            assertArrayEquals(m.get(1), server.hello(m.get(0)));
            assertArrayEquals(m.get(3), server.clientPublic(m.get(2)));
            ShServer.Proof proof = server.proof(m.get(4));
            assertArrayEquals(m.get(5), proof.message());
            assertArrayEquals(hex(field(fixture, "key")), proof.key());
        }
        try (ShServer server = new ShServer(12345, PASSWORD, salt, BigInteger.valueOf(3000))) {
            server.hello(m.get(0));
            server.clientPublic(m.get(2));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> server.proof(ShRecord.message(5, 0x70000000, new byte[20])));
            assertThrows(IllegalArgumentException.class, () -> server.proof(m.get(4)));
        }
    }

    @Test
    void shRejectsWrongStateGroupZeroPeerAndTrailingData() throws Exception {
        List<byte[]> m = messages(fixture("reference"));
        try (ShClient client = new ShClient(12345, PASSWORD, BigInteger.valueOf(2000))) {
            assertThrows(IllegalArgumentException.class, () -> client.parameters(m.get(1)));
            client.start();
            byte[] wrong = m.get(1).clone();
            wrong[12] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> client.parameters(wrong));
            client.parameters(m.get(1));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.challenge(ShRecord.message(4, 0x60000000, new byte[1])));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.challenge(Arrays.copyOf(m.get(3), m.get(3).length + 1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.challenge(ShRecord.message(4, 0x60000000, Crypto.PRIME)));
        }
        assertArrayEquals(hex("0123456789abcdef"), Crypto.identityBytes(0xcdef89ab45670123L));
    }

    @Test
    void registrationRsaDecryptsWithIndependentPrivateOperation() {
        BigInteger p = BigInteger.ONE.shiftLeft(521).subtract(BigInteger.ONE);
        BigInteger q = BigInteger.ONE.shiftLeft(607).subtract(BigInteger.ONE);
        BigInteger modulus = p.multiply(q);
        BigInteger d =
                BigInteger.valueOf(65537)
                        .modInverse(
                                p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE)));
        int width = (modulus.bitLength() + 7) / 8;
        for (int purpose : new int[] {3, 4, 5}) {
            try (RsaSession.Result result = RsaSession.create(Crypto.serial(modulus), purpose)) {
                assertEquals(width, result.ciphertext().length);
                byte[] encoded =
                        Crypto.padded(
                                Crypto.serial(
                                        Crypto.number(result.ciphertext()).modPow(d, modulus)),
                                width);
                assertEquals(0, encoded[0]);
                assertEquals(2, encoded[1]);
                int separator = 2;
                while (encoded[separator] != 0) separator++;
                assertTrue(separator >= 10);
                assertArrayEquals(
                        Crypto.concat(
                                Crypto.le32(3),
                                Crypto.le32(0x4b),
                                Crypto.le32(purpose),
                                result.secret()),
                        Arrays.copyOfRange(encoded, separator + 1, encoded.length));
                assertFalse(result.toString().contains(HexFormat.of().formatHex(result.secret())));
            }
        }
        assertThrows(
                IllegalArgumentException.class, () -> RsaSession.create(Crypto.serial(modulus), 2));
        assertThrows(IllegalArgumentException.class, () -> RsaSession.create(new byte[] {2}, 3));
    }

    @Test
    void networkVerifierMatchesRustUtf8NulTerminatedPasswordVector() throws Exception {
        String fixture = fixture("network-verifier");
        byte[] wirePassword = "synthetic password é\0".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(
                hex(field(fixture, "verifier")),
                Crypto.networkVerifier(
                        Long.parseUnsignedLong("10977597741686862730"),
                        wirePassword,
                        hex(field(fixture, "salt"))));
    }
}
