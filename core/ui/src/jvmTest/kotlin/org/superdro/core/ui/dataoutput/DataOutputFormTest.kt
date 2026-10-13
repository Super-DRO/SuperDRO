package org.superdro.core.ui.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.dataoutput.ConfigProblem
import org.superdro.core.dataoutput.DataOutputErrorType
import org.superdro.core.dataoutput.DataOutputError
import org.superdro.core.dataoutput.StartBlock
import org.superdro.core.dataoutput.TestConnectionResult
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputDelimiter
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.MqttTopicLayout
import org.superdro.core.ui.tabs.english

/** The rules of the data output screen. */
class DataOutputFormTest {

    @Test
    fun theDefaultsAreOffWifiPort5800PskAndOnDemand() {
        val config = DataOutputConfig()
        assertFalse(config.enabled)
        assertEquals(BindInterface.WiFi, config.bindInterface)
        assertEquals(5800, config.port)
        assertEquals(DataOutputSecurity.Psk, config.security)
        assertEquals(org.superdro.core.model.DataOutputActivation.OnDemand, config.activation)
        assertTrue(config.mqttTls)
    }

    @Test
    fun thePlainTextModeAppliesOnlyAfterTheWarning() {
        val (kept, dialog) = DataOutputForm.selectSecurity(DataOutputConfig(), DataOutputSecurity.PlainText)
        assertEquals(DataOutputDialog.PlainTextWarning, dialog)
        assertEquals(DataOutputSecurity.Psk, kept.security, "The mode does not change before the confirmation.")
        val confirmed = DataOutputForm.confirmPlainText(kept)
        assertEquals(DataOutputSecurity.PlainText, confirmed.security)
        assertTrue(confirmed.plainTextConfirmed)
        val (again, noDialog) = DataOutputForm.selectSecurity(confirmed, DataOutputSecurity.PlainText)
        assertNull(noDialog)
        assertEquals(confirmed, again)
        val (psk, _) = DataOutputForm.selectSecurity(confirmed, DataOutputSecurity.Psk)
        assertEquals(DataOutputSecurity.Psk, psk.security)
        assertFalse(psk.plainTextConfirmed, "A different mode clears the confirmation.")
    }

    @Test
    fun aBrokerLinkWithNoTlsAppliesOnlyAfterTheWarning() {
        val mqtt = DataOutputConfig(transport = DataOutputTransport.Mqtt)
        val (kept, dialog) = DataOutputForm.selectTls(mqtt, tls = false)
        assertEquals(DataOutputDialog.MqttNoTlsWarning, dialog)
        assertTrue(kept.mqttTls)
        val confirmed = DataOutputForm.confirmNoTls(kept)
        assertFalse(confirmed.mqttTls)
        assertTrue(confirmed.mqttNoTlsConfirmed)
        assertEquals(1883, confirmed.mqttBrokerPort, "The default port with no TLS.")
        val (tls, none) = DataOutputForm.selectTls(confirmed, tls = true)
        assertNull(none)
        assertTrue(tls.mqttTls)
        assertFalse(tls.mqttNoTlsConfirmed)
        assertEquals(8883, tls.mqttBrokerPort)
    }

    @Test
    fun aDelimitedLabelRefusesTheCharactersThatBreakTheRecord() {
        val config = DataOutputConfig()
        listOf('"', '\\', '=', '\n', ';', '\t').forEach { character ->
            val result = DataOutputForm.editLabel(config, DataOutputFieldId.XAbsolute, "x${character}y")
            assertEquals(LabelEdit.Refused(character), result, "The character U+${character.code.toString(16)}")
        }
        val comma = config.copy(delimiter = DataOutputDelimiter.Comma)
        assertEquals(LabelEdit.Refused(','), DataOutputForm.editLabel(comma, DataOutputFieldId.XAbsolute, "a,b"))
        assertIs<LabelEdit.Accepted>(DataOutputForm.editLabel(comma, DataOutputFieldId.XAbsolute, "a;b"), "A semicolon is not the comma delimiter.")
        val accepted = DataOutputForm.editLabel(config, DataOutputFieldId.XAbsolute, "pos_x")
        assertIs<LabelEdit.Accepted>(accepted)
        assertEquals("pos_x", accepted.config.fields.first { field -> field.id == DataOutputFieldId.XAbsolute }.label)
        assertEquals(LabelEdit.TooLong, DataOutputForm.editLabel(config, DataOutputFieldId.XAbsolute, "a".repeat(33)))
    }

