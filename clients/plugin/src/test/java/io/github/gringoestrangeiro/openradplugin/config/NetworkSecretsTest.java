package io.github.gringoestrangeiro.openradplugin.config;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

final class NetworkSecretsTest {
    @TempDir Path directory;

    private static VpnSettings settings(boolean enabled) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                bridge:
                  enabled: %s
                vpn:
                  networks:
                    - name: Private
                      secret: missing-key
                    - name: Other
                      secret: short-key
                """.formatted(enabled));
        return VpnSettings.from(yaml);
    }

    @Test
    void disabledReplacementNeedsNoSecrets() throws Exception {
        Files.writeString(directory.resolve("secrets.properties"), "short-key=abc\n");
        VpnSettings disabled = settings(false);
        try (NetworkSecrets secrets = NetworkSecrets.loadFor(directory, disabled)) {
            assertDoesNotThrow(() -> secrets.validate(disabled));
        }
        Files.delete(directory.resolve("secrets.properties"));
        try (NetworkSecrets secrets = NetworkSecrets.loadFor(directory, disabled)) {
            assertDoesNotThrow(() -> secrets.validate(disabled));
        }
        assertFalse(Files.exists(directory.resolve("secrets.properties")), "disabled reload never reads or creates it");
    }

    @Test
    void enabledReplacementStillRequiresValidSecrets() throws Exception {
        VpnSettings enabled = settings(true);
        Files.writeString(directory.resolve("secrets.properties"), "short-key=abc\n");
        try (NetworkSecrets secrets = NetworkSecrets.loadFor(directory, enabled)) {
            var invalid = assertThrows(IllegalArgumentException.class, () -> secrets.validate(enabled));
            assertTrue(invalid.getMessage().contains("missing-key") || invalid.getMessage().contains("6..256"),
                    invalid.getMessage());
        }
        Files.writeString(directory.resolve("secrets.properties"),
                "short-key=abc\nmissing-key=password-two\n");
        try (NetworkSecrets secrets = NetworkSecrets.loadFor(directory, enabled)) {
            var tooShort = assertThrows(IllegalArgumentException.class, () -> secrets.validate(enabled));
            assertTrue(tooShort.getMessage().contains("6..256"), tooShort.getMessage());
        }
        Files.writeString(directory.resolve("secrets.properties"), "short-key=password-one\n");
        try (NetworkSecrets secrets = NetworkSecrets.loadFor(directory, enabled)) {
            assertThrows(IllegalArgumentException.class, () -> secrets.validate(enabled));
        }
    }

    @Test
    void createIfMissingWritesTheTemplateOnlyOnce() throws Exception {
        NetworkSecrets.createIfMissing(directory);
        Path file = directory.resolve("secrets.properties");
        assertTrue(Files.readString(file).startsWith("# Private network passwords"));
        Files.writeString(file, "kept=password-one\n");
        NetworkSecrets.createIfMissing(directory);
        assertEquals("kept=password-one\n", Files.readString(file));
    }
}
