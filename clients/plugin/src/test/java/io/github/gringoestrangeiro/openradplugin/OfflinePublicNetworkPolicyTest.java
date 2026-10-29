package io.github.gringoestrangeiro.openradplugin;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.config.VpnSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class OfflinePublicNetworkPolicyTest {
    private VpnSettings settings(String secret, String selection, boolean enabled) throws Exception {
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
                """.formatted(enabled, secret, selection));
        return VpnSettings.from(yaml);
    }

    @Test
    void explicitPasswordlessNetworkRequiresAcknowledgementInOfflineMode() throws Exception {
        var settings = settings("", "[]", true);
        assertThrows(IllegalArgumentException.class,
                () -> OpenRadPlugin.validateOfflinePublicNetworks(settings, false, false));
    }

    @Test
    void privateOnlyNetworkDoesNotRequirePublicAcknowledgement() throws Exception {
        var settings = settings("private-key", "[]", true);
        assertDoesNotThrow(() -> OpenRadPlugin.validateOfflinePublicNetworks(settings, false, false));
    }

    @Test
    void automaticPublicSelectionRequiresAcknowledgement() throws Exception {
        var settings = settings("private-key", "[all]", true);
        assertThrows(IllegalArgumentException.class,
                () -> OpenRadPlugin.validateOfflinePublicNetworks(settings, false, false));
    }

    @Test
    void acknowledgedOnlineOrDisabledBridgeRemainAllowed() throws Exception {
        var publicNetwork = settings("", "[]", true);
        assertDoesNotThrow(() -> OpenRadPlugin.validateOfflinePublicNetworks(publicNetwork, false, true));
        assertDoesNotThrow(() -> OpenRadPlugin.validateOfflinePublicNetworks(publicNetwork, true, false));
        var disabled = settings("", "[]", false);
        assertDoesNotThrow(() -> OpenRadPlugin.validateOfflinePublicNetworks(disabled, false, false));
    }
}
