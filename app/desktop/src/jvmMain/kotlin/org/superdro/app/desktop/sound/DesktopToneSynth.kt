package org.superdro.app.desktop.sound

import org.superdro.core.ui.sound.UiSound
import org.superdro.core.util.sound.Beep
import org.superdro.core.util.sound.SoundGate
import org.superdro.core.util.sound.ToneSynth

/**
 * The samples of the UI sounds of the desktop app. SuperDRO contains no sound file. The three beeps
 * are the samples of [ToneSynth] of `core:util`, which the Android app also uses: a sine with 2π, a
 * linear rise and fall of 5 %, mono PCM 16 bit at 48 kHz, one sample for each frame.
 *
 * The desktop has no Android `ToneGenerator`. Thus this object also makes the two link tones, with
 * the patterns that the Android documentation gives for the two constants:
 * - Link up, `TONE_CDMA_PRESSHOLDKEY_LITE` (90): 587 Hz for 375 ms, then 1175 Hz for 125 ms.
 * - Link lost, `TONE_SUP_PIP` (33): four bursts of 480 Hz, 100 ms on and 100 ms off. The Android
 *   app plays it for 1000 ms; the four bursts end before that.
 */
object DesktopToneSynth {

    const val LINK_UP_LOW_HZ: Double = 587.0
    const val LINK_UP_LOW_MS: Int = 375
    const val LINK_UP_HIGH_HZ: Double = 1175.0
    const val LINK_UP_HIGH_MS: Int = 125

    const val LINK_LOST_HZ: Double = 480.0
    const val LINK_LOST_BURST_MS: Int = 100
    const val LINK_LOST_GAP_MS: Int = 100
    const val LINK_LOST_BURSTS: Int = 4

    /** Gives the samples of [sound]: the UI beeps, and the two link tones. */
    fun samplesOf(sound: UiSound, sampleRate: Int = ToneSynth.SAMPLE_RATE): ShortArray = when (sound) {
        UiSound.Short -> ToneSynth.beep(Beep.Short, sampleRate = sampleRate)
        UiSound.Long -> ToneSynth.beep(Beep.Long, sampleRate = sampleRate)
        UiSound.Error -> ToneSynth.beep(Beep.Error, sampleRate = sampleRate)
        UiSound.LinkUp -> ToneSynth.tone(LINK_UP_LOW_HZ, LINK_UP_LOW_MS, sampleRate = sampleRate) +
            ToneSynth.tone(LINK_UP_HIGH_HZ, LINK_UP_HIGH_MS, sampleRate = sampleRate)
        UiSound.LinkLost -> {
            val burst = ToneSynth.tone(LINK_LOST_HZ, LINK_LOST_BURST_MS, sampleRate = sampleRate) +
                ToneSynth.silence(LINK_LOST_GAP_MS, sampleRate = sampleRate)
            (1 until LINK_LOST_BURSTS).fold(burst) { all, _ -> all + burst }
        }
    }
}

/** The rules of the desktop UI sounds. */
object DesktopSoundRules {

    /** The player of a sound. Two sounds of one kind cannot play at the same time. */
    enum class Kind {
        /** Short, long, and error. */
        Beep,

        /** Link up and link lost. */
        LinkTone,
    }

    /** The player of [sound]. */
    fun kindOf(sound: UiSound): Kind = when (sound) {
        UiSound.Short, UiSound.Long, UiSound.Error -> Kind.Beep
        UiSound.LinkUp, UiSound.LinkLost -> Kind.LinkTone
    }

    /** The time that [sound] keeps its player busy: the length of its samples. */
    fun busyMs(sound: UiSound): Long = when (sound) {
        UiSound.Short -> ToneSynth.durationMs(Beep.Short)
        UiSound.Long -> ToneSynth.durationMs(Beep.Long)
        UiSound.Error -> ToneSynth.durationMs(Beep.Error)
        UiSound.LinkUp -> (DesktopToneSynth.LINK_UP_LOW_MS + DesktopToneSynth.LINK_UP_HIGH_MS).toLong()
        UiSound.LinkLost -> (DesktopToneSynth.LINK_LOST_BURSTS * (DesktopToneSynth.LINK_LOST_BURST_MS + DesktopToneSynth.LINK_LOST_GAP_MS)).toLong()
    }

    /** Gives the busy rule of the desktop players. */
    fun gate(nowMs: () -> Long): SoundGate<UiSound, Kind> = SoundGate(nowMs, ::kindOf, ::busyMs)
}
