package com.openrad.mobile.engine

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Lets the VpnService exclude engine sockets from the tunnel (`VpnService.protect`). */
interface SocketProtector {
    fun protect(socket: Socket): Boolean

    /** UDP sockets of direct peer paths. */
    fun protect(socket: DatagramSocket): Boolean
}

/**
 * Whether the underlying network permits direct peer paths. Direct TCP and UDP
 * are used only on Wi-Fi; any other network is relay-only, like force-relay.
 * The Android implementation tracks `ConnectivityManager`; the engine stays
 * platform-free and re-reads it on [Engine.networkChanged].
 */
fun interface NetworkPolicy {
    fun isWifi(): Boolean

    companion object {
        /** JVM tests and hosts without network classification. */
        val UNRESTRICTED = NetworkPolicy { true }
    }
}

class CancelledException : IOException("cancelled")

class DeadlineException(message: String) : IOException(message)

/** Monotonic milliseconds. */
internal fun now(): Long = System.nanoTime() / 1_000_000

/** Record transport under a peer channel: TCP [Framed] or UDP [Enet] (`peer.rs::PeerStream`). */
interface PeerStream {
    /** Cancellation flag checked by blocking calls. */
    var stop: AtomicBoolean?
    val remote: String

    fun send(body: ByteArray)

    /** Whether a record of [length] bytes can be sent now without exceeding a transport window. */
    fun hasRoom(length: Int): Boolean = true

    fun receive(max: Int, deadline: Long): ByteArray

    /** Waits up to [waitMs] for a complete record; null when none arrived yet. */
    fun poll(max: Int, waitMs: Long): ByteArray?

    /** Lifts setup deadlines once the channel is established. */
    fun sustain() {}

    fun close()
}

/**
 * Length-prefixed TCP records. Mirrors `session.rs::Framed`: timeouts keep any
 * partially received record; one read may capture several records.
 */
class Framed(val socket: Socket, @Volatile override var stop: AtomicBoolean? = null) : PeerStream {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()
    private var buffer = ByteArray(16384)
    private var start = 0
    private var end = 0
    /** Last SO_TIMEOUT applied; on Android every assignment is a setsockopt call. */
    private var readTimeout = -1
    /** Reused length prefix + body for [send], guarded by [writeLock]. */
    private var writeBuffer = ByteArray(2048)

    init {
        runCatching {
            socket.tcpNoDelay = true
            socket.keepAlive = true
        }
    }

    override val remote: String get() = socket.inetAddress?.hostAddress?.let { "$it:${socket.port}" } ?: "?"

    val localAddress: String? get() = socket.localAddress?.hostAddress

    override fun send(body: ByteArray) {
        ensure(body.isNotEmpty() && body.size <= MAX_WIRE) { "invalid outgoing record size" }
        if (stop?.get() == true) throw CancelledException()
        synchronized(writeLock) {
            val total = 4 + body.size
            var out = writeBuffer
            if (total > out.size) {
                out = ByteArray(total)
                if (total <= WRITE_BUFFER_LIMIT) writeBuffer = out
            }
            out[0] = (body.size ushr 24).toByte()
            out[1] = (body.size ushr 16).toByte()
            out[2] = (body.size ushr 8).toByte()
            out[3] = body.size.toByte()
            System.arraycopy(body, 0, out, 4, body.size)
            output.write(out, 0, total)
            output.flush()
        }
    }

    /**
     * Writes [bodies] as consecutive records with one socket write. The stream bytes
     * equal successive [send] calls; only TCP segmentation may differ.
     */
    fun sendAll(bodies: List<ByteArray>) {
        var total = 0
        for (body in bodies) {
            ensure(body.isNotEmpty() && body.size <= MAX_WIRE) { "invalid outgoing record size" }
            total += 4 + body.size
        }
        ensure(total <= MAX_WIRE) { "invalid outgoing batch size" }
        if (stop?.get() == true) throw CancelledException()
        synchronized(writeLock) {
            var out = writeBuffer
            if (total > out.size) {
                out = ByteArray(total)
                if (total <= WRITE_BUFFER_LIMIT) writeBuffer = out
            }
            var at = 0
            for (body in bodies) {
                out[at] = (body.size ushr 24).toByte()
                out[at + 1] = (body.size ushr 16).toByte()
                out[at + 2] = (body.size ushr 8).toByte()
                out[at + 3] = body.size.toByte()
                System.arraycopy(body, 0, out, at + 4, body.size)
                at += 4 + body.size
            }
            output.write(out, 0, total)
            output.flush()
        }
    }

