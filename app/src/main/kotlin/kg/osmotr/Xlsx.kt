package kg.osmotr

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Простейшая книга Excel (.xlsx): листы со строками текста и чисел. Своя, в сотню строк:
 * готовые библиотеки (Apache POI) весят больше всего приложения.
 *
 * Пишет текст ячейками inlineStr — телефоны остаются текстом, Excel не превращает их в числа.
 * Читает и то, что пишет Excel после правки на компьютере: общие строки (sharedStrings),
 * встроенные строки и числа.
 */
object Xlsx {
    /** Лист для записи: имя, ширины столбцов, строки (первая — заголовки). */
    class Out(val name: String, val rows: List<List<Any?>>, val widths: List<Int> = emptyList(), val rowStyle: (Int) -> Int = { 0 })

    /** Стили ячеек: обычный, жирный заголовок, зелёная строка (сделано), розовая (не сделано). */
    const val PLAIN = 0; const val HEADER = 1; const val GREEN = 2; const val RED = 3

    /** Один лист текста — контакты. */
    fun write(file: File, rows: List<List<String>>) = writeBook(file, listOf(Out("Контакты", rows, listOf(32, 24, 20, 28, 40))))

    /**
     * Книга из нескольких листов. Текст — ячейками inlineStr (номера и телефоны не превращаются в
     * числа), числа — числами (суммы складываются в Excel). Заголовок закреплён и с фильтром.
     */
    fun writeBook(file: File, sheets: List<Out>) = Store.writeDurably(file) { stream ->
        val z = ZipOutputStream(stream)
        fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        val names = uniqueNames(sheets.map { it.name })
        val n = sheets.size
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            (1..n).joinToString("") { """<Override PartName="/xl/worksheets/sheet$it.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } +
            """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
            names.mapIndexed { i, nm -> """<sheet name="${esc(nm)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>")
        put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            (1..n).joinToString("") { """<Relationship Id="rId$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$it.xml"/>""" } +
            """<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFC6EFCE"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFC7CE"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="4"><xf xfId="0"/><xf xfId="0" fontId="1" applyFont="1"/><xf xfId="0" fillId="2" applyFill="1"/><xf xfId="0" fillId="3" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
        sheets.forEachIndexed { k, sh ->
            val x = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
            if (sh.widths.isNotEmpty()) {
                x.append("<cols>")
                sh.widths.forEachIndexed { i, w -> x.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
                x.append("</cols>")
            }
            x.append("<sheetData>")
            val width = sh.rows.maxOfOrNull { it.size } ?: 0
            sh.rows.forEachIndexed { r, row ->
                x.append("""<row r="${r + 1}">""")
                val style = if (r == 0) HEADER else sh.rowStyle(r)
                val s = if (style != PLAIN) """ s="$style"""" else ""
                row.forEachIndexed { c, v ->
                    val ref = "${col(c)}${r + 1}"
                    when (v) {
                        null -> if (s.isNotEmpty()) x.append("""<c r="$ref"$s/>""")
                        is Number -> x.append("""<c r="$ref"$s><v>$v</v></c>""")
                        else -> x.append("""<c r="$ref" t="inlineStr"$s><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>""")
                    }
                }
                x.append("</row>")
            }
            x.append("</sheetData>")
            if (sh.rows.size > 1 && width > 0) x.append("""<autoFilter ref="A1:${col(width - 1)}${sh.rows.size}"/>""")
            x.append("</worksheet>")
            put("xl/worksheets/sheet${k + 1}.xml", x.toString())
        }
        // Запись целиком, сброс на диск, потом подмена (Store.writeDurably): оборванная
        // запись не портит прежний файл.
        z.finish()
    }

    /** Имена листов по правилам Excel: до 31 знака, без []:*?/\, без повторов. */
    private fun uniqueNames(names: List<String>): List<String> {
        val used = HashSet<String>()
        return names.map { raw ->
            val base = raw.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().ifEmpty { "Лист" }.take(28)
            var name = base; var i = 2
            while (!used.add(name.lowercase())) name = "$base ${i++}"
            name
        }
    }

    /** Строки первого листа; пустые ячейки — "". Не книга или повреждена — пусто. */
    fun read(file: File): List<List<String>> = sheets(file).firstOrNull()?.rows.orEmpty()

    /** Лист книги: имя и строки (пустые ячейки — ""). */
    class Sheet(val name: String, val rows: List<List<String>>)

    /**
     * Все листы книги по порядку — как их видно в Excel. Описи бывают из нескольких списков:
     * каждый — своим листом. Не книга .xlsx или повреждена — пусто.
     */
    fun sheets(file: File, progress: ((Float, String) -> Unit)? = null, rowLimit: Int = Int.MAX_VALUE): List<Sheet> = runCatching {
        ZipFile(file).use { z ->
            fun text(name: String) = z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } }
            val shared = text("xl/sharedStrings.xml")?.let(::sharedStrings).orEmpty()
            // Имена листов — из workbook.xml, файлы — по связям workbook.xml.rels.
            val rels = text("xl/_rels/workbook.xml.rels")?.let(::relations).orEmpty()
            val named = text("xl/workbook.xml")?.let(::sheetRefs).orEmpty()
            val parts = named.mapNotNull { (name, rid) ->
                val target = rels[rid] ?: return@mapNotNull null
                val path = if (target.startsWith("/")) target.drop(1) else "xl/" + target.removePrefix("./")
                name to path
            }.ifEmpty { z.entries().asSequence().map { it.name }.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.mapIndexed { i, p -> "Лист ${i + 1}" to p }.toList() }
            // Листы читаются потоком: сколько байт прочитано из всех листов — столько и прогресса.
            val total = parts.sumOf { (_, path) -> z.getEntry(path)?.size?.coerceAtLeast(1) ?: 0L }.coerceAtLeast(1)
            var done = 0L
            parts.mapNotNull { (name, path) ->
                val e = z.getEntry(path) ?: return@mapNotNull null
                val base = done
                val rows = z.getInputStream(e).use { raw ->
                    val counting = object : java.io.FilterInputStream(raw.buffered(1 shl 16)) {
                        var read = 0L; var told = 0L
                        private fun tell() { if (read - told > 256 * 1024) { told = read; progress?.invoke((base + read).toFloat() / total, name) } }
                        override fun read(): Int = super.read().also { if (it >= 0) { read++; tell() } }
                        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { read += it; tell() } }
                    }
                    cells(counting, shared, rowLimit)
                }
                done += e.size.coerceAtLeast(1)
                progress?.invoke(done.toFloat() / total, name)
                Sheet(name, rows)
            }
        }
    }.getOrDefault(emptyList())

