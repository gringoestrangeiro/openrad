package io.github.gringoestrangeiro.openradplugin.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Channel;
import org.junit.jupiter.api.Test;

class VirtualOriginsTest {
    private static final class FakeChannel implements Channel {
        volatile boolean open = true;

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    @Test
    void anotherLoopbackAddressUsingTheSamePortDoesNotInheritTheVirtualIdentity() throws Exception {
        VirtualOrigins origins = new VirtualOrigins();
        FakeChannel first = new FakeChannel();
        FakeChannel second = new FakeChannel();
        InetSocketAddress backendClient = new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 49152);
        InetSocketAddress virtualPlayer = new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {26, 1, 2, 3}), 25565);
        origins.register(backendClient, virtualPlayer, first);
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
        origins.register(anotherClient, anotherPlayer, second);
        origins.unregister(backendClient, second);
        assertEquals(virtualPlayer, origins.lookup(backendClient));
        origins.unregister(backendClient, first);
        assertNull(origins.lookup(backendClient));
        assertEquals(anotherPlayer, origins.lookup(anotherClient));
        assertEquals(1, origins.size());
    }

    @Test
    void aClosedBridgeSocketStillRegisteredDoesNotLendItsEndpoint() throws Exception {
        // A rejected non-blocking connect closes the socket inside the JDK before the bridge
        // unregisters it; a local client reusing the freed port must stay on loopback.
        VirtualOrigins origins = new VirtualOrigins();
        FakeChannel failed = new FakeChannel();
        InetSocketAddress endpoint = new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 49153);
        origins.register(endpoint, new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {26, 1, 2, 3}), 25565), failed);
        failed.close();
        assertNull(origins.lookup(endpoint));
        // A stale owner's late unregister must not remove a newer flow on the same endpoint.
        FakeChannel replacement = new FakeChannel();
        InetSocketAddress player = new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {26, 1, 2, 5}), 25565);
        origins.register(endpoint, player, replacement);
        origins.unregister(endpoint, failed);
        assertEquals(player, origins.lookup(endpoint));
    }
}
