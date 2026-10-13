package org.superdro.core.store.repository

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum
import org.superdro.core.model.ThreadSpec
import org.superdro.core.model.Tool
import org.superdro.core.model.newId
import org.superdro.core.store.db.Archived_item
import org.superdro.core.store.db.Machine_profile
import org.superdro.core.store.db.SuperDroDatabase

/** The item kinds of `archived_item`. */
internal object ArchiveKind {
    const val TOOL = "Tool"
    const val SUB_DATUM = "SubDatum"
    const val SUB_DATUM_Y = "SubDatumY"
    const val GUIDE = "Guide"
    const val MACHINE_DATUM = "MachineDatum"
    const val MACHINE_DATUM_Y = "MachineDatumY"
    const val THREAD_SPEC = "ThreadSpec"
}

/** Gives the confirmation list of a change of the profile [row] to [to]. */
internal fun SuperDroDatabase.planMachineTypeChange(json: Json, row: Machine_profile, to: MachineType): MachineTypeChangePlan {
    val from = row.machine_type
    if (from == to) return MachineTypeChangePlan(row.id, from, to, emptyList(), emptyList())
    val mismatches = mutableListOf<Mismatch>()
    val conversions = mutableListOf<ConversionKind>()
    val newKind = toolKindFor(to)
    val tools = toolQueries.selectForProfile(row.id).executeAsList()
    tools.count { tool -> tool.kind != newKind }.takeIf { it > 0 }?.let { mismatches += Mismatch(MismatchKind.ProfileTools, it) }
    if (!from.isLathe && !to.isLathe) conversions += ConversionKind.SharedMillTools

    val workspaceIds = workspaceQueries.selectForProfile(row.id).executeAsList().map { workspace -> workspace.id }
    if (to.isLathe) {
        val subDatums = workspaceIds.flatMap { id -> subDatumQueries.selectRowsForWorkspace(id).executeAsList() }
        subDatums.count { point -> point.y_um != null }.takeIf { it > 0 }
            ?.let { mismatches += Mismatch(MismatchKind.SubDatumYCoordinates, it) }
        val guides = workspaceIds.flatMap { id -> subDatumQueries.selectGuides(id).executeAsList() }
        guides.count { guide -> guide.axis == AxisId.Y }.takeIf { it > 0 }
            ?.let { mismatches += Mismatch(MismatchKind.YGuides, it) }
        machineDatumQueries.selectForProfile(row.id).executeAsList().count { datum -> datum.y_um != null }.takeIf { it > 0 }
            ?.let { mismatches += Mismatch(MismatchKind.MachineDatumYCoordinates, it) }
    } else if (from.isLathe) {
        threadSpecQueries.selectForProfile(row.id).executeAsList().size.takeIf { it > 0 }
            ?.let { mismatches += Mismatch(MismatchKind.LatheThreads, it) }
    }
    if (from.isLathe != to.isLathe) {
        conversions += ConversionKind.WorkspacePlane
        conversions += ConversionKind.DiameterMode
        conversions += ConversionKind.PatternAngleConvention
    }
    if (to.isLathe && json.profileFrom(row).machineMode == MachineMode.Measurement) conversions += ConversionKind.MachineMode
    if (archiveQueries.selectForProfile(row.id).executeAsList().any { item -> item.fits(json, to) }) {
        conversions += ConversionKind.RestoreFromArchive
    }
    return MachineTypeChangePlan(row.id, from, to, mismatches, conversions)
}

/**
 * Changes the machine type of the profile [row] to [to] in the open transaction.
 * The data that does not fit goes to the archive or is deleted ([disposal]). The store converts
 * the rest. Archived data that fits [to] comes back.
 */
