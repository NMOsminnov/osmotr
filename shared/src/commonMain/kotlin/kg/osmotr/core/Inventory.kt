package kg.osmotr.core

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    @kotlinx.serialization.Serializable
    data class Item(
        val list: String, val number: String, val inventory: String, val name: String,
        val initial: Double?, val sum: Double?, val priority: String, val status: String, val row: Int,
        val qty: String = "", val place: String = "",
        /**
         * Свой ключ предмета без инвентарника, когда его номер в описи повторяется (нумерация
         * заново в каждом разделе): «№6 (Склад, стр. 11)». Пусто — ключ «№6» (см. [id]).
         */
        val key: String = "",
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
    /** Нижний регистр, ё → е, пробелы схлопнуты — без регулярных выражений (зовётся на каждую ячейку). */
    private fun norm(s: String): String {
        val sb = StringBuilder(s.length); var space = false
        for (ch in s) {
            if (ch.isWhitespace()) { space = sb.isNotEmpty(); continue }
            if (space) { sb.append(' '); space = false }
            val c = ch.lowercaseChar(); sb.append(if (c == 'ё') 'е' else c)
        }
        return sb.toString()
    }
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
                // «Н/п» — «номер по порядку» (13.10.2026: без него столбец номера не находился, и
                // нумерация заново в каждом разделе терялась — номера шли подряд).
                r == "№" || r == "п/п" || r == "пп" || r == "н/п" || r == "нп" || r == "р/с" || r == "#" || r == "n" || en == "no" || en == "no." || en == "nr" -> 12
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
        // Без регулярных выражений: на телефоне эта функция зовётся сотни тысяч раз на опись.
        if (s.isEmpty()) return null
        plainDouble(s)?.let { return it }
        val first = s.trimStart().firstOrNull() ?: return null
        if (!first.isDigit() && first != '-' && first != '+') return null
        val sb = StringBuilder(s.length)
        for (ch in s) if (!ch.isWhitespace() && ch != '\u00A0' && ch != '\u202F') sb.append(ch)
        var t = sb.toString()
        for (suffix in CURRENCY) if (t.endsWith(suffix, ignoreCase = true)) { t = t.dropLast(suffix.length); break }
        t = t.trimEnd('.')
        // И запятая, и точка: дробная часть — за последним из них («1,033,993.19», «1.033.993,19»).
        if (',' in t && '.' in t) t = if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.') else t.replace(",", "")
        return plainDouble(t.replace(',', '.'))
    }

    private val CURRENCY = listOf("руб.", "руб", "сом", "som", "kgs", "$", "₽", "шт.", "шт")

    /** Обычное десятичное число («-12.5», «1e5», «7.») — без регулярных выражений; иначе null. */
    private fun plainDouble(s: String): Double? {
        var i = 0; val n = s.length
        if (n == 0) return null
        if (s[0] == '+' || s[0] == '-') i++
        var digits = 0; var dot = false; var exp = false
        while (i < n) {
            val c = s[i]
            when {
                c in '0'..'9' -> digits++
                c == '.' && !dot && !exp -> dot = true
                (c == 'e' || c == 'E') && !exp && digits > 0 -> {
                    exp = true
                    if (i + 1 < n && (s[i + 1] == '+' || s[i + 1] == '-')) i++
                    if (i + 1 >= n) return null
                }
                else -> return null
            }
            i++
        }
        if (digits == 0) return null
        return s.toDoubleOrNull()
    }

    private fun whole(d: Double) = d == kotlin.math.floor(d) && !d.isInfinite()
    private val TOTAL = Regex("""^(итого|всего|total|жыйынтык|бардыгы)""")
    private val TOTAL_FIRST = setOf('и', 'в', 't', 'ж', 'б')
    /** Начинается с «Итого», «Всего»…: сначала дешёвая проверка первой буквы, регулярка — только тогда. */
    private fun isTotal(cell: String): Boolean {
        val t = cell.trimStart()
        if (t.length < 5 || t[0].lowercaseChar() !in TOTAL_FIRST) return false
        return TOTAL.containsMatchIn(t.take(12).lowercase())
    }

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
        // Быстро: два разных непустых значения — не раздел (так почти у всех строк данных).
        var one: String? = null
        for (c in r) { if (c.isBlank()) continue; val t = c.trim(); if (one == null) one = t else if (one != t) return null }
        val v = listOfNotNull(one)
        return v.singleOrNull()?.takeIf { s -> s.any(Char::isLetter) && !isTotal(s) }
    }

    /**
     * Заглушка вместо инвентарника: «б/н», «без номера», прочерк, «нет», «0», «?», «#N/A».
     * Предмет без инвентарника, а не инвентарник «б/н» — иначе все «б/н» описи стали бы одним
     * предметом. И при подборе столбцов заглушки не считаются: когда их треть, столбец
     * инвентарников отвергался целиком, и у всех предметов пропадали номера (13.10.2026).
     */
    private val PLACEHOLDER = Regex("""(?iu)^\s*(б\s*[/\\.]?\s*н\.?|б/№|без\s*(инв\.?\s*)?(номера|№|н)|нет|отсутств\p{L}*|не\s*присвоен\p{L}*|[-–—_.?xх*]+|0+|n\s*/?\s*a|#\S*)\s*$""")
    fun placeholder(s: String) = PLACEHOLDER.matches(s)

    /** Насколько значения столбца похожи на поле. */
    private fun dataScore(f: Field, values: List<String>): Int {
        val v = values.map { it.trim() }.filter { it.isNotEmpty() && !(f == Field.INVENTORY && placeholder(it)) }
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
            if (cell.isBlank() || h.isEmpty() || cell.length > h.length + 2 || number(cell) != null) continue
            val v = ru(cell)
            if (v.isNotEmpty() && ru(h).contains(v) && number(cell) == null && ++hits >= 2) return true
        }
        return false
    }

    /** Нижний этаж шапки: заголовки без чисел, не раздел (строка данных под шапкой — с числами). */
    private fun lowerFloor(r: List<String>) = headerFields(r).isNotEmpty() && r.none { it.isNotBlank() && number(it) != null } && sectionOf(r) == null

    /** Строка данных: не пустая, не итог, не повтор шапки, не «1 2 3…», не раздел. */
    private fun dataRow(r: List<String>, headers: List<String>): Boolean =
        r.any { it.isNotBlank() } && r.none(::isTotal) &&
            !repeatOf(r, headers) && !isNumbering(r) && sectionOf(r) == null

    /** Явная пометка инвентарника в тексте: «инв. №», «инв.ном», «inv no». */
    private val INV_MARK = Regex("""(?iu)(?:^|[^\p{L}])(инв|inv)[a-zа-я]*\.?\s*(№|n\b|no\b|номер|ном)""")
    private val INV_IN_NAME = Regex("""[,;(]?\s*(?:инв|inv)[a-zа-я]*\.?\s*(?:№|n|no|номер)?\s*[:.]?\s*([0-9A-Za-zА-Яа-яЁё](?:[0-9A-Za-zА-Яа-яЁё/.,\-]| (?=\d))*[0-9A-Za-zА-Яа-яЁё])\)?""", RegexOption.IGNORE_CASE)

    /**
     * Таблица листа; null — на листе нет списка предметов (нет ни инвентарников, ни наименований).
     * [choice] — что человек указал сам (экран «Столбцы описи»): лист не брать, где шапка, откуда
     * предметы, какой столбец за какое поле. Указанное — поверх подбора; не указанное — как решит подбор.
     */
    fun detect(sheet: Xlsx.Sheet, choice: SheetChoice? = null): Table? {
        if (choice?.skip == true) return null
        val auto = detectAuto(sheet, choice?.header)
        if (choice == null || choice.columns.isEmpty() && choice.body == null && choice.header == null) return auto
        val rows = sheet.rows
        val hr = choice.header ?: auto?.headerRow ?: -1
        val width = rows.maxOfOrNull { it.size } ?: 0
        val headers = auto?.headers ?: if (hr >= 0) composite(rows, hr) else List(width) { "" }
        val body = (choice.body ?: auto?.body ?: (hr + 1)).coerceIn(0, rows.size)
        val columns = HashMap(auto?.columns.orEmpty())
        for ((name, c) in choice.columns) {
            val f = Field.entries.firstOrNull { it.name == name } ?: continue
            if (c >= 0) columns.entries.removeAll { it.value == c && it.key != f }  // столбец — одному полю
            if (c < 0) columns.remove(f) else columns[f] = c
        }
        if (Field.INVENTORY !in columns && Field.NAME !in columns) return null
        // Инвентарник из наименования — если его столбца нет и человек не сказал «не брать».
        val fromName = Field.INVENTORY !in columns && columns[Field.NAME]?.let { c ->
            val names = rows.drop(body).map { it.getOrElse(c) { "" } }.filter(String::isNotBlank)
            names.isNotEmpty() && names.count { INV_IN_NAME.containsMatchIn(it) } >= names.size / 2
        } == true
        return Table(sheet.name, hr, headers, columns, emptyList(), auto?.candidates.orEmpty(),
            rows.drop(body).take(5), count = rows.size, body = body, invFromName = fromName)
    }

    /** Подбор сам; [forcedHeader] — строка шапки, указанная человеком (−1 — шапки нет). */
    private fun detectAuto(sheet: Xlsx.Sheet, forcedHeader: Int?): Table? {
        val rows = sheet.rows
        if (rows.count { it.any(String::isNotBlank) } < 2) return null  // шапка и хоть одна строка
        val hr = if (forcedHeader != null) forcedHeader.takeIf { it in rows.indices } else headerRow(rows)
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
                    // Ясная шапка («Инв. номер», «Наименование», «№ п/п») решает сама: данные
                    // столбца выбирают из равных, но не отменяют его. Сбои — заглушки, ошибки
                    // Excel, мусор — разбираются по ячейкам и задевают только свою ячейку
                    // (автор, 13.10.2026: «Обрабатываем не столбец целиком, а по ячейкам,
                    // чтобы сбои не влияли»).
                    if (h <= 0) Int.MIN_VALUE else if (h >= 10) h + maxOf(d, 0) else h + d
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

    /**
     * Место предмета для экрана: без слова «подразделение»; общий раздел 1С («Основное
     * подразделение») — не место, пусто (автор, 09.10.2026: «Какое ещё основное подразделение?
     * Убирай слово подразделение»).
     */
    fun placeName(raw: String): String {
        val t = raw.replace(Regex("""(?iu)подразделени[еяю]\s*:?"""), " ").replace(Regex("""\s+"""), " ").trim(' ', ':', ',', '-', '—')
        return if (t.lowercase() in GENERIC_PLACES) "" else t
    }
    private val GENERIC_PLACES = setOf("", "основное", "главное", "общее", "основной", "основная")

    /**
     * Раздел описи — место или список. Похож на место («Здание 1», «Кабинет 101», «Каб. 201», «Цех 2»)
     * — место; любой другой — список предметов, а список — это приоритет (автор, 09.10.2026: «Там не
     * подразделения, а списки. Это приоритеты»).
     */
    /**
     * Раздел — место, а не список: начинается с вида помещения или называет этаж, корпус,
     * кабинет где угодно в строке («Коридор, 1 этаж», «Холл у входа»). Прежде — только по
     * началу строки, и «Коридор, 1 этаж» уходил в списки (13.10.2026).
     */
    private val PLACE_SECTION = Regex("""(?iu)^\s*(здани|корпус|кабинет|каб\.|каб\s|этаж|цех|комнат|помещени|склад|лаборатор|коридор|холл|фойе|вестибюл|подвал|чердак|гараж|серверн|котельн|кладов|тамбур|лестниц|кухн|столов|актов|зал\b|двор|территори|room|building|floor|office|block|hall|corridor)|\b(\d+\s*этаж|этаж\s*\d+|корпус|кабинет|каб\.\s*\d)""")

    /** Номер приоритета из названия списка: «Список 1», «1 приоритет», «П2», «Приоритет №3». */
    fun priorityOfList(name: String): String {
        val n = name.trim()
        Regex("""(?iu)(?:список|приоритет|п)\s*№?\s*(\d{1,2})\b""").find(n)?.let { return it.groupValues[1] }
        Regex("""(?iu)^(\d{1,2})\s*[-–—.)]?\s*(?:список|приоритет)""").find(n)?.let { return it.groupValues[1] }
        return ""
    }

    /** Предметы листа по выбранным столбцам; итоги, повторы шапки, «1 2 3…» и пустые строки пропускаются; разделы — местом или списком. */
    fun items(sheet: Xlsx.Sheet, t: Table): List<Item> {
        fun cell(r: List<String>, f: Field) = t.columns[f]?.let { r.getOrElse(it) { "" } }.orEmpty().trim()
        fun int(s: String) = number(s)?.takeIf(::whole)?.toLong()?.toString()
        val out = mutableListOf<Item>()
        // Раздел прямо над шапкой («Здание 1», «Список 1») — место или список первых предметов.
        var placeSection = ""; var listSection = ""
        fun section(sec: String) { if (PLACE_SECTION.containsMatchIn(sec)) placeSection = sec else listSection = sec }
        sheet.rows.getOrNull(t.headerRow - 1)?.let(::sectionOf)?.takeIf { t.headerRow > 0 }?.let(::section)
        for (i in t.body until sheet.rows.size) {
            val r = sheet.rows[i]
            // Раздел объединён на всю ширину — его текст и в столбце инвентарника.
            sectionOf(r)?.let { sec -> cell(r, Field.INVENTORY).let { if (it.isEmpty() || it == sec) { section(sec); continue } } }
            if (!dataRow(r, t.headers)) continue
            // Ячейка инвентарника проверяется сама: заглушка или текст без единой цифры («см. акт»,
            // «уточнить», «ЗНАЧ!») — у этого предмета инвентарника нет; столбец от этого не страдает.
            var inv = cell(r, Field.INVENTORY).let { if (placeholder(it) || it.none(Char::isDigit)) "" else it.replace('\u00A0', ' ') }
            // Наименование: переносы строк и неразрывные пробелы — пробелом; «#N/A», «#ЗНАЧ!» — не название.
            var name = cell(r, Field.NAME).replace(Regex("""[\s\u00A0]+"""), " ").trim().let { if (Regex("""^#\S+$""").matches(it)) "" else it }
            // Инвентарник в наименовании: столбца нет вовсе — или он есть, но в этой строке пуст, а в
            // названии явно «инв. №…» («Монитор Samsung 24 инв. № 013/4501», 13.10.2026).
            if (inv.isEmpty() && (t.invFromName || INV_MARK.containsMatchIn(name))) INV_IN_NAME.find(name)?.let { m ->
                inv = m.groupValues[1]; name = name.removeRange(m.range).trim(' ', ',', ';', '.', '-')
            }
            if (inv.isEmpty() && name.isEmpty()) continue
            if (inv.isEmpty() && name.none(Char::isLetter)) continue  // «01.01», «2» — не предмет
            val n = cell(r, Field.NUMBER).let { s -> int(s) ?: s }
            out += Item(
                list = listSection.ifEmpty { t.sheet }, number = n.ifEmpty { (out.size + 1).toString() }, inventory = inv, name = name,
                initial = number(cell(r, Field.INITIAL)), sum = number(cell(r, Field.SUM)),
                priority = cell(r, Field.PRIORITY).let { s -> int(s) ?: priorityOf(s) }.ifEmpty { priorityOfList(listSection) },
                status = cell(r, Field.LIST), row = i + 1,
                qty = cell(r, Field.QUANTITY).let { s -> int(s) ?: s },
                place = placeName(cell(r, Field.PLACE).ifEmpty { placeSection }),
            )
        }
        return out
    }

    /** Ключ инвентарника для сравнения и id предмета (как в Инвентарники.txt). */
    fun key(inv: String) = Search.compact(inv)
    fun id(i: Item) = i.inventory.ifEmpty { i.key.ifEmpty { "№" + i.number } }

    /**
     * Предметы без инвентарника с одинаковым номером — разные предметы: нумерация начинается
     * заново в каждом разделе, и «№6» перегородки и «№6» паллеты иначе делили бы одну папку и
     * одну отметку «осмотрено» (13.10.2026). Первый остаётся «№6» (папки прежних версий не
     * теряются), следующим — список и строка.
     */
    fun distinctIds(items: List<Item>): List<Item> {
        val seen = HashSet<String>()
        return items.map { i ->
            if (i.inventory.isNotEmpty()) i
            else if (seen.add("№" + i.number)) i
            else i.copy(key = "№${i.number} (${i.list}, стр. ${i.row})")
        }
    }

    /**
     * Имя папки предмета: «№ в описи. инвентарник» — «13. 1562∕65»; нет инвентарника — просто
     * номер «13»; несколько предметов — «… +2 шт». «/» в именах папок запрещён — похожая «∕».
     */
    /**
     * Имя папки предмета — «Номер. Инвентарник — Название» (автор, 13.10.2026: «Формат выгрузки —
     * Номер. Инвентарник — (длинное тире) Название»; везде — на телефоне, по USB и в архиве).
     * Без инвентарника — «Номер — Название». Дробь — U+2215: обычная «/» в имени невозможна.
     * Название — не длиннее [MAX_TITLE] с многоточием: имя целиком укладывается в предел файловых
     * систем (255 байт, кириллица — по два).
     */
    fun folderName(first: Item, count: Int = 1): String {
        fun safe(t: String) = t.replace('/', '∕').replace('\\', '∖')
        val head = if (first.inventory.isEmpty()) first.number else first.number + ". " + safe(first.inventory.take(MAX_INV))
        val name = Store.cleanName(safe(first.name))
        val title = if (name.length > MAX_TITLE) name.take(MAX_TITLE).trimEnd() + "…" else name
        val base = Store.cleanName(if (title.isEmpty()) head else "$head — $title")
        return base + if (count > 1) " +${count - 1} шт" else ""
    }

    /** Вся опись из байтов книги (без папки и кэша). */
    fun parseBook(book: ByteArray, progress: ((Float, String) -> Unit)? = null, choices: Choices = Choices()): List<Item> =
        read(Xlsx.sheets(book, progress), choices).items

    // ---------- Чтение вручную: лист, шапка, первая строка, столбцы ----------
    //
    // Подбор решает сам, но ошибиться может (автор, 13.10.2026: «Есть ли возможность отредактировать
    // чтение файла, например если автоматически не тот столбец считался»). Человек указывает на
    // экране «Столбцы описи»: лист не брать, где шапка, с какой строки предметы, какой столбец за
    // какое поле. Не указанное — по-прежнему подбор. Выбор лежит рядом с описью, виден по USB, идёт
    // в журнал и в архив; пришёл исправленный файл того же шаблона — выбор остаётся в силе, другой
    // шаблон (верх листа иной) — выбор не применяется, и экран об этом говорит.

    /** Ручной выбор к описи — файлом рядом с ней. */
    const val COLUMNS = "Столбцы описи.json"

    @kotlinx.serialization.Serializable
    data class SheetChoice(
        val sheet: String,
        /** Лист не брать в опись (сводка, правила, вынесенное). */
        val skip: Boolean = false,
        /** Строка шапки, с нуля; −1 — шапки нет; null — как найдёт подбор. */
        val header: Int? = null,
        /** Первая строка предметов, с нуля; null — сразу под шапкой. */
        val body: Int? = null,
        /** Поле ([Field.name]) → столбец, с нуля; −1 — поле не брать. */
        val columns: Map<String, Int> = emptyMap(),
        /** Отпечаток верха листа, к которому относится выбор, и сколько строк он охватывает. */
        val sign: String = "", val signRows: Int = 0,
    ) {
        val empty get() = !skip && header == null && body == null && columns.isEmpty()
    }

    @kotlinx.serialization.Serializable
    data class Choices(val sheets: List<SheetChoice> = emptyList()) {
        fun of(name: String) = sheets.firstOrNull { it.sheet == name }
        fun with(c: SheetChoice) = Choices(sheets.filter { it.sheet != c.sheet } + listOfNotNull(c.takeUnless { it.empty }))
    }

    /** Отпечаток верха листа: [rows] первых строк (шапка и что над ней). */
    fun signOf(sheet: Xlsx.Sheet, rows: Int): String =
        sheet.rows.take(rows.coerceIn(1, 15)).joinToString("\n") { r -> r.joinToString("\t") { norm(it) }.trimEnd('\t') }.hashCode().toString(16)

    /** Выбор с отпечатком того верха листа, который он описывает (до первой строки предметов). */
    fun sealed(sheet: Xlsx.Sheet, c: SheetChoice): SheetChoice {
        if (c.empty) return c
        val rows = (detect(sheet, c)?.body ?: c.body ?: 1).coerceAtLeast(1)
        return c.copy(sign = signOf(sheet, rows), signRows = rows)
    }

    /** Лист, как его прочли: таблица (null — не взят), его предметы, ручной выбор и подошёл ли он. */
    class SheetReading(val sheet: Xlsx.Sheet, val table: Table?, val items: List<Item>, val choice: SheetChoice?, val stale: Boolean)
    class Reading(val sheets: List<SheetReading>, val items: List<Item>)

    /** Прочесть листы с ручным выбором поверх подбора. */
    fun read(sheets: List<Xlsx.Sheet>, choices: Choices): Reading {
        val per = sheets.map { s ->
            val c = choices.of(s.name)
            val fits = c == null || c.sign.isEmpty() || c.sign == signOf(s, c.signRows)
            val t = detect(s, c.takeIf { fits })
            SheetReading(s, t, t?.let { items(s, it) }.orEmpty(), c, stale = !fits)
        }
        return Reading(per, distinctIds(per.flatMap { it.items }))
    }

    fun choicesOf(obj: File): Choices = runCatching {
        File(obj, COLUMNS).takeIf { it.isFile }?.let { pretty.decodeFromString(Choices.serializer(), it.readText()) }
    }.getOrNull() ?: Choices()

    /** Записать выбор (пустой — убрать файл): в журнал — что именно указано. */
    fun setChoices(obj: File, c: Choices) {
        val f = File(obj, COLUMNS)
        val clean = Choices(c.sheets.filterNot { it.empty })
        if (clean == choicesOf(obj)) return
        if (clean.sheets.isEmpty()) f.delete() else Store.writeDurably(f, pretty.encodeToString(Choices.serializer(), clean).encodeToByteArray())
        Journal.add(obj, "Столбцы описи", describe(clean), f, gone = clean.sheets.isEmpty())
        Store.scan(listOf(f)); Store.changed()
    }
    private val pretty = kotlinx.serialization.json.Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Буква столбца, как в Excel: 0 → «A», 26 → «AA». */
    fun letter(c: Int): String {
        var n = c + 1; val out = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; out.insert(0, 'A' + r); n = (n - 1) / 26 }
        return out.toString()
    }

    /** Выбор словами — для журнала и отчёта. */
    fun describe(c: Choices): String = if (c.sheets.isEmpty()) "как решит приложение" else c.sheets.joinToString("; ") { s ->
        "«${s.sheet}»: " + if (s.skip) "не брать" else (listOfNotNull(
            s.header?.let { if (it < 0) "без шапки" else "шапка — строка ${it + 1}" },
            s.body?.let { "предметы со строки ${it + 1}" },
        ) + s.columns.entries.map { (f, c) -> (Field.entries.firstOrNull { it.name == f }?.title ?: f) + " — " + if (c < 0) "не брать" else letter(c) })
            .joinToString(", ")
    }

    /**
     * Что в прочитанном подозрительно — повод проверить столбцы. По предметам: инвентарник у
     * малой доли, наименования пусты или одни числа, инвентарники повторяются массово.
     */
    fun itemWarnings(items: List<Item>): List<String> = buildList {
        val n = items.size
        if (n == 0) { add("Ни одного предмета: укажите столбец с инвентарными номерами или наименованиями"); return@buildList }
        val withInv = items.count { it.inventory.isNotEmpty() }
        if (withInv == 0) add("Инвентарных номеров нет ни у одного предмета — предметы пойдут по номерам строк")
        else if (withInv * 2 < n) add("Инвентарный номер есть только у $withInv из $n предметов — проверьте столбец «Инв. номер»")
        val noName = items.count { it.name.isEmpty() }
        if (noName * 10 > n * 3) add("Без наименования $noName из $n предметов — проверьте столбец «Наименование»")
        val numeric = items.count { it.name.isNotEmpty() && it.name.none(Char::isLetter) }
        if (numeric * 2 > n) add("В наименованиях почти одни числа — похоже, взят не тот столбец")
        val dup = items.filter { it.inventory.isNotEmpty() }.groupBy { key(it.inventory) }.values.filter { it.size > 1 }.sumOf { it.size }
        if (withInv > 0 && dup * 5 > withInv) add("Инвентарные номера повторяются у $dup предметов — проверьте столбец «Инв. номер»")
    }

    /** Предупреждения ко всему прочитанному: по предметам, по листам и по выбору, который не подошёл. */
    fun warnings(r: Reading): List<String> = buildList {
        r.sheets.filter { it.stale }.forEach { add("Прежний ручной выбор к листу «${it.sheet.name}» не подошёл: верх листа другой — столбцы найдены заново") }
        addAll(itemWarnings(r.items))
        r.sheets.filter { it.table == null && it.choice?.skip != true && it.sheet.rows.count { row -> row.any(String::isNotBlank) } >= 5 }
            .forEach { add("Лист «${it.sheet.name}» не взят в опись — если на нём предметы, укажите столбцы") }
    }

    // ---------- Чужой и битый файл ----------

    /**
     * Почему книга не годится — по содержимому, а не по расширению: 1С отдаёт «.xls», который на
     * деле веб-страница; мессенджер теряет расширение; пароль превращает .xlsx в контейнер OLE;
     * недокачанный файл обрывается посреди архива. null — это книга Excel (.xlsx) с листами.
     */
    fun problemOf(book: ByteArray): String? {
        if (book.isEmpty()) return "файл пустой (0 байт) — похоже, не докачался; пришлите заново"
        fun starts(vararg b: Int) = book.size >= b.size && b.indices.all { book[it] == b[it].toByte() }
        val head = book.copyOfRange(0, minOf(book.size, 4096)).decodeToString().trimStart('﻿', ' ', '\r', '\n', '\t').lowercase()
        when {
            starts(0xD0, 0xCF, 0x11, 0xE0) -> return if (utf16In(book, "EncryptionInfo"))
                "файл защищён паролем: откройте в Excel, снимите пароль (Файл → Сведения → Защита книги) и сохраните заново"
                else "старый формат Excel (.xls): откройте в Excel и сохраните как .xlsx"
            starts(0x25, 0x50, 0x44, 0x46) -> return "это PDF, а не таблица: нужна сама опись в Excel (.xlsx)"
            starts(0xFF, 0xD8, 0xFF) || starts(0x89, 0x50, 0x4E, 0x47) -> return "это картинка, а не таблица: нужна опись в Excel (.xlsx)"
            head.startsWith("<?xml") && "urn:schemas-microsoft-com:office:spreadsheet" in head ->
                return "таблица Excel 2003 (XML): откройте в Excel и сохраните как .xlsx"
            head.startsWith("<") && ("<html" in head || "<table" in head || "<!doctype" in head) ->
                return "это веб-страница (HTML) под видом Excel — так выгружает 1С: откройте в Excel и сохраните как .xlsx"
            starts(0x50, 0x4B) -> {}
            text(book) -> return "это текст (CSV), а не книга Excel: откройте в Excel и сохраните как .xlsx"
            else -> return "это не таблица Excel (.xlsx)"
        }
        val files = unzip(book)
        if (files.isEmpty()) return "файл повреждён — не открывается как книга Excel (похоже, не докачался); пришлите заново"
        if ("word/document.xml" in files) return "это документ Word, а не таблица Excel"
        if (files.keys.any { it.startsWith("ppt/") }) return "это презентация, а не таблица Excel"
        if (files["mimetype"]?.decodeToString()?.contains("opendocument.spreadsheet") == true)
            return "таблица LibreOffice (.ods): сохраните её как .xlsx"
        if ("xl/workbook.xml" !in files) return "это архив, а не книга Excel"
        if (files.keys.none { it.startsWith("xl/worksheets/") }) return "в книге нет листов — файл повреждён; пришлите заново"
        return null
    }

    /** Строка [s] в UTF-16LE где-то в байтах (имена потоков контейнера OLE). */
    private fun utf16In(book: ByteArray, s: String): Boolean {
        val pat = ByteArray(s.length * 2) { i -> if (i % 2 == 0) s[i / 2].code.toByte() else 0 }
        outer@ for (i in 0..book.size - pat.size) { for (j in pat.indices) if (book[i + j] != pat[j]) continue@outer; return true }
        return false
    }

    /** Похоже на текст: в начале нет нулевых байтов и почти нет управляющих. */
    private fun text(book: ByteArray): Boolean {
        val head = book.copyOfRange(0, minOf(book.size, 4096))
        return head.none { it == 0.toByte() } && head.count { it in 1..8 || it in 14..31 } * 50 < head.size
    }

    /** Такая же книга уже лежит описью — её папка (сравнение по размеру, затем по байтам). */
    fun sameAs(book: ByteArray): File? = Store.foldersIn(Store.root).firstOrNull { d ->
        fileIn(d)?.takeIf { it.length() == book.size.toLong() }?.readBytes()?.contentEquals(book) == true
    }

    /** Что будет при замене файла описи [dir] книгой [book]: тот же ли, сколько предметов, чьи папки потеряют предмет. */
    class ReplaceCheck(val same: Boolean, val items: Int, val bound: Int, val lost: List<String>)

    fun checkReplace(dir: File, book: ByteArray): ReplaceCheck {
        val old = fileIn(dir)
        if (old != null && old.length() == book.size.toLong() && old.readBytes().contentEquals(book)) return ReplaceCheck(true, 0, 0, emptyList())
        val keys = parseBook(book, choices = choicesOf(dir)).map { key(id(it)) }.toSet()
        val bound = boundIn(dir)
        return ReplaceCheck(false, keys.size, bound.size, bound.filter { key(it) !in keys })
    }

    /** Предметы, у которых в описи [dir] уже есть папка (по «Инвентарники.txt»). */
    fun boundIn(dir: File): List<String> = dir.walkTopDown().onEnter { it == dir || !it.name.startsWith(".") }
        .filter { it.isDirectory && it != dir }.flatMap { members(it) }.distinct().toList()

    /** Прежний файл описи — до последней замены: вернуть, если заменили не тем. Скрытый, в журнал не идёт. */
    const val PREVIOUS = ".прежняя опись.xlsx"
    fun hasPrevious(dir: File) = File(dir, PREVIOUS).isFile
    /** Вернуть прежний файл; нынешний становится прежним — можно вернуть обратно. */
    fun restorePrevious(dir: File) {
        val b = File(dir, PREVIOUS).readBytes()
        replace(dir) { it.write(b) }
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
    fun create(name: String, write: (okio.BufferedSink) -> Unit): File {
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
     * Книга (байты файла) — новой описью в своей папке. Годится ли — по содержимому ([problemOf]):
     * не книга, битая, под паролем — отказ с понятной причиной, папка не заводится. Такая же уже
     * загружена — отказ с её именем. Книга читается, а списка предметов подбор не нашёл — опись
     * всё равно заводится, пустой: человек укажет столбцы сам (экран «Столбцы описи»). Имя — из
     * имени файла, а если оно мусорное (мессенджеры шлют «040dc55f-115.4.-____.xlsx») — из заголовка в книге.
     */
    fun importBook(book: ByteArray, fileName: String, onFile: (File) -> Unit = {}): Parsed {
        problemOf(book)?.let { throw IllegalArgumentException(it) }
        sameAs(book)?.let { throw IllegalArgumentException("эта опись уже загружена — «${it.name}»") }
        val tmp = File(Store.cacheDir, "import-${Platform.nanoTime()}.xlsx").also { it.parentFile?.mkdirs(); it.writeBytes(book) }
        val dir = try { create(nameFor(fileName, tmp)) { it.write(book) } } finally { tmp.delete() }
        val f = fileIn(dir)!!
        onFile(f)
        // Разбор — сразу, один раз: кэш готов, опись открывается мгновенно.
        val p = runCatching { parse(f) }.getOrElse { dir.deleteRecursively(); Store.changed(); throw it }
        if (p.items.isEmpty() && Xlsx.sheets(book).all { s -> s.rows.none { r -> r.any(String::isNotBlank) } }) {
            dir.deleteRecursively(); Store.changed(); throw IllegalArgumentException("в книге все листы пустые")
        }
        return p
    }

    /**
     * Прикрепить опись к уже начатой папке [dir] или заменить её файл. Книга не годится — отказ,
     * прежняя опись цела. Годится — ложится, прежний файл — в запас ([restorePrevious]); списка
     * подбор не нашёл — человек укажет столбцы или вернёт прежний файл.
     */
    fun attachBook(book: ByteArray, fileName: String, dir: File): Parsed {
        problemOf(book)?.let { throw IllegalArgumentException(it) }
        replace(dir) { it.write(book) }
        return parse(fileIn(dir)!!)
    }

    /** Имя описи: из имени файла, если оно человеческое; иначе — заголовок из книги; иначе — «Опись от 08.10». */
    fun nameFor(fileName: String, book: File): String {
        val stem = fileName.substringBeforeLast('.').trim()
        val words = Regex("""[\p{L}]{3,}""").findAll(stem).count()
        val junk = stem.count { it == '_' || it == '-' } > stem.length / 4 || Regex("""[0-9a-f]{8}""").containsMatchIn(stem.lowercase())
        if (words >= 1 && !junk) return stem.take(MAX_NAME).trim()
        val title = Xlsx.sheets(book.readBytes(), rowLimit = 4).asSequence().flatMap { it.rows.take(4).asSequence() }
            .mapNotNull(::sectionOf).firstOrNull { t -> t.length in 3..MAX_NAME && headerFields(listOf(t)).isEmpty() }
        return title ?: ("Опись от " + Platform.format(Platform.nowMs(), "dd.MM"))
    }

    /**
     * Заменить файл описи папки (пришла исправленная): папки предметов остаются; ручной выбор
     * столбцов — в силе, если шаблон тот же; прежний файл — в запас ([PREVIOUS]).
     */
    fun replace(dir: File, write: (okio.BufferedSink) -> Unit) {
        require(dir != Store.root) { "опись — только в своей папке" }  // в корне её не видно и не удалить
        val f = fileIn(dir) ?: File(dir, PREFIX + dir.name + ".xlsx")
        cacheFile(f).delete()
        if (f.isFile) runCatching { Store.writeDurably(File(dir, PREVIOUS), f.readBytes()) }
        Store.writeDurably(f, write)
        cacheFile(f).delete()
        Journal.add(dir, "Файл описи", f.name, f)
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
    data class Parsed(val file: File, val items: List<Item>) {
        val lists get() = items.map { it.list }.distinct()
    }

    /** Идёт разбор: какой файл, доля (0…1) и что делается — экраны показывают шкалу, а не «висит». */
    data class Parsing(val file: File, val fraction: Float, val stage: String)
    /** Разборы идут параллельно (несколько описей разом) — у каждого файла своя шкала. */
    val parsing = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Parsing>>(emptyMap())
    private fun tell(f: File, fraction: Float, stage: String) = parsing.update { it + (f.absolutePath to Parsing(f, fraction, stage)) }
    private fun told(f: File) = parsing.update { it - f.absolutePath }
    /** Один файл разбирается один раз: кто пришёл вторым — ждёт и берёт готовое из кэша. */
    private val locks = HashMap<String, kotlinx.atomicfu.locks.SynchronizedObject>()
    private val locksLock = kotlinx.atomicfu.locks.SynchronizedObject()
    private fun lockOf(path: String) = kotlinx.atomicfu.locks.synchronized(locksLock) { locks.getOrPut(path) { kotlinx.atomicfu.locks.SynchronizedObject() } }

    @kotlinx.serialization.Serializable
    private class Cached(val items: List<Item>)
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private fun cacheFile(f: File) = File(Store.cacheDir, "inventory/" + ("v8|" + f.absolutePath + "|" + stampOf(f)).hashCode() + ".json")
    /** Файл описи и выбор столбцов к нему: сменилось одно из двух — разбор заново. */
    private fun stampOf(f: File): String {
        val c = File(f.parentFile!!, COLUMNS)
        return "${f.length()}|${f.lastModified()}|${c.length()}|${c.lastModified()}"
    }
    private fun cached(f: File, cache: File): Parsed? = runCatching { if (cache.isFile) Parsed(f, json.decodeFromString<Cached>(cache.readText()).items) else null }.getOrNull()

    /**
     * Разобрать (или взять готовое); один файл — один разбор, второй ждёт готовое. Разобранное
     * держится в памяти, пока файл тот же (размер и время): с диска кэш читался на каждый снимок
     * и каждую отметку — 70 мс на 5,7 тыс. строк на компьютере, на телефоне в разы дольше.
     */
    fun parse(f: File): Parsed {
        val stamp = stampOf(f)
        kotlinx.atomicfu.locks.synchronized(memoLock) { memo[f.absolutePath]?.takeIf { it.first == stamp }?.let { return it.second } }
        val cache = cacheFile(f)
        val p = cached(f, cache) ?: kotlinx.atomicfu.locks.synchronized(lockOf(f.absolutePath)) {
            cached(f, cache) ?: try { parseFresh(f, cache) } finally { told(f) }
        }
        kotlinx.atomicfu.locks.synchronized(memoLock) { memo[f.absolutePath] = stamp to p }
        return p
    }
    private val memo = HashMap<String, Pair<String, Parsed>>()
    private val memoLock = kotlinx.atomicfu.locks.SynchronizedObject()

    private fun parseFresh(f: File, cache: File): Parsed {
        tell(f, 0f, "Открываем файл")
        var t = Platform.nowMs(); val times = StringBuilder()
        fun lap(what: String) { val now = Platform.nowMs(); times.append(" $what ${now - t}"); t = now }
        val book = f.readBytes(); lap("чтение")
        // Чтение — 85 % шкалы, поиск столбцов и строк — остальное.
        val sheets = Xlsx.sheets(book, progress = { part, sheet -> tell(f, 0.85f * part, "Читаем лист «$sheet»") }); lap("листы")
        tell(f, 0.88f, "Ищем столбцы")
        val items = read(sheets, choicesOf(f.parentFile!!)).items; lap("столбцы и предметы")
        tell(f, 0.97f, "Собираем список: ${items.size} предметов")
        runCatching { cache.parentFile?.mkdirs(); cache.writeText(json.encodeToString(Cached.serializer(), Cached(items))) }; lap("кэш")
        // Имя описи — отпечатком: в телеметрию имена не идут.
        Telemetry.log("разбор описи ${Telemetry.id(f.name)}: ${items.size} предметов, ${book.size / 1024} КБ, мс:$times")
        return Parsed(f, items)
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

    /** Предмет описи не нашёлся (опись заменили) — по одним инвентарникам. */
    private fun folderName(invs: List<String>): String =
        (Store.cleanName(invs.first().replace('/', '∕')).take(MAX_INV).trim() + if (invs.size > 1) " +${invs.size - 1} шт" else "")
    private const val MAX_INV = 48
    /** Название в имени папки предмета — не длиннее (см. [folderName]). */
    private const val MAX_TITLE = 48
    /** Имя папки описи — не длиннее (оно же повторяется в именах её книг). */
    const val MAX_NAME = 60
    /**
     * Инвентарник из имени папки без файла состава: «13. 1562∕65 — Ноутбук +2 шт», «13. 1562∕65 +2 шт»,
     * «777 1001 (3 шт)». Просто номер («13», «13 — ИВЛ») — не инвентарник.
     */
    private fun invOfName(name: String) = name.replace(Regex("""^\d+\.\s+"""), "").substringBefore(" — ")
        .substringBefore(" (").substringBefore(" +").replace('∕', '/')


    /** Ближайшая папка с описью — папка объекта (сама [dir] или выше). */
    fun objectOf(dir: File): File? = generateSequence(dir) { if (it == Store.root) null else it.parentFile }
        .firstOrNull { filesIn(it).isNotEmpty() }

    /**
     * Где что лежит у описи: инвентарник → папка; папка → снимков (со вложенными) и когда сняты
     * первый и последний. Что с предметом не так (нет на месте, неисправен) — комментарием его папки.
     */
    class Status(val folder: Map<String, File>, val photos: Map<File, Int>,
                 val first: Map<File, Long> = emptyMap(), val last: Map<File, Long> = emptyMap(),
                 val brokenKeys: Set<String> = emptySet(), val earlierKeys: Set<String> = emptySet(),
                 val repairKeys: Set<String> = emptySet()) {
        /** Нерабочее (на списание) — отмечено в «Нерабочие.txt» описи и снято: без снимков отметка не действует. */
        fun broken(i: Item) = photosOf(i) > 0 && key(id(i)) in brokenKeys
        /** Требуется ремонт — неисправно на момент осмотра, но не на списание («Требуется ремонт.txt»). */
        fun repair(i: Item) = photosOf(i) > 0 && key(id(i)) in repairKeys && !broken(i)
        fun folderOf(i: Item) = folder[key(id(i))]
        fun photosOf(i: Item) = folderOf(i)?.let { photos[it] } ?: 0
        /** Осмотрено — снято в приложении или отмечено раньше (импорт «Осмотрено ранее.txt»). */
        fun inspected(i: Item) = photosOf(i) > 0 || key(id(i)) in earlierKeys
        /** Осмотрено только по прежним отметкам — снимков в приложении нет. */
        fun earlier(i: Item) = photosOf(i) == 0 && key(id(i)) in earlierKeys
        fun firstShot(i: Item) = folderOf(i)?.let { first[it] }
        fun lastShot(i: Item) = folderOf(i)?.let { last[it] }
        /** Снят сегодня (с полуночи) — «сегодня +N» на выезде. */
        fun today(i: Item) = (lastShot(i) ?: 0L) >= Platform.startOfDay()
    }

    fun status(obj: File): Status {
        val folder = HashMap<String, File>()
        val photos = HashMap<File, Int>(); val first = HashMap<File, Long>(); val last = HashMap<File, Long>()
        // Снимки папки — со вложенными: сначала вглубь, потом сама папка.
        fun visit(d: File): Triple<Int, Long, Long> {
            val f = Folders.facts(d)
            if (d != obj) (f.members.ifEmpty { listOf(invOfName(d.name)) }).forEach { inv -> folder.getOrPut(key(inv)) { d } }
            var n = f.photos; var lo = if (f.photos > 0) f.first else Long.MAX_VALUE; var hi = f.last
            for (sub in f.subdirs) {
                val (cn, clo, chi) = visit(File(d, sub))
                n += cn; if (cn > 0) { lo = minOf(lo, clo); hi = maxOf(hi, chi) }
            }
            if (d != obj) { photos[d] = n; if (n > 0) { first[d] = lo; last[d] = hi } }
            return Triple(n, lo, hi)
        }
        visit(obj)
        Folders.saveSoon()
        return Status(folder, photos, first, last, brokenOf(obj), earlierOf(obj), repairOf(obj))
    }

    // ---------- Нерабочие ----------
    //
    // Нерабочие (на списание) предметы описи — файлом «Нерабочие.txt» в её папке, по инвентарнику
    // на строку: виден на компьютере, уходит в архив, в отчёте — столбец «Состояние». Отметить
    // можно только снятое (автор 09.10.2026: «делаем фотографии, и только потом можем как
    // нерабочее/списанное помечать») — в папке предмета; снимки удалили — отметка не действует.

    const val BROKEN = "Нерабочие.txt"

    private fun brokenLines(obj: File): List<String> = File(obj, BROKEN).takeIf { it.isFile }
        ?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun brokenOf(obj: File): Set<String> = brokenLines(obj).map(::key).toSet()

    /**
     * Отметить [items] нерабочими ([on]) или снять отметку; порядок строк в файле — как отмечали.
     * Отмечаются только снятые; сколько отмечено — в ответе.
     */
    fun setBroken(obj: File, items: List<Item>, on: Boolean): Int =
        mark(obj, BROKEN, items, on, "Нерабочее", "Снова рабочее", other = REPAIR, otherOff = "Ремонт не требуется")

    /**
     * Отметить «Требуется ремонт» или снять (автор, 15.10.2026, со слов осматривающей: «помимо
     * „нерабочее на списание“ просто „требуется ремонт“ — это как бы и не на списание, его можно
     * починить, но на момент осмотра неисправно»). Тем же порядком, что «Нерабочее»: только снятое,
     * файлом «Требуется ремонт.txt» в папке описи; одно из двух — отметка снимает другую.
     */
    fun setRepair(obj: File, items: List<Item>, on: Boolean): Int =
        mark(obj, REPAIR, items, on, "Требуется ремонт", "Ремонт не требуется", other = BROKEN, otherOff = "Снова рабочее")

    private fun mark(obj: File, name: String, items: List<Item>, on: Boolean, onWhat: String, offWhat: String,
                     other: String, otherOff: String): Int {
        val lines = markLines(obj, name).toMutableList()
        val st = if (on) status(obj) else null
        // Только снятое в приложении: «осмотрено ранее» (импорт) снимков не даёт — отметить нельзя.
        val take = if (on) items.filter { st!!.photosOf(it) > 0 } else items
        val keys = take.map { key(id(it)) }.toSet()
        lines.removeAll { key(it) in keys }
        if (on) lines += take.map(::id).distinctBy(::key)
        val f = File(obj, name)
        if (lines.isEmpty()) f.delete() else Store.writeDurably(f, (lines.joinToString("\n") + "\n").encodeToByteArray())
        if (take.isNotEmpty()) Journal.add(obj, if (on) onWhat else offWhat, take.joinToString(", ") { id(it) }, f, gone = lines.isEmpty())
        // Состояние одно: «на списание» снимает «ремонт» и наоборот.
        if (on && take.isNotEmpty()) {
            val rest = markLines(obj, other).toMutableList()
            val dropped = rest.filter { key(it) in keys }
            if (dropped.isNotEmpty()) {
                rest.removeAll { key(it) in keys }
                val g = File(obj, other)
                if (rest.isEmpty()) g.delete() else Store.writeDurably(g, (rest.joinToString("\n") + "\n").encodeToByteArray())
                Journal.add(obj, otherOff, dropped.joinToString(", "), g, gone = rest.isEmpty())
            }
        }
        Store.changed()
        return take.size
    }

    private fun markLines(obj: File, name: String): List<String> = File(obj, name).takeIf { it.isFile }
        ?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    // ---------- Требуется ремонт ----------

    const val REPAIR = "Требуется ремонт.txt"

    fun repairOf(obj: File): Set<String> = markLines(obj, REPAIR).map(::key).toSet()

    // ---------- Осмотрено ранее ----------
    //
    // Что люди отсмотрели до приложения — отметками в своих таблицах (автор, 13.10.2026:
    // «Ранее люди уже отмечали, что отсмотрели. Необходимо импортировать. Импортируем по
    // инвентарникам. Есть инвентарник — отмечаем выполненным»). Человек открывает свою таблицу
    // как в Excel и показывает столбец с инвентарниками; строки, объединённые по ширине, —
    // заголовки, пропускаются. Найденное — файлом «Осмотрено ранее.txt» в папке описи, по
    // инвентарнику на строку (виден на компьютере, уходит в архив); в отчёте — «Ранее».

    const val EARLIER = "Осмотрено ранее.txt"

    private fun earlierLines(obj: File): List<String> = File(obj, EARLIER).takeIf { it.isFile }
        ?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun earlierOf(obj: File): Set<String> = earlierLines(obj).map(::key).toSet()

    /**
     * Что даст импорт столбца [column] листа [sheet]: какие предметы описи нашлись, сколько из
     * них уже осмотрено, какие значения не нашлись в описи, сколько строк-заголовков пропущено.
     */
    class Earlier(val found: List<Item>, val already: Int, val unknown: List<String>, val skipped: Int, val rows: Int) {
        val fresh get() = found.size - already
    }

    fun previewEarlier(obj: File, sheet: Xlsx.Sheet, column: Int, st: Status = status(obj)): Earlier {
        val byKey = HashMap<String, MutableList<Item>>()
        for (i in filesIn(obj).flatMap { parse(it).items }) {
            byKey.getOrPut(key(id(i))) { mutableListOf() } += i
            if (i.inventory.isNotEmpty()) byKey.getOrPut(key(i.inventory)) { mutableListOf() }.let { if (i !in it) it += i }
        }
        val found = LinkedHashMap<String, Item>()
        val unknown = mutableListOf<String>()
        var skipped = 0; var rows = 0
        sheet.rows.forEachIndexed { r, row ->
            if (r in sheet.wide) { skipped++; return@forEachIndexed }
            val cell = row.getOrNull(column)?.trim().orEmpty()
            if (cell.isEmpty()) return@forEachIndexed
            rows++
            // Ячейка целиком, иначе — по словам: «1562/65 (кабинет 3)», «ИНВ 1001; 1002».
            val hits = (listOf(cell) + cell.split(' ', ';', ',', '\n', '\t').map { it.trim() }.filter { it.length >= 2 })
                .map(::key).distinct().flatMap { byKey[it].orEmpty() }
            if (hits.isEmpty()) unknown += cell else hits.forEach { found.getOrPut(key(id(it))) { it } }
        }
        val items = found.values.toList()
        return Earlier(items, items.count(st::inspected), unknown, skipped, rows)
    }

    /** Отметить осмотренным ранее всё, что нашлось в столбце (см. [previewEarlier]); [source] — откуда (имя файла). */
    fun importEarlier(obj: File, sheet: Xlsx.Sheet, column: Int, source: String): Earlier {
        val p = previewEarlier(obj, sheet, column)
        val lines = earlierLines(obj).toMutableList()
        val have = lines.map(::key).toSet()
        val add = p.found.map(::id).filter { key(it) !in have }.distinctBy(::key)
        if (add.isNotEmpty()) {
            lines += add
            val f = File(obj, EARLIER)
            Store.writeDurably(f, (lines.joinToString("\n") + "\n").encodeToByteArray())
            Journal.add(obj, "Осмотрено ранее", "$source, «${sheet.name}»: ${add.size} из ${p.rows} строк", f)
            Store.changed()
        }
        return p
    }

    /**
     * Снять отметку «осмотрено ранее» с [items] ([on] = false) или поставить её ([on] = true) —
     * вручную, без импорта (автор, 13.10.2026: «А как отметить невыполненным?»). Сколько
     * изменилось — в ответе; в журнал — запись.
     */
    fun setEarlier(obj: File, items: List<Item>, on: Boolean): Int {
        val lines = earlierLines(obj).toMutableList()
        val have = lines.map(::key).toSet()
        val take = items.filter { (key(id(it)) in have) != on }.distinctBy { key(id(it)) }
        if (take.isEmpty()) return 0
        val keys = take.map { key(id(it)) }.toSet()
        if (on) lines += take.map(::id) else lines.removeAll { key(it) in keys }
        val f = File(obj, EARLIER)
        if (lines.isEmpty()) f.delete() else Store.writeDurably(f, (lines.joinToString("\n") + "\n").encodeToByteArray())
        Journal.add(obj, if (on) "Отмечено осмотренным" else "Снята отметка «осмотрено»", take.joinToString(", ") { id(it) }, f, gone = lines.isEmpty())
        Store.changed()
        return take.size
    }

    // ---------- Выгрузки ----------

    /** Когда опись выгружали в последний раз — скрытым файлом в её папке (для «нового с прошлой»). */
    private fun exportMark(obj: File) = File(obj, ".выгрузка")
    fun lastExport(obj: File): Long? = runCatching { exportMark(obj).readText().trim().toLong() }.getOrNull()
    fun markExport(obj: File, at: Long = Platform.nowMs()) = runCatching {
        Store.writeDurably(exportMark(obj), at.toString().encodeToByteArray())
        // Отпечаток отчёта — в подробностях: правку отчёта в Excel видно на сервере (сам отчёт файлы не сверяют — он производный).
        Journal.add(obj, "Выгрузка", "отчёт " + (fileIn(obj)?.let(::reportFile)?.takeIf { it.isFile }?.let { Journal.hex(Platform.sha256File(it.path)) } ?: "—"))
    }

    /** Сколько снимков в описи новее [since] — для подписей выгрузки. */
    fun photosSince(obj: File, since: Long): Int =
        obj.walkTopDown().onEnter { it == obj || !it.name.startsWith(".") }.count { Store.isPhoto(it) && it.lastModified() > since }

    private fun writeMembers(dir: File, invs: List<String>) {
        Store.writeDurably(File(dir, MEMBERS), (invs.distinct().joinToString("\n") + "\n").encodeToByteArray())
        Store.scan(listOf(File(dir, MEMBERS)))
        Journal.add(dir, "Предметы в папке", Store.relative(dir) + ": " + invs.distinct().joinToString(", "), File(dir, MEMBERS))
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
            if (rest.isEmpty() && Store.photoTree(other).isEmpty() && Store.foldersIn(other).isEmpty()) {
                Journal.add(other.parentFile!!, Journal.FOLDER_DELETED, Store.relative(other) + ": предмет ушёл в другую папку", other, gone = true)
                other.deleteRecursively()
            }
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

    /**
     * «Такой же» предмет — то же наименование **и похожая цена** (автор 09.10.2026: «имя + цена»;
     * «аппарат ИВЛ и ИВЛ аппарат должны считаться похожими, если цены тоже похожи»):
     * - наименование — тот же набор слов в любом порядке, без регистра и знаков, латиница —
     *   кириллицей ([nameKey]);
     * - цена — за штуку: первоначальная, нет её — сумма, делённая на количество; похожая —
     *   расходится не больше чем на 1 % (копейки округления, разные столбцы списков).
     */
    fun isLike(a: Item, b: Item): Boolean = nameKey(a) == nameKey(b) && pricesClose(unitPrice(a), unitPrice(b))

    fun nameKey(i: Item): String = Search.words(i.name).map(Search::fold).sorted().joinToString(" ")
    fun unitPrice(i: Item): Double? = i.initial ?: i.sum?.let { s -> i.qty.toDoubleOrNull()?.takeIf { it > 1 }?.let { s / it } ?: s }
    private fun pricesClose(a: Double?, b: Double?): Boolean = when {
        a == null || b == null -> a == null && b == null
        else -> kotlin.math.abs(a - b) <= maxOf(kotlin.math.abs(a), kotlin.math.abs(b)) * 0.01 + 0.005
    }

    /** Такие же в описи (наименование и цена), ещё не снятые и не в этой папке — «добавить сюда?». */
    fun similar(obj: File, here: List<Item>, st: Status): List<Item> {
        if (here.isEmpty()) return emptyList()
        val names = here.map(::nameKey).toSet()
        val inHere = here.map { key(id(it)) }.toSet()
        return filesIn(obj).flatMap { parse(it).items }
            .filter { nameKey(it) in names && key(id(it)) !in inHere && !st.inspected(it) && here.any { h -> isLike(h, it) } }
    }

    // ---------- Отчёт ----------

    /** Отчёт по описи — рядом с ней: «Осмотр — <имя>.xlsx». */
    fun reportFile(f: File) = File(f.parentFile, "Осмотр — " + f.name.removePrefix(PREFIX))

    /**
     * Что осмотрено, что нет (по наличию снимков) — книгой Excel, по листу на список и итог
     * первым листом (автор: «автоматом выгружать в Excel»). Пересобирается сам: после съёмки, при
     * открытии описи, перед архивом.
     */
    private val reportLock = kotlinx.atomicfu.locks.SynchronizedObject()
    fun writeReport(obj: File): Unit = kotlinx.atomicfu.locks.synchronized(reportLock) { writeReportLocked(obj) }
    private object WHEN { fun format(ms: Long) = Platform.format(ms, "dd.MM.yyyy HH:mm") }
    private object DAY { fun format(ms: Long) = Platform.format(ms, "dd.MM.yyyy") }

    /** Есть ли у папки [d] голосовые заметки: «Да» / «Нет» (автор, 13.10.2026: «Просто указывай наличие. Да/нет»). */
    private fun voiceCount(d: File): String = if (Store.voiceNotes(d).isNotEmpty()) "Да" else "Нет"

    /**
     * Комментарии ко всей описи — своим листом (автор, 13.10.2026: «Комментарии на опись выносим
     * отдельной страницей»): комментарий, голосовые заметки (имя файла и когда записана) и
     * контакты корня описи. Ничего нет — листа нет.
     */
    private fun aboutObject(obj: File): List<Xlsx.Out> {
        val note = Store.note(obj)
        val voice = Store.voiceNotes(obj)
        val people = Store.contacts(obj)
        if (note == null && voice.isEmpty() && people.isEmpty()) return emptyList()
        val rows = mutableListOf<List<Any?>>(listOf("Что", "Содержание", "Когда"))
        note?.let { rows += listOf("Комментарий", it, null) }
        voice.forEach { v -> rows += listOf("Голосовая заметка", v.name, WHEN.format(v.lastModified())) }
        people.forEach { c -> rows += listOf("Контакт", c.text(), null) }
        return listOf(Xlsx.Out("Комментарии к описи", rows, listOf(20, 80, 17)))
    }

    private fun writeReportLocked(obj: File) {
        val st = status(obj)
        val log = Journal.entries(obj)
        val seen = Journal.firstSeen(log)
        // Отмечено осмотренным вручную — когда (по журналу; снятая отметка — забыта). Отметки импортом
        // в журнале без перечня — это осмотренное до приложения, не работа того дня.
        val marked = HashMap<String, Long>()
        for (e in log) when (e.what) {
            "Отмечено осмотренным", "Осмотрено ранее (вручную)" -> e.subject.split(", ").forEach { marked.getOrPut(key(it)) { e.t } }
            "Снята отметка «осмотрено»", "Снята отметка «ранее»" -> e.subject.split(", ").forEach { marked.remove(key(it)) }
        }
        for (f in filesIn(obj)) {
            val p = parse(f)
            val lists = p.lists
            // Чего в описи нет вовсе (стоимостей, приоритетов, места, списков), того нет и в отчёте:
            // пустой столбец «Первонач. стоимость» и «стоимость осмотренного 0,0» читались как
            // «стоит ноль», а блок приоритетов из одного «П—» — как поломка (автор, 13.10.2026:
            // «стоимости может не быть, приоритетов может не быть»).
            val hasInitial = p.items.any { it.initial != null }
            val hasSum = p.items.any { it.sum != null }
            val hasPriority = p.items.any { it.priority.isNotEmpty() }
            val hasStatus = p.items.any { it.status.isNotEmpty() }
            val hasPlace = p.items.any { it.place.isNotEmpty() }
            val hasQty = p.items.any { it.qty.isNotEmpty() }
            // Столбец: заголовок, ширина, есть ли он, значение строки.
            class Col(val title: String, val width: Int, val on: Boolean, val value: (Item) -> Any?)
            val notes = HashMap<File, String?>()  // комментарий папки — что с предметом не так (общая папка — один раз)
            // Голосовые заметки папок — столбцом наличия, «Да» / «Нет» (автор, 13.10.2026: «Если
            // возможно, встраиваем в Excel. Нет — добавляем столбцом наличие»; «Ссылки в Excel — не вариант»; «Просто указывай наличие. Да/нет»).
            val voices = HashMap<File, Any?>()
            fun voiceOf(d: File) = voices.getOrPut(d) { voiceCount(d) }
            val hasVoice = p.items.any { i -> st.folderOf(i)?.let(::voiceOf) == "Да" }
            // Время снимков — по журналу (когда лёг в приложение), без записи — по файлу.
            val shots = HashMap<File, List<Long>>()
            fun shotsOf(d: File) = shots.getOrPut(d) { Store.photoTree(d).map { f -> seen[Store.relative(f)] ?: f.lastModified() }.sorted() }
            val cols = listOf(
                Col("№", 7, true) { i -> i.number.toLongOrNull() ?: i.number },
                Col("Инв. номер", 18, true) { i -> i.inventory },
                Col("Наименование", 50, true) { i -> i.name },
                Col("Место", 16, hasPlace) { i -> i.place.ifEmpty { null } },
                Col("Кол-во", 8, hasQty) { i -> i.qty.toLongOrNull() ?: i.qty.ifEmpty { null } },
                Col("Первонач. стоимость", 16, hasInitial) { i -> i.initial },
                Col("Сумма", 16, hasSum) { i -> i.sum },
                Col("Приоритет", 10, hasPriority) { i -> i.priority.toLongOrNull() ?: i.priority.ifEmpty { null } },
                Col("Список", 18, hasStatus) { i -> i.status.ifEmpty { null } },
                Col("Осмотрено", 11, true) { i -> if (st.inspected(i)) "Да" else "Нет" },
                Col("Состояние", 17, true) { i -> when { st.broken(i) -> "Нерабочее"; st.repair(i) -> "Требуется ремонт"; else -> null } },
                Col("Снимков", 9, true) { i -> st.photosOf(i) },
                Col("Первый снимок", 17, true) { i -> st.folderOf(i)?.let(::shotsOf)?.firstOrNull()?.let { WHEN.format(it) } },
                Col("Последний снимок", 17, true) { i -> st.folderOf(i)?.let(::shotsOf)?.lastOrNull()?.let { WHEN.format(it) } },
                Col("Комментарий", 40, true) { i -> st.folderOf(i)?.let { notes.getOrPut(it) { Store.note(it) } } },
                Col("Голосовые", 12, hasVoice) { i -> st.folderOf(i)?.let(::voiceOf) ?: "Нет" },
                Col("Папка", 40, true) { i -> st.folderOf(i)?.let { Store.relative(it) } },
            ).filter { it.on }
            val head = cols.map { it.title }
            val sheets = mutableListOf<Xlsx.Out>()
            val total = listOfNotNull("Всего", "Осмотрено", "Не осмотрено", "Осмотрено, %",
                "Первонач. стоимость осмотренного".takeIf { hasInitial }, "Нерабочих", "Требуют ремонта")
            fun line(name: String, items: List<Item>): List<Any?> {
                val done = items.filter(st::inspected)
                return listOf(name, items.size, done.size, items.size - done.size,
                    if (items.isEmpty()) 0 else kotlin.math.round(done.size * 1000.0 / items.size) / 10.0) +
                    (if (hasInitial) listOf(done.sumOf { it.initial ?: 0.0 }) else emptyList()) + listOf(items.count(st::broken), items.count(st::repair))
            }
            val summary = mutableListOf<List<Any?>>(listOf("Лист") + total)
            for (l in lists) {
                val items = p.items.filter { it.list == l }
                summary += line(l, items)
                val rows = listOf<List<Any?>>(head) + items.map { i -> cols.map { it.value(i) } }
                sheets += Xlsx.Out(l, rows, cols.map { it.width }) { r ->
                    // Нерабочий — красным, требующий ремонта — жёлтым (важнее, чем «осмотрен»), осмотренный — зелёным.
                    when { r == 0 -> Xlsx.PLAIN; st.broken(items[r - 1]) -> Xlsx.RED; st.repair(items[r - 1]) -> Xlsx.AMBER
                        st.inspected(items[r - 1]) -> Xlsx.GREEN; else -> Xlsx.PLAIN }
                }
            }
            if (lists.size > 1) summary += line("Все листы", p.items)
            // По приоритетам — по всем спискам описи; приоритетов нет — и блока нет.
            if (hasPriority) {
                summary += listOf<Any?>()
                summary += listOf("Приоритет") + total
                p.items.groupBy { it.priority.ifEmpty { "—" } }.entries.sortedWith(compareBy({ it.key.toIntOrNull() ?: Int.MAX_VALUE }, { it.key }))
                    .forEach { (pr, items) -> summary += line("П$pr", items) }
            }
            // По дням — выезды: сколько предметов осмотрено впервые в тот день (по снимкам — день
            // первого снимка; отмеченные вручную — день отметки), сколько снимков сделано и сколько
            // осмотрено к концу дня. Отмеченное импортом — осмотрено до приложения: строкой сверху.
            val day = HashMap<String, Long>()  // предмет → когда впервые осмотрен
            val viaPhotos = HashSet<String>()     // … и по снимкам ли
            var imported = 0
            for (i in p.items.filter(st::inspected)) {
                val k = key(id(i))
                if (k in day) continue  // один инвентарник в нескольких строках — один предмет
                val shot = st.folderOf(i)?.let(::shotsOf)?.firstOrNull()
                when {
                    shot != null -> { day[k] = shot; viaPhotos += k }
                    marked[k] != null -> day[k] = marked.getValue(k)
                    else -> imported++
                }
            }
            fun timeOf(f: File) = seen[Store.relative(f)] ?: f.lastModified()
            val photosByDay = Store.photoTree(obj).groupBy({ DAY.format(timeOf(it)) }, ::timeOf)
            val itemsByDay = day.entries.groupBy({ DAY.format(it.value) }, { it.key })
            // Прочая работа дня — по журналу (автор, 13.10.2026: «Комментарии, голосовые, контакты и т. д.
            // по дням тоже в отчёты»): в скольких папках писали или правили комментарий и контакты,
            // сколько голосовых записано (из тех, что лежат сейчас), сколько предметов отмечено нерабочими.
            fun perDay(what: String, of: (Journal.Entry) -> List<String>) = log.filter { it.what == what && it.sha256.isNotEmpty() }
                .groupBy({ DAY.format(it.t) }, { it }).mapValues { (_, es) -> es.flatMap(of).toSet().size }
            val notesByDay = perDay("Комментарий") { listOf(it.file) }
            val peopleByDay = perDay("Контакты") { listOf(it.file) }
            val brokenByDay = perDay("Нерабочее") { e -> e.subject.split(", ").map(::key) }
            val repairByDay = perDay("Требуется ремонт") { e -> e.subject.split(", ").map(::key) }
            val voiceByDay = Store.voiceTree(obj).groupBy { DAY.format(timeOf(it)) }.mapValues { it.value.size }
            val firstOf = (itemsByDay.mapValues { (_, ks) -> ks.minOf { day.getValue(it) } }.entries + photosByDay.mapValues { it.value.min() }.entries)
                .groupBy({ it.key }, { it.value }).mapValues { it.value.min() }.toMutableMap()
            // Дни, когда только писали и записывали, — тоже строкой (время — по журналу).
            for (e in log) if (e.what in setOf("Комментарий", "Контакты", "Нерабочее", "Требуется ремонт", "Голосовая заметка")) DAY.format(e.t).let { d -> firstOf[d] = minOf(firstOf[d] ?: e.t, e.t) }
            var sum = imported
            val days = listOf<List<Any?>>(listOf("День", "Осмотрено впервые", "из них по снимкам", "отмечено без снимков", "Снимков сделано",
                "Всего осмотрено к концу дня", "Комментарии (папок)", "Голосовые", "Контакты (папок)", "Отмечено нерабочими",
                "Отмечено «требуется ремонт»")) +
                (if (imported > 0) listOf(listOf<Any?>("до приложения (импорт)", imported, 0, imported, 0, imported)) else emptyList()) +
                firstOf.entries.sortedBy { it.value }.map { (d, _) ->
                    val ks = itemsByDay[d].orEmpty()
                    val photo = ks.count { it in viaPhotos }
                    sum += ks.size
                    listOf(d, ks.size, photo, ks.size - photo, photosByDay[d]?.size ?: 0, sum,
                        notesByDay[d] ?: 0, voiceByDay[d] ?: 0, peopleByDay[d] ?: 0, brokenByDay[d] ?: 0, repairByDay[d] ?: 0)
                }
            // Нет в описи — папки со снимками, которых в описи нет (излишки, неизвестное).
            val keys = p.items.map { key(id(it)) }.toSet()
            // Папка внутри папки предмета («до ремонта») — часть предмета, её снимки уже у него.
            fun ofItem(d: File) = (members(d).ifEmpty { listOf(invOfName(d.name)) }).any { key(it) in keys }
            val extra = obj.walkTopDown().onEnter { it == obj || !it.name.startsWith(".") && !(it != obj && ofItem(it)) }
                .filter { it.isDirectory && it != obj }
                .filter { d -> !ofItem(d) && (d.listFiles()?.any(Store::isPhoto) == true) }
                .map { d -> listOf<Any?>(d.relativeTo(obj).path, d.listFiles()?.count(Store::isPhoto) ?: 0, Store.note(d), voiceCount(d)) }.toList()
            // Один инвентарник в нескольких строках описи — один предмет (одна папка, одна отметка);
            // сказать об этом, чтобы повтор в описи не прошёл незамеченным (13.10.2026).
            val repeats = p.items.filter { it.inventory.isNotEmpty() }.groupBy { key(it.inventory) }.filter { it.value.size > 1 }.values
            if (repeats.isNotEmpty()) {
                summary += listOf<Any?>()
                summary += listOf<Any?>("Инвентарник повторяется в описи — считается одним предметом:")
                repeats.forEach { g -> summary += listOf<Any?>(g.first().inventory, g.joinToString(", ") { "«${it.list}», стр. ${it.row}" }) }
            }
            // Столбцы указаны вручную — сказать, как именно прочитана опись (проверяющему по отчёту).
            choicesOf(obj).takeIf { it.sheets.isNotEmpty() }?.let { c ->
                summary += listOf<Any?>()
                summary += listOf<Any?>("Опись прочитана с ручным выбором столбцов: " + describe(c))
            }
            // Журнал: кто и что менял; подпись каждой строки проверена — до первой нарушенной.
            val check = Journal.verifyQuick(obj)
            summary += listOf<Any?>()
            summary += listOf<Any?>(check.text())
            val journal = if (log.isEmpty()) emptyList() else listOf(Xlsx.Out("Журнал",
                listOf(listOf<Any?>("№", "Когда", "Кто", "Что", "Подробно", "Файл", "Отпечаток", "Подпись")) + log.mapIndexed { i, e ->
                    listOf<Any?>(e.n, WHEN.format(e.t), e.who, e.what, e.subject.take(500), e.file.ifEmpty { null }, e.sha256.take(16).ifEmpty { null },
                        if (check.brokenAt == null || i + 1 < check.brokenAt) "верна" else "НЕ ВЕРНА")
                }, listOf(6, 17, 24, 22, 50, 40, 18, 10)) { r -> if (r > 0 && check.brokenAt != null && r >= check.brokenAt) Xlsx.RED else Xlsx.PLAIN }) +
                (if (check.problems.isEmpty()) emptyList() else listOf(Xlsx.Out("Сверка файлов",
                    listOf(listOf<Any?>("Файл", "Что не так")) + check.problems.map { listOf<Any?>(it.path, it.kind) }, listOf(70, 24)) { r -> if (r > 0) Xlsx.RED else Xlsx.PLAIN }))
            val book = listOf(Xlsx.Out("Итог", summary, listOf(24, 10, 12, 14, 14, 26, 12))) + sheets +
                listOf(Xlsx.Out("По дням", days, listOf(24, 18, 18, 20, 16, 24, 20, 12, 16, 20))) +
                (if (extra.isEmpty()) emptyList() else listOf(Xlsx.Out("Нет в описи", listOf(listOf<Any?>("Папка", "Снимков", "Комментарий", "Голосовые")) + extra, listOf(40, 10, 50, 16)))) +
                aboutObject(obj) + journal
            Xlsx.writeBook(reportFile(f), book)
            Store.scan(listOf(reportFile(f)))
        }
    }

    /** Пересобрать отчёт объекта, к которому относится [dir] (в фоне вызывающего). */
    fun refreshReport(dir: File) { objectOf(dir)?.let { runCatching { writeReport(it) } } }

    /**
     * Пересобрать отчёт объекта **в фоне**, не держа экран: на описи в 5–6 тыс. строк это секунды,
     * а человек ждал их после каждого «Готово» и «убрать из папки». Запросы во время сборки
     * сливаются в одну пересборку после неё. Кому отчёт нужен сейчас (выгрузка) — [writeReport].
     */
    fun refreshReportSoon(dir: File) {
        val obj = objectOf(dir) ?: return
        val k = obj.path
        // Флаг «пересобрать» и метка «отчёт устарел» на диске — под одним замком с её снятием:
        // свернули и закрыли раньше сборки — отчёт соберётся при следующем запуске.
        val first = kotlinx.atomicfu.locks.synchronized(reportsLock) {
            markStale(obj)
            if (k in reports) { reports[k] = true; false } else { reports[k] = false; true }
        }
        if (!first) return
        reportScope.launch {
            do {
                // Серия снимков — одна пересборка: ждём, пока правки утихнут.
                kotlinx.coroutines.delay(REPORT_QUIET_MS)
                kotlinx.atomicfu.locks.synchronized(reportsLock) { reports[k] = false }
                val ok = runCatching { writeReport(obj) }.onFailure { Telemetry.error("отчёт описи ${Telemetry.id(obj.name)}", it) }.isSuccess
                val again = kotlinx.atomicfu.locks.synchronized(reportsLock) {
                    if (reports[k] == true) true
                    else { if (ok) staleMark(obj).delete(); reports.remove(k); false }
                }
            } while (again)
        }
    }
    private fun markStale(obj: File) = runCatching {
        val m = staleMark(obj)
        if (!m.isFile) { m.parentFile?.mkdirs(); Store.writeDurably(m, obj.path.encodeToByteArray()) }
    }
    /** Отчёты, не собранные до закрытия приложения, — собрать (при запуске). */
    fun resumeReports() {
        File(Store.cacheDir, "stale-reports").listFiles()?.forEach { m ->
            val obj = runCatching { File(m.readText().trim()) }.getOrNull()
            if (obj != null && obj.isDirectory) refreshReportSoon(obj) else m.delete()
        }
    }
    /** То же, но сразу и здесь же (тесты; без ожидания фона). */
    fun resumeReportsNow() {
        File(Store.cacheDir, "stale-reports").listFiles()?.forEach { m ->
            val obj = runCatching { File(m.readText().trim()) }.getOrNull()
            if (obj != null && obj.isDirectory && runCatching { writeReport(obj) }.isSuccess) m.delete() else if (obj?.isDirectory != true) m.delete()
        }
    }
    private fun staleMark(obj: File) = File(Store.cacheDir, "stale-reports/" + obj.path.hashCode().toUInt() + ".txt")
    /** Сколько ждать тишины после правки, прежде чем собирать отчёт. */
    var REPORT_QUIET_MS = 1500L

    /** Объект → «просили ещё раз, пока собирали». */
    private val reports = HashMap<String, Boolean>()
    private val reportsLock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val reportScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
}
