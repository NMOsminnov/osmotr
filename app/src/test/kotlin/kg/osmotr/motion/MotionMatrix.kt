package kg.osmotr.motion

import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/*
 * Перенесено из приложения APCS (apcs-mobile, android/src/test/…/motion/MotionMatrix.kt) без
 * изменений логики: те же флаги и пороги, что у сайта и у приложения APCS.
 *
 * Матрица движения приложения — то же, что tools/interaction-matrix у сайта
 * (запись графа tooling-interaction-matrix), но на JVM: Robolectric и часы
 * Compose вручную. Действие × блок, покадрово, с повторами.
 *
 * По каждому блоку (Block.ALL) и каждому его экземпляру — ряд каждый кадр:
 * x, y, ширина, высота (dp, в координатах корня, со всеми слоями и обрезкой)
 * и видимая прозрачность (произведение alpha слоёв графики блока и предков).
 * Экземпляр — узел семантики: при перекрёстной смене (AnimatedContent) старый
 * и новый живут рядом, и это два ряда, а не один.
 *
 * Флаги — как у сайта:
 *   РЫВОК    — смена целиком за один кадр; для появления/исчезновения — блок
 *              возник или пропал при видимости ≥ 0,9 (без проявления);
 *   ПЕРЕЛЁТ  — ушло за конечное больше чем на 2 % пути (и больше 0,5 dp);
 *   ДРОЖЬ    — сменило направление на ходу (кроме перелёта с возвратом и
 *              «туда и обратно»);
 *   ЗАМЕНЁН  — блок исчез и на его месте тут же возник другой экземпляр,
 *              без общего кадра — перестройка вместо перехода;
 *   МЕЛЬКНУЛ — экземпляр появился и исчез за время замера;
 *   РАЗНОБОЙ — повторы одного действия дали разные флаги или сроки
 *              (в 1,8 раза и на 200 мс).
 */

/** Свойства, которые пишутся по каждому блоку. */
enum class Prop(val title: String, val eps: Float) {
    X("x", 0.5f), Y("y", 0.5f), W("ширина", 0.5f), H("высота", 0.5f), A("видимость", 0.02f),
}

class Sample(val nodeId: Int, val v: FloatArray)

/** Запись одного прогона действия: кадры × экземпляры блоков. */
class Take(val frameMs: Long) {
    /** По кадру: блок → (id узла → значения). */
    val frames = mutableListOf<Map<String, List<Sample>>>()
    var alphaKnown = true
    /** Тактильный отклик: (кадр, смысл) — в «Осмотре» журнала отклика нет, список пуст. */
    val haptics = mutableListOf<Pair<Int, String>>()
}

class Recorder(private val compose: ComposeContentTestRule, private val blocks: List<String>, private val density: Float) {

