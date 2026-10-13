package org.superdro.core.ui.adapter.probe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adapter.common.ImportPreviewSheet
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/**
 * Draws the probe screens to PNG files (`core/ui/build/screenshots/probelib-*.png`) in light and
 * dark mode, in landscape (1280 × 800) and portrait (800 × 1280).
 */
class ProbeScreenshotTest {

    private val events = RecordingProbeEvents()

    @Test
    fun theProbeLibrary() = renderAll("library") { ProbeLibraryScreen(ProbeSamples.library, ProbeSamples.selectors, events) }

    @Test
    fun aNewTouchProbe() = renderAll("profile-new") { ProbeProfileScreen(ProbeSamples.newProfile, events) }

    @Test
    fun aToolHeightSetter() = renderAll("profile-setter") { ProbeProfileScreen(ProbeSamples.setter, events) }

    @Test
    fun aCalibratedTouchProbe() = renderAll("profile-calibrated") { ProbeProfileScreen(ProbeSamples.calibrated, events) }

    @Test
    fun theLibraryWithTheReportAndTheStandardTipNotice() = renderAll("library-info") {
        // The dialog rule: the report and the notice show in the list, not in dialogs.
        ProbeLibraryScreen(
            ProbeSamples.library.copy(report = ProbeSamples.report, standardTipNotice = true, standardTipNoticeId = "tip-1"),
            emptyList(),
            events,
        )
    }

    @Test
    fun aCalibratedTouchProbeWithItsRecord() = renderAll("profile-record") {
        // The dialog rule: the calibration record shows in the screen, not in a dialog.
        ProbeProfileScreen(ProbeSamples.calibratedWithRecord, events)
    }

    @Test
    fun theRingGaugeStep() = renderAll("tip-gauge") { TipCalibrationScreen(ProbeSamples.tipGauge, events) }

    @Test
    fun theTouchStep() = renderAll("tip-touches") { TipCalibrationScreen(ProbeSamples.tipTouches, events) }

    @Test
    fun theRecenterStep() = renderAll("tip-recenter") { TipCalibrationScreen(ProbeSamples.tipRecenter, events) }

    @Test
    fun theResult() = renderAll("tip-result") { TipCalibrationScreen(ProbeSamples.tipResult, events) }

    @Test
    fun theImportPreview() = renderAll("import") {
        ProbeLibraryScreen(ProbeSamples.library, ProbeSamples.selectors, events)
        ImportPreviewSheet(ProbeSamples.importPreview, {}, {}, {})
    }

    private fun renderAll(name: String, content: @Composable () -> Unit) {
        listOf("landscape" to (1280 to 800), "portrait" to (800 to 1280)).forEach { (orientation, size) ->
            listOf(false, true).forEach { dark ->
                val file = ScreenshotRenderer.render("probelib-$name-$orientation-${if (dark) "dark" else "light"}", widthDp = size.first, heightDp = size.second, frames = 30) {
                    AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
                        CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(size.first.dp, size.second.dp)) { content() }
                    }
                }
                assertTrue(file.length() > 0, name)
            }
        }
    }
}
