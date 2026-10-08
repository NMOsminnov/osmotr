package kg.osmotr.core

import okio.BufferedSink
import okio.Path.Companion.toPath

/**
 * Zip без сжатия — для книг Excel и архивов осмотра: снимки JPEG уже сжаты, а без сжатия
 * архив собирается в разы быстрее. Пишет потоком в [sink]; имена — UTF-8 (флаг 0x0800).
 */
class ZipWriter(private val sink: BufferedSink) {
    private class Entry(val name: ByteArray, val crc: Int, val size: Long, val packed: Long, val method: Int, val offset: Long)
    private val entries = mutableListOf<Entry>()
    private var offset = 0L

    private fun u16(v: Int) { sink.writeShortLe(v); offset += 2 }
    private fun u32(v: Long) { sink.writeIntLe(v.toInt()); offset += 4 }

    /** Запись целиком из памяти (части книг Excel, тексты) — со сжатием: XML жмётся в 10–20 раз. */
    fun add(name: String, bytes: ByteArray) {
        val packed = deflateRaw(bytes)?.takeIf { it.size < bytes.size }
        if (packed != null) add(name, bytes.size.toLong(), Crc32.of(bytes), packed.size.toLong(), 8) { it.write(packed) }
        else add(name, bytes.size.toLong(), Crc32.of(bytes)) { it.write(bytes) }
    }

    /** Запись файла: размер и CRC считаются заранее — отдельным проходом (снимки читаются дважды, зато не в память). */
    fun add(file: File, name: String, progress: (Long) -> Unit = {}) {
        val crc = Crc32()
        val size = file.length()
        systemFs.read(file.path.toPath()) {
            val buf = ByteArray(1 shl 16)
            while (true) { val n = read(buf); if (n < 0) break; crc.update(buf, 0, n) }
        }
        add(name, size, crc.value) { out ->
            systemFs.read(file.path.toPath()) {
                val buf = ByteArray(1 shl 16)
                while (true) { val n = read(buf); if (n < 0) break; out.write(buf, 0, n); progress(n.toLong()) }
            }
        }
    }

    private fun add(name: String, size: Long, crc: Int, packed: Long = size, method: Int = 0, body: (BufferedSink) -> Unit) {
        val nm = name.encodeToByteArray()
        val start = offset
        u32(0x04034b50); u16(20); u16(0x0800); u16(method); u16(0); u16(0x21)  // заголовок, UTF-8, способ, время 1980
        u32(crc.toLong() and 0xFFFFFFFFL); u32(packed); u32(size); u16(nm.size); u16(0)
        sink.write(nm); offset += nm.size
        body(sink); offset += packed
        entries += Entry(nm, crc, size, packed, method, start)
    }

    fun finish() {
        val dirStart = offset
        for (e in entries) {
            u32(0x02014b50); u16(20); u16(20); u16(0x0800); u16(e.method); u16(0); u16(0x21)
            u32(e.crc.toLong() and 0xFFFFFFFFL); u32(e.packed); u32(e.size); u16(e.name.size); u16(0); u16(0); u16(0); u16(0); u32(0)
            u32(e.offset)
            sink.write(e.name); offset += e.name.size
        }
        val dirSize = offset - dirStart
        u32(0x06054b50); u16(0); u16(0); u16(entries.size); u16(entries.size); u32(dirSize); u32(dirStart); u16(0)
        sink.flush()
    }
}

/** CRC-32 (как в zip). */
class Crc32 {
    private var c = -1
    fun update(b: ByteArray, from: Int = 0, len: Int = b.size) {
        var x = c
        for (i in from until from + len) x = TABLE[(x xor b[i].toInt()) and 0xFF] xor (x ushr 8)
        c = x
    }
    val value: Int get() = c.inv()
    companion object {
        private val TABLE = IntArray(256) { n -> var v = n; repeat(8) { v = if (v and 1 != 0) (v ushr 1) xor 0xEDB88320.toInt() else v ushr 1 }; v }
        fun of(b: ByteArray) = Crc32().apply { update(b) }.value
    }
}