internal fun SuperDroDatabase.writeMachineTypeChange(
    json: Json,
    now: Instant,
    row: Machine_profile,
    to: MachineType,
    disposal: MismatchDisposal,
): MachineTypeChangePlan {
    val plan = planMachineTypeChange(json, row, to)
    val from = row.machine_type
    if (from == to) return plan
    val archive = disposal == MismatchDisposal.Archive

    fun keep(workspaceId: String?, kind: String, itemId: String, itemJson: String) {
        if (archive) archiveQueries.insert(newId(), row.id, workspaceId, kind, itemId, from, itemJson, now)
    }

    val newKind = toolKindFor(to)
    toolQueries.selectForProfile(row.id).executeAsList().filter { tool -> tool.kind != newKind }.forEach { tool ->
        keep(null, ArchiveKind.TOOL, tool.id, json.encodeToString(Tool.serializer(), json.toolFrom(tool)))
        toolQueries.deleteById(tool.id)
    }

    val workspaces = workspaceQueries.selectForProfile(row.id).executeAsList()
    if (to.isLathe) {
        workspaces.forEach { workspace ->
            subDatumQueries.selectForWorkspace(workspace.id).executeAsList().toSubDatums().filter { it.y != null }.forEach { point ->
                val text = json.encodeToString(SubDatum.serializer(), point)
                if (point.x == null && point.z == null && point.a == null) {
                    keep(workspace.id, ArchiveKind.SUB_DATUM, point.id, text)
                    subDatumQueries.deleteById(point.id)
                } else {
                    keep(workspace.id, ArchiveKind.SUB_DATUM_Y, point.id, text)
                    subDatumQueries.setY(null, now, point.id)
                }
            }
            subDatumQueries.selectGuides(workspace.id).executeAsList().filter { guide -> guide.axis == AxisId.Y }.forEach { guide ->
                keep(workspace.id, ArchiveKind.GUIDE, guide.id, json.encodeToString(Guide.serializer(), guide.toModel()))
                subDatumQueries.deleteGuide(guide.id)
            }
        }
        machineDatumQueries.selectForProfile(row.id).executeAsList().filter { it.y_um != null }.forEach { datumRow ->
            val datum = datumRow.toModel()
            val text = json.encodeToString(MachineDatum.serializer(), datum)
            if (datum.x == null && datum.z == null && datum.a == null) {
                keep(null, ArchiveKind.MACHINE_DATUM, datum.id, text)
                machineDatumQueries.deleteById(datum.id)
            } else {
                keep(null, ArchiveKind.MACHINE_DATUM_Y, datum.id, text)
                machineDatumQueries.setY(null, now, datum.id)
            }
        }
    } else if (from.isLathe) {
        threadSpecQueries.selectForProfile(row.id).executeAsList().forEach { threadRow ->
            keep(threadRow.workspace_id, ArchiveKind.THREAD_SPEC, threadRow.id, json.encodeToString(ThreadSpec.serializer(), json.threadFrom(threadRow)))
            threadSpecQueries.deleteById(threadRow.id)
        }
    }

    // The conversions of the workspace state: the plane, the X diameter mode, the armed thread.
    if (from.isLathe != to.isLathe) {
        workspaces.forEach { workspace ->
            val state = json.workspaceFrom(workspace, workspaceQueries.selectAxisStates(workspace.id).executeAsList())
            val plane = when {
                to.isLathe -> Plane.ZX
                state.plane == Plane.ZX -> Plane.XY
                else -> state.plane
            }
            val converted = state.copy(plane = plane, armedThreadId = if (to.isLathe) state.armedThreadId else null)
            workspaceQueries.updateState(json.workspaceStateJson(converted), now, workspace.id)
            val x = state.axisStates.getValue(AxisId.X)
            writeAxisState(workspace.id, AxisId.X, x.copy(diameterMode = to.isLathe))
        }
    }

    restoreArchive(json, now, row.id, to)

    val settings = json.profileFrom(row)
    val convention = when {
        to.isLathe -> PatternAngleConvention.Legacy
        from.isLathe -> PatternAngleConvention.Standard
        else -> settings.patternAngleConvention
    }
    val converted = settings.copy(
        machineType = to,
        machineMode = if (to.isLathe) MachineMode.Cutting else settings.machineMode,
        patternAngleConvention = convention,
    )
    machineProfileQueries.update(
        name = row.name,
        machineType = to,
        metricDefault = row.metric_default,
        useSharedTools = row.use_shared_tools,
        adapterProfileId = row.adapter_profile_id,
        activeWorkspaceId = row.active_workspace_id,
        configJson = json.profileConfigJson(converted),
        updatedOn = now,
        id = row.id,
    )
    return plan
}

/** True when the archived item fits a profile of the type [type]. */
private fun Archived_item.fits(json: Json, type: MachineType): Boolean = when (kind) {
    ArchiveKind.TOOL -> json.decodeFromString(Tool.serializer(), item_json).kind == toolKindFor(type)
    ArchiveKind.THREAD_SPEC -> type.isLathe
    else -> !type.isLathe
}

/** Puts back each archived item of the profile that fits [type], when its place is free. */
private fun SuperDroDatabase.restoreArchive(json: Json, now: Instant, profileId: String, type: MachineType) {
    archiveQueries.selectForProfile(profileId).executeAsList().filter { item -> item.fits(json, type) }.forEach { item ->
        when (item.kind) {
            ArchiveKind.TOOL -> if (toolQueries.selectById(item.item_id).executeAsOneOrNull() == null) {
                insertTool(json, profileId, json.decodeFromString(Tool.serializer(), item.item_json))
            }
            ArchiveKind.SUB_DATUM -> item.workspace_id?.let { workspaceId ->
                if (subDatumQueries.selectById(item.item_id).executeAsOneOrNull() == null) {
                    writeSubDatum(workspaceId, json.decodeFromString(SubDatum.serializer(), item.item_json))
                }
            }
            ArchiveKind.SUB_DATUM_Y -> subDatumQueries.selectById(item.item_id).executeAsOneOrNull()?.let { current ->
                if (current.y_um == null) {
                    subDatumQueries.setY(json.decodeFromString(SubDatum.serializer(), item.item_json).y, now, current.id)
                }
            }
            ArchiveKind.GUIDE -> item.workspace_id?.let { workspaceId ->
                if (subDatumQueries.selectGuideById(item.item_id).executeAsOneOrNull() == null) {
                    writeGuide(workspaceId, json.decodeFromString(Guide.serializer(), item.item_json))
                }
            }
            ArchiveKind.MACHINE_DATUM -> if (machineDatumQueries.selectById(item.item_id).executeAsOneOrNull() == null) {
                writeMachineDatum(profileId, json.decodeFromString(MachineDatum.serializer(), item.item_json))
            }
            ArchiveKind.MACHINE_DATUM_Y -> machineDatumQueries.selectById(item.item_id).executeAsOneOrNull()?.let { current ->
                if (current.y_um == null) {
                    machineDatumQueries.setY(json.decodeFromString(MachineDatum.serializer(), item.item_json).y, now, current.id)
                }
            }
            ArchiveKind.THREAD_SPEC -> item.workspace_id?.let { workspaceId ->
                if (threadSpecQueries.selectById(item.item_id).executeAsOneOrNull() == null) {
                    writeThread(json, workspaceId, json.decodeFromString(ThreadSpec.serializer(), item.item_json))
                }
            }
        }
        archiveQueries.deleteById(item.id)
    }
}

internal fun Archived_item.toModel(): ArchivedItem = ArchivedItem(
    id = id,
    profileId = profile_id,
    workspaceId = workspace_id,
    kind = kind,
    itemId = item_id,
    machineType = machine_type,
    itemJson = item_json,
)
