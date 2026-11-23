package com.arin.bypass.engine

import java.io.ByteArrayOutputStream

/**
 * Minimal binary XML (AXML) reader and writer for AndroidManifest.xml.
 * Namespaces and resource ids are preserved so the framework can still resolve attributes.
 */
object Axml {

    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML_START_NS = 0x0100
    private const val RES_XML_END_NS = 0x0101
    private const val RES_XML_START_ELEM = 0x0102
    private const val RES_XML_END_ELEM = 0x0103
    private const val RES_XML_RES_MAP = 0x0180
    private const val UTF8_FLAG = 0x100

    const val TYPE_REFERENCE = 0x01
    const val TYPE_STRING = 0x03
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
    const val TYPE_INT_BOOLEAN = 0x12

    const val ATTR_EXPORTED = 0x01010010
    const val ATTR_NAME = 0x01010003
    const val ATTR_TARGET_SDK = 0x01010270
    const val ATTR_MIN_SDK = 0x0101020c
    const val ATTR_VERSION_CODE = 0x0101021b
    const val ATTR_VERSION_NAME = 0x0101021c
    const val ATTR_CLEARTEXT = 0x010104ec

    class AxmlException(message: String) : Exception(message)

    class Attr(
        var ns: String?,
        var name: String,
        var resId: Int,
        var raw: String?,
        var type: Int,
        var data: Int
    )

    class Element(
        var name: String,
        var ns: String?,
        val attrs: MutableList<Attr> = ArrayList(),
        val children: MutableList<Element> = ArrayList()
    ) {
        fun attr(nsUri: String?, name: String): Attr? =
            attrs.firstOrNull { it.name == name && sameNs(it.ns, nsUri) }

        fun stringValue(nsUri: String?, name: String): String? {
            val a = attr(nsUri, name) ?: return null
            return when (a.type) {
                TYPE_INT_BOOLEAN -> if (a.data != 0) "true" else "false"
                TYPE_INT_DEC, TYPE_INT_HEX -> a.data.toString()
                else -> a.raw
            }
        }

        fun intValue(nsUri: String?, name: String): Int? {
            val a = attr(nsUri, name) ?: return null
            return when (a.type) {
                TYPE_INT_DEC, TYPE_INT_HEX, TYPE_INT_BOOLEAN -> a.data
                TYPE_STRING -> a.raw?.toIntOrNull()
                else -> null
            }
        }

        fun setString(nsUri: String?, name: String, value: String, resId: Int = 0) {
            val existing = attr(nsUri, name)
            if (existing != null) {
                existing.type = TYPE_STRING
                existing.raw = value
                existing.data = 0
                if (resId != 0) existing.resId = resId
            } else {
                attrs.add(Attr(nsUri, name, resId, value, TYPE_STRING, 0))
            }
        }

        fun setBool(nsUri: String?, name: String, value: Boolean, resId: Int) {
            val existing = attr(nsUri, name)
            val data = if (value) -1 else 0
            if (existing != null) {
                existing.type = TYPE_INT_BOOLEAN
                existing.raw = null
                existing.data = data
                existing.resId = resId
            } else {
                attrs.add(Attr(nsUri, name, resId, null, TYPE_INT_BOOLEAN, data))
            }
        }

        fun setInt(nsUri: String?, name: String, value: Int, resId: Int) {
            val existing = attr(nsUri, name)
            if (existing != null) {
                existing.type = TYPE_INT_DEC
                existing.raw = null
                existing.data = value
                existing.resId = resId
            } else {
                attrs.add(Attr(nsUri, name, resId, null, TYPE_INT_DEC, value))
            }
        }

        fun removeAttr(nsUri: String?, name: String) {
            attrs.removeAll { it.name == name && sameNs(it.ns, nsUri) }
        }

        fun child(name: String): Element? = children.firstOrNull { it.name == name }

        private fun sameNs(a: String?, b: String?): Boolean =
            (a == null && b == null) || (a != null && a == b)
    }

    class Document(val namespaces: MutableList<Pair<String, String>>) {
        var root: Element? = null

        fun manifest(): Element = root ?: throw AxmlException("The settings file has no <manifest> root.")

        fun application(): Element? = root?.child("application")

        fun ensureAndroidNamespace() {
            if (namespaces.none { it.second == ANDROID_NS }) {
                namespaces.add("android" to ANDROID_NS)
            }
        }
    }

