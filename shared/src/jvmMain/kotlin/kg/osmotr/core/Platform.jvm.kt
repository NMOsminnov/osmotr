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
