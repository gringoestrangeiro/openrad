package com.openrad.mobile.engine

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wire-compatible cryptography. Mirrors `crypto.rs` (Channel, rsa_session,
 * ShClient/ShServer) and is checked against the JSON vectors in `tests/fixtures` of the
 * Rust repository in `app/src/test`.
 */
internal val secureRandom = SecureRandom()

fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

fun sha1(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-1")
    for (p in parts) md.update(p)
    return md.digest()
}

/** BN_bn2bin: unsigned, minimal, big-endian. */
fun bnBytes(value: BigInteger): ByteArray {
    ensure(value.signum() >= 0) { "negative bignum" }
    if (value.signum() == 0) return ByteArray(0)
    val raw = value.toByteArray()
    return if (raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
}

fun unsigned(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

private fun leftPad(data: ByteArray, width: Int): ByteArray =
    if (data.size >= width) data else ByteArray(width - data.size) + data

object Md4 {
    private const val H0 = 0x67452301
    private const val H1 = 0xefcdab89.toInt()
    private const val H2 = 0x98badcfe.toInt()
    private const val H3 = 0x10325476

    fun digest(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        var padded = message.size + 1
        while (padded % 64 != 56) padded++
        val msg = ByteArray(padded + 8)
        System.arraycopy(message, 0, msg, 0, message.size)
        msg[message.size] = 0x80.toByte()
        for (i in 0 until 8) msg[padded + i] = (bitLength ushr (8 * i)).toByte()
        val h = intArrayOf(H0, H1, H2, H3)
        var off = 0
        while (off < msg.size) {
            fun x(i: Int) = readLeU32(msg, off + 4 * i).toInt()
            compress(
                h[0], h[1], h[2], h[3],
                x(0), x(1), x(2), x(3), x(4), x(5), x(6), x(7),
                x(8), x(9), x(10), x(11), x(12), x(13), x(14), x(15),
            ) { a, b, c, d ->
                h[0] = a
                h[1] = b
                h[2] = c
                h[3] = d
            }
            off += 64
        }
        return leU32(h[0].toLong()) + leU32(h[1].toLong()) + leU32(h[2].toLong()) + leU32(h[3].toLong())
    }

    /**
     * MD4 of the 8-byte little-endian [word] folded to 8 bytes as `d[i] + d[i + 8]`
     * (the CChannel trailer), returned little-endian. Equals folding [digest]; the
     * single padded block is passed as constants, so nothing is allocated.
     */
    fun foldedWord(word: Long): Long =
        // Padding: 0x80 marker after the 8 message bytes, then the length (64 bits).
        compress(H0, H1, H2, H3, word.toInt(), (word ushr 32).toInt(), 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 64, 0) { a, b, c, d ->
            (addBytes(a, c).toLong() and 0xffffffffL) or (addBytes(b, d).toLong() shl 32)
        }

    /** Byte-wise addition modulo 256 of four packed bytes (no carry between bytes). */
    private fun addBytes(x: Int, y: Int): Int =
        ((x and 0x7f7f7f7f) + (y and 0x7f7f7f7f)) xor ((x xor y) and 0x80808080.toInt())

    private fun r1(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int) =
        Integer.rotateLeft(a + ((b and c) or (b.inv() and d)) + x, s)

    private fun r2(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int) =
        Integer.rotateLeft(a + ((b and c) or (b and d) or (c and d)) + x + 0x5A827999, s)

    private fun r3(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int) =
        Integer.rotateLeft(a + (b xor c xor d) + x + 0x6ED9EBA1, s)

    /** One RFC 1320 block, fully unrolled; [result] receives the chained state. */
    private inline fun <T> compress(
        h0: Int, h1: Int, h2: Int, h3: Int,
        x0: Int, x1: Int, x2: Int, x3: Int, x4: Int, x5: Int, x6: Int, x7: Int,
        x8: Int, x9: Int, x10: Int, x11: Int, x12: Int, x13: Int, x14: Int, x15: Int,
        result: (Int, Int, Int, Int) -> T,
    ): T {
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        a = r1(a, b, c, d, x0, 3); d = r1(d, a, b, c, x1, 7); c = r1(c, d, a, b, x2, 11); b = r1(b, c, d, a, x3, 19)
        a = r1(a, b, c, d, x4, 3); d = r1(d, a, b, c, x5, 7); c = r1(c, d, a, b, x6, 11); b = r1(b, c, d, a, x7, 19)
        a = r1(a, b, c, d, x8, 3); d = r1(d, a, b, c, x9, 7); c = r1(c, d, a, b, x10, 11); b = r1(b, c, d, a, x11, 19)
        a = r1(a, b, c, d, x12, 3); d = r1(d, a, b, c, x13, 7); c = r1(c, d, a, b, x14, 11); b = r1(b, c, d, a, x15, 19)
        a = r2(a, b, c, d, x0, 3); d = r2(d, a, b, c, x4, 5); c = r2(c, d, a, b, x8, 9); b = r2(b, c, d, a, x12, 13)
        a = r2(a, b, c, d, x1, 3); d = r2(d, a, b, c, x5, 5); c = r2(c, d, a, b, x9, 9); b = r2(b, c, d, a, x13, 13)
        a = r2(a, b, c, d, x2, 3); d = r2(d, a, b, c, x6, 5); c = r2(c, d, a, b, x10, 9); b = r2(b, c, d, a, x14, 13)
        a = r2(a, b, c, d, x3, 3); d = r2(d, a, b, c, x7, 5); c = r2(c, d, a, b, x11, 9); b = r2(b, c, d, a, x15, 13)
        a = r3(a, b, c, d, x0, 3); d = r3(d, a, b, c, x8, 9); c = r3(c, d, a, b, x4, 11); b = r3(b, c, d, a, x12, 15)
        a = r3(a, b, c, d, x2, 3); d = r3(d, a, b, c, x10, 9); c = r3(c, d, a, b, x6, 11); b = r3(b, c, d, a, x14, 15)
        a = r3(a, b, c, d, x1, 3); d = r3(d, a, b, c, x9, 9); c = r3(c, d, a, b, x5, 11); b = r3(b, c, d, a, x13, 15)
        a = r3(a, b, c, d, x3, 3); d = r3(d, a, b, c, x11, 9); c = r3(c, d, a, b, x7, 11); b = r3(b, c, d, a, x15, 15)
        return result(h0 + a, h1 + b, h2 + c, h3 + d)
    }
}

/**
 * Vendor CChannel: AES-256-CBC, independent zero IVs per direction chained by the
 * last ciphertext block, 0xcc filler + 8-byte MD4 trailer + pad length byte.
 *
 * The CBC chain lives in the initialised [Cipher]: each record is one `update`
 * continuing from the previous ciphertext block, which is byte-identical to
 * re-initialising with that block as IV. Providers that do not keep the chain
 * across `update` calls (checked once, see [STREAMING]) re-initialise per record.
 * Encryption and decryption are independent and lock separately.
 */
class CChannel internal constructor(key: ByteArray, private val streaming: Boolean) {
    constructor(key: ByteArray) : this(key, STREAMING)

    private val encLock = Any()
    private val decLock = Any()
    private val enc = Cipher.getInstance(TRANSFORMATION)
    private val dec = Cipher.getInstance(TRANSFORMATION)
    private var keyEnc = spec(key)
    private var keyDec = keyEnc
    private val ivEnc = ByteArray(16)
    private val ivDec = ByteArray(16)
    private val encScratch = Scratch()
    private val decScratch = Scratch()

    init {
        if (streaming) {
            enc.init(Cipher.ENCRYPT_MODE, keyEnc, IvParameterSpec(ivEnc))
            dec.init(Cipher.DECRYPT_MODE, keyDec, IvParameterSpec(ivDec))
        }
    }

    /** Rekey both directions; CBC IVs survive (binary setters update schedules only). */
    fun rekey(key: ByteArray) {
        val next = spec(key)
        synchronized(encLock) {
            keyEnc = next
            if (streaming) enc.init(Cipher.ENCRYPT_MODE, next, IvParameterSpec(ivEnc))
        }
        synchronized(decLock) {
            keyDec = next
            if (streaming) dec.init(Cipher.DECRYPT_MODE, next, IvParameterSpec(ivDec))
        }
    }

    fun encrypt(plain: ByteArray): ByteArray {
        ensure(plain.isNotEmpty()) { "CChannel rejects empty plaintext" }
        synchronized(encLock) {
            val total = sealedSize(plain.size)
            val body = encScratch.array(total)
            System.arraycopy(plain, 0, body, 0, plain.size)
            seal(body, encScratch.view, plain.size, total)
            return encryptBody(body, total)
        }
    }

    /**
     * Encrypts a body produced by [seal] (whole blocks, trailer and pad length
     * included); the result equals [encrypt] of the unsealed plaintext. Sealing is
     * key-independent, so one sealed broadcast frame serves every peer channel.
     */
    fun encryptSealed(body: ByteArray): ByteArray {
        ensure(body.size >= 16 && body.size % 16 == 0 && (body[body.size - 1].toInt() and 0xff) in 9..24) {
            "CChannel requires a sealed body"
        }
        synchronized(encLock) { return encryptBody(body, body.size) }
    }

    private fun encryptBody(body: ByteArray, total: Int): ByteArray {
        val out = ByteArray(total)
        if (streaming) {
            ensure(enc.update(body, 0, total, out, 0) == total) { "CChannel cipher withheld blocks" }
        } else {
            enc.init(Cipher.ENCRYPT_MODE, keyEnc, IvParameterSpec(ivEnc))
            enc.doFinal(body, 0, total, out, 0)
        }
        System.arraycopy(out, total - 16, ivEnc, 0, 16)
        return out
    }

    fun decrypt(cipherText: ByteArray): ByteArray {
        ensure(cipherText.isNotEmpty() && cipherText.size % 16 == 0) {
            "CChannel requires nonempty complete ciphertext blocks"
        }
        synchronized(decLock) {
            val size = cipherText.size
            val body = decScratch.array(size)
            if (streaming) {
                ensure(dec.update(cipherText, 0, size, body, 0) == size) { "CChannel cipher withheld blocks" }
            } else {
                dec.init(Cipher.DECRYPT_MODE, keyDec, IvParameterSpec(ivDec))
                dec.doFinal(cipherText, 0, size, body, 0)
            }
            // Decryption precedes verification; a failure still consumes the IV.
            System.arraycopy(cipherText, size - 16, ivDec, 0, 16)
            val padding = body[size - 1].toInt() and 0xff
            ensure(padding in 9..24 && padding < size) { "invalid CChannel padding length" }
            val expected = auth8(body, decScratch.view, size - 9)
            var actual = 0L
            for (i in 7 downTo 0) actual = (actual shl 8) or (body[size - 9 + i].toLong() and 0xff)
            ensure(expected == actual) { "invalid CChannel authentication trailer" }
            return body.copyOfRange(0, size - padding)
        }
    }

    /** Reused plaintext buffer with a little-endian view; oversized records use a one-off array. */
    private class Scratch {
        private var buffer = ByteArray(2048)
        private var bufferView: ByteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        var view: ByteBuffer = bufferView
            private set

        fun array(size: Int): ByteArray {
            if (size <= buffer.size) {
                view = bufferView
                return buffer
            }
            val grown = ByteArray(size)
            val grownView = ByteBuffer.wrap(grown).order(ByteOrder.LITTLE_ENDIAN)
            if (size <= SCRATCH_LIMIT) {
                buffer = grown
                bufferView = grownView
            }
            view = grownView
            return grown
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/CBC/NoPadding"
        private const val SCRATCH_LIMIT = 65536 + 64

        /** Encrypted size of a [length]-byte plaintext: filler, 8-byte trailer and pad byte to whole blocks. */
        fun sealedSize(length: Int): Int = (length + 9 + 15) / 16 * 16

        /**
         * Seals `body[0, length)` in place up to [total] = [sealedSize]: 0xcc filler,
         * MD4 trailer and pad length. [view] is a little-endian view of [body].
         */
        fun seal(body: ByteArray, view: ByteBuffer, length: Int, total: Int) {
            ensure(length > 0) { "CChannel rejects empty plaintext" }
            val regionEnd = total - 9
            Arrays.fill(body, length, regionEnd, 0xcc.toByte())
            val trailer = auth8(body, view, regionEnd)
            for (i in 0 until 8) body[regionEnd + i] = (trailer ushr (8 * i)).toByte()
            body[total - 1] = (total - length).toByte()
        }

        private fun spec(key: ByteArray): SecretKeySpec {
            ensure(key.size == 32) { "CChannel requires a 32-byte key" }
            return SecretKeySpec(key, "AES")
        }

        /**
         * Trailer over `data[0, length)`: wrapping sum of little-endian 64-bit words
         * (a short tail word is zero-extended), MD4-folded. [view] wraps [data].
         */
        fun auth8(data: ByteArray, view: ByteBuffer, length: Int): Long {
            var sum = 0L
            var i = 0
            while (i + 8 <= length) {
                sum += view.getLong(i)
                i += 8
            }
            if (i < length) {
                var w = 0L
                for (k in length - 1 downTo i) w = (w shl 8) or (data[k].toLong() and 0xff)
                sum += w
            }
            return Md4.foldedWord(sum)
        }

        /** Whether this provider keeps the CBC chain across `update` calls, byte-identical to re-initialising. */
        internal val STREAMING: Boolean = streamingMatchesReinit()

        private fun streamingMatchesReinit(): Boolean = runCatching {
            val key = SecretKeySpec(ByteArray(32) { (it * 29 + 7).toByte() }, "AES")
            val streamEnc = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(ByteArray(16))) }
            val streamDec = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ByteArray(16))) }
            var iv = ByteArray(16)
            for (size in intArrayOf(48, 16, 32, 16)) {
                val plain = ByteArray(size) { (it * 13 + size).toByte() }
                val expected = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv)) }.doFinal(plain)
                val out = ByteArray(size)
                if (streamEnc.update(plain, 0, size, out, 0) != size || !out.contentEquals(expected)) return false
                val back = ByteArray(size)
                if (streamDec.update(out, 0, size, back, 0) != size || !back.contentEquals(plain)) return false
                iv = expected.copyOfRange(size - 16, size)
            }
            true
        }.getOrDefault(false)
    }
}

