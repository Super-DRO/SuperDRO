package org.superdro.core.ui.dataoutput

import kotlin.math.abs
import org.superdro.core.dataoutput.OutputValues
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.SurfaceSpeed
import org.superdro.core.readout.input.AxisValue
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.ProbeEvent

/**
 * The readout frame of the display, for the data output. It is
 * the same frame as the frame of the readout presenter of the tab: the sums `R` of the session,
 * and `R` = 0 for the tachometer when the tachometer mode is not "sensor". The
 * output has no hold: a held value is a state of the display only.
 */
object DisplayFrames {

    /** Gives the frame of [profile] with [workspace] and the session values [inputs]. */
    fun of(profile: MachineProfile, workspace: Workspace, inputs: InputSnapshot): ReadoutFrame {
        val sums = inputs.axisValues().toMutableMap()
        if (profile.tachometer.mode != TachometerMode.Sensor) sums[AxisId.Tachometer] = 0.0
        return ReadoutFrame(
            profile = profile,
            workspace = workspace,
            inputs = sums,
            projectedAxes = inputs.axes.values.filter { value -> value.projected }.mapTo(LinkedHashSet()) { value -> value.axis },
        )
    }
}

/**
 * Keeps the last frame of [DisplayFrames.of]. A new snapshot with the same profile, the same
 * workspace, and the same sum and projection of each axis gives the kept frame. The frame does
 * not use the other parts of a snapshot (the channel counts and times, the probes). Thus the
 * kept frame and a new frame give the same values, and the frame calculates its readings only
 * one time.
 *
 * Use one cache in one coroutine.
 */
class DisplayFrameCache {
    private var frame: ReadoutFrame? = null
    private var axes: Map<AxisId, AxisValue> = emptyMap()

    /** Gives the frame of [profile] with [workspace] and the session values [inputs]. */
    fun of(profile: MachineProfile, workspace: Workspace, inputs: InputSnapshot): ReadoutFrame {
        val last = frame
        if (last != null && last.profile == profile && last.workspace == workspace && sameSums(axes, inputs.axes)) {
            axes = inputs.axes
            return last
        }
        return DisplayFrames.of(profile, workspace, inputs).also { next ->
            frame = next
            axes = inputs.axes
        }
    }

    private fun sameSums(old: Map<AxisId, AxisValue>, new: Map<AxisId, AxisValue>): Boolean {
        if (old === new) return true
        if (old.size != new.size) return false
        return old.all { (axis, value) ->
            val other = new[axis] ?: return false
            other.projected == value.projected && sameValue(other.value, value.value)
        }
    }

    /** Compares the bits, so that −0.0 and 0.0 are different and NaN is equal to NaN. */
    private fun sameValue(a: Double?, b: Double?): Boolean =
        if (a == null || b == null) a == null && b == null else a.toBits() == b.toBits()
}

/**
 * The last trigger of probe 1 (the probe port `p`) for the trigger fields of the data output.
 * Lengths are µm, feed rates are µm per minute.
 *
 * @property xUm The shown X absolute position at the trigger, or null when X is off or has no value.
 * @property dirX The approach direction of X: −1, 0, or 1, or null when X is off.
 * @property timeEpochMs The wall clock time of the trigger, in ms since 1970 UTC.
 * @property sequence The number of the trigger since the start of the output. The first trigger is 1.
 * @property feedUmPerMinute The approach feed rate when exactly one axis moves, else null.
 */
data class ProbeTrigger(
    val xUm: Double?,
    val yUm: Double?,
    val zUm: Double?,
    val dirX: Int?,
    val dirY: Int?,
    val dirZ: Int?,
    val timeEpochMs: Long,
    val sequence: Long,
    val feedUmPerMinute: Double?,
)

/**
 * Fills the 25 fields of the data output from the readout model of the display.
 * Thus the output and the display show the same values: the lathe diameter, the incremental
 * offsets and the sub-datum offsets, and the X/Y alignment rotation.
 *
 * - `*_absolute`: the post-processor of the axis (the lathe diameter, the angle roll-over) on
 *   `abs`.
 * - `*_incremental`: the same on `abs` plus the sub-datum offset, else the incremental offset.
 * - `*_feed_rate`: the rate of the 2000 ms window of the input history ([feedRates]). At rest the
 *   rate goes to 0.
 * - `spindle_rpm`: the shown value of the tachometer. `surface_speed`: `|rpm × D × π|` with the
 *   diameter of the display (`SurfaceSpeed.diameterUm`).
 * - `probe_1` and `probe_2`: the probe ports `p` and `q`. A port with no state gives 0
 *   when the probe input is on, else no value.
 * - The trigger fields come from the last trigger of probe 1 ([trigger]), or have no value
 *   before the first trigger.
 */
object OutputValueSource {

    /** The linear axes of the trigger fields. */
    private val TRIGGER_AXES: List<AxisId> = listOf(AxisId.X, AxisId.Y, AxisId.Z)

