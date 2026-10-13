package org.superdro.core.ui.graphics.probe

import org.superdro.core.connection.DeviceSession
import org.superdro.core.functions.FunctionSound
import org.superdro.core.functions.probe.AxisHistory
import org.superdro.core.functions.probe.PositionSample
import org.superdro.core.functions.probe.ProbeTrigger
import org.superdro.core.model.AxisId
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.input.InputHistory
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.ui.sound.UiSound

/**
 * Makes the [ProbeTrigger] of the edge finder from a [ProbeEvent] of the session. All times use the session clock.
 */
object ProbeTriggers {

    /** The history before the trigger time that an [AxisHistory] gets: the 1000 ms window and a margin. */
    const val HISTORY_BEFORE_MS: Long = 1500L

    /** The history after the trigger time that an [AxisHistory] gets, for the interpolation at the trigger. */
    const val HISTORY_AFTER_MS: Long = 600L

    /** The axes of the AUTO direction rule. */
    val AXES: List<AxisId> = listOf(AxisId.X, AxisId.Y, AxisId.Z)

    /**
     * Gives the latched absolute position of each axis of [event]: the sums `R` at the trigger time
     * with the offsets of [frame]. An axis with no finite sum, or an axis that is "not complete",
     * has no position.
     */
    fun latched(frame: ReadoutFrame, event: ProbeEvent): Map<AxisId, Double> {
        val sums = event.axisPositions.filter { (axis, value) -> value.isFinite() && axis !in event.incompleteAxes }
        return frame.absoluteFor(sums).filterValues { value -> value.isFinite() }
    }

    /**
     * Gives the [AxisHistory] of each axis of [axes] near [timeMs]: the sample times of the inputs
     * of the axis from `t − 1500` to `t + 600` ms, and the sum `R` of the axis at each time.
     */
    fun histories(history: InputHistory, timeMs: Long, axes: List<AxisId> = AXES): Map<AxisId, AxisHistory> {
        val result = LinkedHashMap<AxisId, AxisHistory>()
        axes.forEach { axis ->
            val inputs = history.terms(axis)?.inputs ?: return@forEach
            val times = sortedSetOf<Long>()
            inputs.forEach { input ->
                val channel = history.channel(input) ?: return@forEach
                for (index in 0 until channel.size) {
                    val time = channel.timeAt(index)
                    if (time >= timeMs - HISTORY_BEFORE_MS && time <= timeMs + HISTORY_AFTER_MS) times += time
                }
            }
            val samples = times.mapNotNull { time ->
                val position = history.axisPositionAt(axis, time)
                if (position.isFinite()) PositionSample(time, position) else null
            }
            result[axis] = AxisHistory(samples)
        }
        return result
    }

    /** Gives the trigger of [event]: the latch of [frame] and the histories of [session]. */
    suspend fun trigger(session: DeviceSession, frame: ReadoutFrame, event: ProbeEvent): ProbeTrigger {
        val histories = session.readHistory { history -> histories(history, event.timeMs) }
        return ProbeTrigger(timeMs = event.timeMs, latchedUm = latched(frame, event), histories = histories)
    }

    /** Gives the UI sound of a function sound. */
    fun uiSound(sound: FunctionSound): UiSound = when (sound) {
        FunctionSound.Short -> UiSound.Short
        FunctionSound.Long -> UiSound.Long
        FunctionSound.Error -> UiSound.Error
    }
}
