package kg.osmotr

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File

/**
 * Запись галереи после переноса снимка (15.10.2026). На Android 11+ перенос сам переводит запись на
 * новый путь, и удаление записи удаляет файл: 3.8 стирала каждый снимок (поймано на эмуляторе).
 * Удалять можно только запись, которая указывает на старый путь, где файла уже нет. Галерея здесь —
 * подделка: удаление записи удаляет файл, как в настоящем MediaProvider.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GalleryRowTest {
    @get:Rule val tmp = TemporaryFolder()

    class FakeMedia : ContentProvider() {
        val rows = HashMap<Long, String>()
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
            MatrixCursor(arrayOf(MediaStore.Images.Media.DATA)).apply { rows[uri.lastPathSegment!!.toLong()]?.let { addRow(arrayOf(it)) } }
        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int {
            val path = rows.remove(uri.lastPathSegment!!.toLong()) ?: return 0
            File(path).delete()   // как MediaProvider: запись уходит вместе с файлом
            return 1
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, v: ContentValues?): Uri? = null
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private fun media(): FakeMedia =
        Robolectric.buildContentProvider(FakeMedia::class.java).create(MediaStore.AUTHORITY).get()

    @Test fun запись_уже_на_новом_месте_не_трогается_и_снимок_цел() {
        val m = media()
        val old = File(tmp.root, "DCIM/IMG_1.jpg")
        val moved = File(tmp.root, "Осмотры/Склад/МТ-101/МТ-101_1.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(100)) }
        m.rows[7] = moved.absolutePath   // система перевела запись при переносе
        assertFalse(SystemCamera.dropStaleRow(ApplicationProvider.getApplicationContext(), 7, old))
        assertTrue("снимок цел", moved.exists())
        assertEquals(moved.absolutePath, m.rows[7])
    }

    @Test fun запись_на_старом_пути_где_пусто_удаляется() {
        val m = media()
        val old = File(tmp.root, "DCIM/IMG_2.jpg")
        m.rows[8] = old.absolutePath    // запись осталась на старом месте — чёрный квадрат в галерее
        assertTrue(SystemCamera.dropStaleRow(ApplicationProvider.getApplicationContext(), 8, old))
        assertTrue(8L !in m.rows)
    }

    @Test fun запись_на_старом_пути_где_файл_есть_не_трогается() {
        val m = media()
        val old = File(tmp.root, "DCIM/IMG_3.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        m.rows[9] = old.absolutePath
        assertFalse(SystemCamera.dropStaleRow(ApplicationProvider.getApplicationContext(), 9, old))
        assertTrue(old.exists())
    }

    @Test fun записи_нет_ничего_не_делаем() {
        media()
        assertFalse(SystemCamera.dropStaleRow(ApplicationProvider.getApplicationContext(), 10, File(tmp.root, "x.jpg")))
    }
}
