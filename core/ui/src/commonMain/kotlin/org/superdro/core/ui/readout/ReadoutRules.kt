package org.superdro.core.ui.readout

import kotlin.math.max
import kotlin.math.min
import org.superdro.core.functions.compensation.ToolCompensation
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisButtonConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.Workspace
import org.superdro.core.readout.operations.ActionButtons
import org.superdro.core.ui.navigation.AlignAxesRoute
import org.superdro.core.ui.navigation.AngleMeasureRoute
import org.superdro.core.ui.navigation.ArbitraryHoleCircleRoute
import org.superdro.core.ui.navigation.ArcCutRoute
import org.superdro.core.ui.navigation.CompoundRoute
import org.superdro.core.ui.navigation.DimensionMeasureRoute
import org.superdro.core.ui.navigation.EdgeFinderRoute
import org.superdro.core.ui.navigation.EquidistantArrayRoute
import org.superdro.core.ui.navigation.GraphicalViewRoute
import org.superdro.core.ui.navigation.HeadTiltRoute
import org.superdro.core.ui.navigation.HeightCompensationRoute
import org.superdro.core.ui.navigation.HoleCircleRoute
import org.superdro.core.ui.navigation.HoleGridRoute
import org.superdro.core.ui.navigation.InstrumentRoute
import org.superdro.core.ui.navigation.LatheToolOffsetRoute
import org.superdro.core.ui.navigation.RadiusCompensationRoute
import org.superdro.core.ui.navigation.RotatePartRoute
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.navigation.ThreadSetupRoute
import org.superdro.core.ui.navigation.ZReferenceRoute

/**
 * The rows of the readout: mill X, Y, Z; lathe X, Z, then the auxiliary
 * readout (Y). Then the angular row and the tachometer row. Only the enabled outputs show.
 */
object ReadoutRows {

    /** The order of the outputs for [profile], with the disabled outputs. */
    fun order(profile: MachineProfile): List<AxisId> =
        if (profile.machineType.isLathe) {
            listOf(AxisId.X, AxisId.Z, AxisId.Y, AxisId.Angular, AxisId.Tachometer)
        } else {
            listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular, AxisId.Tachometer)
        }

    /** True when the output [axis] of [profile] is enabled. The caliper is not a row. */
    fun enabled(profile: MachineProfile, axis: AxisId): Boolean = when (axis) {
        AxisId.X -> profile.x.enabled
        AxisId.Y -> profile.y.enabled
        AxisId.Z -> profile.z.enabled
        AxisId.Angular -> profile.angular.enabled
        AxisId.Tachometer -> profile.tachometer.enabled
        AxisId.Caliper -> false
    }

    /**
     * Gives the rows of [profile] in the row order. [collapsed] selects the action buttons with
     * `show_collapsed`. The sliders show below the linear rows when [showSliders] is on.
     */
    fun rows(profile: MachineProfile, collapsed: Boolean, showSliders: Boolean = profile.display.showSliders): List<ReadoutRowUiState> =
        order(profile).filter { axis -> enabled(profile, axis) }.map { axis ->
            when (axis) {
                AxisId.X, AxisId.Y, AxisId.Z -> {
                    val config = when (axis) {
                        AxisId.X -> profile.x
                        AxisId.Y -> profile.y
                        else -> profile.z
                    }
                    ReadoutRowUiState(
                        axis = axis,
                        label = config.label,
                        kind = RowKind.Linear,
                        auxiliary = axis == AxisId.Y && profile.machineType.isLathe,
                        buttons = buttonModes(axis, config.buttons, collapsed),
                        slider = showSliders,
                    )
                }
                AxisId.Angular -> ReadoutRowUiState(
                    axis = axis,
                    label = profile.angular.label,
                    kind = RowKind.Angular,
                    buttons = buttonModes(axis, profile.angular.buttons, collapsed),
                )
                else -> ReadoutRowUiState(axis = axis, label = profile.tachometer.label, kind = RowKind.Tachometer)
            }
        }

    /** The modes of the action buttons of [axis] that show (mode 4 only on the angular axis). */
    fun buttonModes(axis: AxisId, buttons: List<AxisButtonConfig>, collapsed: Boolean): List<AxisButtonMode> =
        ActionButtons.visibleButtons(axis, buttons, collapsed).take(MAX_BUTTONS).map { button -> button.mode }

    /** The number of action buttons of a row. */
    const val MAX_BUTTONS: Int = 3

    /** The number of enabled outputs of X, Y, Z, Angular, and Tachometer (the `count` of the row height rule). */
    fun count(profile: MachineProfile): Int = order(profile).count { axis -> enabled(profile, axis) }
}

/**
 * The geometry of the readout rows and the text size of the digits.
 */
object ReadoutGeometry {

