package com.openrad.mobile.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Synthetic but realistic packet-path micro-benchmarks (JVM headless, no emulator,
 * no production service; only 127.0.0.1 loopback). Baseline for the 2.0 goal of
 * +30% packet-path speed with an identical wire format (see [WireIdentityTest]).
 *
 * Each case warms up long enough for the JIT to compile the path, then runs
 * [REPS] measured repetitions with [System.nanoTime] and prints one `[bench]`
 * line with the median and best time per operation (visible with `--info` or
 * in `app/build/test-results/testDebugUnitTest/TEST-*PacketPathBenchmarkTest.xml`).
 *
 * Budget: the whole class runs in well under 60 s and keeps bounded memory
 * (payloads are reused; counting peers only count frames).
 */
class PacketPathBenchmarkTest {
    private fun payload(size: Int): ByteArray = ByteArray(size) { it.toByte() }

    private fun benchHeader(name: String) = println("[bench] $name: warmup + $REPS measured runs (median), System.nanoTime")

    /** Runs [body] [warmup] times, then [REPS] timed runs of [ops] operations; reports the median. */
    private fun measure(tag: String, ops: Int, bytesPerOp: Long, warmup: Int = ops, body: (Int) -> Unit) {
        repeat(warmup) { body(it) }
        val runs = LongArray(REPS)
        for (r in 0 until REPS) {
            val start = System.nanoTime()
            for (i in 0 until ops) body(i)
            runs[r] = System.nanoTime() - start
        }
        report(tag, ops.toLong(), runs, bytesPerOp)
    }

    private fun report(tag: String, ops: Long, runs: LongArray, bytesPerOp: Long) {
        val sorted = runs.sorted()
        val median = sorted[sorted.size / 2]
        val nsPerOp = median.toDouble() / ops
        val mbPerSec = bytesPerOp * 1e3 / nsPerOp
        println(
            "[bench] $tag: ops=$ops medianMs=${"%.2f".format(median / 1e6)} " +
                "usPerOp=${"%.3f".format(nsPerOp / 1e3)} bestUsPerOp=${"%.3f".format(sorted[0] / 1e3 / ops)} " +
                "opsPerSec=${"%.1f".format(1e9 / nsPerOp)} MBPerSec=${"%.2f".format(mbPerSec)}",
        )
    }

    // ---------------------------------------------------------------- crypto

    /**
     * Continuous CChannel encrypt/decrypt with chained IVs (the production packet
     * path: one tx/rx pair per direction, IV carried from the last ciphertext
     * block) at 60/500/1500 B, plus a rekey per measured run. Anchored to
     * `reference.json` so any wire-format drift fails before measuring.
     */
    @Test
    fun cryptoChaining() {
        benchHeader("cryptoChaining")
        // Wire-format anchor: first vectors must still encrypt byte-identically.
        val vectors = fixture("reference.json").getJSONArray("channel")
        val anchorTx = CChannel(ByteArray(32) { it.toByte() })
        val anchorRx = CChannel(ByteArray(32) { it.toByte() })
        for (i in 0 until minOf(3, vectors.length())) {
            val r = vectors.getJSONObject(i)
            assertArrayEquals(r.hex("ct"), anchorTx.encrypt(r.hex("pt")))
            assertArrayEquals(r.hex("pt"), anchorRx.decrypt(r.hex("ct")))
        }

        for (size in intArrayOf(60, 500, 1500)) {
            val key = ByteArray(32) { (it * 7 + 1).toByte() }
            val tx = CChannel(key)
            val rx = CChannel(key)
            val data = payload(size)
            val next = ByteArray(32) { (it * 13 + 5).toByte() }
            val iters = 20_000
            // One op = encrypt+decrypt of `size` plaintext bytes each way.
            measure("cryptoChaining size=${size}B enc+dec", iters, 2L * size) { i ->
                if (i == iters / 2) {
                    tx.rekey(next)
                    rx.rekey(next)
                }
                val pt = rx.decrypt(tx.encrypt(data))
                check(pt.contentEquals(data)) { "roundtrip mismatch at size $size iter $i" }
            }
        }
    }

