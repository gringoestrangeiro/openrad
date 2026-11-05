package io.github.gringoestrangeiro.openradplugin.bridge;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps the loopback source endpoint of each bridge-to-backend connection to the VPN player's
 * virtual address. The bridge binds before connecting, so its complete local address and port
 * identify one live virtual flow. Other loopback addresses can use the same port. Entries
 * are bounded by the bridge's own connection limit and removed when the flow closes.
 */
public final class VirtualOrigins {
    private static final VirtualOrigins SHARED = new VirtualOrigins();

    private final ConcurrentHashMap<InetSocketAddress, InetSocketAddress> byEndpoint =
            new ConcurrentHashMap<>();

    /** One registry per plugin class loader, shared by every runtime generation. */
    public static VirtualOrigins shared() {
        return SHARED;
    }

    void register(InetSocketAddress localEndpoint, InetSocketAddress origin) {
        byEndpoint.put(localEndpoint, origin);
    }

    void unregister(InetSocketAddress localEndpoint, InetSocketAddress origin) {
        byEndpoint.remove(localEndpoint, origin);
    }

    /** Virtual origin of a backend client address, or null for any other connection. */
    public InetSocketAddress lookup(SocketAddress backendClient) {
        if (!(backendClient instanceof InetSocketAddress)) return null;
        InetSocketAddress client = (InetSocketAddress) backendClient;
        if (client.getAddress() == null || !client.getAddress().isLoopbackAddress()) return null;
        return byEndpoint.get(client);
    }

    public int size() {
        return byEndpoint.size();
    }
}
