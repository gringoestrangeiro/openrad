package com.openrad.mobile.engine

import java.io.ByteArrayOutputStream

/**
 * TLV and byte helpers. Mirrors `protocol.rs::records/field/tlv/u32v/u64v/textv`.
 * Every parser is bounded and rejects truncation, duplicates and wrong widths.
 */
class ProtocolException(message: String) : Exception(message)

internal fun fail(message: String): Nothing = throw ProtocolException(message)

internal inline fun ensure(condition: Boolean, message: () -> String) {
    if (!condition) fail(message())
}

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(HEX[v ushr 4]).append(HEX[v and 15])
    }
    return out.toString()
}

private val HEX = "0123456789abcdef".toCharArray()

fun hexToBytes(text: String): ByteArray {
    ensure(text.length % 2 == 0) { "odd hex length" }
    return ByteArray(text.length / 2) { i ->
        val hi = Character.digit(text[2 * i], 16)
        val lo = Character.digit(text[2 * i + 1], 16)
        ensure(hi >= 0 && lo >= 0) { "invalid hex digit" }
        ((hi shl 4) or lo).toByte()
    }
}

fun beU32(value: Long): ByteArray = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
)

fun beU64(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }

fun leU32(value: Long): ByteArray = byteArrayOf(
    value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(),
)

fun readBeU32(data: ByteArray, offset: Int = 0): Long =
    ((data[offset].toLong() and 0xff) shl 24) or ((data[offset + 1].toLong() and 0xff) shl 16) or
        ((data[offset + 2].toLong() and 0xff) shl 8) or (data[offset + 3].toLong() and 0xff)

fun readLeU32(data: ByteArray, offset: Int = 0): Long =
    (data[offset].toLong() and 0xff) or ((data[offset + 1].toLong() and 0xff) shl 8) or
        ((data[offset + 2].toLong() and 0xff) shl 16) or ((data[offset + 3].toLong() and 0xff) shl 24)

fun readBeU64(data: ByteArray, offset: Int = 0): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (data[offset + i].toLong() and 0xff)
    return v
}

fun readBeU16(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(parts.sumOf { it.size })
    for (p in parts) out.write(p)
    return out.toByteArray()
}

class Record(val tag: Long, val value: ByteArray) {
    fun u32(): Long {
        ensure(value.size == 4) { "tag %08x requires four bytes".format(tag) }
        return readBeU32(value)
    }

    fun u64(): Long {
        ensure(value.size == 8) { "invalid 64-bit field width" }
        return readBeU64(value)
    }
}

object Tlv {
    const val MAX_RECORDS = 16384
    const val MAX_PLAINTEXT = 4 * 1024 * 1024

    fun parse(data: ByteArray): List<Record> {
        ensure(data.size <= MAX_PLAINTEXT) { "TLV payload exceeds limit" }
        val out = ArrayList<Record>()
        var offset = 0
        while (offset < data.size) {
            ensure(out.size < MAX_RECORDS && data.size - offset >= 8) { "invalid TLV header or record count" }
            val size = readBeU32(data, offset)
            val tag = readBeU32(data, offset + 4)
            offset += 8
            ensure(size <= data.size - offset) { "truncated TLV body" }
            out.add(Record(tag, data.copyOfRange(offset, offset + size.toInt())))
            offset += size.toInt()
        }
        return out
    }

    fun optional(records: List<Record>, tag: Long): Record? {
        var found: Record? = null
        for (r in records) {
            if (r.tag == tag) {
                ensure(found == null) { "duplicate singleton tag %08x".format(tag) }
                found = r
            }
        }
        return found
    }

    fun unique(records: List<Record>, tag: Long): Record =
        optional(records, tag) ?: fail("missing tag %08x".format(tag))

    fun tlv(tag: Long, value: ByteArray): ByteArray = concat(beU32(value.size.toLong()), beU32(tag), value)

    fun u32(tag: Long, value: Long): ByteArray = tlv(tag, beU32(value))

    fun u64(tag: Long, value: Long): ByteArray = tlv(tag, beU64(value))

    /** UTF-16BE text terminated by U+0001; empty text is allowed (search query). */
    fun text(tag: Long, value: String): ByteArray {
        ensure(value.none { it.code < 32 }) { "name/version must contain no control characters" }
        val encoded = value.toByteArray(Charsets.UTF_16BE)
        ensure(encoded.size <= 512) { "name/version exceeds client limit" }
        return tlv(tag, encoded + byteArrayOf(0, 1))
    }

    fun decodeText(record: Record): String {
        val v = record.value
        ensure(v.size in 4..8192 && v.size % 2 == 0 && v[v.size - 2] == 0.toByte() && v[v.size - 1] == 1.toByte()) {
            "invalid text length or terminator"
        }
        val decoder = Charsets.UTF_16BE.newDecoder()
        val text = try {
            decoder.decode(java.nio.ByteBuffer.wrap(v, 0, v.size - 2)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail("invalid text encoding")
        }
        ensure('\u0000' !in text) { "embedded NUL in text" }
        return text
    }

    /** Public catalog names may contain anything; replace control characters for display. */
    fun displayText(record: Record): String =
        decodeText(record).map { if (it.isISOControl()) '�' else it }.joinToString("")
}

/** IPv4 helpers kept as Int (network order) to avoid InetAddress DNS paths. */
object Ip {
    fun parse(text: String): Int {
        val parts = text.split('.')
        ensure(parts.size == 4) { "invalid IPv4 address" }
        var v = 0
        for (p in parts) {
            val n = p.toIntOrNull()
            ensure(n != null && n in 0..255 && p.length in 1..3) { "invalid IPv4 address" }
            v = (v shl 8) or n!!
        }
        return v
    }

    fun format(value: Int): String =
        "${(value ushr 24) and 255}.${(value ushr 16) and 255}.${(value ushr 8) and 255}.${value and 255}"

    fun bytes(value: Int): ByteArray = beU32(value.toLong() and 0xffffffffL)

    fun read(data: ByteArray, offset: Int): Int = readBeU32(data, offset).toInt()

    fun isMulticast(value: Int): Boolean = (value ushr 28) == 0xE
}
