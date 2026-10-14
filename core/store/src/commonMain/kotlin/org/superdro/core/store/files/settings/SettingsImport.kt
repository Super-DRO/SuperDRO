package org.superdro.core.store.files.settings

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AxisButtonConfig
import org.superdro.core.model.InputConfig
import org.superdro.core.model.LinearAxisConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.ProbeContactType
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.ImportMessage
import org.superdro.core.store.files.ImportPreview
import org.superdro.core.store.files.ImportProblem
import org.superdro.core.store.files.ImportReport
import org.superdro.core.store.files.ImportText
import org.superdro.core.store.files.JsonFiles
import org.superdro.core.store.files.JsonKinds
import org.superdro.core.store.files.MachineNames
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.PreviewRow
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.hasError

/** The target of a settings import. */
sealed interface SettingsTarget {
    /** A new machine profile with the default settings and the values of the file. */
    data object NewProfile : SettingsTarget

    /** The settings of [profile]. Its workspaces, tools, and other data stay. */
    data class SelectedProfile(val profile: MachineProfile) : SettingsTarget
}

/**
 * One row of the settings preview: one key of the file.
 *
 * @property fileValue The text of the value in the file.
 * @property currentValue The value of the target before the import, or null for a key that does
 *   not go to the profile.
 * @property newValue The value that the import writes, or null when the row does not import.
 */
data class SettingRow(
    val key: String,
    val fileValue: String,
    val currentValue: String?,
    val newValue: String?,
    val action: RowAction,
    val problems: List<ImportProblem> = emptyList(),
)

/**
 * The probe values of an imported file that do not go to the machine profile. The
 * import report tells where they go. The UI can give them to the adapter profile and to a probe
 * profile.
 */
data class ProbeValues(
    val contactType: ProbeContactType? = null,
    val debounceMs: Long? = null,
    val xOffsetUm: Double? = null,
    val yOffsetUm: Double? = null,
    val sampleCount: Int? = null,
    val spreadLimitUm: Double? = null,
)

/**
 * The preview of a settings import: a `.tdset` file or a SuperDRO profile file.
 *
 * @property fileProfileName The profile name of the file. The user selects the name.
 * @property problems The problems of the full file. An error stops the import.
 */
class SettingsImportPreview internal constructor(
    val source: FileSource?,
    val fileProfileName: String?,
    val exported: String?,
    val target: SettingsTarget,
    val problems: List<ImportProblem>,
    val rows: List<SettingRow>,
    val probeValues: ProbeValues,
    val report: ImportReport,
    private val settings: MachineProfile?,
    private val now: () -> Instant,
) {
    val usable: Boolean get() = settings != null && !problems.hasError()

    /** The three lists of the preview. */
    val preview: ImportPreview<SettingRow>
        get() = ImportPreview.of(rows.mapIndexed { index, row -> PreviewRow(index + 1, row.key, row.action, row, row.problems) }, problems)

    /** The number of rows that change a value. */
    val changeCount: Int get() = rows.count { row -> row.action == RowAction.Update }

    /**
     * The name that the preview proposes: the profile name of the file for a new profile, else the
     * name of the selected profile. Null when the file has no name; the UI then proposes its own
     * text.
     */
    val suggestedName: String?
        get() = when (target) {
            SettingsTarget.NewProfile -> fileProfileName
            is SettingsTarget.SelectedProfile -> target.profile.name
        }

    /**
     * Gives the profile with the name that the user selects. The file name does not
     * rename a selected profile. Only the valid values go into the profile.
     */
    fun profile(name: String): MachineProfile {
        val result = checkNotNull(settings) { "The file is not usable." }
        val time = now()
        return when (target) {
            SettingsTarget.NewProfile -> result.copy(name = name, createdOn = time, updatedOn = time)
            is SettingsTarget.SelectedProfile -> result.copy(name = name, updatedOn = time)
        }
    }
}

/**
 * The import of a `.tdset` settings file of TouchDRO 3.32.00.
 *
 * - The file has `touchdro` = `settings`, `format` 1, and the `settings` object.
 * - Each key maps to the setting with the same TouchDRO key.
 * - `display_format` 0, 1, 4 give 2, 3, 5.
 * - A text `true` or `false` gives a boolean only for a boolean setting.
 * - `usb_baud_rate` keeps its value.
 * - `legacy_angle_datum` gives the pattern angle convention. A lathe always has the convention
 *   `Legacy`.
 * - A letter A to F in an input key, or `r` or `s` in `probe_prefix`, is not valid.
 * - A value that is not valid gets the default value and a warning.
 * - The import ignores `device`, the license keys, and the internal state keys.
 */
