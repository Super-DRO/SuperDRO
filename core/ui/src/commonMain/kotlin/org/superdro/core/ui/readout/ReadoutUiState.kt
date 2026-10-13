package org.superdro.core.ui.readout

import androidx.compose.runtime.Immutable
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.LinkFailure
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.GraphicalViewPlacement
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SidePanelMode
import org.superdro.core.readout.DisplayUnit
import org.superdro.core.readout.operations.GlobalModeState
import org.superdro.core.readout.operations.GlobalUnitsState
import org.superdro.core.ui.text.UiText

/**
 * The UI state of the readout screen. The values of the axes
 * are not here: each row collects its own [AxisValueUiState] flow. Thus a new input value
 * recomposes only the rows whose value changed.
 *
 * @property loaded False until the profile and the workspace load.
 * @property readoutFont The font of the digits of the profile (`display_font`).
 * @property rows The readout rows in the row order of the profile.
 * @property hint The one-time hint that shows now, or null.
 * @property undo The undo of the last offset change, for 10 s, or null.
 * @property message The message that shows now in the snackbar, or null.
 * @property keepScreenOn True while the session is `Connected`.
 * @property addPointRequest The action of the "+" button that waits for the screen, or null.
 * @property functionSheetOpen True while the function strip shows in its bottom sheet.
 */
@Immutable
data class ReadoutUiState(
    val deviceId: DeviceId,
    val loaded: Boolean = false,
    val machineType: MachineType = MachineType.VerticalMill,
    val readoutFormat: ReadoutFormat = ReadoutFormat.Format3,
    val readoutFont: ReadoutFont = ReadoutFont.Dseg7,
    val status: StatusStripUiState = StatusStripUiState(),
    val rows: List<ReadoutRowUiState> = emptyList(),
    val showSliders: Boolean = true,
    val globalButtons: GlobalButtonsUiState = GlobalButtonsUiState(),
    val functions: List<FunctionButtonUiState> = emptyList(),
    val sidePanel: SidePanelUiState = SidePanelUiState(),
    val graphicalViewPlacement: GraphicalViewPlacement = GraphicalViewPlacement.BelowReadout,
    val setupReminder: SetupReminderUiState? = null,
    val hint: UiText? = null,
    val undo: UndoUiState? = null,
    val message: MessageUiState? = null,
    val keepScreenOn: Boolean = false,
    val addPointRequest: AddPointRequest? = null,
    val functionSheetOpen: Boolean = false,
)

/** The kind of a readout row. */
enum class RowKind { Linear, Angular, Tachometer }

/**
 * One readout row. The value is in the [AxisValueUiState] flow of the axis.
 *
 * @property label The label of the axis, for example "X", "α", or "RPM".
 * @property auxiliary True for the Y row of a lathe: the "Auxiliary readout".
 * @property buttons The action buttons that show with the full readout (`show_expanded`).
 * @property collapsedButtons The action buttons that show while the side panel is open next to
 *   the readout (`show_collapsed`).
 * @property slider True when the position slider shows below the row.
 */
@Immutable
data class ReadoutRowUiState(
    val axis: AxisId,
    val label: String,
    val kind: RowKind,
    val auxiliary: Boolean = false,
    val buttons: List<AxisButtonMode> = emptyList(),
    val collapsedButtons: List<AxisButtonMode> = emptyList(),
    val slider: Boolean = false,
)

/**
 * The display state of a value. Each state has an icon and a text, not only a color.
 */
enum class ValueState {
    /** A live value. */
    Normal,

    /** A live value that an offset or a projection changes: the warning color. */
    Modified,

    /** The session is not `Connected`, or the axis sent no data yet: the value pulses. */
    Pending,

    /** The axis has an input with no encoder profile or no valid resolution: "Error". */
    Error,

    /** An input of the axis is disabled: "No input". */
    NoInput,

    /** The hold is on: the held value. */
    Held,

    /** The session is not online: the last value. */
    Offline,
}

/**
 * The value of one axis.
 *
 * @property digits The text of the value without the minus sign, for example "125.430".
 * @property negative True when the value is less than 0: the sign column shows "-".
 * @property text The full text with the sign, for the semantic label.
 * @property errorInput The input of the axis for the "Error" state: a tap opens its encoder settings.
 * @property positionUm The shown value of a linear axis (ABS or INC), for the slider.
 * @property toolSizeUm The tool size of the slider: tool diameter / 2 on mill X and Y, else 0.
 * @property toolOffsetUm The tool offset of the axis, for the tool bar of the slider.
 * @property incremental True when the axis reads incremental.
 * @property metric True when a linear axis shows mm.
 * @property divisions True when the angular axis shows divisions.
 * @property surfaceSpeed True when the tachometer shows the surface speed.
 */
