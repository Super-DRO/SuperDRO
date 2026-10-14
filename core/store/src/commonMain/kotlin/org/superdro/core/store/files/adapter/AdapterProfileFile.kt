package org.superdro.core.store.files.adapter

import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.Calibration
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.IdentitySource
import org.superdro.core.model.ProbeContactType
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.newId
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.files.ExportFile
import org.superdro.core.store.files.ExportFileNames
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.FileTimes
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
import org.superdro.core.store.files.Severity
import org.superdro.core.store.files.hasError
import org.superdro.core.store.files.settings.ExportJson

/** The names of the 3.32.00 enums in a `.tdap` file. */
internal object TdapNames {
    fun encoderType(type: EncoderType): String = when (type) {
        EncoderType.Scale -> "SCALE"
        EncoderType.LeadscrewEncoder -> "ROTARY"
        EncoderType.RotaryEncoder -> "ROTARY_DIRECT"
        EncoderType.RackPinion -> "RACK_PINION"
    }

    fun kind(kind: EncoderKind): String = when (kind) {
        EncoderKind.Disabled -> "DISABLED"
        EncoderKind.Linear -> "CONTINUOUS"
        EncoderKind.Angular -> "CYCLIC"
    }

    fun backlash(mode: BacklashMode): String = when (mode) {
        BacklashMode.None -> "NONE"
        BacklashMode.Constant -> "CONSTANT"
        BacklashMode.Map -> "MAP"
    }

    fun status(status: CalibrationStatus): String = when (status) {
        CalibrationStatus.NotCalibrated -> "NOT_CALIBRATED"
        CalibrationStatus.Calibrated -> "CALIBRATED"
        CalibrationStatus.UserSet -> "USER_SET"
        CalibrationStatus.Ignored -> "IGNORED"
    }

    fun contact(type: ProbeContactType): String = when (type) {
        ProbeContactType.Auto -> "AUTO"
        ProbeContactType.NormallyClosed -> "NORMALLY_CLOSED"
        ProbeContactType.NormallyOpen -> "NORMALLY_OPEN"
    }

    fun identitySource(source: IdentitySource): String = when (source) {
        IdentitySource.BtMac -> "BT_MAC"
        IdentitySource.AdapterReported -> "ADAPTER_REPORTED"
    }

    fun axis(axis: AxisId): String = when (axis) {
        AxisId.X -> "X"
        AxisId.Y -> "Y"
        AxisId.Z -> "Z"
        AxisId.Angular -> "ANGULAR"
        AxisId.Caliper -> "CALIPER"
        AxisId.Tachometer -> "TACHOMETER"
    }

    /** Finds the value of [values] whose name is [text], in any case. */
    fun <T> parse(text: String, values: List<T>, name: (T) -> String): T? =
        values.firstOrNull { value -> name(value).equals(text.trim(), ignoreCase = true) }
}

/** The target of an adapter profile import. */
sealed interface AdapterTarget {
    /** A new adapter profile. */
    data object NewAdapter : AdapterTarget

    /** The calibration of the inputs of [adapter]. Its name, identity, and link stay. */
    data class Existing(val adapter: AdapterProfile) : AdapterTarget
}

/** One input row of the preview: the actions "Unchanged", "Will replace" ([RowAction.Update]), "Not in file", "Skipped". */
data class AdapterInputRow(
    val input: Char,
    val action: RowAction,
    val profile: EncoderProfile?,
    val problems: List<ImportProblem> = emptyList(),
)

/** The preview of an adapter profile import. */
class AdapterImportPreview internal constructor(
    val source: FileSource?,
    val adapterName: String?,
    val model: String?,
    val exported: String?,
    val target: AdapterTarget,
    val problems: List<ImportProblem>,
    val rows: List<AdapterInputRow>,
    val report: ImportReport,
    /** The adapter profile that the import writes, or null when the file is not usable. */
    val adapter: AdapterProfile?,
) {
    val usable: Boolean get() = adapter != null && !problems.hasError()

    val preview: ImportPreview<EncoderProfile>
        get() = ImportPreview.of(
            rows.mapIndexed { index, row -> PreviewRow(index + 1, row.input.uppercaseChar().toString(), row.action, row.profile, row.problems) },
            problems,
        )
}