    /** The minimum number of row slots: three or fewer rows each get 1/3 of the pane. */
    const val MIN_SLOTS: Int = 3

    /**
     * The height of one row: 1 / max(3, count) of the height of the readout pane. The rows
     * use the full readout pane.
     */
    fun rowHeight(paneHeight: Float, count: Int): Float = paneHeight / max(MIN_SLOTS, count)

    /** The number of row slots of [count] rows. */
    fun slots(count: Int): Int = max(MIN_SLOTS, count)

    /**
     * The text size of the readout digits:
     *
     * ```text
     * h    = (row height − padding) − margins
     * w    = width of the mm text of −9000 at the size h
     * size = min((row width − padding) × h / w, h)
     * ```
     *
     * @param height The height `h` that the digits can use.
     * @param width The width of the value box, without the padding.
     * @param templateWidthAtHeight The width `w` of the template text at the size [height].
     */
    fun textSize(height: Float, width: Float, templateWidthAtHeight: Float): Float {
        if (height <= 0f || width <= 0f) return 0f
        if (templateWidthAtHeight <= 0f) return height
        return min(width * height / templateWidthAtHeight, height)
    }
}

/**
 * The function strip. The strip starts with the three global buttons. Then come the function
 * buttons of [buttons] in the order of the list. "Mill" means a vertical
 * mill or a horizontal mill.
 */
object FunctionStripRules {

    /**
     * Gives the function buttons that show for [profile] and [workspace], in the order of the
     * function table, with the "active" state of the compensation buttons and of the compound and
     * head tilt buttons.
     */
    fun buttons(profile: MachineProfile, workspace: Workspace?): List<FunctionButtonUiState> {
        val show = profile.functionButtons
        val measurement = profile.machineMode == MachineMode.Measurement && !profile.machineType.isLathe
        val caliper = profile.caliper.enabled
        val list = ArrayList<FunctionButtonUiState>()
        fun add(button: FunctionButton, visible: Boolean) {
            if (!visible) return
            val active = workspace != null && when (button) {
                FunctionButton.LatheToolOffset -> ToolCompensation.latheOffsetActive(workspace)
                FunctionButton.HeightCompensation -> ToolCompensation.heightActive(workspace)
                FunctionButton.RadiusCompensation -> ToolCompensation.radiusActive(workspace)
                FunctionButton.CompoundCompensation -> workspace.compoundProjection.active
                FunctionButton.HeadTiltCompensation -> workspace.headTilt.active
                else -> false
            }
            val toggled = button == FunctionButton.RadiusDiameter && workspace?.axisStates?.get(AxisId.X)?.diameterMode == true
            list += FunctionButtonUiState(button, active, toggled)
        }
        if (profile.machineType.isLathe) {
            add(FunctionButton.RadiusDiameter, true)
            add(FunctionButton.LatheToolOffset, true)
            add(FunctionButton.DimensionMeasure, show.dimensionMeasure && caliper)
            add(FunctionButton.ArcCut, show.arcCut)
            add(FunctionButton.AngleMeasure, show.angleMeasure)
            add(FunctionButton.EquidistantArray, show.equidistantArray)
            add(FunctionButton.HoleGrid, show.holeGrid)
            add(FunctionButton.HoleCircle, show.holeCircle)
            add(FunctionButton.GraphicalView, true)
            add(FunctionButton.ThreadSetup, true)
            add(FunctionButton.CompoundCompensation, show.compoundAngle && hasCompoundInput(profile))
        } else {
            add(FunctionButton.ZReference, show.zReference && !measurement)
            add(FunctionButton.RadiusCompensation, show.radiusCompensation && !measurement)
            add(FunctionButton.HeightCompensation, show.heightCompensation && !measurement)
            add(FunctionButton.EdgeFinder, show.edgeFinder)
            add(FunctionButton.HoleCircle, show.holeCircle)
            add(FunctionButton.ArbitraryHoleCircle, show.arbitraryHoleCircle)
            add(FunctionButton.ArcCut, show.arcCut)
            add(FunctionButton.HoleGrid, show.holeGrid)
            add(FunctionButton.EquidistantArray, show.equidistantArray)
            add(FunctionButton.DimensionMeasure, show.dimensionMeasure && caliper)
            add(FunctionButton.AngleMeasure, show.angleMeasure)
            add(FunctionButton.GraphicalView, true)
            add(FunctionButton.ThreadSetup, true)
            add(FunctionButton.HeadTiltCompensation, show.headTilt && hasQuillInput(profile))
            add(FunctionButton.RotatePart, true)
            add(FunctionButton.AlignAxes, measurement)
            add(FunctionButton.ReferenceInstrument, measurement)
        }
        return list
    }

