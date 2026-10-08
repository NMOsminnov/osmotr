package kg.osmotr

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
}
