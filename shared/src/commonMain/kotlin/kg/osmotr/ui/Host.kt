package kg.osmotr.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kg.osmotr.core.File

/**
 * Присланный или выбранный файл: имя, содержимое; [takenMs] — когда снято (фото: EXIF), если известно.
 *
 * Содержимое — в памяти или во временном файле ([file], Android: присланные фото переписываются
 * потоком — сотня снимков по 4 МБ в памяти роняла бы приложение). [bytes] у файлового читает файл.
 */
class Picked private constructor(val name: String, private val data: ByteArray?, val takenMs: Long?, val file: File?, private val start: ByteArray?) {
    constructor(name: String, bytes: ByteArray, takenMs: Long? = null) : this(name, bytes, takenMs, null, null)

    val bytes: ByteArray get() = data ?: file!!.readBytes()

    companion object {
        /** Содержимое — во временном файле; [head] — его начало (вид по содержимому). */
        fun ofFile(name: String, file: File, takenMs: Long?, head: ByteArray) = Picked(name, null, takenMs, file, head)
    }

    /** Фото (по расширению или по началу файла): JPEG, HEIC/HEIF, PNG, WebP. */
    val isPhoto: Boolean get() {
        if (name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "heic", "heif", "png", "webp")) return true
        val b = start ?: data ?: return false
        return b.size > 12 && (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() ||
            b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() ||
            b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte() ||
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte())
    }
}

/** Снято штатной камерой: куда и сколько. */
class Shot(val dir: File, val count: Int)

/**
 * Что делает только платформа: камера, выбор файлов, «Поделиться», сообщения, контакты.
 * Android — MainActivity, iPhone — iosMain. Экраны пишутся один раз (общие) и зовут его.
 */
interface Host {
    /** Снимать в [dir] штатной камерой (снятое забирается в [afterResume]). */
    fun takePhotos(dir: File)
    /** Вернулись в приложение: если снимали — что легло и куда; иначе null. */
    suspend fun afterResume(): Shot?
    /** Можно ли писать в хранилище «Осмотров» (Android — доступ к памяти; iPhone — всегда). */
    fun canWrite(): Boolean
    /** Попросить доступ к памяти (настройки системы). */
    fun requestStorage()
    /** Выбрать книгу(и) Excel. */
    fun pickBooks(multiple: Boolean, got: (List<Picked>) -> Unit)
    /** Есть ли выбор фото из галереи (пока — Android). */
    val canPickGallery: Boolean get() = false
    /** Выбрать фото из галереи — несколько сразу. */
    fun pickGallery(got: (List<Picked>) -> Unit) {}
    /** Отправить файлы через «Поделиться» (мессенджер, почта, «Файлы»). */
    fun share(files: List<File>, mime: String, title: String = "Отправить")
    /** Короткое сообщение внизу экрана. */
    fun toast(text: String, long: Boolean = false)
    /** Выбрать контакт из телефонной книги: имя и телефон; null — нет такой возможности. */
    val pickPhone: ((got: (name: String, phone: String) -> Unit) -> Unit)?
    /** Папка для временных файлов (архивы перед отправкой). */
    val cacheDir: File

    // Диктофон — голосовые заметки к папке и к описи (AAC, .m4a, рядом со снимками).
    /** Начать запись в [file]; false — нет доступа к микрофону (попросили — нажать ещё раз). */
    fun startRecording(file: File): Boolean = false
    /** Остановить запись; true — записалось. */
    fun stopRecording(): Boolean = false
    /** Громкость сейчас, 0…1 — полоска, что микрофон слышит. */
    fun recordingLevel(): Float = 0f
    /** Проиграть запись; [done] — доиграла или остановили. */
    fun play(file: File, done: () -> Unit) { done() }
    fun stopPlaying() {}
    /** Длительность записи, мс; 0 — неизвестно. */
    fun duration(file: File): Long = 0
}

val LocalHost = staticCompositionLocalOf<Host> { error("Host не задан") }
