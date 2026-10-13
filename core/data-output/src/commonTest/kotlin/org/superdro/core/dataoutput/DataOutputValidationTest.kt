package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputDelimiter
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.MqttTopicLayout

/** The configuration rules of the data output. */
class DataOutputValidationTest {

    private fun withLabel(id: DataOutputFieldId, label: String, enabled: Boolean = true) =
        DataOutputConfig.defaultFields().map { field -> if (field.id == id) field.copy(label = label, enabled = enabled) else field }

    @Test
    fun theDefaultConfigurationIsValid() {
        assertEquals(emptyList(), DataOutputValidation.problems(DataOutputConfig()))
    }

    @Test
    fun theLimitsOfTheNumbersAreChecked() {
        val problems = DataOutputValidation.problems(DataOutputConfig(port = 1023, intervalMs = 5001, maxRateHz = 0, precision = 7))
        assertEquals(
            listOf(ConfigProblem.Port(1023), ConfigProblem.Interval(5001), ConfigProblem.MaxRate(0), ConfigProblem.Precision(7)),
            problems,
        )
        assertEquals(emptyList(), DataOutputValidation.problems(DataOutputConfig(port = 65535, intervalMs = 50, maxRateHz = 1, precision = 0)))
    }

    @Test
    fun aLabelHas1To32CharactersAndTheEnabledLabelsAreUnique() {
        assertEquals(
            listOf(ConfigProblem.LabelLength(DataOutputFieldId.XAbsolute, 0)),
            DataOutputValidation.problems(DataOutputConfig(fields = withLabel(DataOutputFieldId.XAbsolute, ""))),
        )
        assertEquals(
            listOf(ConfigProblem.LabelLength(DataOutputFieldId.XAbsolute, 33)),
            DataOutputValidation.problems(DataOutputConfig(fields = withLabel(DataOutputFieldId.XAbsolute, "x".repeat(33)))),
        )
        assertEquals(
            listOf(ConfigProblem.LabelDuplicate("y_absolute", listOf(DataOutputFieldId.XAbsolute, DataOutputFieldId.YAbsolute))),
            DataOutputValidation.problems(DataOutputConfig(fields = withLabel(DataOutputFieldId.XAbsolute, "y_absolute"))),
        )
        // A disabled field does not count.
        assertEquals(emptyList(), DataOutputValidation.problems(DataOutputConfig(fields = withLabel(DataOutputFieldId.XIncremental, "y_absolute", enabled = false))))
    }

    @Test
    fun theDelimitedFormatRefusesTheCharactersThatBreakARecord() {
        val delimiter = DataOutputDelimiter.Semicolon
        assertEquals('"', DataOutputValidation.delimitedRefusedCharacter("a\"b", delimiter))
        assertEquals('\\', DataOutputValidation.delimitedRefusedCharacter("a\\b", delimiter))
        assertEquals('=', DataOutputValidation.delimitedRefusedCharacter("a=b", delimiter))
        assertEquals(';', DataOutputValidation.delimitedRefusedCharacter("a;b", delimiter))
        assertEquals('\n', DataOutputValidation.delimitedRefusedCharacter("a\nb", delimiter))
        assertEquals('\t', DataOutputValidation.delimitedRefusedCharacter("a\tb", delimiter))
        assertEquals('|', DataOutputValidation.delimitedRefusedCharacter("a|b", DataOutputDelimiter.Pipe))
        assertEquals(null, DataOutputValidation.delimitedRefusedCharacter("a|b,c", delimiter))
        assertEquals(null, DataOutputValidation.delimitedRefusedCharacter("X position (mm)", delimiter))
    }

    @Test
    fun theJsonFormatAcceptsTheLabelsThatItEscapes() {
        val fields = withLabel(DataOutputFieldId.XAbsolute, "x=\"1\";\\")
        assertEquals(
            listOf<ConfigProblem>(ConfigProblem.LabelCharacter(DataOutputFieldId.XAbsolute, '=')),
            DataOutputValidation.problems(DataOutputConfig(fields = fields)),
        )
        assertEquals(emptyList(), DataOutputValidation.problems(DataOutputConfig(fields = fields, format = DataOutputFormat.Json)))
    }

    @Test
    fun theKeysOfTheTimeAndTheSequenceAreNotFieldLabels() {
        listOf("timestamp", "sequence").forEach { label ->
            assertEquals(
                listOf<ConfigProblem>(ConfigProblem.LabelReserved(DataOutputFieldId.XAbsolute, label)),
                DataOutputValidation.problems(DataOutputConfig(fields = withLabel(DataOutputFieldId.XAbsolute, label), format = DataOutputFormat.Json)),
            )
        }
    }

    @Test
    fun aTopicForEachFieldRefusesTheTopicCharacters() {
        val mqtt = DataOutputConfig(transport = DataOutputTransport.Mqtt, mqttBrokerHost = "broker.local", mqttTopicLayout = MqttTopicLayout.TopicPerField)
        assertEquals(emptyList(), DataOutputValidation.problems(mqtt))
        listOf('/', '+', '#').forEach { char ->
            val problems = DataOutputValidation.problems(mqtt.copy(fields = withLabel(DataOutputFieldId.ZAbsolute, "z${char}1")))
            assertEquals(listOf<ConfigProblem>(ConfigProblem.LabelTopicCharacter(DataOutputFieldId.ZAbsolute, char)), problems)
        }
        // The JSON layout escapes the label, so a slash is valid there.
        assertEquals(emptyList(), DataOutputValidation.problems(mqtt.copy(mqttTopicLayout = MqttTopicLayout.JsonRecord, fields = withLabel(DataOutputFieldId.ZAbsolute, "z/1"))))
    }

    @Test
    fun theBrokerValuesAreChecked() {
        val mqtt = DataOutputConfig(transport = DataOutputTransport.Mqtt)
        assertEquals(listOf<ConfigProblem>(ConfigProblem.BrokerHost("")), DataOutputValidation.problems(mqtt))
        val problems = DataOutputValidation.problems(
            mqtt.copy(mqttBrokerHost = "b", mqttBrokerPort = 0, mqttClientId = "c".repeat(65), mqttMachineName = "mill/1"),
        )
        assertEquals(
            listOf(ConfigProblem.BrokerPort(0), ConfigProblem.ClientId("c".repeat(65)), ConfigProblem.MachineName("mill/1")),
            problems,
        )
    }

    @Test
    fun theFieldListHasEachFieldOneTime() {
        val fields = DataOutputConfig.defaultFields().drop(1) + DataOutputConfig.defaultFields()[3]
        val problems = DataOutputValidation.problems(DataOutputConfig(fields = fields))
        assertTrue(ConfigProblem.FieldList(listOf(DataOutputFieldId.XAbsolute), listOf(DataOutputFieldId.YAbsolute)) in problems)
    }

    @Test
    fun twoOutputsCannotUseTheSamePort() {
        val on = DataOutputConfig(enabled = true)
        val conflicts = DataOutputValidation.portConflicts(
            mapOf(
                "mill" to on,
                "lathe" to on,
                "router" to on.copy(port = 5801),
                "off" to DataOutputConfig(enabled = false),
                "mqtt" to on.copy(transport = DataOutputTransport.Mqtt),
            ),
        )
        assertEquals(mapOf(5800 to listOf("lathe", "mill")), conflicts)
    }
}
