package io.github.gringoestrangeiro.openradplugin.config;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Set;

final class ConfigParsingTest {
    private static YamlConfiguration yaml(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new AssertionError("invalid test YAML", e);
        }
        return config;
    }

    private static IllegalArgumentException rejected(String text, String key) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> VpnSettings.from(yaml(text)));
        assertTrue(
                failure.getMessage().contains(key),
                "message should name " + key + ": " + failure.getMessage());
        return failure;
    }

    @Test
    void scalarRidAclsAreRejectedInsteadOfBecomingEmpty() {
        rejected("vpn:\n  acl:\n    allow-rids: 12345\n", "vpn.acl.allow-rids");
        rejected("vpn:\n  acl:\n    deny-rids: 67890\n", "vpn.acl.deny-rids");
    }

    @Test
    void mapRidAclsAreRejected() {
        rejected("vpn:\n  acl:\n    allow-rids:\n      12345: true\n", "vpn.acl.allow-rids");
        rejected("vpn:\n  acl:\n    deny-rids:\n      67890: true\n", "vpn.acl.deny-rids");
    }

    @Test
    void nullRidAclElementsAreRejected() {
        rejected("vpn:\n  acl:\n    allow-rids:\n      - null\n", "vpn.acl.allow-rids");
    }

    @Test
    void validRidAclListParses() {
        VpnSettings settings =
                VpnSettings.from(
                        yaml(
                                "vpn:\n"
                                        + "  acl:\n"
                                        + "    allow-rids: [\"12345\", 67890]\n"
                                        + "    deny-rids: []\n"));
        assertEquals(Set.of(12345L, 67890L), settings.allowedRids());
        assertEquals(Set.of(), settings.deniedRids());
    }

    @Test
    void missingRidAclsAreEmpty() {
        VpnSettings settings = VpnSettings.from(yaml(""));
        assertEquals(Set.of(), settings.allowedRids());
        assertEquals(Set.of(), settings.deniedRids());
    }

    @Test
    void quotedNumbersAndBooleansAreRejected() {
        rejected("vpn:\n  max-peers: \"7\"\n", "vpn.max-peers");
        rejected("bridge:\n  lan-discovery:\n    enabled: \"false\"\n", "bridge.lan-discovery.enabled");
        rejected("bridge:\n  backend-port: \"25566\"\n", "bridge.backend-port");
        rejected("bridge:\n  enabled: \"true\"\n", "bridge.enabled");
        rejected(
                "vpn:\n  minecraft-networks:\n    max-networks: \"512\"\n",
                "vpn.minecraft-networks.max-networks");
        rejected("vpn:\n  transports:\n    relay: \"false\"\n", "vpn.transports.relay");
    }

    @Test
    void unquotedValuesParseToExpected() {
        VpnSettings settings =
                VpnSettings.from(
                        yaml(
                                "bridge:\n"
                                        + "  enabled: true\n"
                                        + "  backend-port: 25566\n"
                                        + "  lan-discovery:\n"
                                        + "    enabled: false\n"
                                        + "vpn:\n"
                                        + "  max-peers: 7\n"
                                        + "  transports:\n"
                                        + "    relay: false\n"
                                        + "  minecraft-networks:\n"
                                        + "    max-networks: 512\n"));
        assertEquals(7, settings.maxPeers());
        assertFalse(settings.lanDiscovery());
        assertEquals(25566, settings.bridge().backendPort());
        assertTrue(settings.bridge().enabled());
        assertEquals(512, settings.minecraftNetworks().maxNetworks());
        assertFalse(settings.relay());
    }

    @Test
    void absentKeysUseDefaults() {
        VpnSettings settings = VpnSettings.from(yaml(""));
        assertEquals(0, settings.maxPeers());
        assertTrue(settings.lanDiscovery());
        assertTrue(settings.bridge().backendPortAutomatic());
        assertEquals(25566, settings.bridge().withBackendPort(25566).backendPort());
        assertFalse(settings.bridge().enabled());
        assertEquals(256, settings.minecraftNetworks().maxNetworks());
    }
}
