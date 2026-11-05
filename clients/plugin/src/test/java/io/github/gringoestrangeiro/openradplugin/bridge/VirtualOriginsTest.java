package io.github.gringoestrangeiro.openradplugin.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class VirtualOriginsTest {
    @Test
    void anotherLoopbackAddressUsingTheSamePortDoesNotInheritTheVirtualIdentity() throws Exception {
        VirtualOrigins origins = new VirtualOrigins();
        InetSocketAddress backendClient = new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 49152);
        InetSocketAddress virtualPlayer = new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {26, 1, 2, 3}), 25565);
        origins.register(backendClient, virtualPlayer);
        assertEquals(virtualPlayer, origins.lookup(backendClient));
        assertNull(origins.lookup(new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 2}), 49152)));
        assertNull(origins.lookup(new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 1}), 49152)));
        InetSocketAddress anotherClient = new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 2}), 49152);
        InetSocketAddress anotherPlayer = new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {26, 1, 2, 4}), 25566);
        origins.register(anotherClient, anotherPlayer);
        origins.unregister(backendClient, anotherPlayer);
        assertEquals(virtualPlayer, origins.lookup(backendClient));
        origins.unregister(backendClient, virtualPlayer);
        assertNull(origins.lookup(backendClient));
        assertEquals(anotherPlayer, origins.lookup(anotherClient));
        assertEquals(1, origins.size());
    }
}
