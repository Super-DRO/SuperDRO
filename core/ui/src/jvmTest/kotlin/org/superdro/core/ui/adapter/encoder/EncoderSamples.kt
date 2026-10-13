package org.superdro.core.ui.adapter.encoder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import org.superdro.core.functions.errormap.ErrorMapBuild
import org.superdro.core.functions.errormap.ErrorMapBuilder
import org.superdro.core.functions.errormap.ErrorMapDiagnostics
import org.superdro.core.functions.errormap.ErrorMapReport
import org.superdro.core.functions.errormap.MapEditor
import org.superdro.core.functions.errormap.MapKind
import org.superdro.core.functions.errormap.MapProblem
import org.superdro.core.functions.errormap.MapProvenance
import org.superdro.core.functions.errormap.PassRowInfo
import org.superdro.core.functions.errormap.WizardContext
import org.superdro.core.functions.errormap.WizardSlot
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MachineType
import org.superdro.core.model.MapPoint
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.adapter.encoder.map.MapEditorTab
import org.superdro.core.ui.adapter.encoder.map.MapEditorTexts
import org.superdro.core.ui.adapter.encoder.map.MapEditorUiState
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapDiagnosticsUiState
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapReportUiState
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapWizardUiState
import org.superdro.core.ui.adapter.encoder.wizard.PassRowUi
import org.superdro.core.ui.adapter.encoder.wizard.WizardTestData
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/** The sample states of the screens of the encoder area, for the UI tests and the screenshots. */
object EncoderSamples {

    /** The encoder settings of a calibrated linear scale with a hysteresis and an error map. */
    fun settings(): EncoderSettingsUiState {
        val profile = EncoderProfile.defaultFor('x').copy(resolution = 5080.0, reversed = true, backlashMode = BacklashMode.Constant, backlashCounts = 3)
        val form = EncoderSettingsRules.formOf(profile)
        return EncoderSettingsUiState(
            input = 'x',
            online = true,
            loaded = true,
            available = true,
            adapterName = "Bridgeport adapter",
            form = form,
            visibility = EncoderSettingsRules.visibility(form, 'x'),
            calibration = CalibrationUi(CalibrationStatus.Calibrated, "2026-10-01 09:12", MachineType.VerticalMill, AxisId.X),
            errorMap = MapSummaryLine(10, 0, 100000),
        )
    }

    /** The encoder settings of a leadscrew encoder with a backlash map and a map problem. */
    fun leadscrewSettings(): EncoderSettingsUiState {
        val profile = EncoderProfile.defaultFor('z').copy(
            type = EncoderType.LeadscrewEncoder,
            resolution = 10160.0,
            countsPerScrewRev = 2000.0,
            backlashMode = BacklashMode.Map,
        )
        val form = EncoderSettingsRules.formOf(profile).copy(resolution = "0")
        return EncoderSettingsUiState(
            input = 'z',
            online = false,
            loaded = true,
            available = true,
            adapterName = "Lathe adapter",
            form = form,
            visibility = EncoderSettingsRules.visibility(form, 'z'),
            errors = EncoderSettingsRules.errors(form),
            backlashMap = MapSummaryLine(4, -2000, 30000),
            problems = listOf(MapEditorTexts.problem(MapProblem.Reversal(10000, 20000))),
        )
    }

    /** The settings of an angular input with the cyclic switch. */
    fun angularSettings(): EncoderSettingsUiState {
        val profile = EncoderProfile.defaultFor('w', EncoderKind.Angular).copy(resolution = 7200.0)
        val form = EncoderSettingsRules.formOf(profile)
        return EncoderSettingsUiState(
            input = 'w', online = true, loaded = true, available = true, adapterName = "Rotary table",
            form = form, visibility = EncoderSettingsRules.visibility(form, 'w'),
        )
    }

    /** A smooth error map of 21 points. */
    val sinePoints: List<MapPoint> = (0..20).map { index ->
        val position = index * 5000L
        MapPoint(position, (6.0 * sin(2.0 * PI * position / 40000.0)).roundToLong() + index / 4)
    }

    /** The map editor with [tab]. */
    fun mapEditor(tab: MapEditorTab = MapEditorTab.Points, readOnly: Boolean = false): MapEditorUiState {
        val provenance = if (readOnly) {
            MapProvenance(MapProvenance.SUPERDRO_WIZARD, "2026-10-12 08:00:00", 3, 23.0, 4.0)
        } else {
            MapProvenance(notes = listOf("reference input w"))
        }
        return MapEditorUiState(
            input = 'x',
            kind = MapKind.Error,
            online = true,
            loaded = true,
            available = true,
            adapterName = "Bridgeport adapter",
            rows = sinePoints,
            provenance = provenance,
            readOnly = readOnly,
            summary = MapEditor.summary(sinePoints, provenance),
            dirty = !readOnly,
            tab = tab,
            wizardAvailable = true,
        )
    }

    /** The wizard while pass 2 records. */
    fun wizard(): ErrorMapWizardUiState = ErrorMapWizardUiState(
        input = 'x',
        loaded = true,
        available = true,
        connected = true,
        references = listOf('y', 'w'),
        reference = 'w',
        liveTarget = 48210,
        liveReference = 48236,
        recording = WizardSlot.Pass(1),
        recordingSamples = 1240,
        passes = listOf(PassRowUi(PassRowInfo(1903, 95000, 0))) + List(4) { PassRowUi(null) },
        canStartPass = false,
        canStartVerification = false,
        canReview = true,
    )

    /** The report of two good passes and a verification pass. */
    fun report(): ErrorMapReport {
        val session = WizardTestData.goodSession("adapter")
        val build = ErrorMapBuilder.build(session.passes.map { it.toPass() }, session.verification?.toPass())
        return (build as ErrorMapBuild.Built).report
    }

    fun reportState(): ErrorMapReportUiState = ErrorMapReportUiState(input = 'x', load = null, report = report())

    fun diagnosticsState(): ErrorMapDiagnosticsUiState {
        val session = WizardTestData.goodSession("adapter")
        val passes = session.passes.map { it.toPass() }
        val context = WizardContext("1.0.0", "Bridgeport adapter", "1.4.2, 2026-09-01", 'w')
        return ErrorMapDiagnosticsUiState(input = 'x', load = null, diagnostics = ErrorMapDiagnostics.of(report(), passes, context))
    }

    /** The theme and the window of a test. */
    @Composable
    fun Themed(dark: Boolean, width: Int = 1280, height: Int = 800, content: @Composable () -> Unit) {
        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(width.dp, height.dp)) { content() }
        }
    }
}
