package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.AngleUnit
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputField
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DistanceUnit

/**
 * The formatter table. SuperDRO uses `Double` math, and each row gives the same text as the table.
 */
class FormatterTableTest {

    private fun text(
        id: DataOutputFieldId,
        value: Double,
        precision: Int = 4,
        distance: DistanceUnit = DistanceUnit.Millimeter,
        angle: AngleUnit = AngleUnit.DecimalDegrees,
        format: DataOutputFormat = DataOutputFormat.Delimited,
    ): String? {
        val config = DataOutputConfig(
            fields = DataOutputFieldId.entries.map { field -> DataOutputField(field, enabled = field == id, label = field.wireId) },
            precision = precision,
            distanceUnit = distance,
            angleUnit = angle,
            format = format,
        )
        return RecordFormatter.create(config).valueTexts(OutputValues.of(mapOf(id to value))).single()
    }

    @Test
    fun theDistanceRowsGiveTheTableTexts() {
        val x = DataOutputFieldId.XAbsolute
        assertEquals("0.5000", text(x, 12700.0, 4, DistanceUnit.Inch))
        assertEquals("500.0", text(x, 12700.0, 1, DistanceUnit.Mil))
        assertEquals("12.7000", text(x, 12700.0, 4, DistanceUnit.Millimeter))
        assertEquals("12700", text(x, 12700.0, 0, DistanceUnit.Micron))
        assertEquals("0.012700", text(x, 12700.0, 6, DistanceUnit.Meter))
        assertEquals("-3.1750", text(x, -3175.0, 4, DistanceUnit.Millimeter))
        assertEquals("-0.0000", text(x, -0.00001, 4, DistanceUnit.Micron))
    }

    @Test
    fun theAngleRowsGiveTheTableTexts() {
        val a = DataOutputFieldId.AngularAbsolute
        assertEquals("45.5043", text(a, 163815.5, 4, angle = AngleUnit.DecimalDegrees))
        assertEquals("45d30m15.5000s", text(a, 163815.5, 4, angle = AngleUnit.DegreesMinutesSeconds))
        assertEquals("-45d30m15.5000s", text(a, -163815.5, 4, angle = AngleUnit.DegreesMinutesSeconds))
        assertEquals("0.794200", text(a, 163815.5, 6, angle = AngleUnit.Radians))
    }

    @Test
    fun theJsonFormatGivesDecimalDegreesForTheUnitDms() {
        val a = DataOutputFieldId.AngularAbsolute
        assertEquals("45.5043", text(a, 163815.5, 4, angle = AngleUnit.DegreesMinutesSeconds, format = DataOutputFormat.Json))
    }

    @Test
    fun theSpindleSpeedHasNoDecimals() {
        assertEquals("1235", text(DataOutputFieldId.SpindleRpm, 1234.5, 4))
    }

    @Test
    fun theSurfaceSpeedRowsGiveTheTableTexts() {
        val mill = OutputValues.surfaceSpeedUmPerMinute(rpm = 1000.0, diameterUm = 12700.0)
        val lathe = OutputValues.latheSurfaceSpeedUmPerMinute(rpm = 1000.0, xUm = 6350.0)
        val s = DataOutputFieldId.SurfaceSpeed
        assertEquals("130.8997", text(s, mill, 4, DistanceUnit.Inch))
        assertEquals("39.8982", text(s, mill, 4, DistanceUnit.Millimeter))
        assertEquals("39.8982", text(s, lathe, 4, DistanceUnit.Millimeter))
    }

    @Test
    fun theFeedRateRowsGiveTheTableTexts() {
        // 2540 µm/s is 152400 µm/min, the unit of the readout model.
        val f = DataOutputFieldId.XFeedRate
        assertEquals("152.4000", text(f, 2540.0 * 60.0, 4, DistanceUnit.Millimeter))
        assertEquals("6.0000", text(f, 2540.0 * 60.0, 4, DistanceUnit.Inch))
    }

    @Test
    fun theProbeFieldsAreIntegers() {
        assertEquals("1", text(DataOutputFieldId.Probe1, 1.0))
        assertEquals("0", text(DataOutputFieldId.Probe2, 0.0))
        assertEquals("-1", text(DataOutputFieldId.Probe1TriggerDirX, -1.0))
        assertEquals("1791727402517", text(DataOutputFieldId.Probe1TriggerTime, 1791727402517.0))
        assertEquals("3", text(DataOutputFieldId.Probe1TriggerSeq, 3.0))
        assertEquals("12.7000", text(DataOutputFieldId.Probe1TriggerX, 12700.0))
    }

    @Test
    fun aFieldWithNoValueOrNoFiniteValueHasNoText() {
        val config = DataOutputConfig()
        val formatter = RecordFormatter.create(config)
        assertEquals(listOf(null, null, null), formatter.valueTexts(OutputValues()))
        assertEquals(listOf(null, "0.0000", null), formatter.valueTexts(OutputValues(xAbsolute = Double.NaN, yAbsolute = 0.0, zAbsolute = Double.POSITIVE_INFINITY)))
    }
}
