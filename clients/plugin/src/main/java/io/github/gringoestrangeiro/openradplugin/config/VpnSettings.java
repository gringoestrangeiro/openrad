package io.github.gringoestrangeiro.openradplugin.config;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradplugin.session.ServiceConfig;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

public record VpnSettings(
        BridgeSettings bridge,
        String nodeName,
        String bootstrap,
        int controlPort,
        List<Network> networks,
        Set<Long> allowedRids,
        Set<Long> deniedRids,
        boolean directTcp,
        boolean directUdp,
        boolean relay,
        int maxPeers,
        int retryBaseSeconds,
        int retryMaxSeconds,
        boolean lanDiscovery,
        MinecraftNetworkSettings minecraftNetworks,
        int discoveryLogIntervalMinutes) {
    /** Keep existing Java/test constructors compatible with the new silent default. */
    public VpnSettings(
            BridgeSettings bridge,
            String nodeName,
            String bootstrap,
            int controlPort,
            List<Network> networks,
            Set<Long> allowedRids,
            Set<Long> deniedRids,
            boolean directTcp,
            boolean directUdp,
            boolean relay,
            int maxPeers,
            int retryBaseSeconds,
            int retryMaxSeconds,
            boolean lanDiscovery,
            MinecraftNetworkSettings minecraftNetworks) {
        this(bridge, nodeName, bootstrap, controlPort, networks, allowedRids, deniedRids,
                directTcp, directUdp, relay, maxPeers, retryBaseSeconds, retryMaxSeconds,
                lanDiscovery, minecraftNetworks, 0);
    }

    public VpnSettings(
            BridgeSettings bridge,
            String nodeName,
            String bootstrap,
            int controlPort,
            List<Network> networks,
            Set<Long> allowedRids,
            Set<Long> deniedRids,
            boolean directTcp,
            boolean directUdp,
            boolean relay,
            int maxPeers,
            int retryBaseSeconds,
            int retryMaxSeconds,
            boolean lanDiscovery) {
        this(
                bridge,
                nodeName,
                bootstrap,
                controlPort,
                networks,
                allowedRids,
                deniedRids,
                directTcp,
                directUdp,
                relay,
                maxPeers,
                retryBaseSeconds,
                retryMaxSeconds,
                lanDiscovery,
                MinecraftNetworkSettings.DISABLED);
    }

    public VpnSettings(
            BridgeSettings bridge,
            String nodeName,
            String bootstrap,
            int controlPort,
            List<Network> networks,
            Set<Long> allowedRids,
            Set<Long> deniedRids,
            boolean directTcp,
            boolean directUdp,
            boolean relay,
            int maxPeers,
            int retryBaseSeconds,
            int retryMaxSeconds) {
        this(
                bridge,
                nodeName,
                bootstrap,
                controlPort,
                networks,
                allowedRids,
                deniedRids,
                directTcp,
                directUdp,
                relay,
                maxPeers,
                retryBaseSeconds,
                retryMaxSeconds,
                true);
    }

    public record Network(String name, String secret, Set<Long> allowedRids, Set<Long> deniedRids) {
        public Network {
            allowedRids = Set.copyOf(allowedRids);
            deniedRids = Set.copyOf(deniedRids);
        }
    }

    public VpnSettings {
        require(maxPeers >= 0 && maxPeers <= 4096, "invalid vpn.max-peers");
        require(discoveryLogIntervalMinutes >= 0 && discoveryLogIntervalMinutes <= 1440,
                "invalid vpn.discovery-log-interval-minutes");
        networks = List.copyOf(networks);
        allowedRids = Set.copyOf(allowedRids);
        deniedRids = Set.copyOf(deniedRids);
    }

    public static VpnSettings from(FileConfiguration c) {
        BridgeSettings bridge = BridgeSettings.from(c);
        String name = c.getString("vpn.device-name", "OpenRad Plugin");
        require(!name.isBlank(), "device-name required");
        TlvCodec.textValue(0x03000304, name);
        String bootstrap = c.getString("vpn.bootstrap-host", ServiceConfig.BOOTSTRAP);
        try {
            ipv4(bootstrap);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "vpn.bootstrap-host must be numeric IPv4; IPv6 is not supported", e);
        }
        List<Network> networks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Object rawNetworks = c.get("vpn.networks");
        require(
                rawNetworks == null || rawNetworks instanceof List<?>,
                "vpn.networks must be a YAML list of entries with name/secret");
        if (rawNetworks != null)
            for (Object entry : (List<?>) rawNetworks)
                require(
                        entry instanceof Map<?, ?>,
                        "each vpn.networks entry needs 'name:' (and 'secret:' for a private"
                                + " network); use /orb join <rede> <senha>");
        for (Map<?, ?> m : c.getMapList("vpn.networks")) {
            require(
                    !m.containsKey("password"),
                    "do not put passwords in config.yml: store them in secrets.properties and"
                            + " reference the key with 'secret:' (or use /orb join <rede> <senha>"
                            + " in the console)");
            String n = Objects.toString(m.get("name"), "");
            String secret = Objects.toString(m.get("secret"), "");
            require(!n.isBlank() && n.length() <= 255, "invalid network name in vpn.networks");
            require(seen.add(n), "duplicate network in vpn.networks: " + n);
            text(0x03000306, n);
            require(
                    secret.isEmpty() || secret.matches("[a-zA-Z0-9_.-]{1,64}"),
                    "invalid secret reference for network " + n
                            + " (use letters, digits, '_', '.', '-')");
            networks.add(
                    new Network(
                            n,
                            secret,
                            rids(m.get("allow-rids"), "vpn.networks " + n + " allow-rids"),
                            rids(m.get("deny-rids"), "vpn.networks " + n + " deny-rids")));
        }
        require(networks.size() <= 128, "network limit");
        MinecraftNetworkSettings minecraft = MinecraftNetworkSettings.from(c);
        require(
                !bridge.enabled() || !networks.isEmpty() || minecraft.enabled(),
                "enabled bridge requires vpn.networks or Minecraft discovery");
        boolean tcp = ConfigScalars.flag(c, "vpn.transports.direct-tcp", true),
                udp = ConfigScalars.flag(c, "vpn.transports.direct-udp", true),
                relay = ConfigScalars.flag(c, "vpn.transports.relay", true);
        require(tcp || udp || relay, "at least one transport required");
        return new VpnSettings(
                bridge,
                name,
                bootstrap,
                ConfigScalars.integer(c, "vpn.control-port", 17301, 1, 65535),
                networks,
                rids(c.get("vpn.acl.allow-rids"), "vpn.acl.allow-rids"),
                rids(c.get("vpn.acl.deny-rids"), "vpn.acl.deny-rids"),
                tcp,
                udp,
                relay,
                ConfigScalars.integer(c, "vpn.max-peers", 0, 0, 4096),
                ConfigScalars.integer(c, "vpn.reconnect.base-seconds", 3, 1, 60),
                ConfigScalars.integer(c, "vpn.reconnect.max-seconds", 60, 1, 600),
                ConfigScalars.flag(c, "bridge.lan-discovery.enabled", true),
                minecraft,
                ConfigScalars.integer(c, "vpn.discovery-log-interval-minutes", 0, 0, 1440));
    }

    private static Set<Long> rids(Object input, String key) {
        if (input == null) return Set.of();
        require(input instanceof List<?>, key + " must be a YAML list");
        List<?> list = (List<?>) input;
        require(list.size() <= 4096, key + " limit");
        Set<Long> result = new HashSet<>();
        for (Object v : list) {
            require(v != null, key + " must not contain null RIDs");
            try {
                long rid = Long.parseUnsignedLong(v.toString());
                require(rid != 0, "zero RID in " + key);
                result.add(rid);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid ACL RID in " + key);
            }
        }
        return Set.copyOf(result);
    }

    public VpnSettings withBridge(BridgeSettings replacement) {
        return new VpnSettings(
                replacement,
                nodeName,
                bootstrap,
                controlPort,
                networks,
                allowedRids,
                deniedRids,
                directTcp,
                directUdp,
                relay,
                maxPeers,
                retryBaseSeconds,
                retryMaxSeconds,
                lanDiscovery,
                minecraftNetworks,
                discoveryLogIntervalMinutes);
    }

    public Set<String> networkNames() {
        Set<String> names = new HashSet<>();
        for (Network n : networks) names.add(n.name());
        return Set.copyOf(names);
    }

    /** Zero covers every eligible member within the protocol's bounded 4096-member roster. */
    public int peerLimit() {
        return maxPeers == 0 ? 4096 : maxPeers;
    }

    public int handshakeLimit() {
        return Math.min(16, peerLimit());
    }

    public boolean networkAllows(String name, long rid) {
        return networks.stream()
                .anyMatch(
                        n ->
                                n.name().equals(name)
                                        && !n.deniedRids().contains(rid)
                                        && (n.allowedRids().isEmpty()
                                                || n.allowedRids().contains(rid)));
    }
}