    fun sample(take: Take) {
        val frame = HashMap<String, List<Sample>>()
        val root = compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()?.boundsInRoot
        for (tag in blocks) {
            val nodes = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
            frame[tag] = nodes.map { n ->
                val b = n.boundsInRoot
                val alpha = visibleAlpha(n)
                if (alpha == null) take.alphaKnown = false
                // Целиком обрезан (рамкой перехода, прокруткой) — не виден, а границы такого
                // узла — нулевые в случайной точке: x, y и ширины нет, видимая высота и видимость 0.
                // Целиком за экраном — тоже не виден (ушёл за край нарочно): ряд прерывается.
                val off = root != null && (b.bottom <= root.top || b.top >= root.bottom || b.right <= root.left || b.left >= root.right)
                // За экраном — видимость неизвестна (NaN), а не 0: въезд из-за края — ход, не вспышка.
                if (off) Sample(n.id, floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN))
                else if (b.width < 0.5f || b.height < 0.5f) Sample(n.id, floatArrayOf(Float.NaN, Float.NaN, Float.NaN, 0f, 0f))
                else Sample(n.id, floatArrayOf(b.left / density, b.top / density, b.width / density, b.height / density,
                    alpha ?: Float.NaN))
            }
        }
        take.frames += frame
    }

    /**
     * Видимость узла — произведение alpha слоёв графики узла и всех предков. Слой —
     * `ModifierInfo.extra` (внутренний OwnedLayer): у слоя на GraphicsLayer —
     * публичный [androidx.compose.ui.graphics.layer.GraphicsLayer.alpha], у слоя на
     * View — View.alpha. Не добрались — null (в отчёте «неизвестна»).
     */
    private fun visibleAlpha(node: SemanticsNode): Float? {
        var a = 1f
        var info: LayoutInfo? = node.layoutInfo
        while (info != null) {
            for (m in info.getModifierInfo()) {
                val layer = m.extra ?: continue
                a *= layerAlpha(layer) ?: return null
            }
            // Слой, которым родитель разместил узел (placeWithLayer — так делают
            // AnimatedVisibility/AnimatedContent), висит на внутреннем координаторе узла:
            // в getModifierInfo() его нет.
            innerLayer(info)?.let { a *= layerAlpha(it) ?: return null }
            info = info.parentInfo
        }
        return a
    }

    private fun innerLayer(info: LayoutInfo): Any? = runCatching {
        val inner = info.javaClass.methods.first {
            (it.name == "getInnerCoordinator" || it.name.startsWith("getInnerCoordinator$")) && it.parameterCount == 0
        }
            .invoke(info) ?: return null
        val get = generateSequence<Class<*>>(inner.javaClass) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
            .first { (it.name == "getLayer" || it.name.startsWith("getLayer$")) && it.parameterCount == 0 }
        get.isAccessible = true
        get.invoke(inner)
    }.getOrNull()

    private fun layerAlpha(layer: Any): Float? {
        if (layer is android.view.View) return layer.alpha
        var cls: Class<*>? = layer.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (androidx.compose.ui.graphics.layer.GraphicsLayer::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    return (f.get(layer) as? androidx.compose.ui.graphics.layer.GraphicsLayer)?.alpha
                }
            }
            cls = cls.superclass
        }
        return null
    }
}

/** Ряд одного экземпляра блока в одном прогоне. */
class Track(val block: String, val nodeId: Int, val first: Int, val last: Int, val values: List<FloatArray>)

class Finding(val block: String, val instance: Int, val prop: Prop?, val flag: String, val detail: String)

class PropStat(val durationMs: Long, val path: Float, val peakSpeed: Float)

class TakeResult(
    val findings: List<Finding>, val stats: Map<String, PropStat>, val frames: Int, val alphaKnown: Boolean,
    /** Ряды по кадрам для того, что помечено флагом: «блок·n свойство» → значения (NaN — нет). */
    val series: Map<String, List<Float>> = emptyMap(),
    /** Отклик по порядку: «SELECT (кадр 1)». */
    val haptics: List<String> = emptyList(),
)

object Analysis {

    fun tracks(take: Take): Map<String, List<Track>> {
        val out = LinkedHashMap<String, MutableList<Track>>()
        val tags = take.frames.flatMap { it.keys }.distinct()
        for (tag in tags) {
            val ids = LinkedHashSet<Int>()
            take.frames.forEach { f -> f[tag].orEmpty().forEach { ids += it.nodeId } }
            for (id in ids) {
                val idx = take.frames.indices.filter { i -> take.frames[i][tag].orEmpty().any { it.nodeId == id } }
                val values = idx.map { i -> take.frames[i][tag]!!.first { it.nodeId == id }.v }
                out.getOrPut(tag) { mutableListOf() } += Track(tag, id, idx.first(), idx.last(), values)
            }
        }
        return out
    }

