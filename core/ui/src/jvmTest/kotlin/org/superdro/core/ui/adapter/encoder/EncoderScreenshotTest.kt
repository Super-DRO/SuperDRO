package org.superdro.core.ui.adapter.encoder

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adapter.encoder.map.MapEditorEvents
import org.superdro.core.ui.adapter.encoder.map.MapEditorScreen
import org.superdro.core.ui.adapter.encoder.map.MapEditorTab
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapDiagnosticsScreen
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapReportScreen
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapWizardEvents
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapWizardScreen

/**
 * Draws the screens of the encoder area to PNG files (`core/ui/build/screenshots/encoder-*.png`,
 * `mapeditor-*.png`, `errormap-*.png`) in light and dark mode, at 1280 × 800 (landscape) and
 * 800 × 1280 (portrait).
 */
class EncoderScreenshotTest {

    private val sizes = listOf("landscape" to (1280 to 800), "portrait" to (800 to 1280))

    private fun renderAll(name: String, content: @Composable () -> Unit) {
        sizes.forEach { (orientation, size) ->
            listOf(false, true).forEach { dark ->
                val file = ScreenshotRenderer.render("$name-$orientation-${if (dark) "dark" else "light"}", widthDp = size.first, heightDp = size.second, frames = 30) {
                    EncoderSamples.Themed(dark, size.first, size.second, content)
                }
                assertTrue(file.length() > 0, file.name)
            }
        }
    }

    private val noEncoderEvents = object : EncoderSettingsEvents {
        override fun onType(type: org.superdro.core.model.EncoderType) = Unit
        override fun onKind(kind: org.superdro.core.model.EncoderKind) = Unit
        override fun onResolution(text: String) = Unit
        override fun onCountsPerScrewRev(text: String) = Unit
        override fun onReversed(reversed: Boolean) = Unit
        override fun onFilter(filter: Boolean) = Unit
        override fun onHysteresis(text: String) = Unit
        override fun onBacklashMode(mode: org.superdro.core.model.BacklashMode) = Unit
        override fun onBacklashConstant(text: String) = Unit
        override fun onCyclic(cyclic: Boolean) = Unit
        override fun onConfirmDeleteBacklashMap() = Unit
        override fun onCancelDeleteBacklashMap() = Unit
        override fun onCalibrate() = Unit
        override fun onEditErrorMap() = Unit
        override fun onEditBacklashMap() = Unit
    }

    private val noMapEvents = object : MapEditorEvents {
        override fun onTab(tab: MapEditorTab) = Unit
        override fun onAddRow() = Unit
        override fun onEditRow(index: Int) = Unit
        override fun onRowPosition(text: String) = Unit
        override fun onRowValue(text: String) = Unit
        override fun onSaveRow() = Unit
        override fun onDeleteRow() = Unit
        override fun onCancelRow() = Unit
        override fun onImport() = Unit
        override fun onPaste() = Unit
        override fun onExport() = Unit
        override fun onClear() = Unit
        override fun onSave() = Unit
        override fun onConfirmReplace() = Unit
        override fun onCancelReplace() = Unit
        override fun onConfirmClear() = Unit
        override fun onCancelClear() = Unit
        override fun onWizard() = Unit
        override fun onConfirmWizard() = Unit
        override fun onCancelWizard() = Unit
    }

    private val noWizardEvents = object : ErrorMapWizardEvents {
        override fun onReference(reference: Char) = Unit
        override fun onStartPass() = Unit
        override fun onStartVerification() = Unit
        override fun onStop() = Unit
        override fun onClearPass(index: Int) = Unit
        override fun onClearVerification() = Unit
        override fun onReview() = Unit
        override fun onDiscard() = Unit
        override fun onConfirmDiscard() = Unit
        override fun onCancelDiscard() = Unit
        override fun onExit() = Unit
        override fun onRecover() = Unit
        override fun onDropRecovery() = Unit
    }

    @Test
    fun theEncoderSettings() {
        renderAll("encoder-settings") { EncoderSettingsScreen(EncoderSamples.settings(), noEncoderEvents, onBack = {}) }
        renderAll("encoder-leadscrew") { EncoderSettingsScreen(EncoderSamples.leadscrewSettings(), noEncoderEvents, onBack = {}) }
        renderAll("encoder-angular") { EncoderSettingsScreen(EncoderSamples.angularSettings(), noEncoderEvents, onBack = {}) }
    }

    @Test
    fun theMapEditor() {
        renderAll("mapeditor-points") { MapEditorScreen(EncoderSamples.mapEditor(MapEditorTab.Points), noMapEvents, onBack = {}) }
        renderAll("mapeditor-summary") { MapEditorScreen(EncoderSamples.mapEditor(MapEditorTab.Summary, readOnly = true), noMapEvents, onBack = {}) }
        renderAll("mapeditor-curve") { MapEditorScreen(EncoderSamples.mapEditor(MapEditorTab.Curve), noMapEvents, onBack = {}) }
    }

    @Test
    fun theErrorMapWizardTheReportAndTheDiagnostics() {
        renderAll("errormap-wizard") { ErrorMapWizardScreen(EncoderSamples.wizard(), noWizardEvents, onBack = {}) }
        renderAll("errormap-report") { ErrorMapReportScreen(EncoderSamples.reportState(), onSave = {}, onDiagnostics = {}, onBack = {}) }
        renderAll("errormap-diagnostics") { ErrorMapDiagnosticsScreen(EncoderSamples.diagnosticsState(), onCopied = {}, onBack = {}) }
    }
}
