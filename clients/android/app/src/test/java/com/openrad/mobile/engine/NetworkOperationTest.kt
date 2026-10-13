package com.openrad.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** Mirrors `tests/network_management.rs`, with a synthetic SH responder as the server. */
class NetworkOperationTest {
    private val name = "Synthetic private network"
    private val guid = "000102030405060708090a0b0c0d0e0f"
    private val own = 42L

    private fun packet(op: Long, tag: Long, body: ByteArray) = Tlv.u32(Protocol.SERVER_OP, op) + Tlv.tlv(tag, body)

    private fun network(id: String, name: String, role: Long) =
        Tlv.tlv(0x1315, concat(Tlv.tlv(0x0d000309, hexToBytes(id)), Tlv.text(0x03000306, name), Tlv.u32(0x0100030a, role)))

    private fun response(action: Long, request: Long, context: Long?, fields: ByteArray): ByteArray {
        var body = Tlv.u32(0x0100030c, action) + Tlv.u64(0x02000340, request)
        if (context != null) body += Tlv.u32(0x0100034a, context)
        return packet(37, 0x131a, body + fields)
    }

    private fun authPacket(blob: ByteArray, sequence: Long, request: Long? = 101) = packet(
        40, 0x131c,
        concat(
            Tlv.u64(0x02000303, 0),
            request?.let { Tlv.u64(0x02000340, it) } ?: ByteArray(0),
            Tlv.u32(0x010003be, sequence),
            Tlv.tlv(0x0a00030e, blob),
        ),
    )

    private fun blob(packet: ByteArray): ByteArray =
        Tlv.unique(Tlv.parse(Tlv.unique(Tlv.parse(packet), 0x131c).value), 0x0a00030e).value

    @Test
    fun privateJoinAuthenticatesAgainstServerProofAndRecordsRole() {
        val op = NetworkOperation.join(name, "synthetic password".toCharArray(), 101, 7)
        assertTrue(op.isPrivateJoin)
        val server = ShServer(Protocol.networkIdentity(name), "synthetic password\u0000".toByteArray(), ByteArray(16) { 3 }, BigInteger.valueOf(789123))
        val m = Membership(own)
        val parameters = server.hello(blob(op.packet))
        // A delayed message for a different sequence cannot advance the handshake.
        assertNull(op.handle(authPacket(parameters, 6), m).send)
        val public = op.handle(authPacket(parameters, 7), m).send!!
        val continuation = Tlv.parse(Tlv.unique(Tlv.parse(public), 0x131c).value)
        assertNull(Tlv.optional(continuation, 0x03000306))
        val challenge = server.public(blob(public))
        val proof = op.handle(authPacket(challenge, 7), m).send!!
        val (confirmation, _) = server.proof(blob(proof))
        assertNull(op.handle(authPacket(confirmation, 7), m).send)
        val done = op.handle(response(2, 101, null, Tlv.tlv(0x1316, network(guid, name, 1))), m).outcome
        val joined = done as NetworkOperation.Outcome.Joined
        assertEquals(guid, joined.network.networkId)
        assertFalse(joined.pendingApproval)
        assertEquals(1L, m.role(guid, own))
    }

    @Test
    fun wrongPasswordIsRejectedByServerProof() {
        val op = NetworkOperation.join(name, "wrong password!".toCharArray(), 101, 7)
        val server = ShServer(Protocol.networkIdentity(name), "synthetic password\u0000".toByteArray())
        val m = Membership(own)
        val public = op.handle(authPacket(server.hello(blob(op.packet)), 7), m).send!!
        val proof = op.handle(authPacket(server.public(blob(public)), 7), m).send!!
        assertThrows(ProtocolException::class.java) { server.proof(blob(proof)) }
        // Server failure blob ends the operation with a clear error.
        assertThrows(ProtocolException::class.java) {
            op.handle(authPacket(Sh.record(0x10000000L, ByteArray(4)), 7), m)
        }
    }

    @Test
    fun publicJoinCompletesPendingThenApproved() {
        val op = NetworkOperation.join(name, null, 5, 5)
        assertFalse(op.isPrivateJoin)
        val m = Membership(own)
        assertNull(op.handle(response(2, 6, null, Tlv.tlv(0x1316, network(guid, name, 0))), m).outcome)
        val pending = op.handle(response(2, 5, null, Tlv.tlv(0x1316, network(guid, name, 0))), m).outcome as NetworkOperation.Outcome.Joined
        assertTrue(pending.pendingApproval)
        val approval = packet(42, 0x131f, Tlv.tlv(0x131e, Tlv.tlv(0x0d000309, hexToBytes(guid)) + Tlv.u64(0x020001e1, 84)))
        assertEquals(setOf(guid), NetworkOperation.approvedNetworkIds(Tlv.parse(approval)))
    }

    @Test
    fun refusedJoinReportsServerError() {
        val op = NetworkOperation.join(name, null, 5, 5)
        val e = assertThrows(ProtocolException::class.java) {
            op.handle(response(2, 5, null, Tlv.u32(0x010001d2, 3)), Membership(own))
        }
        assertTrue(e.message!!.contains("server error 3"))
    }

    @Test
    fun publicJoinRejectsPasswordChallenge() {
        val op = NetworkOperation.join(name, null, 5, 5)
        assertThrows(ProtocolException::class.java) {
            op.handle(authPacket(Sh.message(2, 0x30000000L, ByteArray(1)), 5, 5), Membership(own))
        }
    }

    @Test
    fun ambiguousUncorrelatedRepliesAreAcceptedOnlyByPrivateJoins() {
        val private = NetworkOperation.join(name, "synthetic password".toCharArray(), 101, 7)
        val public = NetworkOperation.join("Other", null, 102, 8)
        val uncorrelated = packet(40, 0x131c, Tlv.u64(0x02000303, 0) + Tlv.tlv(0x0a00030e, ByteArray(8)))
        assertTrue(private.acceptsAuthReply(uncorrelated))
        assertFalse(public.acceptsAuthReply(uncorrelated))
        assertFalse(private.acceptsAuthReply(authPacket(ByteArray(4), 8, 102)))
    }

    @Test
    fun createLeaveAndDeleteCorrelateContext() {
        val m = Membership(own)
        val create = NetworkOperation.create(name, "synthetic password".toCharArray(), 9, 3)
        val inner = Tlv.unique(Tlv.parse(network(guid, name, 2)), 0x1315).value
        assertThrows(ProtocolException::class.java) {
            create.handle(response(1, 9, 4, Tlv.tlv(0x1315, inner)), Membership(own))
        }
        val created = create.handle(response(1, 9, 3, Tlv.tlv(0x1315, inner)), m).outcome as NetworkOperation.Outcome.Created
        assertEquals(guid, created.network.networkId)
        assertNotNull(m.networks[guid])
        val leave = NetworkOperation.remove(guid, name, delete = false, id = 10, seq = 4)
        val removed = leave.handle(response(3, 10, 4, Tlv.tlv(0x0d000309, hexToBytes(guid))), m).outcome as NetworkOperation.Outcome.Removed
        assertFalse(removed.deleted)
        assertNull(m.networks[guid])
        val delete = NetworkOperation.remove(guid, name, delete = true, id = 11, seq = 5)
        assertThrows(ProtocolException::class.java) {
            delete.handle(response(3, 11, 5, Tlv.tlv(0x0d000309, hexToBytes(guid))), m)
        }
    }
}
