package kg.osmotr.core

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

actual fun unzip(bytes: ByteArray): Map<String, ByteArray> = runCatching {
    val out = HashMap<String, ByteArray>()
    ZipInputStream(bytes.inputStream()).use { z ->
        while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) out[e.name] = z.readBytes() }
    }
    out
}.getOrDefault(emptyMap())

actual fun deflateRaw(bytes: ByteArray): ByteArray? = runCatching {
    val d = java.util.zip.Deflater(6, true)
    d.setInput(bytes); d.finish()
    val out = java.io.ByteArrayOutputStream(bytes.size / 4 + 64)
    val buf = ByteArray(1 shl 16)
    while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
    d.end(); out.toByteArray()
}.getOrNull()

actual fun memoMap(): MutableMap<String, Int> = ConcurrentHashMap()

actual object Platform {
    actual fun nowMs(): Long = System.currentTimeMillis()
    actual fun nanoTime(): Long = System.nanoTime()
    actual fun format(ms: Long, pattern: String): String = java.text.SimpleDateFormat(pattern, java.util.Locale.ROOT).format(java.util.Date(ms))
    actual fun startOfDay(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    actual fun scan(paths: List<String>) {}
    actual fun makeThumb(photo: String, out: String, px: Int): Boolean = false
    actual fun log(msg: String) = println("osmotr: $msg")
}
