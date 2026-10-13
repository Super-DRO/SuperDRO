package org.superdro.validation.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.superdro.core.dataoutput.DataOutputValidation
import org.superdro.core.dataoutput.InvalidConfigException
import org.superdro.core.dataoutput.OutputTime
import org.superdro.core.dataoutput.OutputValues
import org.superdro.core.dataoutput.PortRegistry
import org.superdro.core.dataoutput.RecordFormatter
import org.superdro.core.model.AngleUnit
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputDelimiter
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DistanceUnit
import org.superdro.core.model.TimestampFormat

/**
 * The record formatter of the data output and the default
 * record of 48 bytes.
 */
class DataOutputFormatTest {

    /** 2026-10-11T14:03:22.517Z. */
    private val time = 1_791_727_402_517L
    private val example = OutputValues(xAbsolute = 12_700.0, yAbsolute = -3_175.0, zAbsolute = 0.0)

    /** A configuration with only [field] enabled and no time stamp. */
    private fun only(field: DataOutputFieldId, unit: DistanceUnit = DistanceUnit.Millimeter, angle: AngleUnit = AngleUnit.DecimalDegrees, precision: Int = 4) =
        DataOutputConfig(
            timestampEnabled = false,
            distanceUnit = unit,
            angleUnit = angle,
            precision = precision,
            fields = DataOutputConfig.defaultFields().map { it.copy(enabled = it.id == field) },
        )

    private fun text(config: DataOutputConfig, values: OutputValues): String =
        RecordFormatter.create(config).record(values, time, 0).removeSuffix("\n")

    @Test
    fun the_default_record_has_48_bytes() {
        val record = RecordFormatter.create(DataOutputConfig()).record(example, time, 0)
        assertEquals("2026-10-11T14:03:22.517Z;12.7000;-3.1750;0.0000\n", record)
        assertEquals(48, record.encodeToByteArray().size)
        assertEquals(0x0A.toByte(), record.encodeToByteArray().last())
    }

    @Test
    fun the_default_json_record_has_116_bytes() {
        val record = RecordFormatter.create(DataOutputConfig(format = DataOutputFormat.Json)).record(example, time, 0)
        assertEquals(
            "{\"timestamp\":\"2026-10-11T14:03:22.517Z\",\"sequence\":0,\"x_absolute\":12.7000,\"y_absolute\":-3.1750,\"z_absolute\":0.0000}\n",
            record,
        )
        assertEquals(116, record.encodeToByteArray().size)
    }

    @Test
    fun delimited_with_comma_labels_and_epoch_ms_has_80_bytes() {
        val config = DataOutputConfig(delimiter = DataOutputDelimiter.Comma, labels = true, timestampFormat = TimestampFormat.EpochMs)
        val record = RecordFormatter.create(config).record(example, time, 0)
        assertEquals("timestamp=1791727402517,x_absolute=12.7000,y_absolute=-3.1750,z_absolute=0.0000\n", record)
        assertEquals(80, record.encodeToByteArray().size)
    }

    @Test
    fun the_time_stamp_texts() {
        assertEquals("2026-10-11T14:03:22.517Z", OutputTime.iso8601(time))
        assertEquals(24, OutputTime.iso8601(0).length)
        assertEquals("1970-01-01T00:00:00.000Z", OutputTime.iso8601(0))
        assertEquals("1791727402517", OutputTime.text(time, TimestampFormat.EpochMs))
    }

