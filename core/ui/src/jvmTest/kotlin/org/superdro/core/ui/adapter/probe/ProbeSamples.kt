package org.superdro.core.ui.adapter.probe

import kotlin.time.Instant
import org.superdro.core.functions.tipcal.CalibrationRecord
import org.superdro.core.functions.tipcal.ClockPosition
import org.superdro.core.functions.tipcal.RecordPositionLine
import org.superdro.core.functions.tipcal.RecenterMarker
import org.superdro.core.functions.tipcal.ResultCheckKind
import org.superdro.core.functions.tipcal.SpreadLimit
import org.superdro.core.functions.tipcal.SpreadLimitSource
import org.superdro.core.functions.tipcal.TipCalibrationResult
import org.superdro.core.functions.tipcal.TouchPrompt
import org.superdro.core.model.ProbeType
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_import_action_add
import org.superdro.core.resources.probelib_import_op_add
import org.superdro.core.resources.probelib_import_op_replace
import org.superdro.core.resources.probelib_import_source_touchdro
import org.superdro.core.resources.probelib_import_title
import org.superdro.core.store.files.MessageKey
import org.superdro.core.ui.adapter.common.ImportPreviewUi
import org.superdro.core.ui.adapter.common.ImportReportUi
import org.superdro.core.ui.adapter.common.PreviewLineUi
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** The sample states of the probe screens for the UI tests and the screenshots. */
object ProbeSamples {

    val rows: List<ProbeLibraryRow> = listOf(
        ProbeLibraryRow("own-1", "Renishaw touch probe", ProbeType.TouchProbe, 1987.4, true, 'p', standardTip = false, shared = false, calibrated = true),
        ProbeLibraryRow("own-2", "Tool setter 50 mm", ProbeType.ToolHeightSetter, null, true, 'q', standardTip = false, shared = false, calibrated = false),
        ProbeLibraryRow("tip-1", "Edge finder 0.200\"", ProbeType.EdgeFinder2D, 5080.0, false, null, standardTip = true, shared = true, calibrated = false),
        ProbeLibraryRow("tip-2", "Edge finder 10 mm", ProbeType.EdgeFinder2D, 10000.0, true, null, standardTip = true, shared = true, calibrated = false),
    )

    val library: ProbeLibraryUiState = ProbeLibraryUiState(loaded = true, rows = rows, machineName = "Bridgeport", canTransfer = true)

    val selectors: List<ProbeSelectorUiState> = listOf(
        ProbeSelectorUiState(
            ProbeFunction.EdgeFinder,
            options = listOf(ProbeOption("own-1", "Renishaw touch probe", ProbeType.TouchProbe, true), ProbeOption("tip-1", "Edge finder 0.200\"", ProbeType.EdgeFinder2D, false)),
            selectedId = "own-1",
            loaded = true,
        ),
        ProbeSelectorUiState(ProbeFunction.ZReference, options = listOf(ProbeOption("own-2", "Tool setter 50 mm", ProbeType.ToolHeightSetter, true)), loaded = true),
        ProbeSelectorUiState(ProbeFunction.MeasurementInstrument, loaded = true),
    )

    val newProfile: ProbeProfileUiState = ProbeProfileUiState(
        loaded = true,
        isNew = true,
        form = ProbeProfileForm(type = ProbeType.TouchProbe, metric = true, port = 'p', diameter = "2.000", sampleCount = "3"),
        autoName = true,
        spreadHint = SpreadLimit(20.0, SpreadLimitSource.Default),
        canSync = true,
    )

    val setter: ProbeProfileUiState = ProbeProfileUiState(
        loaded = true,
        isNew = false,
        form = ProbeProfileForm(type = ProbeType.ToolHeightSetter, name = "Tool setter 50 mm", metric = true, port = 'q', zOffset = "50.000"),
        autoName = false,
        spreadHint = SpreadLimit(20.0, SpreadLimitSource.Default),
    )

