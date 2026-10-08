package kg.osmotr.core

/** Поиск — общий для Android и iPhone: номер без разделителей, слова с опечатками. */
object Search {
    fun norm(s: String) = s.lowercase().replace('ё', 'е')
    /**
     * Только буквы и цифры: «ИН-001 23» → «ин00123». Латинские буквы, похожие на русские,
     * приводятся к русским: инвентарник «M 205» или «p5732» набран латиницей, а ищут по-русски.
     */
    fun compact(s: String) = norm(s).filter { it.isLetterOrDigit() }.map { LOOKALIKE[it] ?: it }.joinToString("")

    private val LOOKALIKE = mapOf('a' to 'а', 'b' to 'в', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'k' to 'к', 'm' to 'м',
        'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у')
    fun words(s: String) = norm(s).split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
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
        return (t.length - 1..t.length + 1).any { n -> n in 1..w.length && distance(w.take(n), t, k) <= k }
    }


    /**
     * Насколько предмет подходит под запрос; 0 — нет. Инвентарник и номер — без разделителей:
     * целиком, начало, вхождение. Иначе — по ключевым словам наименования и места в любом
     * порядке, каждое слово запроса — начало какого-то слова, с учётом опечаток; все слова
     * запроса должны найтись. Точные совпадения выше опечаток.
     */
    fun scoreItem(i: Inventory.Item, query: String): Int {
        val q = query.trim(); if (q.isEmpty()) return 0
        val c = compact(q); if (c.isEmpty()) return 0
        val inv = compact(i.inventory)
        if (inv.isNotEmpty()) when {
            inv == c || i.number == q -> return 10000
            inv.startsWith(c) -> return 9000
            c.length >= 3 && inv.contains(c) -> return 8000
        }
        val tokens = words(q); if (tokens.isEmpty()) return 0
        val ws = words(i.name + " " + i.place) + words(i.inventory)
        var total = 0
        for (t in tokens) {
            total += when {
                ws.any { it == t } -> 30
                ws.any { it.startsWith(t) } -> 20
                compact(i.inventory).contains(compact(t)) && compact(t).length >= 2 -> 20
                ws.any { fuzzyPrefix(it, t) } -> 8
                else -> return 0
            }
        }
        return total
    }
}
