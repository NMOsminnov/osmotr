package kg.osmotr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Снимки камеры — только её, без мессенджеров и снимков экрана. */
class SystemCameraTest {
    @Test fun в_папку_ложатся_только_снимки_камеры() {
        assertTrue(SystemCamera.fromCamera("/storage/emulated/0/DCIM/Camera/IMG_1.jpg", "com.oplus.camera", "com.oplus.camera"))
        assertFalse("мессенджер", SystemCamera.fromCamera("/storage/emulated/0/Pictures/Telegram/x.jpg", "org.telegram.messenger", "com.oplus.camera"))
        assertFalse("мессенджер сохранил в DCIM", SystemCamera.fromCamera("/storage/emulated/0/DCIM/WhatsApp/x.jpg", "com.whatsapp", "com.oplus.camera"))
        assertFalse("снимок экрана", SystemCamera.fromCamera("/storage/emulated/0/DCIM/Screenshots/Screenshot_1.jpg", "com.oplus.camera", "com.oplus.camera"))
        assertTrue("владелец неизвестен — DCIM", SystemCamera.fromCamera("/storage/emulated/0/DCIM/Camera/a.jpg", null, null))
        assertFalse("владелец неизвестен — не DCIM", SystemCamera.fromCamera("/storage/emulated/0/Pictures/Telegram/a.jpg", null, null))
    }

    /**
     * Сборщик прошлого захода не берёт снимки следующего (отзыв 15.10.2026: снимали быстро
     * пункты подряд — снимки ложились не в ту папку, а следующий заход терялся).
     */
    @Test fun граница_захода_начало_следующего() {
        val start = 1_000_000L
        assertEquals(start + SystemCamera.SESSION_MAX_MS, SystemCamera.sessionEnd(start, null))
        assertEquals(start + 60_000 - 1000, SystemCamera.sessionEnd(start, start + 60_000))
        assertEquals(start + SystemCamera.SESSION_MAX_MS, SystemCamera.sessionEnd(start, start + SystemCamera.SESSION_MAX_MS + 5000))
    }
}