    fun parse(bytes: ByteArray): Document {
        val r = Reader(bytes)
        if (r.u16() != 0x0003) throw AxmlException("Not a binary Android settings file.")
        r.skip(2)
        r.skip(4)
        var strings: List<String> = emptyList()
        var resMap: IntArray = IntArray(0)
        val doc = Document(ArrayList())
        val stack = ArrayDeque<Element>()
        while (r.pos + 8 <= bytes.size) {
            val start = r.pos
            val type = r.u16()
            val headerSize = r.u16()
            val size = r.u32()
            if (size <= 8 || start + size > bytes.size) break
            when (type) {
                RES_STRING_POOL -> strings = readPool(r, start + headerSize, start + size)
                RES_XML_RES_MAP -> {
                    val count = (start + size - r.pos) / 4
                    val map = IntArray(count)
                    for (i in 0 until count) map[i] = r.u32()
                    resMap = map
                }
                RES_XML_START_NS -> {
                    r.skip(4)
                    val prefix = str(strings, r.u32())
                    val uri = str(strings, r.u32())
                    doc.namespaces.add(prefix to uri)
                }
                RES_XML_START_ELEM -> {
                    r.skip(8)
                    val nsIdx = r.u32()
                    val ns = if (nsIdx == -1) null else str(strings, nsIdx)
                    val name = str(strings, r.u32())
                    r.skip(2)
                    val attrSize = r.u16()
                    val count = r.u16()
                    r.skip(6)
                    val el = Element(name, ns)
                    for (i in 0 until count) {
                        val aStart = r.pos
                        val aNsIdx = r.u32()
                        val aNs = if (aNsIdx == -1) null else str(strings, aNsIdx)
                        val aNameIdx = r.u32()
                        val aName = str(strings, aNameIdx)
                        val rawIdx = r.u32()
                        val raw = if (rawIdx == -1) null else str(strings, rawIdx)
                        r.skip(2)
                        r.u8()
                        val aType = r.u8()
                        val data = r.u32()
                        val resId = if (aNameIdx in resMap.indices) resMap[aNameIdx] else 0
                        el.attrs.add(Attr(aNs, aName, resId, raw, aType, data))
                        r.seek(if (attrSize > 0) aStart + attrSize else aStart + 20)
                    }
                    stack.addLast(el)
                    if (doc.root == null) doc.root = el
                    else stack.dropLast(1).lastOrNull()?.children?.add(el)
                }
                RES_XML_END_ELEM -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                }
            }
            r.seek(start + size)
        }
        if (doc.root == null) throw AxmlException("The settings file has no <manifest> root.")
        return doc
    }

    fun write(doc: Document): ByteArray {
        val pool = Pool()
        val nsIndices = doc.namespaces.map { (prefix, uri) -> intArrayOf(pool.add(prefix), pool.add(uri)) }
        val body = Chunk()
        for (ns in nsIndices) {
            body.startChunk(RES_XML_START_NS, 16, 24)
            body.le32(0)
            body.le32(-1)
            body.le32(ns[0])
            body.le32(ns[1])
            body.endChunk()
        }
        writeElement(body, doc.manifest(), pool)
        for (ns in nsIndices.reversed()) {
            body.startChunk(RES_XML_END_NS, 16, 24)
            body.le32(0)
            body.le32(-1)
            body.le32(ns[0])
            body.le32(ns[1])
            body.endChunk()
        }

        val out = Chunk()
        out.le16(0x0003)
        out.le16(8)
        out.le32(0)
        writePool(out, pool)
        writeResMap(out, pool)
        out.writeBytes(body.finish())
        val bytes = out.out.toByteArray()
        val total = bytes.size
        putU32(bytes, 4, total)
        return bytes
    }

    private fun writeElement(out: Chunk, el: Element, pool: Pool) {
        val nsIdx = el.ns?.let { pool.add(it) } ?: -1
        val nameIdx = pool.add(el.name)
        out.startChunk(RES_XML_START_ELEM, 16, 28 + 20 * el.attrs.size)
        out.le32(0)
        out.le32(-1)
        out.le32(nsIdx)
        out.le32(nameIdx)
        out.le16(20)
        out.le16(20)
        out.le16(el.attrs.size)
        out.le16(0)
        out.le16(0)
        out.le16(0)
        for (a in el.attrs) {
            val aNs = a.ns?.let { pool.add(it) } ?: -1
            val aName = pool.addAttr(a.name, a.resId)
            out.le32(aNs)
            out.le32(aName)
            if (a.type == TYPE_STRING) {
                val v = pool.add(a.raw ?: "")
                out.le32(v)
                out.le16(8)
                out.u8(0)
                out.u8(TYPE_STRING)
                out.le32(v)
            } else {
                out.le32(-1)
                out.le16(8)
                out.u8(0)
                out.u8(a.type)
                out.le32(a.data)
            }
        }
        for (child in el.children) writeElement(out, child, pool)
        out.startChunk(RES_XML_END_ELEM, 16, 24)
        out.le32(0)
        out.le32(-1)
        out.le32(nsIdx)
        out.le32(nameIdx)
        out.endChunk()
    }

    private fun writePool(out: Chunk, pool: Pool) {
        val strings = pool.list
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            offsets[i] = data.size()
            val encoded = strings[i].toByteArray(Charsets.UTF_8)
            writeUtf8Length(data, strings[i].length)
            writeUtf8Length(data, encoded.size)
            data.write(encoded)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)
        val headerSize = 28
        val stringsStart = headerSize + 4 * strings.size
        out.startChunk(RES_STRING_POOL, headerSize, stringsStart + data.size())
        out.le32(strings.size)
        out.le32(0)
        out.le32(UTF8_FLAG)
        out.le32(stringsStart)
        out.le32(0)
        for (o in offsets) out.le32(o)
        out.writeBytes(data.toByteArray())
        out.endChunk()
    }

    private fun writeResMap(out: Chunk, pool: Pool) {
        if (pool.resIds.none { it != 0 }) return
        out.startChunk(RES_XML_RES_MAP, 8, 8 + 4 * pool.resIds.size)
        for (id in pool.resIds) out.le32(id)
        out.endChunk()
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, value: Int) {
        if (value < 0x80) {
            out.write(value)
        } else {
            out.write((value shr 8) or 0x80)
            out.write(value and 0xFF)
        }
    }

    private fun readPool(r: Reader, dataStart: Int, chunkEnd: Int): List<String> {
        val count = r.u32()
        r.u32()
        val flags = r.u32()
        val stringsStart = r.u32()
        r.u32()
        val base = dataStart - 28 + stringsStart
        val utf8 = (flags and UTF8_FLAG) != 0
        val result = ArrayList<String>(count)
        for (i in 0 until count) {
            val offset = r.u32()
            val pos = base + offset
            result.add(if (utf8) readUtf8(r.bytes, pos, chunkEnd) else readUtf16(r.bytes, pos, chunkEnd))
        }
        return result
    }

    private fun readUtf8(b: ByteArray, start: Int, end: Int): String {
        var p = start
        var len = b[p].toInt() and 0xFF
        if (len and 0x80 != 0) {
            len = ((len and 0x7F) shl 8) or (b[p + 1].toInt() and 0xFF)
            p += 2
        } else {
            p += 1
        }
        var byteLen = b[p].toInt() and 0xFF
        if (byteLen and 0x80 != 0) {
            byteLen = ((byteLen and 0x7F) shl 8) or (b[p + 1].toInt() and 0xFF)
            p += 2
        } else {
            p += 1
        }
        val limit = minOf(p + byteLen, end)
        return String(b, p, limit - p, Charsets.UTF_8)
    }

    private fun readUtf16(b: ByteArray, start: Int, end: Int): String {
        var p = start
        var len = ((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8))
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or
                ((b[p + 2].toInt() and 0xFF) or ((b[p + 3].toInt() and 0xFF) shl 8))
            p += 4
        } else {
            p += 2
        }
        val limit = minOf(p + len * 2, end)
        return String(b, p, limit - p, Charsets.UTF_16LE)
    }

    private fun str(strings: List<String>, idx: Int): String =
        if (idx in strings.indices) strings[idx] else ""

    private fun putU32(b: ByteArray, p: Int, v: Int) {
        b[p] = (v and 0xFF).toByte()
        b[p + 1] = ((v ushr 8) and 0xFF).toByte()
        b[p + 2] = ((v ushr 16) and 0xFF).toByte()
        b[p + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private class Pool {
        val list = ArrayList<String>()
        val resIds = ArrayList<Int>()
        private val map = HashMap<String, Int>()

        fun add(s: String): Int {
            map[s]?.let { return it }
            val i = list.size
            list.add(s)
            resIds.add(0)
            map[s] = i
            return i
        }

        fun addAttr(s: String, resId: Int): Int {
            val i = add(s)
            if (resId != 0) resIds[i] = resId
            return i
        }
    }

    private class Chunk {
        val out = ByteArrayOutputStream()
        private val open = ArrayList<Int>()
        private val ranges = ArrayList<IntArray>()

        fun u8(v: Int) = out.write(v and 0xFF)

        fun le16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }

        fun le32(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }

        fun writeBytes(b: ByteArray) = out.write(b)

        fun startChunk(type: Int, headerSize: Int, bodySize: Int) {
            open.add(out.size())
            le16(type)
            le16(headerSize)
            le32(bodySize)
        }

        fun endChunk() {
            if (open.isEmpty()) return
            val start = open.removeAt(open.size - 1)
            ranges.add(intArrayOf(start, out.size()))
        }

        fun finish(): ByteArray {
            val bytes = out.toByteArray()
            for (r in ranges) putU32(bytes, r[0] + 4, r[1] - r[0])
            return bytes
        }
    }

    private class Reader(val bytes: ByteArray) {
        var pos = 0

        fun seek(p: Int) {
            pos = p
        }

        fun skip(n: Int) {
            pos += n
        }

        fun u8(): Int {
            val v = bytes[pos].toInt() and 0xFF
            pos += 1
            return v
        }

        fun u16(): Int {
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v
        }

        fun u32(): Int {
            val v = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
    }
}