    fun analyze(take: Take, expected: Set<String>, expectedHaptics: List<String>? = null): TakeResult {
        val findings = mutableListOf<Finding>()
        // Отклик: ровно ожидаемая последовательность — ни пропуска, ни двойного, ни лишнего.
        val got = take.haptics.map { it.second }
        if (expectedHaptics != null && got != expectedHaptics)
            findings += Finding("отклик", 0, null, "ОТКЛИК", "ждали ${expectedHaptics.ifEmpty { listOf("—") }}, было ${got.ifEmpty { listOf("—") }}")
        val stats = LinkedHashMap<String, PropStat>()
        val n = take.frames.size
        for ((tag, list) in tracks(take)) {
            list.forEachIndexed { k, t ->
                val inst = k + 1
                val appeared = t.first > 0
                val vanished = t.last < n - 1
                if (appeared && vanished) findings += Finding(tag, inst, null, "МЕЛЬКНУЛ", "кадры ${t.first}–${t.last}")
                val alphaFirst = t.values.first()[Prop.A.ordinal]
                val alphaLast = t.values.last()[Prop.A.ordinal]
                if (appeared && !alphaFirst.isNaN() && alphaFirst >= 0.9f)
                    findings += Finding(tag, inst, Prop.A, "РЫВОК", "возник сразу видимым (${fmt(alphaFirst)})")
                if (vanished && !alphaLast.isNaN() && alphaLast >= 0.9f)
                    findings += Finding(tag, inst, Prop.A, "РЫВОК", "пропал при видимости ${fmt(alphaLast)}")
                // Замена: другой экземпляр того же блока возник ровно там, где этот пропал, без общего кадра.
                // «На месте»: новый возник там, где был старый (±4 dp по x и y), — как у сайта.
                if (vanished) list.filter { it !== t && it.first == t.last + 1 && samePlace(t, it) }.forEach { other ->
                    findings += Finding(tag, inst, null, "ЗАМЕНЁН", "узел ${t.nodeId} → ${other.nodeId} на том же месте, без перехода")
                }
                // Ряд, прерванный невидимостью (ушёл за край и вернулся), — отдельные ходы: иначе
                // склейка «ушёл вверх — пришёл снизу» читалась как перелёт с дрожью.
                for (p in Prop.entries) {
                    val runs = mutableListOf(mutableListOf<Float>())
                    t.values.forEach { row -> val x = row[p.ordinal]; if (x.isNaN()) { if (runs.last().isNotEmpty()) runs += mutableListOf<Float>() } else runs.last() += x }
                    runs.filter { it.size >= 2 }.forEach { v -> analyzeSeries(tag, inst, p, v, take.frameMs, findings, stats) }
                }
            }
        }
        val kept = findings.filter { f -> "${f.block}·${f.instance} ${f.flag}" !in expected && "${f.block} ${f.flag}" !in expected }
        val all = tracks(take)
        val series = LinkedHashMap<String, List<Float>>()
        for (f in kept) {
            val t = all[f.block]?.getOrNull(f.instance - 1) ?: continue
            val props = f.prop?.let { listOf(it) } ?: listOf(Prop.Y, Prop.H, Prop.A)
            for (p in props) {
                val row = MutableList(n) { Float.NaN }
                t.values.forEachIndexed { i, v -> row[t.first + i] = v[p.ordinal] }
                series["${f.block}·${f.instance} ${p.title}"] = row
            }
        }
        return TakeResult(kept, stats, n, take.alphaKnown, series, take.haptics.map { "${it.second} (кадр ${it.first})" })
    }

    private fun samePlace(a: Track, b: Track): Boolean {
        val last = a.values.lastOrNull { !it[Prop.X.ordinal].isNaN() } ?: return false
        val first = b.values.firstOrNull { !it[Prop.X.ordinal].isNaN() } ?: return false
        return abs(last[Prop.X.ordinal] - first[Prop.X.ordinal]) <= 4f && abs(last[Prop.Y.ordinal] - first[Prop.Y.ordinal]) <= 4f
    }

