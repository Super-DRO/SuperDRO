package org.superdro.app.desktop.files

import com.toxicbakery.logging.Arbor
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.superdro.core.util.FilePicker
import org.superdro.core.util.PickedFile

/** The file dialogs of the system. [AwtFileDialogs] is the implementation of the app. A test gives a fake. */
interface FileDialogs {

    /** Shows an open dialog for the files with one of [extensions] (all files for an empty set). Gives the file, or null when the user cancels. */
    fun chooseOpen(extensions: Set<String>): File?

    /** Shows a save dialog with the name [suggestedName]. Gives the file, or null when the user cancels. */
    fun chooseSave(suggestedName: String): File?
}

/**
 * The file dialogs of the AWT `FileDialog`: the native dialog of the system (GTK on Linux, the
 * dialog of Windows and of macOS). A dialog is modal: the call blocks until the user closes it, so
 * call it on an IO thread. The dialog itself runs on the AWT event thread.
 *
 * Windows does not apply a filename filter. There the dialog shows the pattern `*.csv;*.txt` in
 * the name field.
 *
 * @param parent Gives the window of the app, the parent of the dialog.
 */
class AwtFileDialogs(
    private val parent: () -> Frame?,
    private val windows: Boolean = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows"),
) : FileDialogs {

    override fun chooseOpen(extensions: Set<String>): File? = show(FileDialog.LOAD, null, extensions)

    override fun chooseSave(suggestedName: String): File? = show(FileDialog.SAVE, suggestedName, emptySet())

    private fun show(mode: Int, name: String?, extensions: Set<String>): File? {
        var result: File? = null
        EventQueue.invokeAndWait {
            val dialog = FileDialog(parent(), null, mode)
            if (extensions.isNotEmpty()) {
                dialog.setFilenameFilter { _, fileName -> FileTypes.accepts(fileName, extensions) }
                if (windows) dialog.file = extensions.joinToString(";") { extension -> "*.$extension" }
            }
            if (name != null) dialog.file = name
            dialog.isVisible = true
            val chosen = dialog.file
            val directory = dialog.directory
            result = if (chosen != null && directory != null) File(directory, chosen) else null
            dialog.dispose()
        }
        return result
    }
}

/**
 * The desktop [FilePicker] with the AWT `FileDialog`. One request runs at a time.
 *
 * - [openDocument]: an open dialog with the extensions of the MIME types ([FileTypes]). It gives
 *   the name and the bytes of the file.
 * - [createDocument]: a save dialog with the suggested name. It writes the bytes.
 * - [capturePhoto]: the desktop has no camera app: null. The user can open an image file
 *   with [openDocument].
 * - [share]: the desktop has no share sheet. A save dialog writes the file (for example a log
 *   export).
 *
 * @param dialogs The file dialogs.
 * @param io The dispatcher of the dialogs and of the file access.
 */
class AwtFilePicker(
    private val dialogs: FileDialogs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FilePicker {

    /** The picker of the app with the AWT dialogs on the window of [parent]. */
    constructor(parent: () -> Frame?) : this(AwtFileDialogs(parent))

    private val mutex = Mutex()

    override suspend fun openDocument(mimeTypes: List<String>): PickedFile? = mutex.withLock {
        withContext(io) {
            val file = dialogs.chooseOpen(FileTypes.extensionsOf(mimeTypes)) ?: return@withContext null
            try {
                PickedFile(file.name, file.readBytes())
            } catch (error: IOException) {
                log.w(error) { "Cannot read ${file.absolutePath}" }
                null
            }
        }
    }

    override suspend fun createDocument(suggestedName: String, mimeType: String, content: ByteArray): Boolean = mutex.withLock {
        save(suggestedName, content)
    }

    override suspend fun capturePhoto(): PickedFile? {
        log.i { "The desktop has no camera app. Open an image file instead." }
        return null
    }

    override suspend fun share(name: String, mimeType: String, content: ByteArray): Boolean = mutex.withLock {
        save(name, content)
    }

    private suspend fun save(suggestedName: String, content: ByteArray): Boolean = withContext(io) {
        val file = dialogs.chooseSave(suggestedName) ?: return@withContext false
        try {
            file.writeBytes(content)
            true
        } catch (error: IOException) {
            log.w(error) { "Cannot write ${file.absolutePath}" }
            false
        }
    }

    private companion object {
        val log = Arbor.tag("AwtFilePicker")
    }
}

/** The file name extensions of the MIME types that SuperDRO asks for. */
object FileTypes {

    private val IMAGES = setOf("png", "jpg", "jpeg", "bmp", "gif", "webp")

    private val TABLE: Map<String, Set<String>> = mapOf(
        "text/csv" to setOf("csv"),
        "text/comma-separated-values" to setOf("csv"),
        "application/json" to setOf("json"),
        "text/plain" to setOf("txt", "log"),
        "application/x-touchdro-adapter-profile" to setOf("tdap"),
        "application/x-touchdro-probe-profiles" to setOf("tdprb"),
        "application/x-touchdro-settings" to setOf("tdset"),
        "application/dxf" to setOf("dxf"),
        "application/x-dxf" to setOf("dxf"),
        "image/vnd.dxf" to setOf("dxf"),
        "image/x-dxf" to setOf("dxf"),
        "image/png" to setOf("png"),
        "image/jpeg" to setOf("jpg", "jpeg"),
        "image/*" to IMAGES,
    )

    /**
     * Gives the extensions of [mimeTypes] in lower case. An empty list, `*` + `/` + `*`, or a type that the
     * table does not have gives the empty set: the dialog then shows all files, so that it never
     * hides a valid file.
     */
    fun extensionsOf(mimeTypes: List<String>): Set<String> {
        if (mimeTypes.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        for (type in mimeTypes) {
            val extensions = TABLE[type.trim().lowercase()] ?: return emptySet()
            result += extensions
        }
        return result
    }

    /** True when [fileName] has one of [extensions], in any case. An empty set accepts each file. */
    fun accepts(fileName: String, extensions: Set<String>): Boolean =
        extensions.isEmpty() || fileName.substringAfterLast('.', "").lowercase() in extensions
}
