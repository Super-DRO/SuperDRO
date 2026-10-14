package org.superdro.core.ui.readout

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.superdro.core.readout.ReadoutFrame

/**
 * True when [other] has the same structure as this frame: the same machine profile, workspace,
 * reference instrument, hold values, and projected axes. Only the sums of the inputs can be
 * different.
 *
 * The profile, the workspace, and the instrument are immutable data classes. A change of the
 * store gives new objects, so the compare is fast: the data class `equals` first compares the
 * identity. Two objects with equal values also give true.
 */
fun ReadoutFrame.sameStructure(other: ReadoutFrame): Boolean =
    this === other ||
        (profile == other.profile && workspace == other.workspace && instrument == other.instrument &&
            hold == other.hold && projectedAxes == other.projectedAxes)

/**
 * Gives only the frames that change the structure ([sameStructure]): a new profile, workspace,
 * instrument, hold value, or projected axis. A frame that changes only the live position does not
 * come through.
 *
 * Use the frames of this flow only for their structural parts: the profile, the workspace, the
 * axis states, the units, and the labels. Their readings can be old. To read the live position,
 * read `TabReadout.frame.value`.
 */
fun Flow<ReadoutFrame?>.structure(): Flow<ReadoutFrame?> = distinctUntilChanged { old, new ->
    if (old == null || new == null) old === new else old.sameStructure(new)
}