    // ---------------------------------------------------------------- bridge

    private class CountingPeer(override val rid: Long, override val vip: Int, override val mac: ByteArray) : BridgePeer {
        var frames = 0L
        override fun enqueue(frame: ByteArray): Boolean {
            frames++
            return true
        }
    }

    private fun ipv4Packet(src: Int, dst: Int, payloadSize: Int): ByteArray {
        val header = byteArrayOf(0x45, 0) + beU32((20 + payloadSize).toLong()).copyOfRange(2, 4) +
            byteArrayOf(0, 0, 0, 0, 64, 17, 0, 0) + Ip.bytes(src) + Ip.bytes(dst)
        val c = Tunnel.checksum(header)
        header[10] = (c ushr 8).toByte()
        header[11] = c.toByte()
        return header + payload(payloadSize)
    }

    private val own = Ip.parse("26.0.0.100")

    /** The same 61-peer roster as `EnginePeerTest.aLargeRosterIsDialedConcurrentlyInsteadOfAFewAtATime`. */
    private fun roster(): List<Pair<Long, Int>> = (301L..361L).map { rid -> rid to Ip.parse("26.0.1.${(rid - 300).toInt()}") }

    /**
     * L3Bridge outbound with a 61-peer roster: unicast routing plus
     * subnet-broadcast fan-out to every peer (peers only count frames).
     */
    @Test
    fun l3BridgeForward() {
        benchHeader("l3BridgeForward")
        val peers = roster().map { (rid, vip) -> CountingPeer(rid, vip, Protocol.macForIp(vip)) }
        assertEquals(61, peers.size)
        val bridge = L3Bridge({ own }, { peers })
        val target = peers[30]

        val unicast = ipv4Packet(own, target.vip, 100)
        val broadcast = ipv4Packet(own, Ip.parse("26.255.255.255"), 100)
        measure("l3BridgeForward unicast", 200_000, unicast.size.toLong()) {
            check(bridge.outbound(unicast) == L3Bridge.Result.SENT)
        }
        measure("l3BridgeForward broadcast fan-out x61", 50_000, broadcast.size.toLong()) {
            check(bridge.outbound(broadcast) == L3Bridge.Result.SENT)
        }
        assertTrue(peers.all { it.frames > 0 })
    }

    /** A connected peer doing the engine's per-frame work: writeLoop encode+encrypt, remote decrypt+decode+validate+inbound. */
    private class ChannelPeer(override val rid: Long, override val vip: Int, val ownVip: Int, key: ByteArray) : BridgePeer {
        override val mac: ByteArray = Protocol.macForIp(vip)
        private val ownMac = Protocol.macForIp(ownVip)
        private val tx = CChannel(key)
        private val rx = CChannel(key)
        var delivered = 0L

        /** Unicast (and the whole 1.5.0 writeLoop): the peer encodes and seals the frame itself. */
        override fun enqueue(frame: ByteArray): Boolean = receive(tx.encrypt(Tunnel.encode(frame)))

        /** Current broadcast writeLoop ([PeerChannel.sendFrame]): the bridge's frame is sealed once for all peers. */
        override fun enqueue(frame: OutFrame): Boolean = receive(tx.encryptSealed(frame.sealed()))

        private fun receive(record: ByteArray): Boolean {
            // Remote side, from its point of view: we are the peer at ownVip.
            val packet = Tunnel.decode(rx.decrypt(record)) as Tunnel.Packet.Frames
            for (f in packet.frames) {
                check(Tunnel.validFrame(f, ownVip, ownMac, vip, mac))
                delivered++
            }
            return true
        }
    }

