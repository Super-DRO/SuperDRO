package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputDelimiter
import org.superdro.core.model.DataOutputField
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.TimestampFormat

/** The records of the data output. */
class RecordFormatterTest {

    private val updateTime = 1791727402517L
    private val values = OutputValues(xAbsolute = 12700.0, yAbsolute = -3175.0, zAbsolute = 0.0, updatedAtEpochMs = updateTime)

    private fun record(config: DataOutputConfig, values: OutputValues = this.values, sequence: Long = 0L): String =
        RecordFormatter.create(config).record(values, updateTime, sequence)

    @Test
    fun theDefaultRecordHas48Bytes() {
        val text = record(DataOutputConfig())
        assertEquals("2026-10-11T14:03:22.517Z;12.7000;-3.1750;0.0000\n", text)
        val bytes = text.encodeToByteArray()
        assertEquals(48, bytes.size)
        assertEquals(0x0A.toByte(), bytes.last())
        assertEquals("323032362d31302d3131543134", bytes.copyOfRange(0, 13).joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') })
    }

    @Test
    fun theJsonRecordHasTheTimeTheSequenceAndTheFields() {
        val text = record(DataOutputConfig(format = DataOutputFormat.Json))
        assertEquals(
            "{\"timestamp\":\"2026-10-11T14:03:22.517Z\",\"sequence\":0,\"x_absolute\":12.7000,\"y_absolute\":-3.1750,\"z_absolute\":0.0000}\n",
            text,
        )
        assertEquals(116, text.encodeToByteArray().size)
        assertTrue(record(DataOutputConfig(format = DataOutputFormat.Json), sequence = 7).contains("\"sequence\":7,"))
    }

    @Test
    fun theDelimitedRecordWithLabelsAndEpochTimeHas80Bytes() {
        val config = DataOutputConfig(delimiter = DataOutputDelimiter.Comma, labels = true, timestampFormat = TimestampFormat.EpochMs)
        val text = record(config)
        assertEquals("timestamp=1791727402517,x_absolute=12.7000,y_absolute=-3.1750,z_absolute=0.0000\n", text)
        assertEquals(80, text.encodeToByteArray().size)
    }

    @Test
    fun eachDelimiterSeparatesTheValues() {
        assertEquals("2026-10-11T14:03:22.517Z\t12.7000\t-3.1750\t0.0000\n", record(DataOutputConfig(delimiter = DataOutputDelimiter.Tab)))
        assertEquals("2026-10-11T14:03:22.517Z|12.7000|-3.1750|0.0000\n", record(DataOutputConfig(delimiter = DataOutputDelimiter.Pipe)))
    }

    @Test
    fun aFieldWithNoValueGivesAnEmptyTextOrNull() {
        val partial = OutputValues(xAbsolute = 12700.0, zAbsolute = 0.0)
        assertEquals("2026-10-11T14:03:22.517Z;12.7000;;0.0000\n", record(DataOutputConfig(), partial))
        assertEquals("2026-10-11T14:03:22.517Z;12.7000;-3.1750;\n", record(DataOutputConfig(), values.copy(zAbsolute = null)))
        assertEquals(
            "{\"timestamp\":\"2026-10-11T14:03:22.517Z\",\"sequence\":0,\"x_absolute\":12.7000,\"y_absolute\":null,\"z_absolute\":0.0000}\n",
            record(DataOutputConfig(format = DataOutputFormat.Json), partial),
        )
        assertEquals("timestamp=2026-10-11T14:03:22.517Z;x_absolute=12.7000;y_absolute=;z_absolute=0.0000\n", record(DataOutputConfig(labels = true), partial))
    }

    @Test
    fun theJsonTimeInEpochMsHasNoQuotes() {
        val text = record(DataOutputConfig(format = DataOutputFormat.Json, timestampFormat = TimestampFormat.EpochMs))
        assertTrue(text.startsWith("{\"timestamp\":1791727402517,\"sequence\":0,"))
    }

    @Test
    fun aRecordWithNoTimeAndNoFieldIsOnlyTheLineFeed() {
        val config = DataOutputConfig(timestampEnabled = false, fields = DataOutputConfig.defaultFields().map { field -> field.copy(enabled = false) })
        assertEquals("\n", record(config))
        assertEquals("{\"sequence\":3}\n", record(config.copy(format = DataOutputFormat.Json), sequence = 3))
    }

    @Test
    fun theFieldsAreInTheOrderOfTheFieldList() {
        val fields = DataOutputConfig.defaultFields().map { field ->
            when (field.id) {
                DataOutputFieldId.XAbsolute -> field.copy(label = "X")
                DataOutputFieldId.SpindleRpm -> field.copy(enabled = true, label = "rpm")
                DataOutputFieldId.Probe1TriggerFeed -> field.copy(enabled = true)
                else -> field
            }
        }
        val config = DataOutputConfig(fields = fields, format = DataOutputFormat.Json, timestampEnabled = false)
        val text = record(config, values.copy(spindleRpm = 1200.0, probe1TriggerFeed = 60000.0))
        assertEquals("{\"sequence\":0,\"X\":12.7000,\"y_absolute\":-3.1750,\"z_absolute\":0.0000,\"rpm\":1200,\"probe_1_trigger_feed\":60.0000}\n", text)
    }

    @Test
    fun theJsonLabelsHaveTheEscapes() {
        val fields = DataOutputConfig.defaultFields().map { field ->
            if (field.id == DataOutputFieldId.XAbsolute) field.copy(label = "a\"b\\c/d\te") else field
        }
        val text = record(DataOutputConfig(fields = fields, format = DataOutputFormat.Json, timestampEnabled = false))
        assertEquals("{\"sequence\":0,\"a\\\"b\\\\c/d\\te\":12.7000,\"y_absolute\":-3.1750,\"z_absolute\":0.0000}\n", text)
        assertEquals("\\u0001\\n\\r\\b\\f", RecordFormatter.jsonEscape("\u0001\n\r\b\u000C"))
    }

    @Test
    fun aDelimitedLabelWithARefusedCharacterIsRefused() {
        listOf("x\"", "x\\", "x=1", "x;y", "x\ny", "x\ry").forEach { label ->
            val fields = DataOutputConfig.defaultFields().map { field ->
                if (field.id == DataOutputFieldId.XAbsolute) field.copy(label = label) else field
            }
            val exception = assertFailsWith<InvalidConfigException>(label) { RecordFormatter.create(DataOutputConfig(fields = fields, labels = true)) }
            assertTrue(exception.problems.single() is ConfigProblem.LabelCharacter, label)
        }
        // A comma is valid with the semicolon, but not with the comma delimiter.
        val comma = DataOutputConfig.defaultFields().map { field -> if (field.id == DataOutputFieldId.XAbsolute) field.copy(label = "x,y") else field }
        RecordFormatter.create(DataOutputConfig(fields = comma))
        assertFailsWith<InvalidConfigException> { RecordFormatter.create(DataOutputConfig(fields = comma, delimiter = DataOutputDelimiter.Comma)) }
        // The JSON format escapes the same labels.
        RecordFormatter.create(DataOutputConfig(fields = comma, format = DataOutputFormat.Json))
    }

    @Test
    fun theTextsOfTheChangeCheckDoNotHaveTheTime() {
        val formatter = RecordFormatter.create(DataOutputConfig())
        assertEquals(formatter.valueTexts(values), formatter.valueTexts(values.copy(updatedAtEpochMs = updateTime + 500)))
        assertEquals(listOf<String?>("12.7000", "-3.1750", "0.0000"), formatter.valueTexts(values.copy(xAbsolute = 12700.00004)))
    }

    @Test
    fun aDisabledFieldIsNotInTheRecord() {
        val fields = DataOutputConfig.defaultFields().map { field -> if (field.id == DataOutputFieldId.YAbsolute) field.copy(enabled = false) else field }
        assertEquals(listOf(DataOutputFieldId.XAbsolute, DataOutputFieldId.ZAbsolute), RecordFormatter.create(DataOutputConfig(fields = fields)).fields.map(DataOutputField::id))
    }
}
