package org.superdro.core.util.sound

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tone data of the UI sounds of the two apps. */
class ToneSynthTest {

    private fun crossings(samples: ShortArray): Int =
        (1 until samples.size).count { index -> (samples[index - 1] < 0) != (samples[index] < 0) }

    @Test
    fun theShortBeepIs440HzFor100MsWithA5PercentRampAt48Khz() {
        val short = ToneSynth.beep(Beep.Short)
        assertEquals(48_000, ToneSynth.SAMPLE_RATE)
        assertEquals(4800, short.size)
        // 440 Hz with 2π: 2.75 periods at the frame 300, so the sine is -1 there.
        assertEquals(-32767, short[300].toInt())
        assertEquals(0, short[600].toInt())
        // 440 Hz for 0.1 s: 88 zero crossings.
        assertTrue(abs(crossings(short) - 88) <= 1, "crossings: ${crossings(short)}")
        assertTrue(short.all { sample -> abs(sample.toInt()) <= 32767 })
    }

    @Test
    fun theEnvelopeRisesAndFallsLinearlyIn5PercentOfTheFrames() {
        assertEquals(0.0, ToneSynth.envelope(0, 4800))
        assertEquals(0.5, ToneSynth.envelope(120, 4800))
        assertEquals(1.0, ToneSynth.envelope(240, 4800))
        assertEquals(1.0, ToneSynth.envelope(4559, 4800))
        assertEquals(239.0 / 240.0, ToneSynth.envelope(4560, 4800))
        assertEquals(0.0, ToneSynth.envelope(4799, 4800))
        val short = ToneSynth.beep(Beep.Short)
        assertEquals(0, short.first().toInt())
        assertEquals(0, short.last().toInt())
    }

    @Test
    fun theLongBeepIs440HzFor500Ms() {
        val long = ToneSynth.beep(Beep.Long)
        assertEquals(24_000, long.size)
        // The ramp of the long beep is 1200 frames. The frame 1500 is 13.75 periods: -1.
        assertEquals(-32767, long[1500].toInt())
        assertTrue(abs(crossings(long) - 440) <= 1, "crossings: ${crossings(long)}")
    }

    @Test
    fun theErrorSoundIsTwo220HzBeepsOf100MsWithA50MsGap() {
        val error = ToneSynth.beep(Beep.Error)
        assertEquals(4800 + 2400 + 4800, error.size)
        // 220 Hz: 2.75 periods at the frame 600 of each beep.
        assertEquals(-32767, error[600].toInt())
        assertEquals(-32767, error[7200 + 600].toInt())
        assertTrue((4800 until 7200).all { index -> error[index].toInt() == 0 })
        assertTrue(abs(crossings(error.copyOfRange(0, 4800)) - 44) <= 1)
    }

    @Test
    fun aStereoBufferHasTwoEqualSamplesForEachFrameAndTheSameLength() {
        val mono = ToneSynth.tone(ToneSynth.BEEP_HZ, ToneSynth.SHORT_MS, channels = 1)
        val stereo = ToneSynth.tone(ToneSynth.BEEP_HZ, ToneSynth.SHORT_MS, channels = 2)
        assertEquals(mono.size * 2, stereo.size)
        for (frame in mono.indices) {
            assertEquals(mono[frame], stereo[2 * frame])
            assertEquals(mono[frame], stereo[2 * frame + 1])
        }
        assertEquals(ToneSynth.frames(100), stereo.size / 2)
    }

    @Test
    fun eachBeepKeepsItsPlayerBusyForItsLength() {
        assertEquals(100L, ToneSynth.durationMs(Beep.Short))
        assertEquals(500L, ToneSynth.durationMs(Beep.Long))
        assertEquals(250L, ToneSynth.durationMs(Beep.Error))
        Beep.entries.forEach { beep -> assertEquals(ToneSynth.frames(ToneSynth.durationMs(beep).toInt()), ToneSynth.beep(beep).size) }
    }

    @Test
    fun theOutputBytesAreSigned16BitLittleEndian() {
        val bytes = ToneSynth.toBytes(shortArrayOf(0x1234, -2, 32_767))
        assertEquals(listOf<Byte>(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F), bytes.toList())
        assertEquals(2, ToneSynth.toBytes(shortArrayOf(1, 2, 3), count = 1).size)
    }
}
