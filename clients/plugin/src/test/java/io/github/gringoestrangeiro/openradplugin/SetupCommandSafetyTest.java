package io.github.gringoestrangeiro.openradplugin;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.config.ConfigEditor;
import io.github.gringoestrangeiro.openradplugin.config.VpnSettings;

import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.List;

final class SetupCommandSafetyTest {
    @TempDir Path directory;

    private void defaults() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            Files.copy(in, directory.resolve("config.yml"));
        }
    }

    private ConfigEditor offlineEditor() {
        return new ConfigEditor(directory, OpenRadPlugin.offlinePublicPolicy(() -> false));
    }

    private void assertUntouched(byte[] config) throws Exception {
        assertArrayEquals(config, Files.readAllBytes(directory.resolve("config.yml")));
        assertFalse(Files.exists(directory.resolve("secrets.properties")));
    }

    @Test
    void offlinePublicSetupCommandsAreRejectedWithoutWritingAnything() throws Exception {
        defaults();
        byte[] config = Files.readAllBytes(directory.resolve("config.yml"));
        var editor = offlineEditor();
        var joined = assertThrows(IllegalArgumentException.class, () -> editor.join("Public Net", null));
        assertTrue(joined.getMessage().contains("Offline-mode with public Minecraft VPN networks is unsafe"));
        assertThrows(IllegalArgumentException.class, () -> editor.publicSelection(List.of("all-ptbr")));
        assertUntouched(config);
    }

    @Test
    void enableIsRejectedWhenAPublicNetworkIsAlreadyConfigured() throws Exception {
        Files.writeString(
                directory.resolve("config.yml"),
                "bridge:\n  enabled: false\nvpn:\n  networks:\n    - name: Public Net\n      secret: ''\n");
        byte[] config = Files.readAllBytes(directory.resolve("config.yml"));
        assertThrows(IllegalArgumentException.class, () -> offlineEditor().enabled(true));
        assertUntouched(config);
    }

    @Test
    void privateNetworkPasswordIsNeverStoredWhenThePolicyRejectsTheEdit() throws Exception {
        Files.writeString(
                directory.resolve("config.yml"),
                "bridge:\n  enabled: false\nvpn:\n  networks:\n    - name: Public Net\n      secret: ''\n");
        byte[] config = Files.readAllBytes(directory.resolve("config.yml"));
        assertThrows(IllegalArgumentException.class,
                () -> offlineEditor().join("Private", "password-one".toCharArray()));
        assertUntouched(config);
    }

    @Test
    void offlinePrivateOnlyOnlineModeAndAcknowledgedRiskAreAccepted() throws Exception {
        defaults();
        var privateJoin = offlineEditor().join("Private", "password-one".toCharArray());
        assertEquals("rede-private", privateJoin.secretKey());
        offlineEditor().leave("Private");
        new ConfigEditor(directory, OpenRadPlugin.offlinePublicPolicy(() -> true)).join("Public Net", null);

        Files.delete(directory.resolve("config.yml"));
        defaults();
        var yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(directory.resolve("config.yml")));
        yaml.set("security.allow-insecure-offline-public-networks", true);
        Files.writeString(directory.resolve("config.yml"), yaml.saveToString());
        offlineEditor().publicSelection(List.of("all"));
        assertTrue(Files.readString(directory.resolve("config.yml")).contains("all"));
    }

    private static <T extends CommandSender> CommandSender fake(Class<T> type) {
        return (CommandSender) Proxy.newProxyInstance(
                OpenRadPlugin.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> null);
    }

    @Test
    void onlyConsoleAndRconMayCarryAPassword() {
        assertTrue(OpenRadPlugin.acceptsNetworkPassword(fake(ConsoleCommandSender.class)));
        assertTrue(OpenRadPlugin.acceptsNetworkPassword(fake(RemoteConsoleCommandSender.class)));
        assertFalse(OpenRadPlugin.acceptsNetworkPassword(fake(Player.class)));
        assertFalse(OpenRadPlugin.acceptsNetworkPassword(fake(BlockCommandSender.class)));
        assertFalse(OpenRadPlugin.acceptsNetworkPassword(fake(CommandMinecart.class)));
        assertFalse(OpenRadPlugin.acceptsNetworkPassword(fake(ProxiedCommandSender.class)));
        assertFalse(OpenRadPlugin.acceptsNetworkPassword(fake(CommandSender.class)));
    }

    @Test
    void schedulerRefusalDuringShutdownIsNotAnError() {
        assertTrue(OpenRadPlugin.scheduled(() -> {}));
        assertFalse(OpenRadPlugin.scheduled(() -> {
            throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
        }));
        assertFalse(OpenRadPlugin.scheduled(() -> {
            throw new IllegalStateException("stopping");
        }));
        assertThrows(IllegalArgumentException.class, () -> OpenRadPlugin.scheduled(() -> {
            throw new IllegalArgumentException("a real bug");
        }));
    }

    @Test
    void legacyMigrationFailureNamesPathsReasonAndRemedy() {
        Path legacy = Path.of("plugins", "OpenRadBridge");
        Path destination = Path.of("plugins", "OpenRadPlugin");
        String message = OpenRadPlugin.legacyMigrationFailure(
                legacy,
                destination,
                new IOException("legacy identity exists; refusing to start with a new profile"));
        assertTrue(message.contains(legacy.toString()), message);
        assertTrue(message.contains(destination.toString()), message);
        assertTrue(message.contains("legacy identity exists"), message);
        assertTrue(message.contains("identity.json"), message);
        assertTrue(message.contains("VPN nao iniciada"), message);
        assertTrue(OpenRadPlugin.legacyMigrationFailure(legacy, destination, new AccessDeniedException("plugins"))
                .contains("AccessDeniedException"));
    }

    @Test
    void reconnectDoesNotPretendToStartADisabledVpn() throws Exception {
        VpnSettings disabled = settings(false);
        String message = OpenRadPlugin.reconnectDisabledMessage(disabled);
        assertNotNull(message);
        assertTrue(message.contains("desativada"));
        assertFalse(message.contains("iniciada."));
        assertNull(OpenRadPlugin.reconnectDisabledMessage(settings(true)));
    }

    private static VpnSettings settings(boolean enabled) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("bridge:\n  enabled: " + enabled + "\nvpn:\n  networks:\n    - name: Net\n      secret: ''\n");
        return VpnSettings.from(yaml);
    }
}