/** RSA PKCS#1 v1.5 RSession (purposes 3/4/5) carrying 64 secret bytes. */
class RSession(val f6: ByteArray, val secret: ByteArray) {
    companion object {
        fun create(modulus: ByteArray, purpose: Int, secret: ByteArray = randomBytes(64)): RSession {
            ensure(purpose in 3..5) { "unsupported RSession purpose" }
            ensure(secret.size == 64) { "RSession needs exactly 64 secret bytes" }
            val n = unsigned(modulus)
            val e = BigInteger.valueOf(65537)
            ensure(n.signum() > 0 && n.testBit(0)) { "invalid RSA public key" }
            val width = (n.bitLength() + 7) / 8
            val message = leU32(3) + leU32(0x4b) + leU32(purpose.toLong()) + secret
            val count = width - message.size - 3
            ensure(count >= 8) { "RSA message is too long" }
            val padding = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val b = secureRandom.nextInt(256)
                if (b != 0) padding[filled++] = b.toByte()
            }
            val encoded = byteArrayOf(0, 2) + padding + byteArrayOf(0) + message
            val value = unsigned(encoded)
            ensure(value < n) { "RSA encoded message exceeds modulus" }
            return RSession(leftPad(bnBytes(value.modPow(e, n)), width), secret)
        }
    }
}