    private fun analyzeSeries(
        tag: String, inst: Int, p: Prop, v: List<Float>, frameMs: Long,
        findings: MutableList<Finding>, stats: MutableMap<String, PropStat>,
    ) {
        if (v.size < 2) return
        val start = v.first(); val end = v.last()
        val path = end - start
        val deviation = v.maxOf { abs(it - start) }
        if (deviation < p.eps) return
        val d = v.zipWithNext { a, b -> b - a }
        val moving = d.indices.filter { abs(d[it]) > p.eps / 10 }
        if (moving.isEmpty()) return
        val duration = (moving.last() - moving.first() + 1) * frameMs
        val peak = d.maxOf { abs(it) } / frameMs * 1000f
        stats["$tag·$inst ${p.title}"] = PropStat(duration, path, peak)
        // Рывок: всё изменение — в один кадр.
        val bigSteps = moving.filter { abs(d[it]) > p.eps }
        // Рывок — смена за один кадр у блока, который стоит целиком хотя бы два кадра до и два
        // после неё. Видимая высота на рывок не проверяется: её открывает и закрывает край
        // едущей рамки (карточка растёт, уезжает), и за кадр она меняется на скорость края;
        // так же обрезанное движение у самого края (блок шагнул и ушёл под рамку) — не рывок.
        val jump = if (p == Prop.A) 0.3f else 4f
        val k = moving.singleOrNull()
        val settledAround = k != null && k >= 1 && k + 2 < v.size
        if (p != Prop.H && settledAround && abs(path) >= jump)
            findings += Finding(tag, inst, p, "РЫВОК", "${fmt(start)} → ${fmt(end)} за кадр")
        // Перелёт: за конечное по ходу движения. Малый (≤ 2 %) — это довод пружины с
        // отскоком (smooth у сайта — 0,08): он не флаг, и его возврат — не дрожь.
        var overshoot = false
        var settle = false
        if (abs(path) >= p.eps) {
            val dir = sign(path)
            val beyond = v.maxOf { dir * (it - end) }
            if (beyond > max(0.02f * abs(path), p.eps)) {
                overshoot = true
                findings += Finding(tag, inst, p, "ПЕРЕЛЁТ", "на ${fmt(beyond)} за ${fmt(end)} (${(beyond / abs(path) * 100).toInt()} % пути)")
            } else if (beyond > p.eps / 20) settle = true
        }
        // Дрожь: смены направления на ходу.
        // Отрезки одного направления, и только заметные (больше порога свойства): покачивание
        // меньше пикселя глазом не видно и дрожью не считается.
        val segments = mutableListOf<Float>()
        for (x in d.filter { abs(it) > p.eps / 10 }) {
            if (segments.isNotEmpty() && sign(segments.last()) == sign(x)) segments[segments.size - 1] += x else segments += x
        }
        val signs = segments.filter { abs(it) >= p.eps }.map { sign(it) }
        val reversals = signs.zipWithNext().count { (a, b) -> a != b }
        val thereAndBack = abs(path) < 0.25f * deviation
        // «Туда и обратно» — одна смена; довод пружины в конце — ещё одна: вместе — две (лист
        // чуть приподняли и отпустили — подпись вернулась и качнулась на 0,3 dp).
        val allowed = (if (overshoot || settle) 1 else 0) + (if (thereAndBack) 1 else 0)
        if (reversals > allowed && bigSteps.size > 1)
            findings += Finding(tag, inst, p, "ДРОЖЬ", "смен направления: $reversals")
    }

    /** Разнобой между повторами одного действия. */
    fun disagreement(results: List<TakeResult>): List<String> {
        val out = mutableListOf<String>()
        val flagSets = results.map { r -> r.findings.map { "${it.block}·${it.instance} ${it.prop?.title ?: ""} ${it.flag}".trim() }.toSortedSet() }
        if (flagSets.distinct().size > 1) out += "флаги разные: " + flagSets.joinToString(" | ") { it.joinToString(", ").ifEmpty { "—" } }
        val keys = results.flatMap { it.stats.keys }.toSortedSet()
        for (k in keys) {
            val ds = results.mapNotNull { it.stats[k]?.durationMs }
            if (ds.size < 2) continue
            val lo = ds.min(); val hi = ds.max()
            if (hi >= 1.8 * max(lo, 1) && hi - lo >= 200) out += "$k: сроки ${ds.joinToString("/")} мс"
        }
        return out
    }

    fun fmt(f: Float) = String.format(Locale.ROOT, "%.2f", f)
}

/** Провал прогона, если есть незадуманные флаги или разнобой повторов: флаг не прячется в отчёте. */
fun assertClean(results: List<ActionResult>) {
    val bad = results.filter { r -> r.takes.any { it.findings.isNotEmpty() } || r.disagreement.isNotEmpty() }
    assert(bad.isEmpty()) {
        bad.joinToString("\n") { r -> "${r.name}: " + r.takes.first().findings.joinToString("; ") { f ->
            "${f.block}·${f.instance} ${f.prop?.title ?: ""} ${f.flag} ${f.detail}" } + r.disagreement.joinToString("; ") }
    }
}

