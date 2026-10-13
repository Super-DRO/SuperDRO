package org.superdro.core.store.files

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.files.settings.ProfileFile
import org.w3c.dom.Element

/** The type test by the content and the texts of the string resources. */
class ImportFileDetectorTest {

    @Test
    fun theTypeComesFromTheContentNotFromTheFileName() {
        val expected = mapOf(
            "subdatums-3.25.03-mill-inch.csv" to DetectedFile(ImportFileKind.SubDatumCsv, null),
            "subdatums-format2-mill.csv" to DetectedFile(ImportFileKind.SubDatumCsv, FileSource.TouchDro),
            "subdatums-bom-comma-lathe.csv" to DetectedFile(ImportFileKind.SubDatumCsv, null),
            "tools-3.25.03-mill.csv" to DetectedFile(ImportFileKind.ToolCsv, null),
            "tools-3.25.03-lathe-8values.csv" to DetectedFile(ImportFileKind.ToolCsv, null),
            "tools-format2-lathe.csv" to DetectedFile(ImportFileKind.ToolCsv, FileSource.TouchDro),
            "settings-3.32.00.tdset" to DetectedFile(ImportFileKind.Settings, FileSource.TouchDro),
            "adapter-3.32.00.tdap" to DetectedFile(ImportFileKind.AdapterProfile, FileSource.TouchDro),
            "probes-3.32.00.tdprb" to DetectedFile(ImportFileKind.ProbeProfiles, FileSource.TouchDro),
        )
        expected.forEach { (name, kind) -> assertEquals(kind, ImportFileDetector.detect(Fixtures.bytes(name)), name) }
        val profile = ProfileFile.export(MachineProfile.create(), CONTEXT)
        assertEquals(DetectedFile(ImportFileKind.Profile, FileSource.SuperDro), ImportFileDetector.detect(profile.text))
        assertEquals(DetectedFile(ImportFileKind.Unknown, null), ImportFileDetector.detect("just some words"))
        assertEquals(DetectedFile(ImportFileKind.Unknown, null), ImportFileDetector.detect("{not json"))
    }

    @Test
    fun theEnglishTextsAreTheTextsOfTheStringResources() {
        val file = File("../resources/src/commonMain/composeResources/values/strings_files.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val strings = (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
        assertEquals(MessageKey.entries.associate { key -> key.resourceName to key.english }, strings)
    }
}
