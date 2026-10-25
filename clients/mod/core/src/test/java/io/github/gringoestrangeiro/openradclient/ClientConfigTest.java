package io.github.gringoestrangeiro.openradclient;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;

class ClientConfigTest {
    @TempDir Path temp;

    @Test void defaultInstallNeverConnectsOrCreatesPrivateIdentity() throws Exception {
        ClientConfig config = ClientConfig.load(temp);
        assertFalse(config.autoConnect());
        assertEquals(List.of(), config.networks());
        assertFalse(Files.exists(temp.resolve("state/identity.json")));
        assertTrue(Files.exists(temp.resolve("openrad-client.properties")));
    }

    @Test void exactPublicNamesSupportAccentsAndOptIn() {
        Properties p = new Properties();
        p.setProperty("network-names", "Minecraft [Português 1];Minecraft [English 2]");
        p.setProperty("auto-connect", "true");
        ClientConfig cfg = ClientConfig.from(p);
        assertEquals(2, cfg.networks().size());
        assertEquals("Minecraft [Português 1]", cfg.networks().getFirst());
        assertTrue(cfg.autoConnect());
    }

    @Test void invalidInputsAreRejected() {
        Properties p = new Properties();
        p.setProperty("auto-connect", "yes");
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.from(p));
        p.setProperty("auto-connect", "true");
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.from(p));
        p.setProperty("network-names", "A;A");
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.from(p));
    }

    @Test void runtimeStaysOfflineWhenNoNetworksSelected() {
        try (var runtime = new ClientVpnRuntime(new ClientConfig(false, List.of()),
                temp.resolve("identity-data"), line -> {})) {
            assertFalse(runtime.connect());
            assertEquals("OFFLINE", runtime.status().phase());
            assertFalse(Files.exists(temp.resolve("identity-data/identity.json")));
        }
    }

    @Test void streamLimitsAreBoundedAndOldConfigurationKeepsDefaults() {
        ClientConfig defaults = ClientConfig.from(new Properties());
        assertEquals(256, defaults.maxPeers());
        assertEquals(16, defaults.maxConnections());
        assertEquals(20, defaults.connectionTimeoutSeconds());
        assertTrue(defaults.minecraftSelectors().isEmpty());
        assertEquals(256, defaults.maxNetworks());
        Properties properties = new Properties();
        properties.setProperty("max-peers", "4096");
        properties.setProperty("max-connections", "64");
        properties.setProperty("connection-timeout-seconds", "120");
        ClientConfig configured = ClientConfig.from(properties);
        assertEquals(4096, configured.maxPeers());
        assertEquals(64, configured.maxConnections());
        assertEquals(120, configured.connectionTimeoutSeconds());
        for (String key : List.of("max-peers", "max-connections", "connection-timeout-seconds")) {
            for (String invalid : List.of("0", "-1", "9999999", "no", "")) {
                Properties bad = new Properties();
                bad.setProperty(key, invalid);
                assertThrows(IllegalArgumentException.class, () -> ClientConfig.from(bad), key + "=" + invalid);
            }
        }
    }

    @Test void selectorsSupportOptInAndLegacyConstructorsRemainCompatible() {
        ClientConfig old = new ClientConfig(false, List.of("Exact"), 123, 12, 45);
        assertEquals(List.of(), old.minecraftSelectors());
        assertEquals(256, old.maxNetworks());
        Properties properties = new Properties();
        properties.setProperty("auto-connect", "true");
        properties.setProperty("minecraft-selectors", "ALL-PTBR;ptbr-3;all-english;english-2");
        ClientConfig selected = ClientConfig.from(properties);
        assertTrue(selected.autoConnect());
        assertEquals(List.of("all-ptbr", "ptbr-3", "all-english", "english-2"), selected.minecraftSelectors());
        for (String invalid : List.of("all;ALL", "all;;ptbr-1", "../all", "all ptbr")) {
            properties.setProperty("minecraft-selectors", invalid);
            assertThrows(IllegalArgumentException.class, () -> ClientConfig.from(properties), invalid);
        }
    }

    @Test void atomicUtf8SaveRoundTripsAndPreservesIdentity() throws Exception {
        Path identity = temp.resolve("state/identity.json");
        Files.createDirectories(identity.getParent());
        Files.writeString(identity, "synthetic identity sentinel");
        ClientConfig original = new ClientConfig(true, List.of("Minecraft [Português 1]", "Exact = Unicode 雪"),
                321, 32, 60, List.of("all-ptbr", "all-japanese"), 1024);
        original.save(temp);
        assertEquals(original, ClientConfig.load(temp));
        assertTrue(Files.readString(temp.resolve("openrad-client.properties")).contains("Português"));
        assertEquals("synthetic identity sentinel", Files.readString(identity));
        ClientConfig replacement = new ClientConfig(false, List.of(), 256, 16, 20, List.of("all"), 256);
        replacement.save(temp);
        assertEquals(replacement, ClientConfig.load(temp));
        try (var files = Files.list(temp)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
        assertEquals("synthetic identity sentinel", Files.readString(identity));
    }

    @Test void supportsMoreThanSixteenNetworksButKeepsExplicitAndSelectorBounds() {
        List<String> networks = java.util.stream.IntStream.range(0, 1024).mapToObj(i -> "Network " + i).toList();
        assertEquals(1024, new ClientConfig(false, networks, 256, 16, 20, List.of(), 1024).networks().size());
        assertThrows(IllegalArgumentException.class,
                () -> new ClientConfig(false, networks, 256, 16, 20, List.of(), 256));
        for (int invalid : List.of(0, -1, 1025))
            assertThrows(IllegalArgumentException.class,
                    () -> new ClientConfig(false, List.of(), 256, 16, 20, List.of("all"), invalid));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientConfig(false, List.of(), 256, 16, 20,
                        java.util.stream.IntStream.range(1, 1026).mapToObj(i -> "ptbr-" + i).toList(), 1024));
    }
}
