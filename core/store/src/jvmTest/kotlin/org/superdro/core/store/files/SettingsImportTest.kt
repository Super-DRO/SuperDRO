package org.superdro.core.store.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.ProbeContactType
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.TachometerConfig
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.Tool
import org.superdro.core.store.files.settings.ProfileFile
import org.superdro.core.store.files.settings.SettingsImporter
import org.superdro.core.store.files.settings.SettingsTarget

/** The `.tdset` import. */
class SettingsImportTest {

    private val importer = SettingsImporter(now = { FIXED_TIME })

    private fun tdset(settings: String, header: String = ""): String =
        """{"touchdro": "settings", "format": 1, $header "settings": {$settings}}"""

    private fun texts(problems: List<ImportProblem>): List<String> = problems.map { it.describe() }

    @Test
    fun theTransferListHas123KeysAndTheExampleUsesOnlyThem() {
        val keys = org.superdro.core.store.files.settings.TdsetKeys.TRANSFER.map { it.name }
        assertEquals(123, keys.size)
        assertEquals(123, keys.toSet().size)
        assertTrue(keys.none(org.superdro.core.store.files.settings.TdsetKeys::isInternal))
        val example = kotlinx.serialization.json.Json.parseToJsonElement(Fixtures.text("settings-3.32.00.tdset"))
        val fileKeys = (example as kotlinx.serialization.json.JsonObject)["settings"].let { it as kotlinx.serialization.json.JsonObject }.keys
        assertEquals(103, fileKeys.size)
        assertTrue(keys.containsAll(fileKeys))
    }

    @Test
    fun theExampleOf33200GivesANewProfileWithTheDefaults() {
        val preview = importer.read(Fixtures.bytes("settings-3.32.00.tdset"), SettingsTarget.NewProfile)
        assertTrue(preview.usable)
        assertEquals(FileSource.TouchDro, preview.source)
        assertEquals("Bridgeport", preview.suggestedName)
        assertEquals("2026-10-11 14:30:05", preview.exported)
        assertEquals(103, preview.rows.size)
        val profile = preview.profile("Bridgeport")
        assertEquals("Bridgeport", profile.name)
        val expected = MachineProfile.create(MachineType.VerticalMill).let { it.copy(display = it.display.copy(readoutFont = ReadoutFont.System)) }
        assertEquals(ProfileFile.settingsOf(expected), ProfileFile.settingsOf(profile))
        // Only display_font changes the defaults (`2` = the system font).
        assertEquals(listOf("display_font"), preview.rows.filter { it.action == RowAction.Update }.map { it.key })
        assertEquals(
            listOf("bluetooth_connection_enabled", "usb_connection_enabled", "wifi_connection_enabled", "wifi_ip_address", "wifi_port"),
            preview.rows.filter { it.action == RowAction.Skip }.map { it.key },
        )
        assertEquals(98, preview.preview.toImport.size)
        assertEquals(5, preview.preview.skipped.size)
    }

