package org.superdro.core.util

/**
 * Opens the file pickers, the camera app, and the share sheet of the platform (a platform contract).
 * On Android, it uses `ActivityResultContracts.OpenDocument`, `CreateDocument`,
 * `MediaStore.ACTION_IMAGE_CAPTURE` with a `FileProvider` URI, and the share sheet.
 *
 * Each function suspends until the user closes the system screen. A function gives null or false when
 * the user cancels, or when no activity is visible.
 */
interface FilePicker {

    /**
     * Lets the user select a file. [mimeTypes] limits the list, for example `text/csv`.
     * Gives the name and the content of the file, or null when the user cancels.
     */
    suspend fun openDocument(mimeTypes: List<String>): PickedFile?

    /**
     * Lets the user select a location for a new file with the name [suggestedName], then writes
     * [content] to it. Gives true when the file is written.
     */
    suspend fun createDocument(suggestedName: String, mimeType: String, content: ByteArray): Boolean

    /**
     * Starts the camera app for one photo (the reference image). On Android, the
     * implementation asks for `CAMERA` first when the app declares it.
     * Gives the JPEG bytes, or null when the user cancels.
     */
    suspend fun capturePhoto(): PickedFile?

    /** Opens the share sheet with one file, for example a log export. Gives true when the sheet opens. */
    suspend fun share(name: String, mimeType: String, content: ByteArray): Boolean
}

/** One file that the user selected: its display name and its content. */
class PickedFile(val name: String, val content: ByteArray)

/** A [FilePicker] that does nothing, for the tests and for a platform with no file picker. */
object NoFilePicker : FilePicker {
    override suspend fun openDocument(mimeTypes: List<String>): PickedFile? = null
    override suspend fun createDocument(suggestedName: String, mimeType: String, content: ByteArray): Boolean = false
    override suspend fun capturePhoto(): PickedFile? = null
    override suspend fun share(name: String, mimeType: String, content: ByteArray): Boolean = false
}