/**
 * The import of an adapter profile file: the `.tdap` file of TouchDRO 3.32.00 and the
 * SuperDRO adapter profile file. Each map text gets the map text validation. The import
 * refuses `MAP` for an encoder that is not a leadscrew encoder.
 *
 * @param timeZone The time zone of the local times `calibrated-on` in a `.tdap` file.
 */
class AdapterProfileImporter(
    private val now: () -> Instant = { Clock.System.now() },
    private val ids: () -> String = ::newId,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {

    fun read(bytes: ByteArray, target: AdapterTarget): AdapterImportPreview =
        when (val decoded = ImportText.decode(bytes)) {
            is ImportText.Decoded.Text -> read(decoded.text, target)
            is ImportText.Decoded.Refused -> refused(decoded.problem, target)
        }

    fun read(text: String, target: AdapterTarget): AdapterImportPreview = try {
        when (val read = JsonFiles.readHeader(text, JsonKinds.ADAPTER_PROFILE)) {
            is JsonFiles.HeaderRead.Refused -> refused(read.problem, target)
            is JsonFiles.HeaderRead.Header -> {
                val root = read.header.root
                val stored = JsonFiles.obj(root[AdapterProfileFile.SUPERDRO_CONTENT])
                if (stored != null) readSuperDro(read.header.source, root, stored, target) else readTdap(read.header.source, root, target)
            }
        }
    } catch (error: Exception) {
        refused(ImportProblem.error(MessageKey.FileFailed, error.message ?: error.toString()), target)
    }

    /**
     * Gives a preview with one file problem and no adapter. A file with a known header keeps its
     * [source] and the [header] values, so that the preview shows the file type.
     */
    private fun refused(problem: ImportProblem, target: AdapterTarget, source: FileSource? = null, header: Header? = null) = AdapterImportPreview(
        source, header?.name, header?.model, header?.exported, target, listOf(problem), emptyList(),
        ImportReport(0, emptyList(), emptyList(), emptyList(), emptyList()), null,
    )

    /** The parts of one input. */
    private class InputResult(val profile: EncoderProfile?, val problems: List<ImportProblem>)

    private fun readTdap(source: FileSource, root: JsonObject, target: AdapterTarget): AdapterImportPreview {
        val header = Header(
            name = JsonFiles.text(root["adapter"])?.trim()?.takeIf(String::isNotEmpty),
            model = JsonFiles.text(root["model"])?.trim()?.takeIf(String::isNotEmpty),
            identity = AdapterProfile.normalizeIdentity(JsonFiles.text(root["identity"])),
            identitySource = JsonFiles.text(root["identity-source"])?.let { text -> TdapNames.parse(text, IdentitySource.entries, TdapNames::identitySource) },
            exported = JsonFiles.text(root["exported"])?.trim(),
        )
        val inputs = JsonFiles.array(root["inputs"])
        if (inputs == null || inputs.isEmpty()) return refused(ImportProblem.error(MessageKey.AdapterNoInputs), target, source, header)
        val fileProblems = mutableListOf<ImportProblem>()
        val notImported = mutableListOf<ImportProblem>()
        val results = LinkedHashMap<Char, InputResult>()
        inputs.forEach { element ->
            val item = JsonFiles.obj(element) ?: return@forEach
            val inputText = JsonFiles.text(item["input"])?.trim().orEmpty()
            val letter = inputText.singleOrNull()?.let(Channels::normalize)
            if (letter == null || letter !in Channels.encoderInputs) {
                notImported += ImportProblem.warning(MessageKey.AdapterUnknownInput, inputText)
                return@forEach
            }
            if (letter in results) {
                notImported += ImportProblem.warning(MessageKey.AdapterDuplicateInput, letter.uppercaseChar().toString())
                return@forEach
            }
            results[letter] = readInput(letter, item)
        }
        val probe = JsonFiles.obj(root["probe"])?.let(::readProbe)
        return build(source, header, results, probe, target, fileProblems, notImported, keepCyclic = true)
    }

    private fun readSuperDro(source: FileSource, root: JsonObject, stored: JsonObject, target: AdapterTarget): AdapterImportPreview {
        val adapter = try {
            SuperDroJson.decodeFromJsonElement(AdapterProfile.serializer(), stored)
        } catch (_: Exception) {
            return refused(ImportProblem.error(MessageKey.FileNotJson), target, source, Header(null, null, null, null, JsonFiles.text(root["exported"])))
        }
        val results = LinkedHashMap<Char, InputResult>()
        adapter.encoders.forEach { (input, profile) ->
            val letter = Channels.normalize(input)
            if (letter !in Channels.encoderInputs) return@forEach
            val problems = mutableListOf<ImportProblem>()
            val name = letter.uppercaseChar().toString()
            if (!profile.resolution.isFinite() || profile.resolution <= 0.0) {
                results[letter] = InputResult(null, listOf(ImportProblem.warning(MessageKey.AdapterBadResolution, name, profile.resolution.toString())))
                return@forEach
            }
            var checked = profile.copy(input = letter)
            val errorProblems = MapText.validateErrorMap(checked.errorMap)
            if (errorProblems.isNotEmpty()) {
                problems += ImportProblem.warning(MessageKey.AdapterErrorMapRefused, name)
                problems += errorProblems.map { message -> ImportProblem(Severity.Warning, message) }
                checked = checked.copy(errorMap = emptyList(), errorMapComments = emptyList())
            }
            val backlashProblems = MapText.validateBacklashMap(checked.backlashMap, checked.countsPerScrewRev)
            if (backlashProblems.isNotEmpty()) {
                problems += ImportProblem.warning(MessageKey.AdapterBacklashMapRefused, name)
                problems += backlashProblems.map { message -> ImportProblem(Severity.Warning, message) }
                checked = checked.copy(backlashMap = emptyList())
            }
            results[letter] = InputResult(checkBacklash(checked, problems), problems)
        }
        val header = Header(
            adapter.alias.takeIf(String::isNotEmpty),
            adapter.model,
            AdapterProfile.normalizeIdentity(adapter.identity),
            adapter.identitySource,
            JsonFiles.text(root["exported"]),
        )
        val probe = Probe(adapter.probeContactType, adapter.probeDebounceMs, emptyList())
        return build(source, header, results, probe, target, mutableListOf(), mutableListOf(), keepCyclic = false, stored = adapter)
    }

    private class Header(
        val name: String?,
        val model: String?,
        val identity: String?,
        val identitySource: IdentitySource?,
        val exported: String?,
    )

    private class Probe(val contactType: ProbeContactType, val debounceMs: Long, val problems: List<ImportProblem>)

    private fun build(
        source: FileSource,
        header: Header,
        results: Map<Char, InputResult>,
        probe: Probe?,
        target: AdapterTarget,
        fileProblems: MutableList<ImportProblem>,
        notImported: MutableList<ImportProblem>,
        keepCyclic: Boolean,
        stored: AdapterProfile? = null,
    ): AdapterImportPreview {
        val inputSkips = notImported.toList()
        val existing = (target as? AdapterTarget.Existing)?.adapter
        val fileModel = header.model
        val existingModel = existing?.model
        if (fileModel != null && existingModel != null && !fileModel.equals(existingModel, ignoreCase = true)) {
            fileProblems += ImportProblem.warning(MessageKey.AdapterModelMismatch, fileModel, existingModel)
        }
        val baseEncoders = existing?.encoders ?: Channels.encoderInputs.associateWith { input -> EncoderProfile.defaultFor(input) }
        val encoders = baseEncoders.toMutableMap()
        val rows = Channels.encoderInputs.sortedBy(EXPORT_ORDER::indexOf).map { input ->
            val result = results[input] ?: return@map AdapterInputRow(input, RowAction.NotInFile, baseEncoders[input])
            val imported = result.profile
            if (imported == null) {
                notImported += result.problems
                return@map AdapterInputRow(input, RowAction.Skip, null, result.problems)
            }
            val before = baseEncoders[input]
            // A `.tdap` file has no "cyclic" switch. The import keeps the switch of the input.
            val profile = if (keepCyclic && before != null) imported.copy(errorMapCyclic = before.errorMapCyclic) else imported
            encoders[input] = profile
            val action = when {
                existing == null -> RowAction.Add
                before == profile -> RowAction.Unchanged
                else -> RowAction.Update
            }
            AdapterInputRow(input, action, profile, result.problems)
        }
        val imported = rows.count { row -> row.action == RowAction.Add || row.action == RowAction.Update || row.action == RowAction.Unchanged }
        if (imported == 0) fileProblems += ImportProblem.error(MessageKey.NothingUsable)

        var name = header.name ?: ""
        if (name.length > AdapterProfile.MAX_ALIAS_LENGTH) {
            fileProblems += ImportProblem.warning(MessageKey.NameCut, AdapterProfile.MAX_ALIAS_LENGTH)
            name = name.take(AdapterProfile.MAX_ALIAS_LENGTH)
        }
        val probeMessages = mutableListOf<ImportMessage>()
        probe?.let { values ->
            fileProblems += values.problems
            probeMessages += ImportMessage(MessageKey.ProbeAdapterValues, TdapNames.contact(values.contactType), values.debounceMs)
        }
        val adapter = when {
            fileProblems.hasError() -> null
            existing != null -> existing.copy(
                encoders = encoders,
                probeContactType = probe?.contactType ?: existing.probeContactType,
                probeDebounceMs = probe?.debounceMs ?: existing.probeDebounceMs,
            )
            stored != null -> stored.copy(id = ids(), alias = name, identity = header.identity, encoders = encoders)
            else -> AdapterProfile(
                id = ids(),
                alias = name,
                model = header.model,
                identity = header.identity,
                identitySource = header.identitySource,
                encoders = encoders,
                probeContactType = probe?.contactType ?: ProbeContactType.Auto,
                probeDebounceMs = probe?.debounceMs ?: AdapterProfile.DEFAULT_PROBE_DEBOUNCE_MS,
                createdOn = now(),
            )
        }
        val rowWarnings = rows.filter { row -> row.action != RowAction.Skip }.flatMap(AdapterInputRow::problems)
        val defaults = (rowWarnings + (probe?.problems ?: emptyList())).filter { problem -> problem.message.key == MessageKey.AdapterInvalidValue }
        val warnings = (rowWarnings + fileProblems).filter { problem -> problem.severity == Severity.Warning && problem.message.key != MessageKey.AdapterInvalidValue }
        return AdapterImportPreview(
            source = source,
            adapterName = header.name,
            model = header.model,
            exported = header.exported,
            target = target,
            problems = fileProblems + inputSkips,
            rows = rows,
            report = ImportReport(imported, notImported, defaults, probeMessages, warnings),
            adapter = adapter,
        )
    }

    private fun readInput(letter: Char, item: JsonObject): InputResult {
        val name = letter.uppercaseChar().toString()
        val problems = mutableListOf<ImportProblem>()

        fun invalid(key: String, value: String, default: String) {
            problems += ImportProblem.warning(MessageKey.AdapterInvalidValue, name, key, value, default)
        }

        fun <T> enumValue(key: String, values: List<T>, nameOf: (T) -> String, default: T): T {
            val element = item[key] ?: return default
            val text = JsonFiles.text(element) ?: return default.also { invalid(key, element.toString(), nameOf(default)) }
            return TdapNames.parse(text, values, nameOf) ?: default.also { invalid(key, text, nameOf(default)) }
        }

        val kind = enumValue("kind", EncoderKind.entries, TdapNames::kind, EncoderProfile.defaultFor(letter).kind)
        val defaults = EncoderProfile.defaultFor(letter, if (letter == Channels.T || kind == EncoderKind.Disabled) EncoderKind.Linear else kind)
            .let { profile -> if (letter == Channels.T) profile else profile.copy(kind = kind) }
        val type = enumValue("encoder-type", EncoderType.entries, TdapNames::encoderType, defaults.type)

        val resolutionElement = item["resolution"]
        val resolution = if (resolutionElement == null) {
            defaults.resolution
        } else {
            val value = JsonFiles.number(resolutionElement)
            if (value == null || !value.isFinite() || value <= 0.0) {
                return InputResult(null, listOf(ImportProblem.warning(MessageKey.AdapterBadResolution, name, JsonFiles.text(resolutionElement) ?: resolutionElement.toString())))
            }
            value
        }
        val countsPerScrewRev = item["counts-per-screw-rev"]?.let { element ->
            JsonFiles.number(element)?.takeIf { value -> value.isFinite() && value > 0.0 }
                ?: null.also { invalid("counts-per-screw-rev", JsonFiles.text(element) ?: element.toString(), "") }
        }

        fun flag(key: String): Boolean {
            val element = item[key] ?: return false
            return JsonFiles.boolean(element) ?: false.also { invalid(key, element.toString(), "false") }
        }

        val reversed = flag("invert")
        val filter = flag("filter")
        var backlashMode = enumValue("backlash-mode", BacklashMode.entries, TdapNames::backlash, BacklashMode.None)
        val backlashCounts = item["backlash-constant"]?.let { element ->
            JsonFiles.number(element)?.takeIf { value -> value.isFinite() && value >= 0.0 }?.let { value -> floor(value).toLong() }
                ?: 0L.also { invalid("backlash-constant", JsonFiles.text(element) ?: element.toString(), "0") }
        } ?: 0L

        val errorCheck = MapText.checkErrorMap(JsonFiles.text(item["error-map"]))
        if (!errorCheck.valid) {
            problems += ImportProblem.warning(MessageKey.AdapterErrorMapRefused, name)
            problems += errorCheck.problems.map { message -> ImportProblem(Severity.Warning, message) }
        }
        val backlashCheck = MapText.checkBacklashMap(JsonFiles.text(item["backlash-map"]), countsPerScrewRev)
        if (!backlashCheck.valid) {
            problems += ImportProblem.warning(MessageKey.AdapterBacklashMapRefused, name)
            problems += backlashCheck.problems.map { message -> ImportProblem(Severity.Warning, message) }
        }

        val machineType = item["cal-machine-type"]?.let { element ->
            val text = JsonFiles.text(element).orEmpty()
            MachineNames.parse(text) ?: null.also { invalid("cal-machine-type", text, "") }
        }
        val axisRole = item["cal-axis-role"]?.let { element ->
            val text = JsonFiles.text(element).orEmpty()
            TdapNames.parse(text, AxisId.entries, TdapNames::axis) ?: null.also { invalid("cal-axis-role", text, "") }
        }
        val calibratedOn = item["calibrated-on"]?.let { element ->
            val text = JsonFiles.text(element).orEmpty()
            FileTimes.parse(text, timeZone) ?: null.also { invalid("calibrated-on", text, "") }
        }
        val status = enumValue("calibration-status", CalibrationStatus.entries, TdapNames::status, CalibrationStatus.NotCalibrated)

        val profile = EncoderProfile(
            input = letter,
            label = JsonFiles.text(item["label"])?.trim()?.takeIf(String::isNotEmpty),
            type = type,
            kind = kind,
            resolution = resolution,
            countsPerScrewRev = countsPerScrewRev,
            reversed = reversed,
            filter = filter,
            backlashMode = backlashMode,
            backlashCounts = backlashCounts,
            errorMap = errorCheck.points,
            errorMapComments = errorCheck.comments,
            backlashMap = backlashCheck.points,
            calibration = Calibration(
                status = status,
                calibratedOn = calibratedOn,
                machineType = machineType,
                axisRole = axisRole,
                inputMode = JsonFiles.text(item["cal-input-mode"])?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
        return InputResult(checkBacklash(profile, problems), problems)
    }

    /**
     * The backlash rules: `MAP` only on a leadscrew encoder, a `MAP` mode needs a map, and
     * a constant on a linear leadscrew encoder must be less than one turn of the screw.
     */
    private fun checkBacklash(profile: EncoderProfile, problems: MutableList<ImportProblem>): EncoderProfile {
        val name = profile.input.uppercaseChar().toString()
        val turn = profile.countsPerScrewRev
        return when {
            profile.backlashMode == BacklashMode.Map && profile.type != EncoderType.LeadscrewEncoder -> {
                problems += ImportProblem.warning(MessageKey.AdapterMapOnScale, name, TdapNames.encoderType(profile.type))
                profile.copy(backlashMode = BacklashMode.None)
            }
            profile.backlashMode == BacklashMode.Map && profile.backlashMap.isEmpty() -> {
                problems += ImportProblem.warning(MessageKey.AdapterMapMissing, name)
                profile.copy(backlashMode = BacklashMode.None)
            }
            profile.backlashMode == BacklashMode.Constant && profile.type == EncoderType.LeadscrewEncoder &&
                profile.kind == EncoderKind.Linear && turn != null && profile.backlashCounts >= turn -> {
                problems += ImportProblem.warning(MessageKey.AdapterBacklashExceedsTurn, name)
                profile.copy(backlashMode = BacklashMode.None)
            }
            else -> profile
        }
    }

    private fun readProbe(item: JsonObject): Probe {
        val problems = mutableListOf<ImportProblem>()
        val contactElement = item["contact-type"]
        val contact = contactElement?.let { element ->
            val text = JsonFiles.text(element).orEmpty()
            TdapNames.parse(text, ProbeContactType.entries, TdapNames::contact)
                ?: ProbeContactType.Auto.also { problems += ImportProblem.warning(MessageKey.AdapterInvalidValue, "P", "contact-type", text, "AUTO") }
        } ?: ProbeContactType.Auto
        val debounce = item["debounce-ms"]?.let { element ->
            JsonFiles.long(element)?.takeIf { value -> value >= 0 }
                ?: AdapterProfile.DEFAULT_PROBE_DEBOUNCE_MS.also {
                    problems += ImportProblem.warning(
                        MessageKey.AdapterInvalidValue, "P", "debounce-ms", JsonFiles.text(element) ?: element.toString(),
                        AdapterProfile.DEFAULT_PROBE_DEBOUNCE_MS.toString(),
                    )
                }
        } ?: AdapterProfile.DEFAULT_PROBE_DEBOUNCE_MS
        return Probe(contact, debounce, problems)
    }

    private companion object {
        /** The input order of a `.tdap` file: T, X, Y, Z, W, U, V. */
        val EXPORT_ORDER: List<Char> = listOf('t', 'x', 'y', 'z', 'w', 'u', 'v')
    }
}

/**
 * The adapter profile export files: the SuperDRO file, and the `.tdap` form of
 * TouchDRO 3.32.00, so that the user can move the calibration back. No file has a device
 * identity.
 */
object AdapterProfileFile {

    /** The key of the stored adapter profile in the SuperDRO file. */
    const val SUPERDRO_CONTENT: String = "adapterProfile"

    private const val EXCLUDED_TEXT = "Not in this file: machine profile settings, sub-datums, tools, and license data."
    private val ORDER: List<Char> = listOf('t', 'x', 'y', 'z', 'w', 'u', 'v')

    /** `superdro-adapter_profile-<alias>-<yyyyMMdd_HHmmss>.json`. */
    fun exportSuperDro(adapter: AdapterProfile, context: ExportContext): ExportFile {
        val root = buildJsonObject {
            put(FileSource.SuperDro.key, JsonPrimitive(JsonKinds.ADAPTER_PROFILE))
            put("format", JsonPrimitive(JsonFiles.FORMAT))
            put("app", JsonPrimitive(context.appText))
            put("exported", JsonPrimitive(context.exportedText))
            put(SUPERDRO_CONTENT, SuperDroJson.encodeToJsonElement(AdapterProfile.serializer(), adapter))
        }
        val text = ExportJson.encodeToString(JsonElement.serializer(), root) + "\n"
        return ExportFile(ExportFileNames.name(listOf(JsonKinds.ADAPTER_PROFILE, adapter.alias), context.localTime, "json"), ExportFile.MIME_JSON, text)
    }

    /**
     * `superdro-adapter_profile-<alias>-<yyyyMMdd_HHmmss>.tdap`: the keys of a `.tdap` file, with
     * no `device` key. The input order is T, X, Y, Z, W, U, V.
     */
    fun exportTdap(adapter: AdapterProfile, context: ExportContext): ExportFile {
        val root = buildJsonObject {
            put(FileSource.TouchDro.key, JsonPrimitive(JsonKinds.ADAPTER_PROFILE))
            put("format", JsonPrimitive(JsonFiles.FORMAT))
            put("app", JsonPrimitive(context.appText))
            put("exported", JsonPrimitive(context.exportedText))
            if (adapter.alias.isNotBlank()) put("adapter", JsonPrimitive(adapter.alias))
            adapter.model?.let { put("model", JsonPrimitive(it)) }
            adapter.identity?.let { put("identity", JsonPrimitive(it)) }
            adapter.identitySource?.let { put("identity-source", JsonPrimitive(TdapNames.identitySource(it))) }
            put("excluded", JsonPrimitive(EXCLUDED_TEXT))
            put(
                "inputs",
                buildJsonArray {
                    adapter.encoders.values.sortedBy { profile -> ORDER.indexOf(Channels.normalize(profile.input)) }.forEach { profile ->
                        add(input(profile, context.timeZone))
                    }
                },
            )
            put(
                "probe",
                buildJsonObject {
                    put("contact-type", JsonPrimitive(TdapNames.contact(adapter.probeContactType)))
                    put("debounce-ms", JsonPrimitive(adapter.probeDebounceMs))
                },
            )
        }
        val text = ExportJson.encodeToString(JsonElement.serializer(), root) + "\n"
        return ExportFile(ExportFileNames.name(listOf(JsonKinds.ADAPTER_PROFILE, adapter.alias), context.localTime, "tdap"), ExportFile.MIME_TDAP, text)
    }

    private fun input(profile: EncoderProfile, timeZone: TimeZone): JsonObject = buildJsonObject {
        put("input", JsonPrimitive(profile.input.uppercaseChar().toString()))
        profile.label?.let { put("label", JsonPrimitive(it)) }
        put("encoder-type", JsonPrimitive(TdapNames.encoderType(profile.type)))
        put("kind", JsonPrimitive(TdapNames.kind(profile.kind)))
        if (profile.resolution.isFinite()) put("resolution", JsonFiles.exportNumber(profile.resolution))
        profile.countsPerScrewRev?.let { put("counts-per-screw-rev", JsonFiles.exportNumber(it)) }
        put("invert", JsonPrimitive(profile.reversed))
        put("filter", JsonPrimitive(profile.filter))
        if (profile.errorMap.isNotEmpty()) {
            put("error-map", JsonPrimitive(MapText.write(profile.errorMap, MapText.ERROR_MAP_HEADER, profile.errorMapComments)))
        }
        put("backlash-mode", JsonPrimitive(TdapNames.backlash(profile.backlashMode)))
        if (profile.backlashCounts > 0 || profile.backlashMode == BacklashMode.Constant) put("backlash-constant", JsonPrimitive(profile.backlashCounts))
        if (profile.backlashMap.isNotEmpty()) put("backlash-map", JsonPrimitive(MapText.write(profile.backlashMap, MapText.BACKLASH_MAP_HEADER)))
        val calibration = profile.calibration
        calibration.machineType?.let { put("cal-machine-type", JsonPrimitive(MachineNames.name(it))) }
        calibration.axisRole?.let { put("cal-axis-role", JsonPrimitive(TdapNames.axis(it))) }
        calibration.calibratedOn?.let { put("calibrated-on", JsonPrimitive(FileTimes.format(it.toLocalDateTime(timeZone)))) }
        calibration.inputMode?.let { put("cal-input-mode", JsonPrimitive(it)) }
        put("calibration-status", JsonPrimitive(TdapNames.status(calibration.status)))
    }
}
