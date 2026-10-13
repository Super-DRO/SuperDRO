package org.superdro.core.store.files.probe

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.superdro.core.model.Channels
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.newId
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.files.ExportFile
import org.superdro.core.store.files.ExportFileNames
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.FileTimes
import org.superdro.core.store.files.ImportMessage
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.ImportPreview
import org.superdro.core.store.files.ImportProblem
import org.superdro.core.store.files.ImportReport
import org.superdro.core.store.files.ImportText
import org.superdro.core.store.files.ImportTexts
import org.superdro.core.store.files.JsonFiles
import org.superdro.core.store.files.JsonKinds
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.PreviewRow
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.Sha256
import org.superdro.core.store.files.hasError
import org.superdro.core.store.files.settings.ExportJson

/** The names of the probe types in a `.tdprb` file. */
internal object ProbeNames {
    fun type(type: ProbeType): String = when (type) {
        ProbeType.TouchProbe -> "TOUCH_PROBE"
        ProbeType.EdgeFinder2D -> "EDGE_FINDER_2D"
        ProbeType.ToolHeightSetter -> "TOOL_HEIGHT_SETTER"
        ProbeType.Camera -> "CAMERA"
    }

    fun parseType(text: String): ProbeType? = ProbeType.entries.firstOrNull { type -> type(type).equals(text.trim(), ignoreCase = true) }

    fun label(type: ProbeType): MessageKey = when (type) {
        ProbeType.TouchProbe -> MessageKey.ProbeTypeTouchProbe
        ProbeType.EdgeFinder2D -> MessageKey.ProbeTypeEdgeFinder
        ProbeType.ToolHeightSetter -> MessageKey.ProbeTypeToolSetter
        ProbeType.Camera -> MessageKey.ProbeTypeCamera
    }
}

/**
 * The target of a probe profile import: the own probe profiles of a machine profile. The import
 * never makes a standard tip.
 */
data class ProbeImportContext(
    val profileId: String,
    val profileName: String,
    val profiles: List<ProbeProfile> = emptyList(),
)

/** One profile row. [profile] is null when the row does not import. [id] is the `id` of the file. */
data class ProbeRow(
    val row: Int,
    val name: String,
    val id: String?,
    val profile: ProbeProfile?,
    val problems: List<ImportProblem>,
)

/** The result of an import operation. [deleteIds] are the profiles that [ImportOperation.ReplaceAll] deletes first. */
data class ProbeImportPlan(
    val operation: ImportOperation,
    val preview: ImportPreview<ProbeProfile>,
    val add: List<ProbeProfile>,
    val update: List<ProbeProfile>,
    val deleteIds: List<String>,
    val report: ImportReport,
)

/** The preview of a probe profile import. */
class ProbeImportPreview internal constructor(
    val source: FileSource?,
    val fileProfileName: String?,
    val exported: String?,
    val problems: List<ImportProblem>,
    val rows: List<ProbeRow>,
    val operations: List<ImportOperation>,
    private val context: ProbeImportContext,
    private val ids: () -> String,
) {
    val usable: Boolean get() = !problems.hasError() && rows.any { row -> row.profile != null }

    val defaultOperation: ImportOperation? get() = operations.firstOrNull()

    /**
     * Gives the plan of [operation]:
     *
     * - [ImportOperation.AddAsNew]: each row is a new profile.
     * - [ImportOperation.UpdateMatching]: a row with the `id` of an own profile updates it. A
     *   second row with the same `id` is a new profile, with a warning.
     * - [ImportOperation.ReplaceAll]: the import deletes the own profiles, then adds each row.
     */
    fun plan(operation: ImportOperation = defaultOperation ?: ImportOperation.AddAsNew): ProbeImportPlan {
        require(!usable || operation in operations) { "The operation $operation is not available." }
        val previewRows = mutableListOf<PreviewRow<ProbeProfile>>()
        val add = mutableListOf<ProbeProfile>()
        val update = mutableListOf<ProbeProfile>()
        val matched = mutableSetOf<String>()
        rows.forEach { row ->
            val profile = row.profile
            if (profile == null) {
                previewRows += PreviewRow(row.row, row.name, RowAction.Skip, null, row.problems)
                return@forEach
            }
            val existing = if (operation == ImportOperation.UpdateMatching) context.profiles.firstOrNull { it.id == row.id } else null
            when {
                existing != null && existing.id !in matched -> {
                    matched += existing.id
                    val updated = profile.copy(id = existing.id, lastUsedOn = existing.lastUsedOn)
                    update += updated
                    previewRows += PreviewRow(row.row, row.name, RowAction.Update, updated, row.problems)
                }
                else -> {
                    val problems = if (existing != null) row.problems + ImportProblem.warning(MessageKey.ProbeIdRepeated, row = row.row) else row.problems
                    val added = profile.copy(id = ids())
                    add += added
                    previewRows += PreviewRow(row.row, row.name, RowAction.Add, added, problems)
                }
            }
        }
        val preview = ImportPreview.of(previewRows, problems)
        val skipped = previewRows.filter { row -> row.action == RowAction.Skip }.flatMap(PreviewRow<ProbeProfile>::problems)
        val warnings = preview.warnings.filter { problem -> problem !in skipped && problem.message.key != MessageKey.ProbeUnknownType }
        return ProbeImportPlan(
            operation = operation,
            preview = preview,
            add = add,
            update = update,
            deleteIds = if (operation == ImportOperation.ReplaceAll) context.profiles.map(ProbeProfile::id) else emptyList(),
            report = ImportReport(
                imported = add.size + update.size,
                notImported = skipped,
                defaults = emptyList(),
                probe = listOf(ImportMessage(MessageKey.ProbeProfilesValues, add.size + update.size)),
                warnings = warnings,
            ),
        )
    }
}

