package io.github.gringoestrangeiro.openradplugin.network;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.crypto.ShServer;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

class NetworkJoinTest {
    private static final String NAME = "Synthetic 雪 🌐";
    private static final long OWN_RID = 12345, ID = 7;
    private static final int SEQUENCE = 9;

    private static byte[] guid(int value) {
        byte[] guid = new byte[16];
        Arrays.fill(guid, (byte) value);
        return guid;
    }

    private static byte[] response(long id, int role) {
        return concat(
                u32(TlvCodec.SERVER_OP, 37),
                tlv(
                        0x131a,
                        concat(
                                u64(0x02000340, id),
                                u32(0x0100030c, 2),
                                tlv(
                                        0x1316,
                                        tlv(
                                                0x1315,
                                                concat(
                                                        tlv(0x0d000309, guid(0x11)),
                                                        text(0x03000306, NAME),
                                                        u32(0x0100030a, role)))))));
    }

    private static byte[] approved(int value) {
        return concat(
                u32(TlvCodec.SERVER_OP, 42),
                tlv(
                        0x131f,
                        tlv(0x131e, concat(u64(0x020001e1, 999), tlv(0x0d000309, guid(value))))));
    }

    private static byte[] authReply(byte[] blob) {
        return concat(
                u32(TlvCodec.SERVER_OP, 40),
                tlv(
                        0x131c,
                        concat(
                                u64(0x02000340, ID),
                                u32(0x010003be, SEQUENCE),
                                u64(0x02000303, 0),
                                tlv(0x0a00030e, blob))));
    }

    private static byte[] blob(byte[] request) {
        return field(fields(field(fields(request), 0x131c)), 0x0a00030e);
    }

    @Test
    void normalJoinRequiresBothCorrelatedResponseAndMatchingApprovalInEitherOrder() {
        for (boolean approvalFirst : new boolean[] {false, true}) {
            Membership membership = new Membership(OWN_RID);
            try (NetworkJoin join = new NetworkJoin(NAME, null, ID, SEQUENCE)) {
                join.start();
                join.handle(response(ID + 1, 1), membership);
                assertFalse(join.complete());
                join.handle(approved(0x22), membership);
                assertFalse(join.complete());
                join.handle(approvalFirst ? approved(0x11) : response(ID, 1), membership);
                assertFalse(join.complete());
                join.handle(approvalFirst ? response(ID, 1) : approved(0x11), membership);
                assertTrue(join.complete());
                assertFalse(join.pendingApproval());
                assertEquals(
                        1,
                        membership.role(java.util.HexFormat.of().formatHex(guid(0x11)), OWN_RID));
            }
        }
    }

    @Test
    void roleZeroCompletesRequestAsPendingApproval() {
        try (NetworkJoin join = new NetworkJoin(NAME, null, ID, SEQUENCE)) {
            join.start();
            join.handle(response(ID, 0), new Membership(OWN_RID));
            assertTrue(join.complete());
            assertTrue(join.pendingApproval());
        }
    }

    @Test
    void privateJoinChecksServerPasswordProofBeforeMembershipAndConsumesCallerPassword() {
        char[] password = "synthetic network password".toCharArray();
        Membership membership = new Membership(OWN_RID);
        try (NetworkJoin join = new NetworkJoin(NAME, password, ID, SEQUENCE);
                ShServer server =
                        new ShServer(
                                NetworkJoin.networkIdentity(NAME),
                                "synthetic network password\0".getBytes(StandardCharsets.UTF_8))) {
            assertArrayEquals(new char[password.length], password);
            byte[] hello = blob(join.start());
            assertThrows(
                    IllegalArgumentException.class, () -> join.handle(response(ID, 1), membership));
            byte[] clientPublic = blob(join.handle(authReply(server.hello(hello)), membership));
            byte[] proof =
                    blob(join.handle(authReply(server.clientPublic(clientPublic)), membership));
            try (var confirmation = server.proof(proof)) {
                assertNull(join.handle(authReply(confirmation.message()), membership));
            }
            join.handle(response(ID, 1), membership);
            assertFalse(join.complete());
            join.handle(approved(0x11), membership);
            assertTrue(join.complete());
        }
    }

    @Test
    void unicodeNetworkIdentityMatchesCanonicalUnsignedCrcVectorAndCloseDisablesJoin() {
        assertEquals(
                Long.parseUnsignedLong("10977597741686862730"),
                NetworkJoin.networkIdentity("OpenRad 雪 🌐"));
        NetworkJoin join = new NetworkJoin(NAME, null, ID, SEQUENCE);
        join.start();
        join.close();
        assertThrows(IllegalArgumentException.class, join::start);
        assertThrows(
                IllegalArgumentException.class,
                () -> join.handle(response(ID, 1), new Membership(OWN_RID)));
    }
}
