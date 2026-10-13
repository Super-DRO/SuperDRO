package org.superdro.core.ui.functions.patterns

import androidx.compose.runtime.Immutable
import org.superdro.core.functions.arc.AngleReference
import org.superdro.core.functions.arc.CutType
import org.superdro.core.functions.patterns.AxisLetter
import org.superdro.core.functions.patterns.AxisSwitches
import org.superdro.core.functions.patterns.HoleAngleList
import org.superdro.core.functions.patterns.PatternField
import org.superdro.core.functions.patterns.PositionReference
import org.superdro.core.functions.patterns.PositionSource
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.ui.functions.common.ErrorBlock
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.functions.common.PreviewPoint
import org.superdro.core.ui.text.UiText

/** The function screens with a point preview. */
enum class PatternKind {
    /** The hole circle, "Circular Point Array" on a lathe. */
    HoleCircle,

    /** The arbitrary hole circle: a list of angles. */
    ArbitraryHoleCircle,

    /** The hole grid, "Linear Point Array" on a lathe. */
    HoleGrid,

    /** The step and repeat of sub-datums or of the workspace. */
    StepAndRepeat,

    /** The equidistant array. */
    EquidistantArray,

    /** "Radius/Arc Cutting". */
    ArcCut;

    /** True when the screen has the prefix field. The equidistant array has no prefix. */
    val hasPrefix: Boolean get() = this != EquidistantArray

    /** True when the screen has the plane, the position source, and the position fields. */
    val hasPosition: Boolean get() = this != EquidistantArray

    /** True when the screen has the position reference control. */
    val hasReference: Boolean get() = this == HoleCircle || this == ArbitraryHoleCircle || this == ArcCut

    /** True when the user can select the plane (a mill pattern; not the step and repeat). */
    val selectablePlane: Boolean get() = this != StepAndRepeat && this != EquidistantArray
}

/**
 * The fields of the pattern screens. A length field ([isLength]) has its own inch or mm control.
 * The other fields are angles in degrees, counts, or (for [Count]) a plain integer.
 */
enum class PatternInput(val isLength: Boolean) {
    CustomX(true),
    CustomY(true),
    CustomZ(true),
    Radius(true),
    HoleCount(false),
    StartAngle(false),
    SweepAngle(false),
    AngleEntry(false),
    Repeat1(false),
    Step1(true),
    Repeat2(false),
    Step2(true),
    GridAngle(false),
    Count(false),
    StartX(true),
    StartY(true),
    StartZ(true),
    EndX(true),
    EndY(true),
    EndZ(true),
    CutterDiameter(true),
    Stepover(true);

    companion object {
        /** The custom position field of [axis]. */
        fun custom(axis: AxisLetter): PatternInput = when (axis) {
            AxisLetter.X -> CustomX
            AxisLetter.Y -> CustomY
            AxisLetter.Z -> CustomZ
        }

        /** The start field of the equidistant array for [axis]. */
        fun start(axis: AxisLetter): PatternInput = when (axis) {
            AxisLetter.X -> StartX
            AxisLetter.Y -> StartY
            AxisLetter.Z -> StartZ
        }

        /** The end field of the equidistant array for [axis]. */
        fun end(axis: AxisLetter): PatternInput = when (axis) {
            AxisLetter.X -> EndX
            AxisLetter.Y -> EndY
            AxisLetter.Z -> EndZ
        }
    }
}

/**
 * The inputs of one pattern screen. The presenter keeps them while the entry is on the back
 * stack, so the screen keeps its values while the tab changes.
 */
@Immutable
data class PatternInputs(
    val prefix: String = "",
    val plane: Plane = Plane.XY,
    val source: PositionSource = PositionSource.Current,
    val switches: AxisSwitches = AxisSwitches(x = true, y = true, z = true),
    val reference: PositionReference = PositionReference.Center,
    val subDatumId: String? = null,
    val lengths: Map<PatternInput, LengthFieldState> = emptyMap(),
    val numbers: Map<PatternInput, NumberFieldState> = emptyMap(),
    val angles: HoleAngleList = HoleAngleList(),
    val angleError: UiText? = null,
    val cutType: CutType = CutType.External,
    val angleReference: AngleReference = AngleReference.Center,
)

/**
 * One position row of the screen: the axis switch and the position field.
 *
 * @property label The label of the axis, for example "X".
 * @property machineEnabled False when the axis is not enabled in the machine profile: the axis
 *   gives no coordinate.
 * @property diameter True for a lathe X in the diameter mode: the field shows and takes a diameter.
 * @property editable True when the source is "Custom".
 */
@Immutable
data class PositionRowUiState(
    val axis: AxisLetter,
    val label: String,
    val on: Boolean,
    val machineEnabled: Boolean,
    val diameter: Boolean,
    val editable: Boolean,
    val field: LengthFieldState,
)

