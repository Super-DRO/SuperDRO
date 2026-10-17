package org.superdro.core.ui.readout

import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.SidePanelMode

/**
 * The events of the readout screen. The composables are stateless and call these functions. The
 * readout entry connects them to the [ReadoutPresenter] and to the back stack of the tab.
 */
interface ReadoutEvents {
    /** A tap on the value of [axis]: the keypad, the encoder settings ("Error"), or the tachometer action. */
    fun onValueTap(axis: AxisId) = Unit

    /** A tap on the label of [axis]: the axis details. */
    fun onLabelTap(axis: AxisId) = Unit

    /** A tap on an action button. */
    fun onActionTap(axis: AxisId, mode: AxisButtonMode) = Unit

    /** A long press on an action button. */
    fun onActionLongPress(axis: AxisId, mode: AxisButtonMode) = Unit

    /** The RPM or SFM button of the tachometer row. */
    fun onTachometerToggle() = Unit

    fun onZeroSetTap() = Unit
    fun onZeroSetLongPress() = Unit
    fun onModeTap() = Unit
    fun onModeLongPress() = Unit
    fun onUnitsTap() = Unit

    /** A tap on a function button. */
    fun onFunctionTap(button: FunctionButton) = Unit

    /** A long press on a function button. */
    fun onFunctionLongPress(button: FunctionButton) = Unit

    /** A tap on a side panel tab. */
    fun onSidePanelTab(mode: SidePanelMode) = Unit

    /** The side panel sheet closed (portrait). */
    fun onSidePanelClose() = Unit

    /** A tap on "+". */
    fun onAddTap() = Unit

    /** A long press on "+". */
    fun onAddLongPress() = Unit

    /** Opens or closes the bottom sheet of the function strip (compact layout). */
    fun onFunctionSheet(open: Boolean) = Unit

    /** "Settings" of the setup reminder. */
    fun onSetupSettings() = Unit

    /** "Calibrate" of the setup reminder. */
    fun onSetupCalibrate(axis: AxisId) = Unit

    /** The user dragged the vertical split handle in the expanded layout. */
    fun onReadoutPaneFractionChange(fraction: Float) = Unit

    /** The user dragged the horizontal split handle in the portrait layout. */
    fun onGraphicalPaneWeightChange(weight: Float) = Unit
}

/** The events that do nothing, for the previews and the screenshot tests. */
object NoReadoutEvents : ReadoutEvents
