package kg.osmotr.ui

import kg.osmotr.core.File
import kg.osmotr.core.Store
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.setActive
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionUndetermined
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSNumber
import platform.Contacts.CNContactProperty
import platform.Contacts.CNPhoneNumber
import platform.ContactsUI.CNContactPickerDelegateProtocol
import platform.ContactsUI.CNContactPickerViewController
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeSpreadsheet
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iPhone: штатная камера (после снимка открывается снова — серия; «Отмена» — назад в папку),
 * «Файлы» для описей, системное «Поделиться», выбор контакта без доступа ко всей книге.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosHost : Host {
    private var shot: Shot? = null
    private var shooting: File? = null
    private var taken = 0

    private fun top(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (vc?.presentedViewController != null) vc = vc.presentedViewController
        return vc
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        val n = length.toInt()
        val out = ByteArray(n)
        if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }

    private fun saveJpeg(img: UIImage): Boolean {
        val dir = shooting ?: return false
        val data = UIImageJPEGRepresentation(img, 0.92) ?: return false
        val f = Store.newPhotoFile(dir)
        f.writeBytes(data.toByteArray())
        Store.saved(f)
        taken++
        return true
    }

    private fun finishShooting() {
        shooting?.let { shot = Shot(it, taken) }
        shooting = null
    }

    // ---------- Камера ----------

    private val camera = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
        override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
            (didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage)?.let { img ->
                saveJpeg(img)
                // Страховка: копия в «Фото» (разрешение — только «добавлять»; в чужие снимки не смотрим).
                // Удалили приложение по ошибке — снимки осмотра остались в галерее.
                platform.UIKit.UIImageWriteToSavedPhotosAlbum(img, null, null, null)
            }
            // Серия: снимок лёг — камера сразу снова (как штатная камера на Android).
            picker.dismissViewControllerAnimated(false) { openCamera() }
        }
        override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
            picker.dismissViewControllerAnimated(true) { finishShooting(); Store.changed() }
        }
    }

    private fun openCamera() {
        val picker = UIImagePickerController()
        picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        picker.delegate = camera
        top()?.presentViewController(picker, true, null)
    }

    // Нет камеры (симулятор) — снимки из галереи, сразу несколько.
    private val gallery = object : NSObject(), PHPickerViewControllerDelegateProtocol {
        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            val results = didFinishPicking.filterIsInstance<PHPickerResult>()
            var left = results.size
            picker.dismissViewControllerAnimated(true, null)
            if (left == 0) { finishShooting(); Store.changed(); return }
            results.forEach { r ->
                r.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
                    platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
                        data?.let { UIImage(data = it) }?.let(::saveJpeg)
                        if (--left == 0) { finishShooting(); Store.changed() }
                    }
                }
            }
        }
    }

    override fun takePhotos(dir: File) {
        shooting = dir; taken = 0
        if (UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)) openCamera()
        else {
            val cfg = PHPickerConfiguration().apply { selectionLimit = 0; filter = PHPickerFilter.imagesFilter }
            top()?.presentViewController(PHPickerViewController(cfg).apply { delegate = gallery }, true, null)
        }
    }

    override suspend fun afterResume(): Shot? = shot.also { shot = null }

    override fun canWrite() = true
    override fun requestStorage() {}

    // ---------- Описи из «Файлов» ----------

    private var gotBooks: ((List<Picked>) -> Unit)? = null
    private val documents = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            val books = didPickDocumentsAtURLs.filterIsInstance<NSURL>().mapNotNull { url ->
                NSData.dataWithContentsOfURL(url)?.let { Picked(url.lastPathComponent ?: "опись.xlsx", it.toByteArray()) }
            }
            gotBooks?.invoke(books); gotBooks = null
        }
        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { gotBooks = null }
    }

    override fun pickBooks(multiple: Boolean, got: (List<Picked>) -> Unit) {
        gotBooks = got
        val types = listOfNotNull(UTType.typeWithFilenameExtension("xlsx"), UTType.typeWithFilenameExtension("xls"), UTTypeSpreadsheet)
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        picker.allowsMultipleSelection = multiple
        picker.delegate = documents
        top()?.presentViewController(picker, true, null)
    }

    // ---------- Поделиться, сообщения ----------

    override fun share(files: List<File>, mime: String, title: String) {
        val urls = files.map { NSURL.fileURLWithPath(it.path) }
        val vc = UIActivityViewController(activityItems = urls, applicationActivities = null)
        top()?.let { t ->
            vc.popoverPresentationController?.sourceView = t.view
            t.presentViewController(vc, true, null)
        }
    }

    override fun toast(text: String, long: Boolean) = ToastBus.show(text)

    // ---------- Контакт ----------

    private var gotPhone: ((String, String) -> Unit)? = null
    private val contacts = object : NSObject(), CNContactPickerDelegateProtocol {
        override fun contactPicker(picker: CNContactPickerViewController, didSelectContactProperty: CNContactProperty) {
            val c = didSelectContactProperty.contact
            val name = listOf(c.familyName, c.givenName, c.middleName).filter { it.isNotBlank() }.joinToString(" ")
            val phone = (didSelectContactProperty.value as? CNPhoneNumber)?.stringValue.orEmpty()
            gotPhone?.invoke(name, phone); gotPhone = null
        }
    }

    override val pickPhone: ((got: (String, String) -> Unit) -> Unit) = { got ->
        gotPhone = got
        val picker = CNContactPickerViewController()
        picker.displayedPropertyKeys = listOf("phoneNumbers")
        picker.delegate = contacts
        top()?.presentViewController(picker, true, null)
    }

    override val cacheDir: File get() = File(NSTemporaryDirectory().trimEnd('/') + "/osmotr")

    // ---------- Диктофон ----------
    // AAC, моно, 44,1 кГц — как на Android; файл .m4a в папке.

    private var recorder: AVAudioRecorder? = null
    private var player: AVAudioPlayer? = null
    private var playDone: (() -> Unit)? = null
    private val playerDelegate = object : NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) { stopPlaying() }
    }

    override fun startRecording(file: File): Boolean {
        val session = AVAudioSession.sharedInstance()
        when (session.recordPermission) {
            AVAudioSessionRecordPermissionUndetermined -> { session.requestRecordPermission { } ; return false }
            AVAudioSessionRecordPermissionDenied -> { ToastBus.show("Разрешите микрофон: Настройки → Осмотр → Микрофон"); return false }
            else -> {}
        }
        stopPlaying()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionCategoryOptionDefaultToSpeaker, null)
        session.setActive(true, null)
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to NSNumber(unsignedInt = kAudioFormatMPEG4AAC),
            AVSampleRateKey to NSNumber(double = 44_100.0),
            AVNumberOfChannelsKey to NSNumber(int = 1),
            AVEncoderBitRateKey to NSNumber(int = 64_000),
        )
        val r = AVAudioRecorder(NSURL.fileURLWithPath(file.path), settings, null)
        r.meteringEnabled = true
        if (!r.record()) return false
        recorder = r
        return true
    }

    override fun stopRecording(): Boolean {
        val r = recorder ?: return false
        recorder = null
        r.stop()
        AVAudioSession.sharedInstance().setActive(false, null)
        return true
    }

    override fun recordingLevel(): Float {
        val r = recorder ?: return 0f
        r.updateMeters()
        val db = r.averagePowerForChannel(0u)  // −160…0 дБ; голос — примерно −50…−5
        return ((db + 50f) / 45f).coerceIn(0f, 1f)
    }

    override fun play(file: File, done: () -> Unit) {
        stopPlaying()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
        val p = AVAudioPlayer(NSURL.fileURLWithPath(file.path), null)
        p.delegate = playerDelegate
        if (!p.play()) { done(); return }
        player = p; playDone = done
    }

    override fun stopPlaying() {
        player?.stop()
        player = null
        playDone?.let { playDone = null; it() }
    }

    override fun duration(file: File): Long =
        (AVAudioPlayer(NSURL.fileURLWithPath(file.path), null).duration * 1000).toLong()
}
