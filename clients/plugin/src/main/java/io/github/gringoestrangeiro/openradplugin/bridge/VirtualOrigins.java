package io.github.gringoestrangeiro.openradplugin.bridge;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps the loopback source port of each bridge-to-backend connection to the VPN player's
 * virtual address. The bridge binds its local port before connecting, so a backend
 * connection from {@code 127.0.0.1:port} identifies exactly one live virtual flow. Entries
 * are bounded by the bridge's own connection limit and removed when the flow closes.
 */
public final class VirtualOrigins {
    private static final VirtualOrigins SHARED = new VirtualOrigins();

    private final ConcurrentHashMap<Integer, InetSocketAddress> byPort =
            new ConcurrentHashMap<>();

    /** One registry per plugin class loader, shared by every runtime generation. */
    public static VirtualOrigins shared() {
        return SHARED;
    }

    void register(int localPort, InetSocketAddress origin) {
        byPort.put(localPort, origin);
    }

    void unregister(int localPort, InetSocketAddress origin) {
        byPort.remove(localPort, origin);
    }

    /** Virtual origin of a backend client address, or null for any other connection. */
    public InetSocketAddress lookup(SocketAddress backendClient) {
        if (!(backendClient instanceof InetSocketAddress)) return null;
        InetSocketAddress client = (InetSocketAddress) backendClient;
        if (client.getAddress() == null || !client.getAddress().isLoopbackAddress()) return null;
        return byPort.get(client.getPort());
    }

    public int size() {
        return byPort.size();
    }
}
