package io.github.gringoestrangeiro.openradplugin.crypto;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import java.util.Locale;

class IdentityTest {
    @TempDir Path directory;
    private static final String SYNTHETIC =
            "{\"format\":\"openrad-identity-v1\",\"rid\":18446744073709551615,\"vip\":\"26.0.0.5\",\"node_name\":\"Synthetic"
                + " 雪 🌐\","
                + "\"address\":\"000102030405060708090a0b0c0d0e0f\",\"credential\":\"010203040506\","
                + "\"server_address\":\"192.0.2.1\",\"legacy_credential\":\"\"}";

    private static Identity parse(String json) {
        return Identity.fromSecret(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rustSchemaRoundTripsUnsignedRidUnicodeAndOpaqueCredential() throws Exception {
        try (Identity identity = parse(SYNTHETIC)) {
            assertEquals(-1L, identity.rid());
            assertEquals("26.0.0.5", identity.vip().getHostAddress());
            assertEquals("Synthetic 雪 🌐", identity.nodeName());
            byte[] password = identity.password();
            password[0] = 0;
            assertEquals(1, identity.password()[0]);
            Path file = directory.resolve("identity.json");
            identity.save(file);
            try (Identity saved = Identity.load(file)) {
                assertEquals(identity.rid(), saved.rid());
                assertEquals(identity.nodeName(), saved.nodeName());
                assertArrayEquals(identity.password(), saved.password());
                assertArrayEquals(identity.address(), saved.address());
            }
            var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null)
                assertEquals(
                        PosixFilePermissions.fromString("rw-------"),
                        posix.readAttributes().permissions());
            else {
                var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
                assertNotNull(acl);
                var owner = acl.getOwner();
                assertTrue(
                        acl.getAcl().stream()
                                .filter(entry -> entry.type() == AclEntryType.ALLOW)
                                .allMatch(entry -> entry.principal().equals(owner)));
            }
            assertFalse(identity.toString().contains("010203040506"));
            assertFalse(identity.toString().contains("credential"));
        }
    }

    @Test
    void replacementIsAtomicAndPreservesExistingIdentityWhenNewOneCannotBeSaved() throws Exception {
        Path file = directory.resolve("identity.json");
        try (Identity first = parse(SYNTHETIC)) {
            first.save(file);
        }
        byte[] original = Files.readAllBytes(file);
        try (Identity bootstrap = Identity.bootstrap("Bootstrap", "192.0.2.1")) {
            assertThrows(IllegalArgumentException.class, () -> bootstrap.save(file));
        }
        assertArrayEquals(original, Files.readAllBytes(file));
        try (Identity replacement = parse(SYNTHETIC.replace("18446744073709551615", "42"))) {
            replacement.save(file);
        }
        try (Identity loaded = Identity.load(file)) {
            assertEquals(42, loaded.rid());
        }
        try (var paths = Files.list(directory)) {
            assertEquals(1, paths.count());
        }
    }

    @Test
    void saveAcceptsSymlinkedDataDirectoryAndStillRejectsSymlinkedIdentity() throws Exception {
        Assumptions.assumeTrue(
                !System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"),
                "symbolic links require a non-Windows filesystem");
        Path real = Files.createDirectory(directory.resolve("real"));
        Path link = Files.createSymbolicLink(directory.resolve("link"), real);
        Path file = link.resolve("identity.json");
        try (Identity identity = parse(SYNTHETIC)) {
            identity.save(file);
            assertFalse(Files.isSymbolicLink(real.resolve("identity.json")));
            try (Identity loaded = Identity.load(file)) {
                assertEquals(identity.rid(), loaded.rid());
                assertEquals(identity.nodeName(), loaded.nodeName());
                assertArrayEquals(identity.password(), loaded.password());
                assertArrayEquals(identity.address(), loaded.address());
            }
        }
        assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(real.resolve("identity.json")));
        Path other = Files.writeString(real.resolve("other.json"), "synthetic existing file");
        Path second =
                Files.createSymbolicLink(real.resolve("identity2.json"), real.resolve("other.json"));
        try (Identity identity = parse(SYNTHETIC)) {
            IOException failure = assertThrows(IOException.class, () -> identity.save(second));
            assertEquals("unsafe identity file path", failure.getMessage());
        }
        assertTrue(Files.isSymbolicLink(second));
        assertEquals("synthetic existing file", Files.readString(other));
    }

    @Test
    void malformedCredentialRecordIsRejectedWithoutEchoingIt() throws Exception {
        String[] invalid = {
            SYNTHETIC.replace("18446744073709551615", "0"),
            SYNTHETIC.replace("18446744073709551615", "18446744073709551616"),
            SYNTHETIC.replace("26.0.0.5", "0.0.0.0"),
            SYNTHETIC.replace("26.0.0.5", "255.255.255.255"),
            SYNTHETIC.replace("192.0.2.1", "example.invalid"),
            SYNTHETIC.replace("010203040506", "0102"),
            SYNTHETIC.replace("010203040506", "notsecret-validhex"),
            SYNTHETIC.replace("Synthetic 雪 🌐", "line\\nsecret-marker"),
            SYNTHETIC.replace("Synthetic 雪 🌐", "   "),
            SYNTHETIC.replace("Synthetic 雪 🌐", "\\ud800"),
            SYNTHETIC.replace("\"rid\":", "\"rid\":1,\"rid\":"),
            SYNTHETIC + "junk"
        };
        for (String input : invalid) {
            var exception = assertThrows(IllegalArgumentException.class, () -> parse(input));
            assertEquals("invalid identity file", exception.getMessage());
            assertNull(exception.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> Identity.fromSecret(new byte[65537]));
        assertThrows(
                IllegalArgumentException.class,
                () -> Identity.fromSecret(new byte[] {(byte) 0xff}));
        Path corrupt = directory.resolve("corrupt.json");
        Files.writeString(corrupt, "repair-me");
        assertThrows(IOException.class, () -> Identity.load(corrupt));
        assertEquals("repair-me", Files.readString(corrupt));
    }

    @Test
    void registrationParsesActualControlTlvFields() {
        byte[] fields =
                Crypto.concat(
                        TlvCodec.u64(0x020001e1, 12345),
                        TlvCodec.u32(0x01000305, 0x1a000005),
                        TlvCodec.textValue(0x03000304, "Synthetic"),
                        TlvCodec.encode(0x09000305, new byte[16]),
                        TlvCodec.encode(0x0a0001c8, HexFormat.of().parseHex("010203040506")),
                        TlvCodec.textValue(0x030001e2, "192.0.2.1"));
        byte[] registered =
                Crypto.concat(TlvCodec.u32(TlvCodec.SERVER_OP, 8), TlvCodec.encode(0x1233, fields));
        try (Identity identity = Identity.registered(registered)) {
            assertEquals(12345, identity.rid());
            assertEquals("26.0.0.5", identity.vip().getHostAddress());
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Identity.registered(
                                Crypto.concat(
                                        TlvCodec.u32(TlvCodec.SERVER_OP, 9),
                                        TlvCodec.encode(0x1233, fields))));
    }

    @Test
    void privateFileHelperRejectsDirectoriesAndRestrictsRegularFiles() throws Exception {
        assertThrows(IOException.class, () -> Identity.secureFile(directory));
        Path marker = Files.createFile(directory.resolve("registration-intent"));
        Identity.secureFile(marker);
        Files.writeString(marker, "synthetic registration marker");
        assertEquals("synthetic registration marker", Files.readString(marker));
    }
}