/** SH (SRP-6a style) group and message framing; see sh_pure in the references. */
object Sh {
    val GROUP_PRIME: ByteArray = hexToBytes(
        "e634471e75f2d76a0a3f5e252c5a6efb48edad645d31d9ecbc400416e14f5c6c" +
            "2ed378602d6eb8e9478b99e6474932393538b4be7177e0e05d3aaed17b5ae87a" +
            "a20f3dad37af019518f45b6dde76b2bfdfb140f379cc15383b304b7975b90796" +
            "7553cd6fdaf08d68832b603120430a501067d21beb2ce2275a20036a2dc80f1c" +
            "9d3ab9221afd5baceda5c2a31077c54fc6f274aaeba17ddd0b5c68f4f8e751a8" +
            "90a58648147a82e6f4f532ccdc939a12942799a713500e17dd4f6ff216cd54c7",
    )
    val GROUP_GENERATOR: ByteArray = byteArrayOf(5)
    val N: BigInteger = unsigned(GROUP_PRIME)
    val G: BigInteger = BigInteger.valueOf(5)

    fun record(kind: Long, body: ByteArray): ByteArray {
        ensure(kind and 0x07ffffffL == 0L && body.isNotEmpty() && body.size <= 0x07ffffff) { "invalid SH record" }
        return beU32(kind or body.size.toLong()) + body
    }

