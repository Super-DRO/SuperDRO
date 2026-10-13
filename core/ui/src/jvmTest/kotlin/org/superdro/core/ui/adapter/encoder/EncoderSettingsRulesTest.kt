package org.superdro.core.ui.adapter.encoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.Calibration
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MapPoint

/** The rules of the encoder settings screen. */
class EncoderSettingsRulesTest {

    private val scale = EncoderProfile.defaultFor('x')
    private val leadscrew = scale.copy(type = EncoderType.LeadscrewEncoder, countsPerScrewRev = 2000.0)

    private fun ok(base: EncoderProfile, form: EncoderForm): EncoderBuild.Ok {
        val build = EncoderSettingsRules.build(base, form)
        assertIs<EncoderBuild.Ok>(build)
        return build
    }

    @Test
    fun theRowsFollowTheFlagsOfTheKindAndTheType() {
        val lag = EncoderSettingsRules.visibility(EncoderSettingsRules.formOf(scale))
        assertTrue(lag.resolution && lag.calibrate && lag.hysteresis)
        assertFalse(lag.backlashMode || lag.backlashConstant || lag.backlashMap || lag.countsPerScrewRev || lag.cyclic)

        val screw = EncoderSettingsRules.visibility(EncoderSettingsRules.formOf(leadscrew).copy(backlashMode = BacklashMode.Constant))
        assertFalse(screw.hysteresis)
        assertTrue(screw.backlashMode && screw.backlashConstant && screw.countsPerScrewRev)
        assertFalse(screw.backlashMap)
        assertTrue(EncoderSettingsRules.visibility(EncoderSettingsRules.formOf(leadscrew).copy(backlashMode = BacklashMode.Map)).backlashMap)

        val angularScrew = EncoderSettingsRules.visibility(EncoderSettingsRules.formOf(leadscrew).copy(kind = EncoderKind.Angular))
        assertFalse(angularScrew.countsPerScrewRev, "H needs the kind Linear")
        assertTrue(angularScrew.cyclic, "The cyclic switch shows for an angular input.")

        val disabled = EncoderSettingsRules.visibility(EncoderSettingsRules.formOf(scale).copy(kind = EncoderKind.Disabled))
        assertFalse(disabled.resolution || disabled.calibrate || disabled.hysteresis || disabled.backlashMode)

        assertFalse(EncoderSettingsRules.visibility(EncoderSettingsRules.formOf(EncoderProfile.defaultFor('t')), 't').calibrate, "The input t has no calibration.")
    }

    @Test
    fun theFormOfADefaultProfileHasTheDefaults() {
        val form = EncoderSettingsRules.formOf(scale)
        assertEquals(EncoderType.Scale, form.type)
        assertEquals(EncoderKind.Linear, form.kind)
        assertEquals("25400", form.resolution)
        assertEquals("", form.hysteresis)
        assertTrue(form.cyclic, "The cyclic switch is on by default.")
        assertEquals("1", EncoderSettingsRules.formOf(EncoderProfile.defaultFor('t')).resolution)
    }

    @Test
    fun aLagHysteresisGivesTheModeConstantAndABlankFieldGivesNone() {
        val base = scale
        val constant = ok(base, EncoderSettingsRules.formOf(base).copy(hysteresis = "3")).profile
        assertEquals(BacklashMode.Constant, constant.backlashMode)
        assertEquals(3L, constant.backlashCounts)
        val none = ok(constant, EncoderSettingsRules.formOf(constant).copy(hysteresis = " ")).profile
        assertEquals(BacklashMode.None, none.backlashMode)
        assertEquals(0L, none.backlashCounts)
        val error = EncoderSettingsRules.build(base, EncoderSettingsRules.formOf(base).copy(hysteresis = "2.5"))
        assertIs<EncoderBuild.Invalid>(error)
        assertEquals(EncoderFieldError.HysteresisNotWhole, error.errors.hysteresis)
    }

    @Test
    fun theResolutionMustBeMoreThanZero() {
        listOf("0", "-1", "", "abc").forEach { text ->
            val build = EncoderSettingsRules.build(scale, EncoderSettingsRules.formOf(scale).copy(resolution = text))
            assertIs<EncoderBuild.Invalid>(build, text)
            assertEquals(EncoderFieldError.ResolutionNotPositive, build.errors.resolution)
        }
        val disabled = ok(scale, EncoderSettingsRules.formOf(scale).copy(kind = EncoderKind.Disabled, resolution = "")).profile
        assertEquals(25400.0, disabled.resolution, "A disabled input keeps its stored resolution.")
    }

