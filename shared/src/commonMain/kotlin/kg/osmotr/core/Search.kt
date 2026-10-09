package kg.osmotr.core

/** Поиск — общий для Android и iPhone: номер без разделителей, слова с опечатками. */
object Search {
    fun norm(s: String) = s.lowercase().replace('ё', 'е')
    /**
     * Только буквы и цифры: «ИН-001 23» → «ин00123». Латинские буквы, похожие на русские,
     * приводятся к русским: инвентарник «M 205» или «p5732» набран латиницей, а ищут по-русски.
     */
    fun compact(s: String) = norm(s).filter { it.isLetterOrDigit() }.map { LOOKALIKE[it] ?: it }.joinToString("")

    /**
     * Слово для сравнения в поиске: латиница — кириллицей (похожие по виду и прочие по звучанию):
     * в описи «Ноутбук ASER», набирают «асер»; «Dell» — «делл». Инвентарники так не сводятся —
     * у них только похожие по виду ([compact]).
     */
    fun fold(w: String) = w.map { LOOKALIKE[it] ?: SOUND[it] ?: it }.joinToString("")
    private val SOUND = mapOf('s' to 'с', 'r' to 'р', 'd' to 'д', 'f' to 'ф', 'g' to 'г', 'i' to 'и', 'l' to 'л',
        'n' to 'н', 'u' to 'у', 'v' to 'в', 'z' to 'з')

    private val LOOKALIKE = mapOf('a' to 'а', 'b' to 'в', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'k' to 'к', 'm' to 'м',
        'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у')
    /** Слова — буквы и цифры подряд; без регулярного выражения: на 5,7 тыс. строк оно стоило секунды на телефоне. */
    fun words(s: String): List<String> {
        val n = norm(s); val out = ArrayList<String>(); var start = -1
        for (i in n.indices) {
            if (n[i].isLetterOrDigit()) { if (start < 0) start = i }
            else if (start >= 0) { out += n.substring(start, i); start = -1 }
        }
        if (start >= 0) out += n.substring(start)
        return out
    }
    private fun numbers(s: String) = Regex("""\d+""").findAll(s).map { it.value.trimStart('0').ifEmpty { "0" } }.toSet()

    /** Сколько опечаток прощать слову запроса: короткое — точно, от 4 букв — одну, от 7 — две. */
    fun allowed(len: Int) = when { len < 4 -> 0; len < 7 -> 1; else -> 2 }

    /**
     * Расстояние между строками (вставка, удаление, замена, перестановка соседних) — не больше
     * [max]; больше — [max] + 1. Для опечаток: «наименавание», «инвентраный», «сканир».
     */
    fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev2 = IntArray(b.length + 1); var prev = IntArray(b.length + 1) { it }; var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i; var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v; if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Слово [w] начинается со слова запроса [t] с учётом опечаток (сравнивается начало той же длины ±1). */
    fun fuzzyPrefix(w: String, t: String): Boolean {
        if (w.startsWith(t)) return true
        val k = allowed(t.length); if (k == 0) return false
        // Номер с бирки набирают точно: «похожие» числа — мусор, а не опечатка.
        if (t.all { it.isDigit() }) return false
        if (w.length < t.length - k) return false
        // Быстрый отсев без расстояния: букв запроса, которых нет в начале слова, больше, чем
        // прощаем опечаток, — не оно. На 5,7 тыс. строк расстояние считалось для каждого слова
        // каждой строки: 0,7 с на запрос на телефоне.
        val head = minOf(w.length, t.length + k)
        var miss = 0
        for (ch in t) { val at = w.indexOf(ch); if (at < 0 || at >= head) { if (++miss > k) return false } }
        return (t.length - 1..t.length + 1).any { n -> n in 1..w.length && distance(w.take(n), t, k) <= k }
    }


    /**
     * Насколько предмет подходит под запрос; 0 — нет. Инвентарник и номер — без разделителей:
     * целиком, начало, вхождение. Иначе — по ключевым словам наименования и места в любом
     * порядке, каждое слово запроса — начало какого-то слова, с учётом опечаток; все слова
     * запроса должны найтись. Точные совпадения выше опечаток.
     */
    fun scoreItem(i: Inventory.Item, query: String): Int = matcher(query)(i)

    /**
     * Запрос, приготовленный один раз на весь поиск: чистка и разбор на слова (регулярным
     * выражением) делались для каждой строки описи заново — на 5,7 тыс. строк 0,7 с на телефоне.
     */
    fun matcher(query: String): (Inventory.Item) -> Int {
        val q = query.trim(); val c = compact(q)
        if (q.isEmpty() || c.isEmpty()) return { 0 }
        val tokens = words(q).map(::fold).map { it to compact(it) }
        return fun(i: Inventory.Item): Int {
            val (inv, ws) = prepared(i)
            if (inv.isNotEmpty()) when {
                inv == c -> return 10000
                // С бирки набирают хвост («2545» из «013/2545») или начало — выше номера строки.
                c.length >= 3 && (inv.endsWith(c) || inv.startsWith(c)) -> return 9000
            }
            if (i.number == q) return 8500  // № по порядку — из распечатки описи
            if (inv.isNotEmpty() && c.length >= 3 && inv.contains(c)) return 8000
            if (tokens.isEmpty()) return 0
            var total = 0
            for ((t, tc) in tokens) {
                total += when {
                    ws.any { it == t } -> 30
                    ws.any { it.startsWith(t) } -> 20
                    tc.length >= 2 && inv.contains(tc) -> 20
                    ws.any { fuzzyPrefix(it, t) } -> 8
                    else -> return 0
                }
            }
            return total
        }
    }

