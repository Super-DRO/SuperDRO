package org.superdro.core.functions.tipcal

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.ProbeProfile

/** The spread limit rule. */
class SpreadLimitTest {

    @Test
    fun theProfileValueComesFirst() {
        assertEquals(SpreadLimit(20.0, SpreadLimitSource.Profile), SpreadLimits.of(ProbeProfile()))
        assertEquals(
            SpreadLimit(15.0, SpreadLimitSource.Profile),
            SpreadLimits.of(ProbeProfile(spreadLimitUm = 15.0, calibrationResidualUm = 8.0)),
        )
    }

    @Test
    fun withNoProfileValueThreeTimesTheResidualWithTheTenMicrometerFloorApplies() {
        assertEquals(
            SpreadLimit(10.0, SpreadLimitSource.Calibration),
            SpreadLimits.of(ProbeProfile(spreadLimitUm = null, calibrationResidualUm = 2.0)),
        )
        assertEquals(
            SpreadLimit(15.0, SpreadLimitSource.Calibration),
            SpreadLimits.of(ProbeProfile(spreadLimitUm = null, calibrationResidualUm = 5.0)),
        )
        // A profile value of 0 or a value that is not finite does not count.
        assertEquals(SpreadLimitSource.Calibration, SpreadLimits.of(ProbeProfile(spreadLimitUm = 0.0, calibrationResidualUm = 5.0)).source)
        assertEquals(
            SpreadLimitSource.Calibration,
            SpreadLimits.of(ProbeProfile(spreadLimitUm = Double.NaN, calibrationResidualUm = 5.0)).source,
        )
    }

    @Test
    fun elseTheLimitIsTwentyMicrometersAlsoWithNoProfile() {
        assertEquals(SpreadLimit(20.0, SpreadLimitSource.Default), SpreadLimits.of(null))
        assertEquals(SpreadLimit(20.0, SpreadLimitSource.Default), SpreadLimits.of(ProbeProfile(spreadLimitUm = null)))
        assertEquals(
            SpreadLimit(20.0, SpreadLimitSource.Default),
            SpreadLimits.of(ProbeProfile(spreadLimitUm = null, calibrationResidualUm = Double.POSITIVE_INFINITY)),
        )
        assertEquals(
            SpreadLimit(20.0, SpreadLimitSource.Default),
            SpreadLimits.of(ProbeProfile(spreadLimitUm = -3.0, calibrationResidualUm = 0.0)),
        )
    }

    @Test
    fun theTechniqueHasAtLeastOneSample() {
        assertEquals(1, SpreadLimits.techniqueOf(null).sampleCount)
        assertEquals(1, SpreadLimits.techniqueOf(ProbeProfile(sampleCount = 0)).sampleCount)
        assertEquals(4, SpreadLimits.techniqueOf(ProbeProfile(sampleCount = 4)).sampleCount)
        assertEquals(SpreadLimits.of(null), SpreadLimits.techniqueOf(null).spreadLimit)
    }

    @Test
    fun theSampleSpreadIsMaxMinusMin() {
        // |first − last| is 2 µm. max − min is 20 µm.
        assertEquals(20.0, SpreadLimits.sampleSpreadUm(listOf(10.0, 30.0, 12.0)))
        assertEquals(0.0, SpreadLimits.sampleSpreadUm(emptyList()))
    }
}
