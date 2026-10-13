package kg.osmotr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Образец самого сложного по алгоритмам отчёта (автор, 13.10.2026: «самый сложный по алгоритмам
 * отчёт, но не по количеству записей… Опись + часть осмотренное… стоимости может не быть,
 * приоритетов может не быть»).
 *
 * Опись — 23-hardest.xlsx; три дня работы — функциями приложения: снимки, папка на несколько
 * одинаковых, вложенная папка, комментарий, нерабочее, голосовая заметка, импорт «осмотрено
 * ранее» с заголовками и чужим номером, два «№6» без инвентарника, латиница в инвентарнике,
 * папка не из описи; потом правка мимо приложения (подмена, пропажа, подброс). Проверки — что
 * отчёт всё это видит. С OSMOTR_REPORT_SAMPLE=<папка> объект целиком кладётся туда.
 */
class ReportSampleTest {
    @get:Rule val tmp = TemporaryFolder()

    private val день = 86_400_000L
    private val начало = java.util.Calendar.getInstance().apply { set(2026, 9, 6, 10, 0, 0) }.timeInMillis  // 6 октября, 10:00

    /** Снимок — настоящий JPEG с подписью, «снят» в день [d] (0, 1, 2) и минуту [m]. */
    private fun снимок(dir: File, имя: String, подпись: String, d: Int, m: Int = 0): File {
        val img = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(40 + d * 40, 70, 110); g.fillRect(0, 0, 640, 480)
        g.color = Color.WHITE; g.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
        подпись.split('\n').forEachIndexed { k, l -> g.drawString(l, 30, 70 + k * 44) }
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22); g.drawString("день ${d + 1}, образец «Осмотра»", 30, 440)
        g.dispose()
        val f = File(dir, имя)
        java.io.File(f.path).outputStream().use { ImageIO.write(img, "jpg", it) }
        java.io.File(f.path).setLastModified(начало + d * день + m * 60_000L)
        Journal.add(dir, "Снимок", Store.relative(f), f)
        return f
    }

    @Test fun образец_сложного_отчёта() {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
        val obj = Store.createFolder(Store.root, "Объект «Сложный»")!!
        java.io.File(System.getProperty("templates"), "23-hardest.xlsx")
            .copyTo(java.io.File(obj.path, Inventory.PREFIX + "Объект «Сложный».xlsx"))
        val всё = Inventory.parse(Inventory.fileIn(obj)!!).items
        fun по(inv: String) = всё.first { it.inventory == inv }

        // День 1: три одинаковых стула — одной папкой; сервер — с комментарием и нерабочий.
        val стулья = Inventory.group(obj, listOf(по("ОС-000101"), по("ОС-000102"), по("ОС-000103")))
        снимок(стулья, "стулья_1.jpg", "Стул офисный ИЗО ×3\nОС-000101…103", 0, 5)
        снимок(стулья, "стулья_2.jpg", "Стулья, бирки", 0, 6)
        val сервер = Inventory.openFolder(obj, по("777/1002"))
        снимок(сервер, "сервер.jpg", "Сервер Dell R640\n777/1002", 0, 30)
        Store.setNote(сервер, "гудит вентилятор, корпус треснут")
        Inventory.setBroken(obj, listOf(по("777/1002")), true)
        val стол = Inventory.openFolder(obj, всё.first { it.name.startsWith("Стол") })  // инвентарник латиницей
        снимок(стол, "стол.jpg", "Стол письменный\nOC-000104 (латиница)", 0, 40)

        // День 2: монитор (инвентарник из наименования) — со вложенной папкой «до ремонта»;
        // два «№6» без инвентарника; голосовая заметка.
        val монитор = Inventory.openFolder(obj, по("013/4501"))
        снимок(монитор, "монитор.jpg", "Монитор Samsung 24\nинв. 013/4501", 1, 10)
        val доРемонта = Store.createFolder(монитор, "до ремонта")!!
        снимок(доРемонта, "трещина.jpg", "до ремонта:\nтрещина матрицы", 1, 11)
        val шестые = всё.filter { it.inventory.isEmpty() && it.number == "6" }
        val перегородка = Inventory.openFolder(obj, шестые[0]); снимок(перегородка, "перегородка.jpg", "Перегородка, №6\nбез инвентарника", 1, 20)
        val паллета = Inventory.openFolder(obj, шестые[1]); снимок(паллета, "паллета.jpg", "Паллета, №6 (Склад)\nбез инвентарника", 1, 25)
        val голос = Store.newVoiceFile(сервер).also { java.io.File(it.path).writeBytes(ByteArray(2048) { k -> (k % 7).toByte() }) }
        Store.voiceSaved(голос)

        // День 3: огнетушитель; папка не из описи; импорт «осмотрено ранее».
        val огнетушитель = Inventory.openFolder(obj, по("Н179"))
        снимок(огнетушитель, "огнетушитель.jpg", "Огнетушитель ОП-5\nН179", 2, 5)
        val лишняя = Store.createFolder(obj, "Принтер без бирки")!!
        снимок(лишняя, "принтер.jpg", "Принтер без бирки\n(нет в описи)", 2, 15)
        val отметки = Xlsx.Sheet("Отметки 2025", listOf(
            listOf("Отсмотрено в 2025 году", "Отсмотрено в 2025 году", "Отсмотрено в 2025 году"),
            listOf("№", "Инв. №", "Кто"),
            listOf("1", "INV-2024-0001", "Петров"),
            listOf("2", "КГ123456", "Петров"),                     // без пробелов, в описи «КГ 123 456»
            listOf("Коридор", "Коридор", "Коридор"),
            listOf("3", "0001234 (у входа)", "Сидоров"),
            listOf("4", "999/999", "Сидоров"),                      // чужой
        ), wide = setOf(0, 4))
        val ранее = Inventory.importEarlier(obj, отметки, 1, "отметки-2025.xlsx")
        assertEquals(3, ранее.fresh); assertEquals(listOf("Инв. №", "999/999"), ранее.unknown)

        // Мимо приложения: подменили снимок, удалили другой, подбросили третий.
        java.io.File(File(огнетушитель, "огнетушитель.jpg").path).appendBytes(byteArrayOf(1, 2, 3))
        java.io.File(File(стол, "стол.jpg").path).delete()
        java.io.File(File(перегородка, "подброшен.jpg").path).writeBytes(ByteArray(300) { 9 })

        Journal.forget()
        Inventory.writeReport(obj)
        val отчёт = Inventory.reportFile(Inventory.fileIn(obj)!!)
        val книга = Xlsx.sheets(отчёт.readBytes())
        val имена = книга.map { it.name }
        assertTrue(имена.toString(), имена.containsAll(listOf("Итог", "Корпус 1", "Склад", "По дням", "Нет в описи", "Журнал", "Сверка файлов")))
        val корпус = книга.first { it.name == "Корпус 1" }.rows
        val h = корпус[0]
        fun строка(inv: String) = корпус.first { it.getOrNull(h.indexOf("Инв. номер")) == inv }
        assertEquals("Да", строка("INV-2024-0001")[h.indexOf("Осмотрено")])
        assertEquals("Да", строка("ОС-000102")[h.indexOf("Осмотрено")])  // в общей папке
        assertEquals("Нерабочее", строка("777/1002")[h.indexOf("Состояние")])
        assertEquals("Нет", строка("Н180")[h.indexOf("Осмотрено")])
        val сверка = книга.first { it.name == "Сверка файлов" }.rows.drop(1).map { it[1] }.toSet()
        assertTrue(сверка.toString(), сверка.containsAll(setOf("изменён", "пропал без записи", "появился без записи")))
        val нет = книга.first { it.name == "Нет в описи" }.rows
        assertTrue(нет.any { r -> r.any { it.contains("Принтер без бирки") } })
        assertTrue("вложенная папка предмета — не «нет в описи»", нет.none { r -> r.any { it.contains("до ремонта") } })
        // Цен и приоритетов в описи нет — нет и столбцов, и блока приоритетов, и «стоимости 0».
        assertTrue(h.toString(), h.none { it.contains("стоимость") || it == "Сумма" || it == "Приоритет" || it == "Список" })
        val итог = книга.first { it.name == "Итог" }.rows
        assertTrue(итог.toString(), итог.none { r -> r.any { it.contains("стоимость") || it == "Приоритет" || it.startsWith("П") && it.length <= 3 } })

        val куда = System.getProperty("reportSample").orEmpty()
        if (куда.isNotEmpty()) java.io.File(obj.path).copyRecursively(java.io.File(куда, obj.name), overwrite = true)
    }
}