    /**
     * Full CPU packet path for a 61-peer roster, TUN to wire to the remote TUN:
     * bridge routing, Ethernet framing, envelope, chained CChannel per peer,
     * then decrypt, decode, frame validation and L3 delivery on the far side.
     */
    @Test
    fun fullPathFanout() {
        benchHeader("fullPathFanout")
        val peers = roster().map { (rid, vip) -> ChannelPeer(rid, vip, own, ByteArray(32) { (it + rid).toByte() }) }
        val bridge = L3Bridge({ own }, { peers })
        val remoteBridge = L3Bridge({ peers[30].vip }, { emptyList() })
        val sender = CountingPeer(1, own, Protocol.macForIp(own))
        for (size in intArrayOf(60, 500, 1400)) {
            val unicast = ipv4Packet(own, peers[30].vip, size - 20)
            val frame = L3Bridge.frame(peers[30].mac, Protocol.macForIp(own), unicast)
            measure("fullPath unicast ${size}B", 50_000, size.toLong()) {
                check(bridge.outbound(unicast) == L3Bridge.Result.SENT)
                check(remoteBridge.inbound(sender, frame) is L3Bridge.Inbound.Deliver)
            }
        }
        val broadcast = ipv4Packet(own, Ip.parse("26.255.255.255"), 480)
        measure("fullPath broadcast fan-out x61 500B", 2_000, 500L * 61) {
            check(bridge.outbound(broadcast) == L3Bridge.Result.SENT)
        }
        assertTrue(peers.all { it.delivered > 0 })
    }

    // ---------------------------------------------------------------- peer selection

    /**
     * Engine per-packet peer selection for a 61-peer roster in two networks: the
     * 1.5.0 filter ran [Membership.permits] for every connected worker on every TUN
     * packet and every received frame; the engine now caches the permitted set per
     * [Membership.version]. Both variants run here on the same build (supplementary;
     * not part of the 1.5.0 baseline comparison).
     */
    @Test
    fun peerSelection() {
        benchHeader("peerSelection")
        val m = Membership(1)
        for (net in 1..2) {
            val id = ByteArray(16) { net.toByte() }
            val records = ArrayList<ByteArray>()
            records.add(Tlv.tlv(0x1315, Tlv.text(0x03000306, "net$net") + Tlv.tlv(0x0d000309, id) + Tlv.u32(0x0100030a, 1)))
            for ((rid, vip) in roster()) {
                records.add(
                    Tlv.tlv(
                        0x1317,
                        concat(
                            Tlv.u64(0x020001e1, rid), Tlv.text(0x03000304, "peer$rid"), Tlv.u32(0x01000305, vip.toLong() and 0xffffffffL),
                            Tlv.text(0x030001c9, "192.0.2.1"), Tlv.u32(0x010003a0, 1),
                        ),
                    ),
                )
                records.add(Tlv.tlv(0x1318, Tlv.u64(0x020001e1, rid) + Tlv.tlv(0x0d000309, id) + Tlv.u32(0x0100030a, 1)))
            }
            m.snapshot(Tlv.tlv(0x1316, concat(*records.toTypedArray())))
        }
        val workers = roster().map { (rid, vip) -> CountingPeer(rid, vip, Protocol.macForIp(vip)) }
        val lock = Any()
        assertEquals(61, workers.count { m.permits(it.rid) })
        measure("peerSelection x61 permits per packet (1.5.0)", 50_000, 0) {
            check(synchronized(lock) { workers.filter { it.frames >= 0 && m.permits(it.rid) } }.size == 61)
        }
        var cachedVersion = -1L
        var permitted: List<CountingPeer> = emptyList()
        measure("peerSelection x61 version-cached (current)", 50_000, 0) {
            check(
                synchronized(lock) {
                    if (m.version != cachedVersion) {
                        val rids = m.peers.keys.filterTo(HashSet()) { m.permits(it) }
                        permitted = workers.filter { it.rid in rids }
                        cachedVersion = m.version
                    }
                    permitted.filter { it.frames >= 0 }
                }.size == 61,
            )
        }
    }

    // ---------------------------------------------------------------- loopback

    private val dialerVip = Ip.parse("26.0.0.2")
    private val listenerVip = Ip.parse("26.0.0.3")
    private val benchPassword = "benchmark-password".toByteArray()

