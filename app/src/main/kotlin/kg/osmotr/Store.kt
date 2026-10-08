package kg.osmotr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Хранилище осмотров: папка в приложении — настоящий каталог в `Осмотры/` в корне памяти
 * телефона (подключил по USB — скопировал, всё разложено). Базы нет: файловая система —
 * единственный источник правды.
 *
 * Миниатюры — во внутренней памяти приложения (повторяют вложенность), чтобы сетка не
 * декодировала снимки и чтобы в выгружаемую папку не попадало служебное.
 */
object Store {
    lateinit var root: File
        private set
    /** Служебное приложения (кэш разбора описей) — не в «Осмотрах». */
    lateinit var cacheDir: File
        private set
    private lateinit var thumbs: File
    private lateinit var trashRoot: File
    private lateinit var app: Context

    /** Растёт на каждую правку — экраны перечитывают свои папки. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    fun changed() { _version.value++ }

    /** Миниатюры и регистрация файлов — в фоне, одним потоком: съёмка не ждёт. */
    private val background = Executors.newSingleThreadExecutor { Thread(it, "osmotr-thumbs").apply { priority = Thread.MIN_PRIORITY } }

    const val THUMB_PX = 384

    fun init(context: Context, base: File = Environment.getExternalStorageDirectory()) {
        app = context.applicationContext
        root = File(base, "Осмотры")
        thumbs = File(app.filesDir, "thumbs")
        cacheDir = File(app.filesDir, "cache")
        // Корзина — рядом с «Осмотрами», на том же томе: удаление и возврат — переименования,
        // мгновенно. Не в Android/data приложения: туда Android 11+ не даёт переносить каталоги
        // со снимками, и папка просто не удалялась. Скрытая и с .nomedia — в галерею не лезет.
        trashRoot = File(base, ".Осмотры-корзина")
        if (trashRoot.mkdirs()) File(trashRoot, ".nomedia").createNewFile()
        background.execute { purgeTrash() }
    }

