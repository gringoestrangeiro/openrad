package com.openrad.mobile.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Peer service negotiation and Ethernet envelopes. Mirrors `tunnel.rs`.
 * Radmin peers exchange Ethernet frames; Android only offers an L3 TUN, so
 * [L3Bridge] translates IPv4 packets to/from frames in software.
 */
object Tunnel {
    const val MTU = 1500
    const val MAX_FRAME = MTU + 14
    private val BROADCAST_MAC = ByteArray(6) { 0xff.toByte() }

    fun syn(rid: Long, mac: ByteArray): ByteArray {
        ensure(mac.size == 6 && mac[0].toInt() and 1 == 0) { "expected a unicast Ethernet MAC" }
        return concat(Tlv.u32(0x01000302L, 8), Tlv.tlv(0x09000304L, mac), Tlv.u64(0x020001e1L, rid))
    }

    class Negotiated(val reply: ByteArray, val mac: ByteArray, val version: Long)

    fun synack(data: ByteArray): Negotiated {
        val f = Tlv.parse(data)
        val status = Tlv.unique(f, 0x02000303L).value
        ensure(status.contentEquals(ByteArray(8))) { "peer service refused: ${status.toHex()}" }
        val version = minOf(Tlv.unique(f, 0x01000302L).u32(), 8L)
        val mac = Tlv.unique(f, 0x09000304L).value
        ensure(version >= 5 && mac.size == 6 && mac[0].toInt() and 1 == 0) { "invalid service version/MAC" }
        return Negotiated(concat(Tlv.u64(0x02000303L, 0), Tlv.u32(0x01000302L, version)), mac, version)
    }

    fun acceptSyn(data: ByteArray, expectedRid: Long, ownMac: ByteArray): Negotiated {
        val f = Tlv.parse(data)
        ensure(Tlv.unique(f, 0x020001e1L).u64() == expectedRid) { "service peer identity mismatch" }
        val version = minOf(Tlv.unique(f, 0x01000302L).u32(), 8L)
        val mac = Tlv.unique(f, 0x09000304L).value
        ensure(version >= 5 && mac.size == 6 && mac[0].toInt() and 1 == 0) { "invalid service version/MAC" }
        return Negotiated(
            concat(Tlv.u64(0x02000303L, 0), Tlv.u32(0x01000302L, version), Tlv.tlv(0x09000304L, ownMac)),
            mac,
            version,
        )
    }

    fun acceptAck(data: ByteArray, version: Long) {
        val f = Tlv.parse(data)
        ensure(Tlv.unique(f, 0x02000303L).value.contentEquals(ByteArray(8)) && Tlv.unique(f, 0x01000302L).u32() == version) {
            "service acknowledgement mismatch"
        }
    }

    fun encode(frame: ByteArray): ByteArray {
        ensure(frame.size in 14..65535) { "invalid Ethernet length" }
        val out = ByteArray(10 + frame.size)
        out[6] = frame.size.toByte()
        out[7] = (frame.size ushr 8).toByte()
        System.arraycopy(frame, 0, out, 10, frame.size)
        return out
    }

