package org.superdro.validation.store

import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.TachometerMode
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.ImportFileDetector
import org.superdro.core.store.files.ImportFileKind
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.Severity
import org.superdro.core.store.files.settings.ProfileFile
import org.superdro.core.store.files.settings.ProfileFileImporter
import org.superdro.core.store.files.settings.SettingsImportPreview
import org.superdro.core.store.files.settings.SettingsImporter
import org.superdro.core.store.files.settings.SettingsTarget
import org.superdro.core.store.files.tool.ToolCsvImporter
import org.superdro.core.store.files.tool.ToolImportContext
import org.superdro.core.store.valueOrNull

/**
 * The import of the `.tdset` file and of the SuperDRO profile file. The fixture is a `.tdset`
 * example file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFilesTest {

    private val importer = SettingsImporter()
    private val harnesses = mutableListOf<StoreHarness>()

    private fun TestScope.store(): StoreHarness = StoreHarness(StandardTestDispatcher(testScheduler)).also(harnesses::add)

    @AfterTest
    fun closeAll() {
        harnesses.forEach(StoreHarness::close)
    }

    /** A `.tdset` file with only [settings]. */
    private fun tdset(settings: String, header: String = ""): String =
        """{"touchdro":"settings","format":1$header,"settings":{$settings}}"""

    private fun newProfile(settings: String): MachineProfile {
        val preview = importer.read(tdset(settings), SettingsTarget.NewProfile)
        assertTrue(preview.usable, "problems ${preview.problems}")
        return preview.profile("Imported")
    }

    private fun warningsFor(preview: SettingsImportPreview, key: String) =
        preview.rows.single { it.key == key }.problems.filter { it.severity == Severity.Warning }

    @Test
    fun import_of_the_tdset_example_of_settings_calibration_16_8_4() {
        val preview = importer.read(StoreFixtures.text("settings-calibration-16.8.4.tdset"), SettingsTarget.NewProfile)
        assertTrue(preview.usable, "problems ${preview.problems}")
        assertEquals(FileSource.TouchDro, preview.source)
        assertEquals("Bridgeport", preview.fileProfileName)
        assertEquals("Bridgeport", preview.suggestedName)
        assertEquals("2026-10-11 14:30:05", preview.exported)
        val profile = preview.profile("Bridgeport")
        val defaults = MachineProfile.create(MachineType.VerticalMill)
        assertEquals("Bridgeport", profile.name)
        assertEquals(MachineType.VerticalMill, profile.machineType)
        assertFalse(profile.metricDefault)
        assertFalse(profile.useSharedToolLibrary)
        assertEquals(ReadoutFormat.Format3, profile.display.readoutFormat)
        // Display_font 2 is the system default font.
        assertEquals(ReadoutFont.System, profile.display.readoutFont)
        assertEquals(defaults.display.copy(readoutFont = ReadoutFont.System), profile.display)
        assertFalse(profile.muteSounds)
        assertEquals(PatternAngleConvention.Standard, profile.patternAngleConvention)
        assertEquals(115_200, profile.usbBaudRate)
        assertNull(profile.adapter, "the file has no Wi-Fi host")
        assertTrue(profile.twoWayCommunication)
        assertFalse(profile.dataOutput.enabled)
        assertEquals(defaults.x, profile.x)
        assertEquals(defaults.y, profile.y)
        assertEquals(defaults.z, profile.z)
        assertEquals(defaults.angular, profile.angular)
        assertEquals(defaults.caliper, profile.caliper)
        assertEquals(defaults.tachometer, profile.tachometer)
        assertEquals(defaults.functionButtons, profile.functionButtons)
        assertEquals(defaults.auxiliary, profile.auxiliary)
    }

    @Test
    fun each_of_the_123_keys_of_the_transfer_list_is_a_known_key() {
        val keys = TRANSFER_KEYS
        assertEquals(123, keys.size)
        val settings = keys.joinToString(",") { key -> "\"$key\":${validValue(key)}" }
        val preview = importer.read(tdset(settings), SettingsTarget.NewProfile)
        assertTrue(preview.usable)
        val unknown = preview.rows.filter { row -> row.problems.any { it.message.key == MessageKey.SettingUnknown || it.message.key == MessageKey.SettingInternal } }
        assertEquals(emptyList(), unknown.map { it.key })
        assertEquals(keys.toSet(), preview.rows.map { it.key }.toSet())
    }

    @Test
    fun display_format_0_1_4_give_2_3_5() {
        assertEquals(ReadoutFormat.Format2, newProfile("\"display_format\":\"0\"").display.readoutFormat)
        assertEquals(ReadoutFormat.Format3, newProfile("\"display_format\":\"1\"").display.readoutFormat)
        assertEquals(ReadoutFormat.Format5, newProfile("\"display_format\":\"4\"").display.readoutFormat)
        assertEquals(ReadoutFormat.Format2, newProfile("\"display_format\":\"2\"").display.readoutFormat)
        assertEquals(ReadoutFormat.Format5, newProfile("\"display_format\":\"5\"").display.readoutFormat)
    }

    @Test
    fun a_text_true_or_false_gives_a_boolean_only_for_a_boolean_setting() {
        val profile = newProfile("\"x_label\":\"true\",\"y_label\":\"false\",\"x_enable\":\"false\",\"show_sliders\":\"FALSE\"")
        assertEquals("true", profile.x.label)
        assertEquals("false", profile.y.label)
        assertFalse(profile.x.enabled)
        assertFalse(profile.display.showSliders)
        // A JSON boolean for a text setting is not valid: the default and a warning.
        val preview = importer.read(tdset("\"z_label\":true"), SettingsTarget.NewProfile)
        assertEquals("Z", preview.profile("P").z.label)
        assertTrue(warningsFor(preview, "z_label").isNotEmpty())
    }

    @Test
    fun an_imported_baud_rate_of_9600_does_not_change() = runTest {
        val profile = newProfile("\"usb_baud_rate\":\"9600\"")
        assertEquals(9600, profile.usbBaudRate)
        val store = store()
        val stored = assertNotNull(store.machines.createProfile(profile).valueOrNull())
        assertEquals(9600, store.machines.loadProfile(stored.id)!!.usbBaudRate)
    }

    @Test
    fun a_value_that_is_not_valid_gets_the_default_and_a_warning() {
        val cases = listOf(
            Triple("usb_baud_rate", "\"4800\"") { p: MachineProfile -> assertEquals(115_200, p.usbBaudRate) },
            Triple("tach_mode", "\"5\"") { p: MachineProfile -> assertEquals(TachometerMode.Sensor, p.tachometer.mode) },
            Triple("y_type", "\"sidecar\"") { p: MachineProfile -> assertEquals(AuxiliaryType.Tailstock, p.auxiliary.type) },
            Triple("y_angle_reference", "\"45\"") { p: MachineProfile -> assertEquals(0, p.auxiliary.angleReferenceDegrees) },
            Triple("display_format", "\"9\"") { p: MachineProfile -> assertEquals(ReadoutFormat.Format3, p.display.readoutFormat) },
            Triple("x_button_1_mode", "\"8\"") { p: MachineProfile -> assertEquals(0, p.x.buttons[0].mode.ordinal) },
            Triple("show_sliders", "\"perhaps\"") { p: MachineProfile -> assertTrue(p.display.showSliders) },
        )
        cases.forEach { (key, value, check) ->
            val preview = importer.read(tdset("\"$key\":$value"), SettingsTarget.NewProfile)
            check(preview.profile("P"))
            assertTrue(warningsFor(preview, key).isNotEmpty(), "a warning for $key")
            assertTrue(preview.report.defaults.isNotEmpty(), "the report lists the default of $key")
        }
        // The Wi-Fi port of a TCP link: a value outside 1 to 65535 gives 8080.
        val wifi = importer.read(
            tdset("\"wifi_connection_enabled\":true,\"wifi_ip_address\":\"192.168.1.20\",\"wifi_port\":\"70000\""),
            SettingsTarget.NewProfile,
        )
        val address = kotlin.test.assertIs<org.superdro.core.model.AdapterAddress.Tcp>(wifi.profile("P").adapter)
        assertEquals("192.168.1.20", address.host)
        assertEquals(8080, address.port)
        assertTrue(warningsFor(wifi, "wifi_port").isNotEmpty())
    }

    @Test
    fun legacy_angle_datum_gives_the_pattern_angle_convention_and_a_lathe_is_legacy() {
        assertEquals(PatternAngleConvention.Legacy, newProfile("\"legacy_angle_datum\":true").patternAngleConvention)
        assertEquals(PatternAngleConvention.Standard, newProfile("\"legacy_angle_datum\":false").patternAngleConvention)
        val lathe = newProfile("\"machine_type\":\"1\",\"legacy_angle_datum\":false")
        assertEquals(MachineType.Lathe, lathe.machineType)
        assertEquals(PatternAngleConvention.Legacy, lathe.patternAngleConvention)
    }

    @Test
    fun a_letter_a_to_f_or_a_probe_letter_r_or_s_is_not_valid() {
        val preview = importer.read(
            tdset(
                "\"x_prefix\":\"a\",\"angular_prefix\":\"f\",\"caliper_prefix\":\"C\",\"probe_prefix\":\"r\"," +
                    "\"z_secondary_enable\":true,\"z_prefix_secondary\":\"b\",\"y_prefix\":\"U\"",
            ),
            SettingsTarget.NewProfile,
        )
        val profile = preview.profile("P")
        assertEquals('x', profile.x.primary.channel)
        assertEquals('w', profile.angular.primary.channel)
        assertEquals('w', profile.caliper.input.channel)
        assertEquals('p', profile.probe.channel)
        assertNull(profile.z.secondary, "a secondary input with a letter that is not valid: none")
        assertEquals('u', profile.y.primary.channel, "the wire letters are not case-sensitive")
        listOf("x_prefix", "angular_prefix", "caliper_prefix", "probe_prefix", "z_prefix_secondary").forEach { key ->
            assertTrue(warningsFor(preview, key).isNotEmpty(), "a warning for $key")
        }
        assertEquals('q', newProfile("\"probe_prefix\":\"q\"").probe.channel)
    }

    @Test
    fun internal_and_license_keys_are_ignored_and_the_report_lists_the_items_not_mapped() {
        val preview = importer.read(
            tdset(
                "\"g_state_plus_version_expiration\":\"2027-01-01\",\"c_state_default_device_id\":\"7\"," +
                    "\"bluetooth_connection_enabled\":true,\"show_calculators\":true,\"made_up_key\":\"1\",\"x_label\":\"Q\"",
                header = ",\"device\":\"0b6f2c1e-5d3a-4c8e-9f12-7a4b3c2d1e0f\",\"profile\":\"P1\"",
            ),
            SettingsTarget.NewProfile,
        )
        assertTrue(preview.usable)
        assertEquals("Q", preview.profile("P").x.label)
        val skipped = preview.preview.skipped.map { it.label }.toSet()
        listOf("g_state_plus_version_expiration", "c_state_default_device_id", "bluetooth_connection_enabled", "show_calculators", "made_up_key")
            .forEach { key -> assertTrue(key in skipped, "$key is skipped") }
        val report = preview.report.render().toText()
        listOf("g_state_plus_version_expiration", "c_state_default_device_id", "bluetooth_connection_enabled", "show_calculators", "made_up_key")
            .forEach { key -> assertTrue(key in report, "the report names $key") }
        assertFalse("0b6f2c1e" in report)
        assertTrue(preview.preview.toImport.any { it.label == "x_label" })
    }

    @Test
    fun the_type_of_a_file_comes_from_its_content() {
        assertEquals(ImportFileKind.Settings, ImportFileDetector.detect(StoreFixtures.text("settings-calibration-16.8.4.tdset")).kind)
        assertEquals(ImportFileKind.AdapterProfile, ImportFileDetector.detect(StoreFixtures.text("error-compensation-11.1.tdap")).kind)
        assertEquals(ImportFileKind.ProbeProfiles, ImportFileDetector.detect(StoreFixtures.text("probe-edge-finder-13.12.1.tdprb")).kind)
        assertEquals(ImportFileKind.ToolCsv, ImportFileDetector.detect(StoreFixtures.text("data-storage-9.2-mill.csv")).kind)
        assertEquals(ImportFileKind.ToolCsv, ImportFileDetector.detect(StoreFixtures.text("data-storage-14.13.4-lathe-export.csv")).kind)
        assertEquals(ImportFileKind.SubDatumCsv, ImportFileDetector.detect(StoreFixtures.text("data-storage-6.6-mill-inch.csv")).kind)
        assertEquals(ImportFileKind.SubDatumCsv, ImportFileDetector.detect(StoreFixtures.text("data-storage-6.6-lathe-mm.csv")).kind)
        val profileFile = ProfileFile.export(MachineProfile.create(), StoreFixtures.exportContext)
        assertEquals(ImportFileKind.Profile, ImportFileDetector.detect(profileFile.text).kind)
        assertEquals(FileSource.SuperDro, ImportFileDetector.detect(profileFile.text).source)
        // A settings file that goes to the tool import is refused: the content decides, not the name.
        val tools = ToolCsvImporter().read(StoreFixtures.text("settings-calibration-16.8.4.tdset"), ToolImportContext("p", MachineType.VerticalMill))
        assertFalse(tools.usable)
        // A tool file that goes to the settings import is refused.
        assertFalse(importer.read(StoreFixtures.text("data-storage-9.2-mill.csv"), SettingsTarget.NewProfile).usable)
    }

    @Test
    fun a_settings_file_of_a_newer_format_or_of_a_different_kind_is_refused() {
        assertFalse(importer.read("""{"touchdro":"settings","format":2,"settings":{"x_label":"A"}}""", SettingsTarget.NewProfile).usable)
        assertTrue(importer.read("""{"touchdro":"settings","settings":{"x_label":"A"}}""", SettingsTarget.NewProfile).usable, "no format means 1")
        assertFalse(importer.read(StoreFixtures.text("error-compensation-11.1.tdap"), SettingsTarget.NewProfile).usable)
        assertFalse(importer.read("""{"settings":{"x_label":"A"}}""", SettingsTarget.NewProfile).usable, "no touchdro key")
    }

    @Test
    fun the_preview_has_the_lists_to_import_skipped_and_warnings() {
        val preview = importer.read(tdset("\"x_label\":\"A\",\"made_up_key\":\"1\",\"usb_baud_rate\":\"4800\""), SettingsTarget.NewProfile)
        val lists = preview.preview
        assertTrue(lists.toImport.any { it.label == "x_label" })
        assertTrue(lists.skipped.any { it.label == "made_up_key" })
        assertTrue(lists.warnings.isNotEmpty())
    }

    @Test
    fun an_import_into_the_selected_profile_keeps_the_name_that_the_user_selects() {
        val current = MachineProfile.create(MachineType.VerticalMill, "My Mill")
        val preview = importer.read(StoreFixtures.text("settings-calibration-16.8.4.tdset"), SettingsTarget.SelectedProfile(current))
        assertEquals("My Mill", preview.suggestedName)
        val result = preview.profile("My Mill")
        assertEquals("My Mill", result.name)
        assertEquals(current.id, result.id)
        assertEquals(current.workspaces, result.workspaces, "the workspaces of the profile stay")
        // Only changed values count as changes.
        assertTrue(preview.rows.filter { it.key == "x_label" }.all { it.action == RowAction.Unchanged })
    }

    @Test
    fun the_superdro_profile_file_has_no_device_and_no_workspace_data() {
        val profile = MachineProfile.create(MachineType.VerticalMill, "Bridgeport #2")
        val file = ProfileFile.export(profile, StoreFixtures.exportContext)
        assertEquals("superdro-profile-Bridgeport_2-20261011_143005.json", file.fileName)
        val root = SuperDroJson.parseToJsonElement(file.text).jsonObject
        assertEquals("profile", root["superdro"]?.jsonPrimitive?.content)
        assertEquals("1", root["format"]?.jsonPrimitive?.content)
        assertEquals("2026-10-11 14:30:05", root["exported"]?.jsonPrimitive?.content)
        assertNotNull(root["app"])
        assertFalse("device" in root.keys)
        assertFalse("touchdro" in root.keys)
        val settings = assertNotNull(root["settings"] as? JsonObject)
        listOf("workspaces", "tools", "machineDatums", "activeWorkspaceId").forEach { key -> assertFalse(key in settings.keys, key) }
        assertFalse("axisStates" in file.text || "absoluteOffset" in file.text, "no workspace state, no offsets")
    }

    @Test
    fun the_superdro_profile_file_has_all_profile_settings_and_reads_back() {
        val source = MachineProfile.create(MachineType.Lathe, "South Bend").let { lathe ->
            lathe.copy(
                metricDefault = true,
                usbBaudRate = 57_600,
                probe = lathe.probe.copy(enabled = true, channel = 'q', toolSetterEnabled = true),
                display = lathe.display.copy(readoutFont = ReadoutFont.Cousine, shortPressZero = true),
                tachometer = lathe.tachometer.copy(enabled = true, mode = TachometerMode.Preset, presets = listOf(50.0, 100.0)),
                twoWayCommunication = false,
                connectAtStart = true,
                adapterConfigurationCommands = true,
                use3dSubDatums = false,
                muteSounds = true,
                functionButtons = lathe.functionButtons.copy(arcCut = false, headTilt = false),
            )
        }
        val file = ProfileFile.export(source, StoreFixtures.exportContext)
        val preview = ProfileFileImporter().read(file.text, SettingsTarget.NewProfile)
        assertTrue(preview.usable, "problems ${preview.problems}")
        assertEquals("South Bend", preview.suggestedName)
        val result = preview.profile("South Bend")
        assertEquals(source.machineType, result.machineType)
        assertEquals(source.metricDefault, result.metricDefault)
        assertEquals(source.usbBaudRate, result.usbBaudRate)
        assertEquals(source.probe, result.probe, "probe_enable is in the file")
        assertEquals(source.display, result.display)
        assertEquals(source.tachometer, result.tachometer)
        assertEquals(source.twoWayCommunication, result.twoWayCommunication)
        assertEquals(source.connectAtStart, result.connectAtStart)
        assertEquals(source.adapterConfigurationCommands, result.adapterConfigurationCommands)
        assertEquals(source.use3dSubDatums, result.use3dSubDatums)
        assertEquals(source.muteSounds, result.muteSounds)
        assertEquals(source.functionButtons, result.functionButtons)
        assertEquals(source.x, result.x)
        assertEquals(source.patternAngleConvention, result.patternAngleConvention)
    }

    @Test
    fun the_superdro_profile_file_has_the_camera_overlay_and_crosshair_color_settings() {
        val file = ProfileFile.export(MachineProfile.create(), StoreFixtures.exportContext)
        val settings = SuperDroJson.parseToJsonElement(file.text).jsonObject.getValue("settings").jsonObject
        val keys = keysOf(settings).map(String::lowercase)
        assertTrue(keys.any { "keepoverlays" in it || "keep_overlays" in it }, "keys $keys")
        assertTrue(keys.any { "crosshaircolor" in it || "crosshair_color" in it }, "keys $keys")
    }

    @Test
    fun a_profile_file_value_that_is_not_valid_gets_the_default() {
        val file = ProfileFile.export(MachineProfile.create(MachineType.VerticalMill, "P"), StoreFixtures.exportContext).text
        val root = SuperDroJson.parseToJsonElement(file).jsonObject
        val settings = root.getValue("settings").jsonObject.toMutableMap()
        settings["usbBaudRate"] = JsonPrimitive(4800)
        val changed = JsonObject(root + ("settings" to JsonObject(settings))).toString()
        val preview = ProfileFileImporter().read(changed, SettingsTarget.NewProfile)
        assertEquals(115_200, preview.profile("P").usbBaudRate)
        assertTrue(preview.report.defaults.isNotEmpty())
    }

    private fun keysOf(element: kotlinx.serialization.json.JsonElement): List<String> = when (element) {
        is JsonObject -> element.keys.toList() + element.values.flatMap(::keysOf)
        is kotlinx.serialization.json.JsonArray -> element.flatMap(::keysOf)
        else -> emptyList()
    }

    private fun validValue(key: String): String = when {
        key == "machine_type" -> "\"0\""
        key == "display_format" -> "\"3\""
        key == "display_font" -> "\"0\""
        key == "usb_baud_rate" -> "\"115200\""
        key == "wifi_ip_address" -> "\"\""
        key == "wifi_port" -> "\"8080\""
        key == "y_type" -> "\"tailstock\""
        key == "y_angle_reference" -> "\"0\""
        key == "tach_mode" -> "\"0\""
        key == "tach_presets" -> "\"105 160 250 585 898 1400\""
        key.endsWith("_mode") -> "\"0\""
        key.endsWith("_label") -> "\"L\""
        key.endsWith("_prefix_secondary") -> "\"\""
        key == "x_prefix" -> "\"x\""
        key == "y_prefix" -> "\"y\""
        key == "z_prefix" -> "\"z\""
        key.endsWith("_prefix") -> "\"w\""
        else -> "true"
    }

    private companion object {
        fun linear(axis: String) = listOf("_enable", "_label", "_prefix", "_invert", "_secondary_enable", "_prefix_secondary", "_invert_secondary").map { axis + it }

        fun buttons(axis: String) = (1..3).flatMap { n -> listOf("_mode", "_show_expanded", "_show_collapsed").map { "${axis}_button_$n$it" } }

        /** The transfer list (123 keys). */
        val TRANSFER_KEYS: List<String> = buildList {
            addAll(
                listOf(
                    "machine_type", "use_metric_mode", "use_global_tool_library", "display_format", "display_font",
                    "show_sliders", "mute_sounds", "short_press_zero_set", "activate_added_subdatum", "legacy_angle_datum",
                    "bluetooth_connection_enabled", "usb_connection_enabled", "wifi_connection_enabled", "usb_baud_rate",
                    "wifi_ip_address", "wifi_port", "disable_two_way_communications", "data_output_enabled",
                ),
            )
            addAll(linear("x")); addAll(buttons("x"))
            addAll(linear("y")); addAll(listOf("y_type", "y_vectoring", "y_angle_reference")); addAll(buttons("y"))
            addAll(linear("z")); addAll(buttons("z"))
            addAll(listOf("angular_enable", "angular_label", "angular_prefix", "angular_invert")); addAll(buttons("angular"))
            addAll(listOf("tach_enable", "tach_label", "tach_mode", "tach_invert", "tach_presets")); addAll(buttons("tach"))
            addAll(listOf("caliper_enable", "caliper_label", "caliper_prefix", "caliper_invert")); addAll(buttons("caliper"))
            addAll(
                listOf(
                    "show_radius", "show_height", "show_height_setter", "show_edge_finder", "show_hole_circle",
                    "show_arbitrary_circle", "show_hole_grid", "show_equidistant_grid", "show_dimension_measure",
                    "show_cut_radius", "show_measure", "show_calculators", "show_head_tilt", "show_compound_angle",
                ),
            )
        }
    }
}