    fun write(data: ByteArray) {
        if (stop?.get() == true) throw CancelledException()
        synchronized(writeLock) {
            output.write(data)
            output.flush()
        }
    }

    private fun buffered() = end - start

    /** Reads more bytes; returns false on timeout of this slice. */
    private fun fill(deadline: Long): Boolean {
        if (stop?.get() == true) throw CancelledException()
        val remaining = deadline - now()
        if (remaining <= 0) throw DeadlineException("TCP read timed out")
        if (start > 0 && start == end) {
            start = 0
            end = 0
        }
        if (end == buffer.size) {
            if (start > 0) {
                System.arraycopy(buffer, start, buffer, 0, buffered())
                end -= start
                start = 0
            } else {
                buffer = buffer.copyOf(minOf(buffer.size * 2, MAX_WIRE + 8))
            }
        }
        val timeout = remaining.coerceIn(1, 200).toInt()
        if (timeout != readTimeout) {
            socket.soTimeout = timeout
            readTimeout = timeout
        }
        val n = try {
            input.read(buffer, end, buffer.size - end)
        } catch (_: SocketTimeoutException) {
            return false
        }
        if (n < 0) throw IOException("TCP stream closed")
        end += n
        return true
    }

    private fun ensureBuffered(size: Int, deadline: Long) {
        if (start + size > buffer.size) {
            val next = ByteArray(maxOf(size, buffer.size))
            System.arraycopy(buffer, start, next, 0, buffered())
            end -= start
            start = 0
            buffer = next
        }
        while (buffered() < size) fill(deadline)
    }

    fun readExact(size: Int, deadline: Long): ByteArray {
        ensureBuffered(size, deadline)
        val out = buffer.copyOfRange(start, start + size)
        start += size
        return out
    }

    override fun receive(max: Int, deadline: Long): ByteArray {
        ensureBuffered(4, deadline)
        val size = readBeU32(buffer, start)
        ensure(size in 1..max.toLong()) { "incoming record exceeds limit" }
        ensureBuffered(4 + size.toInt(), deadline)
        val out = buffer.copyOfRange(start + 4, start + 4 + size.toInt())
        start += 4 + size.toInt()
        return out
    }

    override fun poll(max: Int, waitMs: Long): ByteArray? {
        val deadline = now() + waitMs
        while (true) {
            if (buffered() >= 4) {
                val size = readBeU32(buffer, start)
                ensure(size in 1..max.toLong()) { "incoming record exceeds limit" }
                if (buffered() >= 4 + size) return receive(max, deadline + 1)
            }
            if (now() >= deadline) return null
            try {
                if (!fill(deadline) && now() >= deadline) return null
            } catch (_: DeadlineException) {
                return null
            }
        }
    }

