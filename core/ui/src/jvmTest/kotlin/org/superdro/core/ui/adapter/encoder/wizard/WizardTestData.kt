package org.superdro.core.ui.adapter.encoder.wizard

import org.superdro.core.functions.errormap.ErrorMapWizardSession
import org.superdro.core.functions.errormap.RecordedPass
import org.superdro.core.functions.errormap.SamplePair
import org.superdro.core.readout.input.ChannelStatus
import org.superdro.core.readout.input.ChannelValue
import org.superdro.core.readout.input.InputSnapshot

/** The test data of the error map wizard. */
object WizardTestData {

    /** A snapshot with the raw counts [counts] of the inputs at the time [timeMs]. */
    fun snapshot(timeMs: Long, vararg counts: Pair<Char, Long>): InputSnapshot = InputSnapshot(
        channels = counts.associate { (input, count) -> input to ChannelValue(input, count, count, count.toDouble(), timeMs, ChannelStatus.Ok) },
    )

    /** A round trip from [from] to [to] and back with [step] counts. The reference is the target plus [error]. */
    fun roundTrip(from: Long, to: Long, step: Long, startTime: Long = 0, error: (Long) -> Long): RecordedPass {
        val pairs = ArrayList<SamplePair>()
        var time = startTime
        var t = from
        while (t <= to) {
            pairs.add(SamplePair(t, t + error(t), time++))
            t += step
        }
        t = to - step
        while (t >= from) {
            pairs.add(SamplePair(t, t + error(t), time++))
            t -= step
        }
        return RecordedPass(pairs)
    }

    /** 8 counts of error in each odd block of 10000 counts. */
    fun squareWave(t: Long): Long = if ((t / 10000) % 2L == 1L) 8 else 0

    /** Two passes of the square wave error: the builder gives a map that "Save Map" accepts. */
    fun goodSession(adapterId: String, input: Char = 'x'): ErrorMapWizardSession = ErrorMapWizardSession.of(
        adapterId = adapterId,
        input = input,
        referenceInput = 'w',
        passes = listOf(roundTrip(0, 95000, 100) { squareWave(it) }, roundTrip(0, 95000, 100, 5000) { squareWave(it) }),
        verification = roundTrip(0, 95000, 500, 20000) { squareWave(it) },
    )

    /** One pass of the square wave error: the map cannot be saved (fewer than 2 passes). */
    fun onePassSession(adapterId: String, input: Char = 'x'): ErrorMapWizardSession = ErrorMapWizardSession.of(
        adapterId = adapterId,
        input = input,
        referenceInput = 'w',
        passes = listOf(roundTrip(0, 95000, 100) { squareWave(it) }),
        verification = null,
    )
}