    @Test
    fun aResolutionChangeOfAProfileThatIsNotCalibratedGivesUserSet() {
        val changed = ok(scale, EncoderSettingsRules.formOf(scale).copy(resolution = "5080")).profile
        assertEquals(5080.0, changed.resolution)
        assertEquals(CalibrationStatus.UserSet, changed.calibration.status)

        val calibrated = scale.copy(calibration = Calibration(status = CalibrationStatus.Calibrated))
        assertEquals(CalibrationStatus.Calibrated, ok(calibrated, EncoderSettingsRules.formOf(calibrated).copy(resolution = "5080")).profile.calibration.status)
        assertEquals(CalibrationStatus.NotCalibrated, ok(scale, EncoderSettingsRules.formOf(scale).copy(filter = true)).profile.calibration.status)
    }

    @Test
    fun theDeadZoneConstantMustBeLessThanTheCountsOfOneScrewTurn() {
        val form = EncoderSettingsRules.formOf(leadscrew).copy(backlashMode = BacklashMode.Constant, backlashConstant = "2000")
        val tooLarge = EncoderSettingsRules.build(leadscrew, form)
        assertIs<EncoderBuild.Invalid>(tooLarge)
        assertEquals(EncoderFieldError.BacklashExceedsPitch, tooLarge.errors.backlashConstant)
        val good = ok(leadscrew, form.copy(backlashConstant = "30")).profile
        assertEquals(BacklashMode.Constant, good.backlashMode)
        assertEquals(30L, good.backlashCounts)
        assertEquals(2000.0, good.countsPerScrewRev)

        val noScrew = EncoderSettingsRules.build(leadscrew, form.copy(backlashConstant = "30", countsPerScrewRev = ""))
        assertIs<EncoderBuild.Invalid>(noScrew)
        assertEquals(EncoderFieldError.ScrewRevRequired, noScrew.errors.countsPerScrewRev)
        val zero = EncoderSettingsRules.build(leadscrew, form.copy(backlashConstant = "0"))
        assertIs<EncoderBuild.Invalid>(zero)
        assertEquals(EncoderFieldError.BacklashNotPositive, zero.errors.backlashConstant)
    }

    @Test
    fun theCountsOfOneScrewTurnStayOnlyForALinearLeadscrewEncoder() {
        assertEquals(2000.0, ok(leadscrew, EncoderSettingsRules.formOf(leadscrew)).profile.countsPerScrewRev)
        assertNull(ok(leadscrew, EncoderSettingsRules.formOf(leadscrew).copy(type = EncoderType.Scale)).profile.countsPerScrewRev)
        assertNull(ok(leadscrew, EncoderSettingsRules.formOf(leadscrew).copy(kind = EncoderKind.Angular)).profile.countsPerScrewRev)
    }

    @Test
    fun aModeThatIsNotMapRemovesTheBacklashMap() {
        val withMap = leadscrew.copy(backlashMode = BacklashMode.Map, backlashMap = listOf(MapPoint(0, 20), MapPoint(1000, 25)))
        val keep = ok(withMap, EncoderSettingsRules.formOf(withMap))
        assertFalse(keep.deletesBacklashMap)
        assertEquals(2, keep.profile.backlashMap.size)
        val none = ok(withMap, EncoderSettingsRules.formOf(withMap).copy(backlashMode = BacklashMode.None))
        assertTrue(none.deletesBacklashMap)
        assertTrue(none.profile.backlashMap.isEmpty())
        // A scale never gets the mode Map.
        val scaleForm = EncoderSettingsRules.formOf(withMap).copy(type = EncoderType.Scale)
        val scaleProfile = ok(withMap, scaleForm)
        assertEquals(BacklashMode.None, scaleProfile.profile.backlashMode)
        assertTrue(scaleProfile.deletesBacklashMap)
    }

    @Test
    fun theCyclicSwitchGoesToTheProfile() {
        val angular = EncoderProfile.defaultFor('w', EncoderKind.Angular)
        assertTrue(angular.errorMapCyclic)
        assertFalse(ok(angular, EncoderSettingsRules.formOf(angular).copy(cyclic = false)).profile.errorMapCyclic)
    }

    @Test
    fun theNumberTextsHaveNoZerosAtTheEnd() {
        assertEquals("25400", EncoderFormat.number(25400.0))
        assertEquals("5333.865", EncoderFormat.number(5333.865))
        assertEquals("5080.5", EncoderFormat.number(5080.5))
        assertEquals("-2.5", EncoderFormat.number(-2.5, 1))
        assertEquals("0", EncoderFormat.number(-0.01, 1))
        assertEquals("1.3", EncoderFormat.fixed(1.26, 1))
        assertEquals("3.0", EncoderFormat.fixed(3.0, 1))
    }
}
