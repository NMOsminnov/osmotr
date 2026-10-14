package kg.osmotr.core

/**
 * Когда снято — из EXIF фото, без платформенных библиотек (iPhone: фото из галереи и «Поделиться»;
 * на Android то же делает ExifInterface). Дата в EXIF — текстом «ГГГГ:ММ:ДД ЧЧ:ММ:СС» по местному
 * времени снимка: ищется в начале файла (JPEG и HEIC держат EXIF там); DateTimeOriginal обычно
 * идёт после DateTime — берётся последняя из первых найденных, она и есть время съёмки.
 */
object Exif {
    fun taken(bytes: ByteArray): Long? {
        val head = bytes.copyOfRange(0, minOf(bytes.size, 128 * 1024))
        var found: Long? = null
        var hits = 0
        var i = 0
        while (i + 19 <= head.size && hits < 3) {
            val t = at(head, i)
            if (t != null) { found = t; hits++; i += 19 } else i++
        }
        return found
    }

    /** «2026:10:08 09:30:00» в позиции [i] — время (мс, местное); не оно — null. */
    private fun at(b: ByteArray, i: Int): Long? {
        fun d(k: Int) = (b[i + k] - '0'.code.toByte()).takeIf { it in 0..9 }
        val shape = "dddd:dd:dd dd:dd:dd"
        for (k in shape.indices) {
            val c = b[i + k].toInt().toChar()
            if (shape[k] == 'd') { if (c !in '0'..'9') return null } else if (c != shape[k]) return null
        }
        fun n(from: Int, len: Int) = (0 until len).fold(0) { acc, k -> acc * 10 + d(from + k)!! }
        val y = n(0, 4); val mo = n(5, 2); val dd = n(8, 2); val h = n(11, 2); val mi = n(14, 2); val s = n(17, 2)
        if (y < 1990 || mo !in 1..12 || dd !in 1..31 || h > 23 || mi > 59 || s > 60) return null
        return local(y, mo, dd, h, mi, s)
    }

    /** Местное время → мс от эпохи: дни по календарю, сдвиг пояса — как сегодня у телефона. */
    private fun local(y: Int, m: Int, d: Int, h: Int, mi: Int, s: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097L + doe - 719_468L
        val utc = ((days * 24 + h) * 60 + mi) * 60_000L + s * 1000L
        // Сдвиг пояса (UTC+6 — плюс 6 ч): полночь UTC против местной полуночи; местное → UTC — вычесть его.
        val now = Platform.nowMs()
        val raw = (now - now.mod(86_400_000L)) - Platform.startOfDay()
        val offset = if (raw > 43_200_000L) raw - 86_400_000L else if (raw < -43_200_000L) raw + 86_400_000L else raw
        return utc - offset
    }
}
