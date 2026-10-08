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
}

/** Выбрать номер из телефонной книги: доступ только к выбранному, без разрешения на все контакты. */
private object PickPhone : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: android.content.Context, input: Unit) =
        Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}
