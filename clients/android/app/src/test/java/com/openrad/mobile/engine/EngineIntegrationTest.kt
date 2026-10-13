package com.openrad.mobile.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Drives the real [Engine] against a synthetic local coordination server that
 * owns a test RSA key (two Mersenne primes): provisioning, login, VIP, catalog
 * search, public join and private-join SH. No real service is contacted.
 */
class EngineIntegrationTest {
    private val p = BigInteger.ONE.shiftLeft(521).subtract(BigInteger.ONE)
    private val q = BigInteger.ONE.shiftLeft(607).subtract(BigInteger.ONE)
    private val n = p.multiply(q)
    private val d = BigInteger.valueOf(65537).modInverse(p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE)))
    private val credential = "synthetic-credential".toByteArray()
    private val alpha = ByteArray(16) { 0x11 }
    private val beta = ByteArray(16) { 0x22 }
    private val secret = ByteArray(16) { 0x33 }
    private lateinit var server: ServerSocket
    private val logins = Collections.synchronizedList(ArrayList<Int>())
    private val sockets = Collections.synchronizedList(ArrayList<Socket>())
    private var engine: Engine? = null

    @Before
    fun setUp() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(s)
                thread(isDaemon = true) { runCatching { serve(Framed(s)) } }
            }
        }
    }

    @After
    fun tearDown() {
        engine?.stop()
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private fun send(f: Framed, cc: CChannel, data: ByteArray) = f.send(cc.encrypt(data))

    private fun serve(f: Framed) {
        val deadline = now() + 10_000
        val first = f.receive(16, deadline)
        assertTrue(first.contentEquals(byteArrayOf(3, 0, 0, 0)))
        val em = bnBytes(unsigned(f.receive(4096, deadline)).modPow(d, n))
        val start = (1 until em.size).first { em[it] == 0.toByte() } + 1
        val message = em.copyOfRange(start, em.size)
        val purpose = readLeU32(message, 8).toInt()
        val cc = CChannel(message.copyOfRange(12, 44))
        f.send(byteArrayOf(3, 0, 0, 0))
        val hello = cc.decrypt(f.receive(65536, deadline))
        val rid = readLeU32(Sh.parse(hello, 1, 0x20000000L)[0]) or (readLeU32(Sh.parse(hello, 1, 0x20000000L)[0], 4) shl 32)
        val sh = ShServer(rid, if (purpose == 3) "rpassword".toByteArray() else credential)
        send(f, cc, sh.hello(hello))
        send(f, cc, sh.public(cc.decrypt(f.receive(65536, deadline))))
        val (proof, key) = sh.proof(cc.decrypt(f.receive(65536, deadline)))
        send(f, cc, proof)
        cc.rekey(key.copyOfRange(0, 32))
        val login = Tlv.parse(cc.decrypt(f.receive(65536, deadline)))
        assertEquals(20L, Tlv.unique(login, Protocol.CLIENT_OP).u32())
        logins.add(purpose)
        send(f, cc, Tlv.u32(Protocol.SERVER_OP, 21))
        if (purpose == 3) {
            val body = concat(
                Tlv.u64(0x020001e1, 4242), Tlv.u32(0x01000305, 0x1a000009), Tlv.text(0x03000304, "phone"),
                Tlv.tlv(0x09000305, ByteArray(16) { 9 }), Tlv.tlv(0x0a0001c8, credential), Tlv.text(0x030001e2, "127.0.0.1"),
            )
            send(f, cc, Tlv.u32(Protocol.SERVER_OP, 8) + Tlv.tlv(0x1233, body))
            return
        }
        val peer = concat(
            Tlv.u64(0x020001e1, 7), Tlv.text(0x03000304, "desktop"), Tlv.u32(0x01000305, 0x1a000007),
            Tlv.text(0x030001c9, "127.0.0.1"), Tlv.u32(0x010003a0, 0),
        )
        send(
            f, cc,
            Tlv.u32(Protocol.SERVER_OP, 38) + Tlv.tlv(
                0x1316,
                concat(
                    Tlv.u32(0x01000305, 0x1a000009), Tlv.tlv(0x09000305, ByteArray(16) { 9 }),
                    Tlv.tlv(0x1315, Tlv.tlv(0x0d000309, alpha) + Tlv.text(0x03000306, "Alpha") + Tlv.u32(0x0100030a, 2)),
                    Tlv.tlv(0x1317, peer),
                    Tlv.tlv(0x1318, Tlv.u64(0x020001e1, 7) + Tlv.tlv(0x0d000309, alpha) + Tlv.u32(0x0100030a, 1)),
                ),
            ),
        )
        var privateSh: ShServer? = null
        while (true) {
            val records = Tlv.parse(cc.decrypt(f.receive(Framed.MAX_WIRE, now() + 60_000)))
            when (Tlv.unique(records, Protocol.CLIENT_OP).u32()) {
                43L -> {
                    val fields = Tlv.parse(Tlv.unique(records, 0x1332).value)
                    val id = Tlv.unique(fields, 0x02000340).u64()
                    val query = Tlv.decodeText(Tlv.unique(fields, 0x03000306))
                    send(
                        f, cc,
                        Tlv.u32(Protocol.SERVER_OP, 45) + Tlv.tlv(
                            0x1334,
                            concat(
                                Tlv.u64(0x02000340, id), Tlv.u64(0x0200030f, 0),
                                Tlv.tlv(0x1333, Tlv.text(0x03000306, "$query Beta") + Tlv.u32(0x01000341, 5)),
                            ),
                        ),
                    )
                }
                39L -> {
                    val fields = Tlv.parse(Tlv.unique(records, 0x131c).value)
                    val blob = Tlv.optional(fields, 0x0a00030e)?.value
                    val name = Tlv.optional(fields, 0x03000306)?.let { Tlv.decodeText(it) }
                    val id = Tlv.optional(fields, 0x02000340)?.u64()
                    val seq = Tlv.unique(fields, 0x010003be).u32()
                    if (blob == null) {
                        joined(f, cc, id!!, name!!, beta)
                    } else {
                        val reply = when {
                            name != null -> {
                                privateSh = ShServer(Protocol.networkIdentity(name), "network password\u0000".toByteArray())
                                lastPrivate = name to id!!
                                privateStep = 1
                                privateSh!!.hello(blob)
                            }
                            privateStep == 1 -> {
                                privateStep = 2
                                privateSh!!.public(blob)
                            }
                            else -> {
                                privateStep = 3
                                privateSh!!.proof(blob).first
                            }
                        }
                        send(
                            f, cc,
                            Tlv.u32(Protocol.SERVER_OP, 40) + Tlv.tlv(
                                0x131c,
                                concat(Tlv.u64(0x02000303, 0), Tlv.u32(0x010003be, seq), Tlv.tlv(0x0a00030e, reply)),
                            ),
                        )
                        if (privateStep == 3) joined(f, cc, lastPrivate!!.second, lastPrivate!!.first, secret)
                    }
                }
            }
        }
    }

    @Volatile private var privateStep = 0
    @Volatile private var lastPrivate: Pair<String, Long>? = null

    private fun joined(f: Framed, cc: CChannel, id: Long, name: String, guid: ByteArray) {
        send(
            f, cc,
            Tlv.u32(Protocol.SERVER_OP, 37) + Tlv.tlv(
                0x131a,
                concat(
                    Tlv.u32(0x0100030c, 2), Tlv.u64(0x02000340, id),
                    Tlv.tlv(0x1316, Tlv.tlv(0x1315, Tlv.tlv(0x0d000309, guid) + Tlv.text(0x03000306, name) + Tlv.u32(0x0100030a, 1))),
                ),
            ),
        )
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val until = now() + timeoutMs
        while (!condition()) {
            check(now() < until) { "condition not met: ${engine?.snapshot()}" }
            Thread.sleep(20)
        }
    }

    private fun call(block: ((CommandResult) -> Unit) -> Unit): CommandResult {
        val future = CompletableFuture<CommandResult>()
        block { future.complete(it) }
        return future.get(20, TimeUnit.SECONDS)
    }

    @Test
    fun provisionsLogsInSearchesAndJoinsPublicAndPrivateNetworks() {
        val store = MemoryIdentityStore()
        val events = Collections.synchronizedList(ArrayList<String>())
        val e = Engine(
            EngineConfig(host = "127.0.0.1", port = server.localPort, nodeName = "phone", modulus = bnBytes(n)),
            store,
            object : EngineListener {
                override fun onEvent(message: String, error: Boolean) {
                    events.add(message)
                }
            },
        )
        engine = e
        e.start()
        waitFor { e.snapshot().phase == EngineState.Phase.ONLINE }
        assertEquals(listOf(3, 4), logins.toList())
        val state = e.snapshot()
        assertEquals("26.0.0.9", Ip.format(state.vip!!))
        assertEquals(4242L, store.load()!!.rid)
        assertEquals(listOf("Alpha"), state.networks.map { it.name })
        assertEquals(2L, state.networks.single().ownRole)
        assertEquals(LinkState.OFFLINE, state.peers.single().link)

        val listing = call { e.search("Mine", 0, it) } as CommandResult.Listing
        assertEquals(listOf("Mine Beta" to 5L), listing.networks)

        val joined = call { e.join("Beta", null, it) } as CommandResult.Network
        assertEquals("Beta", (joined.outcome as NetworkOperation.Outcome.Joined).network.name)

        val password = "network password".toCharArray()
        val private = call { e.join("Secret", password, it) }
        assertTrue("private join: $private", private is CommandResult.Network)
        assertTrue(password.all { it == '\u0000' })
        waitFor { e.snapshot().networks.map { it.name }.toSet() == setOf("Alpha", "Beta", "Secret") }

        val again = call { e.join("Alpha", null, it) } as CommandResult.Network
        assertEquals(alpha.toHex(), (again.outcome as NetworkOperation.Outcome.Joined).network.networkId)

        val ping = call { e.ping(7, it) }
        assertTrue(ping is CommandResult.Failed)
        e.stop()
        assertEquals(EngineState.Phase.STOPPED, e.snapshot().phase)
        assertTrue(events.any { it.startsWith("Online as 26.0.0.9") })
    }
}
