package org.superdro.core.mcp

/** The receipt of an upload: the ID of the file, its size, and its SHA-256 as 64 hex digits. */
data class UploadReceipt(val fileId: String, val bytes: Long, val sha256Hex: String)

/** One stored file. Two files are equal when the ID, the type, and the bytes are equal. */
data class StoredFile(val fileId: String, val contentType: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is StoredFile && other.fileId == fileId && other.contentType == contentType && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = (fileId.hashCode() * 31 + contentType.hashCode()) * 31 + bytes.contentHashCode()
}

/**
 * The files of the MCP server: the uploads of the clients (`POST /upload`) and the downloads
 * that a tool made (`GET /download/{fileId}`). A file expires after [McpLimits.FILE_TTL_MS].
 */
interface McpFileStore {
    /** Keeps an upload and gives its receipt. */
    suspend fun putUpload(contentType: String, bytes: ByteArray): UploadReceipt

    /** Gives the upload, or null when it is not there or expired. */
    suspend fun upload(fileId: String): StoredFile?

    /** Keeps a download for a client and gives its file ID. */
    suspend fun putDownload(fileName: String, contentType: String, bytes: ByteArray): String

    /** Gives the download, or null when it is not there or expired. */
    suspend fun download(fileId: String): StoredFile?
}