    /**
     * [encode] followed by [CChannel.seal] in one buffer: the key-independent
     * plaintext that [CChannel.encryptSealed] turns into the same record as
     * `encrypt(encode(frame))`.
     */
    fun sealEnvelope(frame: ByteArray): ByteArray {
        ensure(frame.size in 14..65535) { "invalid Ethernet length" }
        val length = 10 + frame.size
        val total = CChannel.sealedSize(length)
        val body = ByteArray(total)
        body[6] = frame.size.toByte()
        body[7] = (frame.size ushr 8).toByte()
        System.arraycopy(frame, 0, body, 10, frame.size)
        CChannel.seal(body, ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN), length, total)
        return body
    }

    sealed class Packet {
        class Frames(val frames: List<ByteArray>) : Packet()
        class Keepalive(val sequence: Long, val reply: Boolean) : Packet()
        class Other(val kind: Int) : Packet()
    }

    /** Validates the entire record before releasing any frame. */
    fun decode(data: ByteArray): Packet {
        ensure(data.size >= 2) { "truncated tunnel packet" }
        return when (val kind = readBeU16(data, 0)) {
            0 -> {
                var at = 2
                val frames = ArrayList<ByteArray>()
                while (at < data.size) {
                    ensure(data.size - at >= 22) { "truncated Ethernet envelope" }
                    val flags = readLeU32(data, at)
                    val len = readLeU32(data, at + 4)
                    val start = at + 8
                    ensure(len in 14..65535 && len <= data.size - start) { "invalid Ethernet envelope length" }
                    ensure(flags == 0L) { "unsupported Ethernet offload/compression flags" }
                    frames.add(data.copyOfRange(start, start + len.toInt()))
                    at = start + len.toInt()
                }
                ensure(frames.isNotEmpty()) { "empty Ethernet packet" }
                Packet.Frames(frames)
            }
            3, 7 -> {
                ensure(data.size == 6) { "invalid keepalive length" }
                Packet.Keepalive(readBeU32(data, 2), kind == 7)
            }
            else -> Packet.Other(kind)
        }
    }

    fun keepalive(sequence: Long, reply: Boolean): ByteArray =
        byteArrayOf(0, if (reply) 7 else 3) + beU32(sequence)

    fun checksum(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var total = 0L
        var i = 0
        while (i + 1 < length) {
            total += readBeU16(data, offset + i)
            i += 2
        }
        if (i < length) total += (data[offset + i].toInt() and 0xff) shl 8
        while (total ushr 16 != 0L) total = (total and 0xffff) + (total ushr 16)
        return total.inv().toInt() and 0xffff
    }

    fun arpFrame(sourceMac: ByteArray, sourceIp: Int, targetMac: ByteArray, targetIp: Int, reply: Boolean): ByteArray = concat(
        if (reply) targetMac else BROADCAST_MAC, sourceMac, byteArrayOf(8, 6),
        byteArrayOf(0, 1, 8, 0, 6, 4, 0, if (reply) 2 else 1),
        sourceMac, Ip.bytes(sourceIp), targetMac, Ip.bytes(targetIp),
    )

    /** Gratuitous ARP reply that announces our userspace MAC/VIP. */
    fun gratuitousArp(vip: Int): ByteArray = arpFrame(Protocol.macForIp(vip), vip, BROADCAST_MAC, vip, reply = true)

    class Ipv4(val length: Int, val source: Int, val destination: Int, val headerLength: Int, val protocol: Int)

    /** Parses and validates an IPv4 header in `data[offset, end)`; returns null when invalid. */
    fun ipv4(data: ByteArray, offset: Int = 0, end: Int = data.size): Ipv4? {
        val available = end - offset
        if (available < 20 || (data[offset].toInt() ushr 4) and 15 != 4) return null
        val header = (data[offset].toInt() and 15) * 4
        val size = readBeU16(data, offset + 2)
        if (header < 20 || size < header || size > available) return null
        if (checksum(data, offset, header) != 0) return null
        return Ipv4(size, Ip.read(data, offset + 12), Ip.read(data, offset + 16), header, data[offset + 9].toInt() and 0xff)
    }

    /** Ethernet destination for an IPv4 group address, or null for unicast. */
    fun groupMac(ip: Int, subnetBroadcast: Int = SUBNET_BROADCAST): ByteArray? {
        if (ip == -1 || ip == subnetBroadcast) return BROADCAST_MAC
        if (Ip.isMulticast(ip)) {
            val b = Ip.bytes(ip)
            return byteArrayOf(1, 0, 0x5e, (b[1].toInt() and 127).toByte(), b[2], b[3])
        }
        return null
    }

    val SUBNET_BROADCAST: Int = Ip.parse("26.255.255.255")

    /**
     * Rewrites the destination of an unfragmented IPv4/UDP packet in place, fixing the IP
     * header checksum and the UDP checksum (left at 0 when the sender disabled it).
     * Returns false and leaves the packet untouched for anything else.
     */
    fun rewriteUdpDestination(packet: ByteArray, destination: Int): Boolean {
        val ip = ipv4(packet) ?: return false
        if (ip.protocol != 17 || readBeU16(packet, 6) and 0x3fff != 0) return false
        val udp = ip.headerLength
        if (ip.length - udp < 8 || readBeU16(packet, udp + 4) != ip.length - udp) return false
        System.arraycopy(Ip.bytes(destination), 0, packet, 16, 4)
        packet[10] = 0
        packet[11] = 0
        val headerSum = checksum(packet, 0, ip.headerLength)
        packet[10] = (headerSum ushr 8).toByte()
        packet[11] = headerSum.toByte()
        if (readBeU16(packet, udp + 6) != 0) {
            packet[udp + 6] = 0
            packet[udp + 7] = 0
            val length = ip.length - udp
            val pseudo = ByteArray(12 + length)
            System.arraycopy(packet, 12, pseudo, 0, 8)
            pseudo[9] = 17
            pseudo[10] = (length ushr 8).toByte()
            pseudo[11] = length.toByte()
            System.arraycopy(packet, udp, pseudo, 12, length)
            val sum = checksum(pseudo).let { if (it == 0) 0xffff else it }
            packet[udp + 6] = (sum ushr 8).toByte()
            packet[udp + 7] = sum.toByte()
        }
        return true
    }

    private fun sameMac(frame: ByteArray, offset: Int, mac: ByteArray): Boolean {
        for (i in 0 until 6) if (frame[offset + i] != mac[i]) return false
        return true
    }

    /**
     * Validate routing and sources, allowing arbitrary ARP reply senders (as the
     * references do). Group destinations reach every eligible connected peer.
     */
    fun validFrame(frame: ByteArray, sourceIp: Int, sourceMac: ByteArray, targetIp: Int? = null, targetMac: ByteArray? = null): Boolean {
        if (frame.size !in 14..MAX_FRAME) return false
        val type = readBeU16(frame, 12)
        val arpReply = frame.size >= 42 && type == 0x0806 &&
            frame.copyOfRange(14, 22).contentEquals(byteArrayOf(0, 1, 8, 0, 6, 4, 0, 2))
        if (!arpReply && !sameMac(frame, 6, sourceMac)) return false
        val multicastDst = frame[0].toInt() and 1 == 1
        return when (type) {
            0x0800 -> {
                val ip = ipv4(frame, 14) ?: return false
                if (ip.source != sourceIp) return false
                val group = groupMac(ip.destination)
                when {
                    group != null -> sameMac(frame, 0, group)
                    multicastDst -> true
                    else -> targetIp == null || (ip.destination == targetIp && sameMac(frame, 0, targetMac!!))
                }
            }
            0x0806 -> {
                if (frame.size < 42 || !frame.copyOfRange(14, 20).contentEquals(byteArrayOf(0, 1, 8, 0, 6, 4))) return false
                val op = readBeU16(frame, 20)
                if (op != 1 && op != 2) return false
                if (!arpReply && (Ip.read(frame, 28) != sourceIp || !sameMac(frame, 22, sourceMac))) return false
                if (multicastDst) return true
                targetIp == null || (Ip.read(frame, 38) == targetIp && sameMac(frame, 0, targetMac!!))
            }
            else -> targetMac == null || sameMac(frame, 0, targetMac) || multicastDst
        }
    }
}