    /** Можно ли писать в корень памяти: Android 11+ — «доступ ко всем файлам», 8–10 — запись. */
    fun canWrite(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun ensureRoot(): Boolean = root.isDirectory || root.mkdirs()

    // ---------- Чтение ----------

    data class FolderTile(val dir: File, val photos: Int, val folders: Int, val cover: File?, val note: String?)
    data class Listing(val folders: List<FolderTile>, val photos: List<File>)

    /** Снимок — непустой JPEG или HEIC (так по умолчанию пишут многие штатные камеры). */
    fun isPhoto(f: File) = f.isFile && !f.name.startsWith(".") && f.length() > 0 &&
        f.extension.lowercase() in PHOTO_EXT

    private val PHOTO_EXT = setOf("jpg", "jpeg", "heic", "heif")

    /** Снимки папки — новые первыми, как в галерее: вернулся с камеры — снятое сразу на виду. */
    fun photosIn(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter(::isPhoto).sortedWith(NATURAL_FILES.reversed())

    fun foldersIn(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }.sortedWith(NATURAL_FILES)

    /** Содержимое папки: вложенные (с числом снимков всего и обложкой — последним снимком) и снимки. */
    fun list(dir: File): Listing {
        val folders = foldersIn(dir).map { d ->
            var photos = 0
            var folders = 0
            var cover: File? = null
            d.walkTopDown().onEnter { !it.name.startsWith(".") }.forEach { f ->
                if (f.isDirectory) { if (f != d) folders++ }
                else if (isPhoto(f)) {
                    photos++
                    if (cover == null || f.lastModified() > cover!!.lastModified()) cover = f
                }
            }
            FolderTile(d, photos, folders, cover, note(d))
        }
        return Listing(folders, photosIn(dir))
    }

    /** Путь от корня: «ТЭЦ-2 › Насос 1»; корень — «Осмотры». */
    fun title(dir: File): String = relative(dir).ifEmpty { root.name }.replace(File.separator, " › ")

    fun relative(f: File): String = f.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)

    // ---------- Папки ----------

    /** Имя папки без недопустимых в файловой системе знаков. */
    fun cleanName(name: String): String = name.trim().replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
        .replace(Regex("""\s+"""), " ").trim().trimEnd('.')

    /** Новая папка; null — имя пустое или занято. */
    fun createFolder(parent: File, name: String): File? {
        val clean = cleanName(name)
        if (clean.isEmpty()) return null
        val dir = File(parent, clean)
        if (dir.exists() || !dir.mkdirs()) return null
        changed()
        return dir
    }

    fun renameFolder(dir: File, name: String): File? {
        val clean = cleanName(name)
        if (clean.isEmpty() || dir == root) return null
        val to = File(dir.parentFile, clean)
        if (to.exists()) return null
        val before = photoTree(dir)
        if (!dir.renameTo(to)) return null
        thumbDir(dir).let { if (it.exists()) it.renameTo(thumbDir(to)) }
        scan(before + photoTree(to))
        changed()
        return to
    }

    /**
     * Перенести папку в [to]; занято имя — «Имя (2)». В саму себя и во вложенную — нельзя (null).
     * Для ошибки «создал папку не на том уровне».
     */
    fun moveFolder(dir: File, to: File): File? {
        if (dir == root || to == dir || to.absolutePath.startsWith(dir.absolutePath + File.separator) || to == dir.parentFile) return null
        var target = File(to, dir.name)
        var n = 2
        while (target.exists()) target = File(to, "${dir.name} (${n++})")
        val before = photoTree(dir)
        if (!dir.renameTo(target)) return null
        thumbDir(dir).let { if (it.exists()) { thumbDir(target).parentFile?.mkdirs(); it.renameTo(thumbDir(target)) } }
        scan(before + photoTree(target))
        changed()
        return target
    }

    // ---------- Комментарий к папке ----------

    /** Комментарий лежит в самой папке обычным файлом — виден и по USB, уходит в архив. */
    const val NOTE = "Комментарий.txt"

    fun note(dir: File): String? = File(dir, NOTE).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    fun setNote(dir: File, text: String) {
        val f = File(dir, NOTE)
        if (text.isBlank()) f.delete() else writeDurably(f, (text.trim() + "\n").toByteArray())
        scan(listOf(f))
        changed()
    }

    /**
     * Записать файл так, чтобы он пережил севшую батарею и перезагрузку: во временный рядом,
     * сброс на диск (fsync), подмена. Прежде комментарий, записанный обычным writeText,
     * после резкого выключения оказывался пустым (найдено на эмуляторе, 08.10.2026).
     */
    fun writeDurably(f: File, bytes: ByteArray) = writeDurably(f) { it.write(bytes) }

    fun writeDurably(f: File, body: (java.io.OutputStream) -> Unit) {
        // Свой временный на каждую запись: два писателя одного файла с общим временным теряли его
        // (второй дописывал в уже переименованный и, не найдя своего, удалял готовый).
        val tmp = File(f.parentFile, ".${f.name}.${System.nanoTime()}.tmp")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                val buffered = out.buffered()
                body(buffered)
                buffered.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f) && tmp.isFile) { f.delete(); check(tmp.renameTo(f)) { "не записан ${f.name}" } }
        } finally { tmp.delete() }
    }

    // ---------- Контакты ----------

    /** Контакты людей — книгой Excel в самой папке (автор, 08.10.2026: «контакты человека тоже храним в папке… файлик excel»). */
    const val CONTACTS = "Контакты.xlsx"
    val CONTACT_COLUMNS = listOf("ФИО", "Должность", "Телефон", "Почта", "Примечание")

    data class Contact(val name: String = "", val role: String = "", val phone: String = "", val email: String = "", val note: String = "") {
        val blank get() = listOf(name, role, phone, email, note).all { it.isBlank() }
        fun cells() = listOf(name, role, phone, email, note).map { it.trim() }
        /** Для поиска: всё одной строкой. */
        fun text() = cells().filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** Строки книги; первая — заголовки (как ни правили на компьютере, если там «ФИО» — пропускается). */
    fun contacts(dir: File): List<Contact> {
        val f = File(dir, CONTACTS)
        if (!f.isFile) return emptyList()
        val rows = Xlsx.read(f)
        val body = if (rows.firstOrNull()?.firstOrNull()?.trim()?.equals("ФИО", ignoreCase = true) == true) rows.drop(1) else rows
        return body.map { r -> Contact(r.getOrElse(0) { "" }, r.getOrElse(1) { "" }, r.getOrElse(2) { "" }, r.getOrElse(3) { "" }, r.getOrElse(4) { "" }) }
            .filterNot { it.blank }
    }

    fun setContacts(dir: File, list: List<Contact>) {
        val f = File(dir, CONTACTS)
        val rows = list.filterNot { it.blank }
        if (rows.isEmpty()) f.delete() else Xlsx.write(f, listOf(CONTACT_COLUMNS) + rows.map { it.cells() })
        scan(listOf(f))
        changed()
    }

    // ---------- Корзина ----------

    /** Удалённое: что куда убрано — для «Вернуть». */
    data class Trashed(val items: List<Pair<File, File>>, val photos: Int, val folders: Int)

    /**
     * Удалить снимки и папки — в корзину, не насовсем: по ошибке удалённое возвращается
     * («Вернуть» внизу экрана). Корзина чистится через сутки.
     */
    fun trash(items: Collection<File>): Trashed? {
        if (items.isEmpty()) return null
        val bin = File(trashRoot, System.currentTimeMillis().toString()).apply { mkdirs() }
        var photos = 0; var folders = 0
        val gone = mutableListOf<File>()
        val moved = items.filter { it != root && it.exists() }.mapNotNull { f ->
            val inside = if (f.isDirectory) photoTree(f) else listOf(f)
            val dst = File(bin, relative(f))
            dst.parentFile?.mkdirs()
            // Переименование не вышло (другой том, запрет системы) — скопировать и удалить.
            if (!f.renameTo(dst) && !runCatching { f.copyRecursively(dst, overwrite = true) && f.deleteRecursively() }.getOrDefault(false)) {
                return@mapNotNull null
            }
            if (f.isDirectory || dst.isDirectory) { folders++; thumbDir(f).deleteRecursively() } else thumbFile(f).delete()
            photos += inside.size
            gone += inside
            f to dst
        }
        if (moved.isEmpty()) { bin.deleteRecursively(); return null }
        scan(gone)
        changed()
        return Trashed(moved, photos, folders)
    }

    fun restore(t: Trashed) {
        val back = t.items.mapNotNull { (orig, dst) ->
            orig.parentFile?.mkdirs()
            var to = orig
            var n = 2
            while (to.exists()) to = File(orig.parentFile, orig.nameWithoutExtension + " (${n++})" + (if (orig.extension.isEmpty()) "" else "." + orig.extension))
            if (dst.renameTo(to)) to else null
        }
        scan(back.flatMap { if (it.isDirectory) photoTree(it) else listOf(it) })
        changed()
    }

    private fun purgeTrash(maxAgeMs: Long = 24 * 3600 * 1000L) {
        val now = System.currentTimeMillis()
        trashRoot.listFiles()?.forEach { bin -> bin.name.toLongOrNull()?.let { if (now - it > maxAgeMs) bin.deleteRecursively() } }
    }

    fun photoTree(dir: File): List<File> = dir.walkTopDown().filter(::isPhoto).toList()

    // ---------- Снимки ----------

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)

    /** Файл для нового снимка: `<папка>_<ГГГГММДД_ЧЧММСС>_<NNN>.<ext>`, время — съёмки. */
    @Synchronized
    fun newPhotoFile(dir: File, ext: String = "jpg", takenMs: Long = System.currentTimeMillis()): File {
        dir.mkdirs()
        val prefix = (if (dir == root) "Осмотр" else dir.name) + "_" + stamp.format(Date(takenMs)) + "_"
        var n = 1
        while (true) {
            val f = File(dir, prefix + n.toString().padStart(3, '0') + "." + ext)
            if (!f.exists()) return f
            n++
        }
    }

    /** Снимки легли в папку: миниатюры и регистрация — в фоне, одной пачкой; экран — одно обновление. */
    fun saved(photos: List<File>, removed: List<File> = emptyList()) {
        if (photos.isEmpty()) return
        background.execute {
            scan(photos + removed)
            photos.forEach { makeThumb(it) }
        }
        changed()
    }
    fun saved(photo: File) = saved(listOf(photo))

    /** Перенести снимки в папку; имя занято — с номером. */
    fun move(photos: Collection<File>, to: File) {
        to.mkdirs()
        val moved = photos.mapNotNull { f ->
            var target = File(to, f.name)
            var n = 2
            while (target.exists()) target = File(to, f.nameWithoutExtension + "_" + n++ + "." + f.extension)
            if (!f.renameTo(target)) return@mapNotNull null
            thumbFile(f).let { t -> if (t.exists()) { thumbFile(target).parentFile?.mkdirs(); t.renameTo(thumbFile(target)) } }
            target
        }
        scan(photos + moved)
        changed()
    }

    // ---------- Миниатюры ----------

    private fun thumbDir(dir: File) = File(thumbs, relative(dir))
    fun thumbFile(photo: File) = File(thumbs, relative(photo))

    /** Миниатюра есть — её; нет — сделать в фоне, а пока показывать сам снимок (Coil уменьшит). */
    fun thumbOrPhoto(photo: File): File {
        val t = thumbFile(photo)
        if (t.isFile && t.lastModified() >= photo.lastModified()) return t
        background.execute { makeThumb(photo) }
        return photo
    }

    /**
     * Миниатюра ~384 px: декодирование с `inSampleSize` (12 МП в память не поднимаются),
     * поворот по EXIF, JPEG 80.
     */
    fun makeThumb(photo: File): File? = runCatching {
        val t = thumbFile(photo)
        if (t.isFile && t.lastModified() >= photo.lastModified()) return t
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, bounds)
        if (bounds.outWidth <= 0) return null
        val bmp = BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, THUMB_PX)
        }) ?: return null
        val upright = rotateByExif(bmp, photo)
        t.parentFile?.mkdirs()
        t.outputStream().buffered().use { upright.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (upright !== bmp) upright.recycle()
        bmp.recycle()
        t
    }.getOrNull()

    /** Наибольший `inSampleSize` (степень двойки), при котором меньшая сторона не меньше [target]. */
    fun sampleFor(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (minOf(w, h) / (s * 2) >= target) s *= 2
        return s
    }

    fun exifDegrees(photo: File): Int = runCatching {
        when (ExifInterface(photo.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    fun rotateByExif(bmp: Bitmap, photo: File): Bitmap {
        val deg = exifDegrees(photo)
        if (deg == 0) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }

    // ---------- Система ----------

    /** Зарегистрировать (или снять) файлы в системе: без этого по USB и в галерее их не видно. */
    fun scan(files: Collection<File>) {
        if (files.isEmpty() || !::app.isInitialized) return
        MediaScannerConnection.scanFile(app, files.map { it.absolutePath }.toTypedArray(), null, null)
    }

    /** «Насос 2» раньше «Насос 10»: числа внутри имени сравниваются как числа. */
    val NATURAL: Comparator<String> = Comparator { a, b ->
        val ra = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()).takeIf { it != 0 } ?: x.length.compareTo(y.length)
            else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size.compareTo(rb.size)
    }
    private val NATURAL_FILES: Comparator<File> = Comparator { a, b -> NATURAL.compare(a.name, b.name) }
}