    val calibrated: ProbeProfileUiState = ProbeProfileUiState(
        loaded = true,
        isNew = false,
        form = ProbeProfileForm(type = ProbeType.TouchProbe, name = "Renishaw touch probe", metric = true, port = 'p', diameter = "1.987", sampleCount = "3", xOffset = "0.000", yOffset = "0.000", zOffset = "-42.150"),
        autoName = false,
        spreadHint = SpreadLimit(10.0, SpreadLimitSource.Calibration),
        calibration = ProbeCalibrationInfo(1987.4, Instant.parse("2026-10-11T13:58:02Z"), 1.1),
        canSync = true,
    )

    /** A complete calibration record of [calibrated]. */
    val record: CalibrationRecord = CalibrationRecord.Complete(
        tipDiameterUm = 1987.4,
        repeatabilityUm = 1.1,
        gaugeUm = 25000.0,
        completed = "2026-10-11 13:58",
        diameterXUm = 1987.0,
        diameterYUm = 1987.8,
        positions = listOf(
            RecordPositionLine(ClockPosition.Nine, 3, 0.4),
            RecordPositionLine(ClockPosition.Three, 3, 0.6),
        ),
        spreadLimitFromRunUm = null,
    )

    /** [calibrated] with its calibration record open in the screen. */
    val calibratedWithRecord: ProbeProfileUiState = calibrated.copy(record = record)

    /** The report after the import of two probe profiles with one warning. */
    val report: ImportReportUi = ImportReportUi(
        title = MessageKey.ReportTitle.toUiText(),
        summary = MessageKey.ReportSummary.toUiText(2, 0, 1),
        sections = listOf(MessageKey.ReportWarnings.toUiText() to listOf(MessageKey.ProbeOtherProfile.toUiText("Bridgeport", "Mill"))),
    )

    val tipGauge: TipCalibrationUiState = TipCalibrationUiState(loaded = true, profileName = "Renishaw touch probe", step = TipStep.Gauge, gaugeText = "25")

    val tipTouches: TipCalibrationUiState = TipCalibrationUiState(
        loaded = true,
        profileName = "Renishaw touch probe",
        port = 'p',
        step = TipStep.Touches,
        clock = ClockPosition.Three,
        captured = setOf(ClockPosition.Nine),
        prompt = TouchPrompt.NextPosition,
        touches = 2,
        touchRefused = true,
    )

    val tipRecenter: TipCalibrationUiState = TipCalibrationUiState(
        loaded = true,
        profileName = "Renishaw touch probe",
        step = TipStep.Recenter,
        captured = setOf(ClockPosition.Nine, ClockPosition.Three),
        marker = RecenterMarker.of(-11500.0, 11500.0),
        liveXUm = 1800.0,
    )

    val tipResult: TipCalibrationUiState = TipCalibrationUiState(
        loaded = true,
        profileName = "Renishaw touch probe",
        nominalUm = 2500.0,
        step = TipStep.Result,
        captured = ClockPosition.procedureOrder.toSet(),
        result = TipCalibrationResult(25000.0, 0.4, 0.1, 11506.3, 0.6, 1.1, 1975.0, 2000.0, 13),
        check = ResultCheckKind.NominalMismatch,
    )

    val importPreview: ImportPreviewUi = ImportPreviewUi(
        title = UiText.of(Res.string.probelib_import_title),
        header = listOf(UiText.of(Res.string.probelib_import_source_touchdro)),
        toImport = listOf(
            PreviewLineUi("Touch probe 2 mm", UiText.of(Res.string.adapter_import_action_add)),
            PreviewLineUi("Tool setter 50.00 mm", UiText.of(Res.string.adapter_import_action_add)),
        ),
        warnings = listOf(MessageKey.ProbeOtherProfile.toUiText("Bridgeport", "Mill")),
        operations = listOf(UiText.of(Res.string.probelib_import_op_add), UiText.of(Res.string.probelib_import_op_replace)),
        canImport = true,
    )
}
