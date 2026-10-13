package com.openrad.mobile.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class ProtocolTest {
    private val p = fixture("reference.json").getJSONObject("protocol")

    @Test
    fun buildersMatchFixedWireVectors() {
        assertArrayEquals(p.hex("login"), Protocol.login("openrad-test", 143, 4))
        assertArrayEquals(p.hex("connect_login"), Protocol.login("openrad-test", 143, 5, 12345))
        assertArrayEquals(p.hex("public_list"), Protocol.publicList("Minecraft", 1, 0))
        assertArrayEquals(p.hex("join"), Protocol.join("Minecraft [Português 16]", 2, 1))
        assertArrayEquals(p.hex("syn"), Tunnel.syn(12345, byteArrayOf(2, 0, 192.toByte(), 0, 2, 1)))
    }

    @Test
    fun tlvsRejectTruncationDuplicateAndWrongWidth() {
        val b = Tlv.u32(123, 45)
        assertThrows(ProtocolException::class.java) { Tlv.parse(b.copyOf(b.size - 1)) }
        assertThrows(ProtocolException::class.java) { Tlv.unique(Tlv.parse(b + b), 123) }
        assertThrows(ProtocolException::class.java) { Record(1, ByteArray(4)).u64() }
        assertThrows(ProtocolException::class.java) { Tlv.decodeText(Record(1, byteArrayOf(0, 0))) }
    }

    @Test
    fun ethernetMultipleFramesAndOffloadRejection() {
        val f = p.hex("ethernet")
        val two = f + f.copyOfRange(2, f.size)
        val decoded = Tunnel.decode(two) as Tunnel.Packet.Frames
        assertEquals(2, decoded.frames.size)
        assertThrows(ProtocolException::class.java) { Tunnel.decode(two.copyOf(two.size - 1)) }
        val bad = f.copyOf().also { it[2] = 1 }
        assertThrows(ProtocolException::class.java) { Tunnel.decode(bad) }
        val frame = (Tunnel.decode(f) as Tunnel.Packet.Frames).frames[0]
        assertArrayEquals(f, Tunnel.encode(frame))
    }

    @Test
    fun keepalivesRoundTrip() {
        val k = Tunnel.decode(Tunnel.keepalive(77, reply = true)) as Tunnel.Packet.Keepalive
        assertEquals(77, k.sequence)
        assertTrue(k.reply)
        assertThrows(ProtocolException::class.java) { Tunnel.decode(Tunnel.keepalive(1, false) + byteArrayOf(0)) }
    }

    @Test
    fun crcAndCreateVerifierMatchIndependentUnicodeVector() {
        val v = fixture("network-verifier.json")
        val name = v.getString("name")
        assertEquals(v.getBigInteger("identity").toLong(), Protocol.networkIdentity(name))
        assertEquals(
            v.getString("verifier"),
            Protocol.networkVerifier(name, v.getString("password").toCharArray(), v.hex("salt")).toHex(),
        )
        assertNotEquals(Protocol.networkIdentity(name), Protocol.networkIdentity("$name "))
    }

    @Test
    fun validationCountsUtf16AndRejectsControls() {
        for (name in listOf("", " \t ", "a\nb", "🌐".repeat(128))) {
            assertThrows(ProtocolException::class.java) { Protocol.validateNetworkName(name) }
        }
        Protocol.validateNetworkName("🌐".repeat(127))
        assertThrows(ProtocolException::class.java) { Protocol.passwordBytes("short".toCharArray()) }
        assertThrows(ProtocolException::class.java) { Protocol.passwordBytes("hidden\npassword".toCharArray()) }
        assertArrayEquals("secret é".toByteArray() + byteArrayOf(0), Protocol.passwordBytes("secret é".toCharArray()))
    }

    @Test
    fun registeredIdentityRequiresDirectFields() {
        val body = concat(
            Tlv.u64(0x020001e1, 123), Tlv.u32(0x01000305, 0x1a010203), Tlv.text(0x03000304, "owned"),
            Tlv.tlv(0x09000305, ByteArray(16) { 1 }), Tlv.tlv(0x0a0001c8, "synthetic-password".toByteArray()),
            Tlv.text(0x030001e2, "192.0.2.1"),
        )
        val id = Protocol.Provisioning().run {
            assertNull(receive(Tlv.u32(Protocol.SERVER_OP, 21)))
            receive(Tlv.u32(Protocol.SERVER_OP, 8) + Tlv.tlv(0x1233, body))!!
        }
        assertEquals(123, id.rid)
        assertEquals("26.1.2.3", Ip.format(id.vip))
        assertArrayEquals("synthetic-password".toByteArray(), id.credential)
        assertFalse(id.toString().contains("synthetic-password"))
        val bad = Tlv.parse(Tlv.u32(Protocol.SERVER_OP, 8) + Tlv.tlv(0x1233, Tlv.tlv(0x1317, body)))
        assertThrows(ProtocolException::class.java) { Identity.registered(bad) }
    }

    @Test
    fun registrationReadsOwnAssignmentAndRedirects() {
        val reg = Protocol.Registration()
        assertNull(reg.receive(Tlv.u32(Protocol.SERVER_OP, 21)))
        assertNull(reg.receive(Tlv.u32(Protocol.SERVER_OP, 9)))
        val nets = Tlv.tlv(0x1316, Tlv.u32(0x01000305, 0x1a000005) + Tlv.tlv(0x09000305, ByteArray(16) { 7 }))
        val assigned = reg.receive(Tlv.u32(Protocol.SERVER_OP, 38) + nets)!!
        assertEquals("26.0.0.5", Ip.format(assigned.vip))
        val redirect = Tlv.u32(Protocol.SERVER_OP, 12) +
            Tlv.tlv(0x1238, Tlv.tlv(0x1234, Tlv.text(0x030001c9, "192.0.2.9")))
        val e = assertThrows(Protocol.ServerRedirect::class.java) { Protocol.Registration().receive(redirect) }
        assertEquals("192.0.2.9", e.host)
    }

    private fun peerRecord(rid: Long, vip: Int, state: Long = 1) = concat(
        Tlv.u64(0x020001e1, rid), Tlv.text(0x03000304, "owned"), Tlv.u32(0x01000305, vip.toLong() and 0xffffffffL),
        Tlv.text(0x030001c9, "192.0.2.1"), Tlv.u32(0x010003a0, state),
    )

    @Test
    fun joiningASecondNetworkPreservesSharedPeerMemberships() {
        val membership = Membership(1)
        for ((name, fill) in listOf("first" to 1, "second" to 2)) {
            val network = ByteArray(16) { fill.toByte() }
            membership.snapshot(
                Tlv.tlv(
                    0x1316,
                    concat(
                        Tlv.tlv(0x1315, Tlv.text(0x03000306, name) + Tlv.tlv(0x0d000309, network)),
                        Tlv.tlv(0x1317, peerRecord(7, Ip.parse("192.0.2.7"))),
                        Tlv.tlv(0x1318, Tlv.u64(0x020001e1, 7) + Tlv.tlv(0x0d000309, network)),
                    ),
                ),
            )
        }
        assertEquals(2, membership.peers[7]!!.networkIds.size)
        assertTrue(membership.permits(7))
        membership.removeNetwork(ByteArray(16) { 1 }.toHex())
        assertTrue(membership.permits(7))
        membership.removeNetwork(ByteArray(16) { 2 }.toHex())
        assertFalse(membership.permits(7))
    }

    @Test
    fun everyMembershipMutationInvalidatesVersionKeyedPermitCaches() {
        // The engine caches permits() per Membership.version on the packet path.
        val id = ByteArray(16) { 3 }
        val m = Membership(1)
        var cachedVersion = -1L
        var cached = emptySet<Long>()
        fun check(mutate: () -> Unit) {
            val before = m.version
            runCatching(mutate)
            assertTrue("mutation must bump the version", m.version > before)
            if (m.version != cachedVersion) {
                cached = m.peers.keys.filter { m.permits(it) }.toSet()
                cachedVersion = m.version
            }
            assertEquals(m.eligible().keys, cached)
        }
        check {
            m.snapshot(
                Tlv.tlv(
                    0x1316,
                    concat(
                        Tlv.tlv(0x1315, Tlv.text(0x03000306, "net") + Tlv.tlv(0x0d000309, id) + Tlv.u32(0x0100030a, 1)),
                        Tlv.tlv(0x1317, peerRecord(5, Ip.parse("26.0.0.5"))),
                        Tlv.tlv(0x1317, peerRecord(6, Ip.parse("26.0.0.6"), state = 0)),
                        Tlv.tlv(0x1318, Tlv.u64(0x020001e1, 5) + Tlv.tlv(0x0d000309, id) + Tlv.u32(0x0100030a, 1)),
                        Tlv.tlv(0x1318, Tlv.u64(0x020001e1, 6) + Tlv.tlv(0x0d000309, id)),
                    ),
                ),
            )
        }
        assertEquals(setOf(5L), cached)
        check { m.changes(Tlv.tlv(0x131f, Tlv.tlv(0x1366, Tlv.u64(0x020001e1, 6) + Tlv.u32(0x010003a0, 1)))) }
        assertEquals(setOf(5L, 6L), cached)
        // A malformed change that fails midway still invalidates the cache.
        check { m.changes(Tlv.tlv(0x131f, Tlv.tlv(0x1366, Tlv.u64(0x020001e1, 6))) + byteArrayOf(1)) }
        check { m.changes(Tlv.tlv(0x131f, Tlv.tlv(0x1318, Tlv.u64(0x020001e1, 6) + Tlv.tlv(0x0d000309, id) + Tlv.u32(0x0100030a, 0)))) }
        assertEquals(setOf(5L), cached)
        check { m.removeMember(id.toHex(), 5) }
        assertEquals(emptySet<Long>(), cached)
        check { m.removeNetwork(id.toHex()) }
        assertEquals(emptySet<Long>(), cached)
    }

    @Test
    fun membershipExcludesOfflinePendingAndSelf() {
        val id = ByteArray(16) { 3 }
        val m = Membership(1)
        m.snapshot(
            Tlv.tlv(
                0x1316,
                concat(
                    Tlv.tlv(0x1315, Tlv.text(0x03000306, "net") + Tlv.tlv(0x0d000309, id) + Tlv.u32(0x0100030a, 1)),
                    Tlv.tlv(0x1317, peerRecord(5, Ip.parse("26.0.0.5"))),
                    Tlv.tlv(0x1317, peerRecord(6, Ip.parse("26.0.0.6"), state = 0)),
                    Tlv.tlv(0x1318, Tlv.u64(0x020001e1, 5) + Tlv.tlv(0x0d000309, id) + Tlv.u32(0x0100030a, 1)),
                    Tlv.tlv(0x1318, Tlv.u64(0x020001e1, 6) + Tlv.tlv(0x0d000309, id)),
                ),
            ),
        )
        assertEquals(setOf(5L), m.eligible().keys)
        // An offline→online state change arrives as a 0x1366 change.
        m.changes(Tlv.tlv(0x131f, Tlv.tlv(0x1366, Tlv.u64(0x020001e1, 6) + Tlv.u32(0x010003a0, 1))))
        assertEquals(setOf(5L, 6L), m.eligible().keys)
        // A member awaiting approval (role 0) is not reachable.
        m.changes(Tlv.tlv(0x131f, Tlv.tlv(0x1318, Tlv.u64(0x020001e1, 6) + Tlv.tlv(0x0d000309, id) + Tlv.u32(0x0100030a, 0))))
        assertEquals(setOf(5L), m.eligible().keys)
        // Removing the member drops the peer entirely.
        m.changes(Tlv.tlv(0x131f, Tlv.tlv(0x131d, Tlv.u64(0x020001e1, 5) + Tlv.tlv(0x0d000309, id))))
        assertNull(m.peers[5])
    }

    @Test
    fun tcpCandidatesSkipInvalidAndRelayParses() {
        val cid = 99L
        val cands = Tlv.u32(Protocol.SERVER_OP, 6) + Tlv.u64(0x020001c1, cid) + Tlv.tlv(
            0x1236,
            concat(
                Tlv.tlv(0x127d, Tlv.text(0x030001c2, "198.51.100.4") + Tlv.u32(0x010001c3, 4000)),
                Tlv.tlv(0x127d, Tlv.text(0x030001c2, "224.0.0.1") + Tlv.u32(0x010001c3, 4000)),
                Tlv.tlv(0x127d, Tlv.text(0x030001c2, "198.51.100.5") + Tlv.u32(0x010001c3, 0)),
            ),
        )
        assertEquals(listOf("198.51.100.4" to 4000), Protocol.tcpCandidates(cands, cid))
        assertThrows(ProtocolException::class.java) { Protocol.tcpCandidates(cands, cid + 1) }
        val relay = Tlv.u32(Protocol.SERVER_OP, 23) + Tlv.u64(0x020001c1, cid) + Tlv.tlv(
            0x123f,
            Tlv.text(0x030001cb, "203.0.113.2") + Tlv.u32(0x010001cc, 17302) + Tlv.tlv(0x090001ca, ByteArray(256)),
        )
        val r = Protocol.relayDetails(relay, cid)
        assertEquals("203.0.113.2", r.host)
        assertEquals(17302, r.port)
    }

    @Test
    fun publicListingCorrelatesRequestId() {
        val data = Tlv.u32(Protocol.SERVER_OP, 45) + Tlv.tlv(
            0x1334,
            concat(
                Tlv.u64(0x02000340, 9), Tlv.u64(0x0200030f, 40),
                Tlv.tlv(0x1333, Tlv.text(0x03000306, "Minecraft") + Tlv.u32(0x01000341, 12)),
            ),
        )
        assertEquals(9, Protocol.listingId(data))
        val listing = Protocol.listing(data, 9)
        assertEquals(listOf("Minecraft" to 12L), listing.networks)
        assertEquals(40, listing.cursor)
        assertThrows(ProtocolException::class.java) { Protocol.listing(data, 10) }
    }

    @Test
    fun framedTransportHandlesFragmentedCoalescedAndTruncatedRecords() {
        val server = ServerSocket(0)
        val writer = thread {
            server.accept().use { s ->
                val out = s.getOutputStream()
                for (b in byteArrayOf(0, 0, 0, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())) {
                    out.write(byteArrayOf(b))
                    out.flush()
                }
                out.write(byteArrayOf(0, 0, 0, 2, 'd'.code.toByte(), 'e'.code.toByte(), 0, 0, 0, 4, 'f'.code.toByte()))
                out.flush()
            }
        }
        val client = Framed.connect("127.0.0.1", server.localPort, 3000, null, null)
        assertArrayEquals("abc".toByteArray(), client.receive(10, now() + 3000))
        assertArrayEquals("de".toByteArray(), client.poll(10, 3000))
        assertThrows(Exception::class.java) { client.receive(10, now() + 3000) }
        writer.join()
        client.close()
        server.close()
    }

    @Test
    fun rendezvousChecksumIsStable() {
        val body = beU64(1) + beU64(2) + beU32(3)
        assertEquals(Protocol.rendezvousChecksum(body), Protocol.rendezvousChecksum(body.copyOf()))
        assertNotEquals(Protocol.rendezvousChecksum(body), Protocol.rendezvousChecksum(beU64(1) + beU64(2) + beU32(4)))
    }

    @Test
    fun roundingTiesToEven() {
        assertEquals(2, roundMilliseconds(2_500_000))
        assertEquals(4, roundMilliseconds(3_500_000))
        assertEquals(3, roundMilliseconds(2_500_001))
    }
}