    @Test
    fun the_formatter_table_of_data_output_13_3() {
        val x = DataOutputFieldId.XAbsolute
        assertEquals("0.5000", text(only(x, DistanceUnit.Inch), OutputValues(xAbsolute = 12_700.0)))
        assertEquals("500.0", text(only(x, DistanceUnit.Mil, precision = 1), OutputValues(xAbsolute = 12_700.0)))
        assertEquals("12.7000", text(only(x, DistanceUnit.Millimeter), OutputValues(xAbsolute = 12_700.0)))
        assertEquals("12700", text(only(x, DistanceUnit.Micron, precision = 0), OutputValues(xAbsolute = 12_700.0)))
        assertEquals("0.012700", text(only(x, DistanceUnit.Meter, precision = 6), OutputValues(xAbsolute = 12_700.0)))
        assertEquals("-3.1750", text(only(x, DistanceUnit.Millimeter), OutputValues(xAbsolute = -3_175.0)))
        assertEquals("-0.0000", text(only(x, DistanceUnit.Micron), OutputValues(xAbsolute = -0.00001)))
        val a = DataOutputFieldId.AngularAbsolute
        assertEquals("45.5043", text(only(a), OutputValues(angularAbsolute = 163_815.5)))
        assertEquals("45d30m15.5000s", text(only(a, angle = AngleUnit.DegreesMinutesSeconds), OutputValues(angularAbsolute = 163_815.5)))
        assertEquals("-45d30m15.5000s", text(only(a, angle = AngleUnit.DegreesMinutesSeconds), OutputValues(angularAbsolute = -163_815.5)))
        assertEquals("0.794200", text(only(a, angle = AngleUnit.Radians, precision = 6), OutputValues(angularAbsolute = 163_815.5)))
        assertEquals("1235", text(only(DataOutputFieldId.SpindleRpm), OutputValues(spindleRpm = 1234.5)))
        val speed = DataOutputFieldId.SurfaceSpeed
        val mill = OutputValues.surfaceSpeedUmPerMinute(rpm = 1000.0, diameterUm = 12_700.0)
        assertEquals("130.8997", text(only(speed, DistanceUnit.Inch), OutputValues(surfaceSpeed = mill)))
        assertEquals("39.8982", text(only(speed, DistanceUnit.Millimeter), OutputValues(surfaceSpeed = mill)))
        val lathe = OutputValues.latheSurfaceSpeedUmPerMinute(rpm = 1000.0, xUm = 6_350.0)
        assertEquals("39.8982", text(only(speed, DistanceUnit.Millimeter), OutputValues(surfaceSpeed = lathe)))
        // 2540 µm/s = 152400 µm/min.
        val feed = DataOutputFieldId.XFeedRate
        assertEquals("152.4000", text(only(feed, DistanceUnit.Millimeter), OutputValues(xFeedRate = 2540.0 * 60.0)))
        assertEquals("6.0000", text(only(feed, DistanceUnit.Inch), OutputValues(xFeedRate = 2540.0 * 60.0)))
    }

    @Test
    fun the_round_off_is_half_up_and_precision_0_has_no_point() {
        val x = DataOutputFieldId.XAbsolute
        assertEquals("1235", text(only(x, DistanceUnit.Micron, precision = 0), OutputValues(xAbsolute = 1234.5)))
        assertEquals("-1235", text(only(x, DistanceUnit.Micron, precision = 0), OutputValues(xAbsolute = -1234.5)))
        assertEquals("2.5", text(only(x, DistanceUnit.Micron, precision = 1), OutputValues(xAbsolute = 2.45)))
    }

    @Test
    fun a_field_without_a_value_gives_null_or_an_empty_text() {
        val values = OutputValues(yAbsolute = -3_175.0, zAbsolute = 0.0)
        assertEquals("2026-10-11T14:03:22.517Z;;-3.1750;0.0000\n", RecordFormatter.create(DataOutputConfig()).record(values, time, 0))
        assertEquals(
            "{\"timestamp\":\"2026-10-11T14:03:22.517Z\",\"sequence\":3,\"x_absolute\":null,\"y_absolute\":-3.1750,\"z_absolute\":0.0000}\n",
            RecordFormatter.create(DataOutputConfig(format = DataOutputFormat.Json)).record(values, time, 3),
        )
    }

    @Test
    fun a_change_smaller_than_the_last_decimal_gives_the_same_field_texts() {
        val formatter = RecordFormatter.create(DataOutputConfig())
        assertEquals(formatter.valueTexts(example), formatter.valueTexts(example.copy(xAbsolute = 12_700.04)))
        assertNotEquals(formatter.valueTexts(example), formatter.valueTexts(example.copy(xAbsolute = 12_700.1)))
    }

    @Test
    fun json_uses_decimal_degrees_when_the_angle_unit_is_dms() {
        val config = only(DataOutputFieldId.AngularAbsolute, angle = AngleUnit.DegreesMinutesSeconds).copy(format = DataOutputFormat.Json)
        assertEquals("{\"sequence\":0,\"angular_absolute\":45.5043}", text(config, OutputValues(angularAbsolute = 163_815.5)))
    }

    @Test
    fun the_dms_seconds_carry_into_the_minutes() {
        val config = only(DataOutputFieldId.AngularAbsolute, angle = AngleUnit.DegreesMinutesSeconds, precision = 2)
        // 3599.999″ with 2 decimals gave 0d59m60.00s.
        assertEquals("1d0m0.00s", text(config, OutputValues(angularAbsolute = 3599.999)))
        assertEquals("0d1m0.00s", text(config, OutputValues(angularAbsolute = 59.999)))
    }

