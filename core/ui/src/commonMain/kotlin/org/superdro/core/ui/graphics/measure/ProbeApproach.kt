package org.superdro.core.ui.graphics.measure

import org.superdro.core.functions.measurement.ApproachSign
import org.superdro.core.functions.measurement.HistorySample
import org.superdro.core.model.AxisId
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.input.InputHistory

/**
 * The approach signs of a probe point. The rule is
 * [ApproachSign]: the motion of 5 µm or more in the last 1000 ms, with no sample gap of more than
 * 500 ms.
 *
 * The samples come from the input history of the session. The sample times are the times of the
 * samples of all inputs of the axes in the window before the trigger. At each time, the position of
 * each axis is the sum `R` of the history ([InputHistory.axisPositionAt]). With the axis alignment
 * of the measurement mode, the X and Y positions turn with the alignment, so the signs are
 * in the frame of the readout.
 */
object ProbeApproach {

    /** The part of the history before the trigger that the rule can use: the window and one gap. */
    const val LOOKBACK_MS: Long = ApproachSign.WINDOW_MS + ApproachSign.MAX_GAP_MS

    /** Gives the approach sign −1, 0, or +1 of each axis of [axes] at [timeMs]. */
    fun signs(history: InputHistory, frame: ReadoutFrame, axes: List<AxisId>, timeMs: Long): Map<AxisId, Int> {
        val samples = samples(history, frame, axes, timeMs)
        return axes.associateWith { axis -> ApproachSign.of(samples[axis].orEmpty(), timeMs) }
    }

    /** Gives the history samples of each axis of [axes] in the [LOOKBACK_MS] before [timeMs]. */
    fun samples(history: InputHistory, frame: ReadoutFrame, axes: List<AxisId>, timeMs: Long): Map<AxisId, List<HistorySample>> {
        val times = sortedSetOf<Long>()
        axes.forEach { axis ->
            history.terms(axis)?.inputs?.forEach { input ->
                val channel = history.channel(input) ?: return@forEach
                for (index in 0 until channel.size) {
                    val time = channel.timeAt(index)
                    if (time in (timeMs - LOOKBACK_MS)..timeMs) times += time
                }
            }
        }
        val rotation = frame.alignment
        val result = axes.associateWith { mutableListOf<HistorySample>() }
        for (time in times) {
            val positions = axes.associateWith { axis -> history.axisPositionAt(axis, time) }.toMutableMap()
            val x = positions[AxisId.X]
            val y = positions[AxisId.Y]
            if (rotation != null && x != null && y != null) {
                if (x.isFinite() && y.isFinite()) {
                    val turned = rotation.forward(x, y)
                    positions[AxisId.X] = turned.x
                    positions[AxisId.Y] = turned.y
                } else {
                    positions[AxisId.X] = Double.NaN
                    positions[AxisId.Y] = Double.NaN
                }
            }
            axes.forEach { axis -> result.getValue(axis) += HistorySample(time, positions[axis] ?: Double.NaN) }
        }
        return result
    }
}
