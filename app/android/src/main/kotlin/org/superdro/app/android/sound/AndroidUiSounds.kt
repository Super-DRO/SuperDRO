package org.superdro.app.android.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Process
import android.os.SystemClock
import com.toxicbakery.logging.Arbor
import java.util.concurrent.Executors
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.sound.UiSounds
import org.superdro.core.util.sound.ProximityLevel
import org.superdro.core.util.sound.ProximityTone
import org.superdro.core.util.sound.SoundGate
import org.superdro.core.util.sound.ToneSynth

/**
 * The Android [UiSounds]. It only plays: the common code of `core:ui` applies the mute rules
 * before it calls this class.
 *
 * - Short, long, and error: a static mono `AudioTrack` at 48 kHz with the data of [ToneSynth] of
 *   `core:util`.
 * - Link up and link lost: `ToneGenerator` tones 90 (`TONE_CDMA_PRESSHOLDKEY_LITE`) and 33
 *   (`TONE_SUP_PIP`, 1000 ms).
 * - Proximity: an 800 Hz sine on a stream track of its own thread ([ProximityTone]).
 * - A busy sound drops a new request of the same kind ([SoundGate] with [SoundRules.gate]).
 *
 * All sounds use the media volume (`USAGE_MEDIA`, `STREAM_MUSIC`), so one volume control applies.
 * The calls return at once: one audio thread does the work.
 */
class AndroidUiSounds(
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
) : UiSounds {

    private val audio = Executors.newSingleThreadExecutor { task -> Thread(task, "superdro-sounds").apply { isDaemon = true } }
    private val gate = SoundRules.gate(nowMs)
    private val beeps = HashMap<UiSound, AudioTrack>()
    private var toneGenerator: ToneGenerator? = null
    private val proximityPlayer = ProximityPlayer(nowMs)

    override fun play(sound: UiSound) {
        if (!gate.tryStart(sound)) return
        audio.execute {
            try {
                when (sound) {
                    UiSound.Short, UiSound.Long, UiSound.Error -> playBeep(sound)
                    UiSound.LinkUp -> tones().startTone(SoundRules.TONE_LINK_UP)
                    UiSound.LinkLost -> tones().startTone(SoundRules.TONE_LINK_LOST, SoundRules.LINK_LOST_MS)
                }
            } catch (error: RuntimeException) {
                log.w(error) { "The sound $sound did not play" }
            }
        }
    }

    override fun proximity(level: Double) {
        proximityPlayer.setLevel(level)
    }

    private fun playBeep(sound: UiSound) {
        val track = beeps.getOrPut(sound) { staticTrack(ToneSynth.beep(SoundRules.beepOf(sound))) }
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        track.reloadStaticData()
        track.play()
    }

    private fun tones(): ToneGenerator =
        toneGenerator ?: ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME).also { toneGenerator = it }

    private fun staticTrack(samples: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(monoFormat())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .build()
        track.write(samples, 0, samples.size)
        return track
    }

    private companion object {
        val log = Arbor.tag("UiSounds")

        fun attributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        fun monoFormat(): AudioFormat = AudioFormat.Builder()
            .setSampleRate(ToneSynth.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
    }

    /**
     * Plays the proximity tone on a stream track. The thread writes the tone of
     * [ProximityTone.stream] in chunks of 10 ms, so that a new level applies within one chunk.
     * The tone stops for the level 0 and [ProximityTone.SILENCE_AFTER_MS] after the last level.
     */
    private class ProximityPlayer(nowMs: () -> Long) {

        private val level = ProximityLevel(nowMs)

        private var thread: Thread? = null

        @Synchronized
        fun setLevel(value: Double) {
            level.set(value)
            startIfNeeded()
        }

        @Synchronized
        private fun onThreadEnd() {
            thread = null
            // A level that came while the thread stopped starts a new thread.
            startIfNeeded()
        }

        private fun startIfNeeded() {
            if (thread == null && level.active() != null) {
                thread = Thread(::run, "superdro-proximity").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        private fun run() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (error: RuntimeException) {
                // The tone then plays with the default priority.
                log.w(error) { "The proximity thread did not get the audio priority" }
            }
            val chunkFrames = ToneSynth.frames(ProximityTone.CHUNK_MS)
            val minBuffer = AudioTrack.getMinBufferSize(ToneSynth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(monoFormat())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuffer, chunkFrames * Short.SIZE_BYTES * 2))
                    .build()
            } catch (error: RuntimeException) {
                log.w(error) { "No proximity track" }
                level.stop()
                onThreadEnd()
                return
            }
            try {
                track.play()
                ProximityTone.stream(level::active) { samples, count -> track.write(samples, 0, count) }
            } catch (error: RuntimeException) {
                log.w(error) { "The proximity tone stopped" }
            } finally {
                track.pause()
                track.flush()
                track.release()
                onThreadEnd()
            }
        }
    }
}
