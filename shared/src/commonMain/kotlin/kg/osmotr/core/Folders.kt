package kg.osmotr.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/**
 * Что лежит в папках описей: состав (Инвентарники.txt), снимки (сколько, первый, последний) и
 * вложенные папки. Учёт «осмотрено» обходил все папки объекта и спрашивал диск о каждом снимке —
 * на каждый снимок и каждую отметку (150–200 мс на 600 папок на компьютере, на телефоне в разы
 * дольше). Теперь папка перечитывается, только если она менялась: время папки меняется, когда в
 * ней появляется, исчезает или переименовывается файл, — снимок, состав, комментарий.
 *
 * Истина — по-прежнему файлы (автор 09.10.2026: txt — опорные данные): сведения — только память
 * о них, на диске приложения, чтобы после закрытия не обходить всё заново. Потеряются — соберутся
 * из файлов. Свежим (последние [TRUST_AFTER_MS]) сведениям не верим: у части файловых систем
 * время папки — с точностью до секунды, и правка в ту же секунду была бы не видна.
 */
internal object Folders {
    @kotlinx.serialization.Serializable
    class Facts(val stamp: Long, val members: List<String> = emptyList(), val photos: Int = 0,
                val first: Long = 0, val last: Long = 0, val subdirs: List<String> = emptyList())

    private const val TRUST_AFTER_MS = 2000L
    private val map = HashMap<String, Facts>()
    private var loaded = false
    private var dirty = false
    private var saving = false
    private val lock = SynchronizedObject()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private fun file() = File(Store.cacheDir, "folders-v1.json")

    fun facts(d: File): Facts {
        val stamp = d.lastModified()
        synchronized(lock) {
            load()
            map[d.path]?.takeIf { it.stamp == stamp && Platform.nowMs() - stamp > TRUST_AFTER_MS }?.let { return it }
        }
        val f = read(d, stamp)
        synchronized(lock) { map[d.path] = f; dirty = true }
        return f
    }

    private fun read(d: File, stamp: Long): Facts {
        var n = 0; var lo = Long.MAX_VALUE; var hi = 0L
        val subs = ArrayList<String>()
        var members = emptyList<String>()
        for (c in d.listFiles().orEmpty()) {
            val name = c.name
            val m = runCatching { systemFs.metadataOrNull(c.path.toPath()) }.getOrNull() ?: continue
            when {
                m.isDirectory -> if (!name.startsWith(".")) subs += name
                name == Inventory.MEMBERS -> members = Inventory.members(d)
                Store.isPhoto(name, m) -> { n++; val t = m.lastModifiedAtMillis ?: 0L; if (t < lo) lo = t; if (t > hi) hi = t }
            }
        }
        subs.sort()
        return Facts(stamp, members, n, if (n > 0) lo else 0L, hi, subs)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching { json.decodeFromString<Map<String, Facts>>(file().readText()) }.getOrNull()?.let(map::putAll)
    }

    /** Записать на диск — в фоне, одной записью на серию правок; исчезнувшие папки — долой. */
    fun saveSoon() {
        synchronized(lock) { if (!dirty || saving) return; saving = true }
        scope.launch {
            kotlinx.coroutines.delay(1000)
            val snapshot = synchronized(lock) { dirty = false; HashMap(map) }
            val live = snapshot.filterKeys { File(it).isDirectory }
            runCatching {
                file().parentFile?.mkdirs()
                Store.writeDurably(file(), json.encodeToString<Map<String, Facts>>(live).encodeToByteArray())
            }
            synchronized(lock) { (snapshot.keys - live.keys).forEach(map::remove); saving = false; if (dirty) scope.launch { saveSoon() } }
        }
    }

    /** Для тестов: забыть память (как после перезапуска). */
    fun forget() = synchronized(lock) { map.clear(); loaded = false; dirty = false }
}
