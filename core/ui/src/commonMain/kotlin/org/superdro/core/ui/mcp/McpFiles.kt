package org.superdro.core.ui.mcp

import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.superdro.core.dataoutput.secureRandomBytes
import org.superdro.core.mcp.McpFileStore
import org.superdro.core.mcp.McpLimits
import org.superdro.core.mcp.StoredFile
import org.superdro.core.mcp.UploadReceipt
import org.superdro.core.store.files.Sha256
import org.superdro.core.store.files.StagedFile
import org.superdro.core.store.files.StagingFiles
import org.superdro.core.util.Bytes

private val log = Arbor.tag("McpFiles")

/**
 * The [McpFileStore] of the app on two [StagingFiles]: the uploads under [UPLOADS] and the
 * downloads under [DOWNLOADS]. A file ID has 32 hexadecimal digits from 16 random bytes. A file
 * expires after [McpLimits.FILE_TTL_MS]; [startExpiry] removes the old files each [EXPIRY_PERIOD_MS].
 *
 * @param random Gives random bytes. The production value is `secureRandomBytes`.
 */
class McpFiles(
    private val uploads: StagingFiles,
    private val downloads: StagingFiles,
    private val random: (Int) -> ByteArray = ::secureRandomBytes,
) : McpFileStore {

    override suspend fun putUpload(contentType: String, bytes: ByteArray): UploadReceipt {
        val id = newId()
        uploads.put(id, contentType, null, bytes)
        return UploadReceipt(id, bytes.size.toLong(), Bytes.toHex(Sha256.digest(bytes)))
    }

    override suspend fun upload(fileId: String): StoredFile? = stored(uploads, fileId)

    override suspend fun putDownload(fileName: String, contentType: String, bytes: ByteArray): String {
        val id = newId()
        downloads.put(id, contentType, fileName, bytes)
        return id
    }

    override suspend fun download(fileId: String): StoredFile? = stored(downloads, fileId)

    /** Removes each upload and each download that is older than [McpLimits.FILE_TTL_MS]. Gives the count. */
    suspend fun expire(): Int = uploads.expire(McpLimits.FILE_TTL_MS) + downloads.expire(McpLimits.FILE_TTL_MS)

    /** Starts the expiry in [scope]: [expire] at once and then each [periodMs]. */
    fun startExpiry(scope: CoroutineScope, periodMs: Long = EXPIRY_PERIOD_MS): Job = scope.launch {
        while (true) {
            try {
                expire()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log.w(exception) { "The expiry of the MCP files failed" }
            }
            delay(periodMs)
        }
    }

    private fun newId(): String = Bytes.toHex(random(ID_BYTES))

    private suspend fun stored(files: StagingFiles, fileId: String): StoredFile? {
        if (!ID.matches(fileId)) return null
        val file: StagedFile = files.get(fileId) ?: return null
        return StoredFile(file.id, file.contentType, file.bytes)
    }

    companion object {
        /** The store folder of the uploads. */
        const val UPLOADS: String = "mcp/uploads"

        /** The store folder of the downloads. */
        const val DOWNLOADS: String = "mcp/downloads"

        /** The number of random bytes of a file ID. */
        const val ID_BYTES: Int = 16

        /** The time between two expiry runs. */
        const val EXPIRY_PERIOD_MS: Long = 10 * 60 * 1000L

        private val ID = Regex("[A-Za-z0-9_-]+")
    }
}
