package io.github.gringoestrangeiro.openradplugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.network.Membership;
import io.github.gringoestrangeiro.openradplugin.transport.ReliableUdp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Set;

final class ChannelBindingTest {
    private static Membership.Peer peer(String name, String vip, String server, int state)
            throws Exception {
        return new Membership.Peer(
                7, name, (Inet4Address) InetAddress.getByName(vip), server, state, Set.of("a"));
    }

    @Test
    void renamesPresenceRefreshesAndExtraNetworksKeepChannels() throws Exception {
        var bound = peer("old", "26.0.0.7", "192.0.2.1", 1);
        assertTrue(VpnRuntime.stillBound(bound, peer("new", "26.0.0.7", "192.0.2.1", 5), Set.of("a"), Set.of("a", "b")));
    }

    @Test
    void bindingChangesRevokeChannels() throws Exception {
        var bound = peer("p", "26.0.0.7", "192.0.2.1", 1);
        assertFalse(VpnRuntime.stillBound(bound, null, Set.of("a"), Set.of("a")));
        assertFalse(VpnRuntime.stillBound(bound, peer("p", "26.0.0.8", "192.0.2.1", 1), Set.of("a"), Set.of("a")));
        assertFalse(VpnRuntime.stillBound(bound, peer("p", "26.0.0.7", "192.0.2.2", 1), Set.of("a"), Set.of("a")));
        assertFalse(VpnRuntime.stillBound(bound, peer("p", "26.0.0.7", "192.0.2.1", 0), Set.of("a"), Set.of("a")));
        assertFalse(VpnRuntime.stillBound(bound, bound, Set.of("a", "b"), Set.of("b")));
        assertFalse(VpnRuntime.stillBound(bound, bound, Set.of("a"), null));
    }

    @Test
    void kernelBufferExhaustionIsCongestionNotFailure() {
        assertTrue(ReliableUdp.sendRefused(new IOException("No buffer space available")));
        assertFalse(ReliableUdp.sendRefused(new IOException("Network is unreachable")));
    }
}
