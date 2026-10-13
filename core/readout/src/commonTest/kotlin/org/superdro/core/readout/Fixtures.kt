package org.superdro.core.readout

import kotlin.math.abs
import kotlin.test.assertTrue
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Workspace

/** The test data of the readout tests. */
internal object Fixtures {

    /** A mill profile in inch with format 3 and the defaults. */
    fun mill(metric: Boolean = false): MachineProfile = MachineProfile.create(MachineType.VerticalMill).copy(metricDefault = metric)

    /** A lathe profile. The X diameter mode is on. */
    fun lathe(metric: Boolean = false): MachineProfile = MachineProfile.create(MachineType.Lathe).copy(metricDefault = metric)

    /** A profile with only X enabled. */
    fun onlyX(profile: MachineProfile = mill()): MachineProfile =
        profile.copy(y = profile.y.copy(enabled = false), z = profile.z.copy(enabled = false))

    /** The active workspace of [profile]. */
    fun workspace(profile: MachineProfile): Workspace = profile.activeWorkspace!!

    /** Gives [workspace] with [state] for [axis]. */
    fun withState(workspace: Workspace, axis: AxisId, state: AxisState): Workspace =
        workspace.copy(axisStates = workspace.axisStates + (axis to state))

    /** A linear encoder profile. 25400 counts per inch gives 1 µm for each count. */
    fun linear(
        input: Char,
        resolution: Double = 25400.0,
        type: EncoderType = EncoderType.Scale,
        reversed: Boolean = false,
        filter: Boolean = false,
    ): EncoderProfile = EncoderProfile(input = input, type = type, kind = EncoderKind.Linear, resolution = resolution, reversed = reversed, filter = filter)

    /** An angular encoder profile. */
    fun angular(input: Char, resolution: Double): EncoderProfile =
        EncoderProfile(input = input, type = EncoderType.RotaryEncoder, kind = EncoderKind.Angular, resolution = resolution)

    /** An adapter with [profiles] (the letter of each profile is its key). */
    fun adapter(vararg profiles: EncoderProfile, debounceMs: Long = 0L): AdapterProfile =
        AdapterProfile(id = "a1", encoders = profiles.associateBy { profile -> Channels.normalize(profile.input) }, probeDebounceMs = debounceMs)
}

/** Asserts that [actual] is within [tolerance] of [expected]. */
internal fun assertNear(expected: Double, actual: Double, tolerance: Double = 1e-9, message: String? = null) {
    assertTrue(abs(expected - actual) <= tolerance, (message?.let { "$it: " } ?: "") + "expected $expected, actual $actual")
}
