package org.superdro.validation.readout

import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.expression.ExpressionEvaluator
import org.superdro.core.readout.expression.ExpressionResult
import org.superdro.core.readout.input.InputConfiguration
import org.superdro.core.readout.input.InputStage
import org.superdro.core.readout.input.ProbeEvent

/*
 * The fixtures of the readout validation. The expected values of the tests are independent of the
 * implementation.
 */

/** A new vertical mill with the defaults. */
internal fun mill(): MachineProfile = MachineProfile.create(MachineType.VerticalMill)

/** A new lathe with the defaults. */
internal fun lathe(): MachineProfile = MachineProfile.create(MachineType.Lathe)

/** The active workspace of the profile. */
internal val MachineProfile.workspace: Workspace get() = activeWorkspace ?: error("The profile has no workspace.")

/** Gives the workspace with the state of [axis] changed by [change]. */
internal fun Workspace.axis(axis: AxisId, change: (AxisState) -> AxisState): Workspace =
    copy(axisStates = axisStates + (axis to change(axisStates[axis] ?: AxisState())))

/** Gives the profile with its active workspace changed by [change]. */
internal fun MachineProfile.withWorkspace(change: (Workspace) -> Workspace): MachineProfile {
    val changed = change(workspace)
    return copy(workspaces = workspaces.map { item -> if (item.id == changed.id) changed else item })
}

/** Gives a frame of [profile] with [workspace] and the sums `R` of [inputs]. */
internal fun frame(
    profile: MachineProfile,
    workspace: Workspace,
    vararg inputs: Pair<AxisId, Double>,
): ReadoutFrame = ReadoutFrame(profile, workspace, inputs.toMap())

/** Gives a frame of [profile] with its active workspace and the sums `R` of [inputs]. */
internal fun frameOf(profile: MachineProfile, vararg inputs: Pair<AxisId, Double>): ReadoutFrame =
    ReadoutFrame(profile, profile.workspace, inputs.toMap())

/** A linear scale on [input] with [resolution] counts per inch. */
internal fun scale(input: Char, resolution: Double = 25400.0): EncoderProfile =
    EncoderProfile(input = input, type = EncoderType.Scale, kind = EncoderKind.Linear, resolution = resolution)

/** An angular encoder on [input] with [resolution] counts per revolution. */
internal fun angularEncoder(input: Char, resolution: Double): EncoderProfile =
    EncoderProfile(input = input, type = EncoderType.RotaryEncoder, kind = EncoderKind.Angular, resolution = resolution)

/** An adapter with the default encoder profiles and the profiles [encoders] in place of the defaults. */
internal fun adapter(vararg encoders: EncoderProfile): AdapterProfile =
    AdapterProfile(encoders = AdapterProfile().encoders + encoders.associateBy { encoder -> encoder.input })

/** Gives an input stage for [profile] with [adapter]. */
internal fun stage(profile: MachineProfile, adapter: AdapterProfile?): InputStage =
    InputStage(InputConfiguration.from(profile, adapter))

/** Gives the sum `R` of [axis] in the snapshot of the stage. */
internal fun InputStage.r(axis: AxisId): Double? = snapshot.axisValue(axis)

/** Sends one sample and collects the probe events. */
internal fun InputStage.count(channel: Char, count: Long, timeMs: Long, events: MutableList<ProbeEvent>) {
    events += onAxisCount(channel, count, timeMs)
}

/** The evaluator for the keypad of the axis operations. */
internal val keypadEvaluate: (String) -> Result<Double> = { text ->
    when (val result = ExpressionEvaluator.evaluate(text)) {
        is ExpressionResult.Value -> Result.success(result.value)
        is ExpressionResult.Error -> Result.failure(IllegalArgumentException(result.message))
    }
}
