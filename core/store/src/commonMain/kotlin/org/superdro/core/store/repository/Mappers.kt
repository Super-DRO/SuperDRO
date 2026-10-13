package org.superdro.core.store.repository

import com.toxicbakery.logging.Arbor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.SubDatum
import org.superdro.core.model.ThreadSpec
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolGeometry
import org.superdro.core.model.ToolKind
import org.superdro.core.model.MachineType
import org.superdro.core.model.Workspace
import org.superdro.core.store.db.Axis_state
import org.superdro.core.store.db.Machine_datum
import org.superdro.core.store.db.Machine_profile
import org.superdro.core.store.db.SelectForWorkspace
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.db.Thread_spec
import org.superdro.core.store.db.Guide as GuideRow
import org.superdro.core.store.db.Tool as ToolRow
import org.superdro.core.store.db.Workspace as WorkspaceRow

private val log = Arbor.tag("StoreMappers")

/** The keys of [MachineProfile] that have their own column or their own table. */
private val profileColumnKeys = setOf(
    "id", "name", "machineType", "metricDefault", "useSharedToolLibrary", "adapterProfileId",
    "activeWorkspaceId", "workspaces", "tools", "machineDatums", "createdOn", "updatedOn",
)

/** The keys of [Workspace] that have their own column or their own table. */
private val workspaceColumnKeys = setOf(
    "id", "name", "axisStates", "subDatums", "guides", "lastOrdinal", "referenceImage", "createdOn",
    "updatedOn",
)

/** Encodes [value] as JSON text without the keys [omit]. */
private fun <T> Json.encodeWithout(serializer: KSerializer<T>, value: T, omit: Set<String>): String {
    val element = encodeToJsonElement(serializer, value).jsonObject
    return encodeToString(JsonObject.serializer(), JsonObject(element.filterKeys { key -> key !in omit }))
}

/**
 * Decodes a JSON text column. A text that does not decode gives [fallback] and a log line, so
 * one damaged row does not stop the load of all data.
 */
internal fun <T> Json.decodeColumn(serializer: KSerializer<T>, text: String, fallback: () -> T): T =
    try {
        decodeFromString(serializer, text)
    } catch (error: IllegalArgumentException) {
        log.e(error) { "Cannot decode a JSON column. The store uses the defaults." }
        fallback()
    }

/**
 * The typed JSON of the profile settings: the profile without the column values and the lists. A
 * USB link gets the rate of [MachineProfile.usbBaudRate].
 */
internal fun Json.profileConfigJson(profile: MachineProfile): String = encodeWithout(
    MachineProfile.serializer(),
    profile.withUsbBaudRateOnLink().copy(workspaces = emptyList(), tools = emptyList(), machineDatums = emptyList()),
    profileColumnKeys,
)

/**
 * The settings of a profile row. The lists [MachineProfile.workspaces], [MachineProfile.tools],
 * and [MachineProfile.machineDatums] are empty. The repository fills them for a full profile.
 *
 * A row of an older version can have a USB link with a rate that is not [MachineProfile.usbBaudRate].
 * The read gives the link the rate of the setting, which the settings screen shows. The next write of
 * the profile stores the same value.
 */
internal fun Json.profileFrom(row: Machine_profile): MachineProfile {
    val settings = decodeColumn(MachineProfile.serializer(), row.config_json) { MachineProfile.create(row.machine_type) }
        .withUsbBaudRateOnLink()
    return settings.copy(
        id = row.id,
        name = row.name,
        machineType = row.machine_type,
        metricDefault = row.metric_default,
        useSharedToolLibrary = row.use_shared_tools,
        adapterProfileId = row.adapter_profile_id,
        activeWorkspaceId = row.active_workspace_id.orEmpty(),
        workspaces = emptyList(),
        tools = emptyList(),
        machineDatums = emptyList(),
        createdOn = row.created_on,
        updatedOn = row.updated_on,
    )
}

/** The typed JSON of the workspace state: the workspace without the column values and the lists. */
internal fun Json.workspaceStateJson(workspace: Workspace): String = encodeWithout(
    Workspace.serializer(),
    workspace.copy(subDatums = emptyList(), guides = emptyList(), axisStates = emptyMap(), referenceImage = null),
    workspaceColumnKeys,
)

/** The JSON of a reference image, or null for no image. */
internal fun Json.referenceImageJson(image: ReferenceImage?): String? =
    image?.let { encodeToString(ReferenceImage.serializer(), it) }

/** The reference image of a JSON column. */
internal fun Json.referenceImageFrom(text: String?): ReferenceImage? =
    text?.let { decodeColumn(ReferenceImage.serializer(), it) { ReferenceImage() } }

/**
 * A workspace of a row and its axis states. An axis with no row gets the default state.
 * [subDatums] and [guides] are empty unless the caller gives them.
 */
