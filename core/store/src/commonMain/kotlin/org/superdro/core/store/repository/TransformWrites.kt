package org.superdro.core.store.repository

import kotlin.time.Instant
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.SubDatum
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.db.Workspace_undo

/** The item kinds of `workspace_undo_item`. */
private const val UNDO_SUB_DATUM = "SubDatum"
private const val UNDO_GUIDE = "Guide"
private const val UNDO_SHAPE = "Shape"

private val offsetsSerializer = MapSerializer(AxisId.serializer(), Double.serializer())

/**
 * Writes the transform [result] to the workspace [workspaceId] in the open transaction
 * and keeps a copy of the old rows.
 *
 * The steps: read the old rows, remove the old undo copy, deactivate the active sub-datum, write
 * the new sub-datums, guides, shapes, image matrix, and absolute offsets, then write the new undo
 * copy. The undo copy comes last, because the triggers of `TransformUndo.sq` remove a copy when
 * the data that it restores changes.
 *
 * @return The new undo copy, or null when the workspace does not exist or the result changes no
 *   stored value (an identity transform: no write, no copy).
 */
internal fun SuperDroDatabase.writeTransform(
    json: Json,
    now: Instant,
    workspaceId: String,
    result: WorkspaceTransformResult,
): TransformUndo? {
    val workspaceRow = workspaceQueries.selectById(workspaceId).executeAsOneOrNull() ?: return null
    val oldSubDatums = subDatumQueries.selectForWorkspace(workspaceId).executeAsList().toSubDatums()
    val oldGuides = subDatumQueries.selectGuides(workspaceId).executeAsList().map { row -> row.toModel() }
    val drawing = drawingQueries.selectForWorkspace(workspaceId).executeAsOneOrNull()
    val newShapes = result.shapes?.takeIf { drawing != null }
    val oldShapes = newShapes?.let { drawingQueries.selectShapes(drawing!!.id).executeAsList().map { row -> row.toModel() } }
    val oldImage = json.referenceImageFrom(workspaceRow.reference_image_json)
    val newImage = result.referenceImageMatrix?.let { matrix -> oldImage?.copy(matrix = matrix) }
    val axisRows = workspaceQueries.selectAxisStates(workspaceId).executeAsList().associateBy { row -> row.axis }
    val oldOffsets = result.absoluteOffsets.keys.associateWith { axis -> axisRows[axis]?.absolute_offset ?: 0.0 }

    val sameSubDatums = oldSubDatums.map { it.withoutTimes() } == result.subDatums.map { it.withoutTimes() }
    val sameGuides = oldGuides.map { it.copy(createdOn = Instant.DISTANT_PAST) } ==
        result.guides.map { it.copy(createdOn = Instant.DISTANT_PAST) }
    val sameShapes = newShapes == null || newShapes == oldShapes
    val sameImage = newImage == null || newImage == oldImage
    val sameOffsets = result.absoluteOffsets == oldOffsets
    if (sameSubDatums && sameGuides && sameShapes && sameImage && sameOffsets) return null

    transformUndoQueries.deleteUndo(workspaceId)

    // The store deactivates the active sub-datum first.
    val state = json.workspaceFrom(workspaceRow, axisRows.values.toList())
    if (state.activeSubDatumId != null) {
        workspaceQueries.updateState(json.workspaceStateJson(state.copy(activeSubDatumId = null)), now, workspaceId)
    }

    writeSubDatumList(workspaceId, oldSubDatums, result.subDatums.map { it.copy(updatedOn = now) })
    writeGuideList(workspaceId, oldGuides, result.guides)
    if (newShapes != null && drawing != null) {
        drawingQueries.deleteShapes(drawing.id)
        insertShapes(drawing.id, newShapes)
    }
    if (newImage != null && !sameImage) {
        workspaceQueries.updateReferenceImage(json.referenceImageJson(newImage), now, workspaceId)
    }
    result.absoluteOffsets.forEach { (axis, offset) -> writeAbsoluteOffset(workspaceId, axis, offset) }

    transformUndoQueries.insertUndo(
        workspace_id = workspaceId,
        label = result.label,
        created_on = now,
        has_shapes = oldShapes != null,
        has_image = newImage != null,
        reference_image_json = oldImage?.takeIf { newImage != null }?.let { json.referenceImageJson(it) },
        absolute_offsets_json = oldOffsets.takeIf { it.isNotEmpty() }?.let { json.encodeToString(offsetsSerializer, it) },
    )
    oldSubDatums.forEachIndexed { seq, item ->
        transformUndoQueries.insertUndoItem(workspaceId, UNDO_SUB_DATUM, seq, json.encodeToString(SubDatum.serializer(), item))
    }
    oldGuides.forEachIndexed { seq, item ->
        transformUndoQueries.insertUndoItem(workspaceId, UNDO_GUIDE, seq, json.encodeToString(Guide.serializer(), item))
    }
    oldShapes?.forEachIndexed { seq, item ->
        transformUndoQueries.insertUndoItem(workspaceId, UNDO_SHAPE, seq, json.encodeToString(DrawingShape.serializer(), item))
    }
    return TransformUndo(workspaceId = workspaceId, label = result.label, createdOn = now)
}