    /** True when a lathe has a compound slide input: the auxiliary readout (Y) is on and measures the compound slide. */
    fun hasCompoundInput(profile: MachineProfile): Boolean =
        profile.machineType.isLathe && profile.y.enabled && profile.auxiliary.type == AuxiliaryType.CompoundSlide

    /** True when a mill has a quill input: the secondary input of Z (the quill scale of the machine setup). */
    fun hasQuillInput(profile: MachineProfile): Boolean =
        !profile.machineType.isLathe && profile.z.enabled && profile.z.secondary != null

    /** Gives the route that a tap on [button] opens, or null for the radius or diameter toggle. */
    fun route(button: FunctionButton): TabRoute? = when (button) {
        FunctionButton.RadiusDiameter -> null
        FunctionButton.LatheToolOffset -> LatheToolOffsetRoute
        FunctionButton.ZReference -> ZReferenceRoute
        FunctionButton.RadiusCompensation -> RadiusCompensationRoute
        FunctionButton.HeightCompensation -> HeightCompensationRoute
        FunctionButton.EdgeFinder -> EdgeFinderRoute
        FunctionButton.HoleCircle -> HoleCircleRoute
        FunctionButton.ArbitraryHoleCircle -> ArbitraryHoleCircleRoute(advanced = false)
        FunctionButton.ArcCut -> ArcCutRoute
        FunctionButton.HoleGrid -> HoleGridRoute
        FunctionButton.EquidistantArray -> EquidistantArrayRoute
        FunctionButton.DimensionMeasure -> DimensionMeasureRoute
        FunctionButton.AngleMeasure -> AngleMeasureRoute
        FunctionButton.GraphicalView -> GraphicalViewRoute
        FunctionButton.ThreadSetup -> ThreadSetupRoute
        FunctionButton.CompoundCompensation -> CompoundRoute
        FunctionButton.HeadTiltCompensation -> HeadTiltRoute
        FunctionButton.RotatePart -> RotatePartRoute
        FunctionButton.AlignAxes -> AlignAxesRoute
        FunctionButton.ReferenceInstrument -> InstrumentRoute
    }

    /**
     * Gives the route of a long press on [button], or null. The arbitrary hole circle opens its
     * advanced form. A long press on an active compensation button clears
     * the compensation: the presenter does it, not a route.
     */
    fun longPressRoute(button: FunctionButton): TabRoute? =
        if (button == FunctionButton.ArbitraryHoleCircle) ArbitraryHoleCircleRoute(advanced = true) else null

    /**
     * True for the compensation buttons with an "active" state: the three tool
     * compensations, the compound slide compensation, and the head tilt compensation.
     */
    fun isCompensation(button: FunctionButton): Boolean =
        button == FunctionButton.LatheToolOffset || button == FunctionButton.RadiusCompensation ||
            button == FunctionButton.HeightCompensation || button == FunctionButton.CompoundCompensation ||
            button == FunctionButton.HeadTiltCompensation
}

/**
 * The setup reminder: an enabled axis (X, Y, Z, Angular; the primary or the secondary input) has
 * an input with no encoder profile, or with an encoder profile of the calibration status
 * `NotCalibrated`.
 */
object SetupReminder {

    /** The axes of the rule, in the order of the check. */
    val AXES: List<AxisId> = listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular)

    /** Gives the inputs of the enabled [axis] of [profile]. */
    fun inputs(profile: MachineProfile, axis: AxisId): List<InputConfig> = when (axis) {
        AxisId.X -> if (profile.x.enabled) listOfNotNull(profile.x.primary, profile.x.secondary) else emptyList()
        AxisId.Y -> if (profile.y.enabled) listOfNotNull(profile.y.primary, profile.y.secondary) else emptyList()
        AxisId.Z -> if (profile.z.enabled) listOfNotNull(profile.z.primary, profile.z.secondary) else emptyList()
        AxisId.Angular -> if (profile.angular.enabled) listOf(profile.angular.primary) else emptyList()
        else -> emptyList()
    }

    /** Gives the first axis that needs a calibration, or null. [adapter] is the adapter profile of the tab. */
    fun axisNeedingSetup(profile: MachineProfile, adapter: AdapterProfile?): AxisId? = AXES.firstOrNull { axis ->
        inputs(profile, axis).any { input -> EncoderProfile.needsSetupReminder(adapter?.encoders?.get(input.channel.lowercaseChar())) }
    }

    /** Gives the primary input of [axis], for the "Error" tap that opens the encoder settings. */
    fun primaryInput(profile: MachineProfile, axis: AxisId): Char? = when (axis) {
        AxisId.X -> profile.x.primary.channel
        AxisId.Y -> profile.y.primary.channel
        AxisId.Z -> profile.z.primary.channel
        AxisId.Angular -> profile.angular.primary.channel
        AxisId.Caliper -> profile.caliper.input.channel
        AxisId.Tachometer -> 't'
    }
}