    @Test
    fun eachKeyKindAppliesItsRule() {
        val preview = importer.read(Fixtures.bytes("settings-kinds.tdset"), SettingsTarget.NewProfile)
        assertTrue(preview.usable, preview.problems.toString())
        // Each run of CR or LF in the profile name gives one space.
        assertEquals("Old Lathe Shop", preview.suggestedName)
        val profile = preview.profile("Lathe 2")
        assertEquals(MachineType.Lathe, profile.machineType)
        assertTrue(profile.metricDefault)
        assertEquals(ReadoutFormat.Format5, profile.display.readoutFormat)
        assertEquals(ReadoutFont.PlatformMonospace, profile.display.readoutFont)
        // A lathe always has the legacy pattern angle convention.
        assertEquals(PatternAngleConvention.Legacy, profile.patternAngleConvention)
        // The imported 9600 stays 9600.
        assertEquals(9600, profile.usbBaudRate)
        assertEquals(AdapterAddress.Tcp("192.168.4.1", 8080), profile.adapter)
        // A text "true" is a boolean only for a boolean setting.
        assertTrue(profile.x.enabled)
        assertEquals("true", profile.x.label)
        // The letter A gives the default input, B turns the secondary input off.
        assertEquals('x', profile.x.primary.channel)
        assertNull(profile.x.secondary)
        assertEquals("Y", profile.y.label)
        assertEquals('y', profile.y.primary.channel)
        assertEquals(AuxiliaryType.CompoundSlide, profile.auxiliary.type)
        assertEquals(0, profile.auxiliary.angleReferenceDegrees)
        assertNull(profile.z.secondary)
        assertEquals(AxisButtonMode.Hold, profile.z.buttons[0].mode)
        assertEquals(AxisButtonMode.ToggleIncremental, profile.z.buttons[1].mode)
        assertEquals(TachometerConfig.DEFAULT_PRESETS, profile.tachometer.presets)
        assertEquals(TachometerMode.Direct, profile.tachometer.mode)
        assertTrue(profile.caliper.enabled)
        assertEquals('u', profile.caliper.input.channel)
        assertFalse(profile.functionButtons.arcCut)
        assertTrue(profile.probe.enabled)
        assertEquals('p', profile.probe.channel)

        assertEquals(
            listOf(
                "“70000” is not a valid value for “wifi_port”. The import uses the default value “8080”.",
                "Input A of “x_prefix” gives no data. The import uses the default input “x”.",
                "Input B of “x_prefix_secondary” gives no data. The import turns the secondary input off.",
                "“true” is not a valid value for “y_label”. The import uses the default value “Y”.",
                "“45” is not a valid value for “y_angle_reference”. The import uses the default value “0”.",
                "“9” is not a valid value for “z_button_2_mode”. The import uses the default value “1”.",
                "“100 two 300” is not a valid value for “tach_presets”. The import uses the default value “105 160 250 585 898 1400”.",
                "Input R of “probe_prefix” gives no data. The import uses the default input “p”.",
            ),
            texts(preview.report.defaults),
        )
        assertEquals(
            listOf(
                "“wifi_connection_enabled” is not a profile setting in SuperDRO. The link types are app settings.",
                "SuperDRO has no setting for “tach_button_1_mode”. The import ignores it.",
                "SuperDRO has no setting for “caliper_label”. The import ignores it.",
                "SuperDRO has no setting for “show_calculators”. The import ignores it.",
                "“c_state_stylus_diameter” is internal state, not a setting. The import ignores it.",
                "“g_state_installation_id” is internal state, not a setting. The import ignores it.",
                "“camera_keep_overlays” is not a setting that SuperDRO can import.",
            ),
            texts(preview.report.notImported),
        )
        assertEquals(
            listOf(
                "The primary input and the secondary input of “z_prefix_secondary” are the same (Z). The import turns the secondary input off.",
                "The file has no zero points and no workspace state. Set the zero points again after the import.",
            ),
            texts(preview.report.warnings),
        )
    }

    @Test
    fun theReportTellsWhereTheProbeValuesGo() {
        val preview = importer.read(Fixtures.bytes("settings-kinds.tdset"), SettingsTarget.NewProfile)
        assertEquals(
            listOf(
                "“probe_enable” = true goes to the probe settings of this machine profile.",
                "“probe_prefix” = r goes to the probe settings of this machine profile.",
                "“probe_invert” = true goes to the probe settings of the adapter profile.",
                "“probe_debounce_seconds” = 0.05 goes to the probe settings of the adapter profile.",
                "“probe_x_offset_compensation_microns” = -12.5 goes to a probe profile in the probe library.",
                "“probe_edge_finder_sample_count” = 3 goes to a probe profile in the probe library.",
            ),
            preview.report.probe.map { ImportTexts.English.text(it) },
        )
        assertEquals(ProbeContactType.NormallyClosed, preview.probeValues.contactType)
        assertEquals(50L, preview.probeValues.debounceMs)
        assertEquals(-12.5, preview.probeValues.xOffsetUm)
        assertEquals(3, preview.probeValues.sampleCount)
        assertNull(preview.probeValues.spreadLimitUm)
    }

