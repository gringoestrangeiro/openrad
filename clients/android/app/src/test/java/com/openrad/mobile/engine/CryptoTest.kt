package com.openrad.mobile.engine

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import java.math.BigInteger
import org.junit.Test

internal fun fixture(name: String): JSONObject =
    JSONObject(CryptoTest::class.java.classLoader!!.getResource("fixtures/$name")!!.readText())

internal fun JSONObject.hex(key: String): ByteArray = hexToBytes(getString(key))

/** Synthetic vectors copied verbatim from the Rust repository (`tests/fixtures`). */
class CryptoTest {
    @Test
    fun channelMatchesFixedVectorsAcrossLengthsChainingAndRekey() {
        val f = fixture("reference.json")
        val key = ByteArray(32) { it.toByte() }
        val tx = CChannel(key)
        val rx = CChannel(key)
        val vectors = f.getJSONArray("channel")
        for (i in 0 until vectors.length()) {
            val r = vectors.getJSONObject(i)
            assertArrayEquals(r.hex("ct"), tx.encrypt(r.hex("pt")))
            assertArrayEquals(r.hex("pt"), rx.decrypt(r.hex("ct")))
        }
        val next = ByteArray(32) { (32 + it).toByte() }
        tx.rekey(next)
        rx.rekey(next)
        val rekey = f.getJSONObject("rekey")
        assertArrayEquals(rekey.hex("ct"), tx.encrypt(rekey.hex("pt")))
        assertArrayEquals(rekey.hex("pt"), rx.decrypt(rekey.hex("ct")))
    }

    @Test
    fun channelRejectsTamperAndConsumesCiphertextIv() {
        val tx = CChannel(ByteArray(32))
        val rx = CChannel(ByteArray(32))
        val bad = tx.encrypt("authenticated message long enough".toByteArray())
        bad[0] = (bad[0].toInt() xor 1).toByte()
        assertThrows(ProtocolException::class.java) { rx.decrypt(bad) }
        assertArrayEquals("next".toByteArray(), rx.decrypt(tx.encrypt("next".toByteArray())))
        assertThrows(ProtocolException::class.java) { rx.decrypt(ByteArray(15)) }
        assertThrows(ProtocolException::class.java) { tx.encrypt(ByteArray(0)) }
    }

    @Test
    fun md4MatchesRfc1320Vectors() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", Md4.digest(ByteArray(0)).toHex())
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", Md4.digest("abc".toByteArray()).toHex())
        assertEquals(
            "e33b4ddc9c38f2199c3e7b164fcc0536",
            Md4.digest("12345678901234567890123456789012345678901234567890123456789012345678901234567890".toByteArray()).toHex(),
        )
    }

    @Test
    fun shClientMatchesFixedResponderVectorsAndRejectsWrongConfirmation() {
        val s = fixture("reference.json").getJSONObject("sh")
        val m = s.getJSONArray("messages").let { a -> List(a.length()) { hexToBytes(a.getString(it)) } }
        val wrong = ShClient(12345, "controlled-test-password".toByteArray(), BigInteger.valueOf(2000))
        wrong.start()
        wrong.receiveF10(m[1])
        wrong.receiveF12(m[3])
        assertThrows(ProtocolException::class.java) { wrong.receiveF14(Sh.message(6, 0x70000000L, ByteArray(20))) }

        val c = ShClient(s.getLong("rid"), s.getString("password").toByteArray(), BigInteger.valueOf(s.getLong("private")))
        assertArrayEquals(m[0], c.start())
        assertArrayEquals(m[2], c.receiveF10(m[1]))
        assertArrayEquals(m[4], c.receiveF12(m[3]))
        assertEquals(s.getString("key"), c.receiveF14(m[5]).toHex())
        assertThrows(ProtocolException::class.java) { c.receiveF14(m[5]) }
    }

    @Test
    fun shClientRejectsWrongGroupStateAndTrailingData() {
        val m = fixture("reference.json").getJSONObject("sh").getJSONArray("messages")
            .let { a -> List(a.length()) { hexToBytes(a.getString(it)) } }
        val c = ShClient(12345, "controlled-test-password".toByteArray(), BigInteger.valueOf(2000))
        assertThrows(ProtocolException::class.java) { c.receiveF10(m[1]) }
        c.start()
        val wrong = m[1].copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
        assertThrows(ProtocolException::class.java) { c.receiveF10(wrong) }
        c.receiveF10(m[1])
        assertThrows(ProtocolException::class.java) { c.receiveF12(Sh.message(4, 0x60000000L, byteArrayOf(0))) }
        assertThrows(ProtocolException::class.java) { c.receiveF12(m[3] + byteArrayOf(0)) }
    }

    @Test
    fun incomingShServerMatchesFixedMessagesAndRejectsImpersonation() {
        val f = fixture("incoming-sh.json")
        val m = f.getJSONArray("messages").let { a -> List(a.length()) { hexToBytes(a.getString(it)) } }
        fun server() = ShServer(12345, "controlled-test-password".toByteArray(), ByteArray(16) { it.toByte() }, BigInteger.valueOf(3000))
        var s = server()
        assertThrows(ProtocolException::class.java) { s.public(m[2]) }
        assertThrows(ProtocolException::class.java) { s.hello(Sh.message(1, 0x20000000L, leU32(12346) + ByteArray(4))) }
        assertArrayEquals(m[1], s.hello(m[0]))
        assertArrayEquals(m[3], s.public(m[2]))
        val (proof, key) = s.proof(m[4])
        assertArrayEquals(m[5], proof)
        assertEquals(f.getString("key"), key.toHex())
        s = server()
        s.hello(m[0])
        s.public(m[2])
        assertThrows(ProtocolException::class.java) { s.proof(Sh.message(5, 0x70000000L, ByteArray(20))) }
    }

    @Test
    fun clientAndServerAgreeOnRandomKeys() {
        val password = "random password".toByteArray()
        val client = ShClient(77, password)
        val server = ShServer(77, password)
        val f12 = server.public(client.receiveF10(server.hello(client.start())))
        val (proof, serverKey) = server.proof(client.receiveF12(f12))
        assertArrayEquals(serverKey, client.receiveF14(proof))
    }

    @Test
    fun rsaRecoversVerifiedHeaderWithIndependentPrivateOperation() {
        val p = BigInteger.ONE.shiftLeft(521).subtract(BigInteger.ONE)
        val q = BigInteger.ONE.shiftLeft(607).subtract(BigInteger.ONE)
        val n = p.multiply(q)
        val phi = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE))
        val d = BigInteger.valueOf(65537).modInverse(phi)
        for (purpose in 3..5) {
            val session = RSession.create(bnBytes(n), purpose)
            val em = byteArrayOf(0) + bnBytes(unsigned(session.f6).modPow(d, n))
            assertEquals(0, em[0].toInt())
            assertEquals(2, em[1].toInt())
            val offset = (2 until em.size).first { em[it] == 0.toByte() }
            val expected = leU32(3) + leU32(0x4b) + leU32(purpose.toLong()) + session.secret
            assertArrayEquals(expected, em.copyOfRange(offset + 1, em.size))
        }
    }

    @Test
    fun productionModulusAcceptsAllPurposes() {
        for (purpose in 3..5) assertEquals(192, RSession.create(Protocol.SERVER_MODULUS, purpose).f6.size)
    }
}