    fun message(index: Int, kind: Long, body: ByteArray): ByteArray =
        record(0x10000000L, beU32(index.toLong())) + record(kind, body)

    fun parse(data: ByteArray, index: Int, vararg kinds: Long): List<ByteArray> {
        val values = ArrayList<ByteArray>()
        var off = 0
        for (expected in longArrayOf(0x10000000L, *kinds)) {
            ensure(off + 4 <= data.size) { "truncated SH header" }
            val word = readBeU32(data, off)
            val size = (word and 0x07ffffffL).toInt()
            off += 4
            ensure(word and 0xf8000000L == expected && size in 1..0x2800) { "invalid SH kind or size" }
            ensure(off + size <= data.size) { "truncated SH body" }
            values.add(data.copyOfRange(off, off + size))
            off += size
        }
        ensure(off == data.size && values[0].contentEquals(beU32(index.toLong()))) {
            "unexpected SH state or trailing data"
        }
        return values.subList(1, values.size)
    }

    /** CreateClient swaps bytes within each LE16 word of the u64 RID. */
    fun identityBytes(rid: Long): ByteArray {
        val raw = ByteArray(8) { (rid ushr (8 * it)).toByte() }
        return ByteArray(8) { i -> raw[i xor 1] }
    }

    fun passwordExponent(salt: ByteArray, identity: ByteArray, password: ByteArray): BigInteger =
        unsigned(sha1(salt, sha1(identity, byteArrayOf(':'.code.toByte()), password)))

