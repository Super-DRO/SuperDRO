package org.superdro.app.desktop.sound

import com.toxicbakery.logging.Arbor
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.sound.UiSounds
import org.superdro.core.util.sound.ProximityLevel
import org.superdro.core.util.sound.ProximityTone
import org.superdro.core.util.sound.SoundGate
import org.superdro.core.util.sound.ToneSynth

/** The audio device of the UI sounds. [JavaSoundOutput] is the device of the app. A test gives a fake. */
interface AudioOutput {

    /** Plays [samples] (mono PCM 16 bit at [ToneSynth.SAMPLE_RATE]) and returns when they played. */
    fun play(samples: ShortArray)

    /** Opens a stream for the proximity tone, or gives null when the computer has no audio device. */
    fun openStream(): AudioStream?
}

/** One open audio stream. */
interface AudioStream {

    /** Writes the first [count] samples of [samples]. The call blocks while the buffer of the line is full. */
    fun write(samples: ShortArray, count: Int = samples.size)

    /** Stops the stream. */
    fun close()
}

/**
 * The audio device of the desktop through `javax.sound.sampled`: a `SourceDataLine` with mono PCM
 * signed 16 bit, little-endian, at 48 kHz. A computer with no audio device plays no sound: the
 * first failure goes to the log, the next failures do not.
 */
class JavaSoundOutput : AudioOutput {

    private val format = AudioFormat(ToneSynth.SAMPLE_RATE.toFloat(), BITS, CHANNELS, true, false)

    // Only the one audio thread of DesktopUiSounds uses this line.
    private var beepLine: SourceDataLine? = null

    @Volatile
    private var warned = false

    override fun play(samples: ShortArray) {
        val line = beepLine ?: openLine()?.also { opened -> beepLine = opened } ?: return
        val bytes = ToneSynth.toBytes(samples)
        line.write(bytes, 0, bytes.size)
        line.drain()
    }

    override fun openStream(): AudioStream? {
        val line = openLine() ?: return null
        return object : AudioStream {
            override fun write(samples: ShortArray, count: Int) {
                val bytes = ToneSynth.toBytes(samples, count)
                line.write(bytes, 0, bytes.size)
            }

            override fun close() {
                line.stop()
                line.flush()
                line.close()
            }
        }
    }

    private fun openLine(): SourceDataLine? = try {
        AudioSystem.getSourceDataLine(format).apply {
            open(format, ToneSynth.frames(LINE_BUFFER_MS) * BYTES_PER_FRAME)
            start()
        }
    } catch (error: LineUnavailableException) {
        warnOnce(error)
        null
    } catch (error: IllegalArgumentException) {
        // No line for the format: no audio device.
        warnOnce(error)
        null
    } catch (error: SecurityException) {
        warnOnce(error)
        null
    }

    private fun warnOnce(error: Exception) {
        if (warned) return
        warned = true
        log.w(error) { "No audio line. The app plays no sound." }
    }

    private companion object {
        const val BITS = 16
        const val CHANNELS = 1
        const val BYTES_PER_FRAME = 2
        const val LINE_BUFFER_MS = 50
        val log = Arbor.tag("JavaSoundOutput")
    }
}

/**
 * The desktop [UiSounds]. It only plays: the common code of `core:ui` applies the mute rules
 * before it calls this class.
 *
 * - Short, long, error, link up, and link lost: the samples of [DesktopToneSynth] on one audio
 *   thread (a mono buffer on a mono line).
 * - Proximity: an 800 Hz sine on a stream of its own thread ([ProximityTone]).
 * - A busy sound drops a new request of the same kind ([SoundGate] with [DesktopSoundRules.gate]).
 *
 * The calls return at once.
 *
 * @param nowMs The monotonic clock in milliseconds.
 * @param output The audio device.
 */
class DesktopUiSounds(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val output: AudioOutput = JavaSoundOutput(),
) : UiSounds {

    private val audio = Executors.newSingleThreadExecutor { task -> Thread(task, "superdro-sounds").apply { isDaemon = true } }
    private val gate = DesktopSoundRules.gate(nowMs)
    private val proximityPlayer = ProximityPlayer(nowMs, output)

    override fun play(sound: UiSound) {
        if (!gate.tryStart(sound)) return
        audio.execute {
            try {
                output.play(DesktopToneSynth.samplesOf(sound))
            } catch (error: RuntimeException) {
                log.w(error) { "The sound $sound did not play" }
            }
        }
    }

    override fun proximity(level: Double) {
        proximityPlayer.setLevel(level)
    }

    /** The level pattern of the proximity tone now, for the tests. */
    internal val proximityPattern: ProximityTone.Pattern get() = proximityPlayer.current

    private companion object {
        val log = Arbor.tag("UiSounds")
    }

    /**
     * Plays the proximity tone on a stream. The thread writes the tone of [ProximityTone.stream]
     * in chunks of 10 ms, so that a new level applies within one chunk. The tone stops for the
     * level 0 and [ProximityTone.SILENCE_AFTER_MS] after the last level.
     */
    private class ProximityPlayer(nowMs: () -> Long, private val output: AudioOutput) {

        private val level = ProximityLevel(nowMs)

        private var thread: Thread? = null

        val current: ProximityTone.Pattern get() = level.current

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
            val stream = output.openStream()
            if (stream == null) {
                synchronized(this) {
                    level.stop()
                    thread = null
                }
                return
            }
            try {
                ProximityTone.stream(level::active) { samples, count -> stream.write(samples, count) }
            } catch (error: RuntimeException) {
                log.w(error) { "The proximity tone stopped" }
            } finally {
                stream.close()
                onThreadEnd()
            }
        }
    }
}
