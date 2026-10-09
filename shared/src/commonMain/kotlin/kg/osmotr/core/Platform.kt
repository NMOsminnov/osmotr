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

    // Журнал описи (защита отчёта от подмены): отпечатки и подпись ключом телефона.
    /** SHA-256. */
    fun sha256(bytes: ByteArray): ByteArray
    /** SHA-256 файла — потоком, без чтения снимка в память целиком. */
    fun sha256File(path: String): ByteArray
    /** Открытый ключ телефона — точка P-256 без сжатия (65 байт, 04‖x‖y); ключ создаётся при первом обращении. */
    fun publicKey(): ByteArray
    /** Подпись ECDSA P-256 / SHA-256 закрытым ключом телефона (DER). Закрытый ключ телефон не покидает. */
    fun sign(data: ByteArray): ByteArray
    /** Проверить подпись [signature] данных [data] открытым ключом [publicKey] (65 байт). */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
    /** Модель телефона — «кто» в журнале, если человек не назвался. */
    fun deviceName(): String
}