    /**
     * Предмет, приготовленный к поиску: инвентарник без разделителей и слова названия, места,
     * инвентарника — один раз на предмет, а не на каждую букву запроса (5,7 тыс. строк резались
     * на слова заново при каждом нажатии — секунды на телефоне).
     */
    private fun prepared(i: Inventory.Item): Pair<String, List<String>> =
        kotlinx.atomicfu.locks.synchronized(preparedLock) { preparedMemo[i] }
            ?: (compact(i.inventory) to (words(i.name + " " + i.place) + words(i.inventory)).map(::fold)).also { p ->
                kotlinx.atomicfu.locks.synchronized(preparedLock) { if (preparedMemo.size > 200_000) preparedMemo.clear(); preparedMemo[i] = p }
            }
    private val preparedMemo = HashMap<Inventory.Item, Pair<String, List<String>>>()
    /** Приготовить заранее (при открытии описи, в фоне): первая буква запроса не ждёт 5,7 тыс. строк. */
    fun prepare(items: List<Inventory.Item>) { items.forEach { prepared(it) } }
    private val preparedLock = kotlinx.atomicfu.locks.SynchronizedObject()

    data class Entry(val dir: File, val name: String, val where: String, val note: String?, val photos: Int, val depth: Int)
    data class Hit(val entry: Entry, val score: Int, val noteLine: String?)

    /** Все папки с комментариями — один обход; дальше поиск в памяти на каждую букву. */
    fun index(): List<Entry> = Store.root.walkTopDown()
        .onEnter { it == Store.root || !it.name.startsWith(".") }
        .filter { it.isDirectory && it != Store.root }
        .map { d ->
            // Комментарий и контакты — одним текстом: папка находится и по имени, телефону человека.
            Entry(d, d.name, Store.title(d.parentFile ?: Store.root),
                listOfNotNull(Store.note(d), Store.contacts(d).joinToString("\n") { it.text() }.ifEmpty { null }).joinToString("\n").ifEmpty { null },
                d.listFiles()?.count(Store::isPhoto) ?: 0, Store.relative(d).count { it == '/' })
        }.toList()

    /** Насколько [text] подходит под одно слово запроса [t]; null — не подходит. */
    private fun scoreIn(text: String, t: String, base: Int): Int? {
        val n = norm(text); val c = compact(text); val tc = compact(t)
        if (tc.isEmpty()) return null
        return when {
            n == t || c == tc -> base + 100
            tc.all { it.isDigit() } && tc.trimStart('0').ifEmpty { "0" } in numbers(text) -> base + 90
            n.startsWith(t) || c.startsWith(tc) -> base + 80
            words(text).any { it.startsWith(t) } -> base + 70
            c.contains(tc) -> base + 60
            words(text).any { fuzzyPrefix(it, t) } -> base + 40  // с опечаткой
            else -> null
        }
    }

    fun find(index: List<Entry>, query: String, here: File): List<Hit> {
        val tokens = words(query).ifEmpty { listOf(compact(query)) }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val hits = index.mapNotNull { e ->
            var total = 0
            var noteLine: String? = null
            for (t in tokens) {
                val byName = scoreIn(e.name, t, 1000)
                val line = e.note?.lineSequence()?.firstOrNull { scoreIn(it, t, 0) != null }
                val byNote = line?.let { scoreIn(it, t, 0) }
                val best = byName ?: byNote ?: return@mapNotNull null
                if (byName == null) noteLine = noteLine ?: line
                total += best
            }
            // Всё запросом целиком в названии — выше, чем по словам вразброс.
            if (tokens.size > 1 && compact(e.name).contains(compact(query))) total += 50
            Hit(e, total, noteLine)
        }
        val inside = here.absolutePath + File.separator
        return hits.sortedWith(
            compareByDescending<Hit> { it.score }
                .thenByDescending { here != Store.root && it.entry.dir.absolutePath.startsWith(inside) }
                .thenBy { it.entry.depth }
                .thenComparator { a, b -> Store.NATURAL.compare(a.entry.name, b.entry.name) },
        )
    }

    // ---------- Предметы описей ----------

    data class ItemEntry(val obj: File, val item: Inventory.Item)

    /** Все предметы всех описей под «Осмотрами» (разбор — из кэша). */
    fun items(): List<ItemEntry> = Store.root.walkTopDown().onEnter { it == Store.root || !it.name.startsWith(".") }
        .filter { it.isDirectory }.flatMap { d -> Inventory.filesIn(d).asSequence().flatMap { f -> Inventory.parse(f).items.asSequence().map { ItemEntry(d, it) } } }
        .toList()

    /** [alive] — не бросили ли поиск (набрали следующую букву): тогда прерваться, не досчитывая. */
    fun findItems(all: List<ItemEntry>, query: String, limit: Int = 50, alive: () -> Boolean = { true }): List<ItemEntry> =
        matcher(query).let { match -> all.mapNotNull { e -> if (!alive()) return emptyList(); match(e.item).takeIf { it > 0 }?.let { e to it } } }
            .sortedWith(compareByDescending<Pair<ItemEntry, Int>> { it.second }.thenBy { it.first.item.priority.toIntOrNull() ?: 99 })
            .take(limit).map { it.first }
}