    @Test
    fun json_escapes_the_labels() {
        val fields = DataOutputConfig.defaultFields().map { field ->
            if (field.id == DataOutputFieldId.XAbsolute) field.copy(label = "x \"abs\" \\ 1") else field.copy(enabled = false)
        }
        val config = DataOutputConfig(format = DataOutputFormat.Json, timestampEnabled = false, fields = fields)
        assertEquals("{\"sequence\":0,\"x_absolute\":12.7000}".replace("x_absolute", "x \\\"abs\\\" \\\\ 1"), text(config, example))
    }

    @Test
    fun a_delimited_label_with_the_delimiter_an_equals_sign_or_a_line_feed_is_refused() {
        listOf("a;b", "a=b", "a\nb").forEach { label ->
            val fields = DataOutputConfig.defaultFields().map { field -> if (field.id == DataOutputFieldId.XAbsolute) field.copy(label = label) else field }
            val config = DataOutputConfig(fields = fields)
            assertTrue(DataOutputValidation.problems(config).isNotEmpty(), "label ${label.replace("\n", "\\n")}")
            assertFailsWith<InvalidConfigException> { RecordFormatter.create(config) }
        }
        val comma = DataOutputConfig(
            delimiter = DataOutputDelimiter.Comma,
            fields = DataOutputConfig.defaultFields().map { field -> if (field.id == DataOutputFieldId.XAbsolute) field.copy(label = "a,b") else field },
        )
        assertTrue(DataOutputValidation.problems(comma).isNotEmpty())
    }

    @Test
    fun the_limits_of_the_configuration_of_data_output_4_2() {
        fun problems(config: DataOutputConfig) = DataOutputValidation.problems(config)
        assertEquals(emptyList(), problems(DataOutputConfig()))
        assertEquals(emptyList(), problems(DataOutputConfig(precision = 0)))
        assertEquals(emptyList(), problems(DataOutputConfig(precision = 6)))
        assertTrue(problems(DataOutputConfig(precision = 7)).isNotEmpty(), "precision 0 to 6")
        assertTrue(problems(DataOutputConfig(intervalMs = 49)).isNotEmpty(), "interval 50 to 5000 ms")
        assertTrue(problems(DataOutputConfig(intervalMs = 5001)).isNotEmpty(), "interval 50 to 5000 ms")
        assertEquals(emptyList(), problems(DataOutputConfig(intervalMs = 50, maxRateHz = 1)))
        assertTrue(problems(DataOutputConfig(maxRateHz = 21)).isNotEmpty(), "max rate 1 to 20 Hz")
        assertTrue(problems(DataOutputConfig(maxRateHz = 0)).isNotEmpty(), "max rate 1 to 20 Hz")
        fun label(id: DataOutputFieldId, text: String, enabled: Boolean = true) =
            DataOutputConfig(fields = DataOutputConfig.defaultFields().map { if (it.id == id) it.copy(label = text, enabled = enabled) else it })
        assertTrue(problems(label(DataOutputFieldId.XAbsolute, "")).isNotEmpty(), "a label has 1 to 32 characters")
        assertTrue(problems(label(DataOutputFieldId.XAbsolute, "x".repeat(33))).isNotEmpty(), "a label has 1 to 32 characters")
        assertEquals(emptyList(), problems(label(DataOutputFieldId.XAbsolute, "x".repeat(32))))
        // The labels of the enabled fields are unique.
        assertTrue(problems(label(DataOutputFieldId.XAbsolute, "y_absolute")).isNotEmpty())
        assertEquals(emptyList(), problems(label(DataOutputFieldId.XIncremental, "y_absolute", enabled = false)))
    }

    @Test
    fun probe_2_is_a_field_that_the_user_can_enable() {
        val config = only(DataOutputFieldId.Probe2)
        assertEquals(emptyList(), DataOutputValidation.problems(config))
        assertEquals("1", text(config, OutputValues(probe2 = 1.0)))
        assertEquals("0", text(config, OutputValues(probe2 = 0.0)))
    }

    @Test
    fun two_outputs_cannot_use_the_same_port() {
        val ports = PortRegistry()
        assertEquals(null, ports.claim(5800, "profile-a"))
        assertEquals("profile-a", ports.claim(5800, "profile-b"))
        ports.release(5800, "profile-a")
        assertEquals(null, ports.claim(5800, "profile-b"))
    }
}
