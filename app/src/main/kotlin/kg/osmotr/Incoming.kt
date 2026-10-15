package kg.osmotr

import android.net.Uri
import android.provider.OpenableColumns
import kg.osmotr.ui.Picked

/**
 * Файл по ссылке: имя (мессенджер не сказал — пусто, имя описи тогда — из книги) и содержимое.
 * Фото — потоком во временный файл, не в память: из галереи выбирают до сотни снимков по
 * нескольку мегабайт, и всё разом в памяти роняло бы приложение (нагрузочный тест 15.10.2026).
 * Описи — в память: они маленькие и читаются целиком.
 */
fun readPicked(resolver: android.content.ContentResolver, uri: Uri, incoming: java.io.File): Picked? = runCatching {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { '.' in it } ?: ""
    val image = resolver.getType(uri)?.startsWith("image/") == true ||
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "heic", "heif", "png", "webp")
    if (!image) {
        val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
        return@runCatching Picked(name, bytes, takenOfBytes(bytes))
    }
    val dir = incoming.apply { mkdirs() }
    val tmp = java.io.File.createTempFile("in-", ".part", dir)
    resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
    val head = tmp.inputStream().use { s -> ByteArray(16).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
    val taken = runCatching { androidx.exifinterface.media.ExifInterface(tmp.absolutePath).let { it.dateTimeOriginal ?: it.dateTime } }.getOrNull()
    Picked.ofFile(name, kg.osmotr.core.File(tmp.absolutePath), taken, head)
}.getOrNull()

/** Когда снято — из EXIF (DateTimeOriginal, иначе DateTime); не фото или нет даты — null. */
internal fun takenOfBytes(bytes: ByteArray): Long? = runCatching {
    val exif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
    exif.dateTimeOriginal ?: exif.dateTime
}.getOrNull()
