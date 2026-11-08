package io.github.gringoestrangeiro.openradplugin.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

final class PaperAddressForwarderTest {
    private static final InetSocketAddress BACKEND_CLIENT =
            new InetSocketAddress("127.0.0.1", 49154);
    private static final InetSocketAddress VPN_PLAYER =
            new InetSocketAddress("26.1.2.5", 25565);

    @Test
    void onlyTheRegisteredBackendClientReceivesTheVirtualAddress() throws Exception {
        var fixture = new Fixture();
        try (SocketChannel owner = SocketChannel.open()) {
            fixture.forwarder.initialized(fixture.channel);
            assertTrue(fixture.queue.isEmpty());
            assertEquals(BACKEND_CLIENT, fixture.connection.address);

            fixture.origins.register(BACKEND_CLIENT, VPN_PLAYER, owner);
            fixture.forwarder.initialized(fixture.channel);
            fixture.queue.remove().run();
            assertEquals(VPN_PLAYER, fixture.connection.address);
        }
    }

    @Test
    void delayedForwardingCannotReuseAnEarlierFlowForTheSameAddresses() throws Exception {
        var fixture = new Fixture();
        try (SocketChannel earlier = SocketChannel.open();
                SocketChannel replacement = SocketChannel.open()) {
            fixture.origins.register(BACKEND_CLIENT, VPN_PLAYER, earlier);
            fixture.forwarder.initialized(fixture.channel);
            fixture.origins.unregister(BACKEND_CLIENT, earlier);
            fixture.origins.register(BACKEND_CLIENT, VPN_PLAYER, replacement);

            fixture.queue.remove().run();
            assertEquals(BACKEND_CLIENT, fixture.connection.address);
        }
    }

    @Test
    void delayedForwardingPreservesAnAddressAlreadySetByAProxy() throws Exception {
        var fixture = new Fixture();
        var proxyAddress = new InetSocketAddress("127.0.0.2", 49155);
        try (SocketChannel owner = SocketChannel.open()) {
            fixture.origins.register(BACKEND_CLIENT, VPN_PLAYER, owner);
            fixture.forwarder.initialized(fixture.channel);
            fixture.connection.address = proxyAddress;

            fixture.queue.remove().run();
            assertEquals(proxyAddress, fixture.connection.address);
        }
    }

    static final class Connection {
        SocketAddress address = BACKEND_CLIENT;
    }

    record Pipeline(Connection connection) {
        public Connection get(String handler) {
            assertEquals("packet_handler", handler);
            return connection;
        }
    }

    record AcceptedChannel(SocketAddress remoteAddress, Pipeline pipeline, Executor eventLoop) {}

    private static final class Fixture {
        final VirtualOrigins origins = new VirtualOrigins();
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final Connection connection = new Connection();
        final AcceptedChannel channel = new AcceptedChannel(
                BACKEND_CLIENT, new Pipeline(connection), queue::add);
        final PaperAddressForwarder forwarder;

        Fixture() throws ReflectiveOperationException {
            forwarder = new PaperAddressForwarder(
                    origins,
                    message -> fail(message),
                    null,
                    null,
                    AcceptedChannel.class.getMethod("remoteAddress"),
                    AcceptedChannel.class.getMethod("pipeline"),
                    Pipeline.class.getMethod("get", String.class),
                    AcceptedChannel.class.getMethod("eventLoop"));
        }
    }
}
