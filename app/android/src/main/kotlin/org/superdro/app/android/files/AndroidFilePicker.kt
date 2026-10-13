package org.superdro.app.android.files

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.toxicbakery.logging.Arbor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.superdro.core.util.FilePicker
import org.superdro.core.util.PickedFile

/** A system screen that [AndroidFilePicker] asks the visible activity to start. */
sealed interface PickerRequest {
    /** `ActivityResultContracts.OpenDocument`. The result is a `Uri?`. */
    class Open(val mimeTypes: Array<String>) : PickerRequest

    /** `ActivityResultContracts.CreateDocument` with [mimeType]. The result is a `Uri?`. */
    class Create(val suggestedName: String, val mimeType: String) : PickerRequest

    /** `MediaStore.ACTION_IMAGE_CAPTURE` into [output] (`TakePicture`). The result is a `Boolean`. */
    class Photo(val output: Uri) : PickerRequest

    /** The system dialog of `CAMERA`. The result is a `Boolean`. */
    data object CameraPermission : PickerRequest
}

/**
 * The visible activity that starts the system screens of [AndroidFilePicker]. `MainActivity`
 * implements it. The activity gives each result to [AndroidFilePicker.deliver].
 */
interface FilePickerHost {

    /** Starts [request]. Gives false when the activity cannot start it. Call it on the main thread. */
    fun launch(request: PickerRequest): Boolean

    /** Starts [intent], for example the share sheet. Gives false when no activity can start it. */
    fun startActivitySafely(intent: Intent): Boolean
}

/**
 * The Android [FilePicker]. A DI singleton: the result of a system screen comes to the activity
 * that Android makes again after a configuration change, so the waiting request is in this
 * object, not in the activity.
 *
 * - [openDocument]: `OpenDocument`, then the content and the display name of the file.
 * - [createDocument]: `CreateDocument`, then the write of the content.
 * - [capturePhoto]: `CAMERA` first, then `ACTION_IMAGE_CAPTURE` with a `FileProvider` URI in the app
 *   cache. Android does not let an app that declares `CAMERA` but does not hold it start
 *   `IMAGE_CAPTURE`.
 * - [share]: the share sheet with a `FileProvider` URI of a copy in the app cache.
 *
 * One request runs at a time. A function gives null or false when no activity is visible.
 */
class AndroidFilePicker(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FilePicker {

    private val context: Context = context.applicationContext
    private val authority: String = authorityOf(this.context.packageName)
    private val mutex = Mutex()

    @Volatile
    private var host: FilePickerHost? = null

    @Volatile
    private var pending: CompletableDeferred<Any?>? = null

    /** The activity [host] is visible. */
    fun attach(host: FilePickerHost) {
        this.host = host
    }

    /** The activity [host] goes away. When it finishes, a waiting request gets no result. */
    fun detach(host: FilePickerHost, finishing: Boolean) {
        if (this.host === host) this.host = null
        if (finishing) deliver(null)
    }

    /** Gives the result of the system screen to the waiting request. */
    fun deliver(result: Any?) {
        val waiting = pending
        pending = null
        waiting?.complete(result)
    }

    override suspend fun openDocument(mimeTypes: List<String>): PickedFile? = mutex.withLock {
        val types = mimeTypes.ifEmpty { listOf(ANY_TYPE) }.toTypedArray()
        val uri = request(PickerRequest.Open(types)) as? Uri ?: return null
        withContext(io) { read(uri) }
    }

    override suspend fun createDocument(suggestedName: String, mimeType: String, content: ByteArray): Boolean = mutex.withLock {
        val uri = request(PickerRequest.Create(suggestedName, mimeType)) as? Uri ?: return false
        withContext(io) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { stream -> stream.write(content) } != null
            } catch (error: IOException) {
                log.w(error) { "The write of $suggestedName failed" }
                false
            }
        }
    }

    override suspend fun capturePhoto(): PickedFile? = mutex.withLock {
        if (!hasCameraPermission()) {
            val granted = request(PickerRequest.CameraPermission) as? Boolean ?: false
            if (!granted) return null
        }
        val file = withContext(io) {
            File(context.cacheDir, PHOTO_FOLDER).apply { mkdirs() }.resolve(FileNames.photoName(System.currentTimeMillis()))
        }
        try {
            val uri = FileProvider.getUriForFile(context, authority, file)
            val taken = request(PickerRequest.Photo(uri)) as? Boolean ?: false
            withContext(io) {
                if (taken && file.isFile && file.length() > 0L) PickedFile(file.name, file.readBytes()) else null
            }
        } finally {
            withContext(io) { file.delete() }
        }
    }

    override suspend fun share(name: String, mimeType: String, content: ByteArray): Boolean = mutex.withLock {
        val current = host ?: return false
        val file = withContext(io) {
            val folder = File(context.cacheDir, SHARE_FOLDER)
            // The copies of the last share are not necessary any more.
            folder.deleteRecursively()
            folder.mkdirs()
            folder.resolve(FileNames.safeName(name)).apply { writeBytes(content) }
        }
        val uri = FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        withContext(Dispatchers.Main) { current.startActivitySafely(Intent.createChooser(send, null)) }
    }

    private suspend fun request(request: PickerRequest): Any? {
        val current = host ?: return null
        val waiting = CompletableDeferred<Any?>()
        pending = waiting
        val started = withContext(Dispatchers.Main) { current.launch(request) }
        if (!started) {
            if (pending === waiting) pending = null
            return null
        }
        return try {
            waiting.await()
        } finally {
            if (pending === waiting) pending = null
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun read(uri: Uri): PickedFile? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() } ?: return null
        PickedFile(displayName(uri), bytes)
    } catch (error: IOException) {
        log.w(error) { "The read of $uri failed" }
        null
    } catch (error: SecurityException) {
        log.w(error) { "No access to $uri" }
        null
    }

    private fun displayName(uri: Uri): String {
        val queried = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: RuntimeException) {
            null
        }
        return FileNames.displayName(queried, uri.lastPathSegment)
    }

    companion object {
        private val log = Arbor.tag("FilePicker")

        private const val ANY_TYPE = "*/*"

        /** The cache folder of the photos (see `res/xml/superdro_file_paths.xml`). */
        const val PHOTO_FOLDER: String = "photos"

        /** The cache folder of the shared files (see `res/xml/superdro_file_paths.xml`). */
        const val SHARE_FOLDER: String = "share"

        /** The authority of the `FileProvider` of the app manifest. */
        fun authorityOf(packageName: String): String = "$packageName.files"
    }
}

/** The file name rules of the picker with no Android type, so that a JVM unit test can examine them. */
object FileNames {

    private val unsafe = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /** Gives [name] with each character that a file system can refuse replaced by `_`. A blank name gives "file". */
    fun safeName(name: String): String = name.trim().replace(unsafe, "_").ifBlank { "file" }

    /** Gives the name to show: the display name of the provider, else the last path segment, else "file". */
    fun displayName(queried: String?, lastPathSegment: String?): String =
        queried?.takeIf { it.isNotBlank() } ?: lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"

    /** Gives the name of a photo file of the time [nowMs]. */
    fun photoName(nowMs: Long): String = "photo-$nowMs.jpg"
}
