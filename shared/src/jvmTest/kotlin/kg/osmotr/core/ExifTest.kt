package kg.osmotr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Время съёмки из EXIF — для фото из галереи и «Поделиться» на iPhone (14.10.2026). */
class ExifTest {
    private fun jpeg(vararg dates: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) + "Exif\u0000\u0000II*".encodeToByteArray() +
            dates.joinToString("\u0000") { it }.encodeToByteArray() + ByteArray(100)

    private fun local(y: Int, m: Int, d: Int, h: Int, mi: Int) =
        java.util.Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, mi, 0) }.timeInMillis

    @Test fun время_съёмки_по_местному() {
        // DateTime, затем DateTimeOriginal — берётся последнее из найденных (оно и есть съёмка).
        assertEquals(local(2026, 10, 8, 9, 30), Exif.taken(jpeg("2026:10:09 18:00:00", "2026:10:08 09:30:00")))
    }

    @Test fun нет_даты_или_мусор_нет_времени() {
        assertNull(Exif.taken(ByteArray(64)))
        assertNull(Exif.taken(jpeg("0000:00:00 00:00:00")))
    }
}
