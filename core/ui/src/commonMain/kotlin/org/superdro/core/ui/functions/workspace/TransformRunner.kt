package org.superdro.core.ui.functions.workspace

import org.superdro.core.functions.transform.GuidePolicy
import org.superdro.core.functions.transform.WorkspaceTransform
import org.superdro.core.functions.transform.applyTo
import org.superdro.core.functions.transform.countBrokenGuides
import org.superdro.core.model.AxisId
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_blocker_no_workspace
import org.superdro.core.resources.fn_blocker_thread
import org.superdro.core.resources.ws_blocked_operations
import org.superdro.core.store.StoreResult
import org.superdro.core.store.repository.WorkspaceTransformResult
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.UndoOffers
import org.superdro.core.ui.text.UiText

/** The result of [TransformRunner.run]. */
sealed interface TransformRun {
    /** The store wrote the transform. The snackbar offers "Undo" for 10 s. */
    data object Applied : TransformRun

    /** The transform changes no stored value: no write (an identity transform). */
    data object NoChange : TransformRun

    /** A transform runs already: the call did nothing (one transform at a time). */
    data object Busy : TransformRun

    /** The workspace is not there, or the store refused the write. Nothing changed. */
    data object Failed : TransformRun
}

/**
 * The transform pipeline of the workspace commands:
 *
 * 1. Read the full workspace (the sub-datums and the guides) and its drawing.
 * 2. Apply the transform to all stored geometry: the sub-datums, the guides, the shapes of the
 *    drawing, and the reference image matrix. The positions are in the workspace frame.
 * 3. Write the result in one transaction of the store with a copy of the old rows.
 *    The store deactivates the active sub-datum first.
 * 4. Offer "Undo" for 10 s in the snackbar. The workspace menu also has "Undo last
 *    transform" while the store keeps the copy.
 *
 * One transform runs at a time.
 */
class TransformRunner(private val services: FunctionServices, private val undo: UndoOffers) {

    private var running = false

    /** Gives the number of the guides of the workspace [workspaceId] that [transform] cannot keep. */
    suspend fun brokenGuides(workspaceId: String, transform: WorkspaceTransform): Int =
        services.machines.loadWorkspace(workspaceId)?.guides?.let(transform::countBrokenGuides) ?: 0

    /**
     * Runs [transform] on the workspace [workspaceId].
     *
     * @param label The name of the transform in the undo copy of the store, for example `Flip`.
     * @param undoLabel The text of the snackbar.
     * @param absoluteOffsets The new absolute offset of each axis that moves in the same transaction.
     */
    suspend fun run(
        workspaceId: String,
        transform: WorkspaceTransform,
        policy: GuidePolicy,
        label: String,
        undoLabel: UiText,
        absoluteOffsets: Map<AxisId, Double> = emptyMap(),
    ): TransformRun {
        if (running) return TransformRun.Busy
        running = true
        try {
            val workspace = services.machines.loadWorkspace(workspaceId) ?: return TransformRun.Failed
            val drawing = services.drawings.loadDrawing(workspaceId)
            val outcome = transform.applyTo(workspace, drawing, policy)
            if (!outcome.changed && absoluteOffsets.isEmpty()) return TransformRun.NoChange
            val result = WorkspaceTransformResult(
                label = label,
                subDatums = outcome.workspace.subDatums,
                guides = outcome.workspace.guides,
                shapes = outcome.drawing?.shapes,
                referenceImageMatrix = outcome.workspace.referenceImage?.matrix,
                absoluteOffsets = absoluteOffsets,
            )
            return when (val write = services.machines.applyTransform(workspaceId, result)) {
                is StoreResult.Refused -> TransformRun.Failed
                is StoreResult.Ok -> {
                    if (write.value == null) {
                        TransformRun.NoChange
                    } else {
                        undo.offer(undoLabel) { services.machines.undoLastTransform(workspaceId) }
                        TransformRun.Applied
                    }
                }
            }
        } catch (error: Exception) {
            return TransformRun.Failed
        } finally {
            running = false
        }
    }
}

/**
 * The lock of the workspace operations: no transform, no
 * origin command, and no workspace command while a thread is armed. Gives the text, or
 * null when the operations are available.
 */
fun workspaceOperationsBlocked(workspace: Workspace?): UiText? = when {
    workspace == null -> UiText.of(Res.string.ws_blocked_operations, UiText.of(Res.string.fn_blocker_no_workspace))
    workspace.armedThreadId != null -> UiText.of(Res.string.ws_blocked_operations, UiText.of(Res.string.fn_blocker_thread))
    else -> null
}

/** Gives the value of [axis] of this sub-datum: X, Y, Z in µm, A in arc seconds, or null. */
fun SubDatum.valueOf(axis: AxisId): Double? = when (axis) {
    AxisId.X -> x
    AxisId.Y -> y
    AxisId.Z -> z
    AxisId.Angular -> a
    else -> null
}
