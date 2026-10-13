package org.superdro.core.readout.operations

import org.superdro.core.model.AxisButtonConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.ReadoutMessage
import org.superdro.core.readout.ReadoutText

/**
 * The zero buttons (`short_press_zero_set`, default false):
 *
 * | Control | `short_press_zero_set` = false | `short_press_zero_set` = true |
 * |---|---|---|
 * | Axis zero button, tap | `i(0)` | `i(0)` if `rel`, else `L(axis, 0)` |
 * | Axis zero button, long press | `L(axis, 0)` (absolute origin) | No action |
 * | Global Zero Set, tap | For X and each enabled Y, Z, Angular: `i(0)`. Then the active sub-datum becomes not active. | For each enabled axis: `i(0)` if `rel`, else `L(axis, 0)`. Then the sub-datum becomes not active. |
 * | Global Zero Set, long press | `L(axis, 0)` for X, Y, Z, Angular | No action |
 *
 * SuperDRO sets the value at once. The [OperationResult.undo] snapshot restores all offsets that
 * the action changed.
 */
object ZeroButtons {

    /** The axes of the global buttons, in this order. */
    val GLOBAL_AXES: List<AxisId> = listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular)

    /** The axis zero button, tap. */
    fun tap(frame: ReadoutFrame, axis: AxisId): OperationResult = AxisOperations.result(frame) { base ->
        val current = frame.with(base)
        if (!frame.profile.display.shortPressZero || current.reading(axis).incremental) {
            AxisOperations.setIncrementalValue(current, axis, 0.0)
        } else {
            AxisOperations.setAxisValue(current, axis, 0.0)
        }
    }

    /** The axis zero button, long press: `L(axis, 0)`, or no action with `short_press_zero_set` on. */
    fun longPress(frame: ReadoutFrame, axis: AxisId): OperationResult = AxisOperations.result(frame) { base ->
        if (frame.profile.display.shortPressZero) base else AxisOperations.setAxisValue(frame.with(base), axis, 0.0)
    }

    /** The global Zero Set button, tap. */
    fun globalTap(frame: ReadoutFrame): OperationResult = AxisOperations.result(frame) { base ->
        var workspace = base
        val shortPress = frame.profile.display.shortPressZero
        GLOBAL_AXES.forEach { axis ->
            val current = frame.with(workspace)
            if (!shortPress) {
                if (axis == AxisId.X || current.enabled(axis)) workspace = AxisOperations.setIncrementalValue(current, axis, 0.0)
            } else if (current.enabled(axis)) {
                workspace = if (current.reading(axis).incremental) {
                    AxisOperations.setIncrementalValue(current, axis, 0.0)
                } else {
                    AxisOperations.setAxisValue(current, axis, 0.0)
                }
            }
        }
        workspace.copy(activeSubDatumId = null)
    }

    /** The global Zero Set button, long press: `L(axis, 0)` for X, Y, Z, Angular, or no action. */
    fun globalLongPress(frame: ReadoutFrame): OperationResult = AxisOperations.result(frame) { base ->
        if (frame.profile.display.shortPressZero) {
            base
        } else {
            var workspace = base
            GLOBAL_AXES.forEach { axis -> workspace = AxisOperations.setAxisValue(frame.with(workspace), axis, 0.0) }
            workspace
        }
    }
}

/** The state of the global Mode button. */
enum class GlobalModeState { Absolute, Incremental, Mixed }

/** The state of the global Units button. */
enum class GlobalUnitsState { Inch, Metric, Mixed }

/**
 * The global Mode and Units buttons.
 *
 * | Button | Tap | Long press |
 * |---|---|---|
 * | Mode | If all linear axes are absolute, set `rel = true` on X, Y, Z, and Angular. Else set `rel = false` on them. | Mixed modes: no action. Absolute: `c()` on X and each enabled Y, Z, Angular. Incremental: the sub-datum becomes not active, then `d()` on X and each enabled Y, Z, Angular, and the tachometer. Undo is available. |
 * | Units | If all enabled linear axes show inch, all change to mm. Else all change to inch. The count stops at the first disabled axis from the top. | None |
 */
object GlobalButtons {

    /** The state of the Mode button: the count of all enabled axes of X, Y, Z, and Angular. */
    fun modeState(frame: ReadoutFrame): GlobalModeState {
        val axes = ZeroButtons.GLOBAL_AXES.filter { axis -> frame.enabled(axis) }
        val incremental = axes.count { axis -> frame.reading(axis).incremental }
        return when {
            incremental == 0 -> GlobalModeState.Absolute
            incremental == axes.size -> GlobalModeState.Incremental
            else -> GlobalModeState.Mixed
        }
    }

    /** The Mode button, tap. */
    fun modeTap(frame: ReadoutFrame): OperationResult = AxisOperations.result(frame) { base ->
        val linear = LINEAR.filter { axis -> frame.enabled(axis) }
        val allAbsolute = linear.none { axis -> frame.reading(axis).incremental }
        var workspace = base
        ZeroButtons.GLOBAL_AXES.forEach { axis -> workspace = workspace.updateAxis(axis) { state -> state.copy(relative = allAbsolute) } }
        workspace
    }

