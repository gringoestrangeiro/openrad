package io.github.gringoestrangeiro.openradclient;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradplugin.session.ServerDisconnect;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlDispatchTest {
    @TempDir java.nio.file.Path directory;

    private static byte[] server(int operation, byte[]... fields) {
        byte[] record = u32(TlvCodec.SERVER_OP, operation);
        for (byte[] field : fields) record = concat(record, field);
        return record;
    }

    @Test void refusalOnTheSharedAttachmentKeepsTheSession() throws IOException {
        // Rust and the Paper plugin only record a 44; ending the session made the mod reconnect.
        assertEquals(ClientVpnRuntime.ControlEvent.REFUSAL, ClientVpnRuntime.classify(server(44, u64(0x020001e1, 2))));
        assertEquals(ClientVpnRuntime.ControlEvent.MEMBERSHIP, ClientVpnRuntime.classify(server(38)));
        assertEquals(ClientVpnRuntime.ControlEvent.MEMBERSHIP, ClientVpnRuntime.classify(server(41)));
        assertEquals(ClientVpnRuntime.ControlEvent.PEER_RECORD, ClientVpnRuntime.classify(server(11)));
    }

    @Test void serverDisconnectCarriesItsReason() {
        ServerDisconnect replaced = assertThrows(ServerDisconnect.class,
                () -> ClientVpnRuntime.classify(server(16, u32(ServerDisconnect.REASON_TAG, 1))));
        assertTrue(replaced.replaced());
        ServerDisconnect other = assertThrows(ServerDisconnect.class,
                () -> ClientVpnRuntime.classify(server(16, u32(ServerDisconnect.REASON_TAG, 9))));
        assertFalse(other.replaced());
        assertTrue(ClientVpnRuntime.safeReason(other).contains("reason 9"));
    }

    @Test void reconnectBackoffGrowsAndIsBounded() {
        assertEquals(3, ClientVpnRuntime.retrySeconds(1));
        assertEquals(6, ClientVpnRuntime.retrySeconds(2));
        assertEquals(12, ClientVpnRuntime.retrySeconds(3));
        assertEquals(48, ClientVpnRuntime.retrySeconds(5));
        assertEquals(60, ClientVpnRuntime.retrySeconds(6));
        assertEquals(60, ClientVpnRuntime.retrySeconds(16));
    }

    @Test void forceRelayDisablesDirectTransportsAndRoundTrips() {
        ClientConfig plain = new ClientConfig(false, List.of("Synthetic"));
        assertFalse(plain.forceRelay());
        var all = ClientPeerService.transports(plain);
        assertTrue(all.tcp() && all.udp() && all.relay());
        var relay = ClientPeerService.transports(plain.withForceRelay(true));
        assertFalse(relay.tcp());
        assertFalse(relay.udp());
        assertTrue(relay.relay());

        Properties properties = new Properties();
        properties.setProperty("network-names", "Synthetic");
        assertFalse(ClientConfig.from(properties).forceRelay());
        properties.setProperty("force-relay", "true");
        assertTrue(ClientConfig.from(properties).forceRelay());
        properties.setProperty("force-relay", "yes");
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.from(properties));
    }

    @Test void forceRelaySurvivesSaveAndLoad() throws IOException {
        ClientConfig relay = new ClientConfig(false, List.of("Synthetic")).withForceRelay(true);
        relay.save(directory);
        assertEquals(relay, ClientConfig.load(directory));
    }
}
