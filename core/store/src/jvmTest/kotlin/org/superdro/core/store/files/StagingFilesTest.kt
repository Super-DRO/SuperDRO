package org.superdro.core.store.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.TestWallClock

/** The staging files on the JVM file system. */
class StagingFilesTest {

    private val directory: File = Files.createTempDirectory("superdro-staging").toFile()
    private val io = StoreIOProviderJvm(baseDir = File(directory, "data"))
    private val clock = TestWallClock()
    private val files = StagingFiles(io, "mcp/uploads", clock, Dispatchers.IO)

    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun aPutWritesTheBytesAndTheSidecarAndAGetReadsThem() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        files.put("f1", "image/png", "part.png", bytes)
        assertTrue(File(directory, "data/mcp/uploads/f1").isFile)
        val sidecar = File(directory, "data/mcp/uploads/f1.json").readText()
        assertEquals("""{"contentType":"image/png","fileName":"part.png","atMs":${clock.now().toEpochMilliseconds()}}""", sidecar)

        val staged = files.get("f1")!!
        assertEquals("f1", staged.id)
        assertEquals("image/png", staged.contentType)
        assertEquals("part.png", staged.fileName)
        assertEquals(clock.now().toEpochMilliseconds(), staged.atMs)
        assertContentEquals(bytes, staged.bytes)
        assertNull(files.get("missing"))
    }

    @Test
    fun aSecondPutReplacesTheFileAndADeleteRemovesIt() = runTest {
        files.put("f1", "text/csv", null, "a,b\n".encodeToByteArray())
        clock.advance(1.minutes)
        files.put("f1", "application/json", "x.json", "{}".encodeToByteArray())
        val staged = files.get("f1")!!
        assertEquals("application/json", staged.contentType)
        assertEquals("x.json", staged.fileName)
        assertEquals("{}", staged.bytes.decodeToString())

        files.delete("f1")
        assertNull(files.get("f1"))
        assertFalse(File(directory, "data/mcp/uploads/f1").exists())
        assertFalse(File(directory, "data/mcp/uploads/f1.json").exists())
        // A second delete gives no error.
        files.delete("f1")
    }

    @Test
    fun expireRemovesTheOldFilesAndKeepsTheNewFiles() = runTest {
        files.put("old", "text/csv", null, byteArrayOf(1))
        clock.advance(30.minutes)
        files.put("mid", "text/csv", null, byteArrayOf(2))
        clock.advance(30.minutes)
        files.put("new", "text/csv", null, byteArrayOf(3))

        assertEquals(1, files.expire(ttlMs = 60.minutes.inWholeMilliseconds))
        assertNull(files.get("old"))
        assertEquals(2.toByte(), files.get("mid")!!.bytes.single())
        assertEquals(3.toByte(), files.get("new")!!.bytes.single())
        assertEquals(0, files.expire(ttlMs = 60.minutes.inWholeMilliseconds))

        clock.advance(31.minutes)
        assertEquals(1, files.expire(ttlMs = 60.minutes.inWholeMilliseconds))
        assertNull(files.get("mid"))
        assertEquals(listOf(".index.json", "new", "new.json"), File(directory, "data/mcp/uploads").list()!!.sorted())
    }

    @Test
    fun aNewObjectOnTheSameFolderFindsTheFilesOfTheOldObject() = runTest {
        files.put("f1", "text/csv", null, byteArrayOf(1))
        clock.advance(2.minutes)
        val again = StagingFiles(io, "mcp/uploads", clock, Dispatchers.IO)
        assertEquals(1.toByte(), again.get("f1")!!.bytes.single())
        assertEquals(1, again.expire(ttlMs = 1.minutes.inWholeMilliseconds))
        assertNull(files.get("f1"))
    }

    @Test
    fun anIdThatCanLeaveTheFolderIsRefused() = runTest {
        listOf("", "../x", "a/b", "a\\b", ".index", "a.json", "a b").forEach { id ->
            assertFailsWith<IllegalArgumentException>(id) { files.put(id, "text/csv", null, byteArrayOf()) }
            assertFailsWith<IllegalArgumentException>(id) { files.get(id) }
            assertFailsWith<IllegalArgumentException>(id) { files.delete(id) }
        }
        files.put("Ab-9_z", "text/csv", null, byteArrayOf())
        assertEquals(0, files.get("Ab-9_z")!!.bytes.size)
    }

    @Test
    fun twoFoldersDoNotShareTheirFiles() = runTest {
        val downloads = StagingFiles(io, "mcp/downloads", clock, Dispatchers.IO)
        files.put("f1", "text/csv", null, byteArrayOf(1))
        downloads.put("f1", "application/dxf", "part.dxf", byteArrayOf(2))
        assertEquals(1.toByte(), files.get("f1")!!.bytes.single())
        assertEquals(2.toByte(), downloads.get("f1")!!.bytes.single())
        downloads.delete("f1")
        assertEquals(1.toByte(), files.get("f1")!!.bytes.single())
    }
}
