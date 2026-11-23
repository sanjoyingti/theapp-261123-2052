package com.arin.bypass.util

import java.io.File
import java.security.MessageDigest
import java.util.zip.Adler32

object Hashing {

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).joinToString("") { "%02x".format(it) }

    fun sha256File(file: File): String = sha256Hex(file.readBytes())

    fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

    fun adler32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        val a = Adler32()
        a.update(bytes, offset, length)
        return a.value.toInt()
    }

    fun insert(data: ByteArray, at: Int, extra: ByteArray): ByteArray {
        val out = ByteArray(data.size + extra.size)
        System.arraycopy(data, 0, out, 0, at)
        System.arraycopy(extra, 0, out, at, extra.size)
        System.arraycopy(data, at, out, at + extra.size, data.size - at)
        return out
    }

    fun removeRange(data: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArray(data.size - (to - from))
        System.arraycopy(data, 0, out, 0, from)
        System.arraycopy(data, to, out, from, data.size - to)
        return out
    }

    fun u8(b: ByteArray, p: Int): Int = b[p].toInt() and 0xFF

    fun u16(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

    fun u32(b: ByteArray, p: Int): Long =
        (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or
            ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)

    fun putU16(b: ByteArray, p: Int, v: Int) {
        b[p] = (v and 0xFF).toByte()
        b[p + 1] = ((v shr 8) and 0xFF).toByte()
    }

    fun putU32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v and 0xFF).toByte()
        b[p + 1] = ((v shr 8) and 0xFF).toByte()
        b[p + 2] = ((v shr 16) and 0xFF).toByte()
        b[p + 3] = ((v shr 24) and 0xFF).toByte()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(text: String): ByteArray {
        val clean = text.filter { it.isLetterOrDigit() }
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
