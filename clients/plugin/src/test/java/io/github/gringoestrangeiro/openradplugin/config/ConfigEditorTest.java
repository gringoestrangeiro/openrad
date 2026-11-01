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
        editor.join("Private", "password-one".toCharArray());
        assertFalse(editor.join("Private", "password-two".toCharArray()).added());
        editor.join("Public", null);
        try (NetworkSecrets secrets = NetworkSecrets.load(directory)) {
            assertArrayEquals("password-two".toCharArray(), secrets.password("rede-private"));
        }
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
}
