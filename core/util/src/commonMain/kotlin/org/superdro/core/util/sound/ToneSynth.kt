package org.superdro.core.util.sound

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** The three beeps of the UI sounds. The link tones are not beeps. */
enum class Beep {
    /** 440 Hz for 0.1 s. */
    Short,

    /** 440 Hz for 0.5 s. */
    Long,

    /** Two beeps of 220 Hz for 0.1 s with a gap of 0.05 s. */
    Error,
}

/**
 * Makes the tone data of the UI sounds in code. SuperDRO contains no
 * sound file. The Android app and the desktop app use this object, so that the two apps make the
 * same samples. The functions have no platform type.
 *
 * The sine uses 2π, and each buffer has one sample for each channel of each frame. Thus the
 * pitch and the length are correct for the channel mask of the output: a stereo buffer has two
 * equal samples for each frame, not two frames of a mono tone.
 */
object ToneSynth {

    /** The sample rate of the outputs, the native rate of most devices. */
    const val SAMPLE_RATE: Int = 48_000

    /** The linear rise at the start and the linear fall at the end: 5 % of the frames each. */
    const val RAMP_FRACTION: Double = 0.05

    /** The frequency of the short and the long beep. */
    const val BEEP_HZ: Double = 440.0

    /** The frequency of the two beeps of the error sound. */
    const val ERROR_HZ: Double = 220.0

    const val SHORT_MS: Int = 100
    const val LONG_MS: Int = 500
    const val ERROR_BEEP_MS: Int = 100
    const val ERROR_GAP_MS: Int = 50

    /** The full scale of a 16 bit sample. */
    const val FULL_SCALE: Double = 32767.0

    /** Gives the number of frames of [durationMs]. */
    fun frames(durationMs: Int, sampleRate: Int = SAMPLE_RATE): Int = (sampleRate.toLong() * durationMs / 1000L).toInt()

    /**
     * Gives the envelope value of the frame [index] of [frameCount] frames: a linear rise in the first
     * [rampFraction] of the frames, a linear fall in the last [rampFraction], and 1 between them.
     */
    fun envelope(index: Int, frameCount: Int, rampFraction: Double = RAMP_FRACTION): Double {
        val ramp = (frameCount * rampFraction).roundToInt()
        if (ramp <= 0) return 1.0
        return when {
            index < ramp -> index.toDouble() / ramp
            index >= frameCount - ramp -> (frameCount - 1 - index).toDouble() / ramp
            else -> 1.0
        }
    }

    /**
     * Gives a sine of [frequencyHz] for [durationMs] with the linear rise and fall. The samples of
     * the [channels] of one frame are equal and follow each other (interleaved PCM 16 bit).
     */
    fun tone(
        frequencyHz: Double,
        durationMs: Int,
        channels: Int = 1,
        sampleRate: Int = SAMPLE_RATE,
        rampFraction: Double = RAMP_FRACTION,
    ): ShortArray {
        require(channels >= 1) { "An output has one or more channels." }
        val frameCount = frames(durationMs, sampleRate)
        val samples = ShortArray(frameCount * channels)
        for (frame in 0 until frameCount) {
            val value = sin(2.0 * PI * frequencyHz * frame / sampleRate) * envelope(frame, frameCount, rampFraction)
            val sample = (value * FULL_SCALE).roundToInt().toShort()
            for (channel in 0 until channels) samples[frame * channels + channel] = sample
        }
        return samples
    }

    /** Gives [durationMs] of silence for [channels]. */
    fun silence(durationMs: Int, channels: Int = 1, sampleRate: Int = SAMPLE_RATE): ShortArray =
        ShortArray(frames(durationMs, sampleRate) * channels)

    /** Gives the samples of [beep]. */
    fun beep(beep: Beep, channels: Int = 1, sampleRate: Int = SAMPLE_RATE): ShortArray = when (beep) {
        Beep.Short -> tone(BEEP_HZ, SHORT_MS, channels, sampleRate)
        Beep.Long -> tone(BEEP_HZ, LONG_MS, channels, sampleRate)
        Beep.Error -> {
            val one = tone(ERROR_HZ, ERROR_BEEP_MS, channels, sampleRate)
            one + silence(ERROR_GAP_MS, channels, sampleRate) + one
        }
    }

    /** Gives the time that [beep] plays: the busy time of its player. */
    fun durationMs(beep: Beep): Long = when (beep) {
        Beep.Short -> SHORT_MS.toLong()
        Beep.Long -> LONG_MS.toLong()
        Beep.Error -> (2 * ERROR_BEEP_MS + ERROR_GAP_MS).toLong()
    }

    /** Gives the first [count] samples of [samples] as bytes: signed 16 bit, little-endian. */
    fun toBytes(samples: ShortArray, count: Int = samples.size): ByteArray {
        val bytes = ByteArray(count * 2)
        for (index in 0 until count) {
            val value = samples[index].toInt()
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