    override fun close() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }

    companion object {
        const val MAX_WIRE = 4 * 1024 * 1024
        private const val WRITE_BUFFER_LIMIT = 65536 + 128

        fun connect(host: String, port: Int, timeoutMs: Int, protector: SocketProtector?, stop: AtomicBoolean?): Framed {
            Ip.parse(host) // Service replies supply IP literals; never resolve DNS here.
            ensure(port in 1..65535) { "invalid TCP port" }
            if (stop?.get() == true) throw CancelledException()
            val socket = Socket()
            try {
                protector?.protect(socket)
                socket.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
                return Framed(socket, stop)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}

/** Nearest integer millisecond, ties to even (ROL 0x10001ff0). */
fun roundMilliseconds(nanos: Long): Long {
    val whole = nanos / 1_000_000
    val rem = nanos % 1_000_000
    return whole + if (rem > 500_000 || (rem == 500_000L && whole % 2 == 1L)) 1 else 0
}

/** RSession + SH handshake; returns the smallest measured round trip in ms. */
fun authenticate(stream: Framed, crypto: SessionCrypto, deadline: Long): Long {
    stream.send(byteArrayOf(3, 0, 0, 0))
    stream.send(crypto.f6)
    var reply = crypto.acceptEcho(stream.receive(4, deadline))
    var latency = Long.MAX_VALUE
    for (step in 0 until 3) {
        val started = System.nanoTime()
        stream.send(reply)
        val data = stream.receive(65536, deadline)
        when (step) {
            0 -> reply = crypto.receiveF10(data)
            1 -> reply = crypto.receiveF12(data)
            else -> crypto.receiveF14(data)
        }
        latency = minOf(latency, roundMilliseconds(System.nanoTime() - started))
    }
    return latency
}

/** Encrypted control session with the coordination server. */
class Control(val stream: Framed, private val crypto: SessionCrypto, val latencyMs: Long) {
    fun send(data: ByteArray) = stream.send(crypto.channel.encrypt(data))

    fun receive(deadline: Long): ByteArray = crypto.channel.decrypt(stream.receive(Framed.MAX_WIRE, deadline))

    fun poll(waitMs: Long): ByteArray? = stream.poll(Framed.MAX_WIRE, waitMs)?.let { crypto.channel.decrypt(it) }

    fun close() = stream.close()

    companion object {
        fun open(
            host: String,
            port: Int,
            rid: Long,
            password: ByteArray,
            purpose: Int,
            protector: SocketProtector?,
            stop: AtomicBoolean?,
            modulus: ByteArray = Protocol.SERVER_MODULUS,
            loginBody: (Long) -> ByteArray,
        ): Control {
            val stream = Framed.connect(host, port, 6000, protector, stop)
            try {
                val crypto = SessionCrypto(modulus, rid, password, purpose)
                val latency = authenticate(stream, crypto, now() + 35_000)
                val control = Control(stream, crypto, latency)
                control.send(loginBody(latency))
                return control
            } catch (e: Exception) {
                stream.close()
                throw e
            }
        }
    }
}

/**
 * Registers a brand-new identity (purpose 3). Only an authenticated redirect may
 * restart registration; an ambiguous failure after Login is never retried.
 */
fun provisionIdentity(
    nodeName: String,
    host: String,
    port: Int,
    protector: SocketProtector?,
    stop: AtomicBoolean?,
    modulus: ByteArray = Protocol.SERVER_MODULUS,
): Identity {
    Protocol.validateNodeName(nodeName)
    val bootstrapRid = secureRandom.nextInt(1000) + 1L
    val visited = HashSet<String>()
    val deadline = now() + 60_000
    var target = host
    repeat(4) {
        ensure(target !in visited && now() < deadline) { "registration redirect budget exhausted" }
        visited.add(target)
        var control: Control? = null
        try {
            control = Control.open(target, port, bootstrapRid, BOOTSTRAP_PASSWORD, 3, protector, stop, modulus) {
                Protocol.login(nodeName, 0, 3)
            }
            val receiver = Protocol.Provisioning()
            repeat(16) {
                receiver.receive(control.receive(deadline))?.let { return it }
            }
            fail("registration did not return an identity")
        } catch (redirect: Protocol.ServerRedirect) {
            target = redirect.host
        } finally {
            control?.close()
        }
    }
    fail("registration redirect budget exhausted")
}

private val BOOTSTRAP_PASSWORD = "rpassword".toByteArray()

enum class TransportPath(val label: String) { DIRECT_TCP("direct-tcp"), DIRECT_UDP("direct-udp"), RELAY("relay") }

/** Presents a relay ticket; the relay answers once it admits the pairing. */
fun pairRelay(stream: PeerStream, ticket: ByteArray, deadline: Long) {
    stream.send(concat(Tlv.u32(0x010001dfL, 1), Tlv.u32(0x0100032bL, 2), Tlv.tlv(0x090001caL, ticket)))
    ensure(Tlv.unique(Tlv.parse(stream.receive(65536, deadline)), 0x010001dfL).u32() == 2L) { "relay ticket rejected" }
}

/**
 * Connects and pairs a relay socket without authenticating the peer, so the
 * relay is ready the moment the direct head start ends (`RelayPreparation`).
 */
fun prepareRelay(offer: Protocol.RelayOffer, deadline: Long, protector: SocketProtector?, stop: AtomicBoolean): Framed {
    val stream = Framed.connect(offer.host, offer.port, (deadline - now()).coerceIn(1, 6000).toInt(), protector, stop)
    try {
        pairRelay(stream, offer.ticket, deadline)
        return stream
    } catch (e: Exception) {
        stream.close()
        throw e
    }
}

/** Authenticated, encrypted peer channel over direct TCP, direct UDP or a relay ticket. */
class PeerChannel(
    val stream: PeerStream,
    ownRid: Long,
    ownVip: Int,
    peerRid: Long,
    password: ByteArray,
    incoming: Boolean,
    ticket: ByteArray?,
    val transport: TransportPath,
    timeoutMs: Long,
) {
    private val cc: CChannel
    val mac: ByteArray
    val version: Long
    private val sendLock = Any()

    init {
        val deadline = now() + timeoutMs
        try {
            if (ticket != null) pairRelay(stream, ticket, deadline)
            val key = if (incoming) {
                val sh = ShServer(ownRid, password)
                try {
                    stream.send(sh.hello(stream.receive(65536, deadline)))
                    stream.send(sh.public(stream.receive(65536, deadline)))
                    val (proof, key) = sh.proof(stream.receive(65536, deadline))
                    stream.send(proof)
                    key
                } finally {
                    sh.wipe()
                }
            } else {
                val sh = ShClient(peerRid, password)
                try {
                    stream.send(sh.start())
                    stream.send(sh.receiveF10(stream.receive(65536, deadline)))
                    stream.send(sh.receiveF12(stream.receive(65536, deadline)))
                    sh.receiveF14(stream.receive(65536, deadline))
                } finally {
                    sh.wipe()
                }
            }
            cc = CChannel(key.copyOfRange(0, 32))
            val ownMac = Protocol.macForIp(ownVip)
            if (incoming) {
                val marker = receive(deadline)
                val race = receive(deadline)
                ensure(marker.contentEquals(RACE_MARKER) && race.size == 16) { "invalid channel race marker" }
                val negotiated = Tunnel.acceptSyn(receive(deadline), peerRid, ownMac)
                mac = negotiated.mac
                version = negotiated.version
                setup(negotiated.reply)
                Tunnel.acceptAck(receive(deadline), version)
            } else {
                setup(RACE_MARKER)
                setup(uuidBytesLe(UUID.randomUUID()))
                setup(Tunnel.syn(ownRid, ownMac))
                val negotiated = Tunnel.synack(receive(deadline))
                mac = negotiated.mac
                version = negotiated.version
                setup(negotiated.reply)
            }
        } catch (e: Exception) {
            stream.close()
            throw e
        }
    }

    /**
     * Returns false when the transport window is full and the record was dropped.
     * The check precedes encryption: the CBC chain continues across records, so a
     * ciphertext that is never sent would desynchronise the peer (`peer.rs::send`).
     */
    fun send(plain: ByteArray): Boolean {
        synchronized(sendLock) {
            if (!stream.hasRoom((plain.size + 9 + 15) / 16 * 16)) return false
            stream.send(cc.encrypt(plain))
            return true
        }
    }

    /** [send] of `Tunnel.encode(frame)`, reusing a shared frame's sealed envelope. */
    fun sendFrame(frame: OutFrame): Boolean {
        if (!frame.shared) return send(Tunnel.encode(frame.frame))
        val body = frame.sealed()
        synchronized(sendLock) {
            if (!stream.hasRoom(body.size)) return false
            stream.send(cc.encryptSealed(body))
            return true
        }
    }

    private fun record(frame: OutFrame): ByteArray =
        if (frame.shared) cc.encryptSealed(frame.sealed()) else cc.encrypt(Tunnel.encode(frame.frame))

    /** Whether [sendFrames] applies: TCP streams never drop records, UDP windows decide per record. */
    val batches: Boolean get() = stream is Framed

    /**
     * [sendFrame] for each of [frames] in order, written with one TCP write
     * ([Framed.sendAll]). Wire bytes equal successive sends; requires [batches].
     */
    fun sendFrames(frames: List<OutFrame>) {
        val framed = stream as Framed
        for (frame in frames) if (frame.shared) frame.sealed() // outside the lock
        synchronized(sendLock) {
            val records = ArrayList<ByteArray>(frames.size)
            for (frame in frames) records.add(record(frame))
            framed.sendAll(records)
        }
    }

    private fun setup(plain: ByteArray) = ensure(send(plain)) { "peer channel setup exceeded the transport window" }

    fun receive(deadline: Long): ByteArray = cc.decrypt(stream.receive(65536 + 64, deadline))

    fun poll(waitMs: Long): ByteArray? = stream.poll(65536 + 64, waitMs)?.let { cc.decrypt(it) }

    fun close() = stream.close()

    companion object {
        val RACE_MARKER = byteArrayOf(0xef.toByte(), 0xbe.toByte(), 0xad.toByte(), 0xde.toByte())

        /** Python `uuid.bytes_le`: first three fields little-endian. */
        fun uuidBytesLe(uuid: UUID): ByteArray {
            val be = beU64(uuid.mostSignificantBits) + beU64(uuid.leastSignificantBits)
            return byteArrayOf(be[3], be[2], be[1], be[0], be[5], be[4], be[7], be[6]) + be.copyOfRange(8, 16)
        }
    }
}
