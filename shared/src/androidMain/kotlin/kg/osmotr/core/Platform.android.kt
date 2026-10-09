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
    actual fun log(msg: String) { android.util.Log.i("osmotr", msg) }

    // ---------- Журнал описи: отпечатки и подпись ----------

    actual fun sha256(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    actual fun sha256File(path: String): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(path).use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest()
    }

    actual fun publicKey(): ByteArray = rawPoint(keyPair().public as java.security.interfaces.ECPublicKey)

    actual fun sign(data: ByteArray): ByteArray =
        java.security.Signature.getInstance("SHA256withECDSA").run { initSign(keyPair().private); update(data); sign() }

    actual fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        // Точка P-256 → X.509 SubjectPublicKeyInfo: постоянный заголовок и 65 байт точки.
        val spki = P256_SPKI_HEADER + publicKey
        val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(spki))
        java.security.Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(signature) }
    }.getOrDefault(false)

    private val P256_SPKI_HEADER = byteArrayOf(0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00)

    private fun rawPoint(k: java.security.interfaces.ECPublicKey): ByteArray {
        fun fixed(b: java.math.BigInteger): ByteArray { val a = b.toByteArray(); return when { a.size == 32 -> a; a.size > 32 -> a.copyOfRange(a.size - 32, a.size); else -> ByteArray(32 - a.size) + a } }
        return byteArrayOf(4) + fixed(k.w.affineX) + fixed(k.w.affineY)
    }

    /** Ключ журнала — в Android Keystore: закрытая часть телефон не покидает и не копируется. */
    private fun keyPair(): java.security.KeyPair {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? java.security.KeyStore.PrivateKeyEntry)?.let { return java.security.KeyPair(it.certificate.publicKey, it.privateKey) }
        val gen = java.security.KeyPairGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS, android.security.keystore.KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256).build())
        return gen.generateKeyPair()
    }
    private const val KEY_ALIAS = "osmotr-journal"

    actual fun deviceName(): String = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ")

}
