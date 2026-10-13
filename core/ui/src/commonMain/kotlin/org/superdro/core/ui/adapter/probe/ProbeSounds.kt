package org.superdro.core.ui.adapter.probe

import org.superdro.core.connection.DeviceId
import org.superdro.core.functions.FunctionSound
import org.superdro.core.functions.tipcal.TouchBeep
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.ui.sound.UiSound

/**
 * The probe and edge finder sounds. The sounds obey the mute
 * rules of the machine [device].
 *
 * | Event | Sound |
 * |---|---|
 * | A probe sample before the last sample | Short (440 Hz, 0.1 s) |
 * | The last sample: the edge is found | Long (440 Hz, 0.5 s) |
 * | A sample that the function refuses | Error (two 220 Hz beeps) |
 *
 * The tip calibration, the edge finder, the automatic Z reference,
 * and the probe points of the measurement mode use it.
 */
class ProbeSounds(private val sounds: SoundController, private val device: DeviceId?) {

    /** An accepted sample. [last] is true for the last sample of the edge: the long sound. Else the short sound. */
    fun sample(last: Boolean) = sounds.play(if (last) UiSound.Long else UiSound.Short, device)

    /** A refused sample: the error sound. */
    fun refused() = sounds.play(UiSound.Error, device)

    /**
     * The sound [sound] of a probe function of `core:functions` (the edge finder, the Z
     * reference): [FunctionSound.Short] is a sample, [FunctionSound.Long] is the last sample, and
     * [FunctionSound.Error] is a refused sample.
     */
    fun play(sound: FunctionSound) = when (sound) {
        FunctionSound.Short -> sample(last = false)
        FunctionSound.Long -> sample(last = true)
        FunctionSound.Error -> refused()
    }

    /** The sound of an accepted touch of the tip calibration: the third touch is [TouchBeep.LongBeep]. */
    fun touch(beep: TouchBeep) = sample(last = beep == TouchBeep.LongBeep)
}
