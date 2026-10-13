package com.openrad.mobile.engine

import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.BitSet
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean

/** A UDP socket and its public endpoint agreed by two UES hosts. */
typealias MappedSocket = Pair<UdpSocket, InetSocketAddress>

/**
 * CRC used by the TCP rendezvous and ENET datagrams (`session.rs`): generator
 * 0x04c11db7, MSB first, each byte fed into the low byte after shifting.
 */
object Crc {
    private val TABLE = IntArray(256) { index ->
        var value = index shl 24
        repeat(8) { value = (value shl 1) xor if (value < 0) 0x04c11db7 else 0 }
        value
    }
    private val ZERO_PREFIX = ByteArray(4)

    fun update(crc: Int, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var c = crc
        for (i in offset until offset + length) c = TABLE[c ushr 24] xor ((c shl 8) or (data[i].toInt() and 0xff))
        return c
    }

    /** `session.rs::checksum_slices`: one checksum over two concatenated slices. */
    fun slices(first: ByteArray, second: ByteArray): Int = update(update(-1, first), second).inv()

    /** `session.rs::enet_checksum`: the first four (checksum) bytes count as zero. */
    fun enet(data: ByteArray, length: Int = data.size): Int {
        var c = update(-1, ZERO_PREFIX)
        if (length > 4) c = update(c, data, 4, length - 4)
        return c.inv()
    }
}

/**
 * UDP rendezvous helpers and limits. Mirrors `udp.rs`. Socket reachability is
 * never authentication: the caller still runs peer SH over the channel.
 */
object Udp {
    /** Sequence span a sender may keep unacknowledged, counted from the oldest pending command. */
    const val SEND_WINDOW = 256
    /** [SEND_WINDOW] plus one maximal fragmented message. */
    const val RECEIVE_WINDOW = 512
    /** Stop draining the socket while this many messages await the caller. */
    const val READY_HIGH_WATER = 128
    const val RETRY_INTERVAL_MS = 400L
    /** A datagram the kernel could not accept yet is retried soon, not after a full interval. */
    const val REFUSED_RETRY_MS = 10L
    const val LIVENESS_MS = 35_000L
    const val MAX_TRIES = 20
    const val MAX_CANDIDATES = 32
    /** UES hosts answer on 17301 plus a random offset below 99 (`udp.rs::discover_mapping`). */
    const val UES_BASE_PORT = 17301

    fun punch(nonce: Int, remotePort: Int, localPort: Int, kind: Char): ByteArray = byteArrayOf(
        (nonce ushr 8).toByte(), nonce.toByte(),
        (remotePort ushr 8).toByte(), remotePort.toByte(),
        (localPort ushr 8).toByte(), localPort.toByte(),
        kind.code.toByte(),
    )

    fun validPunch(b: ByteArray, length: Int, nonce: Int): Boolean =
        length == 7 && readBeU16(b, 0) == nonce && readBeU16(b, 2) != 0 && readBeU16(b, 4) != 0 &&
            b[6].toInt().toChar() in "PAC"

    /** IPv4 endpoint from a service-supplied literal; never resolves DNS. */
    fun endpoint(ip: Int, port: Int): InetSocketAddress = InetSocketAddress(InetAddress.getByAddress(Ip.bytes(ip)), port)

    internal fun ipOf(address: InetSocketAddress): Int? {
        val raw = address.address?.address ?: return null
        return when {
            raw.size == 4 -> Ip.read(raw, 0)
            // IPv4-mapped IPv6 from a dual-stack socket.
            raw.size == 16 && (0 until 10).all { raw[it] == 0.toByte() } && raw[10] == (-1).toByte() && raw[11] == (-1).toByte() ->
                Ip.read(raw, 12)
            else -> null
        }
    }

    /** The IPv4 form of an address received on a dual-stack socket (`udp.rs::canonical`). */
    internal fun canonical(address: InetSocketAddress): InetSocketAddress {
        if (address.address !is Inet6Address) return address
        val ip = ipOf(address) ?: return address
        return endpoint(ip, address.port)
    }