    /**
     * Gives the values of [frame] and [inputs]. [feedRates] has the feed rate of each axis in
     * units for each minute. [updatedAtEpochMs] is the wall clock time of the last value update.
     */
    fun values(
        frame: ReadoutFrame,
        inputs: InputSnapshot,
        feedRates: Map<AxisId, Double>,
        trigger: ProbeTrigger?,
        updatedAtEpochMs: Long?,
    ): OutputValues {
        val rpm = spindleRpm(frame)
        return OutputValues(
            xAbsolute = absolute(frame, AxisId.X),
            xIncremental = incremental(frame, AxisId.X),
            xFeedRate = feedRate(frame, AxisId.X, feedRates),
            yAbsolute = absolute(frame, AxisId.Y),
            yIncremental = incremental(frame, AxisId.Y),
            yFeedRate = feedRate(frame, AxisId.Y, feedRates),
            zAbsolute = absolute(frame, AxisId.Z),
            zIncremental = incremental(frame, AxisId.Z),
            zFeedRate = feedRate(frame, AxisId.Z, feedRates),
            angularAbsolute = absolute(frame, AxisId.Angular),
            angularIncremental = incremental(frame, AxisId.Angular),
            angularFeedRate = feedRate(frame, AxisId.Angular, feedRates),
            spindleRpm = rpm,
            surfaceSpeed = rpm?.let { value -> surfaceSpeed(frame, value) },
            probe1 = probeState(frame, inputs, Channels.P),
            probe2 = probeState(frame, inputs, Channels.Q),
            probe1TriggerX = trigger?.xUm,
            probe1TriggerY = trigger?.yUm,
            probe1TriggerZ = trigger?.zUm,
            probe1TriggerDirX = trigger?.dirX?.toDouble(),
            probe1TriggerDirY = trigger?.dirY?.toDouble(),
            probe1TriggerDirZ = trigger?.dirZ?.toDouble(),
            probe1TriggerTime = trigger?.timeEpochMs?.toDouble(),
            probe1TriggerSeq = trigger?.sequence?.toDouble(),
            probe1TriggerFeed = trigger?.feedUmPerMinute,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }

    /**
     * Gives the trigger values of [event] with the offsets of [frame]. [directions] has the
     * direction of the last motion of each axis before the trigger (the 5 µm threshold).
     */
    fun trigger(frame: ReadoutFrame, event: ProbeEvent, directions: Map<AxisId, Int>, sequence: Long, timeEpochMs: Long): ProbeTrigger {
        val absolutes = frame.absoluteFor(event.axisPositions.filterValues { position -> position.isFinite() })
        fun position(axis: AxisId): Double? {
            if (!frame.enabled(axis)) return null
            val value = absolutes[axis]?.takeIf { position -> position.isFinite() } ?: return null
            return frame.postProcessor(axis).toShown(value)
        }
        fun direction(axis: AxisId): Int? = if (frame.enabled(axis)) directions[axis] ?: 0 else null
        val moving = TRIGGER_AXES.filter { axis -> (direction(axis) ?: 0) != 0 }
        val feed = moving.singleOrNull()
            ?.let { axis -> event.axisVelocities[axis] }
            ?.takeIf { speed -> speed.isFinite() }
            ?.let { speed -> abs(speed) * SECONDS_PER_MINUTE }
        return ProbeTrigger(
            xUm = position(AxisId.X),
            yUm = position(AxisId.Y),
            zUm = position(AxisId.Z),
            dirX = direction(AxisId.X),
            dirY = direction(AxisId.Y),
            dirZ = direction(AxisId.Z),
            timeEpochMs = timeEpochMs,
            sequence = sequence,
            feedUmPerMinute = feed,
        )
    }

    private const val SECONDS_PER_MINUTE: Double = 60.0

    private fun absolute(frame: ReadoutFrame, axis: AxisId): Double? {
        val reading = frame.reading(axis).takeIf { value -> value.enabled && value.hasValue } ?: return null
        return frame.postProcessor(axis).toShown(reading.absolute)
    }

    private fun incremental(frame: ReadoutFrame, axis: AxisId): Double? {
        val reading = frame.reading(axis).takeIf { value -> value.enabled && value.hasValue } ?: return null
        val offset = frame.subDatumOffsets[axis] ?: frame.state(axis).incrementalOffset
        return frame.postProcessor(axis).toShown(reading.absolute + offset)
    }

    private fun feedRate(frame: ReadoutFrame, axis: AxisId, feedRates: Map<AxisId, Double>): Double? {
        val reading = frame.reading(axis)
        if (!reading.enabled || !reading.hasValue) return null
        return feedRates[axis] ?: 0.0
    }

    private fun spindleRpm(frame: ReadoutFrame): Double? {
        val reading = frame.reading(AxisId.Tachometer)
        return if (reading.enabled && reading.hasValue) reading.shown else null
    }

    private fun surfaceSpeed(frame: ReadoutFrame, rpm: Double): Double? {
        val diameter = SurfaceSpeed.diameterUm(frame.profile, frame.workspace, frame.reading(AxisId.X).absolute)
        return if (diameter.isFinite()) OutputValues.surfaceSpeedUmPerMinute(rpm, diameter) else null
    }

    private fun probeState(frame: ReadoutFrame, inputs: InputSnapshot, port: Char): Double? {
        val state = inputs.probes[port]
        return when {
            state != null -> if (state) 1.0 else 0.0
            frame.profile.probe.enabled -> 0.0
            else -> null
        }
    }
}