internal fun Json.workspaceFrom(
    row: WorkspaceRow,
    axisRows: List<Axis_state>,
    subDatums: List<SubDatum> = emptyList(),
    guides: List<Guide> = emptyList(),
): Workspace {
    val state = decodeColumn(Workspace.serializer(), row.state_json) { Workspace() }
    val stored = axisRows.associate { axisRow -> axisRow.axis to axisRow.toModel() }
    return state.copy(
        id = row.id,
        name = row.name,
        axisStates = AxisId.entries.associateWith { axis -> stored[axis] ?: AxisState() },
        lastOrdinal = row.last_ordinal,
        referenceImage = referenceImageFrom(row.reference_image_json),
        subDatums = subDatums,
        guides = guides,
        createdOn = row.created_on,
        updatedOn = row.updated_on,
    )
}

internal fun Axis_state.toModel(): AxisState = AxisState(
    absoluteOffset = absolute_offset,
    incrementalOffset = incremental_offset,
    relative = relative,
    toolOffset = tool_offset,
    toolOffsetActive = tool_offset_active,
    metric = metric,
    diameterMode = diameter_mode,
    divisionUnits = division_units,
    divisionsPerRev = divisions_per_rev,
    surfaceSpeedMode = surface_speed_mode,
)

/** Writes the state of one axis: one row. */
internal fun SuperDroDatabase.writeAxisState(workspaceId: String, axis: AxisId, state: AxisState) {
    workspaceQueries.upsertAxisState(
        workspace_id = workspaceId,
        axis = axis,
        absolute_offset = state.absoluteOffset,
        incremental_offset = state.incrementalOffset,
        relative = state.relative,
        tool_offset = state.toolOffset,
        tool_offset_active = state.toolOffsetActive,
        metric = state.metric,
        diameter_mode = state.diameterMode,
        division_units = state.divisionUnits,
        divisions_per_rev = state.divisionsPerRev,
        surface_speed_mode = state.surfaceSpeedMode,
    )
}

/**
 * Gives the sub-datums of the rows of the query `selectForWorkspace`, in the order of the rows.
 * A sub-datum with more tags has one row for each tag.
 */
internal fun List<SelectForWorkspace>.toSubDatums(): List<SubDatum> {
    val order = LinkedHashMap<String, Pair<SelectForWorkspace, MutableList<String>>>()
    for (row in this) {
        val entry = order.getOrPut(row.id) { row to mutableListOf() }
        row.tag?.let { tag -> entry.second += tag }
    }
    return order.values.map { (row, tags) ->
        SubDatum(
            id = row.id,
            name = row.name,
            note = row.note,
            tags = tags,
            x = row.x_um,
            y = row.y_um,
            z = row.z_um,
            a = row.a_arcsec,
            groupId = row.group_id,
            groupKind = row.group_kind,
            groupRef = row.group_ref,
            createdOn = row.created_on,
            updatedOn = row.updated_on,
        )
    }
}

/** Writes [subDatum] and replaces its tags. A tag that shows two times is written one time. */
internal fun SuperDroDatabase.writeSubDatum(workspaceId: String, subDatum: SubDatum) {
    subDatumQueries.upsert(
        id = subDatum.id,
        workspace_id = workspaceId,
        name = subDatum.name,
        note = subDatum.note,
        x_um = subDatum.x,
        y_um = subDatum.y,
        z_um = subDatum.z,
        a_arcsec = subDatum.a,
        group_id = subDatum.groupId,
        group_kind = subDatum.groupKind,
        group_ref = subDatum.groupRef,
        created_on = subDatum.createdOn,
        updated_on = subDatum.updatedOn,
    )
    subDatumQueries.deleteTags(subDatum.id)
    subDatum.tags.distinct().forEachIndexed { position, tag ->
        subDatumQueries.insertTag(sub_datum_id = subDatum.id, position = position, tag = tag)
    }
}

internal fun GuideRow.toModel(): Guide = Guide(id = id, axis = axis, valueUm = value_um, createdOn = created_on)

internal fun SuperDroDatabase.writeGuide(workspaceId: String, guide: Guide) {
    subDatumQueries.upsertGuide(
        id = guide.id,
        workspace_id = workspaceId,
        axis = guide.axis,
        value_um = guide.valueUm,
        created_on = guide.createdOn,
    )
}

internal fun Machine_datum.toModel(): MachineDatum = MachineDatum(
    id = id,
    name = name,
    note = note,
    x = x_um,
    y = y_um,
    z = z_um,
    a = a_arcsec,
    createdOn = created_on,
    updatedOn = updated_on,
)