    /** Parses a correlated UES endpoint discovery response (`udp.rs::mapped_response`). */
    fun mappedResponse(data: ByteArray, length: Int, transaction: ByteArray): InetSocketAddress {
        ensure(
            length >= 20 && readBeU16(data, 0) == 0x0101 && (0 until 16).all { data[4 + it] == transaction[it] },
        ) { "UES response correlation/type" }
        ensure(readBeU16(data, 2) == length - 20) { "UES response length" }
        var at = 20
        var mapped: InetSocketAddress? = null
        while (at < length) {
            ensure(length - at >= 4) { "truncated UES attribute" }
            val kind = readBeU16(data, at)
            val len = readBeU16(data, at + 2)
            at += 4
            ensure(len <= length - at) { "truncated UES value" }
            if (kind == 1) {
                ensure(mapped == null && len == 8 && data[at] == 0.toByte() && data[at + 1] == 1.toByte()) { "invalid UES mapped address" }
                val ip = Ip.read(data, at + 4)
                val port = readBeU16(data, at + 2)
                ensure(port != 0 && ip != 0 && !Ip.isMulticast(ip) && ip != -1) { "invalid UES mapping" }
                mapped = endpoint(ip, port)
            }
            at += len
        }
        return mapped ?: fail("missing UES mapping")
    }

    /**
     * Queries two authenticated hosts concurrently on the same socket. Both must
     * agree; a response from one host never substitutes for the other.
     */
    fun discoverMapping(socket: UdpSocket, hosts: List<Int>, stop: AtomicBoolean?): InetSocketAddress {
        val unique = hosts.distinct().sortedBy { it.toLong() and 0xffffffffL }
        ensure(unique.size >= 2) { "insufficient authenticated UES hosts" }
        val choice = randomBytes(3)
        val start = (choice[0].toInt() and 0xff) % unique.size
        val servers = List(2) { index ->
            endpoint(unique[(start + index) % unique.size], UES_BASE_PORT + (choice[index + 1].toInt() and 0xff) % 99)
        }
        return discoverMappingAt(socket, servers, stop, 2000)
    }

    internal fun discoverMappingAt(socket: UdpSocket, servers: List<InetSocketAddress>, stop: AtomicBoolean?, durationMs: Long): InetSocketAddress {
        val transactions = List(2) { randomBytes(16) }
        val addresses = arrayOfNulls<InetSocketAddress>(2)
        val until = now() + durationMs
        var next = now()
        var sends = 0
        val buffer = ByteBuffer.allocate(2048)
        while (true) {
            if (stop?.get() == true) throw CancelledException()
            if (now() >= until) throw IOException("UES mapping timeout")
            if (sends < 2 && now() >= next) {
                for ((index, server) in servers.withIndex()) {
                    if (addresses[index] == null) socket.sendBestEffort(byteArrayOf(0, 1, 0, 0) + transactions[index], server)
                }
                sends++
                next = now() + 750
            }
            val source = socket.receive(buffer)
            if (source == null) {
                socket.waitReadable(minOf(if (sends < 2) next else until, until) - now())
                continue
            }
            val index = servers.indexOf(canonical(source))
            if (index >= 0 && addresses[index] == null) {
                addresses[index] = runCatching { mappedResponse(buffer.array(), buffer.position(), transactions[index]) }.getOrNull()
            }
            val a = addresses[0]
            val b = addresses[1]
            if (a != null && b != null) {
                ensure(a == b) { "UES mapping changes by destination; no port prediction attempted" }
                return a
            }
        }
    }

