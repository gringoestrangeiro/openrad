package com.openrad.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Golden SHA-256 digests of packet-path output, recorded from the 1.5.0 engine
 * before the packet-path optimisation. Any change to ciphertext chaining,
 * trailers, padding, envelopes, Ethernet framing, checksums or validation
 * verdicts changes a digest, so optimisations must keep the wire byte-identical.
 */
class WireIdentityTest {
    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun pattern(size: Int, seed: Int): ByteArray = ByteArray(size) { (it * 31 + seed * 7 + (it ushr 3)).toByte() }

    private fun ipv4Packet(src: Int, dst: Int, protocol: Int, payloadSize: Int, seed: Int): ByteArray {
        val total = 20 + payloadSize
        val header = byteArrayOf(0x45, 0, (total ushr 8).toByte(), total.toByte(), 0, seed.toByte(), 0, 0, 64, protocol.toByte(), 0, 0) +
            Ip.bytes(src) + Ip.bytes(dst)
        val c = Tunnel.checksum(header)
        header[10] = (c ushr 8).toByte()
        header[11] = c.toByte()
        return header + pattern(payloadSize, seed)
    }

    private fun udpPacket(src: Int, dst: Int, payloadSize: Int, seed: Int, checksum: Boolean): ByteArray {
        val ip = ipv4Packet(src, dst, 17, 8 + payloadSize, seed)
        val udpLen = 8 + payloadSize
        ip[20] = 0x10; ip[21] = seed.toByte(); ip[22] = 0x4d; ip[23] = 0x3c
        ip[24] = (udpLen ushr 8).toByte(); ip[25] = udpLen.toByte()
        ip[26] = if (checksum) 0x12 else 0; ip[27] = if (checksum) 0x34 else 0
        return ip
    }

    private fun chainedStream(streamingTx: Boolean, streamingRx: Boolean): String {
        val tx = CChannel(ByteArray(32) { (it * 5 + 3).toByte() }, streamingTx)
        val rx = CChannel(ByteArray(32) { (it * 5 + 3).toByte() }, streamingRx)
        val wire = ByteArrayOutputStream()
        var n = 0
        for (round in 0 until 2) {
            for (size in 1..1600) {
                if (round == 1 && size == 800) {
                    val next = ByteArray(32) { (it * 11 + 9).toByte() }
                    tx.rekey(next)
                    rx.rekey(next)
                }
                val plain = pattern(size, n++)
                val ct = tx.encrypt(plain)
                wire.write(ct)
                assertTrue(rx.decrypt(ct).contentEquals(plain))
            }
        }
        return sha256(wire.toByteArray())
    }

    @Test
    fun cchannelChainedStreamIsByteIdentical() {
        // The JVM provider keeps the CBC chain across update(), so the fast path is what runs.
        assertTrue(CChannel.STREAMING)
        assertEquals(CCHANNEL_STREAM, chainedStream(streamingTx = true, streamingRx = true))
        // Per-record re-initialisation (fallback providers) is identical and interoperates.
        assertEquals(CCHANNEL_STREAM, chainedStream(streamingTx = false, streamingRx = false))
        assertEquals(CCHANNEL_STREAM, chainedStream(streamingTx = true, streamingRx = false))
        assertEquals(CCHANNEL_STREAM, chainedStream(streamingTx = false, streamingRx = true))
    }

    @Test
    fun foldedMd4WordEqualsFoldedDigest() {
        val words = listOf(0L, 1L, -1L, Long.MIN_VALUE, 0x0123456789abcdefL) + (0 until 2000).map { it * -0x61c8864680b583ebL }
        for (w in words) {
            val d = Md4.digest(ByteArray(8) { (w ushr (8 * it)).toByte() })
            var expected = 0L
            for (i in 7 downTo 0) expected = (expected shl 8) or (((d[i].toInt() + d[i + 8].toInt()) and 0xff).toLong())
            assertEquals(expected, Md4.foldedWord(w))
        }
    }

