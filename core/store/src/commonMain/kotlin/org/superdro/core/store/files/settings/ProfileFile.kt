package org.superdro.core.store.files.settings

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.Channels
import org.superdro.core.model.InputConfig
import org.superdro.core.model.LinearAxisConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.SuperDroJson
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.files.ExportFile
import org.superdro.core.store.files.ExportFileNames
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.ImportMessage
import org.superdro.core.store.files.ImportProblem
import org.superdro.core.store.files.ImportReport
import org.superdro.core.store.files.ImportText
import org.superdro.core.store.files.JsonFiles
import org.superdro.core.store.files.JsonKinds
import org.superdro.core.store.files.MachineNames
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.hasError

/** The JSON form of the SuperDRO export files: two spaces for each level. */
internal val ExportJson: Json = Json(SuperDroJson) {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * The SuperDRO profile file: JSON with the keys
 * `superdro` = `profile`, `format`, `app`, `exported`, `profile`, `machine`, and `settings`.
 *
 * `settings` holds all profile settings: each field of [MachineProfile] except the
 * fields of [EXCLUDED]. The file has no device identity, no workspace state, no offsets,
 * no machine datums, no tools, and no references to other records.
 */
object ProfileFile {

    /** The extension of the file. */
    const val EXTENSION: String = "json"

    /**
     * The fields that the file does not hold: the identity and the dates of the profile, its
     * name (the user selects the name), its data, the references to other records, and
     * the machine mode (a state, not a setting).
     */
    val EXCLUDED: Set<String> = setOf(
        "schemaVersion", "id", "name", "workspaces", "activeWorkspaceId", "tools", "machineDatums",
        "createdOn", "updatedOn", "adapterProfileId", "probeRoles", "cameraId", "machineMode",
    )

    /** Gives the settings of [profile] as a JSON object. */
    fun settingsOf(profile: MachineProfile): JsonObject =
        JsonObject(SuperDroJson.encodeToJsonElement(MachineProfile.serializer(), profile).jsonObject.filterKeys { key -> key !in EXCLUDED })

    /** Writes the profile file of [profile]. The name is `superdro-profile-<name>-<yyyyMMdd_HHmmss>.json`. */
    fun export(profile: MachineProfile, context: ExportContext): ExportFile {
        val root = buildJsonObject {
            put(FileSource.SuperDro.key, JsonPrimitive(JsonKinds.PROFILE))
            put("format", JsonPrimitive(JsonFiles.FORMAT))
            put("app", JsonPrimitive(context.appText))
            put("exported", JsonPrimitive(context.exportedText))
            put("profile", JsonPrimitive(profile.name))
            put("machine", JsonPrimitive(MachineNames.name(profile.machineType)))
            put("settings", settingsOf(profile))
        }
        val text = ExportJson.encodeToString(JsonElement.serializer(), root) + "\n"
        val fileName = ExportFileNames.name(listOf(JsonKinds.PROFILE, profile.name), context.localTime, EXTENSION)
        return ExportFile(fileName, ExportFile.MIME_JSON, text)
    }
}

/**
 * The import of a SuperDRO profile file. It gives the same preview as the `.tdset` import. A
 * value that is not valid gets the default value and a warning. The file reads back with no change.
 */
class ProfileFileImporter(private val now: () -> Instant = { Clock.System.now() }) {

    fun read(bytes: ByteArray, target: SettingsTarget): SettingsImportPreview =
        when (val decoded = ImportText.decode(bytes)) {
            is ImportText.Decoded.Text -> read(decoded.text, target)
            is ImportText.Decoded.Refused -> refused(decoded.problem, target)
        }

    fun read(text: String, target: SettingsTarget): SettingsImportPreview = try {
        when (val read = JsonFiles.readHeader(text, JsonKinds.PROFILE)) {
            is JsonFiles.HeaderRead.Refused -> refused(read.problem, target)
            is JsonFiles.HeaderRead.Header -> readProfile(read.header.source, read.header.root, target)
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

    private fun readProfile(source: FileSource, root: JsonObject, target: SettingsTarget): SettingsImportPreview {
        val fileProfileName = JsonFiles.text(root["profile"])?.let(JsonFiles::singleLine)?.takeIf(String::isNotEmpty)
        val exported = JsonFiles.text(root["exported"])?.trim()
        val settings = JsonFiles.obj(root["settings"])
        if (settings == null || settings.isEmpty()) return refused(ImportProblem.error(MessageKey.SettingNoneUsable), target, source, fileProfileName, exported)

        val currentProfile = (target as? SettingsTarget.SelectedProfile)?.profile
        val headerMachine = JsonFiles.text(root["machine"])?.let(MachineNames::parse)
        val fileMachine = settings["machineType"]?.let { element ->
            try {
                SuperDroJson.decodeFromJsonElement(MachineType.serializer(), element)
            } catch (_: Exception) {
                null
            }
        }
        val machineType = fileMachine ?: headerMachine ?: currentProfile?.machineType ?: MachineType.VerticalMill
        val base = currentProfile?.copy(machineType = machineType) ?: MachineProfile.create(machineType)
        val defaults = encode(MachineProfile.create(machineType))
        val current = encode(currentProfile ?: base)
        val working = encode(base).toMutableMap()

        val notImported = mutableListOf<ImportProblem>()
        val defaultProblems = mutableListOf<ImportProblem>()
        val probeMessages = mutableListOf<ImportMessage>()
        val warnings = mutableListOf<ImportProblem>()
        val rowProblems = mutableMapOf<String, List<ImportProblem>>()
        val mappedKeys = mutableSetOf<String>()

        settings.forEach { (key, element) ->
            if (key in ProfileFile.EXCLUDED || key !in working) {
                val problem = ImportProblem.warning(MessageKey.FieldUnknown, key)
                notImported += problem
                rowProblems[key] = listOf(problem)
                return@forEach
            }
            mappedKeys += key
            val candidate = working.toMutableMap().apply { put(key, element) }
            val decoded = decodeOrNull(candidate)
            if (decoded == null || !valid(key, decoded)) {
                val problem = ImportProblem.warning(MessageKey.FieldInvalid, key)
                defaultProblems += problem
                rowProblems[key] = listOf(problem)
                defaults[key]?.let { default -> working[key] = default }
            } else {
                working[key] = element
            }
            if (key == "probe") probeMessages += ImportMessage(MessageKey.ProbeToProfile, key, element.toString())
        }

        var profile = checkNotNull(decodeOrNull(working)) { "The defaults do not decode." }
        if (machineType.isLathe) profile = profile.copy(patternAngleConvention = PatternAngleConvention.Legacy)
        if (currentProfile != null && currentProfile.machineType != machineType) {
            warnings += ImportProblem.warning(MessageKey.SettingMachineTypeChange, MachineNames.name(currentProfile.machineType), MachineNames.name(machineType))
        }
        warnings += ImportProblem.warning(MessageKey.SettingZeroPoints)

        val after = encode(profile)
        val rows = settings.keys.map { key ->
            val fileValue = settings[key].toString()
            if (key !in mappedKeys) return@map SettingRow(key, fileValue, null, null, RowAction.Skip, rowProblems[key].orEmpty())
            val before = current[key]?.toString()
            val newValue = after[key]?.toString()
            SettingRow(key, fileValue, before, newValue, if (before == newValue) RowAction.Unchanged else RowAction.Update, rowProblems[key].orEmpty())
        }
        val fileProblems = if (rows.none { row -> row.action != RowAction.Skip }) listOf(ImportProblem.error(MessageKey.SettingNoneUsable)) else emptyList()
        return SettingsImportPreview(
            source = source,
            fileProfileName = fileProfileName,
            exported = exported,
            target = target,
            problems = fileProblems + warnings,
            rows = rows,
            probeValues = ProbeValues(),
            report = ImportReport(rows.count { row -> row.action != RowAction.Skip }, notImported, defaultProblems, probeMessages, warnings),
            settings = if (fileProblems.hasError()) null else profile,
            now = now,
        )
    }

    private fun encode(profile: MachineProfile): Map<String, JsonElement> =
        SuperDroJson.encodeToJsonElement(MachineProfile.serializer(), profile).jsonObject

    private fun decodeOrNull(values: Map<String, JsonElement>): MachineProfile? = try {
        SuperDroJson.decodeFromJsonElement(MachineProfile.serializer(), JsonObject(values))
    } catch (_: Exception) {
        null
    }

    /** The value rules that the types do not give. */
    private fun valid(key: String, profile: MachineProfile): Boolean = when (key) {
        "x" -> validLinear(profile.x)
        "y" -> validLinear(profile.y)
        "z" -> validLinear(profile.z)
        "angular" -> validInput(profile.angular.primary) && profile.angular.buttons.size >= 3
        "caliper" -> validInput(profile.caliper.input)
        "probe" -> Channels.isProbeChannel(profile.probe.channel) && profile.probe.channel == Channels.normalize(profile.probe.channel)
        "usbBaudRate" -> profile.usbBaudRate in AdapterAddress.UsbSerial.BAUD_RATES
        "adapter" -> when (val address = profile.adapter) {
            is AdapterAddress.UsbSerial -> address.baudRate in AdapterAddress.UsbSerial.BAUD_RATES
            is AdapterAddress.Tcp -> address.port in 1..65_535
            else -> true
        }
        "auxiliary" -> profile.auxiliary.angleReferenceDegrees in setOf(0, 90, 180)
        "tachometer" -> profile.tachometer.presets.all { preset -> preset.isFinite() && preset >= 0.0 }
        "stylusDiameterUm" -> profile.stylusDiameterUm.isFinite() && profile.stylusDiameterUm >= 0.0
        "zSetterHeightUm" -> profile.zSetterHeightUm.isFinite() && profile.zSetterHeightUm >= 0.0
        else -> true
    }

    private fun validInput(input: InputConfig): Boolean =
        input.channel in Channels.axisChannels && (input.sign == 1 || input.sign == -1) && input.weight.isFinite()

    private fun validLinear(config: LinearAxisConfig): Boolean {
        val secondary = config.secondary
        return validInput(config.primary) && config.buttons.size >= 3 &&
            (secondary == null || (validInput(secondary) && secondary.channel != config.primary.channel))
    }
}
