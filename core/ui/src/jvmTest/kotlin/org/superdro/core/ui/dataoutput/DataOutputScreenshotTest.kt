package org.superdro.core.ui.dataoutput

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.dataoutput.DataOutputStatus
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.ThemeMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.data_output_error_handshake
import org.superdro.core.resources.data_output_label_character
import org.superdro.core.resources.data_output_last_success
import org.superdro.core.resources.data_output_problem
import org.superdro.core.resources.dataoutput_age_seconds
import org.superdro.core.resources.dataoutput_port_conflict
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.readout.LinkStatus
import org.superdro.core.ui.readout.StatusStrip
import org.superdro.core.ui.readout.StatusStripUiState
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AppTheme

/**
 * Draws the data output screen, its dialogs, and the status chip to PNG files
 * (`core/ui/build/screenshots/dataoutput-*.png`) in light and dark mode, in landscape
 * (1280 × 800 dp) and portrait (800 × 1280 dp). The states are built directly.
 */
class DataOutputScreenshotTest {

    private object NoEvents : DataOutputEvents {
        override fun onSelectProfile(profileId: String) = Unit
        override fun onEnabledChange(enabled: Boolean) = Unit
        override fun onStartStop() = Unit
        override fun onShowDiagnostics() = Unit
        override fun onStopFromDiagnostics() = Unit
        override fun onInterface(bind: BindInterface) = Unit
        override fun onPortText(text: String) = Unit
        override fun onSecurity(mode: DataOutputSecurity) = Unit
        override fun onConfirmWarning() = Unit
        override fun onDismissDialog() = Unit
        override fun onTransport(transport: DataOutputTransport) = Unit
        override fun onActivation(activation: DataOutputActivation) = Unit
        override fun onBrokerHost(host: String) = Unit
        override fun onBrokerPortText(text: String) = Unit
        override fun onTls(tls: Boolean) = Unit
        override fun onUsername(username: String) = Unit
        override fun onPassword(password: String) = Unit
        override fun onClientId(clientId: String) = Unit
        override fun onMachineName(name: String) = Unit
        override fun onTopicLayout(layout: org.superdro.core.model.MqttTopicLayout) = Unit
        override fun onFormat(format: org.superdro.core.model.DataOutputFormat) = Unit
        override fun onDelimiter(delimiter: org.superdro.core.model.DataOutputDelimiter) = Unit
        override fun onLabels(labels: Boolean) = Unit
        override fun onTimestamp(enabled: Boolean) = Unit
        override fun onTimestampFormat(format: org.superdro.core.model.TimestampFormat) = Unit
        override fun onDistanceUnit(unit: org.superdro.core.model.DistanceUnit) = Unit
        override fun onAngleUnit(unit: org.superdro.core.model.AngleUnit) = Unit
        override fun onPrecision(precision: Int) = Unit
        override fun onCadence(cadence: org.superdro.core.model.DataOutputCadence) = Unit
        override fun onIntervalText(text: String) = Unit
        override fun onMaxRateText(text: String) = Unit
        override fun onField(field: org.superdro.core.model.DataOutputFieldId, enabled: Boolean) = Unit
        override fun onFieldLabel(field: org.superdro.core.model.DataOutputFieldId, label: String) = Unit
        override fun onDiscard() = Unit
        override fun onSave() = Unit
        override fun onKeyCopied() = Unit
        override fun onNewKey() = Unit
        override fun onConfirmNewKey() = Unit
        override fun onTest() = Unit
        override fun onMessageShown(id: Long) = Unit
    }

    private val interfaces = listOf(
        InterfaceChoice(BindInterface.WiFi, listOf("192.168.1.20")),
        InterfaceChoice(BindInterface.Localhost, listOf("127.0.0.1")),
        InterfaceChoice(BindInterface.Vpn, emptyList()),
        InterfaceChoice(BindInterface.UsbEthernet, emptyList()),
        InterfaceChoice(BindInterface.All, listOf("192.168.1.20", "127.0.0.1")),
    )

