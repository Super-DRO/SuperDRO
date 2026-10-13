package org.superdro.core.connection

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.superdro.core.model.AdapterAddress

/**
 * The record files of the debug option. The Android app and the desktop
 * app use the one recorder of the source set `jvmAndAndroidMain`.
 */
class FileStreamRecorderTest {

    @Test
    fun eachLinkGetsOneFileInTheTextFormatOfTheReplay() {
        val directory = Files.createTempDirectory("streams").toFile().resolve(FileStreamRecorder.FOLDER)
        val recorder = FileStreamRecorder(directory, nowMs = { 1_760_000_000_000L })
        val sink = assertNotNull(recorder.open(DeviceId("mill"), AdapterAddress.Fake("DemoMill"), link = 2))
        val chunks = listOf(
            RecordedChunk(0L, "X1200;".encodeToByteArray()),
            RecordedChunk(250L, "@3E8;".encodeToByteArray()),
        )
        chunks.forEach(sink::write)
        sink.close()

        val file = directory.resolve("mill-1760000000000-2.txt")
        assertEquals(RecordedStream.format(chunks), file.readText())
        assertEquals(chunks, RecordedStream.parse(file.readText()))
        directory.parentFile?.deleteRecursively()
    }

    @Test
    fun theFileNameHasOnlySafeCharacters() {
        assertEquals("a_b_c-5-1.txt", FileStreamRecorder.fileName(DeviceId("a/b:c"), link = 1, nowMs = 5L))
        assertEquals("0f3e-9b.x_y-7-3.txt", FileStreamRecorder.fileName(DeviceId("0f3e-9b.x_y"), link = 3, nowMs = 7L))
    }

    @Test
    fun aFolderThatCannotBeMadeGivesNoRecord() {
        val file = Files.createTempFile("not-a-folder", ".txt").toFile()
        val recorder = FileStreamRecorder(file.resolve("streams"))
        assertNull(recorder.open(DeviceId("mill"), AdapterAddress.Fake("DemoMill"), link = 1))
        file.delete()
    }
}
