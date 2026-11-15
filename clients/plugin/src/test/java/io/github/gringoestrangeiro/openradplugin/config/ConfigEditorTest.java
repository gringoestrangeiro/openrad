package io.github.gringoestrangeiro.openradplugin.config;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

final class ConfigEditorTest {
    @TempDir Path directory;

    private void defaults() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            Files.copy(in, directory.resolve("config.yml"));
        }
    }

    private VpnSettings settings() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(directory.resolve("config.yml")));
        return VpnSettings.from(yaml);
    }

    @Test
    void joinPrivateNetworkEnablesBridgeAndStoresPasswordOutsideYaml() throws Exception {
        defaults();
        var result = new ConfigEditor(directory).join("Minha Rede", "s3nha=#!\\".toCharArray());
        assertTrue(result.added());
        assertEquals("rede-minha-rede", result.secretKey());
        VpnSettings settings = settings();
        assertTrue(settings.bridge().enabled());
        assertEquals("rede-minha-rede", settings.networks().getFirst().secret());
        String yaml = Files.readString(directory.resolve("config.yml"));
        assertFalse(yaml.contains("s3nha"));
        assertTrue(yaml.contains("Passwords belong in secrets.properties"), "comments kept");
        try (NetworkSecrets secrets = NetworkSecrets.load(directory)) {
            assertArrayEquals("s3nha=#!\\".toCharArray(), secrets.password("rede-minha-rede"));
            secrets.validate(settings);
        }
    }

    @Test
    void rejoinKeepsAclsAndLeaveRemovesSecret() throws Exception {
        defaults();
        var editor = new ConfigEditor(directory);
        var original = editor.join("Private", "password-one".toCharArray());
        var updated = editor.join("Private", "password-two".toCharArray());
        assertFalse(updated.added());
        assertNotEquals(original.secretKey(), updated.secretKey());
        editor.join("Public", null);
        try (NetworkSecrets secrets = NetworkSecrets.load(directory)) {
            assertArrayEquals("password-two".toCharArray(), secrets.password(updated.secretKey()));
            assertThrows(IllegalArgumentException.class, () -> secrets.password(original.secretKey()));
        }
        assertEquals(updated.secretKey(), editor.join("Private", null).secretKey());
        assertEquals(2, settings().networks().size());
        assertTrue(editor.leave("Private"));
        assertFalse(editor.leave("Private"));
        assertFalse(Files.readString(directory.resolve("secrets.properties")).contains("password"));
        assertEquals(List.of("Public"), settings().networks().stream().map(VpnSettings.Network::name).toList());
    }

    @Test
    void invalidPasswordIsRejectedWithoutChangingFiles() throws Exception {
        defaults();
        String before = Files.readString(directory.resolve("config.yml"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConfigEditor(directory).join("Private", "short".toCharArray()));
        assertEquals(before, Files.readString(directory.resolve("config.yml"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(directory.resolve("secrets.properties")));
    }

    @Test
    void publicSelectionAndDisable() throws Exception {
        defaults();
        var editor = new ConfigEditor(directory);
        editor.publicSelection(List.of("all-ptbr"));
        assertEquals(List.of("all-ptbr"), settings().minecraftNetworks().select());
        assertTrue(settings().bridge().enabled());
        editor.enabled(false);
        assertFalse(settings().bridge().enabled());
    }

    @Test
    void offlinePublicSelectionDoesNotPersistARejectedConfiguration() throws Exception {
        defaults();
        Path config = directory.resolve("config.yml");
        String before = Files.readString(config);
        var editor = new ConfigEditor(directory, false);
        assertThrows(IllegalArgumentException.class, () -> editor.publicSelection(List.of("all-ptbr")));
        assertEquals(before, Files.readString(config));

        Files.writeString(config, before.replace("allow-insecure-offline-public-networks: false",
                "allow-insecure-offline-public-networks: true"));
        editor.publicSelection(List.of("all-ptbr"));
        assertEquals(List.of("all-ptbr"), settings().minecraftNetworks().select());
    }

    @Test
    void joiningDoesNotPublishFilesWhenAnotherConfiguredPasswordIsMissing() throws Exception {
        Path config = directory.resolve("config.yml");
        String before = """
                vpn:
                  networks:
                    - name: Existing private network
                      secret: missing-password
                """;
        Files.writeString(config, before);
        var editor = new ConfigEditor(directory);
        assertThrows(IllegalArgumentException.class,
                () -> editor.join("New private network", "synthetic-password".toCharArray()));
        assertEquals(before, Files.readString(config));
        assertFalse(Files.exists(directory.resolve("secrets.properties")));
    }

    @Test
    void invalidCustomCoordinatorPreventsPublishingAnEnabledConfig() throws Exception {
        defaults();
        Files.writeString(directory.resolve("coordinator.properties"), "host=127.0.0.1\n");
        Path config = directory.resolve("config.yml");
        String before = Files.readString(config);

        assertThrows(java.io.IOException.class,
                () -> new ConfigEditor(directory).publicSelection(List.of("all")));
        assertEquals(before, Files.readString(config));
    }

    @Test
    void aNetworkWithAnInvalidPasswordCanStillBeRemoved() throws Exception {
        defaults();
        var editor = new ConfigEditor(directory);
        var result = editor.join("Private", "password-one".toCharArray());
        Path secrets = directory.resolve("secrets.properties");
        Files.writeString(secrets, result.secretKey() + "=short\n");

        assertThrows(IllegalArgumentException.class, () -> editor.enabled(true));
        assertTrue(editor.leave("Private"));
        assertTrue(settings().networks().isEmpty());
        assertFalse(Files.readString(secrets).contains(result.secretKey()));
    }

    @Test
    void editCannotProduceAConfigurationThatExceedsTheReadLimit() throws Exception {
        Path config = directory.resolve("config.yml");
        String before = "extra: " + "x".repeat(256 * 1024 - 8) + "\n";
        Files.writeString(config, before);
        var editor = new ConfigEditor(directory);
        assertThrows(java.io.IOException.class, () -> editor.publicSelection(List.of("all")));
        assertEquals(before, Files.readString(config));
    }

    @Test
    void removingASecretPreservesACommentEndingInBackslash() throws Exception {
        defaults();
        var editor = new ConfigEditor(directory);
        var result = editor.join("Private", "password-one".toCharArray());
        Path secrets = directory.resolve("secrets.properties");
        String comment = "# Retain this comment\\\n";
        Files.writeString(secrets, comment + result.secretKey() + "=password-one\n");

        assertTrue(editor.leave("Private"));
        assertEquals(comment, Files.readString(secrets));
    }

    @Test
    void forceRelayCommandPersistsAndRestoresDirectTransports() throws Exception {
        defaults();
        var editor = new ConfigEditor(directory);
        assertFalse(settings().relayOnly());
        editor.forceRelay(true);
        VpnSettings forced = settings();
        assertTrue(forced.relayOnly());
        assertFalse(forced.directTcp());
        assertFalse(forced.directUdp());
        String yaml = Files.readString(directory.resolve("config.yml"));
        assertTrue(yaml.contains("Passwords belong in secrets.properties"), "comments kept");
        editor.forceRelay(false);
        VpnSettings restored = settings();
        assertFalse(restored.relayOnly());
        assertTrue(restored.directTcp());
        assertTrue(restored.directUdp());
    }

    @Test
    void repeatedEditsDoNotGrowTheConfigFile() throws Exception {
        defaults();
        var editor = new ConfigEditor(directory);
        editor.forceRelay(true);
        editor.forceRelay(false);
        long settled = Files.size(directory.resolve("config.yml"));
        for (int i = 0; i < 12; i++) editor.forceRelay(i % 2 == 0);
        editor.forceRelay(false);
        assertEquals(settled, Files.size(directory.resolve("config.yml")));
        assertTrue(Files.readString(directory.resolve("config.yml")).contains("Passwords belong"));
    }

    @Test
    void forceRelayCommandWithRelayDisabledLeavesFileUnchanged() throws Exception {
        Files.writeString(
                directory.resolve("config.yml"), "vpn:\n  transports:\n    relay: false\n");
        String before = Files.readString(directory.resolve("config.yml"));
        assertThrows(
                IllegalArgumentException.class, () -> new ConfigEditor(directory).forceRelay(true));
        assertEquals(before, Files.readString(directory.resolve("config.yml")));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void rejectedConfigReplacementPreservesThePreviouslyReferencedPassword() throws Exception {
        defaults();
        var editor = new ConfigEditor(directory);
        editor.join("Private", "password-one".toCharArray());
        Path config = directory.resolve("config.yml");
        String before = Files.readString(config);
        var dos = Files.getFileAttributeView(config, java.nio.file.attribute.DosFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(dos != null, "DOS readonly fixture requires Windows");
        dos.setReadOnly(true);
        try {
            assertThrows(java.io.IOException.class,
                    () -> editor.join("Private", "password-two".toCharArray()));
        } finally {
            dos.setReadOnly(false);
        }
        assertEquals(before, Files.readString(config));
        try (NetworkSecrets secrets = NetworkSecrets.load(directory)) {
            assertArrayEquals("password-one".toCharArray(),
                    secrets.password(settings().networks().getFirst().secret()));
        }
    }

    @Test
    void changingOneNetworkPasswordPreservesASecretStillUsedByAnotherNetwork() throws Exception {
        defaults();
        var editor = new ConfigEditor(directory);
        var original = editor.join("Private", "password-one".toCharArray());
        editor.join("Other", null);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(directory.resolve("config.yml")));
        var entries = yaml.getMapList("vpn.networks");
        var other = new java.util.LinkedHashMap<String, Object>();
        entries.get(1).forEach((key, value) -> other.put(String.valueOf(key), value));
        other.put("secret", original.secretKey());
        yaml.set("vpn.networks", List.of(entries.getFirst(), other));
        Files.writeString(directory.resolve("config.yml"), yaml.saveToString());

        var updated = editor.join("Private", "password-two".toCharArray());
        assertNotEquals(original.secretKey(), updated.secretKey());
        assertEquals(original.secretKey(), settings().networks().get(1).secret());
        try (NetworkSecrets secrets = NetworkSecrets.load(directory)) {
            assertArrayEquals("password-one".toCharArray(), secrets.password(original.secretKey()));
            assertArrayEquals("password-two".toCharArray(), secrets.password(updated.secretKey()));
        }
        assertTrue(editor.leave("Private"));
        try (NetworkSecrets secrets = NetworkSecrets.load(directory)) {
            assertArrayEquals("password-one".toCharArray(), secrets.password(original.secretKey()));
        }
    }

    @Test
    void aNewReferenceDoesNotOverwriteAnUnreferencedOrEscapedPropertyKey() throws Exception {
        defaults();
        Files.writeString(directory.resolve("secrets.properties"),
                "rede\\u002dprivate=orphan-password\nrede-private-2=other-password\n");
        var result = new ConfigEditor(directory).join("Private", "new-password".toCharArray());
        assertEquals("rede-private-3", result.secretKey());
        try (NetworkSecrets secrets = NetworkSecrets.load(directory)) {
            assertArrayEquals("orphan-password".toCharArray(), secrets.password("rede-private"));
            assertArrayEquals("other-password".toCharArray(), secrets.password("rede-private-2"));
            assertArrayEquals("new-password".toCharArray(), secrets.password(result.secretKey()));
        }
    }

    @Test
    void continuedValuesAreNotTreatedAsSecretDefinitionsOrExtendedByNewReferences() throws Exception {
        defaults();
        Files.writeString(directory.resolve("secrets.properties"),
                "other=prefix\\\nrede-private=continued-value\ntail=tail-password\\");
        var editor = new ConfigEditor(directory);
        var first = editor.join("Private", "password-one".toCharArray());
        var updated = editor.join("Private", "password-two".toCharArray());
        try (NetworkSecrets secrets = NetworkSecrets.load(directory)) {
            assertArrayEquals("prefixrede-private=continued-value".toCharArray(), secrets.password("other"));
            assertArrayEquals("tail-password".toCharArray(), secrets.password("tail"));
            assertArrayEquals("password-two".toCharArray(), secrets.password(updated.secretKey()));
            assertThrows(IllegalArgumentException.class, () -> secrets.password(first.secretKey()));
        }
        assertTrue(editor.leave("Private"));
        try (NetworkSecrets secrets = NetworkSecrets.load(directory)) {
            assertArrayEquals("prefixrede-private=continued-value".toCharArray(), secrets.password("other"));
            assertArrayEquals("tail-password".toCharArray(), secrets.password("tail"));
        }
    }

    @Test
    void insufficientRoomForAFreshSecretPreservesBothFiles() throws Exception {
        defaults();
        String prefix = "other=old-password\n#";
        String originalSecrets = prefix + "x".repeat(65536 - prefix.length());
        Files.writeString(directory.resolve("secrets.properties"), originalSecrets);
        String originalConfig = Files.readString(directory.resolve("config.yml"));
        assertThrows(java.io.IOException.class,
                () -> new ConfigEditor(directory).join("Private", "new-password".toCharArray()));
        assertEquals(originalSecrets, Files.readString(directory.resolve("secrets.properties")));
        assertEquals(originalConfig, Files.readString(directory.resolve("config.yml")));
        try (NetworkSecrets secrets = NetworkSecrets.load(directory)) {
            assertArrayEquals("old-password".toCharArray(), secrets.password("other"));
        }
    }

    private static void assertOwnerOnly(Path file) throws Exception {
        var posix = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                    posix.readAttributes().permissions());
            return;
        }
        var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
        assertNotNull(acl);
        var entries = acl.getAcl();
        assertEquals(1, entries.size(), "only the owner may access the secrets file");
        assertEquals(acl.getOwner(), entries.getFirst().principal());
    }

    @Test
    void transactionRestoresBothFilesWhenVerificationFails() throws Exception {
        defaults();
        var editor = new ConfigEditor(directory);
        editor.join("Private", "password-one".toCharArray());
        Path config = directory.resolve("config.yml");
        Path secrets = directory.resolve("secrets.properties");
        byte[] configBefore = Files.readAllBytes(config);
        byte[] secretsBefore = Files.readAllBytes(secrets);
        var failure = assertThrows(IllegalStateException.class, () -> editor.transaction(
                ed -> ed.join("Private", "password-two".toCharArray()),
                result -> {
                    throw new IllegalStateException("apply failed");
                }));
        assertEquals("apply failed", failure.getMessage());
        assertArrayEquals(configBefore, Files.readAllBytes(config));
        assertArrayEquals(secretsBefore, Files.readAllBytes(secrets));
        assertOwnerOnly(secrets);
        try (NetworkSecrets loaded = NetworkSecrets.load(directory)) {
            assertArrayEquals("password-one".toCharArray(), loaded.password(settings().networks().getFirst().secret()));
        }
    }

    @Test
    void transactionRemovesASecretsFileThatDidNotExistBefore() throws Exception {
        defaults();
        byte[] configBefore = Files.readAllBytes(directory.resolve("config.yml"));
        assertThrows(IllegalStateException.class, () -> new ConfigEditor(directory).transaction(
                ed -> ed.join("Private", "password-one".toCharArray()),
                result -> {
                    throw new IllegalStateException("apply failed");
                }));
        assertArrayEquals(configBefore, Files.readAllBytes(directory.resolve("config.yml")));
        assertFalse(Files.exists(directory.resolve("secrets.properties")));
    }

    @Test
    void transactionRestoresWhenTheEditItselfFailsHalfWay() throws Exception {
        defaults();
        String prefix = "other=old-password\n#";
        String originalSecrets = prefix + "x".repeat(65536 - prefix.length());
        Files.writeString(directory.resolve("secrets.properties"), originalSecrets);
        byte[] configBefore = Files.readAllBytes(directory.resolve("config.yml"));
        assertThrows(java.io.IOException.class, () -> new ConfigEditor(directory).transaction(
                ed -> ed.join("Private", "new-password".toCharArray()), result -> fail("not reached")));
        assertArrayEquals(configBefore, Files.readAllBytes(directory.resolve("config.yml")));
        assertEquals(originalSecrets, Files.readString(directory.resolve("secrets.properties")));
    }

    @Test
    void transactionKeepsTheEditWhenVerificationSucceeds() throws Exception {
        defaults();
        var editor = new ConfigEditor(directory);
        String result = editor.transaction(
                ed -> ed.join("Private", "password-one".toCharArray()).secretKey(),
                key -> assertEquals("rede-private", key));
        assertEquals("rede-private", result);
        assertEquals("rede-private", settings().networks().getFirst().secret());
        assertTrue(Files.readString(directory.resolve("secrets.properties")).contains("rede-private=password-one"));
    }

    @Test
    void transactionReportsWhenTheFilesCannotBeRestored() throws Exception {
        defaults();
        Path config = directory.resolve("config.yml");
        var failure = assertThrows(ConfigEditor.RollbackException.class, () -> new ConfigEditor(directory).transaction(
                ed -> {
                    Files.delete(config);
                    Files.createDirectory(config);
                    Files.writeString(config.resolve("keep"), "x");
                    return null;
                },
                result -> {
                    throw new IllegalStateException("apply failed");
                }));
        assertEquals("apply failed", failure.original().getMessage());
        assertTrue(failure.getMessage().startsWith("falha ao restaurar"), failure.getMessage());
    }

    @Test
    void validatorRejectsBeforeAnyFileIsWritten() throws Exception {
        defaults();
        byte[] configBefore = Files.readAllBytes(directory.resolve("config.yml"));
        var editor = new ConfigEditor(directory, (settings, yaml) -> {
            throw new IllegalArgumentException("policy");
        });
        assertThrows(IllegalArgumentException.class, () -> editor.join("Private", "password-one".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> editor.join("Public", null));
        assertThrows(IllegalArgumentException.class, () -> editor.publicSelection(List.of("all")));
        assertThrows(IllegalArgumentException.class, () -> editor.enabled(true));
        assertThrows(IllegalArgumentException.class, () -> editor.forceRelay(true));
        assertArrayEquals(configBefore, Files.readAllBytes(directory.resolve("config.yml")));
        assertFalse(Files.exists(directory.resolve("secrets.properties")));
    }

    @Test
    void malformedNetworkEntriesAreRejectedInsteadOfBeingDropped() throws Exception {
        Files.writeString(
                directory.resolve("config.yml"),
                "bridge:\n  enabled: false\nvpn:\n  networks:\n    - \"My Net\"\n");
        String before = Files.readString(directory.resolve("config.yml"));
        var editor = new ConfigEditor(directory);
        assertThrows(IllegalArgumentException.class, () -> editor.join("Other", null));
        assertThrows(IllegalArgumentException.class, () -> editor.join("Other", "password-one".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> editor.leave("Other"));
        assertEquals(before, Files.readString(directory.resolve("config.yml")));
        assertFalse(Files.exists(directory.resolve("secrets.properties")));
    }

    @Test
    void leaveReportsSuccessWhenSecretCleanupFailsAfterTheConfigurationWasSaved() throws Exception {
        defaults();
        var editor = new ConfigEditor(directory);
        var joined = editor.join("Private", "password-one".toCharArray());
        editor.join("Public", null);
        Path secrets = directory.resolve("secrets.properties");
        Files.writeString(secrets, joined.secretKey() + "=password-one\n#" + "x".repeat(70000));
        assertTrue(editor.leave("Private"));
        assertEquals(List.of("Public"), settings().networks().stream().map(VpnSettings.Network::name).toList());
        assertTrue(Files.size(secrets) > 65536, "oversized secrets file left for manual cleanup");
    }
}
