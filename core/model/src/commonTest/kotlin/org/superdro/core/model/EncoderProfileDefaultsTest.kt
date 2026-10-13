package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The defaults of a new encoder profile. */
class EncoderProfileDefaultsTest {

    @Test
    fun aNewLinearInputIsAScaleWith25400CountsPerInch() {
        val profile = EncoderProfile.defaultFor('X')
        assertEquals('x', profile.input)
        assertEquals(EncoderType.Scale, profile.type)
        assertEquals(EncoderKind.Linear, profile.kind)
        assertEquals(25400.0, profile.resolution)
        assertEquals(BacklashMode.None, profile.backlashMode)
        assertEquals(0L, profile.backlashCounts)
        assertEquals(false, profile.filter)
        assertEquals(false, profile.reversed)
        assertEquals(CalibrationStatus.NotCalibrated, profile.calibration.status)
    }

    @Test
    fun theTachometerInputIsARotaryEncoderWithOneCountForEachRevolution() {
        val profile = EncoderProfile.defaultFor('t')
        assertEquals(EncoderType.RotaryEncoder, profile.type)
        assertEquals(EncoderKind.Angular, profile.kind)
        assertEquals(1.0, profile.resolution)
    }

    @Test
    fun anErrorMapIsCyclicByDefault() {
        assertTrue(EncoderProfile().errorMapCyclic)
    }

    @Test
    fun aNewAdapterProfileHasAnEncoderProfileForEachOfTheSevenInputs() {
        assertEquals(listOf('x', 'y', 'z', 'w', 'u', 'v', 't'), AdapterProfile().encoders.keys.toList())
    }

    @Test
    fun onlyTheSevenInputsHaveAProfile() {
        assertFailsWith<IllegalArgumentException> { EncoderProfile.defaultFor('a') }
        assertFailsWith<IllegalArgumentException> { EncoderProfile.defaultFor('p') }
    }
}
