package org.superdro.core.readout.operations

import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame

/**
 * The axis operations. All values are internal units after the
 * post-processor (the shown units).
 *
 * | Operation | Effect |
 * |---|---|
 * | Set absolute value `V`: `h(V)` | `A = post.c(V) − (R + T)` |
 * | Set the axis to `V` in absolute mode: `L(axis, V)` | `h(V)`, then `rel = false`. Nothing for a disabled axis. |
 * | Set incremental value `V`: `i(V)` | `I = post.c(V) − abs`, `rel = true`. A sub-datum with a coordinate on the axis becomes not active. |
 * | Clear the absolute offset: `c()` | `A = 0` |
 * | Clear the incremental offset: `d()` | `I = 0` |
 * | Toggle the mode | `rel = !rel`. The old `I` stays. |
 * | Half | `i(shown / 2)`; `L(axis, shown / 2)` when `short_press_zero_set` is on and the axis is absolute |
 * | Center (absolute, axis details) | `L(axis, shown / 2)` |
 * | Hold, first tap | `heldValue = shown` |
 * | Hold, second tap | `i(heldValue)` if `rel`, else `h(heldValue)`. Then the hold stops. |
 * | Hold, long press | The hold stops. No offset changes. |
 * | Keypad value `V` | `i(V)` in incremental mode, `h(V)` in absolute mode |
 *
 * With the X/Y alignment rotation, `h` and `c` on X or Y change the absolute offsets of
 * both axes, because the absolute offsets are in the machine frame. The instrument offset `H`
 * adds to `R + T` in the measurement mode.
 *
 * An operation that needs a position does nothing when the axis has no value (`R` is not
 * finite). Each operation gives an [OperationResult] with the undo snapshot.
 */
object AxisOperations {

    /** `h(V)`: sets the absolute value of [axis] to the shown value [shown]. */
    fun setAbsolute(frame: ReadoutFrame, axis: AxisId, shown: Double): OperationResult =
        result(frame) { base -> setAbsoluteValue(frame.with(base), axis, shown) }

    /** `L(axis, V)`: `h(V)`, then `rel = false`. Nothing for a disabled axis. */
    fun setAxis(frame: ReadoutFrame, axis: AxisId, shown: Double): OperationResult =
        result(frame) { base -> setAxisValue(frame.with(base), axis, shown) }

    /** `i(V)`: sets the incremental value of [axis] to the shown value [shown]. */
    fun setIncremental(frame: ReadoutFrame, axis: AxisId, shown: Double): OperationResult =
        result(frame) { base -> setIncrementalValue(frame.with(base), axis, shown) }

    /** `c()`: `A = 0`. With the X/Y alignment, X and Y both. */
    fun clearAbsolute(frame: ReadoutFrame, axis: AxisId): OperationResult =
        result(frame) { base -> clearAbsoluteOffset(frame.with(base), axis) }

    /** `d()`: `I = 0`. */
    fun clearIncremental(frame: ReadoutFrame, axis: AxisId): OperationResult =
        result(frame) { base -> clearIncrementalOffset(base, axis) }

    /** Toggles the mode: `rel = !rel`. The old `I` stays. */
    fun toggleMode(frame: ReadoutFrame, axis: AxisId): OperationResult =
        result(frame) { base -> base.updateAxis(axis) { state -> state.copy(relative = !state.relative) } }

    /**
     * Half: `i(shown / 2)`. When `short_press_zero_set` is on and the axis is absolute, it sets
     * the absolute value `shown / 2`: `L(axis, shown / 2)`.
     */
    fun half(frame: ReadoutFrame, axis: AxisId): OperationResult = result(frame) { base ->
        val current = frame.with(base)
        val reading = current.reading(axis)
        if (!reading.hasValue) {
            base
        } else if (frame.profile.display.shortPressZero && !reading.incremental) {
            setAxisValue(current, axis, reading.shown / 2.0)
        } else {
            setIncrementalValue(current, axis, reading.shown / 2.0)
        }
    }