    private fun sheetRefs(bytes: ByteArray): List<Pair<String, String>> {
        val p = parser(bytes)
        val out = mutableListOf<Pair<String, String>>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                val rid = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).endsWith("id") && p.getAttributeName(it) != "sheetId" }
                    ?.let { p.getAttributeValue(it) }
                out += (p.getAttributeValue(null, "name") ?: "Лист") to rid.orEmpty()
            }
        }
        return out
    }

    private fun relations(bytes: ByteArray): Map<String, String> {
        val p = parser(bytes)
        val out = HashMap<String, String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship")
                out[p.getAttributeValue(null, "Id").orEmpty()] = p.getAttributeValue(null, "Target").orEmpty()
        }
        return out
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val p = parser(bytes)
        val out = mutableListOf<String>()
        var current: StringBuilder? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when {
                p.eventType == XmlPullParser.START_TAG && p.name == "si" -> current = StringBuilder()
                p.eventType == XmlPullParser.START_TAG && p.name == "t" -> current?.append(p.nextText())
                p.eventType == XmlPullParser.END_TAG && p.name == "si" -> { out += current.toString(); current = null }
            }
        }
        return out
    }

    /**
     * Ячейки листа строками (номер строки — как в Excel, пустые строки — пустыми списками).
     * Сторонние программы пишут ячейки без адреса — тогда по порядку. Объединённые ячейки
     * заполняются значением на весь диапазон: шапка в два этажа («Стоимость» над
     * «первоначальная») читается целиком, раздел на всю ширину — в каждом столбце.
     */
    private fun cells(input: java.io.InputStream, shared: List<String>, rowLimit: Int = Int.MAX_VALUE): List<List<String>> {
        val p = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, "UTF-8")
        }
        val rows = sortedMapOf<Int, MutableMap<Int, String>>()
        val merges = mutableListOf<String>()
        var row = 0; var col = -1; var ref = ""; var type = ""; var value = StringBuilder(); var inCell = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                "row" -> { row = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (row + 1); col = -1; if (row > rowLimit) break }
                "c" -> { ref = p.getAttributeValue(null, "r").orEmpty(); type = p.getAttributeValue(null, "t").orEmpty(); value = StringBuilder(); inCell = true }
                // Текст ячейки — <v> или <t> (в том числе кусками разметки); формула <f> — не значение.
                "v", "t" -> if (inCell) value.append(p.nextText())
                "rPh" -> skip(p)  // фонетическая подсказка — не текст ячейки
                "mergeCell" -> p.getAttributeValue(null, "ref")?.let(merges::add)
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "c") {
                inCell = false
                val raw = value.toString()
                val v = when (type) {
                    "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                    "inlineStr", "str", "e" -> raw
                    "b" -> if (raw == "1") "ИСТИНА" else "ЛОЖЬ"
                    else -> raw.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: raw  // 996555123456.0 → 996555123456
                }
                col = ref.takeWhile { it.isLetter() }.takeIf { it.isNotEmpty() }?.let(::colIndex) ?: (col + 1)
                if (col >= 0 && v.isNotEmpty()) rows.getOrPut(row) { mutableMapOf() }[col] = v
            }
        }
        for (m in merges) {
            val (from, to) = m.split(':').takeIf { it.size == 2 } ?: continue
            fun at(r: String) = (r.dropWhile { it.isLetter() }.toIntOrNull() ?: 0) to colIndex(r.takeWhile { it.isLetter() })
            val (r1, c1) = at(from); val (r2, c2) = at(to)
            val v = rows[r1]?.get(c1) ?: continue
            if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1) > 5000) continue  // объединение на весь лист — не размножать
            for (r in r1..r2) for (c in c1..c2) rows.getOrPut(r) { mutableMapOf() }.putIfAbsent(c, v)
        }
        val last = rows.keys.maxOrNull() ?: return emptyList()
        return (1..last).map { r -> rows[r]?.let { m -> List((m.keys.maxOrNull() ?: -1) + 1) { m[it].orEmpty() } } ?: emptyList() }
    }

    private fun skip(p: XmlPullParser) { var depth = 1; while (depth > 0) when (p.next()) { XmlPullParser.START_TAG -> depth++; XmlPullParser.END_TAG -> depth-- } }

    private fun parser(bytes: ByteArray) = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(bytes), "UTF-8")
    }

    private fun col(i: Int): String { var n = i + 1; val s = StringBuilder(); while (n > 0) { s.insert(0, 'A' + (n - 1) % 26); n = (n - 1) / 26 }; return s.toString() }
    private fun colIndex(letters: String): Int = if (letters.isEmpty()) -1 else letters.uppercase().fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) } - 1
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it >= ' ' }
}
