package org.superdro.core.util.sound

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The proximity warning tone: an 800 Hz sine. A level below 1
 * repeats a 0.1 s beep with the period `max(0.1 s, min(0.1 s / level, 2 s))`. The level 1 gives a
 * continuous tone. The level 0 stops the tone. The common code of `core:functions` calculates the
 * level. The Android app and the desktop app use this object.
 */
object ProximityTone {

    const val FREQUENCY_HZ: Double = 800.0
    const val BEEP_MS: Int = 100
    const val MIN_PERIOD_MS: Double = 100.0
    const val MAX_PERIOD_MS: Double = 2000.0

    /** The tone stops when no new level comes for this time. */
    const val SILENCE_AFTER_MS: Long = 5000L

    /** The length of one chunk of the output. A new level applies within one chunk. */
    const val CHUNK_MS: Int = 10

    /** What the player does for one level. */
    sealed interface Pattern {
        /** No tone. */
        data object Stop : Pattern

        /** One tone with no gap. */
        data object Continuous : Pattern

        /** A beep of 0.1 s at the start of each period of [periodMs]. */
        data class Repeat(val periodMs: Double) : Pattern
    }

    /** Gives the pattern of [level]. A level that is not a number stops the tone. */
    fun patternOf(level: Double): Pattern = when {
        level.isNaN() || level <= 0.0 -> Pattern.Stop
        level >= 1.0 -> Pattern.Continuous
        else -> Pattern.Repeat(max(MIN_PERIOD_MS, min(MIN_PERIOD_MS / level, MAX_PERIOD_MS)))
    }

    /** Gives the frames of one period of [periodMs] at [sampleRate]. */
    fun periodFrames(periodMs: Double, sampleRate: Int = ToneSynth.SAMPLE_RATE): Int = (periodMs * sampleRate / 1000.0).roundToInt()

    /**
     * Writes the tone to an output while [active] gives a pattern (mono PCM 16 bit at
     * [ToneSynth.SAMPLE_RATE]). The continuous tone goes in chunks of [CHUNK_MS] with no break in
     * the phase. A repeat writes the beep, then the silence of the period in chunks of
     * [CHUNK_MS], and it stops the silence when the pattern changes. The function returns when
     * [active] gives null or [Pattern.Stop].
     *
     * @param active Gives the current pattern, or null when the tone must stop.
     * @param write Writes the first `count` samples of the array. The call can block while the
     *   buffer of the output is full.
     */
    fun stream(active: () -> Pattern?, write: (samples: ShortArray, count: Int) -> Unit) {
        val chunkFrames = ToneSynth.frames(CHUNK_MS)
        val beep = ToneSynth.tone(FREQUENCY_HZ, BEEP_MS)
        val silence = ShortArray(chunkFrames)
        val continuous = ShortArray(chunkFrames)
        var phase = 0.0
        val step = 2.0 * PI * FREQUENCY_HZ / ToneSynth.SAMPLE_RATE
        while (true) {
            when (val current = active() ?: return) {
                Pattern.Continuous -> {
                    for (index in continuous.indices) {
                        continuous[index] = (sin(phase) * ToneSynth.FULL_SCALE).roundToInt().toShort()
                        phase = (phase + step) % (2.0 * PI)
                    }
                    write(continuous, continuous.size)
                }
                is Pattern.Repeat -> {
                    write(beep, beep.size)
                    var rest = periodFrames(current.periodMs) - beep.size
                    while (rest > 0 && active() == current) {
                        val frames = min(rest, chunkFrames)
                        write(silence, frames)
                        rest -= frames
                    }
                }
                Pattern.Stop -> return
            }
        }
    }
}

/**
 * The last level of the proximity tone and its time. The level thread
 * and the player thread use one object, so the fields are volatile.
 *
 * @param nowMs The monotonic clock in milliseconds.
 */
class ProximityLevel(private val nowMs: () -> Long) {

    @Volatile
    private var pattern: ProximityTone.Pattern = ProximityTone.Pattern.Stop

    @Volatile
    private var lastLevelMs = 0L

    /** The pattern of the last level. */
    val current: ProximityTone.Pattern get() = pattern

    /** Keeps the pattern of [level] and the time now. */
    fun set(level: Double) {
        pattern = ProximityTone.patternOf(level)
        lastLevelMs = nowMs()
    }

    /** Stops the tone, for example when the output did not open. */
    fun stop() {
        pattern = ProximityTone.Pattern.Stop
    }

    /**
     * Gives the pattern to play now, or null: the level 0 stops the tone, and the tone stops
     * [ProximityTone.SILENCE_AFTER_MS] after the last level.
     */
    fun active(): ProximityTone.Pattern? {
        val now = pattern
        if (now == ProximityTone.Pattern.Stop) return null
        if (nowMs() - lastLevelMs > ProximityTone.SILENCE_AFTER_MS) return null
        return now
    }
}
