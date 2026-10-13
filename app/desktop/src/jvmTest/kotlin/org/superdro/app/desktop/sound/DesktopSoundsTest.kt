package org.superdro.app.desktop.sound

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.util.sound.ProximityTone
import org.superdro.core.util.sound.ToneSynth

/**
 * The UI sounds of the desktop app. The tone data, the busy rule,
 * and the proximity tone are common code of `core:util`, which the Android app also uses.
 */
class DesktopSoundsTest {

    private fun crossings(samples: ShortArray, from: Int = 0, to: Int = samples.size): Int =
        (from + 1 until to).count { index -> (samples[index - 1] < 0) != (samples[index] < 0) }

    @Test
    fun theShortBeepIs440HzFor100MsWithA5PercentRampAt48Khz() {
        val short = DesktopToneSynth.samplesOf(UiSound.Short)
        assertEquals(48_000, ToneSynth.SAMPLE_RATE)
        assertEquals(4_800, short.size)
        // 440 Hz with 2π: 2.75 periods at the frame 300, so the sine is -1 there.
        assertEquals(-32_767, short[300].toInt())
        assertEquals(0, short[600].toInt())
        assertTrue(abs(crossings(short) - 88) <= 1, "crossings: ${crossings(short)}")
        assertEquals(0, short.first().toInt())
        assertEquals(0, short.last().toInt())
    }

    @Test
    fun theEnvelopeRisesAndFallsLinearlyIn5PercentOfTheFrames() {
        assertEquals(0.0, ToneSynth.envelope(0, 4_800))
        assertEquals(0.5, ToneSynth.envelope(120, 4_800))
        assertEquals(1.0, ToneSynth.envelope(240, 4_800))
        assertEquals(1.0, ToneSynth.envelope(4_559, 4_800))
        assertEquals(239.0 / 240.0, ToneSynth.envelope(4_560, 4_800))
        assertEquals(0.0, ToneSynth.envelope(4_799, 4_800))
    }

    @Test
    fun theLongBeepAndTheErrorSoundHaveTheirLengthsAndPitches() {
        val long = DesktopToneSynth.samplesOf(UiSound.Long)
        assertEquals(24_000, long.size)
        assertEquals(-32_767, long[1_500].toInt())
        assertTrue(abs(crossings(long) - 440) <= 1, "crossings: ${crossings(long)}")

        val error = DesktopToneSynth.samplesOf(UiSound.Error)
        assertEquals(4_800 + 2_400 + 4_800, error.size)
        // 220 Hz: 2.75 periods at the frame 600 of each beep.
        assertEquals(-32_767, error[600].toInt())
        assertEquals(-32_767, error[7_200 + 600].toInt())
        assertTrue((4_800 until 7_200).all { index -> error[index].toInt() == 0 })
    }

    @Test
    fun theLinkUpToneIs587HzFor375MsThen1175HzFor125Ms() {
        val up = DesktopToneSynth.samplesOf(UiSound.LinkUp)
        assertEquals(18_000 + 6_000, up.size)
        // 587 Hz for 0.375 s: 220.1 periods, 440 zero crossings.
        assertTrue(abs(crossings(up, 0, 18_000) - 440) <= 2, "crossings: ${crossings(up, 0, 18_000)}")
        // 1175 Hz for 0.125 s: 146.9 periods, 294 zero crossings.
        assertTrue(abs(crossings(up, 18_000, 24_000) - 294) <= 2, "crossings: ${crossings(up, 18_000, 24_000)}")
    }

    @Test
    fun theLinkLostToneIsFourBurstsOf480HzWith100MsOnAnd100MsOff() {
        val lost = DesktopToneSynth.samplesOf(UiSound.LinkLost)
        assertEquals(4 * (4_800 + 4_800), lost.size)
        for (burst in 0 until 4) {
            val start = burst * 9_600
            // 480 Hz for 0.1 s: 48 periods, 96 zero crossings, then 0.1 s of silence.
            assertTrue(abs(crossings(lost, start, start + 4_800) - 96) <= 1, "burst $burst")
            assertTrue((start + 4_800 until start + 9_600).all { index -> lost[index].toInt() == 0 }, "gap $burst")
        }
        assertTrue(DesktopSoundRules.busyMs(UiSound.LinkLost) <= 1_000L)
    }

    @Test
    fun theLineBytesAreSigned16BitLittleEndian() {
        val bytes = ToneSynth.toBytes(shortArrayOf(0x1234, -2, 32_767))
        assertEquals(listOf<Byte>(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F), bytes.toList())
        assertEquals(2, ToneSynth.toBytes(shortArrayOf(1, 2, 3), count = 1).size)
    }

    @Test
    fun aBusySoundDropsANewRequestOfTheSameKind() {
        var now = 0L
        val gate = DesktopSoundRules.gate { now }
        assertTrue(gate.tryStart(UiSound.Short))
        assertFalse(gate.tryStart(UiSound.Long))
        // A link tone has its own player.
        assertTrue(gate.tryStart(UiSound.LinkUp))
        now = 99L
        assertFalse(gate.tryStart(UiSound.Short))
        now = 100L
        assertTrue(gate.tryStart(UiSound.Error))
        now = 349L
        assertFalse(gate.tryStart(UiSound.Short))
        assertFalse(gate.tryStart(UiSound.LinkLost))
        now = 500L
        assertTrue(gate.tryStart(UiSound.LinkLost))
    }

    @Test
    fun theProximityLevelGivesThePattern() {
        assertEquals(ProximityTone.Pattern.Stop, ProximityTone.patternOf(0.0))
        assertEquals(ProximityTone.Pattern.Stop, ProximityTone.patternOf(Double.NaN))
        assertEquals(ProximityTone.Pattern.Continuous, ProximityTone.patternOf(1.0))
        // Level 0.65 gives the period 0.1 s / 0.65.
        val example = ProximityTone.patternOf(0.65) as ProximityTone.Pattern.Repeat
        assertEquals(100.0 / 0.65, example.periodMs, 1e-9)
        assertEquals(7_385, ProximityTone.periodFrames(example.periodMs))
        assertEquals(ProximityTone.Pattern.Repeat(2_000.0), ProximityTone.patternOf(0.01))
        assertEquals(ProximityTone.Pattern.Repeat(100.0 / 0.95), ProximityTone.patternOf(0.95))
    }

    @Test
    fun theSoundsPlayOnTheAudioThreadAndReturnAtOnce() {
        val played: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val done = CountDownLatch(2)
        val output = object : AudioOutput {
            override fun play(samples: ShortArray) {
                played += samples.size
                done.countDown()
            }

            override fun openStream(): AudioStream? = null
        }
        var now = 0L
        val sounds = DesktopUiSounds(nowMs = { now }, output = output)
        sounds.play(UiSound.Short)
        sounds.play(UiSound.Short)
        now = 1_000L
        sounds.play(UiSound.LinkUp)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(4_800, 24_000), played.toList())

        // No audio device: the proximity tone stops with no error.
        sounds.proximity(0.65)
        sounds.proximity(0.0)
        assertEquals(ProximityTone.Pattern.Stop, sounds.proximityPattern)
    }
}
