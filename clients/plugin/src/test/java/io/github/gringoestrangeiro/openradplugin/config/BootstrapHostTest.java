package io.github.gringoestrangeiro.openradplugin.config;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.protocol.Wire;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

final class BootstrapHostTest {
    private static YamlConfiguration yaml(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new AssertionError("invalid test YAML", e);
        }
        return config;
    }

    @Test
    void nonNumericIpv4BootstrapIsRejectedAtLoad() {
        for (String host :
                List.of("::ffff:148.113.190.78", "2001:db8::1", "peer.example.net")) {
            IllegalArgumentException failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    VpnSettings.from(
                                            yaml(
                                                    "vpn:\n"
                                                            + "  bootstrap-host: \""
                                                            + host
                                                            + "\"\n")));
            assertTrue(
                    failure.getMessage().contains("IPv4"),
                    "message should name IPv4: " + failure.getMessage());
        }
    }

    @Test
    void dottedQuadBootstrapParses() {
        VpnSettings settings =
                VpnSettings.from(yaml("vpn:\n  bootstrap-host: \"148.113.190.78\"\n"));
        assertEquals("148.113.190.78", settings.bootstrap());
    }

    @Test
    void wireIpv4MatchesStrictDottedQuad() throws Exception {
        assertEquals(InetAddress.getByName("1.2.3.4"), Wire.ipv4("1.2.3.4"));
        for (String invalid :
                List.of(
                        "::ffff:1.2.3.4",
                        "01.2.3.4",
                        "1.2.3",
                        "1.2.3.256",
                        " 1.2.3.4",
                        "1.2.3.4 ")) {
            assertThrows(IllegalArgumentException.class, () -> Wire.ipv4(invalid), invalid);
        }
    }
}
