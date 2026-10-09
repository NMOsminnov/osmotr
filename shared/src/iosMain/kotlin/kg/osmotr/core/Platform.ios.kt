@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package kg.osmotr.core

import kotlinx.cinterop.memScoped
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Security.SecKeyRef
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256

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
    // Текст — целиком, без подстановок: Kotlin-строку в «%@» NSLog не передать (сбой SIGSEGV).
    actual fun log(msg: String) = platform.Foundation.NSLog("osmotr: " + msg.replace("%", "%%"))

    // ---------- Журнал описи: отпечатки и подпись ----------
    // SHA-256 — CommonCrypto; ключ P-256 — Security: создаётся один раз, закрытая часть — файлом в
    // служебной папке приложения (не в «Файлах»), подпись — ECDSA X9.62 / SHA-256 (DER, как на Android).

    actual fun sha256(bytes: ByteArray): ByteArray {
        val out = UByteArray(32)
        out.usePinned { o -> if (bytes.isEmpty()) CC_SHA256(null, 0u, o.addressOf(0)) else bytes.usePinned { b -> CC_SHA256(b.addressOf(0), bytes.size.toUInt(), o.addressOf(0)) } }
        return out.asByteArray()
    }

    actual fun sha256File(path: String): ByteArray = memScoped {
        val ctx = alloc<CC_SHA256_CTX>()
        CC_SHA256_Init(ctx.ptr)
        val f = platform.posix.fopen(path, "rb") ?: return sha256(ByteArray(0))
        val buf = ByteArray(1 shl 16)
        buf.usePinned { b ->
            while (true) {
                val n = platform.posix.fread(b.addressOf(0), 1u, buf.size.toULong(), f).toInt()
                if (n <= 0) break
                CC_SHA256_Update(ctx.ptr, b.addressOf(0), n.toUInt())
            }
        }
        platform.posix.fclose(f)
        val out = UByteArray(32)
        out.usePinned { o -> CC_SHA256_Final(o.addressOf(0), ctx.ptr) }
        out.asByteArray()
    }

    private fun nsData(b: ByteArray): NSData = if (b.isEmpty()) NSData() else b.usePinned { NSData.dataWithBytes(it.addressOf(0), b.size.toULong()) }
    private fun bytes(d: NSData): ByteArray {
        val out = ByteArray(d.length.toInt())
        if (out.isNotEmpty()) out.usePinned { platform.posix.memcpy(it.addressOf(0), d.bytes, d.length) }
        return out
    }
    @Suppress("UNCHECKED_CAST")
    private fun cf(d: NSData): CFDataRef = CFBridgingRetain(d) as CFDataRef
    private fun ns(d: CFDataRef?): NSData? = d?.let { CFBridgingRelease(it) as NSData }

    private fun keyAttrs(private: Boolean, size: Boolean): CFMutableDictionaryRef? {
        val a = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(a, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
        CFDictionaryAddValue(a, kSecAttrKeyClass, if (private) kSecAttrKeyClassPrivate else kSecAttrKeyClassPublic)
        if (size) CFDictionaryAddValue(a, kSecAttrKeySizeInBits, CFBridgingRetain(NSNumber(int = 256)))
        return a
    }

    private val keyFile get() = Store.cacheDir.parentFile!!.path + "/journal.key"
    private var privateKey: SecKeyRef? = null

    private fun key(): SecKeyRef {
        privateKey?.let { return it }
        val saved = NSData.dataWithContentsOfFile(keyFile)
        val k = if (saved != null) SecKeyCreateWithData(cf(saved), keyAttrs(private = true, size = false), null)
            else SecKeyCreateRandomKey(keyAttrs(private = true, size = true), null)?.also { k ->
                ns(SecKeyCopyExternalRepresentation(k, null))?.writeToFile(keyFile, atomically = true)
            }
        return k!!.also { privateKey = it }
    }

    actual fun publicKey(): ByteArray = ns(SecKeyCopyExternalRepresentation(SecKeyCopyPublicKey(key()), null))?.let(::bytes) ?: ByteArray(0)

    actual fun sign(data: ByteArray): ByteArray =
        ns(SecKeyCreateSignature(key(), kSecKeyAlgorithmECDSASignatureMessageX962SHA256, cf(nsData(data)), null))?.let(::bytes) ?: ByteArray(0)

    actual fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        val pub = SecKeyCreateWithData(cf(nsData(publicKey)), keyAttrs(private = false, size = false), null) ?: return false
        return SecKeyVerifySignature(pub, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, cf(nsData(data)), cf(nsData(signature)), null)
    }

    actual fun deviceName(): String = platform.UIKit.UIDevice.currentDevice.model

}