    @Test
    fun theJsonFormatEscapesTheLabelAndATopicLevelRefusesItsCharacters() {
        val json = DataOutputConfig(format = DataOutputFormat.Json)
        assertIs<LabelEdit.Accepted>(DataOutputForm.editLabel(json, DataOutputFieldId.XAbsolute, "x \"pos\" = 1;"))
        val topics = DataOutputConfig(transport = DataOutputTransport.Mqtt, mqttTopicLayout = MqttTopicLayout.TopicPerField)
        listOf('/', '+', '#').forEach { character ->
            assertEquals(LabelEdit.Refused(character), DataOutputForm.editLabel(topics, DataOutputFieldId.XAbsolute, "a${character}b"))
        }
    }

    @Test
    fun twoOutputsCannotUseTheSamePort() {
        val others = mapOf(
            "lathe" to DataOutputConfig(enabled = true, port = 5800),
            "router" to DataOutputConfig(enabled = false, port = 5801),
            "mqtt" to DataOutputConfig(enabled = true, port = 5802, transport = DataOutputTransport.Mqtt),
        )
        assertEquals(listOf("lathe"), DataOutputForm.portOwners("mill", DataOutputConfig(port = 5800), others), "The switch of the draft does not matter.")
        assertEquals(emptyList(), DataOutputForm.portOwners("mill", DataOutputConfig(port = 5801), others), "A disabled output holds no port.")
        assertEquals(emptyList(), DataOutputForm.portOwners("mill", DataOutputConfig(port = 5802), others), "An MQTT output holds no port.")
        assertEquals(emptyList(), DataOutputForm.portOwners("lathe", DataOutputConfig(port = 5800), others), "The own output does not conflict.")
        assertEquals(emptyList(), DataOutputForm.portOwners("mill", DataOutputConfig(port = 5800, transport = DataOutputTransport.Mqtt), others))
    }

    @Test
    fun theTextsOfTheStatusTheErrorsAndTheTestResults() {
        assertEquals("Sending", english(DataOutputTexts.status(org.superdro.core.dataoutput.DataOutputStatus.Sending)))
        assertEquals("The output is off. No server socket is open.", DataOutputTexts.block(StartBlock.NotEnabled)!!.english())
        assertEquals("The output of a different machine uses port 5800.", DataOutputTexts.block(StartBlock.PortTaken(5800, "lathe"))!!.english())
        val accept = DataOutputError(DataOutputErrorType.AcceptFailed, 0L, "192.168.1.20", 5800, "Socket closed")
        assertEquals("The server stopped: Socket closed. A new server starts in 5 s.", DataOutputTexts.error(accept, mqtt = false).english())
        val handshake = DataOutputError(DataOutputErrorType.HandshakeFailed, 0L, "192.168.1.30", null, null)
        assertEquals("The client 192.168.1.30 did not give the correct key.", DataOutputTexts.error(handshake, mqtt = false).english())
        val auth = DataOutputError(DataOutputErrorType.AuthRejected, 0L, "broker.local", 8883, null)
        assertEquals("The broker broker.local:8883 refused the user name or the password.", DataOutputTexts.error(auth, mqtt = true).english())
        val bind = DataOutputError(DataOutputErrorType.PortBindFailed, 0L, "fe80::1", 5800, null)
        assertEquals("Cannot listen on [fe80::1]:5800: no detail", DataOutputTexts.error(bind, mqtt = false).english())
        assertEquals(
            "Port 5800 is free. Clients can connect to 192.168.1.20:5800.",
            DataOutputTexts.testResult(TestConnectionResult.PortFree(5800, listOf("192.168.1.20:5800"))).english(),
        )
        assertEquals("The broker broker.local:8883 accepted the connection.", DataOutputTexts.testResult(TestConnectionResult.BrokerReachable("broker.local", 8883, true)).english())
        assertEquals(
            "A label cannot contain the character \";\".",
            DataOutputTexts.problem(ConfigProblem.LabelCharacter(DataOutputFieldId.XAbsolute, ';')).text.english(),
        )
        assertEquals("U+000A", DataOutputTexts.characterText('\n'))
        assertEquals("12 s", DataOutputTexts.age(12_400L).english())
        assertEquals("3 min", DataOutputTexts.age(200_000L).english())
        assertEquals("2 h", DataOutputTexts.age(7_300_000L).english())
    }

    private fun english(resource: org.jetbrains.compose.resources.StringResource): String =
        org.superdro.core.ui.text.UiText.of(resource).english()
}
