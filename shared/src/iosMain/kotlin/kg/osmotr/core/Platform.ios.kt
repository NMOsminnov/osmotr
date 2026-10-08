package kg.osmotr.core

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

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

@kotlin.native.concurrent.ThreadLocal
private object Memo { val map = HashMap<String, Int>() }

actual fun memoMap(): MutableMap<String, Int> = Memo.map
