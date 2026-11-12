package io.github.gringoestrangeiro.openradplugin;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.config.VpnSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class OfflinePublicNetworkPolicyTest {
    private VpnSettings settings(
            String secret, String selection, boolean enabled, boolean onlineMode, boolean acknowledged)
            throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                bridge:
                  enabled: %s
                vpn:
                  networks:
                    - name: Synthetic configured network
                      secret: '%s'
                  minecraft-networks:
                    enabled: true
                    select: %s
                security:
                  allow-insecure-offline-public-networks: %s
                """.formatted(enabled, secret, selection, acknowledged));
        return VpnSettings.from(yaml, onlineMode);
    }

    @Test
    void explicitPasswordlessNetworkRequiresAcknowledgementInOfflineMode() {
        assertThrows(IllegalArgumentException.class, () -> settings("", "[]", true, false, false));
    }

    @Test
    void privateOnlyNetworkDoesNotRequirePublicAcknowledgement() {
        assertDoesNotThrow(() -> settings("private-key", "[]", true, false, false));
    }

    @Test
    void automaticPublicSelectionRequiresAcknowledgement() {
        assertThrows(IllegalArgumentException.class, () -> settings("private-key", "[all]", true, false, false));
    }

    @Test
    void acknowledgedOnlineOrDisabledBridgeRemainAllowed() {
        assertDoesNotThrow(() -> settings("", "[]", true, false, true));
        assertDoesNotThrow(() -> settings("", "[]", true, true, false));
        assertDoesNotThrow(() -> settings("", "[]", false, false, false));
    }
}
