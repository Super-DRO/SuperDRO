package org.superdro.core.store

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.writeString

class StoreIOProviderJvmTest {

    private val directory: File = Files.createTempDirectory("superdro-store").toFile()
    private val provider = StoreIOProviderJvm(baseDir = File(directory, "data"))

    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun aWriteThenAMoveGivesTheNewFile() {
        provider.sinkFor("tabs.json.tmp").buffered().use { sink -> sink.writeString("""{"schemaVersion":1}""") }
        provider.move("tabs.json.tmp", "tabs.json")
        assertFalse(provider.exists("tabs.json.tmp"))
        assertTrue(provider.exists("tabs.json"))
        assertEquals("""{"schemaVersion":1}""", provider.sourceFor("tabs.json").buffered().use { source -> source.readString() })
    }

    @Test
    fun aStoreNameCanHaveAFolder() {
        provider.sinkFor("wizard/a.json.tmp").buffered().use { sink -> sink.writeString("{}") }
        provider.move("wizard/a.json.tmp", "images/a.json")
        assertTrue(File(directory, "data/images/a.json").isFile)
        assertEquals("{}", provider.sourceFor("images/a.json").buffered().use { source -> source.readString() })
        provider.delete("images/a.json")
        assertFalse(provider.exists("images/a.json"))
    }

    @Test
    fun deleteDoesNotThrowForAMissingFile() {
        provider.delete("missing.json")
        assertFalse(provider.exists("missing.json"))
    }

    @Test
    fun theLinuxDirectoryFollowsXdg() {
        assertEquals(File("/data/superdro"), appDataDirectory("Linux", { name -> if (name == "XDG_DATA_HOME") "/data" else null }, "/home/u"))
        assertEquals(File("/home/u/.local/share/superdro"), appDataDirectory("Linux", { null }, "/home/u"))
        assertEquals(File("/Users/u/Library/Application Support/SuperDRO"), appDataDirectory("Mac OS X", { null }, "/Users/u"))
    }
}