/**
 * The import of a probe profile file: the `.tdprb` file of TouchDRO 3.32.00 and the
 * SuperDRO probe profile file.
 *
 * @param texts The texts of the type names. A profile with no name gets the name of its type.
 */
class ProbeProfileImporter(
    private val texts: ImportTexts = ImportTexts.English,
    private val ids: () -> String = ::newId,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {

    fun read(bytes: ByteArray, context: ProbeImportContext): ProbeImportPreview =
        when (val decoded = ImportText.decode(bytes)) {
            is ImportText.Decoded.Text -> read(decoded.text, context)
            is ImportText.Decoded.Refused -> refused(decoded.problem, context)
        }

    fun read(text: String, context: ProbeImportContext): ProbeImportPreview = try {
        when (val read = JsonFiles.readHeader(text, JsonKinds.PROBE_PROFILES)) {
            is JsonFiles.HeaderRead.Refused -> refused(read.problem, context)
            is JsonFiles.HeaderRead.Header -> readProfiles(read.header.source, read.header.root, context)
        }
    } catch (error: Exception) {
        refused(ImportProblem.error(MessageKey.FileFailed, error.message ?: error.toString()), context)
    }

    /**
     * Gives a preview with one file problem and no rows. A file with a known header keeps its
     * [source], its profile name, and its export time, so that the preview shows the file type.
     */
    private fun refused(
        problem: ImportProblem,
        context: ProbeImportContext,
        source: FileSource? = null,
        fileProfileName: String? = null,
        exported: String? = null,
    ) = ProbeImportPreview(source, fileProfileName, exported, listOf(problem), emptyList(), emptyList(), context, ids)

    private fun readProfiles(source: FileSource, root: JsonObject, context: ProbeImportContext): ProbeImportPreview {
        val fileProfileName = JsonFiles.text(root["profile"])?.trim()?.takeIf(String::isNotEmpty)
        val exported = JsonFiles.text(root["exported"])
        val problems = mutableListOf<ImportProblem>()
        val items = JsonFiles.array(root["profiles"])
        if (items == null || items.isEmpty()) {
            return refused(ImportProblem.error(MessageKey.ProbeNoProfiles), context, source, fileProfileName, exported)
        }
        if (fileProfileName != null && fileProfileName != context.profileName) {
            problems += ImportProblem.warning(MessageKey.ProbeOtherProfile, fileProfileName, context.profileName)
        }
        val superDro = source == FileSource.SuperDro
        val rows = items.mapIndexed { index, element ->
            val item = JsonFiles.obj(element) ?: JsonObject(emptyMap())
            if (superDro) readStored(index + 1, item, context) else readTdprb(index + 1, item, context)
        }
        if (rows.none { row -> row.profile != null }) problems += ImportProblem.error(MessageKey.NothingUsable)
        val operations = buildList {
            if (rows.any { row -> row.id != null && context.profiles.any { profile -> profile.id == row.id } }) add(ImportOperation.UpdateMatching)
            add(0, ImportOperation.AddAsNew)
            if (rows.none { row -> row.problems.any { problem -> problem.message.key == MessageKey.ProbeUnknownType } }) add(ImportOperation.ReplaceAll)
        }
        return ProbeImportPreview(source, fileProfileName, exported, problems, rows, operations, context, ids)
    }

    private fun readTdprb(row: Int, item: JsonObject, context: ProbeImportContext): ProbeRow {
        val problems = mutableListOf<ImportProblem>()
        val typeText = JsonFiles.text(item["type"]).orEmpty()
        val id = JsonFiles.text(item["id"])?.trim()?.takeIf(String::isNotEmpty)
        val type = ProbeNames.parseType(typeText)
        val rawName = JsonFiles.text(item["name"])?.trim()?.takeIf(String::isNotEmpty)
        if (type == null) {
            problems += ImportProblem.warning(MessageKey.ProbeUnknownType, typeText, row = row)
            return ProbeRow(row, rawName ?: typeText, id, null, problems)
        }
        fun number(key: String): Double? = JsonFiles.number(item[key])?.takeIf(Double::isFinite)
        val metricElement = item["metric"]
        val metric = when {
            metricElement == null -> true
            JsonFiles.string(metricElement) != null -> JsonFiles.string(metricElement)!!.trim().lowercase() !in setOf("0", "false", "no")
            else -> JsonFiles.boolean(metricElement) ?: ((JsonFiles.number(metricElement) ?: 1.0) != 0.0)
        }
        val portText = JsonFiles.text(item["input"])?.trim().orEmpty()
        val sampleElement = item["sample-count"]
        val sampleCount = sampleElement?.let { element ->
            JsonFiles.long(element)?.toInt()?.takeIf { value -> value in ProbeProfile.MIN_SAMPLE_COUNT..ProbeProfile.MAX_SAMPLE_COUNT }
        }
        if (sampleElement != null && sampleCount == null) {
            problems += ImportProblem.warning(
                MessageKey.SettingInvalid, "sample-count", JsonFiles.text(sampleElement) ?: sampleElement.toString(),
                ProbeProfile.DEFAULT_SAMPLE_COUNT.toString(), row = row,
            )
        }
        val profile = ProbeProfile(
            id = "",
            profileId = context.profileId,
            type = type,
            name = rawName ?: "",
            port = null,
            stylusDiameterUm = number("stylus-diameter") ?: 0.0,
            effectiveDiameterUm = number("effective-diameter"),
            xOffsetUm = number("x-offset") ?: 0.0,
            yOffsetUm = number("y-offset") ?: 0.0,
            zOffsetUm = number("z-offset") ?: 0.0,
            metric = metric,
            sampleCount = sampleCount ?: ProbeProfile.DEFAULT_SAMPLE_COUNT,
            spreadLimitUm = number("max-spread")?.takeIf { value -> value >= 0.0 },
            calibrationResidualUm = number("calibration-residual"),
            calibratedOn = JsonFiles.text(item["calibrated-on"])?.let { text -> FileTimes.parse(text, timeZone) },
            calibrationSession = JsonFiles.text(item["calibration-session"])?.takeIf(String::isNotEmpty),
            sessionDigest = JsonFiles.text(item["session-digest"])?.trim()?.takeIf(String::isNotEmpty),
            lastUsedOn = null,
        )
        return finish(row, id, profile, portText, problems)
    }

    private fun readStored(row: Int, item: JsonObject, context: ProbeImportContext): ProbeRow {
        val profile = try {
            SuperDroJson.decodeFromJsonElement(ProbeProfile.serializer(), item)
        } catch (_: Exception) {
            val problem = ImportProblem.warning(MessageKey.FieldInvalid, "profiles[$row]", row = row)
            return ProbeRow(row, JsonFiles.text(item["name"]).orEmpty(), null, null, listOf(problem))
        }
        val problems = mutableListOf<ImportProblem>()
        if (profile.sampleCount !in ProbeProfile.MIN_SAMPLE_COUNT..ProbeProfile.MAX_SAMPLE_COUNT) {
            problems += ImportProblem.warning(MessageKey.SettingInvalid, "sampleCount", profile.sampleCount.toString(), ProbeProfile.DEFAULT_SAMPLE_COUNT.toString(), row = row)
        }
        val fixed = profile.copy(
            profileId = context.profileId,
            sampleCount = profile.sampleCount.takeIf { it in ProbeProfile.MIN_SAMPLE_COUNT..ProbeProfile.MAX_SAMPLE_COUNT } ?: ProbeProfile.DEFAULT_SAMPLE_COUNT,
        )
        return finish(row, profile.id.takeIf(String::isNotEmpty), fixed, profile.port?.toString().orEmpty(), problems, keepRangeDefaults = true)
    }

    /**
     * The common rules of each row: the name (50 characters or less, the type label when blank),
     * the probe port (`p` or `q`), the session digest, and the normalization (the values that
     * the type does not use are cleared).
     */
    private fun finish(
        row: Int,
        id: String?,
        profile: ProbeProfile,
        portText: String,
        problems: MutableList<ImportProblem>,
        keepRangeDefaults: Boolean = false,
    ): ProbeRow {
        var name = profile.name.trim().ifEmpty { texts.text(ImportMessage(ProbeNames.label(profile.type))) }
        if (name.length > MAX_NAME_LENGTH) {
            problems += ImportProblem.warning(MessageKey.NameCut, MAX_NAME_LENGTH, row = row)
            name = name.take(MAX_NAME_LENGTH)
        }
        val letter = portText.firstOrNull()?.let(Channels::normalize)
        var port: Char? = letter?.takeIf(Channels::isProbeChannel)
        if (portText.isNotEmpty() && port == null && profile.type != ProbeType.Camera) {
            problems += if (profile.type == ProbeType.TouchProbe) {
                ImportProblem.warning(MessageKey.ProbeInputDefault, portText, Channels.P.uppercaseChar().toString(), row = row)
            } else {
                ImportProblem.warning(MessageKey.ProbeInputDetached, portText, row = row)
            }
        }
        if (profile.type == ProbeType.TouchProbe && port == null) port = Channels.P
        if (profile.type == ProbeType.Camera) port = null

        var session = profile.calibrationSession
        var digest = profile.sessionDigest
        if (session != null) {
            when {
                digest == null -> problems += ImportProblem.warning(MessageKey.ProbeSessionUnsigned, row = row)
                !digest.equals(Sha256.hex(session), ignoreCase = true) -> {
                    problems += ImportProblem.warning(MessageKey.ProbeSessionDropped, row = row)
                    session = null
                    digest = null
                }
            }
        } else {
            digest = null
        }

        val usesDiameter = profile.type == ProbeType.TouchProbe || profile.type == ProbeType.EdgeFinder2D
        val usesXy = profile.type != ProbeType.ToolHeightSetter
        val usesZ = profile.type == ProbeType.TouchProbe || profile.type == ProbeType.ToolHeightSetter
        val normalized = profile.copy(
            name = name,
            port = port,
            stylusDiameterUm = if (usesDiameter) profile.stylusDiameterUm else 0.0,
            xOffsetUm = if (usesXy) profile.xOffsetUm else 0.0,
            yOffsetUm = if (usesXy) profile.yOffsetUm else 0.0,
            zOffsetUm = if (usesZ) profile.zOffsetUm else 0.0,
            sampleCount = if (port == null && !keepRangeDefaults) ProbeProfile.DEFAULT_SAMPLE_COUNT else profile.sampleCount,
            spreadLimitUm = if (port == null && !keepRangeDefaults) null else profile.spreadLimitUm,
            calibrationSession = session,
            sessionDigest = digest,
        )
        return ProbeRow(row, name, id, normalized, problems)
    }

    private companion object {
        /** A probe profile name has 50 characters or less. */
        const val MAX_NAME_LENGTH = 50
    }
}

/**
 * The probe profile export files: the SuperDRO file, and the `.tdprb` form
 * of TouchDRO 3.32.00. Each file has only the own profiles of the machine profile, sorted
 * by name, and no standard tips. No file has a device identity.
 */
object ProbeProfileFile {

    private const val EXCLUDED_TEXT = "Not in this file: the standard tips, adapter settings, machine profile settings, sub-datums, tools, and license data."

    /** The own profiles of [profileId], sorted by name. A standard tip has no machine profile. */
    fun ownProfiles(profileId: String, profiles: List<ProbeProfile>): List<ProbeProfile> =
        profiles.filter { profile -> profile.profileId == profileId }.sortedBy { profile -> profile.name.lowercase() }

    /** `superdro-probe_profiles-<profile>-<yyyyMMdd_HHmmss>.json`. */
    fun exportSuperDro(profileId: String, profileName: String, profiles: List<ProbeProfile>, context: ExportContext): ExportFile {
        val root = buildJsonObject {
            put(FileSource.SuperDro.key, JsonPrimitive(JsonKinds.PROBE_PROFILES))
            put("format", JsonPrimitive(JsonFiles.FORMAT))
            put("app", JsonPrimitive(context.appText))
            put("exported", JsonPrimitive(context.exportedText))
            put("profile-id", JsonPrimitive(profileId))
            put("profile", JsonPrimitive(profileName))
            put("profiles", SuperDroJson.encodeToJsonElement(ListSerializer(ProbeProfile.serializer()), ownProfiles(profileId, profiles)))
        }
        val text = ExportJson.encodeToString(JsonElement.serializer(), root) + "\n"
        return ExportFile(ExportFileNames.name(listOf(JsonKinds.PROBE_PROFILES, profileName), context.localTime, "json"), ExportFile.MIME_JSON, text)
    }

    /**
     * `superdro-probe_profiles-<profile>-<yyyyMMdd_HHmmss>.tdprb`: the keys of a `.tdprb` file. A
     * key is present only when its value exists. The file has no `device` key. It has no
     * `profile-id` and no profile `id`, because the `.tdprb` form uses whole numbers there and
     * SuperDRO uses UUID texts.
     */
    fun exportTdprb(profileId: String, profileName: String, profiles: List<ProbeProfile>, context: ExportContext): ExportFile {
        val root = buildJsonObject {
            put(FileSource.TouchDro.key, JsonPrimitive(JsonKinds.PROBE_PROFILES))
            put("format", JsonPrimitive(JsonFiles.FORMAT))
            put("app", JsonPrimitive(context.appText))
            put("exported", JsonPrimitive(context.exportedText))
            if (profileName.isNotBlank()) put("profile", JsonPrimitive(profileName))
            put("excluded", JsonPrimitive(EXCLUDED_TEXT))
            put("profiles", JsonArray(ownProfiles(profileId, profiles).map { profile -> tdprbProfile(profile, context.timeZone) }))
        }
        val text = ExportJson.encodeToString(JsonElement.serializer(), root) + "\n"
        return ExportFile(ExportFileNames.name(listOf(JsonKinds.PROBE_PROFILES, profileName), context.localTime, "tdprb"), ExportFile.MIME_TDPRB, text)
    }

    private fun tdprbProfile(profile: ProbeProfile, timeZone: TimeZone): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(ProbeNames.type(profile.type)))
        put("name", JsonPrimitive(profile.name))
        profile.port?.let { put("input", JsonPrimitive(it.toString())) }
        put("stylus-diameter", JsonFiles.exportNumber(profile.stylusDiameterUm))
        put("x-offset", JsonFiles.exportNumber(profile.xOffsetUm))
        put("y-offset", JsonFiles.exportNumber(profile.yOffsetUm))
        put("z-offset", JsonFiles.exportNumber(profile.zOffsetUm))
        put("metric", JsonPrimitive(profile.metric))
        profile.effectiveDiameterUm?.let { put("effective-diameter", JsonFiles.exportNumber(it)) }
        profile.calibrationResidualUm?.let { put("calibration-residual", JsonFiles.exportNumber(it)) }
        profile.calibratedOn?.let { put("calibrated-on", JsonPrimitive(FileTimes.format(it.toLocalDateTime(timeZone)))) }
        put("sample-count", JsonPrimitive(profile.sampleCount))
        profile.spreadLimitUm?.let { put("max-spread", JsonFiles.exportNumber(it)) }
        profile.calibrationSession?.let { put("calibration-session", JsonPrimitive(it)) }
        profile.sessionDigest?.let { put("session-digest", JsonPrimitive(it)) }
    }
}
