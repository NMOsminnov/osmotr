package kg.osmotr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Телеметрия без личных данных (автор, 13.10.2026: «Телеметрия доступна вся, кроме личных
 * данных»; 14.10.2026: «подготовить всю телеметрию… Все покрываем тестами»).
 */
class TelemetryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
        Telemetry.limit = 512 * 1024L
    }

    private fun log() = File(Telemetry.dir(), "log.txt").readText()

    @Test fun почта_и_телефоны_вычищаются_а_числа_остаются() {
        val s = Telemetry.clean("вход ivan.petrov@mail.kg не прошёл; звонить +996 (555) 12-34-56 или 0555123456; 5709 предметов за 1834 мс, строка 12, 013/2385")
        assertFalse(s, "ivan.petrov" in s || "mail.kg" in s)
        assertFalse(s, "12-34-56" in s || "0555123456" in s)
        assertTrue(s, "<почта>" in s && "<номер>" in s)
        assertTrue(s, "5709 предметов за 1834 мс, строка 12, 013/2385" in s)
    }

    @Test fun события_пишутся_с_версией_и_без_личного() {
        Telemetry.start("тесты", "3.4 (11)", "Linux", "JVM")
        Telemetry.event("экран", "опись")
        Telemetry.error("загрузка", IllegalStateException("файл от petrov@mail.kg повреждён"))
        val t = log()
        assertTrue(t, "запуск: Осмотр 3.4 (11) · тесты Linux · JVM" in t)
        assertTrue(t, "экран: опись" in t)
        assertTrue(t, "ошибка · загрузка: IllegalStateException: файл от <почта> повреждён" in t)
        assertFalse(t, "petrov" in t)
    }

    @Test fun журнал_не_растёт_без_конца() {
        Telemetry.limit = 4 * 1024L
        repeat(400) { Telemetry.event("разбор", "строка $it ".repeat(3)) }
        val now = File(Telemetry.dir(), "log.txt"); val before = File(Telemetry.dir(), "log.1.txt")
        assertTrue(now.length() <= 4 * 1024L && before.length() <= 4 * 1024L)
        assertTrue("последнее — в нынешнем", "строка 399" in now.readText())
    }

    @Test fun падение_файлом_предлагается_один_раз() {
        Telemetry.start("тесты", "3.4", "Linux", "JVM")
        Telemetry.event("экран", "опись")
        Telemetry.crash(RuntimeException("сломалось у +996 555 123 456", IllegalArgumentException("причина")))
        val crashes = Telemetry.unsentCrashes()
        assertEquals(1, crashes.size)
        val text = crashes.single().readText()
        assertTrue(text, "RuntimeException: сломалось у <номер>" in text)
        assertTrue(text, "причина: IllegalArgumentException: причина" in text)
        assertTrue("что делали перед сбоем", "экран: опись" in text)
        assertTrue("дата не принята за телефон", Regex("""Падение «Осмотра» · \d{4}-\d\d-\d\d \d\d:\d\d:\d\d""").containsMatchIn(text))
        Telemetry.markSent()
        assertTrue(Telemetry.unsentCrashes().isEmpty())
    }

    @Test fun в_архиве_нет_имён_комментариев_и_контактов() {
        val obj = Inventory.create("Объект Иванова") { sink ->
            val f = File(tmp.root.path + "/b.xlsx")
            Xlsx.writeBook(f, listOf(Xlsx.Out("Опись", listOf(listOf("№", "Наименование", "Инв. номер"), listOf(1, "Стол", "5/1"), listOf(2, "Стул", "5/2")))))
            sink.write(f.readBytes())
        }
        Store.setNote(obj, "ключ у Петрова")
        Store.setContacts(obj, listOf(Store.Contact("Петров Пётр", phone = "+996 555 000 111", email = "p@mail.kg")))
        Inventory.parse(Inventory.fileIn(obj)!!)  // разбор пишет в телеметрию
        Telemetry.crash(IllegalStateException("тест"))
        File(Telemetry.dir(), "metrickit").mkdirs(); File(Telemetry.dir(), "metrickit/diag-1.json").writeText("{}")
        val zip = Telemetry.pack(File(tmp.root.path + "/диагностика.zip"))
        val parts = unzip(zip.readBytes())
        assertTrue(parts.keys.toString(), parts.keys.containsAll(listOf("сводка.txt", "log.txt", "metrickit/diag-1.json")) && parts.keys.any { it.startsWith("crash-") })
        val all = parts.values.joinToString("\n") { it.decodeToString() }
        for (secret in listOf("Иванова", "Петров", "555 000 111", "p@mail.kg", "ключ у")) assertFalse(secret, secret in all)
        assertTrue(all, "опись ${Telemetry.id("Объект Иванова")}: предметов 2" in all)
        assertTrue("разбор — отпечатком имени", "разбор описи ${Telemetry.id("Опись — Объект Иванова.xlsx")}" in all)
    }
}
