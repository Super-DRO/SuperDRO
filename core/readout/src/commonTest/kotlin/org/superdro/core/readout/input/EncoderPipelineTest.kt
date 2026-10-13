package org.superdro.core.readout.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MapPoint
import org.superdro.core.readout.Fixtures

/** The encoder profile pipeline. */
class EncoderPipelineTest {

    private val runTimeProfile = EncoderProfile(
        input = 'x',
        type = EncoderType.Scale,
        kind = EncoderKind.Linear,
        resolution = 25400.0,
        backlashMode = BacklashMode.Constant,
        backlashCounts = 3L,
        errorMap = listOf(MapPoint(0, 0), MapPoint(10000, 4), MapPoint(20000, 9), MapPoint(30000, 6)),
    )

    private fun channel(profile: EncoderProfile?, input: Char = 'x'): EncoderChannel = EncoderChannel(input, profile, ChannelHistory())

    @Test
    fun theRunTimeTableOfErrorCompensation152() {
        val channel = channel(runTimeProfile)
        // Before sample 1 the LAG state is "up", the last extreme is 14996, and the previous raw count is 14990.
        channel.process(14980L, 0L)
        channel.process(14990L, 10L)
        assertEquals(14996L + 3L, channel.correctedCount)
        val values = listOf(15000L, 14998L, 14994L, 35000L, -500L).mapIndexed { index, raw ->
            channel.process(raw, 20L + index)
            channel.value
        }
        assertEquals(listOf(15009.0, 15007.0, 15000.0, 35009.0, -500.0), values)
    }

    @Test
    fun theLeadscrewTableThroughThePipeline() {
        val profile = EncoderProfile(input = 'z', type = EncoderType.LeadscrewEncoder, backlashMode = BacklashMode.Constant, backlashCounts = 30L)
        val channel = channel(profile, 'z')
        val out = listOf(1000L, 1100L, 1050L, 1060L, 1100L).mapIndexed { index, raw ->
            channel.process(raw, index * 10L)
            channel.correctedCount
        }
        assertEquals(listOf(1000L, 1070L, 1050L, 1050L, 1070L), out)
    }

    @Test
    fun theDirectionSignComesAfterTheMapAndTheBacklash() {
        val channel = channel(runTimeProfile.copy(reversed = true, resolution = 5080.0))
        channel.process(10000L, 0L)
        // The first count has d = 0: no backlash. The map adds 4. The scale is -5 µm.
        assertEquals(10004L, channel.correctedCount)
        assertEquals(-50020.0, channel.value)
    }

    @Test
    fun noProfileABadResolutionOrADisabledInputGivesNoValue() {
        val none = channel(null)
        none.process(100L, 0L)
        assertNull(none.value)
        assertEquals(ChannelStatus.NoProfile, none.status)
        assertEquals(100L, none.rawCount)

        val bad = channel(EncoderProfile(resolution = 0.0))
        bad.process(100L, 0L)
        assertNull(bad.value)
        assertEquals(ChannelStatus.InvalidResolution, bad.status)

        val disabled = channel(EncoderProfile(kind = EncoderKind.Disabled))
        disabled.process(100L, 0L)
        assertNull(disabled.value, "a disabled input gives no value, not 0")
        assertEquals(ChannelStatus.Disabled, disabled.status)
    }

    @Test
    fun eachConnectionStartsAllStagesAgainWithTheFirstDeltaZero() {
        val channel = channel(EncoderProfile(backlashMode = BacklashMode.Constant, backlashCounts = 3L))
        channel.process(100L, 0L)
        channel.process(110L, 10L)
        assertEquals(113.0, channel.value)
        channel.restart()
        assertEquals(113.0, channel.value, "the last value stays until the next sample")
        channel.process(120L, 5L)
        assertEquals(120.0, channel.value, "the first count after the connection gets no backlash")
    }

    @Test
    fun aSampleWithAnEarlierTimeIsDropped() {
        val channel = channel(EncoderProfile())
        assertTrue(channel.process(100L, 50L))
        assertFalse(channel.process(200L, 40L))
        assertEquals(100.0, channel.value)
        assertTrue(channel.process(300L, 50L), "the same time is not earlier")
        assertEquals(300.0, channel.value)
    }

    @Test
    fun theHistoryKeepsTheCorrectedCountAlsoWhenTheCountDoesNotChange() {
        val history = ChannelHistory()
        val channel = EncoderChannel('x', runTimeProfile, history)
        channel.process(5000L, 0L)
        channel.process(5000L, 100L)
        assertEquals(2, history.size)
        assertEquals(5002L, history.countAt(1))
        assertEquals(100L, history.timeAt(1))
    }

    @Test
    fun aHeldSpikeGoesThroughTheStagesAtTheNextSampleAlsoWithNoCountChange() {
        val channel = channel(EncoderProfile(resolution = 5080.0, filter = true))
        channel.process(1000L, 0L)
        channel.process(5000L, 10L)
        assertEquals(1000L, channel.correctedCount, "the spike is held")
        channel.process(5000L, 20L)
        assertEquals(5000L, channel.correctedCount, "the same count confirms the jump")
    }

    @Test
    fun aConfigurationChangeStartsANewFilterStateOnlyForTheChangedInput() {
        val profileX = Fixtures.linear('x').copy(backlashMode = BacklashMode.Constant, backlashCounts = 3L)
        val profileY = Fixtures.linear('y').copy(backlashMode = BacklashMode.Constant, backlashCounts = 3L)
        val stage = InputStage(InputConfiguration(mapOf('x' to profileX, 'y' to profileY), InputConfiguration.axisTerms(Fixtures.mill())))
        stage.onAxisCount('x', 100L, 0L)
        stage.onAxisCount('x', 110L, 10L)
        stage.onAxisCount('y', 100L, 0L)
        stage.onAxisCount('y', 110L, 10L)
        assertEquals(113.0, stage.snapshot.axisValue(AxisId.X))
        assertEquals(113.0, stage.snapshot.axisValue(AxisId.Y))

        val changedY = profileY.copy(resolution = 12700.0)
        stage.reconfigure(stage.configuration.copy(encoders = mapOf('x' to profileX, 'y' to changedY)))
        assertEquals(220.0, stage.snapshot.axisValue(AxisId.Y), "the change takes effect at once, with a new state (no backlash)")
        assertEquals(113.0, stage.snapshot.axisValue(AxisId.X))
        stage.onAxisCount('x', 111L, 20L)
        assertEquals(114.0, stage.snapshot.axisValue(AxisId.X), "X keeps its LAG state 'up'")
    }

    @Test
    fun aChangeOfADifferentFieldKeepsTheFilterState() {
        val profile = Fixtures.linear('x').copy(backlashMode = BacklashMode.Constant, backlashCounts = 3L)
        val channel = channel(profile)
        channel.process(100L, 0L)
        channel.process(110L, 10L)
        assertFalse(channel.reconfigure(profile.copy(label = "Knee")))
        channel.process(111L, 20L)
        assertEquals(114.0, channel.value)
    }
}