    /** The Mode button, long press. */
    fun modeLongPress(frame: ReadoutFrame): OperationResult = AxisOperations.result(frame) { base ->
        when (modeState(frame)) {
            GlobalModeState.Mixed -> base
            GlobalModeState.Absolute -> {
                var workspace = base
                ZeroButtons.GLOBAL_AXES.forEach { axis ->
                    if (axis == AxisId.X || frame.enabled(axis)) workspace = AxisOperations.clearAbsoluteOffset(frame.with(workspace), axis)
                }
                workspace
            }
            GlobalModeState.Incremental -> {
                var workspace = base.copy(activeSubDatumId = null)
                (ZeroButtons.GLOBAL_AXES + AxisId.Tachometer).forEach { axis ->
                    if (axis == AxisId.X || frame.enabled(axis)) workspace = AxisOperations.clearIncrementalOffset(workspace, axis)
                }
                workspace
            }
        }
    }

    /** The state of the Units button: the count of the enabled linear axes. */
    fun unitsState(frame: ReadoutFrame): GlobalUnitsState {
        val axes = LINEAR.filter { axis -> frame.enabled(axis) }
        val metric = axes.count { axis -> frame.metric(axis) }
        return when {
            metric == 0 -> GlobalUnitsState.Inch
            metric == axes.size -> GlobalUnitsState.Metric
            else -> GlobalUnitsState.Mixed
        }
    }

    /**
     * The Units button, tap. The count goes in the row order (mill X, Y, Z; lathe X, Z, Y) and
     * stops at the first disabled axis. All three linear axes change.
     */
    fun unitsTap(frame: ReadoutFrame): OperationResult = AxisOperations.result(frame) { base ->
        val counted = rowOrder(frame).takeWhile { axis -> frame.enabled(axis) }
        val allInch = counted.none { axis -> frame.metric(axis) }
        var workspace = base
        LINEAR.forEach { axis -> workspace = workspace.updateAxis(axis) { state -> state.copy(metric = allInch) } }
        workspace
    }

    /** The row order of the linear axes: mill X, Y, Z; lathe X, Z, then the auxiliary readout Y. */
    fun rowOrder(frame: ReadoutFrame): List<AxisId> =
        if (frame.profile.machineType.isLathe) listOf(AxisId.X, AxisId.Z, AxisId.Y) else LINEAR

    private val LINEAR: List<AxisId> = listOf(AxisId.X, AxisId.Y, AxisId.Z)
}

/**
 * The three action buttons of each axis (`<axis>_button_<n>_mode`):
 *
 * | Mode | Name | Linear axes | Angular axis |
 * |---|---|---|---|
 * | 0 | Set zero | Tap: zero. Long press: absolute zero. | The same |
 * | 1 | ABS or INC | Tap: toggle `rel`. Long press: clear `A` in absolute mode or `I` in incremental mode (undo). | The same |
 * | 2 | Units | Tap: inch or mm | Tap: degrees or divisions |
 * | 3 | Center (1/2) | Tap: half | The same |
 * | 4 | Divide circle | Not offered | Tap: divisions sheet |
 * | 5 | Hold | Tap: hold or set. Long press: cancel the hold. | The same |
 */
object ActionButtons {

    /** Gives the modes that the settings offer for [axis]. Mode 4 is only for the angular axis. */
    fun offeredModes(axis: AxisId): List<AxisButtonMode> =
        if (axis == AxisId.Angular) AxisButtonMode.entries else AxisButtonMode.entries - AxisButtonMode.DivideCircle

    /**
     * Gives the buttons that show: with the side panel open (the collapsed readout), the
     * buttons with `show_collapsed`; else the buttons with `show_expanded`. Mode 4 does not show
     * on a linear axis.
     */
    fun visibleButtons(axis: AxisId, buttons: List<AxisButtonConfig>, collapsed: Boolean): List<AxisButtonConfig> =
        buttons.filter { button ->
            (if (collapsed) button.showCollapsed else button.showExpanded) && button.mode in offeredModes(axis)
        }

    /** A tap on an action button of [axis] with [mode]. */
    fun tap(frame: ReadoutFrame, axis: AxisId, mode: AxisButtonMode): OperationResult = when (mode) {
        AxisButtonMode.SetZero -> ZeroButtons.tap(frame, axis)
        AxisButtonMode.ToggleIncremental -> AxisOperations.toggleMode(frame, axis)
        AxisButtonMode.ToggleUnits -> toggleUnits(frame, axis)
        AxisButtonMode.SetCenter -> AxisOperations.half(frame, axis)
        AxisButtonMode.DivideCircle ->
            if (axis == AxisId.Angular) {
                OperationResult(frame.workspace, frame.hold, null, OperationRequest.OpenDivisionsSheet)
            } else {
                OperationResult(frame.workspace, frame.hold, null)
            }
        AxisButtonMode.Hold -> AxisOperations.holdTap(frame, axis)
    }

