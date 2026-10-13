package org.superdro.core.store.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AuxiliaryConfig
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.Tool
import org.superdro.core.store.files.settings.ProfileFile
import org.superdro.core.store.files.settings.ProfileFileImporter
import org.superdro.core.store.files.settings.SettingsTarget

/** The SuperDRO profile file. */
class ProfileFileTest {

    private val importer = ProfileFileImporter(now = { FIXED_TIME })

    /** A lathe profile with a value that is not the default in each group of settings. */
    private val original: MachineProfile = MachineProfile.create(MachineType.Lathe, name = "South Bend").let { profile ->
        profile.copy(
            metricDefault = true,
            usbBaudRate = 9600,
            adapter = AdapterAddress.Tcp("dro.local", 23),
            useSharedToolLibrary = true,
            muteSounds = true,
            twoWayCommunication = false,
            sidePanel = SidePanelMode.Tools,
            use3dSubDatums = false,
            display = profile.display.copy(readoutFormat = ReadoutFormat.Format5, readoutFont = ReadoutFont.JetBrainsMono, shortPressZero = true),
            functionButtons = profile.functionButtons.copy(arcCut = false, headTilt = false),
            x = profile.x.copy(label = "Xd", secondary = InputConfig('w', sign = -1)),
            y = profile.y.copy(enabled = false),
            auxiliary = AuxiliaryConfig(AuxiliaryType.CompoundSlide, vectoring = true, angleReferenceDegrees = 90),
            tachometer = profile.tachometer.copy(enabled = true, presets = listOf(100.0, 200.0)),
            caliper = profile.caliper.copy(enabled = true, input = InputConfig('u')),
            probe = profile.probe.copy(enabled = true, channel = 'q'),
            dataOutput = profile.dataOutput.copy(enabled = true),
            stylusDiameterUm = 4000.0,
            tools = listOf(Tool(name = "Not in the file")),
        )
    }

    @Test
    fun theFileHasTheHeaderAndAllSettingsButNoIdentityAndNoData() {
        val file = ProfileFile.export(original, CONTEXT)
        assertEquals("superdro-profile-South_Bend-20261011_143005.json", file.fileName)
        assertEquals("application/json", file.mimeType)
        val root = Json.parseToJsonElement(file.text).jsonObject
        assertEquals(listOf("superdro", "format", "app", "exported", "profile", "machine", "settings"), root.keys.toList())
        assertEquals("profile", root["superdro"]!!.jsonPrimitive.content)
        assertEquals("1", root["format"]!!.jsonPrimitive.content)
        assertEquals("SuperDRO 1.0.0", root["app"]!!.jsonPrimitive.content)
        assertEquals("2026-10-11 14:30:05", root["exported"]!!.jsonPrimitive.content)
        assertEquals("South Bend", root["profile"]!!.jsonPrimitive.content)
        assertEquals("LATHE", root["machine"]!!.jsonPrimitive.content)
        val settings = root["settings"] as JsonObject
        ProfileFile.EXCLUDED.forEach { key -> assertFalse(key in settings, key) }
        assertTrue("usbBaudRate" in settings)
        assertTrue("probe" in settings)
        assertFalse("device" in file.text)
        assertTrue(file.text.startsWith("{\n  \"superdro\": \"profile\""))
    }

    @Test
    fun aNewProfileFromTheFileHasTheSameSettings() {
        val preview = importer.read(ProfileFile.export(original, CONTEXT).bytes(), SettingsTarget.NewProfile)
        assertTrue(preview.usable, preview.problems.toString())
        assertEquals(FileSource.SuperDro, preview.source)
        assertEquals("South Bend", preview.suggestedName)
        val profile = preview.profile("South Bend 2")
        assertEquals("South Bend 2", profile.name)
        assertEquals(ProfileFile.settingsOf(original), ProfileFile.settingsOf(profile))
        assertTrue(profile.tools.isEmpty())
        assertFalse(profile.id == original.id)
        assertEquals(listOf("“probe” = {\"enabled\":true,\"channel\":\"q\",\"edgeFinderEnabled\":true,\"toolSetterEnabled\":false} goes to the probe settings of this machine profile."), preview.report.probe.map { ImportTexts.English.text(it) })
    }

    @Test
    fun theSettingsOfASelectedProfileChangeAndItsDataStays() {
        val selected = MachineProfile.create(MachineType.VerticalMill, name = "Old").copy(id = "p9", tools = listOf(Tool(id = "t1", name = "Drill")))
        val preview = importer.read(ProfileFile.export(original, CONTEXT).text, SettingsTarget.SelectedProfile(selected))
        val profile = preview.profile(preview.suggestedName!!)
        assertEquals("p9", profile.id)
        assertEquals("Old", profile.name)
        assertEquals(selected.tools, profile.tools)
        assertEquals(selected.workspaces, profile.workspaces)
        assertEquals(ProfileFile.settingsOf(original), ProfileFile.settingsOf(profile))
        assertTrue("The machine type changes from VERTICAL_MILL to LATHE." in preview.report.warnings.map { it.describe() })
    }

    @Test
    fun aFieldThatIsNotValidGetsTheDefaultWithAWarning() {
        val text = """
            {"superdro": "profile", "format": 1, "settings": {
              "machineType": "VerticalMill",
              "usbBaudRate": 4800,
              "metricDefault": true,
              "x": {"primary": {"channel": "a"}},
              "y": {"label": true},
              "auxiliary": {"angleReferenceDegrees": 45},
              "probe": {"channel": "r"},
              "display": "bad",
              "id": "zzz",
              "colour": "red"
            }}
        """.trimIndent()
        val preview = importer.read(text, SettingsTarget.NewProfile)
        val profile = preview.profile("A")
        val defaults = MachineProfile.create(MachineType.VerticalMill)
        assertTrue(profile.metricDefault)
        assertEquals(defaults.usbBaudRate, profile.usbBaudRate)
        assertEquals(defaults.x, profile.x)
        assertEquals(defaults.y, profile.y)
        assertEquals(defaults.auxiliary, profile.auxiliary)
        assertEquals(defaults.probe, profile.probe)
        assertEquals(defaults.display, profile.display)
        assertEquals(
            listOf("usbBaudRate", "x", "y", "auxiliary", "probe", "display").map { key -> "The value of “$key” is not valid. The import uses the default value." },
            preview.report.defaults.map { it.describe() },
        )
        assertEquals(
            listOf("“id” is not a setting of this SuperDRO version. The import ignores it.", "“colour” is not a setting of this SuperDRO version. The import ignores it."),
            preview.report.notImported.map { it.describe() },
        )
    }

    @Test
    fun aSettingsFileIsNotAProfileFile() {
        assertEquals(
            listOf("The file contains “settings”. This import needs “profile”."),
            importer.read(Fixtures.bytes("settings-3.32.00.tdset"), SettingsTarget.NewProfile).problems.map { it.describe() },
        )
    }
}
