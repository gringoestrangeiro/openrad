package com.openrad.mobile.engine

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verbatim 1.5.0 `CChannel` and `Md4` (per-record `Cipher.init`, byte-wise
 * trailer sum, general MD4), kept only as a reference oracle so the optimised
 * packet path can be compared byte-for-byte on arbitrary inputs.
 */
internal object LegacyMd4 {
    fun digest(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        var padded = message.size + 1
        while (padded % 64 != 56) padded++
        val msg = ByteArray(padded + 8)
        System.arraycopy(message, 0, msg, 0, message.size)
        msg[message.size] = 0x80.toByte()
        for (i in 0 until 8) msg[padded + i] = (bitLength ushr (8 * i)).toByte()
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476
        val x = IntArray(16)
        var off = 0
        while (off < msg.size) {
            for (i in 0 until 16) x[i] = readLeU32(msg, off + 4 * i).toInt()
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            fun f(x1: Int, y: Int, z: Int) = (x1 and y) or (x1.inv() and z)
            fun g(x1: Int, y: Int, z: Int) = (x1 and y) or (x1 and z) or (y and z)
            fun h(x1: Int, y: Int, z: Int) = x1 xor y xor z
            for (i in intArrayOf(0, 4, 8, 12)) {
                a = Integer.rotateLeft(a + f(b, c, d) + x[i], 3)
                d = Integer.rotateLeft(d + f(a, b, c) + x[i + 1], 7)
                c = Integer.rotateLeft(c + f(d, a, b) + x[i + 2], 11)
                b = Integer.rotateLeft(b + f(c, d, a) + x[i + 3], 19)
            }
            for (i in 0 until 4) {
                a = Integer.rotateLeft(a + g(b, c, d) + x[i] + 0x5A827999, 3)
                d = Integer.rotateLeft(d + g(a, b, c) + x[i + 4] + 0x5A827999, 5)
                c = Integer.rotateLeft(c + g(d, a, b) + x[i + 8] + 0x5A827999, 9)
                b = Integer.rotateLeft(b + g(c, d, a) + x[i + 12] + 0x5A827999, 13)
            }
            for (i in intArrayOf(0, 2, 1, 3)) {
                a = Integer.rotateLeft(a + h(b, c, d) + x[i] + 0x6ED9EBA1, 3)
                d = Integer.rotateLeft(d + h(a, b, c) + x[i + 8] + 0x6ED9EBA1, 9)
                c = Integer.rotateLeft(c + h(d, a, b) + x[i + 4] + 0x6ED9EBA1, 11)
                b = Integer.rotateLeft(b + h(c, d, a) + x[i + 12] + 0x6ED9EBA1, 15)
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
            off += 64
        }
        return leU32(a0.toLong()) + leU32(b0.toLong()) + leU32(c0.toLong()) + leU32(d0.toLong())
    }
}

internal class LegacyCChannel(key: ByteArray) {
    private var keyEnc = checkKey(key)
    private var keyDec = keyEnc
    private var ivEnc = ByteArray(16)
    private var ivDec = ByteArray(16)
    private val enc = Cipher.getInstance("AES/CBC/NoPadding")
    private val dec = Cipher.getInstance("AES/CBC/NoPadding")

    fun rekey(key: ByteArray) {
        keyEnc = checkKey(key)
        keyDec = keyEnc
    }

    @Synchronized
    fun encrypt(plain: ByteArray): ByteArray {
        ensure(plain.isNotEmpty()) { "CChannel rejects empty plaintext" }
        val total = (plain.size + 9 + 15) / 16 * 16
        val body = ByteArray(total)
        System.arraycopy(plain, 0, body, 0, plain.size)
        val regionEnd = total - 9
        for (i in plain.size until regionEnd) body[i] = 0xcc.toByte()
        val trailer = auth8(body, regionEnd)
        System.arraycopy(trailer, 0, body, regionEnd, 8)
        body[total - 1] = (total - plain.size).toByte()
        enc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyEnc, "AES"), IvParameterSpec(ivEnc))
        val out = enc.doFinal(body)
        ivEnc = out.copyOfRange(out.size - 16, out.size)
        return out
    }

    @Synchronized
    fun decrypt(cipherText: ByteArray): ByteArray {
        ensure(cipherText.isNotEmpty() && cipherText.size % 16 == 0) {
            "CChannel requires nonempty complete ciphertext blocks"
        }
        dec.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyDec, "AES"), IvParameterSpec(ivDec))
        val body = dec.doFinal(cipherText)
        ivDec = cipherText.copyOfRange(cipherText.size - 16, cipherText.size)
        val padding = body[body.size - 1].toInt() and 0xff
        ensure(padding in 9..24 && padding < body.size) { "invalid CChannel padding length" }
        val expected = auth8(body, body.size - 9)
        var diff = 0
        for (i in 0 until 8) diff = diff or (expected[i].toInt() xor body[body.size - 9 + i].toInt())
        ensure(diff == 0) { "invalid CChannel authentication trailer" }
        return body.copyOfRange(0, body.size - padding)
    }

    companion object {
        private fun checkKey(key: ByteArray): ByteArray {
            ensure(key.size == 32) { "CChannel requires a 32-byte key" }
            return key.copyOf()
        }

        fun auth8(data: ByteArray, length: Int): ByteArray {
            var sum = 0L
            var i = 0
            while (i + 8 <= length) {
                var w = 0L
                for (k in 7 downTo 0) w = (w shl 8) or (data[i + k].toLong() and 0xff)
                sum += w
                i += 8
            }
            if (i < length) {
                var w = 0L
                for (k in length - 1 downTo i) w = (w shl 8) or (data[k].toLong() and 0xff)
                sum += w
            }
            val word = ByteArray(8) { (sum ushr (8 * it)).toByte() }
            val d = LegacyMd4.digest(word)
            return ByteArray(8) { ((d[it].toInt() + d[it + 8].toInt()) and 0xff).toByte() }
        }
    }
}
