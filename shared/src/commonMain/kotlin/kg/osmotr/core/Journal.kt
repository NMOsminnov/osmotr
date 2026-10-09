package kg.osmotr.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable

/**
 * Журнал описи — кто и что менял, так, чтобы подменить безнаказанно было нельзя (автор,
 * 08.10.2026: «кто и что менял — внутри файла, чтобы не удалось подменить данные безнаказанно, а
 * то мало ли кто сфилонит»), в том числе мимо приложения — по USB, файловым менеджером (автор,
 * 09.10.2026: «продумал вариант с тем, что кто-то вне приложения пойдёт файл менять?»).
 *
 * «Журнал.jsonl» в папке описи — только дописывается, строка на действие: когда, кто (модель
 * телефона — имя не спрашиваем), что, подробности, **файл и отпечаток SHA-256 его содержимого после
 * действия** (снимок, голосовая заметка, «Комментарий.txt», «Нерабочие.txt», «Инвентарники.txt»,
 * «Контакты.xlsx», опись; пустой отпечаток — файл ушёл). Строки сцеплены (в каждой — отпечаток
 * предыдущей), свой отпечаток подписан ключом телефона (ECDSA P-256; закрытый ключ телефон не
 * покидает). Открытый ключ — в каждой строке: опись могут вести несколько телефонов.
 *
 * Проверка ([verify]): цепочка и подписи; конец журнала — с запомненным в служебной папке
 * приложения (обрезали или удалили — видно); **сверка файлов**: журнал проигрывается от начала, и
 * что должно лежать в описи сейчас, сравнивается с диском — подкинутое, изменённое, пропавшее;
 * время, идущее назад, — предупреждение. На компьютере — `tools/verify_journal.py` (то же, и
 * сравнение с журналом прошлой выгрузки).
 */
object Journal {
    const val FILE = "Журнал.jsonl"
    private const val SEP = '\u001F'

    // Что записывается — проигрывание при проверке опирается на эти слова.
    const val BEFORE = "До журнала"
    const val FOLDER_RENAMED = "Папка переименована"
    const val FOLDER_MOVED = "Папка перенесена"
    const val FOLDER_DELETED = "Папка удалена"
    const val PHOTO_MOVED = "Снимок перенесён"

