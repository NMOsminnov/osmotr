package kg.osmotr.core

import kg.osmotr.ui.Picked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Фото из галереи и из WhatsApp («Поделиться») — снимками в папку (автор, 14.10.2026). */
class AddPhotosTest {
    @get:Rule val tmp = TemporaryFolder()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(60) { 1 }
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(60) { 2 }

    @Test fun фото_ложатся_снимками_с_журналом_и_считаются_у_предмета() {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
        val obj = Store.createFolder(Store.root, "Склад")!!
        Xlsx.writeBook(File(obj, Inventory.PREFIX + "склад.xlsx"), listOf(Xlsx.Out("Опись", listOf(
            listOf("№ п/п", "Основное средство", "Инв.Номер"), listOf(1, "Ноутбук", "555/1")))))
        val item = Inventory.parse(Inventory.fileIn(obj)!!).items.single()
        val dir = Inventory.openFolder(obj, item)
        val taken = java.util.Calendar.getInstance().apply { set(2026, 9, 8, 9, 30, 0) }.timeInMillis
        val out = Store.addPhotos(dir, listOf(
            Triple("IMG-20261008-WA0001.jpeg", jpeg, taken),   // WhatsApp: .jpeg, время из EXIF
            Triple("", png, null),                            // имя не сказали — расширение по началу файла
        ))
        assertEquals(listOf("jpg", "png"), out.map { it.extension })
        assertTrue(out[0].name.contains("20261008_093000"))
        assertTrue(out.all { Store.isPhoto(it) })
        Thread.sleep(300)  // журнал и миниатюры пишутся в фоне (Store.saved)
        assertEquals(2, Inventory.status(obj).photosOf(item))
        assertTrue(Journal.entries(obj).count { it.what == "Снимок" } >= 2)
    }

    @Test fun фото_узнаются_по_имени_и_по_началу_файла() {
        assertTrue(Picked("x.HEIC", ByteArray(20)).isPhoto)
        assertTrue(Picked("", jpeg).isPhoto)
        assertTrue(Picked("", png).isPhoto)
        assertFalse(Picked("опись.xlsx", byteArrayOf(0x50, 0x4B, 3, 4) + ByteArray(20)).isPhoto)
    }
}
