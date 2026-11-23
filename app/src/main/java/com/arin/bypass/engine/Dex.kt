package com.arin.bypass.engine

import com.arin.bypass.util.Hashing
import java.io.File

class DexException(message: String) : Exception(message)

/**
 * Reader/patcher for a single classesN.dex. Only what the patcher needs:
 * method lookup, line lookup and a two-instruction early return.
 */
class Dex(val file: File) {

    val bytes: ByteArray = file.readBytes()
    private val stringIdsOff: Int
    private val typeIdsOff: Int
    private val protoIdsOff: Int
    private val methodIdsOff: Int
    private val classDefsOff: Int
    private val methodCount: Int
    private val classCount: Int
    private var codeMap: HashMap<Int, Int>? = null
    private var ownerMap: HashMap<Int, String>? = null

    init {
        if (bytes.size < 112) throw DexException("${file.name} is too small to be a code file.")
        if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() || bytes[2] != 'x'.code.toByte()) {
            throw DexException("${file.name} is not a dex file.")
        }
        stringIdsOff = u32(60)
        typeIdsOff = u32(68)
        protoIdsOff = u32(76)
        methodIdsOff = u32(92)
        classDefsOff = u32(100)
        methodCount = u32(88)
        classCount = u32(96)
    }

    fun stringAt(idx: Int): String {
        if (idx !in 0 until u32(56)) throw DexException("String index out of range.")
        var p = u32(stringIdsOff + idx * 4)
        var len = 0
        var shift = 0
        while (true) {
            val b = bytes[p].toInt() and 0xFF
            p++
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return String(bytes, p, len, Charsets.UTF_8)
    }

    private fun typeName(idx: Int): String = stringAt(u32(typeIdsOff + idx * 4))

    fun methodClass(methodIdx: Int): String = typeName(u16(methodIdsOff + methodIdx * 8))

    fun methodName(methodIdx: Int): String = stringAt(u32(methodIdsOff + methodIdx * 8 + 4))

    fun methodProto(methodIdx: Int): String {
        val protoIdx = u16(methodIdsOff + methodIdx * 8 + 2)
        val returnType = typeName(u32(protoIdsOff + protoIdx * 12 + 4))
        val paramsOff = u32(protoIdsOff + protoIdx * 12 + 8)
        val builder = StringBuilder("(")
        if (paramsOff != 0) {
            val count = u32(paramsOff)
            for (i in 0 until count) builder.append(typeName(u32(paramsOff + 4 + i * 2)))
        }
        builder.append(")").append(returnType)
        return builder.toString()
    }

    fun signature(methodIdx: Int): String = "${methodClass(methodIdx)}->${methodName(methodIdx)}${methodProto(methodIdx)}"

    fun returnType(methodIdx: Int): String {
        val protoIdx = u16(methodIdsOff + methodIdx * 8 + 2)
        return typeName(u32(protoIdsOff + protoIdx * 12 + 4))
    }

    fun allMethodIndices(): List<Int> = (0 until methodCount).toList()

    private var lastPos = 0

    private fun uleb(start: Int): Int {
        var p = start
        var result = 0
        var shift = 0
        while (true) {
            val b = bytes[p].toInt() and 0xFF
            p++
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        lastPos = p
        return result
    }

    private fun buildCodeMap() {
        if (codeMap != null) return
        val codes = HashMap<Int, Int>()
        val owners = HashMap<Int, String>()
        for (c in 0 until classCount) {
            val base = classDefsOff + c * 32
            val classIdx = u32(base)
            val classDataOff = u32(base + 24)
            if (classDataOff == 0) continue
            val descriptor = typeName(classIdx)
            var p = classDataOff
            val staticFields = uleb(p); p = lastPos
            val instanceFields = uleb(p); p = lastPos
            val directMethods = uleb(p); p = lastPos
            val virtualMethods = uleb(p); p = lastPos
            repeat(staticFields + instanceFields) {
                p = uleb(p); p = lastPos
                p = uleb(p); p = lastPos
            }
            val totalMethods = directMethods + virtualMethods
            var methodIdx = 0
            var codeOff = 0
            repeat(totalMethods) {
                val diff = uleb(p); p = lastPos
                methodIdx += diff
                p = uleb(p); p = lastPos
                val codeDelta = uleb(p); p = lastPos
                codeOff += codeDelta
                if (codeOff != 0) {
                    codes[methodIdx] = codeOff
                    owners[methodIdx] = descriptor
                }
            }
        }
        codeMap = codes
        ownerMap = owners
    }

    fun codeOffset(methodIdx: Int): Int? {
        buildCodeMap()
        return codeMap?.get(methodIdx)
    }

    /** Line number in the original Java source, or -1 when the app was built without debug info. */
    fun lineFor(methodIdx: Int): Int {
        val code = codeOffset(methodIdx) ?: return -1
        val debugInfoOff = u32(code + 8)
        if (debugInfoOff == 0) return -1
        var p = debugInfoOff
        var line = uleb(p); p = lastPos
        val params = uleb(p); p = lastPos
        var addr = 0
        var best = -1
        var bestAddr = Int.MAX_VALUE
        repeat(params) { p = uleb(p); lastPos = p }
        while (p < bytes.size) {
            val op = bytes[p].toInt() and 0xFF
            p++
            when (op) {
                0x00 -> break
                0x01 -> { addr += uleb(p); p = lastPos }
                0x02 -> {
                    var value = 0
                    var shift = 0
                    var b: Int
                    do {
                        b = bytes[p].toInt() and 0xFF
                        p++
                        value = value or ((b and 0x7F) shl shift)
                        shift += 7
                    } while (b and 0x80 != 0)
                    val signed = if (shift < 32 && (value and (1 shl (shift - 1))) != 0) value or (-1 shl shift) else value
                    line += signed
                }
                0x03, 0x04 -> {
                    p = uleb(p); lastPos = p
                    p = uleb(p); lastPos = p
                    p = uleb(p); lastPos = p
                    if (op == 0x04) { p = uleb(p); lastPos = p }
                }
                0x05, 0x06 -> { p = uleb(p); lastPos = p }
                0x09 -> { p = uleb(p); lastPos = p }
                0x0C -> { p = uleb(p); lastPos = p }
                0x0D, 0x0E -> {
                    p = uleb(p); lastPos = p
                    p = uleb(p); lastPos = p
                    if (op == 0x0E) { p = uleb(p); lastPos = p }
                }
                else -> Unit
            }
            if (addr < bestAddr) {
                bestAddr = addr
                best = line
            }
        }
        return best
    }

    /** Rewrites the start of [methodIdx] so it immediately returns true or false. */
    fun forceReturn(methodIdx: Int, value: Boolean): Boolean {
        val code = codeOffset(methodIdx) ?: return false
        val insnsSize = u32(code + 12)
        if (insnsSize < 2) return false
        val start = code + 16
        if (start + 4 > bytes.size) return false
        bytes[start] = 0x12
        bytes[start + 1] = if (value) 0x10 else 0x00
        bytes[start + 2] = 0x0F
        bytes[start + 3] = 0x00
        return true
    }

    fun patchString(old: String, replacement: String): Boolean {
        val iss = u32(56)
        val encoded = replacement.toByteArray(Charsets.UTF_8)
        for (i in 0 until iss) {
            if (stringAt(i) != old) continue
            val dataOff = u32(stringIdsOff + i * 4)
            val start = dataOff + lebLength(replacement.length) + lebLength(encoded.size)
            if (start + encoded.size > bytes.size) continue
            System.arraycopy(encoded, 0, bytes, start, encoded.size)
            return true
        }
        return false
    }

    private fun lebLength(value: Int): Int = if (value < 0x80) 1 else 2

    fun save() {
        val signature = Hashing.sha1(bytes.copyOfRange(32, bytes.size))
        System.arraycopy(signature, 0, bytes, 12, 20)
        val checksum = Hashing.adler32(bytes, 12, bytes.size - 12)
        bytes[8] = (checksum and 0xFF).toByte()
        bytes[9] = ((checksum shr 8) and 0xFF).toByte()
        bytes[10] = ((checksum shr 16) and 0xFF).toByte()
        bytes[11] = ((checksum shr 24) and 0xFF).toByte()
        file.writeBytes(bytes)
    }

    private fun u16(p: Int): Int = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)

    private fun u32(p: Int): Int = (bytes[p].toInt() and 0xFF) or
        ((bytes[p + 1].toInt() and 0xFF) shl 8) or
        ((bytes[p + 2].toInt() and 0xFF) shl 16) or
        ((bytes[p + 3].toInt() and 0xFF) shl 24)

    companion object {
        fun isDex(file: File): Boolean = file.name.startsWith("classes") && file.name.endsWith(".dex")
    }
}
