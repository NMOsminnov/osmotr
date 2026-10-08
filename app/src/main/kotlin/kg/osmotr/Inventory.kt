package kg.osmotr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Описи (оборотки) — списки основных средств из Excel (автор, 08.10.2026: «у нас может быть
 * их несколько… важны номер в списке, инвентарник, наименование, сумма, приоритет, стоимость
 * первоначальная… шаблон может быть непостоянным: найти таблицу в файле, определить нужные
 * столбцы, выводить спорные моменты»).
 *
 * Список — лист книги с таблицей; внутри листа бывает деление по «Статусу» (Основной список /
 * Резерв…). Листы без таблицы предметов («Сводка», «Правила») пропускаются.
 *
 * Столбцы ищутся по названиям (синонимы с весом) и проверяются данными: инвентарник —
 * уникален, деньги — числа, приоритет — малые числа, номер — целые по порядку. Где уверенности
 * нет (не нашлось или два похожих) — спорный момент: человек выбирает столбец по примерам.
 */
object Inventory {
    enum class Field(val title: String, val hint: String) {
        NUMBER("№ в списке", "1, 2, 3…"),
        INVENTORY("Инвентарный номер", "777/1001, К-310…"),
        NAME("Наименование", "Сервер Dell, Ноутбук HP…"),
        INITIAL("Первоначальная стоимость", "450 000…"),
        SUM("Сумма", "300 000…"),
        PRIORITY("Приоритет", "1, 2, 3…"),
        LIST("Список / статус", "Основной, Резерв…"),
        QUANTITY("Количество", "1, 2…"),
        PLACE("Место", "Каб. 101, Склад…"),
    }

    data class Item(
        val list: String, val number: String, val inventory: String, val name: String,
        val initial: Double?, val sum: Double?, val priority: String, val status: String, val row: Int,
        val qty: String = "", val place: String = "",
    )

    /**
     * Таблица листа: шапка (склеенная из этажей), с какой строки данные, какой столбец за какое
     * поле. [disputed] — что спросить у человека; бывает только в крайнем случае: инвентарных
     * номеров нет ни столбцом, ни в наименованиях.
     */
    data class Table(
        val sheet: String, val headerRow: Int, val headers: List<String>,
        val columns: Map<Field, Int>, val disputed: List<Field>, val candidates: Map<Field, List<Int>>,
        val samples: List<List<String>>,
        /** Строк в листе; [skip] — человек не берёт лист в опись (сводки, вынесенное и т. п.). */
        val count: Int = 0, val skip: Boolean = false,
        /** Первая строка данных (после шапки и строки «1 2 3…»); инвентарник — из наименования. */
        val body: Int = headerRow + 1, val invFromName: Boolean = false,
    )

    // ---------- Распознавание ----------
    //
    // Шаблоны бывают какими угодно (набор самых кривых — tools/make_templates.py и тесты на него):
    // шапка в несколько этажей и не в первой строке, строка «1 2 3…» под ней, повтор шапки на
    // каждой странице, итоги, разделы «Кабинет 101», заголовки по-английски и по-киргизски,
    // латинские буквы вместо русских, деньги текстом, шапки нет вовсе. Решаем сами: по
    // заголовку (словарь синонимов) и сверкой с данными. Номера нет — нумеруем; прочего нет —
    // пусто. Спросить — только если инвентарных номеров не нашлось нигде.