/** A connected, authenticated peer as seen by the L3 bridge. */
interface BridgePeer {
    val rid: Long
    val vip: Int
    val mac: ByteArray
    fun enqueue(frame: ByteArray): Boolean

    /** Broadcast entry point: the same [OutFrame] reaches every peer of the fan-out. */
    fun enqueue(frame: OutFrame): Boolean = enqueue(frame.frame)
}

/**
 * An outgoing Ethernet frame, never mutated. A [shared] frame (broadcast fan-out)
 * builds its sealed envelope ([Tunnel.sealEnvelope]) once on first use, so each
 * peer channel only runs its own AES chain; a single-peer frame is sealed in the
 * channel's reused buffer instead ([PeerChannel.sendFrame]).
 */
class OutFrame(val frame: ByteArray, val shared: Boolean = false) {
    @Volatile private var body: ByteArray? = null

    /** Benign race: concurrent writers may both seal; the bytes are identical. */
    fun sealed(): ByteArray = body ?: Tunnel.sealEnvelope(frame).also { body = it }
}

/**
 * Software IP→MAC/RID translation for Android's L3 TUN.
 *
 * Outbound: an IPv4 packet from the TUN is matched to the connected peer whose
 * VIP is the destination (RID → channel MAC), or fanned out to every connected
 * peer for 26.255.255.255 / 255.255.255.255 / multicast. Inbound: validated
 * frames lose their Ethernet header and go to the TUN; ARP requests for our
 * VIP are answered here because the kernel never sees Ethernet.
 */
