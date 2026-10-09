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

    /** Ключ журнала в тестах — в памяти процесса. */
    private val pair by lazy { java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair() }
    private fun keyPair(): java.security.KeyPair = pair

    actual fun deviceName(): String = "Компьютер (тесты)"

}