    @Test
    fun cchannelMatchesTheLegacyImplementationOnArbitraryRecords() {
        val random = java.util.Random(0x0dd5eed)
        val sizes = (1..64).toList() + listOf(2030, 2039, 2040, 2048, 2049, 65_536, 65_600, 65_601, 200_000) +
            List(400) { 1 + random.nextInt(1600) }
        for (streaming in listOf(true, false)) {
            var accepted = 0
            val key = ByteArray(32).also { random.nextBytes(it) }
            val tx = CChannel(key, streaming)
            val rx = CChannel(key, streaming)
            val legacyTx = LegacyCChannel(key)
            val legacyRx = LegacyCChannel(key)
            for ((i, size) in sizes.withIndex()) {
                if (i % 97 == 96) {
                    val next = ByteArray(32).also { random.nextBytes(it) }
                    tx.rekey(next); rx.rekey(next); legacyTx.rekey(next); legacyRx.rekey(next)
                }
                val plain = ByteArray(size).also { random.nextBytes(it) }
                val ct = tx.encrypt(plain)
                assertTrue("size $size", ct.contentEquals(legacyTx.encrypt(plain)))
                // Some records arrive corrupted. Both implementations must agree on every
                // verdict and plaintext, including the IV each rejected record consumes.
                val corrupt = i % 13 == 5
                val received = if (!corrupt) ct else ct.copyOf().also { it[random.nextInt(it.size)] = (it[0].toInt() xor 0x5a).toByte() }
                val newResult = runCatching { rx.decrypt(received) }
                val oldResult = runCatching { legacyRx.decrypt(received) }
                assertEquals("size $size", oldResult.isSuccess, newResult.isSuccess)
                if (oldResult.isSuccess) {
                    assertTrue(oldResult.getOrThrow().contentEquals(newResult.getOrThrow()))
                    accepted++
                } else {
                    assertTrue(newResult.exceptionOrNull() is ProtocolException)
                }
            }
            assertTrue(accepted > sizes.size * 3 / 4)
        }
    }

    /** 1.5.0 `Tunnel.encode`. */
    private fun legacyEncode(frame: ByteArray): ByteArray {
        val out = ByteArray(10 + frame.size)
        System.arraycopy(leU32(frame.size.toLong()), 0, out, 6, 4)
        System.arraycopy(frame, 0, out, 10, frame.size)
        return out
    }

    @Test
    fun sharedSealedEnvelopesEncryptLikeTheLegacyEnvelopePath() {
        val random = java.util.Random(0x5ea1ed)
        for (streaming in listOf(true, false)) {
            val key = ByteArray(32).also { random.nextBytes(it) }
            // Two channels with different keys share every sealed broadcast body.
            val channels = listOf(CChannel(key, streaming), CChannel(key.reversedArray(), streaming))
            val legacy = listOf(LegacyCChannel(key), LegacyCChannel(key.reversedArray()))
            val receivers = listOf(CChannel(key, streaming), CChannel(key.reversedArray(), streaming))
            val sizes = (14..80).toList() + listOf(1514, 9000, 65535) + List(300) { 14 + random.nextInt(1501) }
            for ((i, size) in sizes.withIndex()) {
                val frame = ByteArray(size).also { random.nextBytes(it) }
                val shared = OutFrame(frame, shared = true)
                assertTrue(Tunnel.sealEnvelope(frame).size == CChannel.sealedSize(10 + size))
                for (c in channels.indices) {
                    // Interleave the plain API so the CBC chain is shared between both paths.
                    val ct = if (i % 3 == 0) channels[c].encrypt(Tunnel.encode(frame)) else channels[c].encryptSealed(shared.sealed())
                    assertTrue("size $size", ct.contentEquals(legacy[c].encrypt(legacyEncode(frame))))
                    val packet = Tunnel.decode(receivers[c].decrypt(ct)) as Tunnel.Packet.Frames
                    assertTrue(packet.frames.single().contentEquals(frame))
                }
            }
        }
        for (bad in listOf(ByteArray(0), ByteArray(16), ByteArray(17), ByteArray(32) { 30 })) {
            try {
                CChannel(ByteArray(32)).encryptSealed(bad)
                fail("unsealed body accepted")
            } catch (_: ProtocolException) {
            }
        }
    }

