package org.superdro.core.store.files

import com.toxicbakery.logging.Arbor
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.readEntry
import org.superdro.core.store.storeIoDispatcher
import org.superdro.core.store.writeAtomically

/**
 * One staged file.
 *
 * @property atMs The wall clock time of [StagingFiles.put], in milliseconds since 1970-01-01 00:00 UTC.
 */
data class StagedFile(
    val id: String,
    val contentType: String,
    val fileName: String?,
    val atMs: Long,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is StagedFile && id == other.id && contentType == other.contentType && fileName == other.fileName &&
            atMs == other.atMs && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = id.hashCode()
}

/**
 * The files that wait between the MCP server and the agent: the uploads under `mcp/uploads` and
 * the downloads under `mcp/downloads`.
 *
 * The bytes of a file are in `<folder>/<id>`. A JSON sidecar `<folder>/<id>.json` holds the
 * content type, the file name, and the time. The index `<folder>/.index.json` holds the ID and
 * the time of each file, so that [expire] finds the old files with no list of the folder. Each
 * write is atomic. A [Mutex] serializes the operations of one object. Make one object for each
 * folder.
 *
 * An ID has only the characters `A` to `Z`, `a` to `z`, `0` to `9`, `-`, and `_`. Another ID is
 * refused with an [IllegalArgumentException], so that an ID cannot name a file outside of the
 * folder.
 *
 * @param folder The store folder, for example `mcp/uploads`.
 * @param clock The wall clock of the file times.
 * @param dispatcher The dispatcher of the file IO.
 */
class StagingFiles(
    private val io: StoreIOProvider,
    private val folder: String,
    private val clock: Clock = Clock.System,
    private val dispatcher: CoroutineDispatcher = storeIoDispatcher,
) {
    private val lock = Mutex()

    /** Writes the file [id]. A file with the same ID is replaced. */
    suspend fun put(id: String, contentType: String, fileName: String?, bytes: ByteArray) {
        checkId(id)
        val meta = Sidecar(contentType, fileName, clock.now().toEpochMilliseconds())
        withContext(dispatcher) {
            lock.withLock {
                io.writeAtomically(bytesName(id)) { sink -> sink.write(bytes) }
                io.writeAtomically(sidecarName(id)) { sink -> sink.writeString(JSON.encodeToString(Sidecar.serializer(), meta)) }
                writeIndex(readIndex() + (id to meta.atMs))
            }
        }
    }

    /** Gives the file [id], or null when it is not there. */
    suspend fun get(id: String): StagedFile? {
        checkId(id)
        return withContext(dispatcher) {
            lock.withLock {
                val meta = readSidecar(id) ?: return@withLock null
                if (!io.exists(bytesName(id))) return@withLock null
                val bytes = io.readEntry(bytesName(id)) { source -> source.readByteArray() }
                StagedFile(id, meta.contentType, meta.fileName, meta.atMs, bytes)
            }
        }
    }

    /** Removes the file [id] and its sidecar. A file that is not there gives no error. */
    suspend fun delete(id: String) {
        checkId(id)
        withContext(dispatcher) {
            lock.withLock {
                remove(id)
                writeIndex(readIndex() - id)
            }
        }
    }

    /**
     * Removes each file whose age is [ttlMs] or more.
     *
     * @return The number of removed files.
     */
    suspend fun expire(ttlMs: Long): Int = withContext(dispatcher) {
        lock.withLock {
            val now = clock.now().toEpochMilliseconds()
            val index = readIndex()
            val expired = index.filterValues { atMs -> now - atMs >= ttlMs }.keys
            expired.forEach(::remove)
            if (expired.isNotEmpty()) writeIndex(index - expired)
            expired.size
        }
    }

    private fun remove(id: String) {
        io.delete(bytesName(id))
        io.delete(sidecarName(id))
    }

    private fun readSidecar(id: String): Sidecar? {
        val name = sidecarName(id)
        if (!io.exists(name)) return null
        return try {
            JSON.decodeFromString(Sidecar.serializer(), io.readEntry(name) { source -> source.readString() })
        } catch (error: Exception) {
            log.w(error) { "Cannot read the sidecar $name." }
            null
        }
    }

    /** The index: the ID and the time of each file. A missing or bad index gives an empty map. */
    private fun readIndex(): Map<String, Long> {
        val name = indexName()
        if (!io.exists(name)) return emptyMap()
        return try {
            JSON.decodeFromString(Index.serializer(), io.readEntry(name) { source -> source.readString() }).files
        } catch (error: Exception) {
            log.w(error) { "Cannot read the index $name. The index starts again." }
            emptyMap()
        }
    }

    private fun writeIndex(files: Map<String, Long>) {
        io.writeAtomically(indexName()) { sink -> sink.writeString(JSON.encodeToString(Index.serializer(), Index(files))) }
    }

    private fun bytesName(id: String): String = "$folder/$id"

    private fun sidecarName(id: String): String = "$folder/$id.json"

    private fun indexName(): String = "$folder/$INDEX_FILE"

    @Serializable
    private data class Sidecar(val contentType: String, val fileName: String?, val atMs: Long)

    @Serializable
    private data class Index(val files: Map<String, Long>)

    companion object {
        /** The name of the index in the folder. An ID cannot start with a dot, so the names do not collide. */
        const val INDEX_FILE: String = ".index.json"

        private val ID = Regex("[A-Za-z0-9_-]+")

        private val JSON = Json { ignoreUnknownKeys = true }

        private val log = Arbor.tag("StagingFiles")

        /** Throws [IllegalArgumentException] when [id] is not a valid staging ID. */
        fun checkId(id: String) {
            require(ID.matches(id)) { "The ID \"$id\" is not valid. Use only letters, digits, '-', and '_'." }
        }
    }
}
