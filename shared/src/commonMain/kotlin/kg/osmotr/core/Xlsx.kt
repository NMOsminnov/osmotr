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
}