    /** Center (absolute, axis details): `L(axis, shown / 2)`. */
    fun center(frame: ReadoutFrame, axis: AxisId): OperationResult = result(frame) { base ->
        val current = frame.with(base)
        val reading = current.reading(axis)
        if (reading.hasValue) setAxisValue(current, axis, reading.shown / 2.0) else base
    }

    /** The keypad value [shown]: `i(V)` in incremental mode, `h(V)` in absolute mode. */
    fun keypadValue(frame: ReadoutFrame, axis: AxisId, shown: Double): OperationResult = result(frame) { base ->
        val current = frame.with(base)
        if (current.reading(axis).incremental) setIncrementalValue(current, axis, shown) else setAbsoluteValue(current, axis, shown)
    }

    /**
     * The keypad text [text]: `++x` gives `current + x`, `--x` gives
     * `current − x`, and other text goes to [evaluate]. The value is in the display unit of the
     * axis. The converter of the axis changes it to the internal unit. Then [keypadValue] applies.
     *
     * Example: X shows 1.2500 in, absolute. `++0.5` gives 1.75 in = 44450 µm, and the action calls
     * `h(44450)`.
     *
     * @param evaluate The expression evaluator.
     * @return The result, or the failure of [evaluate].
     */
    fun keypadText(
        frame: ReadoutFrame,
        axis: AxisId,
        text: String,
        evaluate: (String) -> Result<Double>,
    ): Result<OperationResult> {
        val reading = frame.reading(axis)
        return KeypadEntry.resolve(text, reading.display, evaluate).map { display ->
            keypadValue(frame, axis, frame.converter(axis).toInternal(display))
        }
    }

    /**
     * Hold, tap. The first tap keeps the shown value: the display shows it. The second tap sets
     * the held value at the new position: `i(heldValue)` if `rel`, else `h(heldValue)`. Then the
     * hold stops. The first tap needs a finite value.
     */
    fun holdTap(frame: ReadoutFrame, axis: AxisId): OperationResult {
        val held = frame.hold[axis]
        val reading = frame.reading(axis)
        if (held == null) {
            if (!reading.shown.isFinite()) return OperationResult(frame.workspace, frame.hold, null)
            return OperationResult(frame.workspace, frame.hold + (axis to reading.shown), null)
        }
        val released = frame.hold - axis
        val operation = if (reading.incremental) {
            setIncremental(frame.with(hold = released), axis, held)
        } else {
            setAbsolute(frame.with(hold = released), axis, held)
        }
        return operation.copy(hold = released)
    }

    /** Hold, long press: the hold stops. No offset changes. */
    fun holdLongPress(frame: ReadoutFrame, axis: AxisId): OperationResult =
        OperationResult(frame.workspace, frame.hold - axis, null)

    /**
     * Runs [change] on the workspace of [frame] with the state of each axis. Gives the result with
     * the undo snapshot.
     */
    internal fun result(frame: ReadoutFrame, change: (Workspace) -> Workspace): OperationResult {
        val base = complete(frame)
        val after = change(base)
        return OperationResult(after, frame.hold, UndoSnapshot.between(base, after))
    }

    /** Gives the workspace of [frame] with the state of each axis. */
    internal fun complete(frame: ReadoutFrame): Workspace =
        frame.workspace.copy(axisStates = AxisId.entries.associateWith { axis -> frame.state(axis) })

    internal fun setAbsoluteValue(frame: ReadoutFrame, axis: AxisId, shown: Double): Workspace =
        setAbsoluteTargets(frame, mapOf(axis to frame.postProcessor(axis).toInternal(shown)))