class L3Bridge(
    private val ownVip: () -> Int,
    private val peers: () -> Collection<BridgePeer>,
    private val outgoingBroadcasts: () -> Boolean = { true },
) {
    enum class Result { SENT, NO_ROUTE, INVALID, FILTERED, QUEUE_FULL }

    private class OwnMac(val vip: Int, val mac: ByteArray)

    @Volatile private var ownMacCache = OwnMac(0, Protocol.macForIp(0))

    private fun ownMac(vip: Int): ByteArray {
        val cached = ownMacCache
        if (cached.vip == vip) return cached.mac
        return Protocol.macForIp(vip).also { ownMacCache = OwnMac(vip, it) }
    }

    /** [packet] may be a reused TUN buffer: only `packet[0, length)` is read, and frames are copies. */
    fun outbound(packet: ByteArray, length: Int = packet.size): Result {
        if (length > Tunnel.MTU) return Result.INVALID
        // A length beyond the buffer is zero-extended, as a copy would be.
        val data = if (length > packet.size) packet.copyOf(length) else packet
        val ip = Tunnel.ipv4(data, 0, length) ?: return Result.INVALID
        val vip = ownVip()
        if (ip.source != vip || ip.length != length) return Result.INVALID
        val group = Tunnel.groupMac(ip.destination)
        val ownMac = ownMac(vip)
        if (group != null) {
            if (!outgoingBroadcasts()) return Result.FILTERED
            var queued = 0
            val frame = OutFrame(frame(group, ownMac, data, length), shared = true)
            for (peer in peers()) if (peer.enqueue(frame)) queued++
            return if (queued > 0) Result.SENT else Result.NO_ROUTE
        }
        val peer = peers().firstOrNull { it.vip == ip.destination } ?: return Result.NO_ROUTE
        return if (peer.enqueue(frame(peer.mac, ownMac, data, length))) Result.SENT else Result.QUEUE_FULL
    }

    sealed class Inbound {
        class Deliver(val packet: ByteArray) : Inbound()
        class Reply(val frame: ByteArray) : Inbound()
        data object Drop : Inbound()
    }

    /** Frames must already pass [Tunnel.validFrame] for the sending channel. */
    fun inbound(peer: BridgePeer, frame: ByteArray): Inbound {
        val vip = ownVip()
        return when (readBeU16(frame, 12)) {
            0x0800 -> {
                val ip = Tunnel.ipv4(frame, 14) ?: return Inbound.Drop
                val packet = frame.copyOfRange(14, 14 + ip.length)
                // Linux only delivers multicast on an interface where the app joined the group (usually
                // Wi-Fi, not tun0), so LAN discovery (Minecraft Java 224.0.2.60:4445,
                // Bedrock 255.255.255.255:19132, 26.255.255.255) arrives as UDP unicast to our VIP.
                // Sockets bound to 0.0.0.0:<port> receive it exactly like the original broadcast.
                if (Tunnel.groupMac(ip.destination) != null && vip != 0) Tunnel.rewriteUdpDestination(packet, vip)
                Inbound.Deliver(packet)
            }
            0x0806 -> {
                if (frame.size >= 42 && readBeU16(frame, 20) == 1 && Ip.read(frame, 38) == vip) {
                    Inbound.Reply(Tunnel.arpFrame(Protocol.macForIp(vip), vip, peer.mac, peer.vip, reply = true))
                } else {
                    Inbound.Drop
                }
            }
            else -> Inbound.Drop
        }
    }

    companion object {
        fun frame(destination: ByteArray, source: ByteArray, ip: ByteArray, length: Int = ip.size): ByteArray {
            val out = ByteArray(14 + length)
            System.arraycopy(destination, 0, out, 0, 6)
            System.arraycopy(source, 0, out, 6, 6)
            out[12] = 8
            out[13] = 0
            System.arraycopy(ip, 0, out, 14, length)
            return out
        }
    }
}
