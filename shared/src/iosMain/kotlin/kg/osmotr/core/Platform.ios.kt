package kg.osmotr.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

actual fun unzip(bytes: ByteArray): Map<String, ByteArray> = runCatching {
    // openZip работает с файлом — книга во временный файл, потом прочь.
    val tmp = (NSTemporaryDirectory() + "/xlsx-" + NSUUID().UUIDString + ".zip").toPath()
    FileSystem.SYSTEM.write(tmp) { write(bytes) }
    try {
        val zip = FileSystem.SYSTEM.openZip(tmp)
        val out = HashMap<String, ByteArray>()
        zip.listRecursively("/".toPath()).forEach { p ->
            if (zip.metadataOrNull(p)?.isRegularFile == true) out[p.toString().removePrefix("/")] = zip.source(p).buffer().use { it.readByteArray() }
        }
        out
    } finally { FileSystem.SYSTEM.delete(tmp) }
}.getOrDefault(emptyMap())

@OptIn(ExperimentalForeignApi::class)
actual fun deflateRaw(bytes: ByteArray): ByteArray? = runCatching {
    kotlinx.cinterop.memScoped {
        val strm = alloc<platform.zlib.z_stream>()
        // −15 — без обёртки zlib (сырой deflate, как в zip).
        if (platform.zlib.deflateInit2_(strm.ptr, 6, platform.zlib.Z_DEFLATED, -15, 8, platform.zlib.Z_DEFAULT_STRATEGY,
                platform.zlib.ZLIB_VERSION, sizeOf<platform.zlib.z_stream>().toInt()) != platform.zlib.Z_OK) return@memScoped null
        val bound = platform.zlib.deflateBound(strm.ptr, bytes.size.toULong()).toInt() + 64
        val out = ByteArray(bound)
        val n = bytes.usePinned { src ->
            out.usePinned { dst ->
                strm.next_in = src.addressOf(0).reinterpret()
                strm.avail_in = bytes.size.toUInt()
                strm.next_out = dst.addressOf(0).reinterpret()
                strm.avail_out = bound.toUInt()
                val r = platform.zlib.deflate(strm.ptr, platform.zlib.Z_FINISH)
                platform.zlib.deflateEnd(strm.ptr)
                if (r != platform.zlib.Z_STREAM_END) -1 else strm.total_out.toInt()
            }
        }
        if (n < 0) null else out.copyOf(n)
    }
}.getOrNull()

@kotlin.native.concurrent.ThreadLocal
private object Memo { val map = HashMap<String, Int>() }

actual fun memoMap(): MutableMap<String, Int> = Memo.map

actual object Platform {
    actual fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
    actual fun nanoTime(): Long = (NSProcessInfo.processInfo.systemUptime * 1_000_000_000).toLong()
    actual fun format(ms: Long, pattern: String): String = NSDateFormatter().apply {
        dateFormat = pattern; locale = NSLocale(localeIdentifier = "en_US_POSIX")
    }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(ms / 1000.0))
    actual fun startOfDay(): Long = (NSCalendar.currentCalendar.startOfDayForDate(NSDate()).timeIntervalSince1970 * 1000).toLong()
    actual fun scan(paths: List<String>) {}  // на iPhone медиатеки для файлов нет
    @OptIn(ExperimentalForeignApi::class)
    actual fun makeThumb(photo: String, out: String, px: Int): Boolean {
        // UIImage сам учитывает поворот из EXIF при рисовании.
        val img = UIImage.imageWithContentsOfFile(photo) ?: return false
        val (w, h) = img.size.useContents { width to height }
        if (w <= 0.0 || h <= 0.0) return false
        val k = minOf(1.0, px / minOf(w, h))
        val nw = w * k; val nh = h * k
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(nw, nh), true, 1.0)
        img.drawInRect(CGRectMake(0.0, 0.0, nw, nh))
        val small = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        val data = small?.let { UIImageJPEGRepresentation(it, 0.8) } ?: return false
        File(out).parentFile?.mkdirs()
        return data.writeToFile(out, atomically = true)
    }
    actual fun log(msg: String) = platform.Foundation.NSLog("osmotr: %@", msg)
}
