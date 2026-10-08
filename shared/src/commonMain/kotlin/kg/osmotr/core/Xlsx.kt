package kg.osmotr.core

/**
 * Чтение книг Excel (.xlsx) — общее для Android и iPhone, без платформенного разбора XML: свой
 * проход по тегам (как в web/src/xlsx.ts). Объединённые ячейки заполняются значением на весь
 * диапазон (шапка в два этажа, разделы на всю ширину); ячейки без адреса — по порядку.
 */
object Xlsx {
    class Sheet(val name: String, val rows: List<List<String>>)

    /** Все листы книги по порядку. Не .xlsx или повреждена — пусто. [progress] — доля и имя листа. */
    fun sheets(book: ByteArray, progress: ((Float, String) -> Unit)? = null, rowLimit: Int = Int.MAX_VALUE): List<Sheet> {
        val files = unzip(book)
        if (files.isEmpty()) return emptyList()
        fun text(name: String) = files[name]?.decodeToString()
        val shared = text("xl/sharedStrings.xml")?.let(::sharedStrings).orEmpty()
        val rels = HashMap<String, String>()
        tags(text("xl/_rels/workbook.xml.rels").orEmpty(), "Relationship").forEach { a -> rels[attr(a, "Id").orEmpty()] = attr(a, "Target").orEmpty() }
        var parts = tags(text("xl/workbook.xml").orEmpty(), "sheet").mapNotNull { a ->
            val name = attr(a, "name") ?: "Лист"
            val rid = Regex("""\s[\w]*:id="([^"]*)"""").find(" $a")?.groupValues?.get(1).orEmpty()
            val target = rels[rid] ?: return@mapNotNull null
            name to (if (target.startsWith("/")) target.drop(1) else "xl/" + target.removePrefix("./"))
        }
        if (parts.isEmpty()) parts = files.keys.filter { Regex("""xl/worksheets/sheet\d+\.xml""").matches(it) }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.mapIndexed { i, p -> "Лист ${i + 1}" to p }
        val sizes = parts.map { (_, p) -> files[p]?.size ?: 0 }
        val total = sizes.sum().coerceAtLeast(1).toFloat()
        var done = 0
        return parts.mapIndexedNotNull { k, (name, path) ->
            val bytes = files[path] ?: return@mapIndexedNotNull null
            val base = done
            val rows = cells(bytes.decodeToString(), shared, { p -> progress?.invoke((base + p * sizes[k]) / total, name) }, rowLimit)
            done += sizes[k]
            progress?.invoke(done / total, name)
            Sheet(name, rows)
        }
    }

    private fun unescape(s: String): String {
        if ('&' !in s) return s
        return Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);""").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> {
                    val code = if (e[1] == 'x') e.drop(2).toInt(16) else e.drop(1).toInt()
                    if (code < 0x10000) code.toChar().toString() else {
                        val v = code - 0x10000
                        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
                    }
                }
            }
        }
    }

    private fun attr(tag: String, name: String): String? {
        var i = 0
        while (true) {
            i = tag.indexOf("$name=\"", i)
            if (i < 0) return null
            if (i == 0 || tag[i - 1].isWhitespace()) {
                val start = i + name.length + 2
                val end = tag.indexOf('"', start)
                return if (end < 0) null else unescape(tag.substring(start, end))
            }
            i += name.length
        }
    }

    /** Атрибуты всех тегов [name] (открывающих и пустых). */
    private fun tags(xml: String, name: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (true) {
            i = xml.indexOf("<$name", i)
            if (i < 0) return out
            val after = i + name.length + 1
            if (after < xml.length && (xml[after].isWhitespace() || xml[after] == '/' || xml[after] == '>')) {
                val end = xml.indexOf('>', after)
                if (end < 0) return out
                out += xml.substring(after, end).removeSuffix("/")
                i = end
            } else i = after
        }
    }

    /** Текст всех <t>…</t> внутри [body] (кусками разметки — тоже); <rPh> — фонетика, не текст. */
    private fun texts(body: String): String {
        val b = if ("<rPh" in body) Regex("""<rPh\b[\s\S]*?</rPh>""").replace(body, "") else body
        val sb = StringBuilder()
        var i = 0
        while (true) {
            i = b.indexOf("<t", i)
            if (i < 0) break
            val c = b.getOrNull(i + 2)
            if (c != '>' && c != ' ' && c != '/') { i += 2; continue }
            val open = b.indexOf('>', i)
            if (open < 0) break
            if (b[open - 1] == '/') { i = open; continue }
            val close = b.indexOf("</t>", open)
            if (close < 0) break
            sb.append(unescape(b.substring(open + 1, close)))
            i = close + 4
        }
        return sb.toString()
    }

    private fun sharedStrings(xml: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (true) {
            i = xml.indexOf("<si", i)
            if (i < 0) return out
            val open = xml.indexOf('>', i)
            if (open < 0) return out
            if (xml[open - 1] == '/') { out += ""; i = open; continue }
            val close = xml.indexOf("</si>", open)
            if (close < 0) return out
            out += texts(xml.substring(open + 1, close))
            i = close + 5
        }
    }

    /** Число как его показывает Excel, без хвостов: «996555123456.0» → «996555123456», «1E+5» → «100000». */
    fun plain(raw: String): String {
        // Частый случай — целое или десятичное без степени: без регулярных выражений.
        var simple = raw.isNotEmpty(); var dot = -1
        for ((k, c) in raw.withIndex()) {
            if (c == '.' && dot < 0) dot = k else if (c !in '0'..'9' && !(k == 0 && c == '-')) { simple = false; break }
        }
        if (simple && raw != "-" && raw != ".") {
            val neg = raw.startsWith("-")
            val body = if (neg) raw.drop(1) else raw
            val d = body.indexOf('.')
            val int = (if (d < 0) body else body.substring(0, d)).trimStart('0').ifEmpty { "0" }
            val frac = if (d < 0) "" else body.substring(d + 1).trimEnd('0')
            val out = if (frac.isNotEmpty()) "$int.$frac" else int
            return if (out == "0") "0" else (if (neg) "-" else "") + out
        }
        val m = Regex("""^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$""").matchEntire(raw.trim()) ?: return raw
        val (sign, intPart, fracPart, exp) = m.destructured
        if (intPart.isEmpty() && fracPart.isEmpty()) return raw
        var digits = intPart + fracPart
        var point = intPart.length + (exp.toIntOrNull() ?: 0)
        if (point < 0) { digits = "0".repeat(-point) + digits; point = 0 }
        if (point > digits.length) digits += "0".repeat(point - digits.length)
        val int = digits.take(point).trimStart('0').ifEmpty { "0" }
        val frac = digits.drop(point).trimEnd('0')
        val out = if (frac.isNotEmpty()) "$int.$frac" else int
        return if (out == "0") "0" else (if (sign == "-") "-" else "") + out
    }

    private fun colIndex(letters: String): Int =
        if (letters.isEmpty()) -1 else letters.uppercase().fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) } - 1

    private fun cells(xml: String, shared: List<String>, progress: (Float) -> Unit, rowLimit: Int): List<List<String>> {
        val rows = HashMap<Int, HashMap<Int, String>>()
        val merges = mutableListOf<String>()
        var row = 0; var col = -1
        var i = 0; var told = 0
        val len = xml.length
        while (true) {
            i = xml.indexOf('<', i)
            if (i < 0) break
            if (i - told > 512 * 1024) { told = i; progress(i.toFloat() / len) }
            val tagEnd = xml.indexOf('>', i)
            if (tagEnd < 0) break
            val c1 = xml.getOrNull(i + 1)
            when {
                xml.startsWith("<row", i) && (xml[i + 4].isWhitespace() || xml[i + 4] == '>') -> {
                    val a = xml.substring(i + 4, tagEnd)
                    row = attr(a, "r")?.toIntOrNull() ?: (row + 1)
                    col = -1
                    if (row > rowLimit) break
                    i = tagEnd + 1
                }
                c1 == 'c' && (xml[i + 2].isWhitespace() || xml[i + 2] == '>' || xml[i + 2] == '/') -> {
                    val a = xml.substring(i + 2, tagEnd)
                    val ref = attr(a, "r").orEmpty()
                    val type = attr(a, "t").orEmpty()
                    val letters = ref.takeWhile { it.isLetter() }
                    col = if (letters.isNotEmpty()) colIndex(letters) else col + 1
                    if (xml[tagEnd - 1] == '/') { i = tagEnd + 1; continue }  // пустая <c …/>
                    val close = xml.indexOf("</c>", tagEnd)
                    if (close < 0) break
                    val inner = xml.substring(tagEnd + 1, close)
                    val raw = if (type == "inlineStr") texts(inner) else {
                        val v = inner.indexOf("<v")
                        if (v < 0) "" else {
                            val vo = inner.indexOf('>', v)
                            if (inner[vo - 1] == '/') "" else unescape(inner.substring(vo + 1, inner.indexOf("</v>", vo).let { if (it < 0) inner.length else it }))
                        }
                    }
                    val value = when (type) {
                        "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                        "inlineStr", "str", "e" -> raw
                        "b" -> if (raw == "1") "ИСТИНА" else "ЛОЖЬ"
                        else -> plain(raw)
                    }
                    if (col >= 0 && value.isNotEmpty()) rows.getOrPut(row) { HashMap() }[col] = value
                    i = close + 4
                }
                xml.startsWith("<mergeCell", i) && (xml[i + 10].isWhitespace() || xml[i + 10] == '/') -> {
                    attr(xml.substring(i + 10, tagEnd), "ref")?.let(merges::add)
                    i = tagEnd + 1
                }
                else -> i = tagEnd + 1
            }
        }
        for (m in merges) {
            val parts = m.split(':'); if (parts.size != 2) continue
            fun at(r: String) = (r.dropWhile { it.isLetter() }.toIntOrNull() ?: 0) to colIndex(r.takeWhile { it.isLetter() })
            val (r1, c1) = at(parts[0]); val (r2, c2) = at(parts[1])
            val v = rows[r1]?.get(c1) ?: continue
            if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1) > 5000) continue
            for (r in r1..r2) for (c in c1..c2) rows.getOrPut(r) { HashMap() }.getOrPut(c) { v }
        }
        val last = rows.keys.maxOrNull() ?: return emptyList()
        return (1..last).map { r -> rows[r]?.let { m -> List((m.keys.maxOrNull() ?: -1) + 1) { m[it].orEmpty() } } ?: emptyList() }
    }

    // ---------- Запись ----------

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
        val z = ZipWriter(stream)
        fun put(name: String, text: String) = z.add(name, text.encodeToByteArray())
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


    private fun col(i: Int): String { var n = i + 1; val s = StringBuilder(); while (n > 0) { s.insert(0, 'A' + (n - 1) % 26); n = (n - 1) / 26 }; return s.toString() }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it >= ' ' }

    /** Строки первого листа книги-файла (контакты). */
    fun read(file: File): List<List<String>> = runCatching { sheets(file.readBytes()).firstOrNull()?.rows }.getOrNull().orEmpty()
}
