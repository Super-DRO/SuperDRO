package org.superdro.core.store.repository

import kotlin.time.Instant
import org.superdro.core.model.AxisId
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.SubDatum

/**
 * The result of one workspace transform (flip, rotate, scale, translate, or a pair command) as
 * plain new rows. `core:functions` computes the values. The store
 * writes them in one transaction and keeps a copy of the old rows for the undo.
 *
 * The values are in the workspace frame. Lengths are µm, drawing coordinates are nm.
 *
 * @property label The name of the transform for the undo, for example `Flip`.
 * @property subDatums The full new list of the sub-datums of the workspace. A sub-datum of the
 *   workspace that is not in the list is deleted. A guide that a rotation converts to a point is
 *   a new sub-datum here.
 * @property guides The full new list of the guides. A guide that is not in the list is deleted.
 * @property shapes The new shapes of the drawing in the order of the drawing, or null when the
 *   transform does not change the drawing or the workspace has no drawing.
 * @property referenceImageMatrix The new 9 values of the image matrix, or null when the
 *   transform does not change the reference image or the workspace has no image.
 * @property absoluteOffsets The new absolute offset of each axis that the transform moves, for
 *   example the translate of "Align Absolute Origin to Sub-Datum". Empty when
 *   the transform does not move the absolute origin.
 */
data class WorkspaceTransformResult(
    val label: String,
    val subDatums: List<SubDatum>,
    val guides: List<Guide>,
    val shapes: List<DrawingShape>? = null,
    val referenceImageMatrix: List<Double>? = null,
    val absoluteOffsets: Map<AxisId, Double> = emptyMap(),
)

/**
 * The undo copy of the last transform of a workspace. The workspace menu shows
 * "Undo last transform" while the copy is there. A later change of the data that the copy
 * restores removes the copy, so the undo never removes a later change of the user.
 *
 * @property label The [WorkspaceTransformResult.label] of the transform.
 * @property createdOn The time of the transform. The snackbar shows "Undo" for 10 s after it.
 */
data class TransformUndo(
    val workspaceId: String,
    val label: String,
    val createdOn: Instant,
)