@Immutable
data class AxisValueUiState(
    val axis: AxisId,
    val digits: String = "",
    val negative: Boolean = false,
    val unit: DisplayUnit = DisplayUnit.Millimeter,
    val state: ValueState = ValueState.Pending,
    val errorInput: Char? = null,
    val positionUm: Double = 0.0,
    val toolSizeUm: Double = 0.0,
    val toolOffsetUm: Double = 0.0,
    val incremental: Boolean = false,
    val metric: Boolean = true,
    val divisions: Boolean = false,
    val surfaceSpeed: Boolean = false,
) {
    /** The full text with the sign. */
    val text: String get() = if (negative) "-$digits" else digits

    /** True when the hold of the axis is on. */
    val held: Boolean get() = state == ValueState.Held
}

/** The state of the link in the status strip and the tab. */
sealed interface LinkStatus {
    data object Connected : LinkStatus
    data class Connecting(val attempt: Int) : LinkStatus
    data object AwaitingData : LinkStatus
    data object NoData : LinkStatus
    data class Reconnecting(val attempt: Int) : LinkStatus
    data class WaitingForDevice(val cause: LinkFailure) : LinkStatus
    data class Failed(val cause: LinkFailure) : LinkStatus
    data object Disconnected : LinkStatus
}

/** The label of a tab whose session is not online. */
enum class OfflineLabel { Offline, Reconnecting }

/**
 * The status strip: the units, ABS or INC, the active tool, the active sub-datum, the RPM,
 * the adapter state, and the "Measurement" chip.
 *
 * @property rpmText The RPM text of the tachometer, or null when the tachometer is off.
 * @property offline The label while the session is not online, or null.
 */
@Immutable
data class StatusStripUiState(
    val units: GlobalUnitsState = GlobalUnitsState.Metric,
    val mode: GlobalModeState = GlobalModeState.Absolute,
    val toolName: String? = null,
    val subDatumName: String? = null,
    val rpmText: String? = null,
    val link: LinkStatus = LinkStatus.Disconnected,
    val measurementMode: Boolean = false,
    val offline: OfflineLabel? = null,
)

/** The global buttons Zero Set, Mode, and Units. */
@Immutable
data class GlobalButtonsUiState(
    val mode: GlobalModeState = GlobalModeState.Absolute,
    val units: GlobalUnitsState = GlobalUnitsState.Metric,
)

/** The function buttons of the strip. */
enum class FunctionButton {
    RadiusDiameter,
    LatheToolOffset,
    ZReference,
    RadiusCompensation,
    HeightCompensation,
    EdgeFinder,
    HoleCircle,
    ArbitraryHoleCircle,
    ArcCut,
    HoleGrid,
    EquidistantArray,
    DimensionMeasure,
    AngleMeasure,
    GraphicalView,
    ThreadSetup,
    CompoundCompensation,
    HeadTiltCompensation,
    RotatePart,
    AlignAxes,
    ReferenceInstrument,
}

/**
 * One function button.
 *
 * @property active True for a compensation button with an active offset. A long press then
 *   clears it with undo.
 * @property toggled For the radius or diameter button: true when X shows the diameter.
 */
@Immutable
data class FunctionButtonUiState(
    val button: FunctionButton,
    val active: Boolean = false,
    val toggled: Boolean = false,
)

/**
 * The side panel.
 *
 * @property toolsEnabled True when the tool library is not empty.
 * @property addVisible True when the "+" button shows: in `Off` and in `SubDatums`.
 */
@Immutable
data class SidePanelUiState(
    val mode: SidePanelMode = SidePanelMode.Off,
    val toolsEnabled: Boolean = false,
    val addVisible: Boolean = true,
)

/** The setup reminder banner. [axis] is the first axis that needs a calibration. */
@Immutable
data class SetupReminderUiState(val axis: AxisId)

/** The undo of one offset change. [id] changes for each new change. */
@Immutable
data class UndoUiState(val id: Long, val label: UiText)

/** A message of the snackbar. [id] changes for each new message. */
@Immutable
data class MessageUiState(val id: Long, val text: UiText)

/** An action of the "+" button. */
@Immutable
data class AddPointRequest(val id: Long, val kind: AddPointKind)

/** The gesture of the "+" button. */
enum class AddPointKind {
    /** A single tap: add a sub-datum at the current position. */
    Single,

    /** A second tap in 300 ms: the custom point sheet. */
    Double,

    /** A long press: the shape menu. */
    Long,
}
