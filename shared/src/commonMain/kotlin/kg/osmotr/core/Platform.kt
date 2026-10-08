package kg.osmotr.core

/** Распаковать .xlsx (zip) целиком: имя файла внутри → содержимое. Не zip — пусто. */
expect fun unzip(bytes: ByteArray): Map<String, ByteArray>

/** Память для оценок заголовков: на JVM — общая потокобезопасная, на iOS — своя у потока. */
expect fun memoMap(): MutableMap<String, Int>
