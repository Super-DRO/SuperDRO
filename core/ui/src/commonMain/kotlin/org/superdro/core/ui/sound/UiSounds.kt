package org.superdro.core.ui.sound

/**
 * Plays the UI sounds (a platform contract). The platform implementation only plays a sound. The
 * common code applies the mute rules before it calls this contract.
 *
 * Rules for an implementation:
 * - Make the tones in code. Use a correct buffer for the channel mask of the track.
 * - A busy sound drops a new request of the same kind.
 */
interface UiSounds {

    /** Plays one UI sound. */
    fun play(sound: UiSound)

    /**
     * Sets the level of the proximity warning tone (an 800 Hz sine).
     * A level below 1 repeats a 0.1 s beep with the period `max(0.1 s, min(0.1 s / level, 2 s))`.
     * The level 1 gives a continuous tone. The level 0 stops the tone.
     */
    fun proximity(level: Double)
}

/** The UI sounds. */
enum class UiSound {
    /** 440 Hz sine, 0.1 s, 5 % linear rise and fall. */
    Short,

    /** 440 Hz sine, 0.5 s, 5 % rise and fall. */
    Long,

    /** Two 220 Hz beeps of 0.1 s with a gap of 0.05 s. */
    Error,

    /** Android `ToneGenerator` tone 90 (`TONE_CDMA_PRESSHOLDKEY_LITE`). */
    LinkUp,

    /** Android `ToneGenerator` tone 33 (`TONE_SUP_PIP`), 1000 ms. */
    LinkLost,
}

/** A [UiSounds] that plays nothing, for the tests, the previews, and the JVM. */
object NoUiSounds : UiSounds {
    override fun play(sound: UiSound) = Unit
    override fun proximity(level: Double) = Unit
}
