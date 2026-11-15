package io.github.gringoestrangeiro.openradplugin.network;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class MembershipTest {
    private static byte[] snapshot(long rid, String server) {
        return tlv(
                0x1316,
                tlv(
                        0x1317,
                        concat(
                                u64(0x020001e1, rid),
                                text(0x03000304, "Peer"),
                                u32(0x01000305, 0x1a000005),
                                text(0x030001c9, server))));
    }

    @Test
    void snapshotPreservesHostnameAndPrivateCoordinatorText() {
        for (String server : List.of("peer.example.net", "10.8.0.1")) {
            var membership = new Membership(1);
            assertDoesNotThrow(() -> membership.snapshot(snapshot(7, server)));
            var peer = membership.peers().get(7L);
            assertNotNull(peer);
            assertEquals(server, peer.server());
        }
    }

    private static byte[] announce(long rid, boolean includeServer, Long state) {
        byte[] payload =
                concat(
                        u64(0x020001e1, rid),
                        tlv(0x0d000309, new byte[16]),
                        u32(0x01000305, 0x1a000000 + rid),
                        text(0x03000304, "new-device"),
                        u32(0x0100030a, 1),
                        includeServer ? text(0x030001c9, "192.0.2.10") : new byte[0],
                        state != null ? u32(0x010003a0, state) : new byte[0]);
        return concat(u32(io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.SERVER_OP, 41), tlv(0x131f, tlv(0x131e, payload)));
    }

    @Test
    void liveMemberAnnouncementWithoutPresenceCanConnectButExplicitOfflineCannot() {
        var membership = new Membership(1);
        membership.snapshot(
                tlv(
                        0x1316,
                        tlv(
                                0x1315,
                                concat(
                                        text(0x03000306, "Private"),
                                        tlv(0x0d000309, new byte[16]),
                                        u32(0x0100030a, 2)))));
        var names = java.util.Set.of("Private");
        membership.changes(announce(7, true, null));
        assertEquals(1, membership.peers().get(7L).state());
        assertEquals(1, membership.eligible(names, java.util.Set.of(), java.util.Set.of()).size());
        membership.changes(announce(8, true, 0L));
        membership.changes(announce(9, false, null));
        assertEquals(0, membership.peers().get(8L).state());
        assertEquals(0, membership.peers().get(9L).state());
        assertEquals(1, membership.eligible(names, java.util.Set.of(), java.util.Set.of()).size());
        // A later partial update keeps the known coordinator and presence.
        membership.changes(announce(7, false, null));
        assertEquals("192.0.2.10", membership.peers().get(7L).server());
        assertEquals(1, membership.peers().get(7L).state());
        // Presence events still revoke dial eligibility immediately.
        membership.changes(
                concat(
                        u32(io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.SERVER_OP, 41),
                        tlv(0x131f, tlv(0x1366, concat(u64(0x020001e1, 7), u32(0x010003a0, 0))))));
        assertEquals(0, membership.peers().get(7L).state());
        assertTrue(membership.eligible(names, java.util.Set.of(), java.util.Set.of()).isEmpty());
    }
    private static byte[] wireName(String text) {
        byte[] body = text.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        return concat(body, new byte[] {0, 1});
    }

    @Test
    void peerNamesAreNeutralisedButNetworkNameKeysAreNot() {
        var membership = new Membership(1);
        String network = "Net§a‍";
        membership.snapshot(
                tlv(
                        0x1316,
                        concat(
                                tlv(
                                        0x1315,
                                        concat(
                                                tlv(0x03000306, wireName(network)),
                                                tlv(0x0d000309, new byte[16]),
                                                u32(0x0100030a, 2))),
                                tlv(
                                        0x1317,
                                        concat(
                                                u64(0x020001e1, 7),
                                                tlv(
                                                        0x03000304,
                                                        wireName("Op§c‮evil")),
                                                u32(0x01000305, 0x1a000005))))));
        assertEquals("Op�c�evil", membership.peers().get(7L).name());
        assertEquals(network, membership.networks().get("00".repeat(16)).name());
    }

    private static byte[] vipRecord(long ip) {
        return concat(
                u32(io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.SERVER_OP, 38),
                tlv(0x1316, concat(u32(0x01000305, ip), tlv(0x09000305, new byte[16]))));
    }

    @Test
    void ownVipMustBeInTheServiceRange() {
        assertEquals("26.0.0.5", Membership.ownVip(vipRecord(0x1a000005)).getHostAddress());
        assertThrows(IllegalArgumentException.class, () -> Membership.ownVip(vipRecord(0x0a000002)));
        assertThrows(IllegalArgumentException.class, () -> Membership.ownVip(vipRecord(0)));
        assertThrows(IllegalArgumentException.class, () -> Membership.ownVip(vipRecord(0xffffffffL)));
        assertNull(Membership.ownVip(
                concat(
                        u32(io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.SERVER_OP, 38),
                        tlv(0x1316, new byte[0]))));
    }
}