    @Serializable
    data class Entry(
        val n: Int, val t: Long, val who: String, val what: String, val subject: String = "", val file: String = "",
        val sha256: String = "", val key: String = "", val prev: String = "", val hash: String = "", val sig: String = "",
    ) {
        /** Что подписано: поля по порядку через разделитель — так же считается в Python. */
        fun body() = listOf(n.toString(), t.toString(), who, what, subject, file, sha256, key, prev).joinToString(SEP.toString())
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()
    /** Последняя запись по описи — без чтения файла на каждое действие. */
    private val heads = HashMap<String, Pair<Int, String>>()
    /** Сколько строк уже проверено в этом сеансе (журнал только дописывается). */
    private val checked = HashMap<String, Pair<Int, String>>()

    private fun appDir() = Store.cacheDir.parentFile!!

    /**
     * «Кто» — модель телефона: имя не спрашиваем (автор, 09.10.2026: вариант без вопроса — «не
     * грузить пользователя»); телефоны и так различимы по ключу подписи. who.txt в служебной папке
     * — если имя всё же нужно (проверки).
     */
    fun who(): String = runCatching { File(appDir(), "who.txt").readText().trim() }.getOrNull()?.ifEmpty { null } ?: Platform.deviceName()
    fun setWho(name: String) { Store.writeDurably(File(appDir().also { it.mkdirs() }, "who.txt"), name.trim().encodeToByteArray()) }

    /**
     * Записать действие в журнал описи, к которой относится [dir] (не опись — ничего). [file] —
     * файл, который действие создало или поменяло: в строку — его путь и отпечаток содержимого;
     * [gone] — файл (или папка) ушёл. Пишется сразу и надёжно (fsync).
     */
    fun add(dir: File, what: String, subject: String = "", file: File? = null, gone: Boolean = false) {
        val obj = Inventory.objectOf(dir) ?: return
        runCatching {
            val path = file?.let { Store.relative(it) }.orEmpty()
            val sha = if (file == null || gone || !file.isFile) "" else hashOf(file, fresh = true)
            synchronized(lock) {
                val f = File(obj, FILE)
                if (!f.isFile) baseline(obj, f, except = path)
                append(obj, f, what, subject, path, sha)
            }
        }.onFailure { Platform.log("журнал: не записали «$what» — ${it.message}") }
    }

    /** Первая строка журнала описи: что уже лежало (снятое до журнала) — одной записью, без отпечатков. */
    private fun baseline(obj: File, f: File, except: String) {
        val before = tracked(obj).map { Store.relative(it) }.filter { it != except }.sorted()
        append(obj, f, BEFORE, before.joinToString("\n"), "", "")
    }

    private fun append(obj: File, f: File, what: String, subject: String, path: String, sha: String) {
        val (n, prev) = heads[obj.path] ?: last(f)
        val e0 = Entry(n + 1, Platform.nowMs(), who(), what, subject, path, sha, hex(Platform.publicKey()), prev)
        val hash = hex(Platform.sha256(e0.body().encodeToByteArray()))
        val e = e0.copy(hash = hash, sig = hex(Platform.sign(hash.encodeToByteArray())))
        f.appendDurably((json.encodeToString(Entry.serializer(), e) + "\n").encodeToByteArray())
        heads[obj.path] = e.n to e.hash
        saveHead(obj, e.n, e.hash)
    }

    /** Номер и отпечаток последней строки файла (нет файла — начало цепочки). */
    private fun last(f: File): Pair<Int, String> {
        if (!f.isFile) return 0 to ""
        val line = f.readLines().lastOrNull { it.isNotBlank() } ?: return 0 to ""
        return runCatching { json.decodeFromString(Entry.serializer(), line) }.map { it.n to it.hash }.getOrDefault(0 to "")
    }

    fun entries(obj: File): List<Entry> = File(obj, FILE).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
        ?.map { runCatching { json.decodeFromString(Entry.serializer(), it) }.getOrElse { _ -> Entry(-1, 0, "", "строка не читается") } }.orEmpty()

    // ---------- Конец журнала — ещё и в служебной папке приложения (туда по USB не добраться) ----------

    private fun headsFile() = File(appDir(), "journal-heads.txt")
    private fun storedHeads(): Map<String, Pair<Int, String>> = runCatching {
        headsFile().readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 3 }?.let { it[0] to (it[1].toInt() to it[2]) } }.toMap()
    }.getOrDefault(emptyMap())

    private fun saveHead(obj: File, n: Int, hash: String) {
        val all = storedHeads() + (Store.relative(obj) to (n to hash))
        Store.writeDurably(headsFile(), all.entries.joinToString("\n") { "${it.key}\t${it.value.first}\t${it.value.second}" }.encodeToByteArray())
    }

    // ---------- Файлы описи, за которыми следит журнал ----------

    /** Опорные файлы описи: снимки, записи, txt, контакты, сама опись (отчёт, журнал и скрытое — нет). */
    fun tracked(obj: File): List<File> = obj.walkTopDown().onEnter { it == obj || !it.name.startsWith(".") }
        .filter { f -> f.isFile && !f.name.startsWith(".") && isTracked(f.name) }.toList()

    private fun isTracked(name: String) = name != FILE && !name.startsWith("Осмотр — ") && (
        name == Store.NOTE || name == Store.CONTACTS || name == Inventory.MEMBERS || name == Inventory.BROKEN ||
            name.startsWith(Inventory.PREFIX) || name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "heic", "heif", "m4a"))

    /** Отпечаток файла — по памяти, если размер и время не менялись (выгрузка не пересчитывает тысячи снимков). */
    fun hashOf(f: File, fresh: Boolean = false): String {
        val stamp = "${f.length()}:${f.lastModified()}"
        if (!fresh) synchronized(lock) { loadHashes(); hashes[f.path]?.takeIf { it.first == stamp }?.let { return it.second } }
        val h = hex(Platform.sha256File(f.path))
        synchronized(lock) { loadHashes(); hashes[f.path] = stamp to h; hashesDirty = true }
        return h
    }
    private val hashes = HashMap<String, Pair<String, String>>()
    private var hashesLoaded = false
    private var hashesDirty = false
    private fun hashesFile() = File(Store.cacheDir, "hashes.txt")
    private fun loadHashes() {
        if (hashesLoaded) return
        hashesLoaded = true
        runCatching { hashesFile().readLines().forEach { l -> l.split('\t').takeIf { it.size == 3 }?.let { hashes[it[0]] = it[1] to it[2] } } }
    }
    private fun saveHashes(): Unit = synchronized(lock) {
        if (!hashesDirty) return@synchronized
        hashesDirty = false
        runCatching { hashesFile().parentFile?.mkdirs(); Store.writeDurably(hashesFile(), hashes.entries.joinToString("\n") { "${it.key}\t${it.value.first}\t${it.value.second}" }.encodeToByteArray()) }
        Unit
    }

    /**
     * Что должно лежать в описи по журналу: путь → отпечаток («» — до журнала, содержимое не
     * известно). Переименования и переносы папок и снимков сдвигают пути.
     */
    fun expected(list: List<Entry>): Map<String, String> {
        val state = LinkedHashMap<String, String>()
        fun rename(from: String, to: String) {
            val moved = state.keys.filter { it == from || it.startsWith("$from/") }
            moved.forEach { k -> state.remove(k)?.let { v -> state[to + k.removePrefix(from)] = v } }
        }
        for (e in list) {
            when {
                e.what == BEFORE -> e.subject.lines().filter { it.isNotBlank() }.forEach { state[it] = "" }
                e.what == FOLDER_RENAMED || e.what == FOLDER_MOVED -> e.subject.split(" → ").takeIf { it.size == 2 }?.let { rename(it[0], it[1]) }
                e.what == FOLDER_DELETED -> state.keys.filter { it == e.file || it.startsWith(e.file + "/") }.forEach { state.remove(it) }
                e.what == PHOTO_MOVED -> {
                    e.subject.split(" → ").takeIf { it.size == 2 }?.let { state.remove(it[0]) }
                    if (e.file.isNotEmpty()) state[e.file] = e.sha256
                }
                e.file.isNotEmpty() -> if (e.sha256.isEmpty()) state.remove(e.file) else state[e.file] = e.sha256
            }
        }
        return state
    }

    /** Расхождение файлов с журналом. */
    data class Problem(val path: String, val kind: String)

    /** Итог проверки. [brokenAt] — с какой записи журнал нарушен (null — цел). */
    data class Check(
        val count: Int, val brokenAt: Int?, val reason: String, val devices: Int,
        val problems: List<Problem> = emptyList(), val warnings: List<String> = emptyList(),
    ) {
        val ok get() = brokenAt == null && problems.isEmpty()
        /** Строка для отчёта и экрана. */
        fun text() = when {
            brokenAt != null -> "Журнал изменён: запись № $brokenAt — $reason"
            problems.isNotEmpty() -> "Файлы менялись мимо приложения: ${problems.size} — " +
                problems.groupBy { it.kind }.entries.joinToString(", ") { "${it.key} ${it.value.size}" }
            else -> "Журнал цел: $count " + records(count) + (if (devices > 1) " с $devices телефонов" else "") +
                (if (warnings.isNotEmpty()) " · ${warnings.first()}" else "")
        }
    }

    /**
     * Полная проверка: цепочка, подписи, конец журнала, сверка файлов. [fresh] — пересчитать
     * отпечатки всех файлов (иначе — только изменившихся по размеру и времени).
     */
    fun verify(obj: File, fresh: Boolean = false): Check = verifyFrom(obj, entries(obj), chainFrom = 0, fresh = fresh)

    /**
     * Для отчёта (пересобирается после каждой правки): подписи — только у строк, дописанных после
     * прошлой проверки этого сеанса; сверка файлов — по памяти отпечатков.
     */
    fun verifyQuick(obj: File): Check {
        val list = entries(obj)
        val known = synchronized(lock) { checked[obj.path] }
        val from = if (known != null && known.first in 1..list.size && list[known.first - 1].hash == known.second) known.first else 0
        return verifyFrom(obj, list, from, fresh = false)
    }

    private fun verifyFrom(obj: File, list: List<Entry>, chainFrom: Int, fresh: Boolean): Check {
        val head = storedHeads()[Store.relative(obj)]
        if (list.isEmpty()) return Check(0, if (head != null) 1 else null, "журнал удалён (было ${head?.first} записей)", 0)
        val c = check(list, chainFrom)
        if (c.brokenAt != null) return c
        if (head != null && (list.size < head.first || list[head.first - 1].hash != head.second))
            return c.copy(brokenAt = minOf(list.size, head.first), reason = "журнал короче, чем был, или заменён (было ${head.first} записей)")
        val want = expected(list)
        val have = tracked(obj).associateBy { Store.relative(it) }
        val problems = mutableListOf<Problem>()
        for ((path, f) in have) {
            val sha = want[path]
            when {
                sha == null -> problems += Problem(path, "появился без записи")
                sha.isNotEmpty() && hashOf(f, fresh) != sha -> problems += Problem(path, "изменён")
            }
        }
        for (path in want.keys) if (path !in have) problems += Problem(path, "пропал без записи")
        saveHashes()
        synchronized(lock) { checked[obj.path] = list.size to list.last().hash }
        return c.copy(problems = problems, devices = list.map { it.key }.distinct().size)
    }

    /** Цепочка и подписи (с [from] — только новые строки); время назад — предупреждение. */
    fun check(list: List<Entry>, from: Int = 0): Check {
        var prev = if (from > 0) list[from - 1].hash else ""
        var prevT = if (from > 0) list[from - 1].t else 0L
        val warnings = mutableListOf<String>()
        list.forEachIndexed { i, e ->
            if (i < from) return@forEachIndexed
            val at = i + 1
            if (e.n != at) return Check(list.size, at, if (e.n < 0) "строка не читается" else "номер ${e.n} вместо $at — строку удалили или вставили", 0)
            if (e.prev != prev) return Check(list.size, at, "не сходится с предыдущей записью", 0)
            if (hex(Platform.sha256(e.body().encodeToByteArray())) != e.hash) return Check(list.size, at, "содержимое изменено", 0)
            if (!Platform.verify(unhex(e.key), e.hash.encodeToByteArray(), unhex(e.sig))) return Check(list.size, at, "подпись не сходится", 0)
            if (e.t < prevT - 10 * 60_000) warnings += "время записи № $at раньше предыдущей — переводили часы?"
            prev = e.hash; prevT = e.t
        }
        return Check(list.size, null, "", list.map { it.key }.distinct().size, warnings = warnings)
    }

    /** Забыть, что помнили в памяти (тесты: как после перезапуска). */
    fun forget() = synchronized(lock) { heads.clear(); checked.clear(); hashes.clear(); hashesLoaded = false }

    private fun records(n: Int) = when { n % 100 in 11..14 -> "записей"; n % 10 == 1 -> "запись"; n % 10 in 2..4 -> "записи"; else -> "записей" }

    /**
     * Самопроверка журнала на этом телефоне (iPhone в облаке — запуск с «-selftest»): пробная опись,
     * запись со снимком, подпись, проверка, подмена снимка — должна быть поймана. Итог — строкой.
     */
    fun selfTest(): String = runCatching {
        val obj = File(Store.root, ".selftest-" + Platform.nowMs()).also { it.mkdirs() }
        Xlsx.writeBook(File(obj, Inventory.PREFIX + "проба.xlsx"), listOf(Xlsx.Out("Опись", listOf(listOf<Any?>("№", "Наименование", "Инв.Номер"), listOf<Any?>(1, "Проба", "1/1")))))
        val photo = File(obj, "p.jpg").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        add(obj, "Снимок", "проба", photo)
        val ok = verify(obj, fresh = true)
        photo.writeBytes(ByteArray(64) { 7 })
        val caught = verify(obj, fresh = true)
        obj.deleteRecursively()
        when {
            Platform.publicKey().size != 65 -> "журнал: ключ не тот (${Platform.publicKey().size} байт)"
            !ok.ok -> "журнал: целая опись не прошла — ${ok.text()}"
            caught.problems.none { it.kind == "изменён" } -> "журнал: подмену не поймал — ${caught.text()}"
            else -> "журнал: ok"
        }
    }.getOrElse { "журнал: сбой — ${it.message}" }

    fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    fun unhex(s: String): ByteArray = runCatching { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrDefault(ByteArray(0))
}
