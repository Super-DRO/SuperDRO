package org.superdro.core.util.sound

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The proximity tone and the busy rule of the two apps. */
class ProximityToneTest {

    @Test
    fun theProximityPeriodIsTheBeepDividedByTheLevelFrom100MsTo2S() {
        assertEquals(800.0, ProximityTone.FREQUENCY_HZ)
        assertEquals(ProximityTone.Pattern.Stop, ProximityTone.patternOf(0.0))
        assertEquals(ProximityTone.Pattern.Stop, ProximityTone.patternOf(-0.2))
        assertEquals(ProximityTone.Pattern.Stop, ProximityTone.patternOf(Double.NaN))
        assertEquals(ProximityTone.Pattern.Continuous, ProximityTone.patternOf(1.0))
        assertEquals(ProximityTone.Pattern.Continuous, ProximityTone.patternOf(1.5))
        assertEquals(ProximityTone.Pattern.Repeat(2000.0), ProximityTone.patternOf(0.05))
        assertEquals(ProximityTone.Pattern.Repeat(2000.0), ProximityTone.patternOf(0.01))
        val near = ProximityTone.patternOf(0.95) as ProximityTone.Pattern.Repeat
        assertEquals(100.0 / 0.95, near.periodMs, 1e-9)
        assertEquals(5000L, ProximityTone.SILENCE_AFTER_MS)
    }

    @Test
    fun theWorkedExampleOf1000UmGivesTheLevel065AndAPeriodOf154Ms() {
        // D = 1000 µm and the axis moves closer.
        val level = 0.95 - floor((1000.0 - 127.0) / 2540.0 * 20.0) / 20.0
        assertEquals(0.65, level, 1e-12)
        val pattern = ProximityTone.patternOf(level) as ProximityTone.Pattern.Repeat
        assertEquals(153.846, pattern.periodMs, 0.001)
        assertEquals(7385, ProximityTone.periodFrames(pattern.periodMs))
        assertEquals(4800, ProximityTone.periodFrames(100.0))
        assertEquals(96_000, ProximityTone.periodFrames(2000.0))
    }

    @Test
    fun aRepeatWritesTheBeepAndThenTheSilenceOfThePeriod() {
        val written = ArrayList<Pair<Int, Boolean>>()
        // Two periods of 0.65, then the stop.
        val pattern = ProximityTone.patternOf(0.65)
        ProximityTone.stream(active = { if (written.count { it.second } >= 2) null else pattern }) { samples, count ->
            written += count to samples.take(count).any { sample -> sample != 0.toShort() }
        }
        val beeps = written.filter { it.second }
        assertEquals(2, beeps.size)
        assertTrue(beeps.all { (count, _) -> count == 4800 }, "a beep of 0.1 s")
        // The silence of one period: 7385 − 4800 frames in chunks of 480 frames (10 ms).
        val firstSilence = written.drop(1).takeWhile { !it.second }.sumOf { it.first }
        assertEquals(7385 - 4800, firstSilence)
        assertTrue(written.filterNot { it.second }.all { (count, _) -> count <= 480 })
    }

    @Test
    fun theContinuousToneHasNoBreakInThePhaseFromChunkToChunk() {
        val chunks = ArrayList<ShortArray>()
        ProximityTone.stream(active = { if (chunks.size >= 3) null else ProximityTone.Pattern.Continuous }) { samples, count ->
            chunks += samples.copyOf(count)
        }
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { chunk -> chunk.size == 480 })
        // 800 Hz at 48 kHz: 60 frames for each period, so 480 frames are 8 periods. Each chunk starts at the phase 0.
        assertTrue(chunks[0].indices.all { index -> kotlin.math.abs(chunks[0][index] - chunks[1][index]) <= 1 })
        assertEquals(0, chunks[0][0].toInt())
        assertEquals(32_767, chunks[0][15].toInt(), "a quarter period: the sine is 1")
    }

    @Test
    fun theLevelStopsAtZeroAndFiveSecondsAfterTheLastLevel() {
        var now = 0L
        val level = ProximityLevel { now }
        assertNull(level.active())
        level.set(0.65)
        assertEquals(ProximityTone.patternOf(0.65), level.active())
        now = 5_000L
        assertEquals(ProximityTone.patternOf(0.65), level.active())
        now = 5_001L
        assertNull(level.active(), "no new level for more than 5 s")
        level.set(1.0)
        assertEquals(ProximityTone.Pattern.Continuous, level.current)
        level.set(0.0)
        assertNull(level.active())
        level.set(0.5)
        level.stop()
        assertNull(level.active())
    }

    @Test
    fun aBusySoundDropsANewRequestOfTheSamePlayer() {
        var now = 0L
        // Two players: "beep" for 100 ms and "tone" for 1000 ms.
        val gate = SoundGate<String, Char>({ now }, { sound -> sound.first() }, { sound -> if (sound.startsWith("b")) 100L else 1_000L })
        assertTrue(gate.tryStart("beep"))
        now = 99L
        assertEquals(false, gate.tryStart("beep"))
        assertTrue(gate.tryStart("tone"), "a different player")
        now = 100L
        assertTrue(gate.tryStart("beep"))
        now = 1_098L
        assertEquals(false, gate.tryStart("tone"))
        now = 1_099L
        assertTrue(gate.tryStart("tone"))
    }
}
