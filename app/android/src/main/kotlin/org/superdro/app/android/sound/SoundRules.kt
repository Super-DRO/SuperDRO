package org.superdro.app.android.sound

import org.superdro.core.ui.sound.UiSound
import org.superdro.core.util.sound.Beep
import org.superdro.core.util.sound.SoundGate
import org.superdro.core.util.sound.ToneSynth

/** The player of a sound. Two sounds of one kind cannot play at the same time. */
enum class SoundKind {
    /** The beeps of the `AudioTrack` player: short, long, and error. */
    Beep,

    /** The link tones of the `ToneGenerator`: link up and link lost. */
    LinkTone,
}

/**
 * The rules of the UI sounds of the Android app with no Android type. The tone data, the busy rule,
 * and the proximity tone are common code of `core:util`
 * (`org.superdro.core.util.sound`). This object gives only the parts of the Android players.
 */
object SoundRules {

    /** `ToneGenerator.TONE_CDMA_PRESSHOLDKEY_LITE`: the link up tone. */
    const val TONE_LINK_UP: Int = 90

    /** `ToneGenerator.TONE_SUP_PIP`: the link lost tone. */
    const val TONE_LINK_LOST: Int = 33

    /** The length of the link lost tone. */
    const val LINK_LOST_MS: Int = 1000

    /**
     * The busy time of the link up tone. The tone has the default length of `ToneGenerator`, which
     * Android does not give.
     */
    const val LINK_UP_BUSY_MS: Long = 400L

    /** The player of [sound]. */
    fun kindOf(sound: UiSound): SoundKind = when (sound) {
        UiSound.Short, UiSound.Long, UiSound.Error -> SoundKind.Beep
        UiSound.LinkUp, UiSound.LinkLost -> SoundKind.LinkTone
    }

    /**
     * The beep of [sound]. The link tones are not beeps: `ToneGenerator` plays them, and this
     * function refuses them with [IllegalArgumentException].
     */
    fun beepOf(sound: UiSound): Beep = when (sound) {
        UiSound.Short -> Beep.Short
        UiSound.Long -> Beep.Long
        UiSound.Error -> Beep.Error
        UiSound.LinkUp, UiSound.LinkLost -> throw IllegalArgumentException("$sound is a ToneGenerator tone, not a beep.")
    }

    /** The time that [sound] keeps its player busy. */
    fun busyMs(sound: UiSound): Long = when (sound) {
        UiSound.Short, UiSound.Long, UiSound.Error -> ToneSynth.durationMs(beepOf(sound))
        UiSound.LinkUp -> LINK_UP_BUSY_MS
        UiSound.LinkLost -> LINK_LOST_MS.toLong()
    }

    /** Gives the busy rule of the Android players. */
    fun gate(nowMs: () -> Long): SoundGate<UiSound, SoundKind> = SoundGate(nowMs, ::kindOf, ::busyMs)
}