    @Test
    fun tamperedRecordIsRejectedAfterConsumingItsIv() {
        for (streaming in listOf(true, false)) tamperCase(streaming)
    }

    private fun tamperCase(streaming: Boolean) {
        val tx = CChannel(ByteArray(32) { it.toByte() }, streaming)
        val rx = CChannel(ByteArray(32) { it.toByte() }, streaming)
        val first = tx.encrypt(pattern(500, 1))
        val second = tx.encrypt(pattern(60, 2))
        first[0] = (first[0].toInt() xor 1).toByte()
        try {
            rx.decrypt(first)
            fail("tampered record accepted")
        } catch (_: ProtocolException) {
        }
        assertTrue(rx.decrypt(second).contentEquals(pattern(60, 2)))
        for (bad in listOf(ByteArray(0), ByteArray(15), ByteArray(33))) {
            try {
                rx.decrypt(bad)
                fail("partial ciphertext accepted")
            } catch (_: ProtocolException) {
            }
        }
    }

    private fun framedStream(batched: Boolean): String {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val raw = ByteArrayOutputStream()
        val reader = thread {
            server.accept().use { s ->
                val buf = ByteArray(8192)
                while (true) {
                    val r = s.getInputStream().read(buf)
                    if (r < 0) break
                    raw.write(buf, 0, r)
                }
            }
        }
        val own = Ip.parse("26.0.0.2")
        val peer = Ip.parse("26.0.0.3")
        val stream = Framed.connect("127.0.0.1", server.localPort, 5000, null, null)
        val tx = CChannel(ByteArray(32) { (it * 3).toByte() })
        val pending = ArrayList<ByteArray>()
        for (i in 0 until 200) {
            val frame = L3Bridge.frame(Protocol.macForIp(peer), Protocol.macForIp(own), ipv4Packet(own, peer, 17, i * 7 % 1480, i))
            if (batched) {
                // Engine writeLoop on TCP: shared sealed envelopes, gathered writes of up to 32 records.
                pending.add(tx.encryptSealed(OutFrame(frame, shared = true).sealed()))
                pending.add(tx.encrypt(Tunnel.keepalive(i.toLong() * 977, i % 2 == 0)))
                if (pending.size >= 32 || i == 199) {
                    stream.sendAll(pending)
                    pending.clear()
                }
            } else {
                stream.send(tx.encrypt(Tunnel.encode(frame)))
                stream.send(tx.encrypt(Tunnel.keepalive(i.toLong() * 977, i % 2 == 0)))
            }
        }
        stream.close()
        reader.join(10_000)
        server.close()
        return sha256(raw.toByteArray())
    }

    @Test
    fun framedRecordsOnTheSocketAreByteIdentical() {
        assertEquals(FRAMED_STREAM, framedStream(batched = false))
        assertEquals(FRAMED_STREAM, framedStream(batched = true))
    }