    /**
     * Advertises only IPv4 addresses on the interface used by the authenticated
     * coordinator (`udp.rs::bind_candidates`). Interface inventory is local, not probing.
     */
    fun bindCandidates(routeIp: Int?, protector: SocketProtector?): Pair<UdpSocket, List<InetSocketAddress>> {
        ensure(routeIp != null) { "no coordinator route for UDP candidates" }
        val socket = UdpSocket.bind(protector)
        try {
            return socket to localCandidates(routeIp!!, socket.localPort)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    fun localCandidates(routeIp: Int, port: Int): List<InetSocketAddress> {
        val entries = ArrayList<Pair<String, Int>>()
        for (nic in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            for (address in nic.inetAddresses.toList()) {
                if (address is Inet4Address) entries.add(nic.name to Ip.read(address.address, 0))
            }
        }
        return candidatesFromInventory(entries, routeIp, port)
    }

    /**
     * Like the desktop, loopback addresses are excluded, except when the
     * coordinator route itself is loopback: the same explicitly local deployment
     * in which `protocol.rs::candidate_address` accepts loopback candidates.
     */
    internal fun candidatesFromInventory(entries: List<Pair<String, Int>>, routeIp: Int, port: Int): List<InetSocketAddress> {
        val name = entries.firstOrNull { it.second == routeIp }?.first
        val candidates = entries.asSequence()
            .filter { (n, ip) -> n == name && ip != 0 && !Ip.isMulticast(ip) && (!isLoopback(ip) || isLoopback(routeIp)) }
            .map { it.second }
            .distinct()
            .sortedBy { it.toLong() and 0xffffffffL }
            .take(MAX_CANDIDATES)
            .map { endpoint(it, port) }
            .toList()
        ensure(candidates.isNotEmpty()) { "no routable local UDP candidates" }
        return candidates
    }

    fun isLoopback(ip: Int): Boolean = ip ushr 24 == 127
}

/** Nonblocking UDP socket with a readiness selector (`udp.rs::bind_dual_stack`). */
class UdpSocket private constructor(private val channel: DatagramChannel) : Closeable {
    private val selector: Selector = Selector.open()

    init {
        try {
            channel.configureBlocking(false)
            channel.register(selector, SelectionKey.OP_READ)
        } catch (e: Exception) {
            runCatching { selector.close() }
            throw e
        }
    }

    val localPort: Int get() = (channel.localAddress as InetSocketAddress).port

    /** False when the kernel refused the datagram for lack of buffer space (congestion, not failure). */
    fun sendTo(data: ByteArray, target: InetSocketAddress): Boolean = refusable { channel.send(ByteBuffer.wrap(data), target) > 0 }

    /** Best-effort datagram: congestion drops it, other errors surface (`udp.rs::send_best_effort`). */
    fun sendBestEffort(data: ByteArray, target: InetSocketAddress) {
        sendTo(data, target)
    }

    internal fun write(buffer: ByteBuffer): Boolean = refusable { channel.write(buffer) > 0 }

    /** Next datagram into [buffer] (position = length); null when none is waiting. */
    fun receive(buffer: ByteBuffer): InetSocketAddress? {
        (buffer as Buffer).clear()
        return channel.receive(buffer) as InetSocketAddress?
    }

    fun connect(target: InetSocketAddress) {
        channel.connect(target)
    }

    /** Waits until a datagram is readable, [wakeup] is called, or [timeoutMs] elapses. */
    fun waitReadable(timeoutMs: Long) {
        if (!selector.isOpen) return
        selector.select(timeoutMs.coerceIn(1, 200))
        selector.selectedKeys().clear()
    }

    fun wakeup() {
        selector.wakeup()
    }

    override fun close() {
        runCatching { selector.close() }
        runCatching { channel.close() }
    }

    private inline fun refusable(block: () -> Boolean): Boolean = try {
        block()
    } catch (e: IOException) {
        // ENOBUFS: a burst filled the send buffer. ENET retransmits what stays unacknowledged.
        if (e.message?.contains("No buffer space", ignoreCase = true) == true) false else throw e
    }

    companion object {
        fun bind(protector: SocketProtector?): UdpSocket {
            val channel = DatagramChannel.open()
            try {
                protector?.protect(channel.socket())
                channel.bind(InetSocketAddress(0))
                return UdpSocket(channel)
            } catch (e: Exception) {
                runCatching { channel.close() }
                throw e
            }
        }
    }
}

/**
 * Reliable, ordered ENET channel over one connected UDP socket. Mirrors
 * `udp.rs::Enet`: CONNECT/VERIFY/ACK setup, CRC-checked datagrams, a 256-command
 * send window, reordering and fragment reassembly with bounded buffers, and
 * jitter-free 400 ms retransmission with a 20-try / 35 s bound.
 *
 * The reader thread drives [poll]/[receive] (ingest, ACKs, retransmissions);
 * any thread may [send]. State is guarded by one lock that is never held while
 * waiting for the socket.
 */
class Enet internal constructor(
    internal val socket: UdpSocket,
    val endpoint: InetSocketAddress,
    deadline: Long?,
    @Volatile override var stop: AtomicBoolean?,
) : PeerStream {
    internal class Pending(val command: ByteArray, var last: Long, var tries: Int, val queued: Long)

    internal class Fragment(val total: Int, val count: Int) {
        val data = ByteArray(total)
        val offsets = IntArray(count)
        val lengths = IntArray(count)
        val received = BitSet(count)
        var receivedCount = 0
    }

    private val lock = Any()
    internal val cookie: ByteArray = randomBytes(4)
    internal var remoteId = 0x7fff
    internal var connected = false
    internal var incomingNonce: Int? = null
    internal var mtu = 1400
    internal var outgoing = 0
    internal var incoming = 0
    /** Keyed by `(channel shl 16) or sequence`, the order of the Rust `(u8, u16)` tree. */
    internal val pending = TreeMap<Int, Pending>()
    internal var oldestPending: Int? = null
    internal var nextRetry: Long? = null
    internal val ordered = HashMap<Int, Pair<Int, ByteArray>>()
    internal val fragments = HashMap<Int, Fragment>()
    internal val ready = ArrayDeque<ByteArray>()
    private val started = System.nanoTime()
    internal var deadline: Long? = deadline
    internal var lastReceive = now()
    /** Test hook: report every datagram as refused by a full kernel send buffer. */
    internal var refuseSends = false
    @Volatile private var waitingUntil = Long.MAX_VALUE
    private val rx = ByteBuffer.allocate(4096)
    private val tx = ByteBuffer.allocate(2048)

    override val remote: String get() = "${endpoint.address.hostAddress}:${endpoint.port}"

    /** Ends the handshake deadline once the peer channel is established. */
    override fun sustain() = synchronized(lock) { deadline = null }

    /** Whether a message of [length] bytes fits the send window now; callers drop it otherwise. */
    override fun hasRoom(length: Int): Boolean = synchronized(lock) {
        inFlight() + (length + mtu - 33) / (mtu - 32) <= Udp.SEND_WINDOW
    }

    internal fun inFlight(): Int = oldestPending?.let { ((outgoing - it) and 0xffff) + 1 } ?: 0

    override fun send(body: ByteArray) {
        val retry = synchronized(lock) {
            ensure(connected && body.isNotEmpty() && body.size <= 65536) { "UDP message size/state" }
            val fragmentSize = mtu - 32
            val count = (body.size + fragmentSize - 1) / fragmentSize
            ensure(hasRoom(body.size)) { "UDP send window exhausted" }
            val start = (outgoing + 1) and 0xffff
            for (index in 0 until count) {
                val offset = index * fragmentSize
                val size = minOf(fragmentSize, body.size - offset)
                outgoing = (outgoing + 1) and 0xffff
                val c = ByteArray((if (count == 1) 6 else 24) + size)
                c[0] = if (count == 1) 0x86.toByte() else 0x88.toByte()
                putU16(c, 2, outgoing)
                if (count == 1) {
                    putU16(c, 4, size)
                } else {
                    putU16(c, 4, start)
                    putU16(c, 6, size)
                    putU32(c, 8, count.toLong())
                    putU32(c, 12, index.toLong())
                    putU32(c, 16, body.size.toLong())
                    putU32(c, 20, offset.toLong())
                }
                System.arraycopy(body, offset, c, c.size - size, size)
                queue(c)
            }
            nextRetry
        }
        // The reader may be sleeping until a later timer; make it see the new retry deadline.
        if (retry != null && retry < waitingUntil) socket.wakeup()
    }

    override fun receive(max: Int, deadline: Long): ByteArray {
        while (true) {
            synchronized(lock) {
                val data = ready.removeFirstOrNull() ?: run {
                    if (now() >= deadline) throw DeadlineException("UDP receive timed out")
                    pump()
                    ready.removeFirstOrNull()
                }
                if (data != null) {
                    ensure(data.size <= max) { "UDP message limit" }
                    return data
                }
            }
            waitFor(deadline)
        }
    }

    override fun poll(max: Int, waitMs: Long): ByteArray? {
        val until = now() + waitMs
        while (true) {
            synchronized(lock) {
                pump()
                ready.removeFirstOrNull()?.let {
                    ensure(it.size <= max) { "UDP message limit" }
                    return it
                }
                if (now() >= until) return null
            }
            waitFor(until)
        }
    }

    /**
     * Closes the socket. An established channel first sends a best-effort ENET
     * DISCONNECT (unsequenced, like `enet_peer_disconnect_now`), so the peer
     * fails over at once instead of waiting for its liveness timeout.
     */
    override fun close() {
        synchronized(lock) {
            if (connected) runCatching { transmit(byteArrayOf(0x44, 0xff.toByte(), 0, 0, 0, 0, 0, 0)) }
            connected = false
        }
        socket.close()
    }

    private fun waitFor(until: Long) {
        val next = synchronized(lock) {
            var next = minOf(until, lastReceive + Udp.LIVENESS_MS)
            deadline?.let { next = minOf(next, it) }
            nextRetry?.let { next = minOf(next, it) }
            waitingUntil = next
            next
        }
        try {
            socket.waitReadable(next - now())
        } finally {
            waitingUntil = Long.MAX_VALUE
        }
    }

    /** Ingests waiting datagrams, then expires liveness and retransmits due commands. */
    internal fun pump() {
        synchronized(lock) { pumpLocked() }
    }

    private fun pumpLocked() {
        if (stop?.get() == true) throw CancelledException()
        deadline?.let { if (now() >= it) throw DeadlineException("UDP handshake deadline") }
        // A busy process can resume with ACKs already waiting in the socket.
        // Consume them before expiring liveness or the final retry.
        for (i in 0 until 64) {
            if (ready.size >= Udp.READY_HIGH_WATER) break
            socket.receive(rx) ?: break
            ingestLocked(rx.array(), rx.position())
        }
        val t = now()
        if (t - lastReceive >= Udp.LIVENESS_MS) throw IOException("UDP liveness timeout")
        val due = nextRetry
        if (due == null || t < due) return
        var next: Long? = null
        for (p in pending.values) {
            var retry = p.last + Udp.RETRY_INTERVAL_MS
            if (t >= retry) {
                if (p.tries >= Udp.MAX_TRIES) throw IOException("UDP acknowledgement timeout")
                // Bound sustained send-buffer congestion even when tries never advance.
                if (t - p.queued >= Udp.LIVENESS_MS) throw IOException("UDP send congestion timeout")
                if (transmit(p.command)) {
                    p.tries++
                    p.last = t
                } else {
                    // Not transmitted, so not a failed attempt: try again soon.
                    p.last = refusedLast(t)
                }
                retry = p.last + Udp.RETRY_INTERVAL_MS
            }
            next = if (next == null) retry else minOf(next, retry)
        }
        nextRetry = next
    }

    /** Whether the datagram was handed to the kernel; false means its send buffer was full. */
    private fun transmit(command: ByteArray): Boolean {
        if (refuseSends) return false
        val header = ByteArray(8)
        putU16(header, 4, remoteId or 0x8000)
        putU16(header, 6, (((System.nanoTime() - started) / 1_000_000) and 0xffff).toInt())
        putU32(header, 0, Crc.slices(header, command).toLong() and 0xffffffffL)
        val packet = if (8 + command.size <= tx.capacity()) tx else ByteBuffer.allocate(8 + command.size)
        (packet as Buffer).clear()
        packet.put(header).put(command)
        (packet as Buffer).flip()
        return socket.write(packet)
    }

    internal fun queue(command: ByteArray) = synchronized(lock) {
        ensure(pending.size < Udp.SEND_WINDOW) { "UDP send window exhausted" }
        ensure(command.size >= 4) { "truncated ENET command" }
        // A refused datagram stays pending and is retransmitted shortly.
        val sent = transmit(command)
        val channel = command[1].toInt() and 0xff
        val sequence = readBeU16(command, 2)
        val t = now()
        val last = if (sent) t else refusedLast(t)
        if (channel == 0 && oldestPending == null) oldestPending = sequence
        val retry = last + Udp.RETRY_INTERVAL_MS
        nextRetry = nextRetry?.let { minOf(it, retry) } ?: retry
        pending[key(channel, sequence)] = Pending(command, last, if (sent) 1 else 0, t)
    }

    private fun removePending(channel: Int, sequence: Int): Pending? {
        val removed = pending.remove(key(channel, sequence)) ?: return null
        if (channel == 0 && oldestPending == sequence) {
            // Commands are queued in wrapping sequence order; skip acknowledged holes.
            oldestPending = pending.ceilingKey(sequence)?.takeIf { it <= 0xffff }
                ?: pending.firstEntry()?.key?.takeIf { it < sequence }
        }
        if (pending.isEmpty()) nextRetry = null
        return removed
    }

    internal fun ingest(b: ByteArray, length: Int = b.size) {
        synchronized(lock) { ingestLocked(b, length) }
    }

    private fun ingestLocked(b: ByteArray, length: Int) {
        val nonce = incomingNonce
        if (nonce != null && Udp.validPunch(b, length, nonce)) {
            if (b[6] == 'P'.code.toByte() || b[6] == 'A'.code.toByte()) {
                socket.write(ByteBuffer.wrap(Udp.punch(nonce, endpoint.port, socket.localPort, 'C')))
            }
            return
        }
        if (length < 8) return
        if (readBeU32(b, 0) != (Crc.enet(b, length).toLong() and 0xffffffffL)) return
        val id = readBeU16(b, 4)
        if (id and 0x7fff != 0 && !(nonce != null && id and 0x7fff == 0x7fff)) return
        val time = if (id and 0x8000 != 0) readBeU16(b, 6) else 0
        var at = if (id and 0x8000 != 0) 8 else 6
        for (i in 0 until 32) {
            if (at == length) break
            ensure(length - at >= 4) { "truncated ENET command" }
            val kind = b[at].toInt() and 15
            ensure(kind > 0 && kind < SIZES.size) { "unknown ENET command" }
            val size = SIZES[kind]
            ensure(length - at >= size) { "truncated ENET command body" }
            val c = at
            val flags = b[c].toInt() and 0xff
            val channel = b[c + 1].toInt() and 0xff
            val seq = readBeU16(b, c + 2)
            at += size
            val len = when (kind) {
                6 -> readBeU16(b, c + 4)
                7, 8, 9 -> readBeU16(b, c + 6)
                else -> 0
            }
            ensure(len <= length - at) { "truncated ENET data" }
            val payload = at
            at += len
            if (kind == 2) {
                ensure(
                    nonce != null && id and 0x8000 != 0 && channel == 255 && seq == 1 && flags == 0x82 &&
                        readBeU16(b, c + 4) < 0x7fff && readBeU32(b, c + 12) == 1L && readBeU32(b, c + 24) == 5000L &&
                        readBeU32(b, c + 28) == 2L && readBeU32(b, c + 32) == 2L,
                ) { "ENET incoming CONNECT mismatch" }
                if (remoteId == 0x7fff) {
                    remoteId = readBeU16(b, c + 4)
                    mtu = readBeU16(b, c + 6).coerceIn(576, mtu)
                    System.arraycopy(b, c + 36, cookie, 0, 4)
                    val verify = ByteArray(36)
                    verify[0] = 0x83.toByte()
                    verify[1] = 0xff.toByte()
                    putU16(verify, 2, 1)
                    putU16(verify, 6, mtu)
                    for ((index, value) in HANDSHAKE.withIndex()) putU32(verify, 8 + 4 * index, value)
                    queue(verify)
                } else {
                    ensure(remoteId == readBeU16(b, c + 4) && (0 until 4).all { cookie[it] == b[c + 36 + it] }) { "ENET changed CONNECT" }
                }
            }
            if (flags and 128 != 0) {
                ensure(id and 0x8000 != 0) { "ENET reliable command without timestamp" }
                if (kind == 3) {
                    ensure(
                        nonce == null && channel == 255 && seq == 1 && flags == 0x83 && readBeU32(b, c + 12) == 1L &&
                            readBeU32(b, c + 24) == 5000L && readBeU32(b, c + 28) == 2L && readBeU32(b, c + 32) == 2L,
                    ) { "ENET verification mismatch" }
                    remoteId = readBeU16(b, c + 4)
                    ensure(remoteId < 0x7fff) { "ENET peer id" }
                    mtu = readBeU16(b, c + 6).coerceIn(576, mtu)
                    removePending(255, 1)
                    connected = true
                }
            }
            // A reliable command outside the receive window is left unacknowledged
            // so the sender retransmits it, instead of failing the channel.
            val accepted = when (kind) {
                1 -> {
                    val acknowledged = removePending(channel, readBeU16(b, c + 4))
                    if (nonce != null && channel == 255 && readBeU16(b, c + 4) == 1 && acknowledged != null) connected = true
                    true
                }
                2, 3, 5, 10, 11 -> true
                4 -> throw IOException("UDP peer disconnected")
                6 -> {
                    ensure(channel == 0) { "unsupported ENET channel/command" }
                    acceptOrdered(seq, 1, b.copyOfRange(payload, payload + len))
                }
                8 -> {
                    ensure(channel == 0) { "unsupported ENET channel/command" }
                    fragment(b, c, payload, len)
                }
                else -> fail("unsupported ENET channel/command")
            }
            if (flags and 128 != 0 && accepted) {
                val ack = ByteArray(8)
                ack[0] = 1
                ack[1] = channel.toByte()
                putU16(ack, 4, seq)
                putU16(ack, 6, time)
                // A lost ACK only makes the sender retransmit; duplicates are acknowledged again.
                transmit(ack)
            }
            lastReceive = now()
        }
        ensure(at == length) { "ENET command budget" }
    }

    /** True once the message is buffered or already delivered; false outside the receive window. */
    internal fun acceptOrdered(start: Int, count: Int, data: ByteArray): Boolean {
        val ahead = (start - incoming) and 0xffff
        if (ahead == 0 || ahead > 0x8000) return true
        if (!orderedHasRoom(start)) return false
        if (ahead == 1) {
            // Preserve first-arrival precedence if this sequence was already buffered.
            val (n, first) = ordered.remove(start) ?: (count to data)
            incoming = (incoming + n) and 0xffff
            ready.addLast(first)
        } else {
            ordered.putIfAbsent(start, count to data)
        }
        while (true) {
            val (n, next) = ordered.remove((incoming + 1) and 0xffff) ?: break
            incoming = (incoming + n) and 0xffff
            ready.addLast(next)
        }
        // Retransmitted fragments of delivered messages can leave groups that never complete.
        fragments.keys.removeAll { ((it - incoming) and 0xffff).let { d -> d == 0 || d >= 0x8001 } }
        return true
    }

    private fun orderedHasRoom(start: Int): Boolean {
        val ahead = (start - incoming) and 0xffff
        // The next expected message is always taken: refusing it would stall the channel.
        return ahead <= Udp.RECEIVE_WINDOW && (ahead == 1 || (ordered.size < 256 && ready.size < 256))
    }

    private fun fragment(b: ByteArray, c: Int, payload: Int, length: Int): Boolean {
        val start = readBeU16(b, c + 4)
        val count = readBeU32(b, c + 8)
        val index = readBeU32(b, c + 12)
        val total = readBeU32(b, c + 16)
        val offset = readBeU32(b, c + 20)
        val ahead = (start - incoming) and 0xffff
        if (ahead == 0 || ahead > 0x8000 || ordered.containsKey(start)) return true
        ensure(
            count in 1L..128L && index < count && total in 1L..65536L && offset <= total && length <= total - offset,
        ) { "UDP fragment bounds" }
        ensure(readBeU16(b, c + 2) == ((start + index.toInt()) and 0xffff)) { "UDP fragment sequence" }
        val full = !fragments.containsKey(start) && fragments.size >= 16
        if (ahead > Udp.RECEIVE_WINDOW || (full && ahead != 1)) return false
        val f = fragments.getOrPut(start) { Fragment(total.toInt(), count.toInt()) }
        ensure(f.total.toLong() == total && f.count.toLong() == count) { "inconsistent UDP fragments" }
        val i = index.toInt()
        if (!f.received[i]) {
            System.arraycopy(b, payload, f.data, offset.toInt(), length)
            f.offsets[i] = offset.toInt()
            f.lengths[i] = length
            f.received.set(i)
            f.receivedCount++
        }
        if (f.receivedCount == f.count) {
            var assembled = 0
            for (part in 0 until f.count) {
                ensure(f.offsets[part] == assembled) { "overlapping/gapped UDP fragments" }
                assembled += f.lengths[part]
            }
            ensure(assembled == f.total) { "UDP fragment total mismatch" }
            // Earlier parts are already acknowledged; keep them while the reorder queue is full.
            if (!orderedHasRoom(start)) return false
            fragments.remove(start)
            return acceptOrdered(start, f.count, f.data)
        }
        return true
    }

    companion object {
        private val SIZES = intArrayOf(0, 8, 40, 36, 8, 4, 6, 8, 24, 8, 12, 16)
        /** Outgoing bandwidth fields of CONNECT/VERIFY: window, channels, bandwidth, throttle. */
        private val HANDSHAKE = longArrayOf(32768, 1, 0, 0, 5000, 2, 2)

        internal fun key(channel: Int, sequence: Int) = (channel shl 16) or sequence

        /** The `last` timestamp that makes a command due again after [Udp.REFUSED_RETRY_MS]. */
        private fun refusedLast(t: Long) = t - (Udp.RETRY_INTERVAL_MS - Udp.REFUSED_RETRY_MS)

        private fun putU16(b: ByteArray, at: Int, value: Int) {
            b[at] = (value ushr 8).toByte()
            b[at + 1] = value.toByte()
        }

        private fun putU32(b: ByteArray, at: Int, value: Long) {
            for (i in 0 until 4) b[at + i] = (value ushr (24 - 8 * i)).toByte()
        }

        /**
         * Incoming role: exact candidate + server-correlated nonce, then ENET's
         * CONNECT/VERIFY/ACK. This is transport setup, not peer authentication.
         * The socket is closed on failure.
         */
        fun accept(socket: UdpSocket, endpoints: List<InetSocketAddress>, nonce: Int, durationMs: Long, stop: AtomicBoolean?): Enet =
            establish(socket, endpoints, nonce, durationMs, stop, incoming = true)

        /** Outgoing role: punches every candidate, then sends ENET CONNECT. The socket is closed on failure. */
        fun connect(socket: UdpSocket, endpoints: List<InetSocketAddress>, nonce: Int, durationMs: Long, stop: AtomicBoolean?): Enet =
            establish(socket, endpoints, nonce, durationMs, stop, incoming = false)

        private fun establish(
            socket: UdpSocket,
            endpoints: List<InetSocketAddress>,
            nonce: Int,
            durationMs: Long,
            stop: AtomicBoolean?,
            incoming: Boolean,
        ): Enet {
            try {
                ensure((endpoints.isNotEmpty() || !incoming) && endpoints.size <= Udp.MAX_CANDIDATES && nonce in 1..0xffff) {
                    "UDP candidate/nonce limit"
                }
                val until = now() + durationMs
                val endpoint = rendezvous(socket, endpoints, nonce, until, stop, incoming)
                socket.connect(endpoint)
                val e = Enet(socket, endpoint, until, stop)
                if (incoming) {
                    e.incomingNonce = nonce
                } else {
                    val command = ByteArray(40)
                    command[0] = 0x82.toByte()
                    command[1] = 0xff.toByte()
                    putU16(command, 2, 1)
                    putU16(command, 6, e.mtu)
                    for ((index, value) in HANDSHAKE.withIndex()) putU32(command, 8 + 4 * index, value)
                    System.arraycopy(e.cookie, 0, command, 36, 4)
                    e.queue(command)
                }
                while (true) {
                    synchronized(e.lock) {
                        e.pump()
                        if (e.connected) return e
                    }
                    e.waitFor(until)
                }
            } catch (t: Throwable) {
                socket.close()
                throw t
            }
        }

        /** Exact authenticated candidates only: no port guessing or unsolicited peers. */
        private fun rendezvous(
            socket: UdpSocket,
            endpoints: List<InetSocketAddress>,
            nonce: Int,
            until: Long,
            stop: AtomicBoolean?,
            incoming: Boolean,
        ): InetSocketAddress {
            val localPort = socket.localPort
            var next = now()
            val buffer = ByteBuffer.allocate(4096)
            var received = 0
            var matched = 0
            val kinds = IntArray(3)
            while (true) {
                if (stop?.get() == true) throw CancelledException()
                if (now() >= until) {
                    throw IOException(
                        if (incoming) {
                            "incoming UDP rendezvous timeout"
                        } else {
                            "UDP rendezvous timeout (received=$received, matched=$matched, P/A/C=${kinds.toList()})"
                        },
                    )
                }
                if (now() >= next) {
                    for (a in endpoints) runCatching { socket.sendTo(Udp.punch(nonce, a.port, localPort, 'P'), a) }
                    next = now() + 250
                }
                val source = socket.receive(buffer)
                if (source == null) {
                    socket.waitReadable(minOf(next, until) - now())
                    continue
                }
                received++
                val a = Udp.canonical(source)
                val data = buffer.array()
                if (a !in endpoints || !Udp.validPunch(data, buffer.position(), nonce)) continue
                matched++
                val kind = data[6].toInt().toChar()
                kinds[when (kind) { 'P' -> 0; 'A' -> 1; else -> 2 }]++
                if (incoming) {
                    if (kind == 'P' || kind == 'A') {
                        socket.sendBestEffort(Udp.punch(nonce, source.port, localPort, 'C'), source)
                        return a
                    }
                } else {
                    if (kind == 'C') return a
                    if (kind == 'P') socket.sendBestEffort(Udp.punch(nonce, a.port, localPort, 'A'), source)
                }
            }
        }
    }
}
