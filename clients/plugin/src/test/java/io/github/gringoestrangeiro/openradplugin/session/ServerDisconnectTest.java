package io.github.gringoestrangeiro.openradplugin.session;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import org.junit.jupiter.api.Test;

class ServerDisconnectTest {
    private static byte[] disconnect(byte[]... fields) {
        byte[] record = u32(TlvCodec.SERVER_OP, ServerDisconnect.OPERATION);
        for (byte[] field : fields) record = concat(record, field);
        return record;
    }

    @Test
    void replacedSessionIsRecognisedSoClientsDoNotReplaceEachOtherForever() {
        ServerDisconnect replaced = ServerDisconnect.of(disconnect(u32(ServerDisconnect.REASON_TAG, 1)));
        assertTrue(replaced.replaced());
        assertEquals(1, replaced.reason());
        assertTrue(replaced.getMessage().contains("another client"));
    }

    @Test
    void otherReasonsAreReportedWithoutRecordContents() {
        ServerDisconnect other =
                ServerDisconnect.of(
                        disconnect(
                                u32(ServerDisconnect.REASON_TAG, 7),
                                tlv(0x0a0001cd, "synthetic-credential".getBytes())));
        assertFalse(other.replaced());
        assertEquals(7, other.reason());
        assertEquals("service disconnected session (reason 7)", other.getMessage());
        assertFalse(other.getMessage().contains("synthetic"));
    }

    @Test
    void missingOrMalformedReasonIsUnknownInsteadOfThrowing() {
        assertEquals(-1, ServerDisconnect.of(disconnect()).reason());
        assertEquals(
                -1,
                ServerDisconnect.of(disconnect(tlv(ServerDisconnect.REASON_TAG, new byte[] {1})))
                        .reason());
        assertEquals(-1, ServerDisconnect.of(new byte[] {1, 2, 3}).reason());
        assertFalse(ServerDisconnect.of(disconnect()).replaced());
    }
}