/**
 * Writes the undo copy of the workspace [workspaceId] back in the open transaction and removes
 * the copy. The active sub-datum stays not active.
 *
 * @return False when the workspace has no undo copy.
 */
internal fun SuperDroDatabase.writeUndo(json: Json, now: Instant, workspaceId: String): Boolean {
    val undo = transformUndoQueries.selectUndo(workspaceId).executeAsOneOrNull() ?: return false
    // Read the full copy first: the writes below start the triggers that remove the copy.
    val subDatums = undoItems(workspaceId, UNDO_SUB_DATUM).map { text -> json.decodeFromString(SubDatum.serializer(), text) }
    val guides = undoItems(workspaceId, UNDO_GUIDE).map { text -> json.decodeFromString(Guide.serializer(), text) }
    val shapes = if (undo.has_shapes) {
        undoItems(workspaceId, UNDO_SHAPE).map { text -> json.decodeFromString(DrawingShape.serializer(), text) }
    } else {
        null
    }
    val image = undo.oldImage(json)
    val offsets = undo.absolute_offsets_json?.let { text -> json.decodeFromString(offsetsSerializer, text) }.orEmpty()

    val currentSubDatums = subDatumQueries.selectForWorkspace(workspaceId).executeAsList().toSubDatums()
    val currentGuides = subDatumQueries.selectGuides(workspaceId).executeAsList().map { row -> row.toModel() }
    writeSubDatumList(workspaceId, currentSubDatums, subDatums)
    writeGuideList(workspaceId, currentGuides, guides)
    if (shapes != null) {
        drawingQueries.selectForWorkspace(workspaceId).executeAsOneOrNull()?.let { drawing ->
            drawingQueries.deleteShapes(drawing.id)
            insertShapes(drawing.id, shapes)
        }
    }
    if (undo.has_image) {
        workspaceQueries.updateReferenceImage(json.referenceImageJson(image), now, workspaceId)
    }
    offsets.forEach { (axis, offset) -> writeAbsoluteOffset(workspaceId, axis, offset) }
    transformUndoQueries.deleteUndo(workspaceId)
    return true
}

internal fun Workspace_undo.toModel(): TransformUndo =
    TransformUndo(workspaceId = workspace_id, label = label, createdOn = created_on)

private fun Workspace_undo.oldImage(json: Json): ReferenceImage? = json.referenceImageFrom(reference_image_json)

private fun SuperDroDatabase.undoItems(workspaceId: String, kind: String): List<String> =
    transformUndoQueries.selectUndoItems(workspaceId, kind).executeAsList().map { row -> row.item_json }

/**
 * Makes the sub-datums of the workspace equal to [target]: deletes each sub-datum of [current]
 * that is not in [target], and writes each item of [target]. An existing row keeps its place in
 * the list.
 */
private fun SuperDroDatabase.writeSubDatumList(workspaceId: String, current: List<SubDatum>, target: List<SubDatum>) {
    val keep = target.mapTo(HashSet()) { item -> item.id }
    current.filter { item -> item.id !in keep }.forEach { item -> subDatumQueries.deleteById(item.id) }
    target.forEach { item -> writeSubDatum(workspaceId, item) }
}

private fun SuperDroDatabase.writeGuideList(workspaceId: String, current: List<Guide>, target: List<Guide>) {
    val keep = target.mapTo(HashSet()) { item -> item.id }
    current.filter { item -> item.id !in keep }.forEach { item -> subDatumQueries.deleteGuide(item.id) }
    target.forEach { item -> writeGuide(workspaceId, item) }
}

/** Sets the absolute offset of one axis. An axis with no row gets a row with the default state. */
internal fun SuperDroDatabase.writeAbsoluteOffset(workspaceId: String, axis: AxisId, offset: Double) {
    val row = workspaceQueries.selectAxisStates(workspaceId).executeAsList().firstOrNull { it.axis == axis }
    val state = row?.toModel() ?: AxisState()
    writeAxisState(workspaceId, axis, state.copy(absoluteOffset = offset))
}

private fun SubDatum.withoutTimes(): SubDatum = copy(createdOn = Instant.DISTANT_PAST, updatedOn = Instant.DISTANT_PAST)
