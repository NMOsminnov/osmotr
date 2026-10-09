package kg.osmotr

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kg.osmotr.core.File
import kg.osmotr.core.Platform
import kg.osmotr.core.Store
import kg.osmotr.ui.Host
import kg.osmotr.ui.OsmotrApp
import kg.osmotr.ui.Picked
import kg.osmotr.ui.Shot

/**
 * Android-часть «Осмотра»: всё приложение — общее (shared, то же на iPhone), здесь только то,
 * что умеет одна платформа: штатная камера, выбор файлов, «Поделиться», контакты, доступ к памяти.
 */
class MainActivity : ComponentActivity(), Host {
    /** Описи, присланные через «Поделиться» / «Открыть с помощью». */
    private val incoming = mutableStateListOf<Picked>()

    // Камера — как ожидающая результата, внутри задачи приложения: «назад» из неё — сюда.
    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private var gotBooks: ((List<Picked>) -> Unit)? = null
    private val openOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> gotBooks?.invoke(listOfNotNull(u).mapNotNull(::read)); gotBooks = null }
    private val openMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us -> gotBooks?.invoke(us.mapNotNull(::read)); gotBooks = null }
    private var gotPhone: ((String, String) -> Unit)? = null
    private val phone = registerForActivityResult(PickPhone) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) gotPhone?.invoke(c.getString(0).orEmpty(), c.getString(1).orEmpty())
            }
        }
        gotPhone = null
    }
    private val legacyStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Platform.context = applicationContext
        Store.init(File(Environment.getExternalStorageDirectory().absolutePath), File(filesDir.absolutePath))
        take(intent)
        setContent { OsmotrApp(this, incoming) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** Присланное «Поделиться» (WhatsApp, Telegram, почта) или «Открыть с помощью». */
    private fun take(intent: Intent?) {
        intent ?: return
        val got = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM))?.let(got::add)
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.let(got::addAll)
            Intent.ACTION_VIEW -> intent.data?.let(got::add)
            else -> return
        }
        // Некоторые мессенджеры кладут файлы только в clipData.
        if (got.isEmpty()) intent.clipData?.let { c -> (0 until c.itemCount).mapNotNullTo(got) { c.getItemAt(it).uri } }
        incoming.addAll(got.distinct().mapNotNull(::read))
        intent.action = null  // поворот / возврат не загружает второй раз
    }

    /** Файл по ссылке: имя (мессенджер не сказал — пусто, имя описи тогда — из книги) и содержимое. */
    private fun read(uri: Uri): Picked? = runCatching {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { '.' in it } ?: ""
        Picked(name, contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }.getOrNull()

    // ---------- Host ----------

    override fun takePhotos(dir: File) {
        SystemCamera.begin(this, dir)
        runCatching { camera.launch(SystemCamera.intent()) }.onFailure {
            SystemCamera.cancel(this)
            toast("На телефоне нет приложения камеры")
        }
    }

    override suspend fun afterResume(): Shot? {
        if (SystemCamera.session(this) == null) return null
        return SystemCamera.collect(this)?.let { Shot(it.dir, it.count) }
    }

    /** Можно ли писать в корень памяти: Android 11+ — «доступ ко всем файлам», 8–10 — запись. */
    override fun canWrite(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    override fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else legacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    override fun pickBooks(multiple: Boolean, got: (List<Picked>) -> Unit) {
        gotBooks = got
        val types = arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "application/octet-stream")
        if (multiple) openMany.launch(types) else openOne.launch(types)
    }

    override fun share(files: List<File>, mime: String, title: String) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", java.io.File(it.path)) })
        val intent = (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)).setType(mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        startActivity(Intent.createChooser(intent, title))
    }

    override fun toast(text: String, long: Boolean) = Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    override val pickPhone: ((got: (String, String) -> Unit) -> Unit) = { got -> gotPhone = got; phone.launch(Unit) }

    override val cacheDir: File get() = File(super.getCacheDir().absolutePath)

    // ---------- Диктофон ----------
    // AAC, моно, 64 кбит/с: голос разборчив, минута — около полумегабайта.

    private var recorder: android.media.MediaRecorder? = null
    private var recordingTo: File? = null
    private var player: android.media.MediaPlayer? = null
    private var playDone: (() -> Unit)? = null
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun startRecording(file: File): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphone.launch(Manifest.permission.RECORD_AUDIO); return false
        }
        stopPlaying()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.media.MediaRecorder(this) else @Suppress("DEPRECATION") android.media.MediaRecorder()
        return runCatching {
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1); r.setAudioSamplingRate(44_100); r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(file.path)
            r.prepare(); r.start()
            recorder = r; recordingTo = file
        }.onFailure { r.release() }.isSuccess
    }

    override fun stopRecording(): Boolean {
        val r = recorder ?: return false
        recorder = null; recordingTo = null
        val ok = runCatching { r.stop() }.isSuccess  // слишком короткая запись — stop() бросает
        r.release()
        return ok
    }

    override fun recordingLevel(): Float = (recorder?.let { runCatching { it.maxAmplitude }.getOrDefault(0) } ?: 0) / 32_767f

    override fun play(file: File, done: () -> Unit) {
        stopPlaying()
        val p = android.media.MediaPlayer()
        runCatching {
            p.setDataSource(file.path)
            p.setOnCompletionListener { stopPlaying() }
            p.prepare(); p.start()
            player = p; playDone = done
        }.onFailure { p.release(); done() }
    }

    override fun stopPlaying() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playDone?.let { playDone = null; it() }
    }

    override fun duration(file: File): Long {
        val m = android.media.MediaMetadataRetriever()  // AutoCloseable — только с Android 10
        return try {
            m.setDataSource(file.path)
            m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { runCatching { m.release() } }
    }

    override fun onStop() {
        super.onStop()
        // Свернули во время записи — записанное сохранить, а не потерять.
        if (recorder != null) { val f = recordingTo; if (stopRecording() && f != null) { Store.scan(listOf(f)); Store.changed() } }
        stopPlaying()
    }
}

/** Выбрать номер из телефонной книги: доступ только к выбранному, без разрешения на все контакты. */
private object PickPhone : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: android.content.Context, input: Unit) =
        Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}