/** One sub-datum of the list that fills the custom fields. */
@Immutable
data class SubDatumChoice(val id: String, val name: String)

/** One row of the point list below the preview: the name and the X, Y, Z texts ("" for no coordinate). */
@Immutable
data class PointRowUiState(val name: String, val x: String, val y: String, val z: String)

/** One editable field of the screen with its label. */
@Immutable
data class InputUiState(val input: PatternInput, val label: UiText, val length: LengthFieldState?, val number: NumberFieldState?)

/**
 * The UI state of a pattern screen.
 *
 * @property planes The planes that the plane control shows. A lathe has only `ZX`.
 * @property sourceError The error text when the source is "Current" and the session is not
 *   `Connected`.
 * @property fields The fields of the function, in the order of the screen.
 * @property angles The angle list of the arbitrary hole circle, in degrees.
 * @property preview The points of the current inputs in the two plane axes.
 * @property pointRows The names and the coordinates of the points that "Create" makes.
 * @property errors The errors of the last "Create", or null.
 * @property blocked The message of the sub-datum blocker, or null.
 * @property closeAfterBlocked True when the screen closes when the user closes the blocker dialog.
 * @property created The number of the new sub-datums after a good "Create". The entry then closes.
 * @property sourceCount The number of the source sub-datums of the step and repeat.
 */
@Immutable
data class PatternUiState(
    val kind: PatternKind,
    val loaded: Boolean = false,
    val title: UiText = UiText.Plain(""),
    val machineType: MachineType = MachineType.VerticalMill,
    val prefix: String = "",
    val prefixDefault: String = "",
    val plane: Plane = Plane.XY,
    val planes: List<Plane> = emptyList(),
    val planeEnabled: Boolean = false,
    val source: PositionSource = PositionSource.Current,
    val positionTitle: UiText? = null,
    val positionRows: List<PositionRowUiState> = emptyList(),
    val sourceError: UiText? = null,
    val reference: PositionReference = PositionReference.Center,
    val referenceLabels: Pair<UiText, UiText>? = null,
    val subDatums: List<SubDatumChoice> = emptyList(),
    val subDatumId: String? = null,
    val fields: List<InputUiState> = emptyList(),
    val angles: List<Double> = emptyList(),
    val angleError: UiText? = null,
    val cutType: CutType = CutType.External,
    val angleReference: AngleReference = AngleReference.Center,
    val preview: List<PreviewPoint> = emptyList(),
    val previewOrigin: PreviewPoint? = null,
    val hAxis: String = "X",
    val vAxis: String = "Y",
    val pointRows: List<PointRowUiState> = emptyList(),
    val pointCount: Int = 0,
    val errors: ErrorBlock? = null,
    val blocked: UiText? = null,
    val closeAfterBlocked: Boolean = false,
    val created: Int? = null,
    val sourceCount: Int? = null,
) {
    /** The field of [input], or null when the screen does not have it. */
    fun field(input: PatternInput): InputUiState? = fields.firstOrNull { field -> field.input == input }
}

/** The fields of the error texts of `core:functions` and the screen fields. */
internal fun PatternField.inputs(): Set<PatternInput> = when (this) {
    PatternField.Prefix -> emptySet()
    PatternField.CircleRadius, PatternField.PartRadius -> setOf(PatternInput.Radius)
    PatternField.HoleCount -> setOf(PatternInput.HoleCount)
    PatternField.StartAngle -> setOf(PatternInput.StartAngle)
    PatternField.SweepAngle -> setOf(PatternInput.SweepAngle)
    PatternField.HoleAngle -> setOf(PatternInput.AngleEntry)
    PatternField.RepeatX, PatternField.RepeatY, PatternField.RepeatZ -> setOf(PatternInput.Repeat1, PatternInput.Repeat2)
    PatternField.StepX, PatternField.StepY, PatternField.StepZ -> setOf(PatternInput.Step1, PatternInput.Step2)
    PatternField.GridAngle -> setOf(PatternInput.GridAngle)
    PatternField.NumberOfHoles -> setOf(PatternInput.Count)
    PatternField.StartX -> setOf(PatternInput.StartX)
    PatternField.StartY -> setOf(PatternInput.StartY)
    PatternField.StartZ -> setOf(PatternInput.StartZ)
    PatternField.EndX -> setOf(PatternInput.EndX)
    PatternField.EndY -> setOf(PatternInput.EndY)
    PatternField.EndZ -> setOf(PatternInput.EndZ)
    PatternField.CutterDiameter -> setOf(PatternInput.CutterDiameter)
    PatternField.Stepover -> setOf(PatternInput.Stepover)
}
