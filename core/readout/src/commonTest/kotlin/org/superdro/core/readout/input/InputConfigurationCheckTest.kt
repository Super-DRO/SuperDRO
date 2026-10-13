package org.superdro.core.readout.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MapPoint
import org.superdro.core.readout.Fixtures
import org.superdro.core.readout.encoder.EncoderProfileCheck
import org.superdro.core.readout.encoder.EncoderProfileProblem
import org.superdro.core.readout.encoder.EncoderStages
import org.superdro.core.readout.encoder.MapProblem

/** The check of each stored map at each load. */
class InputConfigurationCheckTest {

    private val goodMap = listOf(MapPoint(0, 0), MapPoint(10000, 4), MapPoint(20000, 9), MapPoint(30000, 6))
    private val reversedMap = listOf(MapPoint(0, 0), MapPoint(100, -150), MapPoint(10000, 4))

    @Test
    fun aValidMapStaysAndGivesNoProblem() {
        val configuration = InputConfiguration.from(Fixtures.mill(), Fixtures.adapter(Fixtures.linear('x').copy(errorMap = goodMap)))
        assertEquals(goodMap, configuration.encoders.getValue('x').errorMap)
        assertEquals(emptyMap(), configuration.problems)
        assertEquals(emptyList(), configuration.problemsOf('X'))
    }

    @Test
    fun anErrorMapThatFailsTheCheckGivesNoCorrection() {
        val configuration = InputConfiguration.from(Fixtures.mill(), Fixtures.adapter(Fixtures.linear('x').copy(errorMap = reversedMap)))
        assertEquals(emptyList(), configuration.encoders.getValue('x').errorMap)
        assertEquals(
            listOf<EncoderProfileProblem>(EncoderProfileProblem.ErrorMap(MapProblem.Reversal(0, 100))),
            configuration.problemsOf('x'),
        )
        assertEquals(
            "The corrected position goes back between positions 0 and 100.",
            configuration.problemsOf('x').single().message.english,
        )
        val stage = InputStage(configuration)
        stage.onAxisCount('x', 5000L, 0L)
        assertEquals(5000.0, stage.snapshot.axisValue(AxisId.X), "no correction")
    }

    @Test
    fun aBacklashMapThatFailsTheCheckGivesNoBacklash() {
        val leadscrew = EncoderProfile(
            input = 'z',
            type = EncoderType.LeadscrewEncoder,
            backlashMode = BacklashMode.Map,
            backlashMap = listOf(MapPoint(0, -5), MapPoint(1000, 30)),
        )
        val configuration = InputConfiguration.from(Fixtures.mill(), Fixtures.adapter(leadscrew))
        assertEquals(emptyList(), configuration.encoders.getValue('z').backlashMap)
        assertEquals(
            listOf<EncoderProfileProblem>(EncoderProfileProblem.BacklashMap(MapProblem.NegativeBacklash(0, -5))),
            configuration.problemsOf('z'),
        )
        val stage = InputStage(configuration)
        stage.onAxisCount('z', 0L, 0L)
        stage.onAxisCount('z', 100L, 10L)
        assertEquals(100.0, stage.snapshot.axisValue(AxisId.Z), "no backlash")
    }

    @Test
    fun aBacklashMapOnAScaleIsAProblemAndTheScaleUsesTheConstant() {
        val scale = Fixtures.linear('x').copy(backlashMode = BacklashMode.Map, backlashCounts = 3L, backlashMap = listOf(MapPoint(0, 5)))
        val configuration = InputConfiguration.from(Fixtures.mill(), Fixtures.adapter(scale))
        assertTrue(EncoderProfileProblem.BacklashMapNotAllowed in configuration.problemsOf('x'))
        val stage = InputStage(configuration)
        stage.onAxisCount('x', 100L, 0L)
        stage.onAxisCount('x', 110L, 10L)
        assertEquals(113.0, stage.snapshot.axisValue(AxisId.X))
    }

    @Test
    fun theStagesAlsoIgnoreAMapThatFailsTheCheck() {
        val profile = EncoderProfile(errorMap = reversedMap)
        assertTrue(EncoderStages(profile).errorCorrection.isIdentity)
        assertTrue(!EncoderStages(EncoderProfile(errorMap = goodMap)).errorCorrection.isIdentity)
        val checked = EncoderProfileCheck.checked(profile)
        assertTrue(checked.errorMapRemoved)
        assertTrue(!checked.backlashMapRemoved)
        assertEquals(EncoderProfileCheck.problems(profile), checked.problems)
    }
}
