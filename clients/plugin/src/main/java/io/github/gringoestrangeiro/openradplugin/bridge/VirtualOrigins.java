package io.github.gringoestrangeiro.openradplugin.bridge;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.Channel;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps the loopback source endpoint of each bridge-to-backend connection to the VPN player's
 * virtual address. The bridge binds before connecting, so its complete local address and port
 * identify one live virtual flow. Other loopback addresses can use the same port. Entries
 * are bounded by the bridge's own connection limit and removed when the flow closes.
 *
 * <p>A failed non-blocking connect closes the bridge socket inside the JDK, releasing its port
 * before the bridge can unregister it. Each entry therefore keeps its owning channel, and a
 * lookup ignores entries whose channel is no longer open: {@code isOpen()} turns false before
 * the descriptor (and so the port) is released, so a local connection reusing the endpoint
 * can never inherit the VPN player's address.
 */
public final class VirtualOrigins {
    private static final VirtualOrigins SHARED = new VirtualOrigins();

    private record Entry(InetSocketAddress origin, Channel owner) {}

    private final ConcurrentHashMap<InetSocketAddress, Entry> byEndpoint =
            new ConcurrentHashMap<>();

    /** One registry per plugin class loader, shared by every runtime generation. */
    public static VirtualOrigins shared() {
        return SHARED;
    }

    void register(InetSocketAddress localEndpoint, InetSocketAddress origin, Channel owner) {
        byEndpoint.put(localEndpoint, new Entry(origin, owner));
    }

    /** Removes the endpoint only while it still belongs to {@code owner}. */
    void unregister(InetSocketAddress localEndpoint, Channel owner) {
        byEndpoint.computeIfPresent(
                localEndpoint, (endpoint, entry) -> entry.owner() == owner ? null : entry);
    }

    /** Virtual origin of a backend client address, or null for any other connection. */
    public InetSocketAddress lookup(SocketAddress backendClient) {
        if (!(backendClient instanceof InetSocketAddress)) return null;
        InetSocketAddress client = (InetSocketAddress) backendClient;
        if (client.getAddress() == null || !client.getAddress().isLoopbackAddress()) return null;
        Entry entry = byEndpoint.get(client);
        return entry == null || !entry.owner().isOpen() ? null : entry.origin();
    }

    public int size() {
        return byEndpoint.size();
    }
}