    @Test
    fun aValueThatIsNotValidGetsTheDefaultAndIsNotWritten() {
        val preview = importer.read(tdset(""""usb_baud_rate": "4800", "machine_type": "7""""), SettingsTarget.NewProfile)
        assertEquals(115_200, preview.profile("A").usbBaudRate)
        assertEquals(MachineType.VerticalMill, preview.profile("A").machineType)
        assertEquals(
            listOf(
                "“4800” is not a valid value for “usb_baud_rate”. The import uses the default value “115200”.",
                "“7” is not a valid value for “machine_type”. The import uses the default value “0”.",
            ),
            texts(preview.report.defaults),
        )
    }

    @Test
    fun theDisplayFormats014Give235() {
        listOf("0" to ReadoutFormat.Format2, "1" to ReadoutFormat.Format3, "4" to ReadoutFormat.Format5, "5" to ReadoutFormat.Format5).forEach { (code, format) ->
            val preview = importer.read(tdset(""""display_format": "$code""""), SettingsTarget.NewProfile)
            assertEquals(format, preview.profile("A").display.readoutFormat, code)
        }
    }

    @Test
    fun legacyAngleDatumGivesThePatternAngleConventionOfAMill() {
        assertEquals(PatternAngleConvention.Legacy, importer.read(tdset(""""legacy_angle_datum": true"""), SettingsTarget.NewProfile).profile("A").patternAngleConvention)
        assertEquals(PatternAngleConvention.Standard, importer.read(tdset(""""legacy_angle_datum": "false""""), SettingsTarget.NewProfile).profile("A").patternAngleConvention)
    }

    @Test
    fun anOverwriteKeepsTheDataAndTheNameOfTheSelectedProfile() {
        val selected = MachineProfile.create(MachineType.VerticalMill, name = "My Mill").copy(
            id = "p1",
            adapter = AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60, serial = "A1"),
            tools = listOf(Tool(id = "t1", name = "Drill")),
        )
        val preview = importer.read(
            tdset(""""usb_baud_rate": "9600", "use_metric_mode": true, "machine_type": 2""", """"profile": "Bridgeport","""),
            SettingsTarget.SelectedProfile(selected),
        )
        assertEquals("My Mill", preview.suggestedName)
        val profile = preview.profile(preview.suggestedName!!)
        assertEquals("p1", profile.id)
        assertEquals("My Mill", profile.name)
        assertEquals(selected.workspaces, profile.workspaces)
        assertEquals(selected.tools, profile.tools)
        assertEquals(AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60, serial = "A1", baudRate = 9600), profile.adapter)
        assertEquals(9600, profile.usbBaudRate)
        assertTrue(profile.metricDefault)
        assertEquals(MachineType.HorizontalMill, profile.machineType)
        assertTrue("The machine type changes from VERTICAL_MILL to HORIZONTAL_MILL." in texts(preview.report.warnings))
        assertEquals(listOf("usb_baud_rate", "use_metric_mode", "machine_type"), preview.rows.filter { it.action == RowAction.Update }.map { it.key })
        assertEquals(3, preview.changeCount)
    }

    @Test
    fun theInputConflictCheckGivesWarnings() {
        val shared = importer.read(tdset(""""x_prefix": "y", "y_prefix": "y""""), SettingsTarget.NewProfile)
        assertTrue("“x_prefix” and “y_prefix” both use input Y." in texts(shared.report.warnings))
        val none = importer.read(tdset(""""x_enable": false, "y_enable": false, "z_enable": false"""), SettingsTarget.NewProfile)
        assertTrue("No output axis is enabled. The import enables X." in texts(none.report.warnings))
        assertTrue(none.profile("A").x.enabled)
    }

    @Test
    fun theFileTypeIsTestedByTheContent() {
        assertEquals(listOf("The file contains “tools”. This import needs “settings”."), texts(importer.read(Fixtures.bytes("tools-3.25.03-mill.csv"), SettingsTarget.NewProfile).problems))
        assertEquals(listOf("The file contains “adapter-profile”. This import needs “settings”."), texts(importer.read(Fixtures.bytes("adapter-3.32.00.tdap"), SettingsTarget.NewProfile).problems))
        assertEquals(listOf("A newer version made this file (format 2). Update SuperDRO to import it."), texts(importer.read("""{"touchdro": "settings", "format": 2}""", SettingsTarget.NewProfile).problems))
        assertEquals(listOf("The app cannot read the file as JSON."), texts(importer.read("hello", SettingsTarget.NewProfile).problems))
        assertEquals(listOf("The app does not know the type of this file."), texts(importer.read("""{"format": 1}""", SettingsTarget.NewProfile).problems))
        assertEquals(listOf("The file has no setting that the import can use."), texts(importer.read(tdset(""""g_state_x": 1"""), SettingsTarget.NewProfile).problems.filter { it.severity == Severity.Error }))
        assertFalse(importer.read(tdset(""""g_state_x": 1"""), SettingsTarget.NewProfile).usable)
    }

    @Test
    fun theReportHasATitleASummaryAndSections() {
        val preview = importer.read(tdset(""""usb_baud_rate": "4800", "show_calculators": true, "probe_invert": true"""), SettingsTarget.NewProfile)
        val expected = """
            Import report
            =============

            Imported: 1. Not imported: 1. Warnings: 2.

            --------------------------------------------------------
            Items that the import did not map

            SuperDRO has no setting for “show_calculators”. The import ignores it.

            --------------------------------------------------------
            Values that got the default

            “4800” is not a valid value for “usb_baud_rate”. The import uses the default value “115200”.

            --------------------------------------------------------
            Where the probe values go

            “probe_invert” = true goes to the probe settings of the adapter profile.

            --------------------------------------------------------
            Other warnings

            The file has no zero points and no workspace state. Set the zero points again after the import.

        """.trimIndent()
        assertEquals(expected, preview.report.render().toText())
        val file = preview.report.render().exportFile(Report.IMPORT_REPORT_KIND, "Bridgeport", CONTEXT)
        assertEquals("superdro-import_report-Bridgeport-20261011_143005.txt", file.fileName)
        assertEquals("text/plain", file.mimeType)
        assertEquals(expected, file.text)
    }
}
