package org.superdro.core.ui.settings.logs

import kotlin.jvm.Synchronized
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.RecordSink
import org.superdro.core.connection.RecordedChunk
import org.superdro.core.connection.RecordedStream
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.model.AdapterAddress

/**
 * One recorded link of a session.
 *
 * @property id The number of the record in this process.
 * @property link The number of the link of the session: 1 for the first link.
 * @property byteCount The bytes of the chunks in the copy of the app.
 * @property open True while the link is open and the session writes chunks.
 * @property truncated True when the copy of the app stopped at [RecordedStreams.maxBytesPerLink].
 *   The file of the platform has the full stream.
 */
data class RecordedLink(
    val id: Long,
    val device: DeviceId,
    val link: Int,
    val address: AdapterAddress,
    val startedOn: Instant,
    val chunkCount: Int,
    val byteCount: Long,
    val open: Boolean,
    val truncated: Boolean,
)

/**
 * The record of the byte streams. The connection
 * manager opens a sink for each link of a session that has `DeviceRef.recordStream`. This
 * [StreamRecorder] gives the sink of the platform ([delegate], the file in the app files) and
 * keeps a copy of the same chunks in memory. Common code cannot list or read the files of the
 * platform, so the log screen exports the copy ([export]). The text is the same as the file:
 * [RecordedStream.format].
 *
 * Limits: the copy of one link keeps the first [maxBytesPerLink] bytes (the start of a link has
 * the start queries). The app keeps the newest [maxLinks] records. The sessions write from their
 * own threads, so each function is synchronized.
 *
 * @param clock Gives the start time of each record.
 */
class RecordedStreams(
    private val delegate: StreamRecorder = StreamRecorder.None,
    private val clock: Clock = Clock.System,
    val maxBytesPerLink: Long = DEFAULT_MAX_BYTES_PER_LINK,
    val maxLinks: Int = DEFAULT_MAX_LINKS,
) : StreamRecorder {

    private class Record(var info: RecordedLink) {
        val chunks = ArrayList<RecordedChunk>()
    }

    private var counter = 0L
    private val records = ArrayList<Record>()
    private val mutableLinks = MutableStateFlow<List<RecordedLink>>(emptyList())

    /** The recorded links, newest first. */
    val links: StateFlow<List<RecordedLink>> = mutableLinks.asStateFlow()

    @Synchronized
    override fun open(device: DeviceId, address: AdapterAddress, link: Int): RecordSink {
        val inner = delegate.open(device, address, link)
        val record = Record(RecordedLink(++counter, device, link, address, clock.now(), 0, 0L, open = true, truncated = false))
        records += record
        while (records.size > maxLinks) records.removeAt(0)
        publish()
        return object : RecordSink {
            override fun write(chunk: RecordedChunk) {
                inner?.write(chunk)
                append(record, chunk)
            }

            override fun close() {
                inner?.close()
                finish(record)
            }
        }
    }

    /** Gives the text of the record [id] in the form of [RecordedStream], or null when the app has no such record. */
    @Synchronized
    fun export(id: Long): String? = records.firstOrNull { record -> record.info.id == id }?.let { record ->
        RecordedStream.format(record.chunks.toList())
    }

    /** Gives the chunks of the record [id], or an empty list. */
    @Synchronized
    fun chunks(id: Long): List<RecordedChunk> = records.firstOrNull { record -> record.info.id == id }?.chunks?.toList().orEmpty()

    @Synchronized
    private fun append(record: Record, chunk: RecordedChunk) {
        val info = record.info
        if (info.truncated) return
        if (info.byteCount + chunk.bytes.size > maxBytesPerLink) {
            record.info = info.copy(truncated = true)
        } else {
            record.chunks += chunk
            record.info = info.copy(chunkCount = info.chunkCount + 1, byteCount = info.byteCount + chunk.bytes.size)
        }
        publish()
    }

    @Synchronized
    private fun finish(record: Record) {
        record.info = record.info.copy(open = false)
        publish()
    }

    private fun publish() {
        mutableLinks.value = records.map { record -> record.info }.asReversed()
    }

    companion object {
        /** 4 MB of chunks for each link. */
        const val DEFAULT_MAX_BYTES_PER_LINK: Long = 4L * 1024L * 1024L

        /** The app keeps the 10 newest records. */
        const val DEFAULT_MAX_LINKS: Int = 10
    }
}