    fun multiplier(): BigInteger = unsigned(sha1(GROUP_PRIME, leftPad(GROUP_GENERATOR, GROUP_PRIME.size)))

    fun sessionKey(shared: ByteArray): ByteArray = sha1(shared, beU32(0)) + sha1(shared, beU32(1))

    fun prefix(): ByteArray {
        val a = sha1(GROUP_PRIME)
        val b = sha1(GROUP_GENERATOR)
        return ByteArray(20) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }

    fun padded(value: ByteArray): ByteArray = leftPad(value, GROUP_PRIME.size)
}

/** Client states 0→2→4→6→7. The key becomes available after f14 verifies. */
class ShClient(rid: Long, password: ByteArray, private var private: BigInteger? = null) {
    private val identity = Sh.identityBytes(rid)
    private val ridBytes = ByteArray(8) { (rid ushr (8 * it)).toByte() }
    private val password = password.copyOf()
    var state = 0
        private set
    private var salt = ByteArray(0)
    private var x = BigInteger.ZERO
    private var serverBody = ByteArray(0)
    private var key = ByteArray(0)

    init {
        ensure(password.size > 5) { "SH password must contain more than five bytes" }
    }

    fun start(): ByteArray {
        ensure(state == 0) { "SH already started" }
        state = 2
        return Sh.message(1, 0x20000000L, ridBytes)
    }

    fun receiveF10(f10: ByteArray): ByteArray {
        ensure(state == 2) { "SH is not awaiting f10" }
        val (prime, generator, s) = Sh.parse(f10, 2, 0x30000000L, 0x40000000L, 0x50000000L)
        ensure(prime.contentEquals(Sh.GROUP_PRIME) && generator.contentEquals(Sh.GROUP_GENERATOR)) {
            "SH group does not match the verified client build"
        }
        salt = s
        if (private == null) private = unsigned(randomBytes(32)).add(BigInteger.valueOf(Sh.N.bitLength().toLong()))
        try {
            x = Sh.passwordExponent(salt, identity, password)
        } finally {
            // The password is consumed here; wipe on every path, including failure.
            // Earlier failures (bad f10/group) keep it so the caller can retry.
            password.fill(0)
        }
        state = 4
        return Sh.message(3, 0x60000000L, bnBytes(Sh.G.modPow(private!!, Sh.N)))
    }

    fun receiveF12(f12: ByteArray): ByteArray {
        ensure(state == 4) { "SH is not awaiting f12" }
        val peer = Sh.parse(f12, 4, 0x60000000L)[0]
        val b = unsigned(peer)
        ensure(peer.size <= Sh.GROUP_PRIME.size && b.signum() > 0 && b < Sh.N) { "invalid SH peer public value" }
        val a = private!!
        val public = bnBytes(Sh.G.modPow(a, Sh.N))
        val k = Sh.multiplier()
        val u = unsigned(sha1(Sh.padded(public), Sh.padded(peer)))
        val v = Sh.G.modPow(x, Sh.N)
        val base = b.subtract(k.multiply(v)).mod(Sh.N)
        val shared = bnBytes(base.modPow(a.add(u.multiply(x)), Sh.N))
        key = Sh.sessionKey(shared)
        val body = sha1(Sh.prefix(), sha1(identity), salt, public, peer, key)
        serverBody = sha1(public, body, key)
        state = 6
        return Sh.message(5, 0x70000000L, body)
    }