class SettingsImporter(
    private val now: () -> Instant = { Clock.System.now() },
) {

    fun read(bytes: ByteArray, target: SettingsTarget): SettingsImportPreview =
        when (val decoded = ImportText.decode(bytes)) {
            is ImportText.Decoded.Text -> read(decoded.text, target)
            is ImportText.Decoded.Refused -> refused(decoded.problem, target)
        }

    /** Reads the text of a `.tdset` file. Each exception gives a problem, not a crash. */
    fun read(text: String, target: SettingsTarget): SettingsImportPreview = try {
        when (val read = JsonFiles.readHeader(text, JsonKinds.SETTINGS)) {
            is JsonFiles.HeaderRead.Refused -> refused(read.problem, target)
            is JsonFiles.HeaderRead.Header -> readSettings(read.header.source, read.header.root, target)
        }
    } catch (error: Exception) {
        refused(ImportProblem.error(MessageKey.FileFailed, error.message ?: error.toString()), target)
    }

    /**
     * Gives a preview with one file problem and no settings. A file with a known header keeps its
     * [source], its profile name, and its export time, so that the preview shows the file type.
     */
    private fun refused(
        problem: ImportProblem,
        target: SettingsTarget,
        source: FileSource? = null,
        fileProfileName: String? = null,
        exported: String? = null,
    ) = SettingsImportPreview(
        source, fileProfileName, exported, target, listOf(problem), emptyList(), ProbeValues(),
        ImportReport(0, emptyList(), emptyList(), emptyList(), emptyList()), null, now,
    )

    private class Parsed(val key: String, val def: KeyDef?, val fileText: String, val value: KeyValue?)

    private fun readSettings(source: FileSource, root: JsonObject, target: SettingsTarget): SettingsImportPreview {
        val fileProfileName = JsonFiles.text(root["profile"])?.let(JsonFiles::singleLine)?.takeIf(String::isNotEmpty)
        val exported = JsonFiles.text(root["exported"])?.trim()
        val headerMachine = JsonFiles.text(root["machine"])?.let(MachineNames::parse)
        val settingsObject = JsonFiles.obj(root["settings"])
        if (settingsObject == null || settingsObject.isEmpty()) {
            return refused(ImportProblem.error(MessageKey.SettingNoneUsable), target, source, fileProfileName, exported)
        }

        val parsed = settingsObject.map { (key, element) ->
            val def = TdsetKeys.ALL[key]
            val fileText = JsonFiles.text(element) ?: element.toString()
            Parsed(key, def, fileText, def?.let { TdsetKeys.parse(it, element) })
        }

        // The machine type comes first: the defaults depend on it.
        val currentProfile = (target as? SettingsTarget.SelectedProfile)?.profile
        val defaultType = currentProfile?.machineType ?: headerMachine ?: MachineType.VerticalMill
        val typeValue = parsed.firstOrNull { item -> item.key == "machine_type" }?.value
        val machineType = (typeValue as? KeyValue.Valid)?.let { TdsetKeys.machineType(it.value as Int) } ?: defaultType

        val defaults = TdsetKeys.flatten(MachineProfile.create(machineType)) + TdsetKeys.PROBE_DEFAULTS
        val base = currentProfile?.copy(machineType = machineType) ?: MachineProfile.create(machineType)
        val current = TdsetKeys.flatten(currentProfile ?: base)
        val values = TdsetKeys.flatten(base).toMutableMap()
        val fileKeys = mutableSetOf<String>()

        val notImported = mutableListOf<ImportProblem>()
        val defaultProblems = mutableListOf<ImportProblem>()
        val probeMessages = mutableListOf<ImportMessage>()
        val warnings = mutableListOf<ImportProblem>()
        val rowProblems = mutableMapOf<String, MutableList<ImportProblem>>()
        val probeValues = mutableMapOf<String, Any>()

        parsed.forEach { item ->
            val def = item.def
            val problems = rowProblems.getOrPut(item.key) { mutableListOf() }
            when {
                TdsetKeys.isInternal(item.key) -> problems += ImportProblem.warning(MessageKey.SettingInternal, item.key).also(notImported::add)
                def == null -> problems += ImportProblem.warning(MessageKey.SettingUnknown, item.key).also(notImported::add)
                def.target == KeyTarget.NotMapped -> problems += ImportProblem.warning(MessageKey.SettingNotMapped, item.key).also(notImported::add)
                def.target == KeyTarget.LinkType -> {
                    // SuperDRO keeps the link types in the app settings. The Wi-Fi switch only
                    // decides if a Wi-Fi host gives a TCP link.
                    (item.value as? KeyValue.Valid)?.let { valid -> values[item.key] = valid.value }
                    problems += ImportProblem.warning(MessageKey.SettingLinkType, item.key).also(notImported::add)
                }
                else -> {
                    val value = when (val result = item.value) {
                        is KeyValue.Valid -> result.value
                        is KeyValue.NotPresent -> {
                            val default = defaults[item.key]
                            problems += if (def.rule == KeyRule.SecondaryInput) {
                                ImportProblem.warning(MessageKey.SettingSecondaryOff, item.key, result.letter)
                            } else {
                                ImportProblem.warning(MessageKey.SettingInputNotPresent, item.key, result.letter, TdsetKeys.display(default))
                            }
                            defaultProblems += problems.last()
                            default
                        }
                        else -> {
                            val default = defaults[item.key]
                            problems += ImportProblem.warning(MessageKey.SettingInvalid, item.key, item.fileText, TdsetKeys.display(default))
                            defaultProblems += problems.last()
                            default
                        }
                    }
                    when (def.target) {
                        KeyTarget.ProbeAdapter -> {
                            if (value != null) probeValues[item.key] = value
                            probeMessages += ImportMessage(MessageKey.ProbeToAdapter, item.key, item.fileText)
                        }
                        KeyTarget.ProbeProfile -> {
                            if (value != null) probeValues[item.key] = value
                            probeMessages += ImportMessage(MessageKey.ProbeToProbeProfile, item.key, item.fileText)
                        }
                        else -> {
                            values[item.key] = value
                            fileKeys += item.key
                            if (def.probe) probeMessages += ImportMessage(MessageKey.ProbeToProfile, item.key, item.fileText)
                        }
                    }
                }
            }
        }

        val composed = compose(base, values, fileKeys, machineType, warnings, rowProblems)
        if (currentProfile != null && currentProfile.machineType != machineType) {
            warnings += ImportProblem.warning(MessageKey.SettingMachineTypeChange, MachineNames.name(currentProfile.machineType), MachineNames.name(machineType))
        }
        inputConflicts(composed.profile, warnings)
        warnings += ImportProblem.warning(MessageKey.SettingZeroPoints)

        val after = TdsetKeys.flatten(composed.profile)
        val rows = parsed.map { item ->
            val def = item.def
            val problems = rowProblems[item.key].orEmpty()
            val mapped = def != null && !TdsetKeys.isInternal(item.key) &&
                (def.target == KeyTarget.Profile || (def.target == KeyTarget.Wifi && composed.wifiMapped))
            if (def?.target == KeyTarget.Wifi && !composed.wifiMapped) {
                val problem = ImportProblem.warning(MessageKey.SettingWifiNotMapped, item.key)
                notImported += problem
                return@map SettingRow(item.key, item.fileText, null, null, RowAction.Skip, problems + problem)
            }
            if (!mapped) return@map SettingRow(item.key, item.fileText, null, null, RowAction.Skip, problems)
            val before = TdsetKeys.display(current[item.key])
            val newValue = TdsetKeys.display(after[item.key])
            SettingRow(item.key, item.fileText, before, newValue, if (before == newValue) RowAction.Unchanged else RowAction.Update, problems)
        }

        val fileProblems = mutableListOf<ImportProblem>()
        if (rows.none { row -> row.action != RowAction.Skip }) fileProblems += ImportProblem.error(MessageKey.SettingNoneUsable)
        val report = ImportReport(
            imported = rows.count { row -> row.action != RowAction.Skip },
            notImported = notImported,
            defaults = defaultProblems,
            probe = probeMessages,
            warnings = warnings,
        )
        return SettingsImportPreview(
            source = source,
            fileProfileName = fileProfileName,
            exported = exported,
            target = target,
            problems = fileProblems + warnings,
            rows = rows,
            probeValues = probeValues(probeValues),
            report = report,
            settings = if (fileProblems.hasError()) null else composed.profile,
            now = now,
        )
    }

    private class Composed(val profile: MachineProfile, val wifiMapped: Boolean)

    /** Builds the profile from the key values. [values] has a value for each mapped key. */
    @Suppress("UNCHECKED_CAST")
    private fun compose(
        base: MachineProfile,
        values: Map<String, Any?>,
        fileKeys: Set<String>,
        machineType: MachineType,
        warnings: MutableList<ImportProblem>,
        rowProblems: MutableMap<String, MutableList<ImportProblem>>,
    ): Composed {
        fun b(key: String): Boolean = values[key] as Boolean
        fun i(key: String): Int = values[key] as Int
        fun s(key: String): String = values[key] as String

        fun buttons(axis: String, current: List<AxisButtonConfig>): List<AxisButtonConfig> = (1..3).map { n ->
            AxisButtonConfig(
                mode = TdsetKeys.buttonMode(i("${axis}_button_${n}_mode")),
                showExpanded = b("${axis}_button_${n}_show_expanded"),
                showCollapsed = b("${axis}_button_${n}_show_collapsed"),
            )
        } + current.drop(3)

        fun linear(axis: String, config: LinearAxisConfig): LinearAxisConfig {
            val primary = config.primary.copy(channel = s("${axis}_prefix")[0], sign = if (b("${axis}_invert")) -1 else 1)
            val secondaryLetter = s("${axis}_prefix_secondary")
            val secondary = when {
                !b("${axis}_secondary_enable") -> null
                secondaryLetter.isEmpty() -> {
                    val key = "${axis}_prefix_secondary"
                    // A letter that is not valid already gave its warning.
                    if (rowProblems[key].isNullOrEmpty()) {
                        warnings += ImportProblem.warning(MessageKey.SettingSecondaryNoInput, "${axis}_secondary_enable")
                    }
                    null
                }
                secondaryLetter[0] == primary.channel -> {
                    warnings += ImportProblem.warning(MessageKey.SettingSameInput, "${axis}_prefix_secondary", secondaryLetter.uppercase())
                    null
                }
                else -> InputConfig(
                    channel = secondaryLetter[0],
                    sign = if (b("${axis}_invert_secondary")) -1 else 1,
                    weight = config.secondary?.weight ?: 1.0,
                )
            }
            return config.copy(
                enabled = b("${axis}_enable"),
                label = s("${axis}_label"),
                buttons = buttons(axis, config.buttons),
                primary = primary,
                secondary = secondary,
            )
        }

        val lathe = machineType.isLathe
        var x = linear("x", base.x)
        val y = linear("y", base.y)
        val z = linear("z", base.z)
        val angularEnabled = b("angular_enable")
        if (!x.enabled && !y.enabled && !z.enabled && !angularEnabled) {
            warnings += ImportProblem.warning(MessageKey.SettingNoAxis)
            x = x.copy(enabled = true)
        }

        // The link keeps the baud rate. A Wi-Fi host goes to a TCP link.
        val baud = s("usb_baud_rate").toInt()
        val host = s("wifi_ip_address")
        val port = i("wifi_port")
        val wifiInFile = "wifi_ip_address" in fileKeys || "wifi_port" in fileKeys
        val wifiSwitch = values["wifi_connection_enabled"] as? Boolean
        var wifiMapped = false
        val adapter = when (val address = base.adapter) {
            is AdapterAddress.UsbSerial -> address.copy(baudRate = baud)
            is AdapterAddress.Tcp -> if (wifiInFile && host.isNotBlank()) {
                wifiMapped = true
                address.copy(host = host, port = port)
            } else {
                address
            }
            null -> if (wifiInFile && wifiSwitch == true && host.isNotBlank()) {
                wifiMapped = true
                AdapterAddress.Tcp(host, port)
            } else {
                null
            }
            else -> address
        }

        val profile = base.copy(
            machineType = machineType,
            metricDefault = b("use_metric_mode"),
            useSharedToolLibrary = b("use_global_tool_library"),
            display = base.display.copy(
                readoutFormat = ReadoutFormat.fromTouchDroCode(i("display_format")) ?: ReadoutFormat.Format3,
                readoutFont = (values["display_font"] as Int?)?.let(TdsetKeys::font) ?: base.display.readoutFont,
                showSliders = b("show_sliders"),
                shortPressZero = b("short_press_zero_set"),
                activateNewSubDatum = b("activate_added_subdatum"),
            ),
            muteSounds = b("mute_sounds"),
            patternAngleConvention = if (lathe || b("legacy_angle_datum")) PatternAngleConvention.Legacy else PatternAngleConvention.Standard,
            usbBaudRate = baud,
            adapter = adapter,
            twoWayCommunication = !b("disable_two_way_communications"),
            dataOutput = base.dataOutput.copy(enabled = b("data_output_enabled")),
            x = x,
            y = y,
            z = z,
            auxiliary = base.auxiliary.copy(
                type = TdsetKeys.auxiliaryType(s("y_type")),
                vectoring = b("y_vectoring"),
                angleReferenceDegrees = i("y_angle_reference"),
            ),
            angular = base.angular.copy(
                enabled = angularEnabled,
                label = s("angular_label"),
                buttons = buttons("angular", base.angular.buttons),
                primary = base.angular.primary.copy(channel = s("angular_prefix")[0], sign = if (b("angular_invert")) -1 else 1),
            ),
            tachometer = base.tachometer.copy(
                enabled = b("tach_enable"),
                label = s("tach_label"),
                mode = TdsetKeys.tachometerMode(i("tach_mode")),
                invert = b("tach_invert"),
                presets = values["tach_presets"] as List<Double>,
            ),
            caliper = base.caliper.copy(
                enabled = b("caliper_enable"),
                input = base.caliper.input.copy(channel = s("caliper_prefix")[0], sign = if (b("caliper_invert")) -1 else 1),
            ),
            functionButtons = base.functionButtons.copy(
                radiusCompensation = b("show_radius"),
                heightCompensation = b("show_height"),
                zReference = b("show_height_setter"),
                edgeFinder = b("show_edge_finder"),
                holeCircle = b("show_hole_circle"),
                arbitraryHoleCircle = b("show_arbitrary_circle"),
                holeGrid = b("show_hole_grid"),
                equidistantArray = b("show_equidistant_grid"),
                dimensionMeasure = b("show_dimension_measure"),
                arcCut = b("show_cut_radius"),
                angleMeasure = b("show_measure"),
                headTilt = b("show_head_tilt"),
                compoundAngle = b("show_compound_angle"),
            ),
            probe = base.probe.copy(
                enabled = b("probe_enable"),
                channel = s("probe_prefix")[0],
                edgeFinderEnabled = b("probe_edge_finder_enable"),
                toolSetterEnabled = b("probe_tool_setter_enable"),
            ),
        )
        return Composed(profile, wifiMapped)
    }

    /** The input conflict check: one letter on two enabled inputs gives a warning. */
    private fun inputConflicts(profile: MachineProfile, warnings: MutableList<ImportProblem>) {
        val inputs = buildList {
            listOf("x" to profile.x, "y" to profile.y, "z" to profile.z).forEach { (axis, config) ->
                if (config.enabled) {
                    add("${axis}_prefix" to config.primary.channel)
                    config.secondary?.let { secondary -> add("${axis}_prefix_secondary" to secondary.channel) }
                }
            }
            if (profile.angular.enabled) add("angular_prefix" to profile.angular.primary.channel)
            if (profile.caliper.enabled) add("caliper_prefix" to profile.caliper.input.channel)
        }
        inputs.forEachIndexed { index, (key, letter) ->
            inputs.drop(index + 1).filter { (_, other) -> other == letter }.forEach { (otherKey, _) ->
                warnings += ImportProblem.warning(MessageKey.SettingSharedInput, key, otherKey, letter.uppercaseChar().toString())
            }
        }
    }

    private fun probeValues(values: Map<String, Any>): ProbeValues = ProbeValues(
        contactType = (values["probe_invert"] as Boolean?)?.let { invert -> if (invert) ProbeContactType.NormallyClosed else ProbeContactType.Auto },
        debounceMs = values["probe_debounce_seconds"] as Long?,
        xOffsetUm = values["probe_x_offset_compensation_microns"] as Double?,
        yOffsetUm = values["probe_y_offset_compensation_microns"] as Double?,
        sampleCount = values["probe_edge_finder_sample_count"] as Int?,
        spreadLimitUm = values["probe_edge_finder_sample_spread_microns"] as Double?,
    )
}
