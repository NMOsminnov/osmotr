package kg.osmotr.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kg.osmotr.core.File

/** Присланный или выбранный файл: имя и содержимое. */
class Picked(val name: String, val bytes: ByteArray)

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
