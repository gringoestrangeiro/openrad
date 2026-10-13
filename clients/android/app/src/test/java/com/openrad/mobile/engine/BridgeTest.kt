package com.openrad.mobile.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Software IP→MAC/RID translation used instead of an Android TAP. */
class BridgeTest {
    private val own = Ip.parse("26.0.0.1")

    private class FakePeer(override val rid: Long, override val vip: Int, override val mac: ByteArray) : BridgePeer {
        val sent = ArrayList<ByteArray>()
        var full = false
        override fun enqueue(frame: ByteArray): Boolean = !full && sent.add(frame)
    }

    private val a = FakePeer(10, Ip.parse("26.0.0.2"), byteArrayOf(2, 0x1a, 26, 0, 0, 2))
    private val b = FakePeer(11, Ip.parse("26.0.0.3"), byteArrayOf(0x0a, 0x11, 0x22, 0x33, 0x44, 0x55))

    private fun ipv4(src: Int, dst: Int, payload: ByteArray = ByteArray(8) { it.toByte() }, protocol: Int = 17): ByteArray {
        val header = byteArrayOf(0x45, 0) + beU32((20 + payload.size).toLong()).copyOfRange(2, 4) +
            byteArrayOf(0, 0, 0, 0, 64, protocol.toByte(), 0, 0) + Ip.bytes(src) + Ip.bytes(dst)
        val c = Tunnel.checksum(header)
        header[10] = (c ushr 8).toByte()
        header[11] = c.toByte()
        return header + payload
    }

    private fun bridge(broadcasts: Boolean = true) = L3Bridge({ own }, { listOf(a, b) }, { broadcasts })

    @Test
    fun unicastUsesTheDestinationPeersChannelMac() {
        val packet = ipv4(own, b.vip)
        assertEquals(L3Bridge.Result.SENT, bridge().outbound(packet))
        assertTrue(a.sent.isEmpty())
        val frame = b.sent.single()
        assertArrayEquals(b.mac, frame.copyOfRange(0, 6))
        assertArrayEquals(Protocol.macForIp(own), frame.copyOfRange(6, 12))
        assertEquals(0x0800, readBeU16(frame, 12))
        assertArrayEquals(packet, frame.copyOfRange(14, frame.size))
        assertTrue(Tunnel.validFrame(frame, own, Protocol.macForIp(own), b.vip, b.mac))
    }

    @Test
    fun broadcastAndMulticastFanOutUnlessDisabled() {
        assertEquals(L3Bridge.Result.SENT, bridge().outbound(ipv4(own, Ip.parse("26.255.255.255"))))
        assertEquals(1, a.sent.size)
        assertEquals(1, b.sent.size)
        assertTrue(a.sent[0].copyOfRange(0, 6).all { it == 0xff.toByte() })
        bridge().outbound(ipv4(own, Ip.parse("239.255.255.250")))
        assertArrayEquals(byteArrayOf(1, 0, 0x5e, 0x7f, 0xff.toByte(), 0xfa.toByte()), a.sent[1].copyOfRange(0, 6))
        assertEquals(L3Bridge.Result.FILTERED, bridge(broadcasts = false).outbound(ipv4(own, Ip.parse("255.255.255.255"))))
    }

    @Test
    fun rejectsForeignSourceBadChecksumUnknownPeerAndFullQueue() {
        assertEquals(L3Bridge.Result.INVALID, bridge().outbound(ipv4(Ip.parse("26.9.9.9"), a.vip)))
        val corrupt = ipv4(own, a.vip).also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertEquals(L3Bridge.Result.INVALID, bridge().outbound(corrupt))
        assertEquals(L3Bridge.Result.NO_ROUTE, bridge().outbound(ipv4(own, Ip.parse("26.0.0.99"))))
        a.full = true
        assertEquals(L3Bridge.Result.QUEUE_FULL, bridge().outbound(ipv4(own, a.vip)))
        assertEquals(L3Bridge.Result.INVALID, bridge().outbound(ByteArray(1600), 1600))
    }

    @Test
    fun inboundStripsEthernetAndAnswersArpForOurVip() {
        val packet = ipv4(a.vip, own)
        val frame = L3Bridge.frame(Protocol.macForIp(own), a.mac, packet)
        assertTrue(Tunnel.validFrame(frame, a.vip, a.mac, own, Protocol.macForIp(own)))
        val delivered = bridge().inbound(a, frame) as L3Bridge.Inbound.Deliver
        assertArrayEquals(packet, delivered.packet)

        val request = Tunnel.arpFrame(a.mac, a.vip, ByteArray(6), own, reply = false)
        assertTrue(Tunnel.validFrame(request, a.vip, a.mac, own, Protocol.macForIp(own)))
        val reply = (bridge().inbound(a, request) as L3Bridge.Inbound.Reply).frame
        assertArrayEquals(a.mac, reply.copyOfRange(0, 6))
        assertEquals(2, readBeU16(reply, 20))
        assertEquals(own, Ip.read(reply, 28))
        assertArrayEquals(Protocol.macForIp(own), reply.copyOfRange(22, 28))

        val otherTarget = Tunnel.arpFrame(a.mac, a.vip, ByteArray(6), b.vip, reply = false)
        assertEquals(L3Bridge.Inbound.Drop, bridge().inbound(a, otherTarget))
    }

