package io.github.gringoestrangeiro.openradplugin.network;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

class NetworkManagementTest {
    private static final String NAME = "Synthetic 雪 🌐", GUID = "11111111111111111111111111111111";

    private static byte[] response(long id, int action, int context, byte[] body) {
        return concat(
                u32(TlvCodec.SERVER_OP, 37),
                tlv(
                        0x131a,
                        concat(
                                u64(0x02000340, id),
                                u32(0x0100030c, action),
                                u32(0x0100034a, context),
                                body)));
    }

    private static byte[] networkEntry() {
        return concat(
                tlv(0x0d000309, HexFormat.of().parseHex(GUID)),
                text(0x03000306, NAME),
                u32(0x0100030a, 2));
    }

    @Test
    void privateCreateEmitsVerifierAndCorrelatedManagementFieldsAndAppliesReply() {
        char[] password = "synthetic network password".toCharArray();
        byte[] salt = new byte[32];
        try (NetworkManagement operation =
                NetworkManagement.createPrivate(NAME, password, 7, 9, salt)) {
            assertArrayEquals(new char[password.length], password);
            byte[] request = operation.start();
            var outer = fields(request);
            assertEquals(52, number(outer, TlvCodec.CLIENT_OP));
            var f = fields(field(outer, 0x1319));
            assertEquals(1, number(f, 0x0100030c));
            assertEquals(7, wide(f, 0x02000340));
            assertEquals(9, number(f, 0x0100034a));
            var create = fields(field(f, 0x1314));
            assertEquals(0, number(create, 0x0b000308));
            assertEquals(NAME, TlvCodec.text(field(create, 0x03000306)));
            byte[] verifier = field(create, 0x0a000307);
            assertEquals(
                    32,
                    java.nio.ByteBuffer.wrap(verifier)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .getInt());
            assertArrayEquals(salt, Arrays.copyOfRange(verifier, 4, 36));
            assertTrue(verifier.length > 36);
            Membership membership = new Membership(12345);
            assertNull(
                    operation.handle(response(8, 1, 9, tlv(0x1315, networkEntry())), membership));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            operation.handle(
                                    response(7, 1, 8, tlv(0x1315, networkEntry())), membership));
            var result =
                    operation.handle(response(7, 1, 9, tlv(0x1315, networkEntry())), membership);
            assertTrue(result.success());
            assertEquals(GUID, result.networkId());
            assertTrue(operation.complete());
            assertEquals(2, membership.role(GUID, 12345));
            assertEquals(NAME, membership.networks().get(GUID).name());
        }
    }

    @Test
    void leaveAndDeleteRequireMatchingNetworkActionContextAndRemoveMembership() {
        for (int action : new int[] {3, 7}) {
            Membership membership = new Membership(12345);
            membership.snapshot(tlv(0x1316, tlv(0x1315, networkEntry())));
            try (NetworkManagement operation =
                    action == 3
                            ? NetworkManagement.leave(GUID, 7, 9)
                            : NetworkManagement.delete(GUID, 7, 9)) {
                var f = fields(field(fields(operation.start()), 0x1319));
                assertEquals(action, number(f, 0x0100030c));
                assertArrayEquals(HexFormat.of().parseHex(GUID), field(f, 0x0d000309));
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                operation.handle(
                                        response(
                                                7,
                                                action == 3 ? 7 : 3,
                                                9,
                                                tlv(0x0d000309, HexFormat.of().parseHex(GUID))),
                                        membership));
                assertTrue(
                        operation
                                .handle(
                                        response(
                                                7,
                                                action,
                                                9,
                                                tlv(0x0d000309, HexFormat.of().parseHex(GUID))),
                                        membership)
                                .success());
                assertTrue(membership.networks().isEmpty());
            }
        }
    }

    @Test
    void serverErrorsCompleteWithoutChangingMembershipAndSecretsNeverAppearInDiagnostics() {
        char[] password = "synthetic secret marker".toCharArray();
        try (NetworkManagement operation = NetworkManagement.createPrivate(NAME, password, 7, 9)) {
            operation.start();
            assertFalse(operation.toString().contains("synthetic secret marker"));
            var result =
                    operation.handle(response(7, 1, 9, u32(0x010001d2, 19)), new Membership(12345));
            assertFalse(result.success());
            assertEquals(19, result.errorCode());
            assertTrue(operation.complete());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkManagement.delete("00".repeat(16), 7, 9));
        assertThrows(IllegalArgumentException.class, () -> NetworkManagement.leave(GUID, 0, 9));
    }
}
