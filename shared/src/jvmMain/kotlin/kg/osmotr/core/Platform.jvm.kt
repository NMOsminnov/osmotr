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
}
