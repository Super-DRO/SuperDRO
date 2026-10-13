package org.superdro.core.ui.graphics.measure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.superdro.core.model.AxisAlignmentSource
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.graphics.GraphicsHarness

/**
 * Draws the screens of the measurement mode to PNG files in light and dark mode
 * (`core/ui/build/screenshots/graphics-measure-*.png`).
 */
class MeasurementScreenshotTest {

    private fun suffix(dark: Boolean) = if (dark) "dark" else "light"

    @Test
    fun thePane() {
        listOf(false, true).forEach { dark ->
            val file = ScreenshotRenderer.render("graphics-measure-pane-${suffix(dark)}", widthDp = 600, heightDp = 700) {
                MeasurementSamples.Themed(dark, landscape = false, measurement = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MeasurementModeToggle(on = true, onToggle = {})
                                MeasurementLogButton(count = 2, onClick = {})
                            }
                            MeasurementPane(MeasurementSamples.circle, RecordingMeasurementEvents())
                        }
                    }
                }
            }
            assertTrue(file.length() > 0)
            val partial = ScreenshotRenderer.render("graphics-measure-pane-partial-${suffix(dark)}", widthDp = 600, heightDp = 700) {
                MeasurementSamples.Themed(dark, landscape = false, measurement = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            MeasurementPane(MeasurementSamples.strip, RecordingMeasurementEvents())
                            MeasurementPane(MeasurementSamples.partial, RecordingMeasurementEvents())
                        }
                    }
                }
            }
            assertTrue(partial.length() > 0)
            // The dialog rule: the help text shows in the pane, below the title, not in a dialog.
            val help = ScreenshotRenderer.render("graphics-measure-pane-help-${suffix(dark)}", widthDp = 600, heightDp = 700) {
                MeasurementSamples.Themed(dark, landscape = false, measurement = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Column(Modifier.padding(16.dp)) {
                            MeasurementPane(MeasurementSamples.circle.copy(helpOpen = true), RecordingMeasurementEvents())
                        }
                    }
                }
            }
            assertTrue(help.length() > 0)
        }
    }

    @Test
    fun theInstrumentSheet() {
        listOf(false, true).forEach { dark ->
            val file = ScreenshotRenderer.render("graphics-measure-instrument-${suffix(dark)}", widthDp = 1280, heightDp = 800) {
                MeasurementSamples.Themed(dark, measurement = true) {
                    Box(Modifier.fillMaxSize()) {
                        InstrumentSheet(MeasurementSamples.instrument, onSelect = {}, onNew = {}, onClear = {}, onDismiss = {})
                    }
                }
            }
            assertTrue(file.length() > 0)
        }
    }

    @Test
    fun theAlignSheet() {
        val scope = TestScope()
        val harness = GraphicsHarness(scope)
        scope.launch {
            harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true))
            harness.updateProfile { profile -> profile.copy(machineMode = MachineMode.Measurement) }
            harness.moveTo(0L, x = 60_000L, y = 15_000L, z = 0L)
        }
        scope.testScheduler.advanceUntilIdle()
        val presenter = AlignAxesPresenter(harness.readout, harness.context)
        scope.testScheduler.advanceUntilIdle()
        presenter.onSource(AxisAlignmentSource.MeasuredLine)
        presenter.onPick(AlignPoint.A, PointPick.Custom)
        presenter.onPointX(AlignPoint.A, "10")
        presenter.onPointY(AlignPoint.A, "10")
        scope.testScheduler.advanceUntilIdle()
        listOf(false, true).forEach { dark ->
            val file = ScreenshotRenderer.render("graphics-measure-align-${suffix(dark)}", widthDp = 1280, heightDp = 800) {
                MeasurementSamples.Themed(dark, measurement = true) {
                    Box(Modifier.fillMaxSize()) { AlignAxesSheet(presenter.uiState.value, presenter, onDismiss = {}) }
                }
            }
            assertTrue(file.length() > 0)
        }
        presenter.close()
        harness.close()
    }

    @Test
    fun theLogScreen() {
        listOf(false, true).forEach { dark ->
            val file = ScreenshotRenderer.render("graphics-measure-log-${suffix(dark)}", widthDp = 1280, heightDp = 800) {
                MeasurementSamples.Themed(dark, measurement = true) {
                    MeasurementLogScreen(MeasurementSamples.log, onBack = {}, onExportCsv = {}, onExportSheet = {}, onClearAsk = {}, onClearConfirm = {})
                }
            }
            assertTrue(file.length() > 0)
        }
    }
}
