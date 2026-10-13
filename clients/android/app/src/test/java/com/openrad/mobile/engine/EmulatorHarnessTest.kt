package com.openrad.mobile.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Manual end-to-end check on an emulator, skipped by default (like the Rust
 * ignored `linux_interface` test). Run with:
 *
 *   OPENRAD_EMULATOR=1 ./gradlew :app:testDebugUnitTest --tests '*EmulatorHarnessTest*' --rerun -i
 *
 * It starts a synthetic coordinator + relay on 127.0.0.1:47301/47302 (the
 * emulator reaches them as 10.0.2.2), writes `build/emulator-endpoint.json` for
 * the debug app, and runs a second engine (rid 200, 26.0.0.200) on the host.
 * The phone registers (rid 300, 26.0.0.30), connects through the relay, and the
 * host sends ICMP echo to the phone VIP, which the Android kernel answers via TUN.
 * The host answers echo requests too, so `adb shell ping 26.0.0.200` works.
 */
class EmulatorHarnessTest {
    private val p = BigInteger.ONE.shiftLeft(521).subtract(BigInteger.ONE)
    private val q = BigInteger.ONE.shiftLeft(607).subtract(BigInteger.ONE)
    private val n = p.multiply(q)
    private val d = BigInteger.valueOf(65537).modInverse(p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE)))
    private val network = ByteArray(16) { 0x55 }
    private val hostRid = 200L
    private val phoneRid = 300L
    private val vips = mapOf(hostRid to Ip.parse("26.0.0.200"), phoneRid to Ip.parse("26.0.0.30"))
    private fun address(rid: Long) = if (rid == phoneRid) "10.0.2.2" else "127.0.0.1"

    private inner class Session(val framed: Framed, val cc: CChannel, val rid: Long) {
        fun send(data: ByteArray) = synchronized(this) { framed.send(cc.encrypt(data)) }
        fun receive() = Tlv.parse(cc.decrypt(framed.receive(Framed.MAX_WIRE, now() + 600_000)))
    }

    private val mains = ConcurrentHashMap<Long, Session>()
    private val requesters = ConcurrentHashMap<Long, Session>()
    private val cids = AtomicLong(5000)
    private val tickets = ConcurrentHashMap<String, LinkedBlockingQueue<Socket>>()
    private lateinit var relay: ServerSocket

    private fun log(message: String) = println("harness: $message")

    @Test
    fun phoneConnectsThroughRelayAndAnswersIcmpViaTun() {
        assumeTrue(System.getenv("OPENRAD_EMULATOR") != null)
        val control = ServerSocket(47301, 16, InetAddress.getByName("127.0.0.1"))
        relay = ServerSocket(47302, 16, InetAddress.getByName("127.0.0.1"))
        File("build/emulator-endpoint.json").writeText(
            """{"host":"10.0.2.2","port":47301,"modulus":"${bnBytes(n).toHex()}"}""",
        )
        accept(control) { serve(Framed(it)) }
        accept(relay) { relayConnection(it) }

        val packets = LinkedBlockingQueue<ByteArray>()
        lateinit var host: Engine
        val listener = object : EngineListener {
            override fun onEvent(message: String, error: Boolean) = log("host event: $message")
            override fun onPacket(packet: ByteArray) {
                val ip = Tunnel.ipv4(packet) ?: return
                if (ip.protocol == 1 && packet[ip.headerLength] == 8.toByte()) {
                    log("echo request from ${Ip.format(ip.source)} → replying")
                    host.sendIp(echo(packet, ip, reply = true), packet.size)
                } else {
                    if (ip.protocol == 17 && packet.size >= ip.headerLength + 8) {
                        val text = String(packet, ip.headerLength + 8, ip.length - ip.headerLength - 8, Charsets.UTF_8)
                        log("udp from phone → ${Ip.format(ip.destination)}:${readBeU16(packet, ip.headerLength + 2)} \"${text.take(60)}\"")
                    } else if (ip.protocol == 1 && Ip.isMulticast(ip.destination) || ip.destination == -1) {
                        log("icmp from phone → ${Ip.format(ip.destination)}")
                    }
                    packets.add(packet)
                }
            }
        }
        host = Engine(
            EngineConfig(host = "127.0.0.1", port = 47301, nodeName = "host-peer", modulus = bnBytes(n)),
            MemoryIdentityStore(Identity(hostRid, vips.getValue(hostRid), "host-peer", ByteArray(16) { 2 }, "credential-$hostRid".toByteArray(), "127.0.0.1")),
            listener,
        )
        host.tunAttached = true
        host.start()
        log("READY — push build/emulator-endpoint.json to the app and tap Connect")
        val until = now() + 300_000
        while (host.snapshot().peers.none { it.rid == phoneRid && it.link == LinkState.CONNECTED }) {
            check(now() < until) { "phone did not connect: ${host.snapshot()}" }
            Thread.sleep(200)
        }
        log("phone connected via ${host.snapshot().peers.first { it.rid == phoneRid }.transport}")
        var replies = 0
        for (seq in 1..5) {
            val request = icmpRequest(vips.getValue(hostRid), vips.getValue(phoneRid), seq)
            val started = System.nanoTime()
            log("ping 26.0.0.30 seq=$seq → ${host.sendIp(request, request.size)}")
            val reply = packets.poll(3, TimeUnit.SECONDS)
            if (reply != null) {
                val ip = Tunnel.ipv4(reply)!!
                if (ip.protocol == 1 && reply[ip.headerLength] == 0.toByte()) {
                    replies++
                    log("echo reply from ${Ip.format(ip.source)} seq=$seq in ${(System.nanoTime() - started) / 1_000_000.0} ms")
                }
            }
        }
        check(replies >= 4) { "only $replies/5 ICMP replies from the phone" }
        log("PASS: $replies/5 ICMP replies through the Android TUN; announcing a LAN world for 120 s")
        val announce = "[MOTD]Harness world[/MOTD][AD]25565[/AD]".toByteArray()
        val stopAt = now() + 120_000
        while (now() < stopAt) {
            for ((group, port) in listOf(Ip.parse("224.0.2.60") to 4445, -1 to 19132)) {
                val udp = udp(vips.getValue(hostRid), group, 40000, port, announce)
                host.sendIp(udp, udp.size)
            }
            Thread.sleep(1500)
        }
        host.stop()
        control.close()
        relay.close()
    }

    private fun accept(server: ServerSocket, handler: (Socket) -> Unit) = thread(isDaemon = true) {
        while (!server.isClosed) {
            val s = runCatching { server.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { runCatching { handler(s) }.onFailure { log("connection ended: ${it.message}") } }
        }
    }

    private fun icmpRequest(src: Int, dst: Int, seq: Int): ByteArray {
        val payload = "openrad-harness-$seq".toByteArray()
        val icmp = byteArrayOf(8, 0, 0, 0, 0x12, 0x34, 0, seq.toByte()) + payload
        val c = Tunnel.checksum(icmp)
        icmp[2] = (c ushr 8).toByte()
        icmp[3] = c.toByte()
        return ipHeader(src, dst, icmp.size) + icmp
    }

    private fun echo(packet: ByteArray, ip: Tunnel.Ipv4, reply: Boolean): ByteArray {
        val icmp = packet.copyOfRange(ip.headerLength, ip.length)
        icmp[0] = if (reply) 0 else 8
        icmp[2] = 0
        icmp[3] = 0
        val c = Tunnel.checksum(icmp)
        icmp[2] = (c ushr 8).toByte()
        icmp[3] = c.toByte()
        return ipHeader(ip.destination, ip.source, icmp.size) + icmp
    }

    private fun udp(src: Int, dst: Int, sport: Int, dport: Int, data: ByteArray): ByteArray {
        val body = byteArrayOf((sport ushr 8).toByte(), sport.toByte(), (dport ushr 8).toByte(), dport.toByte()) +
            beU32(((8L + data.size) shl 16)) + data
        val c = Tunnel.checksum(Ip.bytes(src) + Ip.bytes(dst) + byteArrayOf(0, 17, 0, body.size.toByte()) + body)
        body[6] = (c ushr 8).toByte()
        body[7] = c.toByte()
        return ipHeader(src, dst, body.size, 17) + body
    }

    private fun ipHeader(src: Int, dst: Int, payload: Int, protocol: Int = 1): ByteArray {
        val h = byteArrayOf(0x45, 0) + beU32((20 + payload).toLong()).copyOfRange(2, 4) +
            byteArrayOf(0, 0, 0, 0, 64, protocol.toByte(), 0, 0) + Ip.bytes(src) + Ip.bytes(dst)
        val c = Tunnel.checksum(h)
        h[10] = (c ushr 8).toByte()
        h[11] = c.toByte()
        return h
    }

    private fun relayConnection(s: Socket) {
        val f = Framed(s)
        val ticket = Tlv.unique(Tlv.parse(f.receive(4096, now() + 10_000)), 0x090001ca).value.toHex()
        f.send(Tlv.u32(0x010001df, 2))
        s.soTimeout = 0
        val queue = tickets.computeIfAbsent(ticket) { LinkedBlockingQueue() }
        synchronized(queue) {
            val other = queue.poll() ?: return queue.add(s).let { }
            log("relay paired ticket ${ticket.take(8)}")
            pipe(s, other)
            pipe(other, s)
        }
    }

    private fun pipe(from: Socket, to: Socket) = thread(isDaemon = true) {
        val buffer = ByteArray(65536)
        runCatching {
            from.soTimeout = 0
            while (true) {
                val count = from.getInputStream().read(buffer)
                if (count < 0) break
                to.getOutputStream().write(buffer, 0, count)
            }
        }
    }

    private fun serve(f: Framed) {
        val deadline = now() + 20_000
        f.receive(16, deadline)
        val em = bnBytes(unsigned(f.receive(4096, deadline)).modPow(d, n))
        val message = em.copyOfRange((1 until em.size).first { em[it] == 0.toByte() } + 1, em.size)
        val purpose = readLeU32(message, 8).toInt()
        val cc = CChannel(message.copyOfRange(12, 44))
        f.send(byteArrayOf(3, 0, 0, 0))
        val hello = cc.decrypt(f.receive(65536, deadline))
        val ridBytes = Sh.parse(hello, 1, 0x20000000L)[0]
        val rid = readLeU32(ridBytes) or (readLeU32(ridBytes, 4) shl 32)
        val sh = ShServer(rid, if (purpose == 3) "rpassword".toByteArray() else "credential-$rid".toByteArray())
        f.send(cc.encrypt(sh.hello(hello)))
        f.send(cc.encrypt(sh.public(cc.decrypt(f.receive(65536, deadline)))))
        val (proof, key) = sh.proof(cc.decrypt(f.receive(65536, deadline)))
        f.send(cc.encrypt(proof))
        cc.rekey(key.copyOfRange(0, 32))
        val session = Session(f, cc, rid)
        val login = session.receive()
        log("login purpose=$purpose rid=$rid")
        session.send(Tlv.u32(Protocol.SERVER_OP, 21) + Tlv.u32(0x0100025b, 1))
        when (purpose) {
            3 -> session.send(
                Tlv.u32(Protocol.SERVER_OP, 8) + Tlv.tlv(
                    0x1233,
                    concat(
                        Tlv.u64(0x020001e1, phoneRid), Tlv.u32(0x01000305, vips.getValue(phoneRid).toLong() and 0xffffffffL),
                        Tlv.text(0x03000304, "openrad-android"), Tlv.tlv(0x09000305, ByteArray(16) { 3 }),
                        Tlv.tlv(0x0a0001c8, "credential-$phoneRid".toByteArray()), Tlv.text(0x030001e2, "10.0.2.2"),
                    ),
                ),
            )
            5 -> coordinator(session, Tlv.unique(login, 0x0200030b).u64())
            else -> main(session)
        }
    }

    private fun main(session: Session) {
        val other = vips.keys.first { it != session.rid }
        // Each side sees the coordinator at the address it can reach.
        val withServer = concat(
            Tlv.u64(0x020001e1, other), Tlv.text(0x03000304, if (other == phoneRid) "openrad-android" else "host-peer"),
            Tlv.u32(0x01000305, vips.getValue(other).toLong() and 0xffffffffL), Tlv.text(0x030001c9, address(session.rid)),
            Tlv.u32(0x010003a0, 1),
        )
        session.send(
            Tlv.u32(Protocol.SERVER_OP, 38) + Tlv.tlv(
                0x1316,
                concat(
                    Tlv.u32(0x01000305, vips.getValue(session.rid).toLong() and 0xffffffffL), Tlv.tlv(0x09000305, ByteArray(16) { 9 }),
                    Tlv.tlv(0x1315, Tlv.tlv(0x0d000309, network) + Tlv.text(0x03000306, "Harness LAN") + Tlv.u32(0x0100030a, 1)),
                    Tlv.tlv(0x1317, withServer),
                    Tlv.tlv(0x1318, Tlv.u64(0x020001e1, other) + Tlv.tlv(0x0d000309, network) + Tlv.u32(0x0100030a, 1)),
                ),
            ),
        )
        mains[session.rid] = session
        while (true) {
            val records = session.receive()
            if (Tlv.unique(records, Protocol.CLIENT_OP).u32() == 2L) {
                val cid = Tlv.unique(records, 0x020001c1).u64()
                val advertised = Tlv.optional(records, 0x1230) ?: continue
                requesters[cid]?.send(Tlv.u32(Protocol.SERVER_OP, 6) + Tlv.u64(0x020001c1, cid) + Tlv.tlv(0x1236, advertised.value))
            }
        }
    }

    private fun coordinator(session: Session, target: Long) {
        val cid = cids.incrementAndGet()
        requesters[cid] = session
        val password = "pair-password-$cid".toByteArray()
        fun offer(rid: Long) = Tlv.u32(Protocol.SERVER_OP, 11) + Tlv.u64(0x020001c1, cid) +
            Tlv.tlv(0x1235, Tlv.u64(0x020001e1, rid) + Tlv.tlv(0x0a0001cd, password))
        session.send(offer(target))
        mains[target]?.send(offer(session.rid))
        while (true) {
            val records = session.receive()
            if (Tlv.unique(records, Protocol.CLIENT_OP).u32() == 22L) {
                val ticket = ByteArray(256) { (cid + it).toByte() }
                fun relayOffer(rid: Long) = Tlv.u32(Protocol.SERVER_OP, 23) + Tlv.u64(0x020001c1, cid) + Tlv.tlv(
                    0x123f,
                    Tlv.text(0x030001cb, address(rid)) + Tlv.u32(0x010001cc, 47302) + Tlv.tlv(0x090001ca, ticket),
                )
                session.send(relayOffer(session.rid))
                mains[target]?.send(relayOffer(target))
            }
        }
    }
}
