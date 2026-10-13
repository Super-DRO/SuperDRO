package org.superdro.core.ui.readout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AngularAxisConfig
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.TachometerConfig
import org.superdro.core.model.Workspace
import org.superdro.core.readout.DisplayUnit
import org.superdro.core.readout.operations.GlobalModeState
import org.superdro.core.readout.operations.GlobalUnitsState
import org.superdro.core.ui.text.UiText

/**
 * The UI states of the screenshot tests and the UI tests (with no fake adapter).
 */
object ReadoutSamples {

    /** A UI state and the value of each axis. */
    class Sample(val state: ReadoutUiState, val values: Map<AxisId, AxisValueUiState>) {
        private val flows = AxisId.entries.associateWith { axis -> MutableStateFlow(values[axis] ?: AxisValueUiState(axis)) }

        /** The value flow of [axis]. */
        fun flow(axis: AxisId): StateFlow<AxisValueUiState> = flows.getValue(axis)

        /** Changes the value of [axis]. */
        fun set(value: AxisValueUiState) {
            flows.getValue(value.axis).value = value
        }
    }

    /** A vertical mill with X, Y, Z, the angular axis, and the tachometer. */
    fun mill(
        machineType: MachineType = MachineType.VerticalMill,
        hint: UiText? = null,
        setupReminder: Boolean = false,
    ): Sample {
        val profile = MachineProfile.create(machineType, "Mill").copy(
            metricDefault = true,
            angular = AngularAxisConfig(enabled = true),
            tachometer = TachometerConfig(enabled = true),
        )
        val workspace = Workspace.forMachine(machineType).let { base ->
            base.copy(axisStates = base.axisStates + (AxisId.X to AxisState(metric = true, toolOffset = 5_000.0, toolOffsetActive = true)))
        }
        val values = mapOf(
            AxisId.X to linear(AxisId.X, "125.430", negative = false, positionUm = 25_430.0, state = ValueState.Modified, toolSizeUm = 5_000.0),
            AxisId.Y to linear(AxisId.Y, "12.005", negative = true, positionUm = -12_005.0, incremental = true),
            AxisId.Z to linear(AxisId.Z, "3.100", negative = false, positionUm = 3_100.0),
            AxisId.Angular to AxisValueUiState(AxisId.Angular, digits = "45.00", unit = DisplayUnit.Degrees, state = ValueState.Normal),
            AxisId.Tachometer to AxisValueUiState(AxisId.Tachometer, digits = "1250", unit = DisplayUnit.Rpm, state = ValueState.Normal, metric = true),
        )
        return Sample(state(profile, workspace, hint, setupReminder, toolName = "6 mm end mill", subDatum = "S2"), values)
    }

    /** A lathe with X (diameter), Z, the auxiliary readout, and the tachometer. */
    fun lathe(): Sample {
        val profile = MachineProfile.create(MachineType.Lathe).copy(
            y = MachineProfile.create(MachineType.Lathe).y.copy(enabled = true),
            tachometer = TachometerConfig(enabled = true),
        )
        val workspace = Workspace.forMachine(MachineType.Lathe)
        val values = mapOf(
            AxisId.X to linear(AxisId.X, "1.0000", negative = false, positionUm = 12_700.0, metric = false, unit = DisplayUnit.Inch),
            AxisId.Z to linear(AxisId.Z, "2.4301", negative = true, positionUm = -61_725.0, metric = false, unit = DisplayUnit.Inch, state = ValueState.Pending),
            AxisId.Y to linear(AxisId.Y, "Error", negative = false, positionUm = 0.0, state = ValueState.Error, metric = false, unit = DisplayUnit.Inch),
            AxisId.Tachometer to AxisValueUiState(AxisId.Tachometer, digits = "157.08", unit = DisplayUnit.SurfaceFeetPerMinute, state = ValueState.Normal, surfaceSpeed = true, metric = false),
        )
        return Sample(state(profile, workspace, null, setupReminder = true, toolName = null, subDatum = null), values)
    }

    private fun linear(
        axis: AxisId,
        digits: String,
        negative: Boolean,
        positionUm: Double,
        incremental: Boolean = false,
        state: ValueState = ValueState.Normal,
        toolSizeUm: Double = 0.0,
        metric: Boolean = true,
        unit: DisplayUnit = DisplayUnit.Millimeter,
    ) = AxisValueUiState(
        axis = axis,
        digits = digits,
        negative = negative,
        unit = unit,
        state = state,
        errorInput = if (state == ValueState.Error) 'y' else null,
        positionUm = positionUm,
        toolSizeUm = toolSizeUm,
        incremental = incremental,
        metric = metric,
    )

    private fun state(
        profile: MachineProfile,
        workspace: Workspace,
        hint: UiText?,
        setupReminder: Boolean,
        toolName: String?,
        subDatum: String?,
    ): ReadoutUiState {
        val expanded = ReadoutRows.rows(profile, collapsed = false)
        val collapsed = ReadoutRows.rows(profile, collapsed = true)
        val mode = GlobalModeState.Mixed
        val units = if (profile.machineType.isLathe) GlobalUnitsState.Inch else GlobalUnitsState.Metric
        return ReadoutUiState(
            deviceId = DeviceId(profile.id),
            loaded = true,
            machineType = profile.machineType,
            status = StatusStripUiState(
                units = units,
                mode = mode,
                toolName = toolName,
                subDatumName = subDatum,
                rpmText = "1250",
                link = LinkStatus.Connected,
            ),
            rows = expanded.mapIndexed { index, row -> row.copy(collapsedButtons = collapsed[index].buttons) },
            globalButtons = GlobalButtonsUiState(mode, units),
            functions = FunctionStripRules.buttons(profile, workspace),
            sidePanel = SidePanelUiState(toolsEnabled = true),
            setupReminder = if (setupReminder) SetupReminderUiState(AxisId.X) else null,
            hint = hint,
            keepScreenOn = true,
        )
    }
}
