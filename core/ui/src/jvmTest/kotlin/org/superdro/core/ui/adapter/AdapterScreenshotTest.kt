package org.superdro.core.ui.adapter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.model.DeclaredInput
import org.superdro.core.model.ProbeContactType
import org.superdro.core.model.ThemeMode
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adapter.details.AdapterDetailsEvents
import org.superdro.core.ui.adapter.details.AdapterDetailsScreen
import org.superdro.core.ui.adapter.details.GearTarget
import org.superdro.core.ui.adapter.identity.NewAdapterDialog
import org.superdro.core.ui.adapter.identity.NewAdapterEvents
import org.superdro.core.ui.adapter.inputs.ProbeInputSheet
import org.superdro.core.ui.adapter.inputs.TachPulsesSheet
import org.superdro.core.ui.adapter.list.AdapterRowUi
import org.superdro.core.ui.adapter.list.AdaptersEvents
import org.superdro.core.ui.adapter.list.AdaptersScreen
import org.superdro.core.ui.adapter.list.OfflineAdapterEvents
import org.superdro.core.ui.adapter.list.OfflineAdapterScreen
import org.superdro.core.ui.adapter.list.OfflineRowTarget
import org.superdro.core.ui.adapter.profile.ProfileActionsUiState
import org.superdro.core.ui.adapter.profile.ProfileDialog
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/** The events of the adapter screens that do nothing, for the screenshots. */
object NoAdapterEvents : AdapterDetailsEvents, AdaptersEvents, OfflineAdapterEvents, NewAdapterEvents {
    override fun onSelectMode(channel: InputChannel, mode: InputMode) = Unit
    override fun onCancel(channel: InputChannel) = Unit
    override fun onSave(channel: InputChannel) = Unit
    override fun onReference(channel: InputChannel) = Unit
    override fun onClear(channel: InputChannel) = Unit
    override fun onGear(target: GearTarget) = Unit
    override fun onRestart() = Unit
    override fun onRestartDialogDismissed() = Unit
    override fun onCreateProfile() = Unit
    override fun onRow(row: AdapterRowUi) = Unit
    override fun onCreate() = Unit
    override fun onCreateName(text: String) = Unit
    override fun onCreateToggle(input: DeclaredInput) = Unit
    override fun onCreateConfirm() = Unit
    override fun onCreateDismiss() = Unit
    override fun onEditAlias() = Unit
    override fun onAliasText(text: String) = Unit
    override fun onSaveAlias() = Unit
    override fun onCancelAlias() = Unit
    override fun onToggleDeclared(input: DeclaredInput) = Unit
    override fun onRow(target: OfflineRowTarget) = Unit
    override fun onName(text: String) = Unit
    override fun onUse(profileId: String?) = Unit
    override fun onConfirm() = Unit
    override fun onDisconnect() = Unit
    override fun onDismiss() = Unit
}

/**
 * Draws the screens of the adapter area to PNG files (`core/ui/build/screenshots/adapter-*.png`) in
 * light and dark mode, in landscape (1280 × 800) and portrait (800 × 1280).
 */
class AdapterScreenshotTest {

    private val sizes = listOf("landscape" to (1280 to 800), "portrait" to (800 to 1280))

    private fun renderAll(name: String, content: @Composable () -> Unit) {
        sizes.forEach { (orientation, size) ->
            val (width, height) = size
            listOf(false, true).forEach { dark ->
                val file: File = ScreenshotRenderer.render("adapter-$name-$orientation-${if (dark) "dark" else "light"}", widthDp = width, heightDp = height, frames = 30) {
                    AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
                        CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(width.dp, height.dp)) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { Box(Modifier.fillMaxSize()) { content() } }
                        }
                    }
                }
                assertTrue(file.length() > 0, file.name)
            }
        }
    }

    @Test
    fun theAdapterDetails() {
        renderAll("details") {
            AdapterDetailsScreen(AdapterSamples.details, ProfileActionsUiState(), AdapterSamples.RecordingActions(), NoAdapterEvents, onBack = {})
        }
    }

    @Test
    fun theAdapterDetailsOfALegacyAdapter() {
        renderAll("details-legacy") {
            AdapterDetailsScreen(AdapterSamples.legacyDetails, ProfileActionsUiState(), AdapterSamples.RecordingActions(), NoAdapterEvents, onBack = {})
        }
    }

    @Test
    fun theAdaptersAndTheOfflineDetails() {
        renderAll("list") { AdaptersScreen(AdapterSamples.adapters, ProfileActionsUiState(), AdapterSamples.RecordingActions(), NoAdapterEvents, onBack = {}) }
        renderAll("offline") { OfflineAdapterScreen(AdapterSamples.offline, ProfileActionsUiState(), AdapterSamples.RecordingActions(), NoAdapterEvents, onBack = {}) }
    }

    @Test
    fun theReportOfAProfileImportInTheScreens() {
        // The dialog rule: the report shows at the top of the screen, not in a dialog.
        val report = ProfileActionsUiState(dialog = ProfileDialog.Report(AdapterSamples.importReport))
        renderAll("details-report") { AdapterDetailsScreen(AdapterSamples.details, report, AdapterSamples.RecordingActions(), NoAdapterEvents, onBack = {}) }
        renderAll("list-report") { AdaptersScreen(AdapterSamples.adapters, report, AdapterSamples.RecordingActions(), NoAdapterEvents, onBack = {}) }
        renderAll("offline-report") { OfflineAdapterScreen(AdapterSamples.offline, report, AdapterSamples.RecordingActions(), NoAdapterEvents, onBack = {}) }
    }

    @Test
    fun theNewAdapterPrompt() {
        renderAll("prompt-legacy") { NewAdapterDialog(AdapterSamples.legacyPrompt, NoAdapterEvents) }
        renderAll("prompt-differs") { NewAdapterDialog(AdapterSamples.differsPrompt, NoAdapterEvents) }
    }

    @Test
    fun theTachPulsesAndTheProbeInputSheets() {
        renderAll("tach") { TachPulsesSheet(AdapterSamples.tach, onText = {}, onSet = {}, onDismiss = {}) }
        renderAll("probe-input") {
            ProbeInputSheet(AdapterSamples.probe, onContactType = { _: ProbeContactType -> }, onDebounce = {}, onSave = {}, onDismiss = {})
        }
    }
}
