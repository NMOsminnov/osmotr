package kg.osmotr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kg.osmotr.core.Store
import kg.osmotr.core.File as KFile

/**
 * Снимает штатная камера телефона — со всеми её настройками и обработкой (автор, 08.10.2026:
 * «лучше передать снимок встроенному приложению системы… там уже есть все настройки…
 * бесшовно интегрировать»).
 *
 * «Снимать» открывает камеру в обычном режиме (без подтверждения каждого кадра — снимать
 * можно сколько угодно подряд) и запоминает заход: папку и время начала — на диске, чтобы
 * снимки не потерялись, если система выгрузит приложение, пока открыта камера. По
 * возвращении всё, что камера сняла за заход, переносится из DCIM в папку осмотра.
 */
object SystemCamera {
    private const val PREFS = "camera-session"

    /** Заход: папка, время начала и приложение камеры, которое снимало (null — не узнали). */
    data class Session(val dir: KFile, val startedMs: Long, val cameraPackage: String?)

    /** Снятое позже этого после начала захода — уже не осмотр (ушли из камеры «Домой» и снимали своё). */
    const val SESSION_MAX_MS = 2 * 60 * 60 * 1000L

    fun session(context: Context): Session? {
        val p = context.getSharedPreferences(PREFS, 0)
        val dir = p.getString("dir", null) ?: return null
        return Session(KFile(dir), p.getLong("started", 0), p.getString("package", null))
    }

    /**
     * Закрыть заход [s] — только его. Пока сборщик прошлого захода дожидался кадров, человек мог
     * уйти снимать следующий предмет: стирать тогда новый заход нельзя — вернувшись из камеры,
     * приложение не узнало бы, что снимало, и снимки остались бы в DCIM (отзыв 15.10.2026:
     * «сфотала несколько фоток, потом вышла, и он мне не добавил»).
     */
    internal fun end(context: Context, s: Session) {
        val p = context.getSharedPreferences(PREFS, 0)
        if (p.getLong("started", -1) == s.startedMs && p.getString("dir", null) == s.dir.absolutePath) p.edit().clear().commit()
    }

    /**
     * Сборщики — по одному. Два сразу (прошлый заход ещё ждёт кадров, новый уже вернулся) брали
     * один и тот же файл из DCIM: где переименование не проходит и файл копируется, снимок ложился
     * дважды, а копия дописываемого камерой файла выходила обрезанной.
     */
    private val сбор = Mutex()

