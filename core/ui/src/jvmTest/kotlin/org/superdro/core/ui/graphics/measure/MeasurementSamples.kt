package org.superdro.core.ui.graphics.measure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.superdro.core.functions.measurement.AddAction
import org.superdro.core.functions.measurement.MeasuredSubDatumName
import org.superdro.core.functions.measurement.MeasurementVerb
import org.superdro.core.functions.measurement.ResultKey
import org.superdro.core.functions.measurement.SlotState
import org.superdro.core.model.ProbeType
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WidthClass
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme
import org.superdro.core.ui.adapter.probe.ProbeFunction
import org.superdro.core.ui.adapter.probe.ProbeOption
import org.superdro.core.ui.adapter.probe.ProbeSelectorUiState

/** The sample states of the measurement screens for the UI tests and the screenshots. */
object MeasurementSamples {

    /** A complete circle set with a probe: the bore of the worked example. */
    val circle = MeasurementUiState(
        available = true,
        modeOn = true,
        instrumentName = "Ball 2 mm",
        verb = MeasurementVerb.Circle,
        slots = (0..2).map { index -> SlotUiState(index, SlotState.Filled, 0) },
        canReset = true,
        note = "Bore A",
        logVisible = true,
        canLog = true,
        results = listOf(
            ResultRowUiState(ResultKey.Center, "20.000, 15.000 mm", AddAction.AddSubDatum(MeasuredSubDatumName.FittedCenter), true),
            ResultRowUiState(ResultKey.Diameter, "Ø 52.000 mm", AddAction.AddCircleToDrawing, true),
        ),
        logCount = 2,
    )

    /** An "Angle Between Lines" set with two points and the step text. */
    val partial = MeasurementUiState(
        available = true,
        modeOn = true,
        verb = MeasurementVerb.AngleBetweenLines,
        slots = listOf(
            SlotUiState(0, SlotState.Filled, 0),
            SlotUiState(1, SlotState.FilledSuspect, 0),
            SlotUiState(2, SlotState.Armed, 1),
            SlotUiState(3, SlotState.Blank, 1),
        ),
        canReset = true,
        message = MeasurementTexts.message(
            org.superdro.core.functions.measurement.MeasurementMessage.Step(org.superdro.core.functions.measurement.MeasurementStep.LocatePoint1OnTheSecondLine),
        ),
        results = listOf(
            ResultRowUiState(ResultKey.IncludedAngle, null, null, false),
            ResultRowUiState(ResultKey.Supplement, null, null, false),
            ResultRowUiState(ResultKey.Corner, null, AddAction.AddSubDatum(MeasuredSubDatumName.Corner), false),
        ),
    )

    /** The panel with no open function: only the function strip. */
    val strip = MeasurementUiState(available = true, modeOn = true, logCount = 0)

    /** The instrument sheet with two instruments and a selection. */
    val instrument = InstrumentUiState(
        selector = ProbeSelectorUiState(
            function = ProbeFunction.MeasurementInstrument,
            options = listOf(
                ProbeOption("ball", "Ball 2 mm", ProbeType.TouchProbe, editable = true),
                ProbeOption("scope", "Microscope", ProbeType.Camera, editable = true),
            ),
            selectedId = "ball",
            loaded = true,
        ),
        xOffsetText = "12.700",
        yOffsetText = "-2.500",
        metric = true,
    )

    /** Two log rows: a circle and a point with a system note. */
    val log = MeasurementLogUiState(
        rows = listOf(
            LogRowUiState("1", 1, "2026-10-11 14:25:30 -0700", "Circle from 3 points", listOf(ResultKey.Center to "20.000, 15.000 mm", ResultKey.Diameter to "Ø 50.000 mm"), "Bore A", ""),
            LogRowUiState("2", 2, "2026-10-11 14:27:02 -0700", "Point", listOf(ResultKey.Position to "0.4724, 0.1181\""), null, "uncompensated"),
        ),
    )

    /** Gives [content] the theme in light or dark mode and a window layout. */
    @Composable
    fun Themed(dark: Boolean, landscape: Boolean = true, measurement: Boolean = false, content: @Composable () -> Unit) {
        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout(if (landscape) WidthClass.Expanded else WidthClass.Medium, landscape)) {
                MeasurementTheme(active = measurement, content = content)
            }
        }
    }
}

/** The events of a test: each call goes into [calls]. */
class RecordingMeasurementEvents : MeasurementEvents {
    val calls = mutableListOf<String>()
    override fun onVerbTap(verb: MeasurementVerb) { calls += "verb ${verb.wire}" }
    override fun onVerbLongPress(verb: MeasurementVerb) { calls += "verbLong ${verb.wire}" }
    override fun onSlotTap(index: Int) { calls += "slot $index" }
    override fun onSlotLongPress(index: Int) { calls += "slotLong $index" }
    override fun onReset() { calls += "reset" }
    override fun onNoteChange(text: String) { calls += "note $text" }
    override fun onHelp(open: Boolean) { calls += "help $open" }
    override fun onLog(title: String) { calls += "log $title" }
    override fun onAdd(key: ResultKey, name: String) { calls += "add ${key.wire} $name" }
}
