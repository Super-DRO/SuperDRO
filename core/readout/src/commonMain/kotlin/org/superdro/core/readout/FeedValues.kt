package org.superdro.core.readout

import kotlin.math.abs
import kotlin.math.round
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType
import org.superdro.core.model.Units
import org.superdro.core.readout.input.InputHistory

/**
 * The values of the linear axis details.
 *
 * - Feed rate: the position change of the last 2000 ms from the input history, × 30
 *   (µm/min); 0 when the history has no value for one of the times.
 * - Feed per revolution: `fpr = |rate| / RPM` (µm per revolution), 0 if RPM = 0.
 * - Chip load: `fpr / teeth`. Lathe: `teeth` = 1. Mill: the tooth count of the active tool.
 *   0 if `teeth` = 0.
 * - Thread (lathe, Z axis only): mm: `pitch = rint(fpr × 20) / 20` µm, then `/ 1000`. Inch:
 *   `f = rint(fpr × 10) / 10` µm; "0.0" if `f` = 0, else `TPI = 25400 / f`.
 *
 * Example: the slide moves 250 µm each 50 ms. Rate = 300000 µm/min = "11.8110" in/min or
 * "300.000" mm/min. At 1200 RPM, `fpr` = 250 µm. With 4 teeth, the chip load is "0.0025"
 * in/tooth or "0.063" mm/tooth. Thread: "0.25" mm or "101.6" TPI.
 */
object FeedValues {

    /** Gives the feed rate of [axis] in µm/min (or ″/min) at the time [nowMs] of the session clock. */
    fun feedRate(history: InputHistory, axis: AxisId, nowMs: Long): Double = history.feedRate(axis, nowMs)

    /** `fpr = |rate| / |RPM|` (µm per revolution), 0 if RPM = 0. */
    fun feedPerRevolution(rateUmPerMinute: Double, rpm: Double): Double =
        if (rpm == 0.0 || !rpm.isFinite() || !rateUmPerMinute.isFinite()) 0.0 else abs(rateUmPerMinute) / abs(rpm)

    /** Gives the tooth count: 1 on a lathe, else [toothCount] of the active tool. */
    fun teeth(machineType: MachineType, toothCount: Int): Int = if (machineType.isLathe) 1 else toothCount

    /** `fpr / teeth` (µm for each tooth), 0 if `teeth` = 0. */
    fun chipLoad(feedPerRevolutionUm: Double, teeth: Int): Double = if (teeth == 0) 0.0 else feedPerRevolutionUm / teeth

    /** The thread pitch in mm: `rint(fpr × 20) / 20 / 1000`. */
    fun threadPitchMm(feedPerRevolutionUm: Double): Double = round(feedPerRevolutionUm * 20.0) / 20.0 / Units.UM_PER_MM

    /** The text of the thread pitch (format `###0.00`). */
    fun threadPitchText(feedPerRevolutionUm: Double): String = ReadoutPatterns.THREAD_PITCH_MM.format(threadPitchMm(feedPerRevolutionUm))

    /** The threads per inch `25400 / f` with `f = rint(fpr × 10) / 10`, or null when `f` = 0. */
    fun threadsPerInch(feedPerRevolutionUm: Double): Double? {
        val f = round(feedPerRevolutionUm * 10.0) / 10.0
        return if (f == 0.0) null else Units.UM_PER_INCH / f
    }

    /** The text of the threads per inch (format `###0.0`): "0.0" when `f` = 0. */
    fun threadsPerInchText(feedPerRevolutionUm: Double): String =
        ReadoutPatterns.THREADS_PER_INCH.format(threadsPerInch(feedPerRevolutionUm) ?: 0.0)

    /** True when the axis details show the thread pitch or the thread count: only on a lathe, for Z. */
    fun showsThread(machineType: MachineType, axis: AxisId): Boolean = machineType.isLathe && axis == AxisId.Z
}

/**
 * The tachometer value of the RPM sensor:
 * `RPM = count × (1 / tach_ppu)`, × −1 if `tach_invert`. No filter, no hysteresis. SuperDRO keeps
 * the pulses per revolution in the encoder profile of input `t`. Example: `tach_ppu` = 4, count
 * 4800 gives "1200".
 */
object Tachometer {

    /** Gives the RPM of the count [count] with [pulsesPerRev] and [invert]. */
    fun rpm(count: Long, pulsesPerRev: Double, invert: Boolean): Double {
        val k = if (pulsesPerRev > 0.0) 1.0 / pulsesPerRev else 0.0
        return count * k * if (invert) -1.0 else 1.0
    }

    /** Gives the text of [rpm] (format `####0`). */
    fun text(rpm: Double): String = ReadoutPatterns.RPM.format(rpm)
}