    /** Латинские буквы, похожие на русские, — русскими (в заголовках их путают). */
    private val LOOKALIKE = mapOf('a' to 'а', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'c' to 'с', 'x' to 'х', 'y' to 'у',
        'k' to 'к', 'h' to 'н', 'm' to 'м', 't' to 'т', 'b' to 'в', 'n' to 'п', 'u' to 'и', 'r' to 'г')
    private fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("""\s+"""), " ").trim()
    /** Для русских слов: латиница → кириллица, только если в слове уже есть кириллица (иначе «cost» не испортить). */
    private fun ru(s: String) = norm(s).split(' ').joinToString(" ") { w ->
        if (w.any { it in 'а'..'я' }) w.map { LOOKALIKE[it] ?: it }.joinToString("") else w
    }

    private val NOT_DATA = Regex("""износ|амортиз|рекоменд|итог|дата|срок|год выпуск|примечан|коммент|^сч[её]т|ед\.? ?изм|единиц|заводск|серийн|паспорт|serial|note|date""")

    private val headerMemo = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Сколько очков столбцу с заголовком [h] за поле [f]; отрицательное — точно не он. Заголовки повторяются — помнится. */
    fun headerScore(f: Field, h: String): Int {
        if (h.isBlank() || h.length > 120) return 0
        if (headerMemo.size > 50_000) headerMemo.clear()
        return headerMemo.getOrPut(f.ordinal.toString() + "|" + h) { headerScoreOf(f, h) }
    }

    private fun headerScoreOf(f: Field, h: String): Int {
        val r = ru(h); val en = norm(h)
        if (r.isEmpty()) return 0
        val hw = r.split(Regex("""[^\p{L}\p{N}/№-]+""")).filter { it.isNotEmpty() }
        // Ключевое слово — вхождением или, от 5 букв, началом слова с опечаткой («наименавание», «инвентраный»).
        fun has(vararg w: String) = w.any { k -> r.contains(k) || k.length >= 5 && ' ' !in k && hw.any { Search.fuzzyPrefix(it, k) } }
        fun en(vararg w: String) = w.any { Regex("""\b$it\b""").containsMatchIn(en) }
        val noise = NOT_DATA.containsMatchIn(r)
        val base = when (f) {
            Field.INVENTORY -> when {
                has("заводск", "серийн", "паспорт") || en("serial") -> -6
                has("инв") || en("asset tag", "inventory", "inv", "tag") -> 12
                has("номенклатурн", "шифр", "код ос", "код объекта") -> 7
                r == "номер" || r == "номер объекта" || r == "код" -> 6
                else -> 0
            }
            Field.NAME -> when {
                has("номер", "№") -> 0
                has("наименован", "аталыш") -> 12
                has("основное средство", "основного средства", "основные средства") -> 11
                en("description", "name", "item", "asset name", "equipment") -> 10
                has("назван", "объект", "имуществ", "предмет", "оборудован", "товар", "номенклатур", "описание") -> 7
                else -> 0
            }
            Field.NUMBER -> when {
                has("инв", "заводск", "паспорт") -> -10
                r == "№" || r == "п/п" || r == "пп" || r == "р/с" || r == "#" || r == "n" || en == "no" || en == "no." || en == "nr" -> 12
                has("п/п", "п. п", "по порядку", "порядков", "№ по списку", "номер в списке", "р/с") -> 12
                r.startsWith("№") && r.length <= 4 -> 10
                r == "номер" -> 4
                has("строк") -> 3
                else -> 0
            }
            Field.INITIAL -> when {
                has("остаточн", "износ") || en("residual", "net") -> -8
                has("первонач", "балансов") || en("initial", "original") -> 12
                en("cost", "price", "value") || has("баасы") -> 9
                has("стоимост") -> 7
                has("цена") -> 6
                else -> 0
            }
            Field.SUM -> when {
                has("износ") -> -8
                has("первонач") -> -6
                has("остаточн") || en("residual", "net book") -> 12
                has("сумма") || en("amount", "total") -> 9
                has("сальдо") && has("дебет") -> if (has("конец")) 8 else 6
                has("балансов") -> 6  // «Балансовая», когда первоначальная — отдельно
                has("стоимост") -> 5
                else -> 0
            }
            Field.PRIORITY -> when {
                has("приоритет", "маанилуу") || en("priority") -> 12
                has("важност", "очередн", "срочност") -> 8
                else -> 0
            }
            Field.LIST -> when {
                has("статус") || en("status") -> 9
                has("список", "раздел") -> 6
                else -> 0
            }
            Field.QUANTITY -> when {
                has("кол-во", "количеств", "кол.", "саны") || r == "кол" || en("qty", "quantity", "count", "pcs") -> 12
                else -> 0
            }
            Field.PLACE -> when {
                has("местонахожд", "местоположен", "помещени", "кабинет", "расположен") || en("location", "room", "place", "site") -> 12
                has("место", "корпус", "здание", "этаж", "подразделени", "отдел") -> 8
                else -> 0
            }
        }
        // Шум (износ, даты, сроки, примечания…) не годится ни во что, кроме явного «сальдо … дебет».
        return if (base > 0 && noise && !(f == Field.SUM && has("сальдо"))) base - 10 else base
    }

    /** Число из ячейки: «1 044 321,55 сом», «1.», «12 000,5» — с пробелами, валютой, точкой в конце. */
    fun number(s: String): Double? {
        // Быстро: обычное число (так их пишет Excel) — сразу; текст не с цифры («Сервер…», «№ 1») — не число.
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return it }
        val first = s.trimStart().firstOrNull() ?: return null
        if (!first.isDigit() && first != '-' && first != '+') return null
        var t = s.replace(Regex("""[\s\u00A0\u202F]"""), "").replace(Regex("""(?i)(сом|руб\.?|som|kgs|\$|₽|шт\.?)$"""), "").trimEnd('.')
        // И запятая, и точка: дробная часть — за последним из них («1,033,993.19», «1.033.993,19»).
        if (',' in t && '.' in t) t = if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.') else t.replace(",", "")
        return t.replace(',', '.').toDoubleOrNull()
    }

    private fun whole(d: Double) = d == Math.floor(d) && !d.isInfinite()
    private val TOTAL = Regex("""^(итого|всего|total|жыйынтык|бардыгы)""")

    /** Строка «1 2 3 4 …» под шапкой (номера столбцов в печатных формах). */
    private fun isNumbering(r: List<String>): Boolean {
        val v = r.filter { it.isNotBlank() }
        if (v.size < 3) return false
        val n = v.map { number(it) ?: return false }
        return n.first() == 1.0 && n.zipWithNext().all { (a, b) -> b == a + 1 }
    }

    /** Строка похожа на шапку: в ней заголовки двух и больше разных полей. */
    private fun headerFields(r: List<String>): Set<Field> =
        Field.entries.filter { f -> r.any { headerScore(f, it) >= 6 } }.toSet()

    /** Раздел («Кабинет 101», «Здание 2»): в строке одно значение (объединённое на ширину — тоже). */
    private fun sectionOf(r: List<String>): String? {
        val v = r.filter { it.isNotBlank() }.map { it.trim() }.distinct()
        return v.singleOrNull()?.takeIf { s -> s.any(Char::isLetter) && !TOTAL.containsMatchIn(norm(s)) }
    }

    /** Насколько значения столбца похожи на поле. */
    private fun dataScore(f: Field, values: List<String>): Int {
        val v = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (v.isEmpty()) return -6
        if (v.size == 1) return 0  // одно значение (лист из одного предмета) — не довод ни за, ни против
        val nums = v.mapNotNull(::number)
        val numeric = nums.size.toDouble() / v.size
        val unique = v.toSet().size.toDouble() / v.size
        val letters = v.count { s -> s.count(Char::isLetter) >= 3 }.toDouble() / v.size
        val ints = nums.filter(::whole)
        val sequential = if (ints.size >= 2) ints.zipWithNext().count { (a, b) -> b == a + 1 || b == 1.0 }.toDouble() / (ints.size - 1) else 0.0
        return when (f) {
            Field.INVENTORY -> when {
                unique < 0.7 -> -6
                v.count { s -> s.any(Char::isDigit) } < v.size * 0.8 -> -6
                numeric > 0.9 && sequential > 0.8 && nums.max() <= v.size * 2 -> -6  // это порядковый номер (1, 2, 3…)
                numeric > 0.9 && nums.count { !whole(it) } > nums.size * 0.3 -> -6  // это деньги
                v.map { it.length }.average() !in 2.0..30.0 -> -4
                else -> 4
            }
            Field.NAME -> if (letters >= 0.8 && numeric < 0.2 && v.map { it.length }.average() > 5) 4 else -6
            Field.NUMBER -> if (numeric > 0.9 && ints.size >= nums.size * 0.95 && nums.all { it >= 0 } && sequential >= 0.7) 4 else -6
            Field.INITIAL, Field.SUM -> if (numeric >= 0.8) 2 + (if (nums.any { it >= 100 || !whole(it) }) 1 else 0) else -6
            Field.PRIORITY -> if (v.toSet().size <= 10 && (numeric > 0.9 && nums.all { it in 0.0..20.0 } || v.all { priorityOf(it) != it })) 3 else -6
            Field.QUANTITY -> if (numeric > 0.9 && nums.all { it >= 0 && it <= 100000 } && nums.count { it <= 10 } >= nums.size * 0.6) 3 else -6
            Field.PLACE -> if (letters >= 0.7 && unique <= 0.6) 3 else -4
            Field.LIST -> if (v.toSet().size in 1..12 && numeric < 0.5) 2 else -4
        }
    }

    /** «высокий / средний / низкий» → 1 / 2 / 3 (сортировка и счётчики по приоритету — числами). */
    fun priorityOf(s: String): String {
        val n = ru(s)
        return when {
            n.startsWith("высок") || n.startsWith("high") || n.startsWith("критич") -> "1"
            n.startsWith("средн") || n.startsWith("medium") -> "2"
            n.startsWith("низк") || n.startsWith("low") -> "3"
            else -> s
        }
    }

    /** Шапка: склеенные этажи над строкой [i] (только этажи, сами похожие на шапку). */
    private fun composite(rows: List<List<String>>, i: Int): List<String> {
        val floors = (maxOf(0, i - 2)..i).filter { k -> k == i || headerFields(rows[k]).isNotEmpty() && sectionOf(rows[k]) == null }
        val width = floors.maxOf { rows[it].size }
        return List(width) { c ->
            floors.map { rows[it].getOrElse(c) { "" }.trim() }.filter { it.isNotEmpty() && number(it) == null }.distinct().joinToString(" ")
        }
    }

    /** Строка шапки (нижний этаж): где заголовков разных полей больше всего; нет — null (шапки нет). */
    private fun headerRow(rows: List<List<String>>): Int? {
        var best: Int? = null; var bestScore = 1
        for (i in 0 until minOf(rows.size, 60)) {
            if (rows[i].isEmpty() || sectionOf(rows[i]) != null) continue  // раздел — не шапка
            val score = headerFields(composite(rows, i)).size
            // Равный счёт ниже — нижний этаж той же шапки (второй этаж под объединёнными ячейками).
            if (score > bestScore || (score == bestScore && best != null && i == best + 1 && lowerFloor(rows[i]))) { best = i; bestScore = score }
        }
        return best
    }

    /** Повтор шапки (новая страница печатной формы, второй блок): две и больше ячеек — из шапки своего столбца. */
    private fun repeatOf(r: List<String>, headers: List<String>): Boolean {
        var hits = 0
        for (c in r.indices) {
            val h = headers.getOrElse(c) { "" }; val cell = r[c]
            // Дёшево отсеять: ячейка длиннее заголовка или пуста — не повтор.
            if (cell.isBlank() || h.isEmpty() || cell.length > h.length + 2) continue
            val v = ru(cell)
            if (v.isNotEmpty() && ru(h).contains(v) && number(cell) == null && ++hits >= 2) return true
        }
        return false
    }

    /** Нижний этаж шапки: заголовки без чисел, не раздел (строка данных под шапкой — с числами). */
    private fun lowerFloor(r: List<String>) = headerFields(r).isNotEmpty() && r.none { it.isNotBlank() && number(it) != null } && sectionOf(r) == null

    /** Строка данных: не пустая, не итог, не повтор шапки, не «1 2 3…», не раздел. */
    private fun dataRow(r: List<String>, headers: List<String>): Boolean =
        r.any { it.isNotBlank() } && r.none { it.length >= 5 && TOTAL.containsMatchIn(it.trimStart().take(12).lowercase()) } &&
            !repeatOf(r, headers) && !isNumbering(r) && sectionOf(r) == null

    private val INV_IN_NAME = Regex("""(?iu)[,;(]?\s*(?:инв|inv)[a-zа-я]*\.?\s*(?:№|n|no|номер)?\s*[:.]?\s*([0-9A-Za-zА-Яа-яЁё](?:[0-9A-Za-zА-Яа-яЁё/.,\-]| (?=\d))*[0-9A-Za-zА-Яа-яЁё])\)?""")

    /** Таблица листа; null — на листе нет списка предметов (нет ни инвентарников, ни наименований). */
    fun detect(sheet: Xlsx.Sheet): Table? {
        val rows = sheet.rows
        if (rows.count { it.any(String::isNotBlank) } < 2) return null  // шапка и хоть одна строка
        val hr = headerRow(rows)
        val headers = if (hr != null) composite(rows, hr) else emptyList()
        var body = (hr ?: -1) + 1
        while (body < rows.size && rows[body].isNotEmpty() && isNumbering(rows[body])) body++
        val data = rows.asSequence().drop(body).filter { dataRow(it, headers) }.take(500).toList()
        // С шапкой — список и из одной строки («Вынесено»: 1 предмет); без шапки — только уверенно.
        if (data.isEmpty() || hr == null && data.size < 3) return null
        val width = maxOf(headers.size, data.maxOf { it.size })
        fun column(c: Int) = data.map { it.getOrElse(c) { "" } }
        val dataScores = Field.entries.associateWith { f -> (0 until width).associateWith { c -> dataScore(f, column(c)) } }
        val scores = Field.entries.associateWith { f ->
            (0 until width).associateWith { c ->
                val d = dataScores.getValue(f).getValue(c)
                if (hr != null) {
                    val h = headerScore(f, headers.getOrElse(c) { "" })
                    if (h <= 0) Int.MIN_VALUE else h + d
                } else d + 5  // шапки нет — по одним данным
            }.filterValues { it >= 8 }
        }
        val columns = HashMap<Field, Int>()
        val candidates = HashMap<Field, List<Int>>()
        val taken = HashSet<Int>()
        // Сначала — самые уверенные поля, чтобы слабое не заняло чужой столбец; при равенстве — левее.
        for (f in Field.entries.sortedByDescending { scores[it]?.values?.maxOrNull() ?: 0 }) {
            val ranked = scores.getValue(f).filterKeys { it !in taken }.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            candidates[f] = ranked.map { it.key }
            ranked.firstOrNull()?.let { columns[f] = it.key; taken += it.key }
        }
        // Без шапки наименование — самый «словесный» и длинный из текстовых столбцов.
        if (hr == null && Field.NAME in columns) {
            val text = (0 until width).filter { c -> c !in taken || columns[Field.NAME] == c }.filter { dataScores.getValue(Field.NAME).getValue(it) > 0 }
            text.maxByOrNull { c -> column(c).sumOf { it.length } }?.let { columns[Field.NAME] = it }
        }
        // Сверка денег: остаточная не больше первоначальной — перепутаны, если наоборот.
        val ini = columns[Field.INITIAL]; val sum = columns[Field.SUM]
        if (ini != null && sum != null && hr == null) {
            val pairs = data.mapNotNull { r -> number(r.getOrElse(ini) { "" })?.let { a -> number(r.getOrElse(sum) { "" })?.let { a to it } } }
            if (pairs.count { (a, b) -> b > a } > pairs.size / 2) { columns[Field.INITIAL] = sum; columns[Field.SUM] = ini }
        }
        if (Field.INVENTORY !in columns && Field.NAME !in columns) return null
        if (hr == null && (Field.INVENTORY !in columns || Field.NAME !in columns)) return null  // без шапки — только уверенно
        // Инвентарники в описи есть всегда (автор): заголовок не узнали — столбец, больше всех похожий на них по данным.
        if (Field.INVENTORY !in columns) (0 until width).filter { it !in columns.values && dataScores.getValue(Field.INVENTORY).getValue(it) > 0 }
            .maxByOrNull { c -> column(c).count { v -> v.any(Char::isDigit) && v.any { !it.isDigit() } } }
            ?.let { columns[Field.INVENTORY] = it }
        // Инвентарник внутри наименования («Ноутбук HP, инв. № 777/1007»).
        val fromName = Field.INVENTORY !in columns && columns[Field.NAME]?.let { c -> column(c).count { INV_IN_NAME.containsMatchIn(it) } >= data.size / 2 } == true
        val disputed = emptyList<Field>()  // ничего не спрашиваем: необязательного нет — пусто, номера нет — по порядку
        return Table(sheet.name, hr ?: -1, headers.ifEmpty { List(width) { "" } }, columns, disputed, candidates, data.take(5), body = body, invFromName = fromName)
    }

    /** Предметы листа по выбранным столбцам; итоги, повторы шапки, «1 2 3…» и пустые строки пропускаются, разделы — местом. */
    fun items(sheet: Xlsx.Sheet, t: Table): List<Item> {
        fun cell(r: List<String>, f: Field) = t.columns[f]?.let { r.getOrElse(it) { "" } }.orEmpty().trim()
        fun int(s: String) = number(s)?.takeIf(::whole)?.toLong()?.toString()
        val out = mutableListOf<Item>()
        // Раздел прямо над шапкой («Здание 1») — место первых предметов.
        var section = sheet.rows.getOrNull(t.headerRow - 1)?.let(::sectionOf).takeIf { t.headerRow > 0 }.orEmpty()
        for (i in t.body until sheet.rows.size) {
            val r = sheet.rows[i]
            // Раздел объединён на всю ширину — его текст и в столбце инвентарника.
            sectionOf(r)?.let { sec -> cell(r, Field.INVENTORY).let { if (it.isEmpty() || it == sec) { section = sec; continue } } }
            if (!dataRow(r, t.headers)) continue
            var inv = cell(r, Field.INVENTORY); var name = cell(r, Field.NAME)
            if (t.invFromName && inv.isEmpty()) INV_IN_NAME.find(name)?.let { m ->
                inv = m.groupValues[1]; name = name.removeRange(m.range).trim(' ', ',', ';', '.', '-')
            }
            if (inv.isEmpty() && name.isEmpty()) continue
            if (inv.isEmpty() && name.none(Char::isLetter)) continue  // «01.01», «2» — не предмет
            val n = cell(r, Field.NUMBER).let { s -> int(s) ?: s }
            out += Item(
                list = t.sheet, number = n.ifEmpty { (out.size + 1).toString() }, inventory = inv, name = name,
                initial = number(cell(r, Field.INITIAL)), sum = number(cell(r, Field.SUM)),
                priority = cell(r, Field.PRIORITY).let { s -> int(s) ?: priorityOf(s) },
                status = cell(r, Field.LIST), row = i + 1,
                qty = cell(r, Field.QUANTITY).let { s -> int(s) ?: s },
                place = cell(r, Field.PLACE).ifEmpty { section },
            )
        }
        return out
    }

    // ---------- Опись в папке ----------

    /** Описи лежат в папке объекта файлами «Опись — <имя>.xlsx» (видны и по USB). */
    const val PREFIX = "Опись — "

    private fun rawFiles(dir: File): List<File> = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".xlsx", true) }.sortedBy { it.name }

    /**
     * Опись папки — одна (автор, 08.10.2026: «описи надо разделять, а не в кучу запихивать…
     * Опись → внутри как раньше было в папке „Осмотры“»). Каждая опись — своя папка в
     * «Осмотрах»: файл описи, отчёт, папки предметов.
     */
    fun fileIn(dir: File): File? = rawFiles(dir).firstOrNull()
    fun filesIn(dir: File): List<File> = listOfNotNull(fileIn(dir))

    /** Имя описи — из имени файла: «Опись — Объект.xlsx» → «Объект». */
    fun nameOf(f: File) = f.name.removePrefix(PREFIX).removeSuffix(".xlsx").removeSuffix(".XLSX")

    /** Новая опись — своя папка в «Осмотрах»: «Осмотры/<имя>/Опись — <имя>.xlsx». */
    fun create(name: String, write: (java.io.OutputStream) -> Unit): File {
        val clean = Store.cleanName(name).take(MAX_NAME).trim().ifEmpty { "Опись" }
        var dir = File(Store.root, clean); var n = 2
        while (dir.exists()) dir = File(Store.root, "$clean ($n)").also { n++ }
        dir.mkdirs()
        val f = File(dir, PREFIX + dir.name + ".xlsx")
        Store.writeDurably(f, write)
        Store.scan(listOf(f)); Store.changed()
        return dir
    }

    /**
     * Книга из телефона — новой описью в своей папке. Только .xlsx (старый .xls — сохранить в
     * Excel как .xlsx); без списка предметов — отказ с понятной причиной. Имя — из имени файла,
     * а если оно мусорное (мессенджеры шлют «040dc55f-115.4.-____.xlsx») — из заголовка в книге.
     */
    fun importBook(context: android.content.Context, uri: android.net.Uri, fileName: String, onFile: (File) -> Unit = {}): Parsed {
        val tmp = copyChecked(context, uri, fileName)
        val dir = try { create(nameFor(fileName, tmp)) { out -> tmp.inputStream().use { it.copyTo(out) } } } finally { tmp.delete() }
        val f = fileIn(dir)!!
        onFile(f)
        // Разбор — сразу, один раз: кэш готов, опись открывается мгновенно. Списка нет — опись не заводится.
        val p = runCatching { parse(f) }.getOrElse { dir.deleteRecursively(); Store.changed(); throw it }
        if (p.items.isEmpty()) { dir.deleteRecursively(); Store.changed(); throw IllegalArgumentException(NO_LIST) }
        return p
    }

    /** Прикрепить опись к уже начатой папке [dir]. */
    fun attachBook(context: android.content.Context, uri: android.net.Uri, fileName: String, dir: File): Parsed {
        val tmp = copyChecked(context, uri, fileName)
        val old = fileIn(dir)?.let { f -> File(Store.cacheDir, "was-${System.nanoTime()}.xlsx").also { f.copyTo(it) } }
        try {
            replace(dir) { out -> tmp.inputStream().use { it.copyTo(out) } }
            val p = parse(fileIn(dir)!!)
            if (p.items.isNotEmpty()) return p
            // Списка нет — вернуть прежнюю опись (или убрать пустую).
            if (old != null) replace(dir) { out -> old.inputStream().use { it.copyTo(out) } } else fileIn(dir)?.delete()
            Store.changed()
            throw IllegalArgumentException(NO_LIST)
        } finally { tmp.delete(); old?.delete() }
    }

    private const val NO_LIST = "в файле не нашли список предметов (или файл повреждён)"

    private fun copyChecked(context: android.content.Context, uri: android.net.Uri, fileName: String): File {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        require(ext != "xls") { "старый формат .xls: откройте в Excel и сохраните как .xlsx" }
        require(ext == "xlsx" || ext.isEmpty()) { "это не таблица Excel (.xlsx)" }
        val tmp = File(Store.cacheDir, "import-${System.nanoTime()}.xlsx")
        context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        return tmp
    }

    /** Имя описи: из имени файла, если оно человеческое; иначе — заголовок из книги; иначе — «Опись от 08.10». */
    fun nameFor(fileName: String, book: File): String {
        val stem = fileName.substringBeforeLast('.').trim()
        val words = Regex("""[\p{L}]{3,}""").findAll(stem).count()
        val junk = stem.count { it == '_' || it == '-' } > stem.length / 4 || Regex("""[0-9a-f]{8}""").containsMatchIn(stem.lowercase())
        if (words >= 1 && !junk) return stem.take(MAX_NAME).trim()
        val title = Xlsx.sheets(book, rowLimit = 4).asSequence().flatMap { it.rows.take(4).asSequence() }
            .mapNotNull(::sectionOf).firstOrNull { t -> t.length in 3..MAX_NAME && headerFields(listOf(t)).isEmpty() }
        return title ?: ("Опись от " + java.text.SimpleDateFormat("dd.MM", java.util.Locale.forLanguageTag("ru")).format(System.currentTimeMillis()))
    }

    /** Заменить файл описи папки (пришла исправленная): папки предметов остаются, выбор столбцов — заново. */
    fun replace(dir: File, write: (java.io.OutputStream) -> Unit) {
        require(dir != Store.root) { "опись — только в своей папке" }  // в корне её не видно и не удалить
        val f = fileIn(dir) ?: File(dir, PREFIX + dir.name + ".xlsx")
        choiceFile(f).delete(); cacheFile(f).delete()
        Store.writeDurably(f, write)
        cacheFile(f).delete()
        Store.scan(listOf(f)); Store.changed()
    }

    /**
     * Перенос: в одной папке оказалось несколько описей (прежние версии клали их в кучу) —
     * каждая лишняя уезжает в свою папку в «Осмотрах» вместе с отчётом и папками своих
     * предметов. Первая остаётся; из корня уезжают все. Раз при запуске; без куч — ничего не делает.
     */
    /**
     * Папки предметов — по нынешнему правилу имён («13. 1562∕65», «13. ИВЛ», «… +2 шт»): прежние
     * «777 1001 (3 шт)», «777 1001 +2 шт» переименовываются. Разбор описей — из кэша.
     */
    fun renameItemFolders() {
        for (obj in Store.foldersIn(Store.root).filter { fileIn(it) != null }) {
            val all = runCatching { filesIn(obj).flatMap { parse(it).items }.associateBy { key(id(it)) } }.getOrNull() ?: continue
            obj.walkTopDown().onEnter { it == obj || !it.name.startsWith(".") }.filter { it.isDirectory && it != obj }.toList().forEach { d ->
                val m = members(d); val first = m.firstOrNull()?.let { all[key(it)] } ?: return@forEach
                val want = folderName(first, m.size)
                if (d.name != want && !File(d.parentFile, want).exists()) { Store.renameFolder(d, want); Store.changed() }
            }
        }
    }

    fun migrate() {
        Store.root.walkTopDown().onEnter { it == Store.root || !it.name.startsWith(".") }.filter { it.isDirectory }.toList().forEach { d ->
            // В корне описи не лежат вовсе — у каждой своя папка; в папке — одна.
            val keep = if (d == Store.root) 0 else 1
            if (rawFiles(d).size <= keep) return@forEach
            // Остаётся та, чьих предметов здесь больше всего (с ней работали), при равенстве — большая.
            val subs = Store.foldersIn(d).associateWith { sub -> members(sub).ifEmpty { listOf(invOfName(sub.name)) }.map(::key) }
            val keysOf = rawFiles(d).associateWith { f -> parse(f).items.map { key(id(it)) }.toSet() }
            val files = rawFiles(d).sortedWith(compareByDescending<File> { f -> subs.values.count { m -> m.isNotEmpty() && m.all { it in keysOf.getValue(f) } } }
                .thenByDescending { it.length() })
            val firstKeys = files.take(keep).flatMap { keysOf.getValue(it) }.toSet()
            for (f in files.drop(keep)) {
                val keys = keysOf.getValue(f)
                val base = Store.cleanName(nameOf(f)).take(MAX_NAME).trim().ifEmpty { "Опись" }
                var dir = File(Store.root, base); var n = 2
                while (dir.exists()) dir = File(Store.root, "$base ($n)").also { n++ }
                dir.mkdirs()
                // Книги — по имени папки (короткому); выбор столбцов едет с ними, отчёт пересоберётся.
                val moved = File(dir, PREFIX + dir.name + ".xlsx")
                choiceFile(f).takeIf { it.isFile }?.renameTo(choiceFile(moved))
                f.renameTo(moved)
                reportFile(f).delete()
                // Папки предметов этой описи (и не предметов первой) — туда же.
                Store.foldersIn(d).forEach { sub ->
                    val m = members(sub).ifEmpty { listOf(invOfName(sub.name)) }.map(::key)
                    if (sub != dir && m.isNotEmpty() && m.all { it in keys } && m.none { it in firstKeys }) Store.moveFolder(sub, dir)
                }
            }
            Store.changed()
        }
        renameItemFolders()
    }

    /** Разбор описи с выбором столбцов — кэшем рядом с приложением (5–6 тыс. строк читаются секунды). */
    data class Parsed(val file: File, val tables: List<Table>, val items: List<Item>) {
        val unresolved get() = tables.any { !it.skip && it.disputed.isNotEmpty() }
        val lists get() = items.map { it.list }.distinct()
    }

    private fun cacheFile(f: File) = File(Store.cacheDir, "inventory/" + ("v3|" + f.absolutePath + "|" + f.length() + "|" + f.lastModified()).hashCode() + ".json")
    private const val SKIP = "_skip"
    /** Листы и столбцы уже подтверждены человеком (после загрузки спрашиваем один раз). */
    fun confirmed(f: File) = choiceFile(f).isFile
    private fun choiceFile(f: File) = File(Store.cacheDir, "inventory/choice-" + f.absolutePath.hashCode() + ".json")

    /** Идёт разбор: какой файл, доля (0…1) и что делается — экраны показывают шкалу, а не «висит». */
    data class Parsing(val file: File, val fraction: Float, val stage: String)
    /** Разборы идут параллельно (несколько описей разом) — у каждого файла своя шкала. */
    val parsing = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Parsing>>(emptyMap())
    private fun tell(f: File, fraction: Float, stage: String) = parsing.update { it + (f.absolutePath to Parsing(f, fraction, stage)) }
    private fun told(f: File) = parsing.update { it - f.absolutePath }
    private inline fun <T> kotlinx.coroutines.flow.MutableStateFlow<T>.update(change: (T) -> T) {
        while (true) { val was = value; if (compareAndSet(was, change(was))) return }
    }
    /** Один файл разбирается один раз: кто пришёл вторым — ждёт и берёт готовое из кэша. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Разобрать (или взять из кэша). [choice] — выбор человека: лист → поле → столбец. */
    fun parse(f: File): Parsed {
        val cache = cacheFile(f)
        runCatching { if (cache.isFile) return fromJson(f, JSONObject(cache.readText())) }
        synchronized(locks.getOrPut(f.absolutePath) { Any() }) {
            runCatching { if (cache.isFile) return fromJson(f, JSONObject(cache.readText())) }  // пока ждали — разобрал другой
            try {
                return parseFresh(f, cache)
            } finally { told(f) }
        }
    }

    private fun parseFresh(f: File, cache: File): Parsed {
        val choice = runCatching { JSONObject(choiceFile(f).readText()) }.getOrNull()
        tell(f, 0f, "Открываем файл")
        // Чтение — 85 % шкалы, поиск столбцов и строк — остальное.
        val sheets = Xlsx.sheets(f, progress = { part, sheet -> tell(f, 0.85f * part, "Читаем лист «$sheet»") })
        tell(f, 0.88f, "Ищем столбцы")
        val tables = sheets.mapNotNull { s ->
            val t = detect(s) ?: return@mapNotNull null
            val c = choice?.optJSONObject(s.name) ?: return@mapNotNull s to t
            val cols = t.columns.toMutableMap()
            Field.entries.forEach { fld -> if (c.has(fld.name)) { val i = c.getInt(fld.name); if (i < 0) cols.remove(fld) else cols[fld] = i } }
            s to t.copy(columns = cols, disputed = emptyList(), skip = c.optBoolean(SKIP))
        }.map { (s, t) -> Triple(s, t, items(s, t)) }
        // Пропущенный лист остаётся в таблицах (чтобы вернуть), но его строк в описи нет.
        val items = tables.filterNot { it.second.skip }.flatMap { it.third }
        tell(f, 0.97f, "Собираем список: ${items.size} предметов")
        val parsed = Parsed(f, tables.map { (_, t, rows) -> t.copy(count = rows.size) }, items)
        runCatching { cache.parentFile?.mkdirs(); cache.writeText(toJson(parsed).toString()) }
        return parsed
    }

    /** Человек решил спорное: запомнить и пересобрать. [cols] — поле → столбец (−1 — нет такого); [skip] — лист не брать. */
    fun resolve(f: File, sheet: String, cols: Map<Field, Int>, skip: Boolean = false) {
        val file = choiceFile(f)
        val all = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        all.put(sheet, JSONObject().apply { cols.forEach { (k, v) -> put(k.name, v) }; put(SKIP, skip) })
        file.parentFile?.mkdirs(); file.writeText(all.toString())
        cacheFile(f).delete()
    }

    private fun toJson(p: Parsed) = JSONObject().apply {
        put("tables", JSONArray(p.tables.map { t ->
            JSONObject().apply {
                put("sheet", t.sheet); put("headerRow", t.headerRow); put("headers", JSONArray(t.headers))
                put("columns", JSONObject().apply { t.columns.forEach { (k, v) -> put(k.name, v) } })
                put("disputed", JSONArray(t.disputed.map { it.name }))
                put("candidates", JSONObject().apply { t.candidates.forEach { (k, v) -> put(k.name, JSONArray(v)) } })
                put("samples", JSONArray(t.samples.map { JSONArray(it) }))
                put("count", t.count); put("skip", t.skip); put("body", t.body); put("invFromName", t.invFromName)
            }
        }))
        put("items", JSONArray(p.items.map { i ->
            JSONArray(listOf(i.list, i.number, i.inventory, i.name, i.initial ?: JSONObject.NULL, i.sum ?: JSONObject.NULL, i.priority, i.status, i.row, i.qty, i.place))
        }))
    }

    private fun fromJson(f: File, o: JSONObject): Parsed {
        fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }
        val tables = o.getJSONArray("tables").let { a -> List(a.length()) { k ->
            val t = a.getJSONObject(k)
            Table(t.getString("sheet"), t.getInt("headerRow"), strings(t.getJSONArray("headers")),
                t.getJSONObject("columns").let { c -> c.keys().asSequence().associate { Field.valueOf(it) to c.getInt(it) } },
                strings(t.getJSONArray("disputed")).map { Field.valueOf(it) },
                t.getJSONObject("candidates").let { c -> c.keys().asSequence().associate { key -> Field.valueOf(key) to c.getJSONArray(key).let { arr -> List(arr.length()) { arr.getInt(it) } } } },
                t.getJSONArray("samples").let { s -> List(s.length()) { strings(s.getJSONArray(it)) } },
                t.optInt("count"), t.optBoolean("skip"), t.optInt("body", t.getInt("headerRow") + 1), t.optBoolean("invFromName"))
        } }
        val items = o.getJSONArray("items").let { a -> List(a.length()) { k ->
            val r = a.getJSONArray(k)
            Item(r.getString(0), r.getString(1), r.getString(2), r.getString(3),
                if (r.isNull(4)) null else r.getDouble(4), if (r.isNull(5)) null else r.getDouble(5),
                r.getString(6), r.getString(7), r.getInt(8), r.optString(9), r.optString(10))
        } }
        return Parsed(f, tables, items)
    }

    // ---------- Папка предмета ----------
    //
    // Папка предмета называется его инвентарником («К-310»), а какие инвентарники в ней —
    // записано в ней же файлом «Инвентарники.txt» (виден и на компьютере). Похожие предметы
    // (три одинаковых серверных юнита) — одной папкой: «777 1001 (3 шт)», в файле — все три.
    // Папку можно переименовать или переложить — привязка держится на файле, не на имени.

    const val MEMBERS = "Инвентарники.txt"

    fun members(dir: File): List<String> = File(dir, MEMBERS).takeIf { it.isFile }
        ?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun key(inv: String) = Search.compact(inv)
    private fun id(i: Item) = i.inventory.ifEmpty { "№" + i.number }

    /**
     * Имя папки предмета (автор, 08.10.2026): «№ в описи. инвентарник» — «13. 1562∕65»; нет
     * инвентарника — просто номер — «13»; несколько предметов — «… +2 шт». «/» в именах папок
     * запрещён (Android, Windows) — вместо него похожая «∕». Состав — в [MEMBERS].
     */
    fun folderName(first: Item, count: Int = 1): String {
        val base = if (first.inventory.isEmpty()) Store.cleanName(first.number)
            else Store.cleanName(first.number + ". " + first.inventory.take(MAX_INV).replace('/', '∕').replace('\\', '∖')).trim()
        return base + if (count > 1) " +${count - 1} шт" else ""
    }
    /** Предмет описи не нашёлся (опись заменили) — по одним инвентарникам. */
    private fun folderName(invs: List<String>): String =
        (Store.cleanName(invs.first().replace('/', '∕')).take(MAX_INV).trim() + if (invs.size > 1) " +${invs.size - 1} шт" else "")
    private const val MAX_INV = 48
    /** Имя папки описи — не длиннее (оно же повторяется в именах её книг). */
    const val MAX_NAME = 60
    /** Инвентарник из имени папки без файла состава: «13. 1562∕65 +2 шт», «777 1001 +2 шт», «777 1001 (3 шт)». Просто номер («13») — не инвентарник. */
    private fun invOfName(name: String) = name.replace(Regex("""^\d+\.\s+"""), "").substringBefore(" (").substringBefore(" +").replace('∕', '/')


    /** Ближайшая папка с описью — папка объекта (сама [dir] или выше). */
    fun objectOf(dir: File): File? = generateSequence(dir) { if (it == Store.root) null else it.parentFile }
        .firstOrNull { filesIn(it).isNotEmpty() }

    /**
     * Где что лежит у описи: инвентарник → папка; папка → снимков (со вложенными) и когда сняты
     * первый и последний. Что с предметом не так (нет на месте, неисправен) — комментарием его папки.
     */
    class Status(val folder: Map<String, File>, val photos: Map<File, Int>,
                 val first: Map<File, Long> = emptyMap(), val last: Map<File, Long> = emptyMap()) {
        fun folderOf(i: Item) = folder[key(id(i))]
        fun photosOf(i: Item) = folderOf(i)?.let { photos[it] } ?: 0
        fun inspected(i: Item) = photosOf(i) > 0
        fun firstShot(i: Item) = folderOf(i)?.let { first[it] }
        fun lastShot(i: Item) = folderOf(i)?.let { last[it] }
        /** Снят сегодня (с полуночи) — «сегодня +N» на выезде. */
        fun today(i: Item) = (lastShot(i) ?: 0L) >= startOfDay()
    }

    fun startOfDay(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun status(obj: File): Status {
        val folder = HashMap<String, File>()
        val photos = HashMap<File, Int>(); val first = HashMap<File, Long>(); val last = HashMap<File, Long>()
        obj.walkTopDown().onEnter { it == obj || !it.name.startsWith(".") }.filter { it.isDirectory && it != obj }.forEach { d ->
            val m = members(d)
            // Без файла — по имени: папку назвали инвентарником вручную.
            (m.ifEmpty { listOf(invOfName(d.name)) }).forEach { inv -> folder.putIfAbsent(key(inv), d) }
            var n = 0; var lo = Long.MAX_VALUE; var hi = 0L
            d.walkTopDown().filter(Store::isPhoto).forEach { f -> n++; val t = f.lastModified(); if (t < lo) lo = t; if (t > hi) hi = t }
            photos[d] = n
            if (n > 0) { first[d] = lo; last[d] = hi }
        }
        return Status(folder, photos, first, last)
    }

    // ---------- Выгрузки ----------

    /** Когда опись выгружали в последний раз — скрытым файлом в её папке (для «нового с прошлой»). */
    private fun exportMark(obj: File) = File(obj, ".выгрузка")
    fun lastExport(obj: File): Long? = runCatching { exportMark(obj).readText().trim().toLong() }.getOrNull()
    fun markExport(obj: File, at: Long = System.currentTimeMillis()) = runCatching { Store.writeDurably(exportMark(obj), at.toString().toByteArray()) }

    /** Сколько снимков в описи новее [since] — для подписей выгрузки. */
    fun photosSince(obj: File, since: Long): Int =
        obj.walkTopDown().onEnter { it == obj || !it.name.startsWith(".") }.count { Store.isPhoto(it) && it.lastModified() > since }

    private fun writeMembers(dir: File, invs: List<String>) {
        Store.writeDurably(File(dir, MEMBERS), (invs.distinct().joinToString("\n") + "\n").toByteArray())
        Store.scan(listOf(File(dir, MEMBERS)))
    }

    /** Папка предмета: есть — её, нет — создать (имя — инвентарник). Сразу к снимкам. */
    fun openFolder(obj: File, i: Item, st: Status = status(obj)): File {
        st.folderOf(i)?.let { return it }
        val dir = unique(obj, folderName(i))
        writeMembers(dir, listOf(id(i)))
        Store.changed()
        return dir
    }

    /**
     * Собрать предметы в одну папку («ходим по описи и отмечаем, что в папке»): папка первого,
     * у кого она уже есть (или новая); снимки из папок остальных переезжают туда, пустые их
     * папки убираются; имя — «первый (N шт)».
     */
    fun group(obj: File, items: List<Item>, st: Status = status(obj)): File {
        val base = items.firstNotNullOfOrNull { st.folderOf(it) } ?: openFolder(obj, items.first(), st)
        return addTo(base, items, st)
    }

    /** Добавить предметы в папку [dir] (из её карточки: «ещё такие же»). */
    fun addTo(dir: File, items: List<Item>, st: Status? = null): File {
        val status = st ?: objectOf(dir)?.let(::status)
        val all = (members(dir) + items.map(::id)).distinct()
        for (i in items) {
            val other = status?.folderOf(i) ?: continue
            if (other == dir) continue
            Store.move(Store.photoTree(other), dir)
            // Свой у прежней папки только этот предмет — она больше не нужна.
            val rest = members(other).filterNot { key(it) == key(id(i)) }
            if (rest.isEmpty() && Store.photoTree(other).isEmpty() && Store.foldersIn(other).isEmpty()) other.deleteRecursively()
            else writeMembers(other, rest)
        }
        writeMembers(dir, all)
        return renameFor(dir, all)
    }

    /** Убрать предмет из папки (ошиблись при объединении); снимки остаются в папке. */
    fun removeFrom(dir: File, i: Item): File {
        val rest = members(dir).filterNot { key(it) == key(id(i)) }
        if (rest.isEmpty()) return dir
        writeMembers(dir, rest)
        return renameFor(dir, rest)
    }

    private fun renameFor(dir: File, invs: List<String>): File {
        val first = itemsByIds(dir, invs.take(1)).firstOrNull()
        val want = first?.let { folderName(it, invs.size) } ?: folderName(invs)
        val out = if (dir.name == want) dir else Store.renameFolder(dir, want) ?: dir
        Store.changed()
        return out
    }

    /** Предметы описи, лежащие в папке [dir] (для карточки в папке). */
    /** Новая папка [name] в [parent]; имя занято (тот же № и название в другом списке) — «… (2)». */
    private fun unique(parent: File, name: String): File {
        var n = 1
        while (true) { Store.createFolder(parent, if (n == 1) name else "$name ($n)")?.let { return it }; if (++n > 99) return File(parent, name).apply { mkdirs() } }
    }

    /** Предметы описи по их id (как в [MEMBERS]) — для имени папки. */
    private fun itemsByIds(dir: File, ids: List<String>): List<Item> {
        val obj = objectOf(dir.parentFile ?: return emptyList()) ?: return emptyList()
        val all = filesIn(obj).flatMap { parse(it).items }.associateBy { key(id(it)) }
        return ids.mapNotNull { all[key(it)] }
    }

    fun itemsIn(dir: File): List<Item> {
        val obj = objectOf(dir.parentFile ?: return emptyList()) ?: return emptyList()
        val keys = (members(dir).ifEmpty { listOf(invOfName(dir.name)) }).map(::key).toSet()
        return filesIn(obj).flatMap { parse(it).items }.filter { key(id(it)) in keys }
    }

    /** Такие же в описи (то же наименование), ещё не в этой папке — «добавить сюда?». */
    fun similar(obj: File, here: List<Item>, st: Status): List<Item> {
        if (here.isEmpty()) return emptyList()
        val names = here.map { Search.compact(it.name) }.toSet()
        val inHere = here.map { key(id(it)) }.toSet()
        return filesIn(obj).flatMap { parse(it).items }
            .filter { Search.compact(it.name) in names && key(id(it)) !in inHere && !st.inspected(it) }
    }

    // ---------- Отчёт ----------

    /** Отчёт по описи — рядом с ней: «Осмотр — <имя>.xlsx». */
    fun reportFile(f: File) = File(f.parentFile, "Осмотр — " + f.name.removePrefix(PREFIX))

    /**
     * Что осмотрено, что нет (по наличию снимков) — книгой Excel, по листу на список и итог
     * первым листом (автор: «автоматом выгружать в Excel»). Пересобирается сам: после съёмки, при
     * открытии описи, перед архивом.
     */
    private val reportLock = Any()
    fun writeReport(obj: File): Unit = synchronized(reportLock) { writeReportLocked(obj) }
    private val WHEN = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.forLanguageTag("ru"))
    private val DAY = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.forLanguageTag("ru"))

    private fun writeReportLocked(obj: File) {
        val st = status(obj)
        for (f in filesIn(obj)) {
            val p = parse(f)
            val lists = p.lists
            val head = listOf("№", "Инв. номер", "Наименование", "Место", "Кол-во", "Первонач. стоимость", "Сумма", "Приоритет", "Статус",
                "Осмотрено", "Снимков", "Первый снимок", "Последний снимок", "Комментарий", "Папка")
            val sheets = mutableListOf<Xlsx.Out>()
            val total = listOf("Всего", "Осмотрено", "Не осмотрено", "Осмотрено, %", "Первонач. стоимость осмотренного")
            fun line(name: String, items: List<Item>): List<Any?> {
                val done = items.filter(st::inspected)
                return listOf(name, items.size, done.size, items.size - done.size,
                    if (items.isEmpty()) 0 else Math.round(done.size * 1000.0 / items.size) / 10.0, done.sumOf { it.initial ?: 0.0 })
            }
            val summary = mutableListOf<List<Any?>>(listOf("Список") + total)
            val notes = HashMap<File, String?>()  // комментарий папки — что с предметом не так (общая папка — один раз)
            for (l in lists) {
                val items = p.items.filter { it.list == l }
                summary += line(l, items)
                val rows = listOf<List<Any?>>(head) + items.map { i ->
                    listOf(i.number.toLongOrNull() ?: i.number, i.inventory, i.name, i.place.ifEmpty { null }, i.qty.toLongOrNull() ?: i.qty.ifEmpty { null },
                        i.initial, i.sum, i.priority.toLongOrNull() ?: i.priority.ifEmpty { null }, i.status.ifEmpty { null },
                        if (st.inspected(i)) "Да" else "Нет", st.photosOf(i), st.firstShot(i)?.let { WHEN.format(it) }, st.lastShot(i)?.let { WHEN.format(it) },
                        st.folderOf(i)?.let { notes.getOrPut(it) { Store.note(it) } }, st.folderOf(i)?.let { Store.relative(it) })
                }
                sheets += Xlsx.Out(l, rows, listOf(7, 18, 50, 16, 8, 16, 16, 10, 18, 11, 9, 17, 17, 40, 40)) { r ->
                    if (r > 0 && st.inspected(items[r - 1])) Xlsx.GREEN else Xlsx.PLAIN
                }
            }
            if (lists.size > 1) summary += line("Все списки", p.items)
            // По приоритетам — по всем спискам описи.
            summary += listOf<Any?>()
            summary += listOf("Приоритет") + total
            p.items.groupBy { it.priority.ifEmpty { "—" } }.toSortedMap(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
                .forEach { (pr, items) -> summary += line("П$pr", items) }
            // По дням — выезды: сколько предметов впервые снято в тот день и сколько всего к его концу.
            val byDay = p.items.mapNotNull { i -> st.firstShot(i)?.let { DAY.format(it) to it } }.groupBy({ it.first }, { it.second })
                .entries.sortedBy { it.value.min() }
            var sum = 0
            val days = listOf<List<Any?>>(listOf("День", "Впервые снято предметов", "Всего снято к концу дня")) +
                byDay.map { (d, v) -> sum += v.size; listOf(d, v.size, sum) }
            // Нет в описи — папки со снимками, которых в описи нет (излишки, неизвестное).
            val keys = p.items.map { key(id(it)) }.toSet()
            val extra = obj.walkTopDown().onEnter { it == obj || !it.name.startsWith(".") }.filter { it.isDirectory && it != obj }
                .filter { d -> (members(d).ifEmpty { listOf(invOfName(d.name)) }).none { key(it) in keys } && (d.listFiles()?.any(Store::isPhoto) == true) }
                .map { d -> listOf<Any?>(d.relativeTo(obj).path, d.listFiles()?.count(Store::isPhoto) ?: 0, Store.note(d)) }.toList()
            val book = listOf(Xlsx.Out("Итог", summary, listOf(24, 10, 12, 14, 14, 26))) + sheets +
                listOf(Xlsx.Out("По дням", days, listOf(14, 24, 22))) +
                (if (extra.isEmpty()) emptyList() else listOf(Xlsx.Out("Нет в описи", listOf(listOf<Any?>("Папка", "Снимков", "Комментарий")) + extra, listOf(40, 10, 50))))
            Xlsx.writeBook(reportFile(f), book)
            Store.scan(listOf(reportFile(f)))
        }
    }

    /** Пересобрать отчёт объекта, к которому относится [dir] (в фоне вызывающего). */
    fun refreshReport(dir: File) { objectOf(dir)?.let { runCatching { writeReport(it) } } }
}