    /**
     * Sets the absolute value of each axis of [targets] (internal units, before the
     * post-processor, in the display frame). With the X/Y alignment, the pair of X and Y goes
     * back to the machine frame first. An axis with no value does not change.
     */
    internal fun setAbsoluteTargets(frame: ReadoutFrame, targets: Map<AxisId, Double>): Workspace {
        var workspace = frame.workspace
        val rotation = frame.alignment
        val planeTargets = targets.filterKeys { axis -> axis == AxisId.X || axis == AxisId.Y }
        if (rotation != null && planeTargets.isNotEmpty()) {
            val x = planeTargets[AxisId.X] ?: frame.reading(AxisId.X).absolute
            val y = planeTargets[AxisId.Y] ?: frame.reading(AxisId.Y).absolute
            val machine = rotation.inverse(x, y)
            val offsetX = machine.x - frame.machineOffsets(AxisId.X)
            val offsetY = machine.y - frame.machineOffsets(AxisId.Y)
            if (offsetX.isFinite() && offsetY.isFinite()) {
                workspace = workspace.updateAxis(AxisId.X) { state -> state.copy(absoluteOffset = offsetX) }
                workspace = workspace.updateAxis(AxisId.Y) { state -> state.copy(absoluteOffset = offsetY) }
            }
        }
        targets.forEach { (axis, target) ->
            if (rotation != null && (axis == AxisId.X || axis == AxisId.Y)) return@forEach
            val base = if (axis == AxisId.Tachometer) frame.r(axis) else frame.machineOffsets(axis)
            val offset = target - base
            if (offset.isFinite()) workspace = workspace.updateAxis(axis) { state -> state.copy(absoluteOffset = offset) }
        }
        return workspace
    }

    internal fun setAxisValue(frame: ReadoutFrame, axis: AxisId, shown: Double): Workspace {
        if (!frame.enabled(axis)) return frame.workspace
        if (!frame.reading(axis).hasValue) return frame.workspace
        return setAbsoluteValue(frame, axis, shown).updateAxis(axis) { state -> state.copy(relative = false) }
    }

    internal fun setIncrementalValue(frame: ReadoutFrame, axis: AxisId, shown: Double): Workspace {
        val reading = frame.reading(axis)
        val offset = frame.postProcessor(axis).toInternal(shown) - reading.absolute
        if (!offset.isFinite()) return frame.workspace
        var workspace = frame.workspace.updateAxis(axis) { state -> state.copy(incrementalOffset = offset, relative = true) }
        if (frame.subDatumOffsets.containsKey(axis)) workspace = workspace.copy(activeSubDatumId = null)
        return workspace
    }

    internal fun clearAbsoluteOffset(frame: ReadoutFrame, axis: AxisId): Workspace {
        var workspace = frame.workspace.updateAxis(axis) { state -> state.copy(absoluteOffset = 0.0) }
        if (frame.alignment != null && (axis == AxisId.X || axis == AxisId.Y)) {
            val other = if (axis == AxisId.X) AxisId.Y else AxisId.X
            workspace = workspace.updateAxis(other) { state -> state.copy(absoluteOffset = 0.0) }
        }
        return workspace
    }

    internal fun clearIncrementalOffset(workspace: Workspace, axis: AxisId): Workspace =
        workspace.updateAxis(axis) { state -> state.copy(incrementalOffset = 0.0) }
}

/** Gives the workspace with the state of [axis] changed by [change]. */
internal fun Workspace.updateAxis(axis: AxisId, change: (AxisState) -> AxisState): Workspace {
    val state = axisStates[axis] ?: AxisState()
    return copy(axisStates = axisStates + (axis to change(state)))
}

/**
 * The relative entries of the keypad: `++x` gives
 * `current + x`, and `--x` gives `current − x`. Other text goes to the expression evaluator.
 */
object KeypadEntry {

    /**
     * Gives the display value of [text] for the current display value [current]. [evaluate]
     * evaluates `x` of the relative forms and all other text.
     */
    fun resolve(text: String, current: Double, evaluate: (String) -> Result<Double>): Result<Double> {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("++") -> evaluate(trimmed.substring(2)).map { value -> current + value }
            trimmed.startsWith("--") -> evaluate(trimmed.substring(2)).map { value -> current - value }
            else -> evaluate(trimmed)
        }
    }
}
