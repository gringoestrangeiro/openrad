package com.openrad.mobile.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Two real engines connected through a synthetic coordinator: connection offers
 * (op 11), TCP candidates (op 2 → op 6), UDP candidates (op 28 → op 29), relay
 * tickets (op 22 → op 23) and a byte-piping relay. Verifies the full
 * outgoing/incoming channel setup and the L3 path: an IPv4 packet from A's TUN
 * arrives at B's TUN, plus correlated RTT; and the Wi-Fi-only direct policy.
 */
class EnginePeerTest {
    private val p = BigInteger.ONE.shiftLeft(521).subtract(BigInteger.ONE)
    private val q = BigInteger.ONE.shiftLeft(607).subtract(BigInteger.ONE)
    private val n = p.multiply(q)
    private val d = BigInteger.valueOf(65537).modInverse(p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE)))
    private val network = ByteArray(16) { 0x44 }
    private val sockets = Collections.synchronizedList(ArrayList<Socket>())
    private val engines = ArrayList<Engine>()
    private lateinit var control: ServerSocket
    private lateinit var relay: ServerSocket

    private inner class Session(val framed: Framed, val cc: CChannel, val rid: Long) {
        fun send(data: ByteArray) = synchronized(this) { framed.send(cc.encrypt(data)) }
    }

    private val mains = ConcurrentHashMap<Long, Session>()
    private class Pairing(val cid: Long, val requester: Session, val requesterRid: Long, val target: Long, val password: ByteArray)
    private val pairings = ConcurrentHashMap<Long, Pairing>()
    private val cids = AtomicLong(1000)
    private val tickets = ConcurrentHashMap<String, LinkedBlockingQueue<Socket>>()
    val relayed = AtomicLong()

    private val peers = mapOf(100L to Ip.parse("26.0.0.100"), 200L to Ip.parse("26.0.0.200"))
    /** Members announced to every engine; peers without an engine never answer offers. */
    private var roster: Map<Long, Int> = peers
    private val coordinators = AtomicInteger()
    private val maxCoordinators = AtomicInteger()
    /** Whether the coordinator forwards TCP (op 6) and UDP (op 29) candidates. */
    private var forwardTcp = true
    private var forwardUdp = false
    /** Direct-path requests and advertisements (ops 2, 3, 28) received from any engine. */
    private val directMessages = AtomicInteger()

    private fun start() {
        control = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        relay = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!control.isClosed) {
                val s = runCatching { control.accept() }.getOrNull() ?: break
                sockets.add(s)
                thread(isDaemon = true) { runCatching { serve(Framed(s)) } }
            }
        }
        thread(isDaemon = true) {
            while (!relay.isClosed) {
                val s = runCatching { relay.accept() }.getOrNull() ?: break
                sockets.add(s)
                thread(isDaemon = true) { runCatching { relayConnection(s) } }
            }
        }
    }

    @After
    fun tearDown() {
        engines.forEach { it.stop() }
        control.close()
        relay.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private fun relayConnection(s: Socket) {
        val f = Framed(s)
        val fields = Tlv.parse(f.receive(4096, now() + 5000))
        val ticket = Tlv.unique(fields, 0x090001ca).value.toHex()
        f.send(Tlv.u32(0x010001df, 2))
        val queue = tickets.computeIfAbsent(ticket) { LinkedBlockingQueue() }
        synchronized(queue) {
            val other = queue.poll()
            if (other == null) {
                queue.add(s)
                return
            }
            pipe(s, other)
            pipe(other, s)
        }
    }

    private fun pipe(from: Socket, to: Socket) = thread(isDaemon = true) {
        val buffer = ByteArray(65536)
        runCatching {
            // Framed left a short read timeout on the socket; the pipe must block indefinitely.
            from.soTimeout = 0
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                relayed.addAndGet(count.toLong())
                output.write(buffer, 0, count)
                output.flush()
            }
        }
    }

    private fun serve(f: Framed) {
        val deadline = now() + 10_000
        f.receive(16, deadline)
        val em = bnBytes(unsigned(f.receive(4096, deadline)).modPow(d, n))
        val message = em.copyOfRange((1 until em.size).first { em[it] == 0.toByte() } + 1, em.size)
        val purpose = readLeU32(message, 8).toInt()
        val cc = CChannel(message.copyOfRange(12, 44))
        f.send(byteArrayOf(3, 0, 0, 0))
        val hello = cc.decrypt(f.receive(65536, deadline))
        val ridBytes = Sh.parse(hello, 1, 0x20000000L)[0]
        val rid = readLeU32(ridBytes) or (readLeU32(ridBytes, 4) shl 32)
        val sh = ShServer(rid, "credential-$rid".toByteArray())
        f.send(cc.encrypt(sh.hello(hello)))
        f.send(cc.encrypt(sh.public(cc.decrypt(f.receive(65536, deadline)))))
        val (proof, key) = sh.proof(cc.decrypt(f.receive(65536, deadline)))
        f.send(cc.encrypt(proof))
        cc.rekey(key.copyOfRange(0, 32))
        val login = Tlv.parse(cc.decrypt(f.receive(65536, deadline)))
        val session = Session(f, cc, rid)
        session.send(Tlv.u32(Protocol.SERVER_OP, 21) + Tlv.u32(0x0100025b, 1))
        if (purpose == 5) coordinator(session, Tlv.unique(login, 0x0200030b).u64()) else main(session)
    }

    private fun peerRecord(rid: Long) = concat(
        Tlv.u64(0x020001e1, rid), Tlv.text(0x03000304, "peer-$rid"), Tlv.u32(0x01000305, roster.getValue(rid).toLong() and 0xffffffffL),
        Tlv.text(0x030001c9, "127.0.0.1"), Tlv.u32(0x010003a0, 1),
    )

    private fun main(session: Session) {
        val others = roster.keys.filter { it != session.rid }
        session.send(
            Tlv.u32(Protocol.SERVER_OP, 38) + Tlv.tlv(
                0x1316,
                concat(
                    Tlv.u32(0x01000305, peers.getValue(session.rid).toLong()), Tlv.tlv(0x09000305, ByteArray(16) { 9 }),
                    Tlv.tlv(0x1315, Tlv.tlv(0x0d000309, network) + Tlv.text(0x03000306, "Alpha") + Tlv.u32(0x0100030a, 1)),
                    *others.map { Tlv.tlv(0x1317, peerRecord(it)) }.toTypedArray(),
                    *others.map { Tlv.tlv(0x1318, Tlv.u64(0x020001e1, it) + Tlv.tlv(0x0d000309, network) + Tlv.u32(0x0100030a, 1)) }.toTypedArray(),
                ),
            ),
        )
        mains[session.rid] = session
        while (true) {
            val records = Tlv.parse(session.cc.decrypt(session.framed.receive(Framed.MAX_WIRE, now() + 120_000)))
            val op = Tlv.unique(records, Protocol.CLIENT_OP).u32()
            if (op in DIRECT_OPS) directMessages.incrementAndGet()
            if (op == 2L && forwardTcp) {
                val cid = Tlv.unique(records, 0x020001c1).u64()
                val advertised = Tlv.optional(records, 0x1230) ?: continue
                val pairing = pairings[cid] ?: continue
                pairing.requester.send(
                    Tlv.u32(Protocol.SERVER_OP, 6) + Tlv.u64(0x020001c1, cid) + Tlv.tlv(0x1236, advertised.value),
                )
            }
            if (op == 28L && forwardUdp) {
                val cid = Tlv.unique(records, 0x020001c1).u64()
                pairings[cid]?.requester?.send(udpCandidates(cid, records))
            }
        }
    }

    private fun coordinator(session: Session, target: Long) {
        val open = coordinators.incrementAndGet()
        maxCoordinators.accumulateAndGet(open) { a, b -> maxOf(a, b) }
        try {
            pairPeers(session, target)
        } finally {
            coordinators.decrementAndGet()
        }
    }

    private fun pairPeers(session: Session, target: Long) {
        val cid = cids.incrementAndGet()
        val password = "pair-password-$cid".toByteArray()
        val pairing = Pairing(cid, session, session.rid, target, password)
        pairings[cid] = pairing
        session.send(offer(cid, target, password))
        val waitUntil = now() + 10_000
        while (mains[target] == null && now() < waitUntil) Thread.sleep(10)
        mains[target]?.send(offer(cid, session.rid, password))
        while (true) {
            val records = Tlv.parse(session.cc.decrypt(session.framed.receive(Framed.MAX_WIRE, now() + 120_000)))
            val op = Tlv.unique(records, Protocol.CLIENT_OP).u32()
            if (op in DIRECT_OPS) directMessages.incrementAndGet()
            if (op == 28L && forwardUdp) mains[target]?.send(udpCandidates(cid, records))
            if (op == 22L) {
                val ticket = ByteArray(256) { (cid + it).toByte() }
                val relayOffer = Tlv.u32(Protocol.SERVER_OP, 23) + Tlv.u64(0x020001c1, cid) + Tlv.tlv(
                    0x123f,
                    Tlv.text(0x030001cb, "127.0.0.1") + Tlv.u32(0x010001cc, relay.localPort.toLong()) + Tlv.tlv(0x090001ca, ticket),
                )
                session.send(relayOffer)
                mains[target]?.send(relayOffer)
            }
        }
    }

    /** The other member receives the advertised UDP body unchanged, nonce included. */
    private fun udpCandidates(cid: Long, records: List<Record>) =
        Tlv.u32(Protocol.SERVER_OP, 29) + Tlv.u64(0x020001c1, cid) + Tlv.tlv(0x127c, Tlv.unique(records, 0x127b).value)

    private fun offer(cid: Long, rid: Long, password: ByteArray) = Tlv.u32(Protocol.SERVER_OP, 11) + Tlv.u64(0x020001c1, cid) +
        Tlv.tlv(0x1235, Tlv.u64(0x020001e1, rid) + Tlv.tlv(0x0a0001cd, password))

    private class Capture : EngineListener {
        val packets = LinkedBlockingQueue<ByteArray>()
        override fun onPacket(packet: ByteArray) {
            packets.add(packet)
        }

        override fun onEvent(message: String, error: Boolean) {
            if (System.getProperty("openrad.debug") != null || System.getenv("OPENRAD_DEBUG") != null) println("event: $message")
        }
    }

    private fun engine(rid: Long, forceRelay: Boolean, capture: Capture, policy: NetworkPolicy = NetworkPolicy.UNRESTRICTED): Engine {
        val id = Identity(rid, peers.getValue(rid), "peer-$rid", ByteArray(16) { 9 }, "credential-$rid".toByteArray(), "127.0.0.1")
        val e = Engine(
            EngineConfig(host = "127.0.0.1", port = control.localPort, nodeName = "peer-$rid", forceRelay = forceRelay, modulus = bnBytes(n)),
            MemoryIdentityStore(id),
            capture,
            null,
            policy,
        )
        e.tunAttached = true
        engines.add(e)
        e.start()
        return e
    }

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val until = now() + timeoutMs
        while (!condition()) {
            check(now() < until) { "condition not met: ${engines.map { it.snapshot() }}" }
            Thread.sleep(25)
        }
    }

    private fun udp(src: Int, dst: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(0x45, 0) + beU32((20 + 8 + payload.size).toLong()).copyOfRange(2, 4) +
            byteArrayOf(0, 0, 0, 0, 64, 17, 0, 0) + Ip.bytes(src) + Ip.bytes(dst)
        val c = Tunnel.checksum(header)
        header[10] = (c ushr 8).toByte()
        header[11] = c.toByte()
        val udp = beU32(((40000L shl 16) or 40001L)) + beU32(((8L + payload.size) shl 16)) + payload
        return header + udp
    }

    private fun connected(e: Engine) = e.snapshot().peers.singleOrNull()?.link == LinkState.CONNECTED

    private fun connectAndExchange(
        forceRelay: Boolean,
        policyA: NetworkPolicy = NetworkPolicy.UNRESTRICTED,
        policyB: NetworkPolicy = NetworkPolicy.UNRESTRICTED,
    ): TransportPath {
        start()
        val ca = Capture()
        val cb = Capture()
        val a = engine(100, forceRelay, ca, policyA)
        val b = engine(200, forceRelay, cb, policyB)
        waitFor { connected(a) && connected(b) }
        val transport = a.snapshot().peers.single().transport!!
        assertEquals(transport, b.snapshot().peers.single().transport)
        exchange(a, ca, b, cb)
        return transport
    }

    private fun exchange(a: Engine, ca: Capture, b: Engine, cb: Capture) {
        val packet = udp(peers.getValue(100), peers.getValue(200), "hello over openrad".toByteArray())
        assertEquals(L3Bridge.Result.SENT, a.sendIp(packet, packet.size))
        assertArrayEquals(packet, cb.packets.poll(10, TimeUnit.SECONDS))

        val back = udp(peers.getValue(200), Tunnel.SUBNET_BROADCAST, "broadcast".toByteArray())
        assertEquals(L3Bridge.Result.SENT, b.sendIp(back, back.size))
        // Group traffic reaches the TUN as UDP unicast to our VIP (Android LAN discovery delivery).
        val received = ca.packets.poll(10, TimeUnit.SECONDS)!!
        val header = Tunnel.ipv4(received)!!
        assertEquals(peers.getValue(100), header.destination)
        assertEquals(peers.getValue(200), header.source)
        assertArrayEquals(back.copyOfRange(28, back.size), received.copyOfRange(header.headerLength + 8, header.length))

        val rtt = CompletableFuture<CommandResult>()
        a.ping(200) { rtt.complete(it) }
        val result = rtt.get(5, TimeUnit.SECONDS)
        assertTrue("rtt: $result", result is CommandResult.Rtt && result.millis < Engine.PING_TIMEOUT_MS)
        assertTrue(a.snapshot().traffic.sentFrames >= 1)
    }

    @Test
    fun enginesConnectDirectlyOverTcpAndCarryIpv4BothWays() {
        assertEquals(TransportPath.DIRECT_TCP, connectAndExchange(forceRelay = false))
    }

    @Test
    fun aLargeRosterIsDialedConcurrentlyInsteadOfAFewAtATime() {
        // 61 members that never answer (above the old 32-peer cap, like two busy
        // public networks): every setup stays in progress until its deadline.
        roster = peers + (301L..360L).associateWith { Ip.parse("26.0.1.${it - 300}") }
        start()
        val a = engine(100, forceRelay = false, Capture())
        waitFor(10_000) { maxCoordinators.get() >= roster.size - 1 }
        assertEquals(roster.size - 1, a.snapshot().peers.count { it.link == LinkState.CONNECTING })
    }

    @Test
    fun forceRelaySuppressesDirectSetupInBothDirections() {
        forwardUdp = true
        assertEquals(TransportPath.RELAY, connectAndExchange(forceRelay = true))
        assertTrue(relayed.get() > 0)
        assertEquals("no direct candidate requests or UDP advertisements", 0, directMessages.get())
    }

    @Test
    fun enginesConnectOverDirectUdpAndCarryIpv4BothWays() {
        // Without TCP candidates the UDP rendezvous must win well inside the relay head start.
        forwardTcp = false
        forwardUdp = true
        assertEquals(TransportPath.DIRECT_UDP, connectAndExchange(forceRelay = false))
        assertEquals("the direct path never needed the relay", 0L, relayed.get())
    }

    @Test
    fun cellularNetworksAreRelayOnlyInBothDirections() {
        forwardUdp = true
        val cellular = NetworkPolicy { false }
        assertEquals(TransportPath.RELAY, connectAndExchange(forceRelay = false, policyA = cellular, policyB = cellular))
        assertTrue(relayed.get() > 0)
        assertEquals("no direct candidate requests or UDP advertisements", 0, directMessages.get())
    }

    @Test
    fun leavingWifiRetiresTheDirectUdpChannelAndReconnectsOverRelay() {
        forwardTcp = false
        forwardUdp = true
        start()
        val wifi = AtomicBoolean(true)
        val ca = Capture()
        val cb = Capture()
        val a = engine(100, forceRelay = false, ca, policy = NetworkPolicy { wifi.get() })
        val b = engine(200, forceRelay = false, cb)
        fun transport(e: Engine) = e.snapshot().peers.singleOrNull()?.takeIf { it.link == LinkState.CONNECTED }?.transport
        waitFor { transport(a) == TransportPath.DIRECT_UDP && transport(b) == TransportPath.DIRECT_UDP }

        wifi.set(false)
        a.networkChanged()
        // The retired channel tells B at once (ENET disconnect); both converge on the relay.
        waitFor(40_000) { transport(a) == TransportPath.RELAY && transport(b) == TransportPath.RELAY }
        while (ca.packets.poll() != null || cb.packets.poll() != null) Unit
        exchange(a, ca, b, cb)
        assertTrue(relayed.get() > 0)

        // Returning to Wi-Fi keeps the working relay channel instead of churning it.
        wifi.set(true)
        a.networkChanged()
        Thread.sleep(500)
        assertEquals(TransportPath.RELAY, transport(a))
    }

    private companion object {
        val DIRECT_OPS = setOf(2L, 3L, 28L)
    }
}
