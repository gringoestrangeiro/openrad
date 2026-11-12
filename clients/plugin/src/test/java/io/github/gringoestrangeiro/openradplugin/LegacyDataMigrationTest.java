package io.github.gringoestrangeiro.openradplugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LegacyDataMigrationTest {
    @TempDir Path directory;

    @Test
    void pendingRegistrationPreventsASecondProfileFromStarting() throws Exception {
        Path parent = Files.createDirectory(directory.resolve("Perfil configura\u00e7\u00e3o"));
        Path legacy = Files.createDirectory(parent.resolve("OpenRadBridge"));
        Path destination = Files.createDirectory(parent.resolve("OpenRadPlugin"));
        Path marker = Files.writeString(legacy.resolve("registration.pending"), "synthetic marker");

        assertThrows(IOException.class, () -> OpenRadPlugin.migrateLegacyData(destination));
        assertEquals("synthetic marker", Files.readString(marker));
        assertFalse(Files.exists(destination.resolve("registration.pending")));
    }

    @Test
    void legacyRegistrationMarkerMovesWithTheProfile() throws Exception {
        Path legacy = Files.createDirectory(directory.resolve("OpenRadBridge"));
        Files.writeString(legacy.resolve("registration.pending"), "synthetic marker");
        Path destination = directory.resolve("OpenRadPlugin");

        assertTrue(OpenRadPlugin.migrateLegacyData(destination));
        assertFalse(Files.exists(legacy));
        assertEquals("synthetic marker", Files.readString(destination.resolve("registration.pending")));
        assertFalse(OpenRadPlugin.migrateLegacyData(destination));
    }

    @Test
    void alreadyRegisteredDestinationIsPreserved() throws Exception {
        Path legacy = Files.createDirectory(directory.resolve("OpenRadBridge"));
        Files.writeString(legacy.resolve("registration.pending"), "synthetic marker");
        Path destination = Files.createDirectory(directory.resolve("OpenRadPlugin"));
        Path identity = Files.writeString(destination.resolve("identity.json"), "synthetic identity");

        assertFalse(OpenRadPlugin.migrateLegacyData(destination));
        assertEquals("synthetic identity", Files.readString(identity));
        assertTrue(Files.exists(legacy.resolve("registration.pending")));
    }
}
