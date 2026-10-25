package io.github.gringoestrangeiro.openradplugin.config;

import org.bukkit.configuration.file.FileConfiguration;

public record BridgeSettings(
        boolean enabled,
        String backendHost,
        int backendPort,
        int virtualPort,
        int maxPlayers,
        int maxPendingTcp,
        int timeoutSeconds,
        String tcpProbeHost,
        int tcpProbePort,
        String udpProbeHost,
        int udpProbePort,
        int diagnosticTimeoutMillis) {
    public static BridgeSettings from(FileConfiguration config) {
        String host = config.getString("bridge.backend-host", "127.0.0.1");
        if (!"127.0.0.1".equals(host) && !"::1".equals(host)) {
            throw new IllegalArgumentException(
                    "bridge.backend-host must be a numeric loopback address");
        }
        String tcp = config.getString("diagnostics.tcp-egress-host", "").trim();
        String udp = config.getString("diagnostics.udp-egress-host", "").trim();
        int tcpPort = ConfigScalars.integer(config, "diagnostics.tcp-egress-port", 0, 0, 65535);
        int udpPort = ConfigScalars.integer(config, "diagnostics.udp-egress-port", 0, 0, 65535);
        if (tcp.isEmpty() != (tcpPort == 0) || udp.isEmpty() != (udpPort == 0)) {
            throw new IllegalArgumentException("diagnostic host and port must be set together");
        }
        if ((!tcp.isEmpty() && !validProbeHost(tcp)) || (!udp.isEmpty() && !validProbeHost(udp))) {
            throw new IllegalArgumentException(
                    "diagnostic hosts must be IP literals, not DNS names");
        }
        return new BridgeSettings(
                ConfigScalars.flag(config, "bridge.enabled", false),
                host,
                ConfigScalars.integer(config, "bridge.backend-port", 0, 0, 65535),
                ConfigScalars.integer(config, "bridge.listen-virtual-port", 25565, 1, 65535),
                ConfigScalars.integer(config, "bridge.max-players-through-vpn", 30, 1, 1024),
                ConfigScalars.integer(config, "bridge.max-pending-tcp", 60, 1, 2048),
                ConfigScalars.integer(config, "bridge.connection-timeout-seconds", 15, 1, 120),
                tcp,
                tcpPort,
                udp,
                udpPort,
                ConfigScalars.integer(config, "diagnostics.timeout-millis", 2000, 100, 5000));
    }

    /** {@code backend-port: 0} means "the port this Paper server listens on". */
    public boolean backendPortAutomatic() {
        return backendPort == 0;
    }

    public BridgeSettings withBackendPort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid backend port");
        return new BridgeSettings(
                enabled,
                backendHost,
                port,
                virtualPort,
                maxPlayers,
                maxPendingTcp,
                timeoutSeconds,
                tcpProbeHost,
                tcpProbePort,
                udpProbeHost,
                udpProbePort,
                diagnosticTimeoutMillis);
    }

    private static boolean validProbeHost(String host) {
        if (host.length() > 45) return false;
        if (host.contains(":")) return host.matches("[0-9a-fA-F:.%]+") && !host.contains("%");
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.matches("[0-9]+")) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}