    private val psk = "superdro:00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    private fun sending(): DataOutputUiState {
        val config = DataOutputConfig(enabled = true)
        return DataOutputUiState(
            loading = false,
            profiles = listOf(ProfileChoice("mill", "Mill"), ProfileChoice("lathe", "Lathe")),
            profileId = "mill",
            draft = config,
            stored = config,
            interfaces = interfaces,
            status = OutputStatusUiState(
                status = DataOutputStatus.Sending,
                running = true,
                enabled = true,
                addresses = listOf("192.168.1.20:5800"),
                clients = 2,
                records = 1234,
                sent = 2468,
                failed = 0,
                canStartStop = true,
            ),
            psk = psk,
        )
    }

    private fun plainWithConflict(): DataOutputUiState {
        val stored = DataOutputConfig()
        val draft = stored.copy(security = DataOutputSecurity.PlainText, plainTextConfirmed = true, bindInterface = BindInterface.All)
        return DataOutputUiState(
            loading = false,
            profiles = listOf(ProfileChoice("mill", "Mill")),
            profileId = "mill",
            draft = draft,
            stored = stored,
            interfaces = interfaces,
            problems = listOf(FormProblem(ProblemTarget.Port, UiText.of(Res.string.dataoutput_port_conflict, "Lathe", 5800))),
            status = OutputStatusUiState(blockText = DataOutputTexts.block(org.superdro.core.dataoutput.StartBlock.NotEnabled), addresses = listOf("192.168.1.20:5800")),
        )
    }

    private fun mqttNoTls(): DataOutputUiState {
        val config = DataOutputConfig(
            enabled = true,
            transport = DataOutputTransport.Mqtt,
            mqttBrokerHost = "broker.local",
            mqttBrokerPort = 1883,
            mqttTls = false,
            mqttNoTlsConfirmed = true,
        )
        return DataOutputUiState(
            loading = false,
            profiles = listOf(ProfileChoice("mill", "Mill")),
            profileId = "mill",
            draft = config,
            stored = config,
            brokerPortText = "1883",
            mqttUsername = "dro",
            mqttPassword = "secret",
            interfaces = interfaces,
            status = OutputStatusUiState(
                status = DataOutputStatus.Warning,
                running = true,
                enabled = true,
                transport = DataOutputTransport.Mqtt,
                addresses = listOf("broker.local:1883"),
                brokerTls = false,
                canStartStop = true,
            ),
        )
    }

    private val diagnostics = DiagnosticsUiState(
        running = true,
        listening = "192.168.1.20:5800",
        clients = 1,
        broker = null,
        problem = UiText.of(
            Res.string.data_output_problem,
            UiText.of(Res.string.data_output_error_handshake, "192.168.1.30"),
            UiText.of(Res.string.dataoutput_age_seconds, 4),
        ),
        delivery = UiText.of(Res.string.data_output_last_success, UiText.of(Res.string.dataoutput_age_seconds, 1)),
        counts = null,
        warning = true,
    )

    private val sizes = listOf(Triple("landscape", 1280, 800), Triple("portrait", 800, 1280))

    @Composable
    private fun Themed(dark: Boolean, width: Int, height: Int, content: @Composable () -> Unit) {
        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(width.dp, height.dp)) { content() }
        }
    }

    private fun renderScreen(name: String, firstItem: Int = 0, state: () -> DataOutputUiState) {
        sizes.forEach { (orientation, width, height) ->
            listOf(false, true).forEach { dark ->
                val file = ScreenshotRenderer.render("dataoutput-$name-$orientation-${if (dark) "dark" else "light"}", widthDp = width, heightDp = height, frames = 30) {
                    Themed(dark, width, height) {
                        DataOutputScreen(state(), NoEvents, onBack = {}, onCopyKey = {}, listState = rememberLazyListState(initialFirstVisibleItemIndex = firstItem))
                    }
                }
                assertTrue(file.length() > 0, file.name)
            }
        }
    }

    @Test
    fun theScreenWithARunningEncryptedOutput() = renderScreen("psk-sending") { sending() }

    @Test
    fun theSecurityCardWithThePskAndTheFormat() = renderScreen("psk-key", firstItem = 3) { sending() }

    @Test
    fun theFormatTheCadenceAndTheFields() = renderScreen("fields", firstItem = 4) {
        sending().copy(labelRefusals = mapOf(org.superdro.core.model.DataOutputFieldId.XAbsolute to UiText.of(Res.string.data_output_label_character, "\";\"")))
    }

    @Test
    fun theScreenWithThePlainTextModeAllInterfacesAndAPortConflict() = renderScreen("plain-conflict") { plainWithConflict() }

    @Test
    fun theScreenWithAnMqttLinkWithNoTls() = renderScreen("mqtt-no-tls") { mqttNoTls() }

    @Test
    fun theDialogs() {
        listOf(false, true).forEach { dark ->
            val mode = if (dark) "dark" else "light"
            ScreenshotRenderer.render("dataoutput-dialog-plain-warning-$mode", widthDp = 1280, heightDp = 800, frames = 30) {
                Themed(dark, 1280, 800) { DataOutputScreen(plainWithConflict().copy(dialog = DataOutputDialog.PlainTextWarning), NoEvents, {}, {}) }
            }
        }
    }

    @Test
    fun theDiagnosticsInTheStatusSection() = renderScreen("diagnostics") {
        // The dialog rule: the diagnostics show in the status section of the screen, not in a dialog.
        sending().copy(dialog = DataOutputDialog.Diagnostics, diagnostics = diagnostics)
    }

    @Test
    fun theStatusChipInTheStatusStrip() {
        listOf(false, true).forEach { dark ->
            val file = ScreenshotRenderer.render("dataoutput-chip-${if (dark) "dark" else "light"}", widthDp = 1280, heightDp = 340, frames = 30) {
                Themed(dark, 1280, 800) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Box {
                            androidx.compose.foundation.layout.Column {
                                DataOutputStatus.entries.forEach { status ->
                                    StatusStrip(StatusStripUiState(link = LinkStatus.Connected)) {
                                        DataOutputStatusChip(DataOutputChipUiState(visible = true, status = status), onTap = {}, onLongPress = {})
                                    }
                                }
                            }
                        }
                    }
                }
            }
            assertTrue(file.length() > 0)
        }
    }
}
