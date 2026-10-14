package org.superdro.core.ui.settings.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.RecordSink
import org.superdro.core.connection.RecordedChunk
import org.superdro.core.connection.RecordedStream
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.model.AdapterAddress
import org.superdro.core.ui.testing.FixedWallClock

/** The copy of the recorded byte streams that the log screen exports. */
class RecordedStreamsTest {

    /** A platform recorder that keeps the chunks of each sink. */
    private class PlatformRecorder(private val gives: Boolean = true) : StreamRecorder {
        val written = mutableListOf<RecordedChunk>()
        var closed = 0
        val opened = mutableListOf<Triple<DeviceId, AdapterAddress, Int>>()
        override fun open(device: DeviceId, address: AdapterAddress, link: Int): RecordSink? {
            opened += Triple(device, address, link)
            if (!gives) return null
            return object : RecordSink {
                override fun write(chunk: RecordedChunk) {
                    written += chunk
                }

                override fun close() {
                    closed++
                }
            }
        }
    }

    private val mill = DeviceId("mill")
    private val address = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3")

    @Test
    fun theTeeWritesToThePlatformAndKeepsTheSameChunks() {
        val platform = PlatformRecorder()
        val streams = RecordedStreams(platform, FixedWallClock())
        val sink = streams.open(mill, address, link = 1)
        val chunks = listOf(RecordedChunk(0L, "X1200;".encodeToByteArray()), RecordedChunk(250L, "@3E8;".encodeToByteArray()))
        chunks.forEach(sink::write)
        assertEquals(chunks, platform.written)
        val link = streams.links.value.single()
        assertEquals(mill, link.device)
        assertEquals(1, link.link)
        assertEquals(2, link.chunkCount)
        assertEquals(11L, link.byteCount)
        assertTrue(link.open)
        assertEquals(RecordedStream.format(chunks), streams.export(link.id))
        assertEquals("# SuperDRO byte stream 1\n0 58313230303b\n250 403345383b\n", streams.export(link.id))
        sink.close()
        assertEquals(1, platform.closed)
        assertEquals(false, streams.links.value.single().open)
    }

    @Test
    fun theCopyOfOneLinkStopsAtTheCapAndThePlatformGetsEachChunk() {
        val platform = PlatformRecorder()
        val streams = RecordedStreams(platform, FixedWallClock(), maxBytesPerLink = 5)
        val sink = streams.open(mill, address, link = 3)
        sink.write(RecordedChunk(0L, byteArrayOf(1, 2, 3)))
        sink.write(RecordedChunk(10L, byteArrayOf(4, 5, 6)))
        sink.write(RecordedChunk(20L, byteArrayOf(7)))
        val link = streams.links.value.single()
        assertEquals(1, link.chunkCount)
        assertEquals(3L, link.byteCount)
        assertTrue(link.truncated)
        assertEquals(3, platform.written.size)
        assertEquals(listOf(0L), streams.chunks(link.id).map { it.timeMs })
    }

    @Test
    fun oneBatchGivesOneUpdateOfTheLinksAndStopsAtTheCap() {
        val platform = PlatformRecorder()
        val streams = RecordedStreams(platform, FixedWallClock(), maxBytesPerLink = 5)
        val sink = streams.open(mill, address, link = 1)
        val updates = ArrayList<List<RecordedLink>>()
        updates += streams.links.value
        val chunks = listOf(RecordedChunk(0L, byteArrayOf(1, 2)), RecordedChunk(10L, byteArrayOf(3, 4)), RecordedChunk(20L, byteArrayOf(5, 6)))
        sink.writeAll(chunks)
        assertTrue(streams.links.value !== updates.single(), "the batch gives an update")
        val link = streams.links.value.single()
        assertEquals(2, link.chunkCount)
        assertEquals(4L, link.byteCount)
        assertTrue(link.truncated)
        assertEquals(chunks, platform.written)
        assertEquals(listOf(0L, 10L), streams.chunks(link.id).map { it.timeMs })
        // After the cap, a batch gives no update.
        val last = streams.links.value
        sink.writeAll(listOf(RecordedChunk(30L, byteArrayOf(7))))
        assertTrue(streams.links.value === last)
    }

    @Test
    fun theAppKeepsTheNewestRecordsAndAlsoRecordsWithNoPlatformFile() {
        val platform = PlatformRecorder(gives = false)
        val streams = RecordedStreams(platform, FixedWallClock(), maxLinks = 2)
        (1..3).forEach { number -> streams.open(mill, address, link = number).write(RecordedChunk(0L, byteArrayOf(number.toByte()))) }
        assertEquals(listOf(3, 2), streams.links.value.map { it.link })
        assertEquals(3, platform.opened.size)
        val oldest = streams.links.value.last()
        assertEquals("# SuperDRO byte stream 1\n0 02\n", streams.export(oldest.id))
        assertNull(streams.export(1L))
    }
}