    fun intent() = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)

    /**
     * Начать заход в [dir]: запомнить папку, время и приложение камеры. Камера запускается
     * вызывающим — как ожидающая результата, внутри задачи приложения: тогда «назад» из камеры
     * возвращает сюда, а не в галерею камеры (автор, 08.10.2026: «жест назад из камеры в
     * галерею уводит»).
     */
    fun begin(context: Context, dir: KFile) {
        val camera = context.packageManager.resolveActivity(intent(), 0)?.activityInfo?.packageName
            ?.takeIf { it != "android" }  // «android» — окно выбора камеры, не сама камера
        context.getSharedPreferences(PREFS, 0).edit()
            .putString("dir", dir.absolutePath).putLong("started", System.currentTimeMillis())
            .putString("package", camera).commit()
    }

    fun cancel(context: Context) = context.getSharedPreferences(PREFS, 0).edit().clear().commit()

    data class Imported(val dir: KFile, val count: Int)

    /**
     * Перенести снятое за заход в его папку. Камера дописывает кадры и после возврата (обработка
     * HDR и т. п. — секунды), поэтому снятое собирается несколько раз, пока новых не будет
     * [settleMs]. Заход закрывается: следующий начнётся с нового «Снимать».
     */
    suspend fun collect(context: Context, settleMs: Long = 2500,
                        importer: (Context, Session) -> Int = ::importOnce): Imported? {
        val s = session(context) ?: return null
        var total = 0
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() - quietSince < settleMs) {
            val n = сбор.withLock { withContext(Dispatchers.IO) { importer(context, s) } }
            if (n > 0) { total += n; quietSince = System.currentTimeMillis() }
            delay(500)
        }
        end(context, s)
        return Imported(s.dir, total)
    }

    /**
     * Один проход: снимки, которые за заход записала **сама камера**, и только они (автор,
     * 08.10.2026: «чтобы попадали только наши фото, без других — от мессенджеров, например»).
     * Android 10+ знает, какое приложение записало файл (OWNER_PACKAGE_NAME), — берём только
     * файлы открытой камеры. Не знает — только из DCIM и без снимков экрана.
     */
    private fun importOnce(context: Context, s: Session): Int {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val cols = buildList {
            add(MediaStore.Images.Media.DATA); add(MediaStore.Images.Media.DATE_TAKEN); add(MediaStore.Images.Media._ID)
            if (q) { add(MediaStore.Images.Media.IS_PENDING); add(MediaStore.Images.Media.OWNER_PACKAGE_NAME) }
        }.toTypedArray()
        // DATE_ADDED — в секундах; запас в 2 с на расхождение часов.
        val from = (s.startedMs / 1000 - 2).toString()
        // Начался следующий заход — его снимки не наши: граница — его начало, а не два часа.
        val next = session(context)?.startedMs?.takeIf { it > s.startedMs }
        val till = (sessionEnd(s.startedMs, next) / 1000).toString()
        val found = mutableListOf<Triple<File, Long, Long>>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} <= ?", arrayOf(from, till),
            "${MediaStore.Images.Media.DATE_TAKEN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                if (q && c.getInt(3) == 1) continue  // ещё пишется
                val owner = if (q) c.getString(4) else null
                if (!fromCamera(path, owner, s.cameraPackage)) continue
                val f = File(path)
                if (!Store.isPhoto(KFile(f.absolutePath)) || f.absolutePath.startsWith(Store.root.absolutePath)) continue
                found += Triple(f, c.getLong(1).takeIf { it > 0 }.let { it ?: f.lastModified() }, c.getLong(2))
            }
        }
        // Сотня снимков за заход — обычное дело: переименование в пределах памяти мгновенно,
        // регистрация в системе и миниатюры — одной пачкой в фоне, экран — одно обновление.
        val moved = mutableListOf<KFile>()
        val sources = mutableListOf<KFile>()
        for ((f, taken, id) in found) {
            val target = Store.newPhotoFile(s.dir, f.extension.lowercase(), taken)
            val to = File(target.path)
            if (f.renameTo(to) || copyWhole(f, to)) {
                moved += target; sources += KFile(f.absolutePath)
                // Запись галереи о прежнем месте — прочь сразу. Повторный разбор пути, которого
                // больше нет, успевал не всегда, и в галерее оставались чёрные квадраты «файл
                // повреждён» (отзыв 15.10.2026). Нет прав — подберёт разбор, как раньше.
                runCatching { context.contentResolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), null, null) }
            }
        }
        Store.saved(moved, removed = sources)
        return moved.size
    }

    /**
     * Копия, когда переименовать нельзя (другой том): во временный файл и переименованием —
     * обрезанный файл под именем снимка не появится никогда.
     */
    internal fun copyWhole(from: File, to: File): Boolean = runCatching {
        val tmp = File(to.parentFile, "." + to.name + ".part")
        from.copyTo(tmp, overwrite = true)
        if (tmp.length() != from.length() || !tmp.renameTo(to)) { tmp.delete(); return false }
        from.delete()
    }.getOrDefault(false)

    /** Граница захода: два часа или начало следующего, что раньше. */
    fun sessionEnd(startedMs: Long, nextStartedMs: Long?): Long =
        minOf(startedMs + SESSION_MAX_MS, nextStartedMs?.let { it - 1000 } ?: Long.MAX_VALUE)

    /** Снимок камеры: записан ею (если система это знает) и лежит в DCIM, не снимок экрана. */
    fun fromCamera(path: String, owner: String?, camera: String?): Boolean {
        val p = path.replace('\\', '/')
        if (p.contains("screenshot", ignoreCase = true) || p.contains("скриншот", ignoreCase = true)) return false
        if (owner != null && camera != null) return owner == camera
        return p.contains("/DCIM/", ignoreCase = true)
    }
}