internal fun SuperDroDatabase.writeMachineDatum(profileId: String, datum: MachineDatum) {
    machineDatumQueries.upsert(
        id = datum.id,
        profile_id = profileId,
        name = datum.name,
        note = datum.note,
        x_um = datum.x,
        y_um = datum.y,
        z_um = datum.z,
        a_arcsec = datum.a,
        created_on = datum.createdOn,
        updated_on = datum.updatedOn,
    )
}

internal fun Json.threadFrom(row: Thread_spec): ThreadSpec =
    decodeColumn(ThreadSpec.serializer(), row.spec_json) { ThreadSpec() }.copy(
        id = row.id,
        workspaceId = row.workspace_id,
        tag = row.tag,
        createdOn = row.created_on,
        updatedOn = row.updated_on,
    )

internal fun SuperDroDatabase.writeThread(json: Json, workspaceId: String, thread: ThreadSpec) {
    threadSpecQueries.upsert(
        id = thread.id,
        workspace_id = workspaceId,
        tag = thread.tag,
        spec_json = json.encodeToString(ThreadSpec.serializer(), thread.copy(workspaceId = workspaceId)),
        created_on = thread.createdOn,
        updated_on = thread.updatedOn,
    )
}

internal fun Json.toolFrom(row: ToolRow): Tool = Tool(
    id = row.id,
    kind = row.kind,
    name = row.name,
    description = row.description,
    metric = row.metric,
    diameterUm = row.diameter_um,
    toothCount = row.tooth_count,
    zOffsetUm = row.z_offset_um,
    xOffsetUm = row.x_offset_um,
    internal = row.internal_,
    geometry = row.geometry_json?.let { text -> decodeColumn(ToolGeometry.serializer(), text) { ToolGeometry() } },
    createdOn = row.created_on,
    updatedOn = row.updated_on,
)

/** Inserts [tool] into the library [profileId] (null: the shared library). */
internal fun SuperDroDatabase.insertTool(json: Json, profileId: String?, tool: Tool) {
    toolQueries.insert(
        id = tool.id,
        profile_id = profileId,
        kind = tool.kind,
        name = tool.name,
        description = tool.description,
        metric = tool.metric,
        diameter_um = tool.diameterUm,
        tooth_count = tool.toothCount,
        z_offset_um = tool.zOffsetUm,
        x_offset_um = tool.xOffsetUm,
        internal_ = tool.internal,
        geometry_json = tool.geometry?.let { geometry -> json.encodeToString(ToolGeometry.serializer(), geometry) },
        created_on = tool.createdOn,
        updated_on = tool.updatedOn,
    )
}

/** Changes the values of [tool]. The library of the tool does not change. */
internal fun SuperDroDatabase.updateTool(json: Json, tool: Tool) {
    toolQueries.update(
        kind = tool.kind,
        name = tool.name,
        description = tool.description,
        metric = tool.metric,
        diameterUm = tool.diameterUm,
        toothCount = tool.toothCount,
        zOffsetUm = tool.zOffsetUm,
        xOffsetUm = tool.xOffsetUm,
        internal = tool.internal,
        geometryJson = tool.geometry?.let { geometry -> json.encodeToString(ToolGeometry.serializer(), geometry) },
        updatedOn = tool.updatedOn,
        id = tool.id,
    )
}

/** The tool kind of [machineType]: a mill and a horizontal mill use the mill tools. */
fun toolKindFor(machineType: MachineType): ToolKind = if (machineType.isLathe) ToolKind.Lathe else ToolKind.Mill

/**
 * Compares two texts in the natural order: a run of digits compares as a number, so "Datum 2"
 * comes before "Datum 10". Letters compare without the letter case. Used for the machine datum
 * list.
 */
val NaturalOrder: Comparator<String> = Comparator { left, right ->
    val leftParts = naturalParts(left)
    val rightParts = naturalParts(right)
    for (index in 0 until minOf(leftParts.size, rightParts.size)) {
        val a = leftParts[index]
        val b = rightParts[index]
        val result = if (a.first().isDigit() && b.first().isDigit()) {
            val aDigits = a.trimStart('0')
            val bDigits = b.trimStart('0')
            if (aDigits.length != bDigits.length) aDigits.length.compareTo(bDigits.length) else aDigits.compareTo(bDigits)
        } else {
            a.compareTo(b, ignoreCase = true)
        }
        if (result != 0) return@Comparator result
    }
    if (leftParts.size != rightParts.size) leftParts.size.compareTo(rightParts.size) else left.compareTo(right)
}

private fun naturalParts(text: String): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val digit = text[start].isDigit()
        var end = start + 1
        while (end < text.length && text[end].isDigit() == digit) end++
        parts += text.substring(start, end)
        start = end
    }
    return parts
}

/** The name of a store rule test: trims [name] and compares without the letter case. */
internal fun sameName(left: String, right: String): Boolean = left.trim().equals(right.trim(), ignoreCase = true)
