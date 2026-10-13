package org.superdro.core.functions.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import org.superdro.core.model.LogDisplayFormat

class MeasurementFormatTest {

    private val mm3 = LogDisplayFormat(metric = true, precision = 3)
    private val inch4 = LogDisplayFormat(metric = false, precision = 4)

    @Test
    fun aNumberRoundsHalfTowardPositiveInfinity() {
        assertEquals("0.57", MeasurementFormat.fixed(0.5729, 2))
        assertEquals("-89.43", MeasurementFormat.fixed(-89.4271, 2))
        assertEquals("0.000", MeasurementFormat.fixed(-0.0004, 3))
        assertEquals("-0.001", MeasurementFormat.fixed(-0.0006, 3))
        assertEquals("3", MeasurementFormat.fixed(2.5, 0))
        assertEquals("-2", MeasurementFormat.fixed(-2.5, 0))
        assertEquals("12345.000", MeasurementFormat.fixed(12345.0, 3))
        assertEquals("—", MeasurementFormat.fixed(Double.NaN, 3))
    }

    @Test
    fun aLengthIsMillimetersOrInches() {
        assertEquals("105.774", MeasurementFormat.length(105773.5, metric = true, decimals = 3))
        assertEquals("1.9685", MeasurementFormat.length(50000.0, metric = false, decimals = 4))
        assertEquals("50.00", MeasurementFormat.length(50000.0, metric = true, decimals = 2))
        assertEquals("-0.500", MeasurementFormat.length(-500.0, mm3))
    }

    @Test
    fun theValueTextsHaveTheUnitsOfTheTable() {
        assertEquals("50.000 mm", MeasurementFormat.valueText(ResultValue.Length(50000.0), mm3))
        assertEquals("1.9685\"", MeasurementFormat.valueText(ResultValue.Length(50000.0), inch4))
        assertEquals("Ø 50.000 mm", MeasurementFormat.valueText(ResultValue.Diameter(50000.0), mm3))
        assertEquals("Ø 1.9685\"", MeasurementFormat.valueText(ResultValue.Diameter(50000.0), inch4))
        assertEquals("20.000, 15.000 mm", MeasurementFormat.valueText(ResultValue.Position(20000.0, 15000.0), mm3))
        assertEquals("0.7874, 0.5906\"", MeasurementFormat.valueText(ResultValue.Position(20000.0, 15000.0), inch4))
        assertEquals("120.00°", MeasurementFormat.valueText(ResultValue.Angle(119.9993), mm3))
    }

    // The CreatedOn text
    @Test
    fun theTimeStampHasTheLocalTimeAndTheOffset() {
        assertEquals("2026-10-11 14:25:30 -0700", LogTimestamp.format(Instant.parse("2026-10-11T21:25:30Z"), -25200))
        assertEquals("2024-03-01 00:30:00 +0100", LogTimestamp.format(Instant.parse("2024-02-29T23:30:00Z"), 3600))
        assertEquals("2024-02-29 05:30:00 +0530", LogTimestamp.format(Instant.parse("2024-02-29T00:00:00Z"), 19800))
        assertEquals("1969-12-31 23:59:59 +0000", LogTimestamp.format(Instant.parse("1969-12-31T23:59:59Z"), 0))
        assertEquals("2000-01-01 00:00:00 +0000", LogTimestamp.format(Instant.parse("2000-01-01T00:00:00Z"), 0))
        assertEquals("20261011_142530", LogTimestamp.fileStamp(Instant.parse("2026-10-11T21:25:30Z"), -25200))
    }
}
