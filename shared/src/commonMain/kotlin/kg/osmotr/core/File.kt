package kg.osmotr.core

import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** Файловая система телефона (Android — память, iPhone — папка приложения). */
expect val systemFs: FileSystem

/**
 * Файл или папка — с теми же именами, что java.io.File на Android (listFiles, renameTo,
 * lastModified, walkTopDown…): код хранилища, описей и отчётов переехал в общий почти без
 * правок. Внутри — okio, который работает и на Android, и на iPhone.
 */
class File(val path: String) : Comparable<File> {
    constructor(parent: File?, child: String) : this(if (parent == null) child else parent.path.trimEnd('/') + "/" + child)

    private val p: Path get() = path.toPath()
    private val fs get() = systemFs

    val name: String get() = path.trimEnd('/').substringAfterLast('/')
    val absolutePath: String get() = path
    val parentFile: File? get() = path.trimEnd('/').substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::File)
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')
    val extension: String get() = name.substringAfterLast('.', "")

    private fun meta() = runCatching { fs.metadataOrNull(p) }.getOrNull()
    val isDirectory: Boolean get() = meta()?.isDirectory == true
    val isFile: Boolean get() = meta()?.isRegularFile == true
    fun exists(): Boolean = meta() != null
    fun length(): Long = meta()?.size ?: 0L
    fun lastModified(): Long = meta()?.lastModifiedAtMillis ?: 0L

    fun listFiles(): Array<File>? = if (!isDirectory) null else runCatching { fs.list(p).map { File(it.toString()) }.toTypedArray() }.getOrNull()
    fun mkdirs(): Boolean = runCatching { fs.createDirectories(p); true }.getOrDefault(false)
    fun delete(): Boolean = runCatching { fs.delete(p, mustExist = false); true }.getOrDefault(false)
    fun deleteRecursively(): Boolean = runCatching { fs.deleteRecursively(p, mustExist = false); true }.getOrDefault(false)
    fun renameTo(to: File): Boolean = runCatching { fs.atomicMove(p, to.p); true }.getOrDefault(false)
    fun copyTo(to: File): File { fs.copy(p, to.p); return to }

    fun readBytes(): ByteArray = fs.read(p) { readByteArray() }
    fun readText(): String = fs.read(p) { readUtf8() }
    fun readLines(): List<String> = readText().lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
    fun writeBytes(bytes: ByteArray) { fs.write(p) { write(bytes) } }
    fun writeText(text: String) { fs.write(p) { writeUtf8(text) } }
    fun sink(body: (BufferedSink) -> Unit) { fs.sink(p).buffer().use(body) }
    /** Дописать в конец и сбросить на диск (fsync): журнал растёт строкой, а не перезаписью всего файла. */
    fun appendDurably(bytes: ByteArray) {
        fs.openReadWrite(p).use { h -> h.write(h.size(), bytes, 0, bytes.size); h.flush() }
    }

    /** Обход вглубь (папка, потом её содержимое); [onEnter] — заходить ли в папку. */
    fun walkTopDown(): Walk = Walk(this, { true })
    class Walk(private val start: File, private val enter: (File) -> Boolean) : Sequence<File> {
        fun onEnter(f: (File) -> Boolean) = Walk(start, f)
        override fun iterator(): Iterator<File> = sequence {
            suspend fun SequenceScope<File>.go(f: File) {
                yield(f)
                if (f.isDirectory && enter(f)) f.listFiles()?.sortedBy { it.name }?.forEach { go(it) }
            }
            go(start)
        }.iterator()
    }

    fun relativeTo(base: File): File = File(path.removePrefix(base.path.trimEnd('/')).trimStart('/'))

    override fun equals(other: Any?) = other is File && other.path.trimEnd('/') == path.trimEnd('/')
    override fun hashCode() = path.trimEnd('/').hashCode()
    override fun toString() = path
    override fun compareTo(other: File) = path.compareTo(other.path)

    companion object {
        const val separator = "/"
        const val separatorChar = '/'
    }
}
