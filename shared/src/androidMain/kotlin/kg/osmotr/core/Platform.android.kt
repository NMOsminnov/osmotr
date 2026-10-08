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
    actual fun scan(paths: List<String>) {
        val ctx = context ?: return
        if (paths.isNotEmpty()) android.media.MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
    }
    /** Приложение задаёт при запуске (MainActivity): без него медиатеке не сообщить. */
    var context: android.content.Context? = null
    actual fun makeThumb(photo: String, out: String, px: Int): Boolean = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(photo, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(photo, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val deg = when (androidx.exifinterface.media.ExifInterface(photo).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val upright = if (deg == 0f) bmp else android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
        java.io.File(out).parentFile?.mkdirs()
        java.io.File(out).outputStream().buffered().use { upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        if (upright !== bmp) upright.recycle()
        bmp.recycle()
        true
    }.getOrDefault(false)
}