    @Test
    fun bridgeTunnelAndChecksumOutputsAreByteIdentical() {
        val own = Ip.parse("26.0.0.100")
        val out = ByteArrayOutputStream()
        class Peer(override val rid: Long, override val vip: Int) : BridgePeer {
            override val mac = Protocol.macForIp(vip)
            override fun enqueue(frame: ByteArray): Boolean {
                out.write(rid.toInt())
                out.write(frame)
                return true
            }
        }
        val peers = (1L..61L).map { Peer(it, Ip.parse("26.0.1.$it")) }
        val bridge = L3Bridge({ own }, { peers })
        val padded = ByteArray(1600)
        for (i in 0 until 120) {
            val size = i * 13 % 1460
            val dst = when (i % 4) {
                0 -> peers[i % 61].vip
                1 -> Tunnel.SUBNET_BROADCAST
                2 -> Ip.parse("224.0.2.60")
                else -> Ip.parse("26.0.9.9")
            }
            val packet = ipv4Packet(own, dst, 17, size, i)
            out.write(bridge.outbound(packet).ordinal)
            System.arraycopy(packet, 0, padded, 0, packet.size)
            out.write(bridge.outbound(padded, packet.size).ordinal)
            val bad = packet.copyOf().also { it[15] = (it[15].toInt() xor 1).toByte() }
            out.write(bridge.outbound(bad).ordinal)
            out.write(bridge.outbound(ipv4Packet(Ip.parse("26.0.0.7"), dst, 17, size, i)).ordinal)
        }
        out.write(bridge.outbound(ByteArray(1501)).ordinal)

        for (i in 0 until 300) {
            val data = pattern(i * 5 + 1, i)
            val c = Tunnel.checksum(data, i % 3, data.size - i % 3)
            out.write(c ushr 8)
            out.write(c)
            Tunnel.ipv4(data)?.let { out.write(it.length); out.write(it.headerLength) } ?: out.write(255)
        }

        val sender = peers[4]
        for (i in 0 until 200) {
            val udp = udpPacket(sender.vip, if (i % 3 == 0) Tunnel.SUBNET_BROADCAST else own, i * 11 % 1400, i, i % 2 == 0)
            val frame = L3Bridge.frame(if (i % 3 == 0) ByteArray(6) { 0xff.toByte() } else Protocol.macForIp(own), sender.mac, udp)
            val wire = Tunnel.encode(frame)
            out.write(wire)
            val record = wire + (if (i % 5 == 0) wire.copyOfRange(2, wire.size) else ByteArray(0))
            when (val p = Tunnel.decode(record)) {
                is Tunnel.Packet.Frames -> for (f in p.frames) {
                    out.write(f)
                    val ok = Tunnel.validFrame(f, sender.vip, sender.mac, own, Protocol.macForIp(own))
                    out.write(if (ok) 1 else 0)
                    out.write(if (Tunnel.validFrame(f, own, sender.mac, own, Protocol.macForIp(own))) 1 else 0)
                    when (val r = bridge.inbound(sender, f)) {
                        is L3Bridge.Inbound.Deliver -> out.write(r.packet)
                        is L3Bridge.Inbound.Reply -> out.write(r.frame)
                        L3Bridge.Inbound.Drop -> out.write(0xdd)
                    }
                }
                else -> out.write(0xee)
            }
            val copy = udp.copyOf()
            out.write(if (Tunnel.rewriteUdpDestination(copy, own)) 1 else 0)
            out.write(copy)
        }
        val arpRequest = Tunnel.arpFrame(sender.mac, sender.vip, ByteArray(6), own, reply = false)
        out.write(if (Tunnel.validFrame(arpRequest, sender.vip, sender.mac, own, Protocol.macForIp(own))) 1 else 0)
        (bridge.inbound(sender, arpRequest) as L3Bridge.Inbound.Reply).let { out.write(it.frame) }
        out.write(Tunnel.gratuitousArp(own))
        for (seq in listOf(0L, 1L, 0xffffffffL)) {
            out.write(Tunnel.keepalive(seq, true))
            val k = Tunnel.decode(Tunnel.keepalive(seq, false)) as Tunnel.Packet.Keepalive
            out.write(beU32(k.sequence))
        }
        for (bad in listOf(byteArrayOf(0), byteArrayOf(0, 0), byteArrayOf(0, 0) + ByteArray(21), byteArrayOf(0, 0, 0, 0, 0, 1) + ByteArray(30))) {
            try {
                Tunnel.decode(bad)
                out.write(1)
            } catch (_: ProtocolException) {
                out.write(0)
            }
        }
        assertEquals(BRIDGE_OUTPUT, sha256(out.toByteArray()))
    }

    companion object {
        const val CCHANNEL_STREAM = "ac1481cfdc63e67d6c64843c012bbb0186c4cb799132e84a12aac9609540aee7"
        const val FRAMED_STREAM = "79ee619ff70a207f425efdf46078af396e0db0ddef1773a46a7703174c26b077"
        const val BRIDGE_OUTPUT = "385f5cfbcb7fcb9243ca06e226265d7e8f44ccbf7e2068a7316bd3c2ced19d1f"
    }
}
