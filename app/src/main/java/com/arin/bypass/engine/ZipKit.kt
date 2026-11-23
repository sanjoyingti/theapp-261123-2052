package com.arin.bypass.engine

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Minimal, control-first zip reader/writer. Writing our own central directory keeps
 * resources.arsc stored and 4-byte aligned, which target 30+ packages require.
 */
object ZipKit {

    private val DROPPED = Regex("META-INF/(.+\\.(SF|RSA|DSA|EC)|MANIFEST\\.MF)", RegexOption.IGNORE_CASE)

    fun entries(apk: File): List<String> =
        ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }.toList() }

    fun contains(apk: File, name: String): Boolean =
        ZipFile(apk).use { zip -> zip.getEntry(name) != null }

    fun read(apk: File, name: String): ByteArray? = ZipFile(apk).use { zip ->
        val entry = zip.getEntry(name) ?: return null
        zip.getInputStream(entry).use { it.readBytes() }
    }

    fun unpack(apk: File, dir: File): Int {
        var count = 0
        ZipFile(apk).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val out = File(dir, entry.name)
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                count++
            }
        }
        return count
    }

    /** Copies [src] to [dst] applying deletions (null value) and additions/replacements. */
    fun rewrite(src: File, dst: File, replace: Map<String, ByteArray?>, add: Map<String, ByteArray>) {
        val written = HashMap<String, Long>()
        val crc = HashMap<String, Long>()
        val sizes = HashMap<String, Long>()
        val methods = HashMap<String, Int>()
        val names = ArrayList<String>()
        val offset = LongArray(1)
        dst.parentFile?.mkdirs()
        FileOutputStream(dst).use { fos ->
            ZipFile(src).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (entry.isDirectory) continue
                    if (replace.containsKey(name)) {
                        val body = replace[name] ?: continue
                        addOne(fos, name, body, offset, written, crc, sizes, methods, names)
                        continue
                    }
                    if (DROPPED.matches(name)) continue
                    val body = zip.getInputStream(entry).use(InputStream::readBytes)
                    addOne(fos, name, body, offset, written, crc, sizes, methods, names)
                }
            }
            for ((name, body) in add) {
                if (written.containsKey(name)) continue
                addOne(fos, name, body, offset, written, crc, sizes, methods, names)
            }
            val cdStart = offset[0]
            for (name in names) {
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val buf = java.io.ByteArrayOutputStream()
                buf.writeLe32(0x02014b50)
                buf.writeLe16(20)
                buf.writeLe16(20)
                buf.writeLe16(0)
                buf.writeLe16(methods[name] ?: 8)
                buf.writeLe16(0)
                buf.writeLe16(0x21)
                buf.writeLe32(crc[name] ?: 0)
                buf.writeLe32(sizes[name] ?: 0)
                buf.writeLe32(sizes[name] ?: 0)
                buf.writeLe16(nameBytes.size)
                buf.writeLe16(0)
                buf.writeLe16(0)
                buf.writeLe16(0)
                buf.writeLe16(0)
                buf.writeLe32(0)
                buf.writeLe32(written[name] ?: 0)
                buf.write(nameBytes)
                fos.write(buf.toByteArray())
            }
            val cdSize = offset[0] - cdStart
            val eocd = java.io.ByteArrayOutputStream()
            eocd.writeLe32(0x06054b50)
            eocd.writeLe16(0)
            eocd.writeLe16(0)
            eocd.writeLe16(names.size)
            eocd.writeLe16(names.size)
            eocd.writeLe32(cdSize)
            eocd.writeLe32(cdStart)
            eocd.writeLe16(0)
            fos.write(eocd.toByteArray())
        }
    }

    private fun addOne(
        fos: FileOutputStream,
        name: String,
        body: ByteArray,
        offset: LongArray,
        written: HashMap<String, Long>,
        crc: HashMap<String, Long>,
        sizes: HashMap<String, Long>,
        methods: HashMap<String, Int>,
        names: ArrayList<String>
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val method = if (name == "resources.arsc" || name.endsWith(".so")) 0 else 8
        val payload = if (method == 8) deflate(body) else body
        val checksum = CRC32().apply { update(body) }.value
        var extraLen = 0
        if (method == 0) {
            val base = offset[0] + 30 + nameBytes.size
            if (base % 4 != 0L) extraLen = (4 - (base % 4)).toInt() + 4
        }
        val start = offset[0]
        val buf = java.io.ByteArrayOutputStream()
        buf.writeLe32(0x04034b50)
        buf.writeLe16(20)
        buf.writeLe16(0)
        buf.writeLe16(method)
        buf.writeLe16(0)
        buf.writeLe16(0x21)
        buf.writeLe32(checksum)
        buf.writeLe32(payload.size.toLong())
        buf.writeLe32(body.size.toLong())
        buf.writeLe16(nameBytes.size)
        buf.writeLe16(extraLen)
        buf.write(nameBytes)
        if (extraLen > 0) {
            buf.writeLe16(0)
            buf.writeLe16(extraLen - 4)
            buf.write(ByteArray(extraLen - 4))
        }
        buf.write(payload)
        val bytes = buf.toByteArray()
        fos.write(bytes)
        offset[0] = start + bytes.size
        written[name] = start
        crc[name] = checksum
        sizes[name] = body.size.toLong()
        methods[name] = method
        names.add(name)
    }

    private fun deflate(body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out).use { it.write(body) }
        return out.toByteArray()
    }

    private fun java.io.ByteArrayOutputStream.writeLe16(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    private fun java.io.ByteArrayOutputStream.writeLe32(v: Long) {
        write((v and 0xFF).toInt())
        write(((v shr 8) and 0xFF).toInt())
        write(((v shr 16) and 0xFF).toInt())
        write(((v shr 24) and 0xFF).toInt())
    }

    fun isApk(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().any { it.name == "AndroidManifest.xml" }
        }
    }.getOrDefault(false)

    fun entryNames(file: File): List<ZipEntry> = ZipFile(file).use { zip -> zip.entries().toList() }
}
