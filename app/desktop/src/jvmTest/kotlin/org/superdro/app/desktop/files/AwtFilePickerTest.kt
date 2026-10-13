package org.superdro.app.desktop.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The desktop file picker with fake dialogs, and the extensions of the MIME types. */
class AwtFilePickerTest {

    private val root: File = Files.createTempDirectory("superdro-picker").toFile()

    private class FakeDialogs(var open: File? = null, var save: File? = null) : FileDialogs {
        val openRequests = mutableListOf<Set<String>>()
        val saveRequests = mutableListOf<String>()

        override fun chooseOpen(extensions: Set<String>): File? {
            openRequests += extensions
            return open
        }

        override fun chooseSave(suggestedName: String): File? {
            saveRequests += suggestedName
            return save
        }
    }

    @AfterTest
    fun deleteRoot() {
        root.deleteRecursively()
    }

    @Test
    fun theMimeTypesGiveTheExtensionsOfTheDialog() {
        assertEquals(setOf("csv"), FileTypes.extensionsOf(listOf("text/csv")))
        assertEquals(setOf("csv", "txt", "log"), FileTypes.extensionsOf(listOf("text/csv", "text/plain")))
        assertEquals(setOf("tdap"), FileTypes.extensionsOf(listOf("application/x-touchdro-adapter-profile")))
        assertEquals(setOf("tdprb"), FileTypes.extensionsOf(listOf("application/x-touchdro-probe-profiles")))
        assertEquals(setOf("png", "jpg", "jpeg", "bmp", "gif", "webp"), FileTypes.extensionsOf(listOf("image/*")))
        assertEquals(setOf("dxf"), FileTypes.extensionsOf(listOf("application/dxf", "image/vnd.dxf")))
        // A type that the table does not have, or all types: the dialog shows all files.
        assertEquals(emptySet(), FileTypes.extensionsOf(listOf("text/csv", "application/octet-stream")))
        assertEquals(emptySet(), FileTypes.extensionsOf(listOf("*/*")))
        assertEquals(emptySet(), FileTypes.extensionsOf(emptyList()))

        assertTrue(FileTypes.accepts("tools.CSV", setOf("csv")))
        assertFalse(FileTypes.accepts("tools.csv.bak", setOf("csv")))
        assertFalse(FileTypes.accepts("README", setOf("csv")))
        assertTrue(FileTypes.accepts("README", emptySet()))
    }

    @Test
    fun anOpenGivesTheNameAndTheBytesOfTheFile() = runBlocking {
        val file = File(root, "tools.csv").apply { writeText("tool,diameter\n1,6.0\n") }
        val dialogs = FakeDialogs(open = file)
        val picker = AwtFilePicker(dialogs)
        val picked = picker.openDocument(listOf("text/csv"))!!
        assertEquals("tools.csv", picked.name)
        assertContentEquals(file.readBytes(), picked.content)
        assertEquals(listOf(setOf("csv")), dialogs.openRequests)

        dialogs.open = null
        assertNull(picker.openDocument(listOf("text/csv")))
        dialogs.open = File(root, "gone.csv")
        assertNull(picker.openDocument(listOf("text/csv")))
    }

    @Test
    fun aCreateAndAShareWriteTheFileOfTheSaveDialog() = runBlocking {
        val target = File(root, "export.csv")
        val dialogs = FakeDialogs(save = target)
        val picker = AwtFilePicker(dialogs)
        assertTrue(picker.createDocument("superdro-tools-mill-20261012_120000.csv", "text/csv", "a,b\n".encodeToByteArray()))
        assertEquals("a,b\n", target.readText())
        assertTrue(picker.share("superdro.log", "text/plain", "log".encodeToByteArray()))
        assertEquals("log", target.readText())
        assertEquals(listOf("superdro-tools-mill-20261012_120000.csv", "superdro.log"), dialogs.saveRequests)

        dialogs.save = null
        assertFalse(picker.createDocument("x.csv", "text/csv", ByteArray(0)))
        dialogs.save = File(root, "no-such-folder/x.csv")
        assertFalse(picker.createDocument("x.csv", "text/csv", ByteArray(0)))
    }

    @Test
    fun theDesktopHasNoCameraApp() = runBlocking {
        assertNull(AwtFilePicker(FakeDialogs()).capturePhoto())
    }
}