    fun receiveF14(f14: ByteArray): ByteArray {
        ensure(state == 6) { "SH is not awaiting f14" }
        val body = Sh.parse(f14, 6, 0x70000000L)[0]
        ensure(MessageDigest.isEqual(body, serverBody)) { "invalid SH server confirmation" }
        state = 7
        return key
    }

    /** Drops the stored password copy; safe to call more than once. */
    fun wipe() {
        password.fill(0)
    }
}

/** Responder used when a peer dials us (incoming direct TCP or relay). */
class ShServer(
    private val rid: Long,
    password: ByteArray,
    private val salt: ByteArray = randomBytes(16),
    private val private: BigInteger = unsigned(randomBytes(32)).add(BigInteger.valueOf(1536)),
) {
    private val password = password.copyOf()
    private var state = 1
    private var m1 = ByteArray(0)
    private var m2 = ByteArray(0)
    private var key = ByteArray(0)

    init {
        ensure(password.size > 5) { "SH password too short" }
    }

    fun hello(data: ByteArray): ByteArray {
        ensure(state == 1) { "unexpected SH state" }
        val ridBytes = Sh.parse(data, 1, 0x20000000L)[0]
        ensure(ridBytes.contentEquals(ByteArray(8) { (rid ushr (8 * it)).toByte() })) { "SH identity mismatch" }
        state = 3
        return Sh.message(2, 0x30000000L, Sh.GROUP_PRIME) + Sh.record(0x40000000L, Sh.GROUP_GENERATOR) +
            Sh.record(0x50000000L, salt)
    }

    fun public(data: ByteArray): ByteArray {
        ensure(state == 3) { "unexpected SH state" }
        val a = Sh.parse(data, 3, 0x60000000L)[0]
        val av = unsigned(a)
        ensure(a.size <= Sh.GROUP_PRIME.size && av.signum() > 0 && av < Sh.N) { "invalid SH client public" }
        val identity = Sh.identityBytes(rid)
        val x = try {
            Sh.passwordExponent(salt, identity, password)
        } finally {
            // Consumed here; wipe on every path, including failure.
            password.fill(0)
        }
        val v = Sh.G.modPow(x, Sh.N)
        val k = Sh.multiplier()
        val b = bnBytes(k.multiply(v).add(Sh.G.modPow(private, Sh.N)).mod(Sh.N))
        val u = unsigned(sha1(Sh.padded(a), Sh.padded(b)))
        val shared = bnBytes(av.multiply(v.modPow(u, Sh.N)).mod(Sh.N).modPow(private, Sh.N))
        key = Sh.sessionKey(shared)
        m1 = sha1(Sh.prefix(), sha1(identity), salt, a, b, key)
        m2 = sha1(a, m1, key)
        state = 5
        return Sh.message(4, 0x60000000L, b)
    }

    fun proof(data: ByteArray): Pair<ByteArray, ByteArray> {
        ensure(state == 5) { "unexpected SH state" }
        val proof = Sh.parse(data, 5, 0x70000000L)[0]
        ensure(MessageDigest.isEqual(proof, m1)) { "SH client proof rejected" }
        state = 7
        return Sh.message(6, 0x70000000L, m2) to key
    }

    /** Drops the stored password copy; safe to call more than once. */
    fun wipe() {
        password.fill(0)
    }
}

/** RSession + CChannel bootstrap + SH for server sessions (purposes 3/4/5). */
class SessionCrypto(modulus: ByteArray, rid: Long, password: ByteArray, purpose: Int) {
    private val rs = RSession.create(modulus, purpose)
    val channel = CChannel(rs.secret.copyOfRange(0, 32))
    private val sh = ShClient(rid, password)
    private var started = false
    val f6: ByteArray get() = rs.f6

    fun acceptEcho(echo: ByteArray): ByteArray {
        ensure(!started && echo.contentEquals(byteArrayOf(3, 0, 0, 0))) { "unexpected RSession echo" }
        started = true
        return channel.encrypt(sh.start())
    }

    fun receiveF10(c: ByteArray) = channel.encrypt(sh.receiveF10(channel.decrypt(c)))
    fun receiveF12(c: ByteArray) = channel.encrypt(sh.receiveF12(channel.decrypt(c)))
    fun receiveF14(c: ByteArray) {
        val key = sh.receiveF14(channel.decrypt(c))
        channel.rekey(key.copyOfRange(0, 32))
    }
}
