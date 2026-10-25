package io.github.gringoestrangeiro.openradplugin.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

final class VpnDiscoveryLogTest {
    private static VpnSettings settings(int intervalMinutes) {
        return new VpnSettings(
                null, "Test node", "127.0.0.1", 17301, List.of(), Set.of(), Set.of(),
                true, true, true, 64, 3, 60, true,
                MinecraftNetworkSettings.DISABLED, intervalMinutes);
    }

    @Test
    void discoveryDiagnosticsAreSilentByDefaultAndCanBeRateLimited() {
        assertEquals(0, settings(0).discoveryLogIntervalMinutes());
        assertEquals(5, settings(5).discoveryLogIntervalMinutes());
        assertEquals(1440, settings(1440).discoveryLogIntervalMinutes());
        assertThrows(IllegalArgumentException.class, () -> settings(-1));
        assertThrows(IllegalArgumentException.class, () -> settings(1441));
    }
}
