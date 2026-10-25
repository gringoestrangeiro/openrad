package io.github.gringoestrangeiro.openradclient;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.gringoestrangeiro.openradplugin.network.Membership;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Set;

class PeerAuthorizationTest {
    private static final long OWN = 100;
    private static byte[] guid(int value) { byte[] bytes = new byte[16]; bytes[0] = (byte) value; return bytes; }
    private static byte[] display(String name) { return concat(name.getBytes(StandardCharsets.UTF_16BE), new byte[] {0, 1}); }
    private static byte[] network(int id, String name, int ownRole) {
        return tlv(0x1315, concat(tlv(0x0d000309, guid(id)), tlv(0x03000306, display(name)), u32(0x0100030a, ownRole)));
    }
    private static byte[] peer(long rid, int vip, int state) {
        return tlv(0x1317, concat(u64(0x020001e1, rid), tlv(0x03000304, display("Test peer")),
                u32(0x01000305, vip), text(0x030001c9, "127.0.0.1"), u32(0x010003a0, state)));
    }
    private static byte[] member(long rid, int network, int role) {
        return tlv(0x1318, concat(u64(0x020001e1, rid), tlv(0x0d000309, guid(network)), u32(0x0100030a, role)));
    }
    private static Membership membership(byte[]... entries) {
        Membership result = new Membership(OWN);
        result.snapshot(concat(u32(TlvCodec.SERVER_OP, 38), tlv(0x1316, concat(entries))));
        return result;
    }

    @Test void persistedUnselectedMembershipNeverAuthorizesGameTraffic() {
        Membership membership = membership(network(1, "Selected", 1), network(2, "Old saved network", 1),
                peer(200, 0x1a000002, 1), peer(300, 0x1a000003, 5),
                member(200, 1, 1), member(200, 2, 1), member(300, 2, 1));
        var grants = PeerAuthorization.select(membership, Set.of("Selected"));
        assertEquals(Set.of(200L), grants.keySet());
        assertEquals(Set.of(java.util.HexFormat.of().formatHex(guid(1))), grants.get(200L).networkIds());
        assertTrue(PeerAuthorization.select(membership, Set.of()).isEmpty());
    }

    @Test void pendingRolesOfflinePeersAndNonVpnAddressesAreDenied() {
        Membership membership = membership(network(1, "Selected", 1), network(2, "Awaiting approval", 0),
                peer(200, 0x1a000002, 1), peer(300, 0x1a000003, 1), peer(400, 0x1a000004, 0),
                peer(500, 0x7f000001, 1), peer(OWN, 0x1a000001, 1),
                member(200, 1, 0), member(300, 2, 1), member(400, 1, 1), member(500, 1, 1), member(OWN, 1, 1));
        assertTrue(PeerAuthorization.select(membership, Set.of("Selected", "Awaiting approval")).isEmpty());
    }

    @Test void revokedMembershipRemovesGrantImmediatelyAndDuplicateVipDeniesBothIdentities() {
        Membership membership = membership(network(1, "Selected", 1), peer(200, 0x1a000002, 1), member(200, 1, 1));
        assertEquals(1, PeerAuthorization.select(membership, Set.of("Selected")).size());
        membership.changes(concat(u32(TlvCodec.SERVER_OP, 42), tlv(0x131f,
                tlv(0x131d, concat(u64(0x020001e1, 200), tlv(0x0d000309, guid(1)))))));
        assertTrue(PeerAuthorization.select(membership, Set.of("Selected")).isEmpty());
        Membership duplicate = membership(network(1, "Selected", 1), peer(200, 0x1a000002, 1),
                peer(300, 0x1a000002, 1), member(200, 1, 1), member(300, 1, 1));
        assertTrue(PeerAuthorization.select(duplicate, Set.of("Selected")).isEmpty());
    }
}
