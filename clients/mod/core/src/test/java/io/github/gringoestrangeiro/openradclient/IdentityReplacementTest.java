package io.github.gringoestrangeiro.openradclient;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

final class IdentityReplacementTest {
    @TempDir Path directory;

    private static Inet4Address ip(String text) throws IOException {
        return (Inet4Address) InetAddress.getByName(text);
    }

    private static void save(Path file, long rid, String vip) throws IOException {
        try (Identity identity = new Identity(rid, ip(vip), "OpenRad Minecraft", new byte[16],
                "password-1234".getBytes(), "127.0.0.1")) {
            identity.save(file);
        }
    }

    /** Behaves like Provisioner: reuse a stored identity, otherwise "register" a new one. */
    private IdentityReplacement.Registrar issuing(long rid, String vip, AtomicInteger registrations) {
        return staging -> {
            Path file = staging.resolve("identity.json");
            if (!Files.exists(file)) {
                registrations.incrementAndGet();
                save(file, rid, vip);
            }
            return Identity.load(file);
        };
    }

    @Test void newIdentityReplacesTheSavedOneOnlyAfterItWasIssued() throws Exception {
        save(directory.resolve("identity.json"), 1, "26.0.0.1");
        AtomicInteger registrations = new AtomicInteger();

        Inet4Address vip = IdentityReplacement.replace(directory, issuing(2, "26.0.0.2", registrations));

        assertEquals(ip("26.0.0.2"), vip);
        assertEquals(1, registrations.get());
        try (Identity saved = Identity.load(directory.resolve("identity.json"))) {
            assertEquals(2, saved.rid());
            assertEquals(ip("26.0.0.2"), saved.vip());
        }
        assertFalse(Files.exists(directory.resolve(IdentityReplacement.STAGING)));
    }

    @Test void failedRegistrationKeepsTheCurrentIdentity() throws Exception {
        save(directory.resolve("identity.json"), 1, "26.0.0.1");
        byte[] before = Files.readAllBytes(directory.resolve("identity.json"));

        IOException failure = assertThrows(IOException.class, () -> IdentityReplacement.replace(directory,
                staging -> { throw new IOException("coordinator unreachable"); }));

        assertEquals("coordinator unreachable", failure.getMessage());
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("identity.json")));
    }

    @Test void anIssuedReplacementThatWasNotInstalledIsReusedInsteadOfRegisteringAgain() throws Exception {
        save(directory.resolve("identity.json"), 1, "26.0.0.1");
        Path staging = directory.resolve(IdentityReplacement.STAGING);
        Files.createDirectories(staging);
        save(staging.resolve("identity.json"), 7, "26.0.0.7");
        AtomicInteger registrations = new AtomicInteger();

        assertEquals(ip("26.0.0.7"), IdentityReplacement.replace(directory, issuing(9, "26.0.0.9", registrations)));
        assertEquals(0, registrations.get(), "the device issued earlier must not be registered twice");
        try (Identity saved = Identity.load(directory.resolve("identity.json"))) {
            assertEquals(7, saved.rid());
        }
    }

    @Test void aProfileWithoutAnIdentityStillGetsOne() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        assertEquals(ip("26.0.0.3"), IdentityReplacement.replace(directory, issuing(3, "26.0.0.3", registrations)));
        assertTrue(Files.exists(directory.resolve("identity.json")));
    }

    @Test void refusesWhileARuntimeStillOwnsTheIdentity() throws Exception {
        save(directory.resolve("identity.json"), 1, "26.0.0.1");
        AtomicInteger registrations = new AtomicInteger();
        try (FileChannel owner = FileChannel.open(directory.resolve("identity.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = owner.lock()) {
            assertThrows(IOException.class,
                    () -> IdentityReplacement.replace(directory, issuing(2, "26.0.0.2", registrations)));
        }
        assertEquals(0, registrations.get());
        try (Identity saved = Identity.load(directory.resolve("identity.json"))) {
            assertEquals(1, saved.rid());
        }
    }
}
