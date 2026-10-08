package kg.osmotr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

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
    data class Session(val dir: File, val startedMs: Long, val cameraPackage: String?)

    /** Снятое позже этого после начала захода — уже не осмотр (ушли из камеры «Домой» и снимали своё). */
    const val SESSION_MAX_MS = 2 * 60 * 60 * 1000L

    fun session(context: Context): Session? {
        val p = context.getSharedPreferences(PREFS, 0)
        val dir = p.getString("dir", null) ?: return null
        return Session(File(dir), p.getLong("started", 0), p.getString("package", null))
    }

    private fun end(context: Context) = context.getSharedPreferences(PREFS, 0).edit().clear().apply()

    fun intent() = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)

    /**
     * Начать заход в [dir]: запомнить папку, время и приложение камеры. Камера запускается
     * вызывающим — как ожидающая результата, внутри задачи приложения: тогда «назад» из камеры
     * возвращает сюда, а не в галерею камеры (автор, 08.10.2026: «жест назад из камеры в
     * галерею уводит»).
     */
    fun begin(context: Context, dir: File) {
        val camera = context.packageManager.resolveActivity(intent(), 0)?.activityInfo?.packageName
            ?.takeIf { it != "android" }  // «android» — окно выбора камеры, не сама камера
        context.getSharedPreferences(PREFS, 0).edit()
            .putString("dir", dir.absolutePath).putLong("started", System.currentTimeMillis())
            .putString("package", camera).commit()
    }

    fun cancel(context: Context) = end(context)

    data class Imported(val dir: File, val count: Int)

    /**
     * Перенести снятое за заход в его папку. Камера дописывает кадры и после возврата (обработка
     * HDR и т. п. — секунды), поэтому снятое собирается несколько раз, пока новых не будет
     * [settleMs]. Заход закрывается: следующий начнётся с нового «Снимать».
     */
    suspend fun collect(context: Context, settleMs: Long = 2500): Imported? {
        val s = session(context) ?: return null
        var total = 0
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() - quietSince < settleMs) {
            val n = withContext(Dispatchers.IO) { importOnce(context, s) }
            if (n > 0) { total += n; quietSince = System.currentTimeMillis() }
            delay(500)
        }
        end(context)
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
            add(MediaStore.Images.Media.DATA); add(MediaStore.Images.Media.DATE_TAKEN)
            if (q) { add(MediaStore.Images.Media.IS_PENDING); add(MediaStore.Images.Media.OWNER_PACKAGE_NAME) }
        }.toTypedArray()
        // DATE_ADDED — в секундах; запас в 2 с на расхождение часов.
        val from = (s.startedMs / 1000 - 2).toString()
        val till = ((s.startedMs + SESSION_MAX_MS) / 1000).toString()
        val found = mutableListOf<Pair<File, Long>>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} <= ?", arrayOf(from, till),
            "${MediaStore.Images.Media.DATE_TAKEN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                if (q && c.getInt(2) == 1) continue  // ещё пишется
                val owner = if (q) c.getString(3) else null
                if (!fromCamera(path, owner, s.cameraPackage)) continue
                val f = File(path)
                if (!Store.isPhoto(f) || f.absolutePath.startsWith(Store.root.absolutePath)) continue
                found += f to c.getLong(1).takeIf { it > 0 }.let { it ?: f.lastModified() }
            }
        }
        // Сотня снимков за заход — обычное дело: переименование в пределах памяти мгновенно,
        // регистрация в системе и миниатюры — одной пачкой в фоне, экран — одно обновление.
        val moved = mutableListOf<File>()
        val sources = mutableListOf<File>()
        for ((f, taken) in found) {
            val target = Store.newPhotoFile(s.dir, f.extension.lowercase(), taken)
            if (f.renameTo(target) || runCatching { f.copyTo(target); f.delete() }.getOrDefault(false)) {
                moved += target; sources += f
            }
        }
        Store.saved(moved, removed = sources)
        return moved.size
    }

    /** Снимок камеры: записан ею (если система это знает) и лежит в DCIM, не снимок экрана. */
    fun fromCamera(path: String, owner: String?, camera: String?): Boolean {
        val p = path.replace('\\', '/')
        if (p.contains("screenshot", ignoreCase = true) || p.contains("скриншот", ignoreCase = true)) return false
        if (owner != null && camera != null) return owner == camera
        return p.contains("/DCIM/", ignoreCase = true)
    }
}
