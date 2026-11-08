package io.github.gringoestrangeiro.openradplugin.session;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.ipv4;
import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ProvisionerStateTest {
    private static final long RID = 12345L;
    private static final String NAME = "Synthetic provisioner";
    private static final byte[] PASSWORD = "synthetic-issued-credential".getBytes(StandardCharsets.US_ASCII);

    @TempDir Path directory;

    @Test
    void obsoleteMarkerCannotPreventLoadingAValidIdentity() throws Exception {
        Path identityPath = directory.resolve("identity.json");
        try (Identity identity = issued()) {
            identity.save(identityPath);
        }
        Path content = obstructMarkerCleanup();

        try (Identity loaded =
                Provisioner.loadOrRegister(directory, NAME, "127.0.0.1", 1, ignored -> fail("must load identity"))) {
            assertEquals(RID, loaded.rid());
            assertArrayEquals(PASSWORD, loaded.password());
        }
        assertEquals("preserve unknown marker content", Files.readString(content));
    }

    @Test
    void savedIdentityIsNotRewrittenWhenObsoleteMarkerCannotBeDeleted() throws Exception {
        Path content = obstructMarkerCleanup(), destination = directory.resolve("identity.json");
        AtomicInteger attempts = new AtomicInteger();
        try (Identity identity = issued()) {
            Provisioner.saveIssued(identity, destination, content.getParent(), (same, path) -> {
                assertSame(identity, same);
                assertEquals(1, attempts.incrementAndGet(), "an already saved identity must not be rewritten");
                same.save(path);
            });
        }
        try (Identity loaded = Identity.load(destination)) {
            assertEquals(RID, loaded.rid());
        }
        assertEquals(1, attempts.get());
        assertEquals("preserve unknown marker content", Files.readString(content));
    }

    @Test
    @Timeout(20)
    void failedLocalMarkerWriteBeforeRegistrationLeavesNoPendingRequest() throws Exception {
        // A real filesystem without POSIX or ACL views can create the file but cannot secure it.
        try (var archive = FileSystems.newFileSystem(directory.resolve("profile.zip"), Map.of("create", "true"));
                var listener = new ServerSocket(0, 1, ipv4("127.0.0.1"));
                var executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())) {
            listener.setSoTimeout(5000);
            var service = executor.submit(() -> {
                try (var authenticated = SyntheticControlService.authenticate(
                        listener.accept(), 3, 0, "rpassword".getBytes(StandardCharsets.US_ASCII))) {
                    assertThrows(EOFException.class, authenticated::receive, "no Login may be sent");
                }
                return null;
            });

            Path profile = archive.getPath("/");
            IOException failure = assertThrows(IOException.class, () -> Provisioner.loadOrRegister(
                    profile, NAME, "127.0.0.1", listener.getLocalPort(),
                    ignored -> {}, SyntheticControlService.modulus()));
            assertEquals("private file permissions unavailable", failure.getMessage());
            service.get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(profile.resolve("registration.pending")));
            assertFalse(Files.exists(profile.resolve("identity.json")));
        }
    }

    private Path obstructMarkerCleanup() throws IOException {
        Path marker = Files.createDirectory(directory.resolve("registration.pending"));
        return Files.writeString(marker.resolve("unrelated"), "preserve unknown marker content");
    }

    private static Identity issued() {
        return new Identity(RID, ipv4("26.0.0.5"), NAME, new byte[16], PASSWORD, "127.0.0.1");
    }
}
