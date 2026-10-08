package kg.osmotr.core

/**
 * Описи — подбор столбцов, общий для Android и iPhone (перенесён из app/…/Inventory.kt без
 * изменений логики). Проверяется набором кривых шаблонов (tools/make_templates.py).
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

    private val headerMemo = memoMap()

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

    private fun whole(d: Double) = d == kotlin.math.floor(d) && !d.isInfinite()
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

    private val INV_IN_NAME = Regex("""[,;(]?\s*(?:инв|inv)[a-zа-я]*\.?\s*(?:№|n|no|номер)?\s*[:.]?\s*([0-9A-Za-zА-Яа-яЁё](?:[0-9A-Za-zА-Яа-яЁё/.,\-]| (?=\d))*[0-9A-Za-zА-Яа-яЁё])\)?""", RegexOption.IGNORE_CASE)

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

    /** Ключ инвентарника для сравнения и id предмета (как в Инвентарники.txt). */
    fun key(inv: String) = Search.compact(inv)
    fun id(i: Item) = i.inventory.ifEmpty { "№" + i.number }

    /**
     * Имя папки предмета: «13. 1562∕65»; без инвентарника — просто номер «13»; несколько —
     * «… +2 шт». «/» в именах папок запрещён — похожая «∕».
     */
    fun folderName(first: Item, count: Int = 1): String {
        fun clean(s: String) = s.trim().replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ").replace(Regex("""\s+"""), " ").trim().trimEnd('.')
        val base = if (first.inventory.isEmpty()) clean(first.number)
            else clean(first.number + ". " + first.inventory.take(48).replace('/', '∕').replace('\\', '∖'))
        return base + if (count > 1) " +${count - 1} шт" else ""
    }

    /** Вся опись: предметы всех листов-списков. */
    fun parseBook(book: ByteArray, progress: ((Float, String) -> Unit)? = null): List<Item> =
        Xlsx.sheets(book, progress).flatMap { s -> detect(s)?.let { items(s, it) }.orEmpty() }
}