    @Test
    fun validFrameRejectsSpoofedSourcesAndMisroutedUnicast() {
        val spoofed = L3Bridge.frame(Protocol.macForIp(own), a.mac, ipv4(b.vip, own))
        assertFalse(Tunnel.validFrame(spoofed, a.vip, a.mac, own, Protocol.macForIp(own)))
        val wrongMac = L3Bridge.frame(Protocol.macForIp(own), b.mac, ipv4(a.vip, own))
        assertFalse(Tunnel.validFrame(wrongMac, a.vip, a.mac, own, Protocol.macForIp(own)))
        val otherDst = L3Bridge.frame(Protocol.macForIp(own), a.mac, ipv4(a.vip, b.vip))
        assertFalse(Tunnel.validFrame(otherDst, a.vip, a.mac, own, Protocol.macForIp(own)))
        val broadcast = L3Bridge.frame(ByteArray(6) { 0xff.toByte() }, a.mac, ipv4(a.vip, Ip.parse("26.255.255.255")))
        assertTrue(Tunnel.validFrame(broadcast, a.vip, a.mac, own, Protocol.macForIp(own)))
        // ARP replies may carry arbitrary sender fields (as in the references).
        val reply = Tunnel.arpFrame(b.mac, b.vip, Protocol.macForIp(own), own, reply = true)
        assertTrue(Tunnel.validFrame(reply, a.vip, a.mac, own, Protocol.macForIp(own)))
    }

    @Test
    fun gratuitousArpAnnouncesOwnMac() {
        val frame = Tunnel.gratuitousArp(own)
        assertTrue(Tunnel.validFrame(frame, own, Protocol.macForIp(own)))
        assertArrayEquals(byteArrayOf(2, 0x1a, 26, 0, 0, 1), frame.copyOfRange(6, 12))
    }

    private fun udpWithChecksum(src: Int, dst: Int, sport: Int, dport: Int, data: ByteArray, withChecksum: Boolean = true): ByteArray {
        val udp = byteArrayOf((sport ushr 8).toByte(), sport.toByte(), (dport ushr 8).toByte(), dport.toByte()) +
            beU32(((8L + data.size) shl 16)) + data
        if (withChecksum) {
            val c = Tunnel.checksum(Ip.bytes(src) + Ip.bytes(dst) + byteArrayOf(0, 17, 0, udp.size.toByte()) + udp)
            udp[6] = (c ushr 8).toByte()
            udp[7] = c.toByte()
        }
        return ipv4(src, dst, udp, protocol = 17)
    }

    private fun udpChecksumValid(packet: ByteArray): Boolean {
        val ip = Tunnel.ipv4(packet)!!
        val udp = packet.copyOfRange(ip.headerLength, ip.length)
        return Tunnel.checksum(packet.copyOfRange(12, 20) + byteArrayOf(0, 17, 0, udp.size.toByte()) + udp) == 0
    }

    @Test
    fun inboundLanDiscoveryIsDeliveredAsUnicastToOurVip() {
        val announce = "[MOTD]Mundo do Server[/MOTD][AD]25565[/AD]".toByteArray()
        for (group in listOf("224.0.2.60", "255.255.255.255", "26.255.255.255")) {
            val packet = udpWithChecksum(a.vip, Ip.parse(group), 40000, 4445, announce)
            val dstMac = Tunnel.groupMac(Ip.parse(group))!!
            val frame = L3Bridge.frame(dstMac, a.mac, packet)
            assertTrue(Tunnel.validFrame(frame, a.vip, a.mac, own, Protocol.macForIp(own)))
            val delivered = (bridge().inbound(a, frame) as L3Bridge.Inbound.Deliver).packet
            val ip = Tunnel.ipv4(delivered)!!
            assertEquals(own, ip.destination)
            assertEquals(a.vip, ip.source)
            assertTrue(udpChecksumValid(delivered))
            assertEquals(4445, readBeU16(delivered, ip.headerLength + 2))
            assertArrayEquals(announce, delivered.copyOfRange(ip.headerLength + 8, ip.length))
        }
        // A zero UDP checksum (disabled) stays zero; unicast traffic is untouched.
        val noSum = udpWithChecksum(a.vip, Ip.parse("255.255.255.255"), 1, 19132, ByteArray(4), withChecksum = false)
        val out = (bridge().inbound(a, L3Bridge.frame(ByteArray(6) { 0xff.toByte() }, a.mac, noSum)) as L3Bridge.Inbound.Deliver).packet
        assertEquals(0, readBeU16(out, 26))
        assertEquals(own, Tunnel.ipv4(out)!!.destination)
        val unicast = udpWithChecksum(a.vip, own, 1, 2, ByteArray(4))
        assertArrayEquals(unicast, (bridge().inbound(a, L3Bridge.frame(Protocol.macForIp(own), a.mac, unicast)) as L3Bridge.Inbound.Deliver).packet)
    }
}
