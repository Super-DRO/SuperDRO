package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.AngleUnit
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.MqttTopicLayout

/** The two MQTT topic layouts. */
class MqttTopicsTest {

    private val config = DataOutputConfig(transport = DataOutputTransport.Mqtt, mqttBrokerHost = "broker.local")
    private val formatter = RecordFormatter.create(config, DataOutputFormat.Json)
    private val values = OutputValues(xAbsolute = 12700.0, yAbsolute = -3175.0, zAbsolute = 0.0)
    private val record = OutputRecord(formatter.valueTexts(values), 1791727402517L, 4L)

    private fun List<Pair<String, ByteArray>>.texts() = map { (topic, payload) -> topic to payload.decodeToString() }

    @Test
    fun theBaseTopicIsSuperdroAndTheMachine() {
        assertEquals("superdro/mill-1", MqttTopics.base("mill-1"))
        assertEquals("superdro/Mill 1", MqttTopics.base("Mill 1"))
        assertEquals("superdro/a_b_c_d", MqttTopics.base("a/b+c#d"))
        assertEquals("superdro/_", MqttTopics.base(""))
    }

    @Test
    fun theJsonLayoutSendsOneRecordForEachMessage() {
        val messages = MqttTopics.messages(MqttTopicLayout.JsonRecord, "superdro/mill-1", formatter, record, timestampEnabled = true)
        assertEquals(
            listOf(
                "superdro/mill-1" to
                    "{\"timestamp\":\"2026-10-11T14:03:22.517Z\",\"sequence\":4,\"x_absolute\":12.7000,\"y_absolute\":-3.1750,\"z_absolute\":0.0000}",
            ),
            messages.texts(),
        )
    }

    @Test
    fun theFieldLayoutSendsTheNumberOfEachFieldOnItsTopic() {
        val messages = MqttTopics.messages(MqttTopicLayout.TopicPerField, "superdro/mill-1", formatter, record, timestampEnabled = true)
        assertEquals(
            listOf(
                "superdro/mill-1/timestamp" to "2026-10-11T14:03:22.517Z",
                "superdro/mill-1/x_absolute" to "12.7000",
                "superdro/mill-1/y_absolute" to "-3.1750",
                "superdro/mill-1/z_absolute" to "0.0000",
            ),
            messages.texts(),
        )
        val partial = OutputRecord(formatter.valueTexts(values.copy(yAbsolute = null)), 0L, 0L)
        assertEquals(
            listOf("superdro/m/x_absolute" to "12.7000", "superdro/m/y_absolute" to "", "superdro/m/z_absolute" to "0.0000"),
            MqttTopics.messages(MqttTopicLayout.TopicPerField, "superdro/m", formatter, partial, timestampEnabled = false).texts(),
        )
    }

    @Test
    fun theFieldLayoutSendsNumbersAlsoForTheUnitDms() {
        val fields = DataOutputConfig.defaultFields().map { field -> field.copy(enabled = field.id == DataOutputFieldId.AngularAbsolute) }
        val dms = config.copy(fields = fields, angleUnit = AngleUnit.DegreesMinutesSeconds, mqttTopicLayout = MqttTopicLayout.TopicPerField)
        val jsonFormatter = RecordFormatter.create(dms, DataOutputFormat.Json)
        val angle = OutputRecord(jsonFormatter.valueTexts(OutputValues(angularAbsolute = 163815.5)), 0L, 0L)
        assertEquals(
            listOf("superdro/m/angular_absolute" to "45.5043"),
            MqttTopics.messages(MqttTopicLayout.TopicPerField, "superdro/m", jsonFormatter, angle, timestampEnabled = false).texts(),
        )
    }

    @Test
    fun theServerUriHasTheSchemeOfTheTlsSetting() {
        assertEquals("ssl://broker.local:8883", MqttConnectParams("broker.local", 8883, tls = true, clientId = "c").serverUri)
        assertEquals("tcp://10.0.10.5:1883", MqttConnectParams("10.0.10.5", 1883, tls = false, clientId = "c").serverUri)
    }
}