    /** A long press on an action button of [axis] with [mode]. */
    fun longPress(frame: ReadoutFrame, axis: AxisId, mode: AxisButtonMode): OperationResult = when (mode) {
        AxisButtonMode.SetZero -> ZeroButtons.longPress(frame, axis)
        AxisButtonMode.ToggleIncremental ->
            if (frame.reading(axis).incremental) AxisOperations.clearIncremental(frame, axis) else AxisOperations.clearAbsolute(frame, axis)
        AxisButtonMode.Hold -> AxisOperations.holdLongPress(frame, axis)
        AxisButtonMode.ToggleUnits, AxisButtonMode.SetCenter, AxisButtonMode.DivideCircle -> OperationResult(frame.workspace, frame.hold, null)
    }

    /**
     * The units tap: inch or mm on a linear axis; degrees or divisions on the angular axis. The
     * divisions mode needs `N` of 1 or more. With no `N`, the action asks for the divisions
     * sheet.
     */
    fun toggleUnits(frame: ReadoutFrame, axis: AxisId): OperationResult {
        if (axis == AxisId.Angular) {
            val state = frame.state(axis)
            val divisions = state.divisionsPerRev
            if (!state.divisionUnits && (divisions == null || divisions < 1)) {
                return OperationResult(frame.workspace, frame.hold, null, OperationRequest.OpenDivisionsSheet)
            }
            return AxisOperations.result(frame) { base -> base.updateAxis(axis) { old -> old.copy(divisionUnits = !old.divisionUnits) } }
        }
        if (axis == AxisId.Tachometer) {
            return AxisOperations.result(frame) { base -> base.updateAxis(axis) { old -> old.copy(surfaceSpeedMode = !old.surfaceSpeedMode) } }
        }
        val metric = frame.metric(axis)
        return AxisOperations.result(frame) { base -> base.updateAxis(axis) { old -> old.copy(metric = !metric) } }
    }
}

/**
 * The angular divisions. `N` must be 1 or more. With `N` not set,
 * the divisions mode is not available.
 */
object Divisions {

    /** Gives the value of the divisions sheet: `round(360 / q)` = `N`, or null when `N` is not set. */
    fun sheetValue(frame: ReadoutFrame): Int? = frame.state(AxisId.Angular).divisionsPerRev?.takeIf { divisions -> divisions >= 1 }

    /** Sets `N` = [divisionsPerRev] and turns on the divisions mode. A value less than 1 changes nothing. */
    fun set(frame: ReadoutFrame, divisionsPerRev: Int): OperationResult {
        if (divisionsPerRev < 1) return OperationResult(frame.workspace, frame.hold, null)
        return AxisOperations.result(frame) { base ->
            base.updateAxis(AxisId.Angular) { state -> state.copy(divisionsPerRev = divisionsPerRev, divisionUnits = true) }
        }
    }
}

/**
 * The tachometer actions.
 *
 * | Mode (`tach_mode`) | Tap on the RPM row |
 * |---|---|
 * | 0 RPM sensor | Message "Can't set RPM" |
 * | 1 Preset | Preset sheet: the list always starts with 0 |
 * | 2 Direct entry | Keypad sheet |
 */
object TachometerActions {

    /** A tap on the RPM row. */
    fun tap(frame: ReadoutFrame): OperationResult {
        val request = when (frame.profile.tachometer.mode) {
            TachometerMode.Sensor -> OperationRequest.ShowMessage(ReadoutMessage(ReadoutText.CantSetRpm))
            TachometerMode.Preset -> OperationRequest.OpenPresetSheet(presetList(frame.profile.tachometer.presets))
            TachometerMode.Direct -> OperationRequest.OpenKeypad(AxisId.Tachometer)
        }
        return OperationResult(frame.workspace, frame.hold, null, request)
    }

    /**
     * Gives the preset list of the sheet: the list always starts with one 0. A 0 at a different
     * place goes away. Example: `105 0 160` gives `0 105 160`.
     */
    fun presetList(presets: List<Double>): List<Double> = listOf(0.0) + presets.filter { preset -> preset != 0.0 }

    /** Gives the presets of the text [text]: numbers separated by spaces. A word that is not a number is ignored. */
    fun parsePresets(text: String): List<Double> =
        text.split(' ').mapNotNull { word -> word.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull() }

    /** Selects the preset [rpm]: `i(value)` in incremental mode, else `h(value)`. */
    fun selectPreset(frame: ReadoutFrame, rpm: Double): OperationResult = AxisOperations.keypadValue(frame, AxisId.Tachometer, rpm)

    /**
     * A change of the pulses per revolution (the resolution of the encoder profile of `t`) from
     * [old] to [new]: a value that is not 0 and not equal to the old value sets the tachometer
     * offsets to 0.
     */
    fun onPulsesChanged(workspace: Workspace, old: Double, new: Double): Workspace {
        if (new == 0.0 || new == old) return workspace
        return workspace.updateAxis(AxisId.Tachometer) { state -> state.copy(absoluteOffset = 0.0, incrementalOffset = 0.0) }
    }
}
