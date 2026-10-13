package org.superdro.core.ui.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.PermissionKind
import org.superdro.core.dataoutput.DataOutputSecrets
import org.superdro.core.dataoutput.DataOutputStatus
import org.superdro.core.dataoutput.PskKey
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.MachineProfile
import org.superdro.core.ui.tabs.english

/**
 * The presenter of the data output screen with virtual time: the warning
 * before the plain text mode, the port conflict, the label refusal, the switch, the PSK, and the
 * MQTT credentials.
 */
class DataOutputPresenterTest {

    private class Screen(val harness: DataOutputHarness, val presenter: DataOutputPresenter) {
        val state get() = presenter.uiState.value

        fun settle() = harness.settle()

        suspend fun stored(profile: MachineProfile): DataOutputConfig = harness.store.machines.loadProfile(profile.id)!!.dataOutput
    }

    private fun run(prepare: suspend DataOutputHarness.() -> MachineProfile = { profile() }, block: suspend TestScope.(Screen, MachineProfile) -> Unit) = runTest {
        val harness = DataOutputHarness(this)
        try {
            val profile = harness.prepare()
            harness.session(profile)
            harness.start()
            val presenter = harness.presenter()
            presenter.selectProfile(profile.id)
            harness.settle()
            try {
                block(Screen(harness, presenter), profile)
            } finally {
                presenter.close()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun theScreenShowsTheDefaultsAndTheStatus() = run { screen, profile ->
        val state = screen.state
        assertEquals(profile.id, state.profileId)
        assertFalse(state.draft.enabled)
        assertEquals(DataOutputSecurity.Psk, state.draft.security)
        assertEquals("5800", state.portText)
        assertEquals(DataOutputStatus.Stopped, state.status.status)
        assertEquals(listOf("192.168.1.20:5800"), state.status.addresses, "The address of the Wi-Fi interface.")
        assertEquals(DataOutputSecurity.Psk, state.status.security)
        assertEquals(0, state.status.clients)
        assertEquals("The output is off. No server socket is open.", state.status.blockText!!.english())
        assertEquals(listOf("192.168.1.20"), state.interfaces.first { choice -> choice.bind == BindInterface.WiFi }.addresses)
        assertEquals(listOf("192.168.1.20", "127.0.0.1"), state.interfaces.first { choice -> choice.bind == BindInterface.All }.addresses, "All interfaces has no cellular address.")
        val key = PskKey.parse(state.psk!!)
        assertEquals(PskKey.DEFAULT_IDENTITY, key.identity, "The screen shows the PSK share text <identity>:<64 hex>.")
        assertEquals(key, DataOutputSecrets(screen.harness.secrets, profile.id).psk(), "The key is in the SecretStore.")
        assertTrue(screen.harness.sockets.sockets.isEmpty(), "No server socket.")
    }

    @Test
    fun thePlainTextModeNeedsTheWarningFirst() = run { screen, _ ->
        screen.presenter.onSecurity(DataOutputSecurity.PlainText)
        screen.settle()
        assertEquals(DataOutputDialog.PlainTextWarning, screen.state.dialog)
        assertEquals(DataOutputSecurity.Psk, screen.state.draft.security)
        screen.presenter.onDismissDialog()
        screen.settle()
        assertNull(screen.state.dialog)
        assertEquals(DataOutputSecurity.Psk, screen.state.draft.security, "Cancel keeps the encrypted mode.")

        screen.presenter.onSecurity(DataOutputSecurity.PlainText)
        screen.presenter.onConfirmWarning()
        screen.settle()
        assertEquals(DataOutputSecurity.PlainText, screen.state.draft.security)
        assertTrue(screen.state.draft.plainTextConfirmed)
        screen.presenter.onSecurity(DataOutputSecurity.Psk)
        screen.settle()
        assertFalse(screen.state.draft.plainTextConfirmed)
    }

    @Test
    fun aPortThatADifferentOutputUsesIsRefused() = run(
        prepare = {
            profile(name = "Lathe") { stored -> stored.copy(dataOutput = DataOutputConfig(enabled = true, port = 5800)) }
            profile(name = "Mill")
        },
    ) { screen, profile ->
        val problems = screen.state.problemsOf(ProblemTarget.Port)
        assertEquals(listOf("The output of Lathe uses port 5800. Select a different port."), problems.map { text -> text.english() })
        screen.presenter.onEnabledChange(true)
        screen.settle()
        assertFalse(screen.stored(profile).enabled, "The switch does not go on with a port conflict.")
        assertEquals("Correct the problems of the settings first.", screen.state.message!!.text.english())

        screen.presenter.onPortText("5801")
        screen.settle()
        assertTrue(screen.state.problems.isEmpty())
        assertTrue(screen.state.canSave)
        screen.presenter.onSave()
        screen.settle()
        assertEquals(5801, screen.stored(profile).port)

        screen.presenter.onPortText("80")
        screen.settle()
        assertEquals(listOf("The port must be 1024 to 65535."), screen.state.problemsOf(ProblemTarget.Port).map { text -> text.english() })
        assertFalse(screen.state.canSave)
    }

    @Test
    fun aLabelRefusesACharacterOfItsFormat() = run { screen, _ ->
        screen.presenter.onFieldLabel(DataOutputFieldId.XAbsolute, "x;pos")
        screen.settle()
        assertEquals("x_absolute", screen.state.draft.fields.first { field -> field.id == DataOutputFieldId.XAbsolute }.label)
        assertEquals("A label cannot contain the character \";\".", screen.state.labelRefusals.getValue(DataOutputFieldId.XAbsolute).english())
        screen.presenter.onFieldLabel(DataOutputFieldId.XAbsolute, "xpos")
        screen.settle()
        assertEquals("xpos", screen.state.draft.fields.first { field -> field.id == DataOutputFieldId.XAbsolute }.label)
        assertTrue(screen.state.labelRefusals.isEmpty())

        screen.presenter.onFieldLabel(DataOutputFieldId.YAbsolute, "xpos")
        screen.settle()
        assertEquals(
            listOf("More than one enabled field has the label xpos."),
            screen.state.problems.map { problem -> problem.text.english() },
        )

        screen.presenter.onFormat(DataOutputFormat.Json)
        screen.presenter.onFieldLabel(DataOutputFieldId.YAbsolute, "y \"pos\"")
        screen.settle()
        assertEquals("y \"pos\"", screen.state.draft.fields.first { field -> field.id == DataOutputFieldId.YAbsolute }.label, "JSON escapes the label.")
    }

    @Test
    fun theSwitchSavesTheDraftAsksForTheLocalNetworkAndTurnsOffAtOnce() = run { screen, profile ->
        screen.presenter.onActivation(DataOutputActivation.AlwaysOn)
        screen.settle()
        assertEquals(DataOutputActivation.OnDemand, screen.state.draft.activation, "Always on needs the switch on.")

        screen.presenter.onSecurity(DataOutputSecurity.PlainText)
        screen.presenter.onConfirmWarning()
        screen.presenter.onEnabledChange(true)
        screen.settle()
        val stored = screen.stored(profile)
        assertTrue(stored.enabled)
        assertEquals(DataOutputSecurity.PlainText, stored.security, "The switch saves the draft.")
        assertEquals(listOf<ConnectionRequest>(ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))), screen.harness.requests.sent)
        assertTrue(screen.harness.sockets.sockets.isEmpty(), "On demand: no server before the start.")
        assertTrue(screen.state.status.canStartStop)

        screen.presenter.onStartStop()
        screen.settle()
        assertEquals(1, screen.harness.sockets.open.size)
        assertTrue(screen.state.status.running)

        screen.presenter.onActivation(DataOutputActivation.AlwaysOn)
        screen.settle()
        assertEquals(DataOutputActivation.AlwaysOn, screen.state.draft.activation)

        screen.presenter.onEnabledChange(false)
        screen.settle()
        assertFalse(screen.stored(profile).enabled)
        assertTrue(screen.harness.sockets.open.isEmpty(), "The switch off closes the server at once.")
    }

    @Test
    fun makeANewKeyAsksFirstAndReplacesTheStoredKey() = run { screen, profile ->
        val old = screen.state.psk!!
        screen.presenter.onNewKey()
        assertEquals(DataOutputDialog.NewKey, screen.state.dialog)
        screen.presenter.onConfirmNewKey()
        screen.settle()
        val new = screen.state.psk!!
        assertNotEquals(old, new)
        assertEquals(new, DataOutputSecrets(screen.harness.secrets, profile.id).psk()!!.shareText())
        assertEquals("A new key is in use. The clients with the old key get no records.", screen.state.message!!.text.english())
    }

    @Test
    fun theMqttCredentialsGoToTheSecretStoreAndNotToTheConfiguration() = run { screen, profile ->
        screen.presenter.onTransport(DataOutputTransport.Mqtt)
        screen.presenter.onBrokerHost("broker.local")
        screen.presenter.onUsername("dro")
        screen.presenter.onPassword("s3cret")
        screen.settle()
        assertTrue(screen.state.problems.isEmpty(), screen.state.problems.toString())
        screen.presenter.onSave()
        screen.settle()
        val stored = screen.stored(profile)
        assertEquals("broker.local", stored.mqttBrokerHost)
        assertTrue(stored.mqttTls, "TLS to the broker by default.")
        val credentials = DataOutputSecrets(screen.harness.secrets, profile.id).mqttCredentials()!!
        assertEquals("dro", credentials.username)
        assertEquals("s3cret", credentials.password!!.decodeToString())
        assertFalse(org.superdro.core.model.SuperDroJson.encodeToString(DataOutputConfig.serializer(), stored).contains("s3cret"))

        screen.presenter.onTls(false)
        screen.settle()
        assertEquals(DataOutputDialog.MqttNoTlsWarning, screen.state.dialog)
        screen.presenter.onConfirmWarning()
        screen.settle()
        assertFalse(screen.state.draft.mqttTls)
        assertEquals("1883", screen.state.brokerPortText)
    }

    @Test
    fun testConnectionTestsTheDraftAndTheDiagnosticsShowTheLines() = run { screen, _ ->
        screen.presenter.onPortText("5810")
        screen.presenter.onTest()
        screen.settle()
        assertEquals("Port 5810 is free. Clients can connect to 192.168.1.20:5810.", screen.state.testResult!!.text.english())
        assertTrue(screen.state.testResult!!.passed)
        assertTrue(screen.harness.sockets.open.isEmpty(), "The test closes the socket.")

        screen.presenter.onSecurity(DataOutputSecurity.PlainText)
        screen.presenter.onConfirmWarning()
        screen.presenter.onEnabledChange(true)
        screen.settle()
        screen.presenter.onStartStop()
        screen.settle()
        screen.harness.sockets.open.single().connect()
        screen.settle()
        screen.presenter.onShowDiagnostics()
        screen.settle()
        val lines = assertNotNotNull(screen.state.diagnostics)
        assertEquals("192.168.1.20:5810", lines.listening)
        assertEquals(1, lines.clients)
        assertEquals("No record was delivered since the start.", lines.delivery.english())
        screen.presenter.onStopFromDiagnostics()
        screen.settle()
        assertNull(screen.state.dialog)
        assertTrue(screen.harness.sockets.open.isEmpty(), "\"Stop the output\" stops an On demand output.")
    }

    private fun <T : Any> assertNotNotNull(value: T?): T = assertNotNull(value)
}