/** Итог действия: повторы и разнобой. */
class ActionResult(val name: String, val takes: List<TakeResult>, val disagreement: List<String>)

object Report {
    fun write(dir: File, results: List<ActionResult>, frameMs: Long, name: String = "report", title: String = "приложение") {
        dir.mkdirs()
        val md = StringBuilder()
        md.append("# Матрица движения — $title\n\n")
        md.append("Кадр ${frameMs} мс (часы Compose вручную), повторов на действие: ${results.firstOrNull()?.takes?.size ?: 0}. ")
        md.append("Флаги — как у tools/interaction-matrix сайта; ожидаемое снято сценарием.\n\n")
        val total = results.sumOf { r -> r.takes.sumOf { it.findings.size } }
        md.append("Всего флагов по всем повторам: **$total**, разнобой — в ${results.count { it.disagreement.isNotEmpty() }} действиях.\n\n")
        for (r in results) {
            md.append("## ${r.name}\n\n")
            val first = r.takes.first()
            if (!first.alphaKnown) md.append("_Видимость неизвестна (слой не прочитан) — флаги появления не проверены._\n\n")
            if (first.haptics.isNotEmpty()) md.append("Отклик: ${first.haptics.joinToString(", ")}.\n\n")
            if (first.findings.isEmpty()) md.append("Флагов нет.\n\n")
            else {
                md.append("| Блок | Свойство | Флаг | Подробно |\n|---|---|---|---|\n")
                first.findings.forEach { f -> md.append("| ${f.block}·${f.instance} | ${f.prop?.title ?: "—"} | **${f.flag}** | ${f.detail} |\n") }
                md.append('\n')
            }
            if (first.stats.isNotEmpty()) {
                md.append("| Что двигалось | Длительность, мс | Путь | Пик скорости, ед/с |\n|---|---|---|---|\n")
                first.stats.forEach { (k, s) -> md.append("| $k | ${s.durationMs} | ${Analysis.fmt(s.path)} | ${Analysis.fmt(s.peakSpeed)} |\n") }
                md.append('\n')
            }
            if (first.series.isNotEmpty()) {
                md.append("<details><summary>Ряды помеченного по кадрам (${frameMs} мс)</summary>\n\n```\n")
                first.series.forEach { (k, row) ->
                    md.append(k.padEnd(28)).append(row.joinToString(" ") { if (it.isNaN()) "·" else String.format(Locale.ROOT, "%.1f", it) }).append('\n')
                }
                md.append("```\n</details>\n\n")
            }
            if (r.disagreement.isNotEmpty()) md.append("**РАЗНОБОЙ:** ${r.disagreement.joinToString("; ")}\n\n")
        }
        File(dir, "$name.md").writeText(md.toString())
        val json = StringBuilder("{\"frameMs\":$frameMs,\"actions\":[")
        results.forEachIndexed { i, r ->
            if (i > 0) json.append(',')
            json.append("{\"name\":${q(r.name)},\"disagreement\":[${r.disagreement.joinToString(",") { q(it) }}],\"takes\":[")
            r.takes.forEachIndexed { j, t ->
                if (j > 0) json.append(',')
                json.append("{\"frames\":${t.frames},\"alphaKnown\":${t.alphaKnown},\"findings\":[")
                json.append(t.findings.joinToString(",") { f ->
                    "{\"block\":${q(f.block)},\"instance\":${f.instance},\"prop\":${f.prop?.let { q(it.title) } ?: "null"},\"flag\":${q(f.flag)},\"detail\":${q(f.detail)}}"
                })
                json.append("],\"series\":{")
                json.append(t.series.entries.joinToString(",") { (k, row) -> "${q(k)}:[${row.joinToString(",") { if (it.isNaN()) "null" else it.toString() }}]" })
                json.append("},\"stats\":{")
                json.append(t.stats.entries.joinToString(",") { (k, s) -> "${q(k)}:{\"ms\":${s.durationMs},\"path\":${s.path},\"peak\":${s.peakSpeed}}" })
                json.append("}}")
            }
            json.append("]}")
        }
        json.append("]}")
        File(dir, "$name.json").writeText(json.toString())
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