    private fun pair(): Pair<PeerChannel, PeerChannel> {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val incoming = AtomicReference<Result<PeerChannel>>()
        val t = thread {
            incoming.set(
                runCatching {
                    PeerChannel(Framed(server.accept()), 200, listenerVip, 100, benchPassword, true, null, TransportPath.DIRECT_TCP, 10_000)
                },
            )
        }
        try {
            val stream = Framed.connect("127.0.0.1", server.localPort, 5000, null, null)
            val outgoing = PeerChannel(stream, 100, dialerVip, 200, benchPassword, false, null, TransportPath.DIRECT_TCP, 10_000)
            t.join(15_000)
            return outgoing to incoming.get().getOrThrow()
        } finally {
            t.join(15_000)
            server.close()
        }
    }

    /**
     * Full peer packet path over TCP loopback (SH-authenticated CChannel +
     * tunnel envelopes, same [PeerChannel] the engine uses): keepalive
     * roundtrips with mean RTT, plus one-way frame throughput at 100 B and
     * 1400 B IPv4 payloads.
     */
    @Test
    fun peerFanout() {
        benchHeader("peerFanout")
        val (dialer, listener) = pair()
        try {
            // Responder: answer keepalive probes like Engine.Peer's readLoop does.
            val stop = AtomicBoolean(false)
            val responder = thread(isDaemon = true) {
                runCatching {
                    while (!stop.get()) {
                        val data = listener.poll(500) ?: continue
                        val packet = Tunnel.decode(data)
                        if (packet is Tunnel.Packet.Keepalive && !packet.reply) {
                            check(listener.send(Tunnel.keepalive(packet.sequence, true)))
                        }
                    }
                }
            }

            var seq = 0L
            measure("peerFanout keepalive roundtrip", 2_000, 6, warmup = 4_000) {
                val expect = ++seq
                check(dialer.send(Tunnel.keepalive(expect, false)))
                val deadline = now() + 5000
                while (true) {
                    val data = dialer.poll(500) ?: if (now() >= deadline) throw AssertionError("keepalive reply timeout seq=$expect") else continue
                    val packet = Tunnel.decode(data)
                    if (packet is Tunnel.Packet.Keepalive && packet.reply && packet.sequence == expect) break
                }
            }
            stop.set(true)
            responder.join(3000)

            // One-way: dialer -> listener frames through Tunnel.encode, as writeLoop sends;
            // the receiver decodes each record like readLoop does.
            val received = LinkedBlockingQueue<Int>()
            val readerStop = AtomicBoolean(false)
            val reader = thread(isDaemon = true) {
                runCatching {
                    while (!readerStop.get()) {
                        val data = listener.poll(500) ?: continue
                        val packet = Tunnel.decode(data) as Tunnel.Packet.Frames
                        received.add(packet.frames.size)
                    }
                }
            }
            for (size in intArrayOf(100, 1400)) {
                val frame = L3Bridge.frame(Protocol.macForIp(listenerVip), Protocol.macForIp(dialerVip), ipv4Packet(dialerVip, listenerVip, size))
                val batch = 1_000
                fun drain() {
                    var got = 0
                    while (got < batch) {
                        got += received.poll(10, TimeUnit.SECONDS) ?: error("frame $got/$batch not received")
                    }
                }
                // Per-record sends, as the 1.5.0 writeLoop (and UDP paths) send.
                measure("peerFanout one-way frames ${size}B dialer->listener (x$batch)", 20, batch.toLong() * frame.size, warmup = 20) {
                    repeat(batch) { check(dialer.send(Tunnel.encode(frame))) }
                    drain()
                }
                // The current TCP writeLoop: bridge OutFrames, up to 32 queued frames per write.
                val frames = List(batch) { OutFrame(frame) } // unicast: not shared
                measure("peerFanout writeLoop frames ${size}B dialer->listener (x$batch)", 20, batch.toLong() * frame.size, warmup = 20) {
                    for (from in 0 until batch step 32) dialer.sendFrames(frames.subList(from, minOf(batch, from + 32)))
                    drain()
                }
            }
            readerStop.set(true)
            reader.join(3000)
        } finally {
            dialer.close()
            listener.close()
        }
    }

    companion object {
        const val REPS = 5
    }
}
