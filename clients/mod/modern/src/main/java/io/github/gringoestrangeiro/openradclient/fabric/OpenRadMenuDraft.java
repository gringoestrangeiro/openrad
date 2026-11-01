package io.github.gringoestrangeiro.openradclient.fabric;

import io.github.gringoestrangeiro.openradclient.ClientConfig;
import java.util.List;

/** Unsaved menu state; returning from a child screen never writes a configuration. */
public final class OpenRadMenuDraft {
    boolean autoConnect;
    List<String> networks;
    List<String> selectors;
    int maxPeers, maxConnections, timeout, maxNetworks;

    public OpenRadMenuDraft(ClientConfig config) {
        autoConnect = config.autoConnect();
        networks = config.networks();
        selectors = config.minecraftSelectors();
        maxPeers = config.maxPeers();
        maxConnections = config.maxConnections();
        timeout = config.connectionTimeoutSeconds();
        maxNetworks = config.maxNetworks();
    }
    public ClientConfig config() {
        return new ClientConfig(autoConnect, networks, maxPeers, maxConnections, timeout, selectors, maxNetworks);
    }
    public void select(List<String> selection) {
        List<String> next = List.copyOf(selection);
        // A new language/filter replaces the legacy exact-name selection instead of silently unioning it.
        if (!next.equals(selectors)) networks = List.of();
        selectors = next;
    }
}
