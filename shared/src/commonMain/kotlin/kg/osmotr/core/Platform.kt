package kg.osmotr.core

/** Распаковать .xlsx (zip) целиком: имя файла внутри → содержимое. Не zip — пусто. */
expect fun unzip(bytes: ByteArray): Map<String, ByteArray>

/** Сжать (deflate без обёртки, как в zip); не вышло — null (тогда запись без сжатия). */
expect fun deflateRaw(bytes: ByteArray): ByteArray?

/** Память для оценок заголовков: на JVM — общая потокобезопасная, на iOS — своя у потока. */
expect fun memoMap(): MutableMap<String, Int>

/** Что умеет только платформа: время, даты, медиатека, превью снимков. */
expect object Platform {
    fun nowMs(): Long
    fun nanoTime(): Long
    /** Дата по шаблону («yyyyMMdd_HHmmss», «dd.MM.yyyy HH:mm») в местном времени. */
    fun format(ms: Long, pattern: String): String
    /** Полночь сегодня, местное время. */
    fun startOfDay(): Long
    /** Зарегистрировать (или снять) файлы в системе — Android: видно по USB и в галерее. */
    fun scan(paths: List<String>)
    /** Превью снимка ~[px] по меньшей стороне, повёрнутое по EXIF, JPEG. Не вышло — false. */
    fun makeThumb(photo: String, out: String, px: Int): Boolean
    /** Строка в системный журнал (Android — logcat, iPhone — консоль): для разбора медленного и ошибок. */
    fun log(msg: String)
}
