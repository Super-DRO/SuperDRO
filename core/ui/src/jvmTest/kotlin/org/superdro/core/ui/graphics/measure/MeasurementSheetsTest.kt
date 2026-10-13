package org.superdro.core.ui.graphics.measure

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.graphics.AngleMath
import org.superdro.core.functions.measurement.InspectionSheetLabels
import org.superdro.core.functions.measurement.MeasurementFormat
import org.superdro.core.functions.measurement.MeasurementVerb
import org.superdro.core.functions.measurement.SlotState
import org.superdro.core.model.AngularAxisConfig
import org.superdro.core.model.AxisAlignmentSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.resources.Res
import org.superdro.core.resources.measure_align_offset_range
import org.superdro.core.resources.measure_align_same_point
import org.superdro.core.resources.measure_align_typed_range
import org.superdro.core.resources.measure_align_xy_only
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.text.UiText

/** The presenters of the reference instrument sheet, the align axes sheet, and the inspection log. */
class MeasurementSheetsTest {

    private val scope = TestScope()
    private val harness = GraphicsHarness(scope)

    @AfterTest
    fun close() = harness.close()

    private val metricMill = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true)

    private suspend fun startMeasurement(machine: MachineProfile = metricMill) {
        harness.start(machine)
        harness.updateProfile { profile -> profile.copy(machineMode = MachineMode.Measurement) }
        harness.moveTo(0L, x = 0L, y = 0L, z = 0L)
    }

    @Test
    fun theInstrumentSheetListsTheValidProfilesAndWritesTheSelection() = scope.runTest {
        // The sheet works on a horizontal mill too.
        startMeasurement(MachineProfile.create(MachineType.HorizontalMill, "Horizontal").copy(metricDefault = true))
        val probe = harness.probes.saveProbeProfile(
            ProbeProfile(profileId = harness.profile.id, type = ProbeType.TouchProbe, name = "Ball", port = 'p', xOffsetUm = 12_700.0, yOffsetUm = -2_500.0),
        ).valueOrNull()!!
        harness.probes.saveProbeProfile(ProbeProfile(profileId = harness.profile.id, type = ProbeType.ToolHeightSetter, name = "Setter", port = 'q')).valueOrNull()!!
        val presenter = InstrumentPresenter(harness.deviceId, harness.readout, harness.deps)
        advanceUntilIdle()
        // The setter and the six standard tips (no trigger port) are not valid instruments.
        assertEquals(listOf("Ball"), presenter.uiState.value.options.map { option -> option.name })
        assertNull(presenter.uiState.value.selected)
        presenter.onSelect(probe.id)
        scope.advanceUntilIdle()
        assertEquals(probe.id, harness.fullProfile().probeRoles.measurementInstrumentProfileId)
        assertEquals("12.700", presenter.uiState.value.xOffsetText)
        assertEquals("-2.500", presenter.uiState.value.yOffsetText)
        assertEquals(12_700.0, harness.readout.frame.value!!.instrumentOffset(AxisId.X), 1e-9)
        assertTrue(harness.probes.loadProbeProfile(probe.id)!!.lastUsedOn != null)
        presenter.onClear()
        scope.advanceUntilIdle()
        assertNull(harness.fullProfile().probeRoles.measurementInstrumentProfileId)
        assertNull(presenter.uiState.value.selected)
        presenter.close()
    }

    @Test
    fun aNewProfileFromNewProfileBecomesTheInstrument() = scope.runTest {
        startMeasurement()
        val presenter = InstrumentPresenter(harness.deviceId, harness.readout, harness.deps)
        advanceUntilIdle()
        presenter.onNew()
        // A setter is not a valid instrument; the next touch probe with a port is.
        harness.probes.saveProbeProfile(ProbeProfile(profileId = harness.profile.id, type = ProbeType.ToolHeightSetter, name = "Setter", port = 'q')).valueOrNull()!!
        scope.advanceUntilIdle()
        assertNull(harness.fullProfile().probeRoles.measurementInstrumentProfileId)
        val probe = harness.probes.saveProbeProfile(
            ProbeProfile(profileId = harness.profile.id, type = ProbeType.TouchProbe, name = "Ball 3", port = 'p', xOffsetUm = 1_000.0),
        ).valueOrNull()!!
        scope.advanceUntilIdle()
        assertEquals(probe.id, harness.fullProfile().probeRoles.measurementInstrumentProfileId)
        assertEquals("Ball 3", presenter.uiState.value.selected?.name)
        assertEquals("1.000", presenter.uiState.value.xOffsetText)
        presenter.close()
    }

    @Test
    fun twoMeasuredPointsGiveTheWorkedExampleAngle() = scope.runTest {
        startMeasurement()
        val presenter = AlignAxesPresenter(harness.readout, harness.context)
        advanceUntilIdle()
        presenter.onSource(AxisAlignmentSource.MeasuredLine)
        scope.advanceUntilIdle()
        presenter.onPick(AlignPoint.A, PointPick.Custom)
        scope.advanceUntilIdle()
        presenter.onPointX(AlignPoint.A, "10")
        scope.advanceUntilIdle()
        presenter.onPointY(AlignPoint.A, "10")
        scope.advanceUntilIdle()
        presenter.onPick(AlignPoint.B, PointPick.Custom)
        scope.advanceUntilIdle()
        presenter.onPointX(AlignPoint.B, "60")
        scope.advanceUntilIdle()
        presenter.onPointY(AlignPoint.B, "15")
        scope.advanceUntilIdle()
        assertEquals("5.7106", presenter.uiState.value.measuredAngleText)
        assertTrue(presenter.onAlign())
        scope.advanceUntilIdle()
        advanceUntilIdle()
        val alignment = harness.workspace().axisAlignment
        assertTrue(alignment.active)
        assertEquals(AxisAlignmentSource.MeasuredLine, alignment.source)
        assertEquals("5.7106", MeasurementFormat.fixed(AngleMath.arcSecToDegrees(alignment.angleArcSec), 4))
        // The edge from A to B is now parallel to the X readout: A and B have the same Y'.
        harness.moveTo(100L, x = 10_000L, y = 10_000L)
        val a = harness.readout.frame.value!!
        assertEquals(10_945.0, a.reading(AxisId.X).absolute, 1.0)
        assertEquals(8_955.0, a.reading(AxisId.Y).absolute, 1.0)
        harness.moveTo(200L, x = 60_000L, y = 15_000L)
        val b = harness.readout.frame.value!!
        assertEquals(61_195.0, b.reading(AxisId.X).absolute, 1.0)
        assertEquals(8_955.0, b.reading(AxisId.Y).absolute, 1.0)
        assertTrue(presenter.uiState.value.active)
        presenter.onClear()
        scope.advanceUntilIdle()
        assertFalse(harness.workspace().axisAlignment.active)
        assertEquals(0.0, harness.workspace().axisAlignment.angleArcSec)
        assertEquals(AxisAlignmentSource.Typed, harness.workspace().axisAlignment.source)
        presenter.close()
    }

    @Test
    fun theAlignSheetRefusesTheBadInputs() = scope.runTest {
        startMeasurement()
        val presenter = AlignAxesPresenter(harness.readout, harness.context)
        advanceUntilIdle()
        presenter.onAngleText("181")
        scope.advanceUntilIdle()
        assertFalse(presenter.onAlign())
        scope.advanceUntilIdle()
        assertEquals(UiText.of(Res.string.measure_align_typed_range), presenter.uiState.value.error)
        presenter.onAngleText("-12.5")
        scope.advanceUntilIdle()
        assertTrue(presenter.onAlign())
        scope.advanceUntilIdle()
        advanceUntilIdle()
        assertEquals(-45_000.0, harness.workspace().axisAlignment.angleArcSec, 1e-9)
        presenter.onSource(AxisAlignmentSource.MeasuredLine)
        scope.advanceUntilIdle()
        presenter.onPick(AlignPoint.A, PointPick.Origin)
        scope.advanceUntilIdle()
        presenter.onPick(AlignPoint.B, PointPick.Origin)
        scope.advanceUntilIdle()
        assertFalse(presenter.onAlign())
        scope.advanceUntilIdle()
        assertEquals(UiText.of(Res.string.measure_align_same_point), presenter.uiState.value.error)
        harness.updateWorkspace { workspace -> workspace.copy(plane = Plane.XZ) }
        assertFalse(presenter.onAlign())
        scope.advanceUntilIdle()
        assertEquals(UiText.of(Res.string.measure_align_xy_only), presenter.uiState.value.error)
        presenter.close()
    }

    @Test
    fun theAngularEncoderSourceLinksTheAngle() = scope.runTest {
        startMeasurement(metricMill.copy(angular = AngularAxisConfig(enabled = true)))
        val presenter = AlignAxesPresenter(harness.readout, harness.context)
        advanceUntilIdle()
        assertTrue(presenter.uiState.value.angularEnabled)
        presenter.onSource(AxisAlignmentSource.AngularEncoder)
        scope.advanceUntilIdle()
        presenter.onOffsetText("400")
        scope.advanceUntilIdle()
        assertFalse(presenter.onAlign())
        scope.advanceUntilIdle()
        assertEquals(UiText.of(Res.string.measure_align_offset_range), presenter.uiState.value.error)
        presenter.onOffsetText("10")
        scope.advanceUntilIdle()
        assertTrue(presenter.onAlign())
        scope.advanceUntilIdle()
        advanceUntilIdle()
        val alignment = harness.workspace().axisAlignment
        assertEquals(AxisAlignmentSource.AngularEncoder, alignment.source)
        assertEquals(36_000.0, alignment.encoderOffsetArcSec, 1e-9)
        // An alignment that uses the angular encoder makes the angular axis read absolute.
        assertTrue(harness.readout.frame.value!!.angularLocked)
        presenter.close()
    }

    @Test
    fun theLogExportsTheCsvAndTheTextSheet() = scope.runTest {
        startMeasurement()
        val controller = MeasurementController(harness.deviceId, harness.readout, harness.deps)
        advanceUntilIdle()
        controller.onVerbTap(MeasurementVerb.Distance)
        scope.advanceUntilIdle()
        harness.moveTo(100L, x = 0L, y = 0L)
        controller.onSlotTap(0)
        scope.advanceUntilIdle()
        harness.moveTo(200L, x = 30_000L, y = 40_000L)
        controller.onSlotTap(1)
        scope.advanceUntilIdle()
        assertEquals(listOf(SlotState.Filled, SlotState.Filled), controller.uiState.value.slots.map { slot -> slot.state })
        controller.onNoteChange("Slot \"A\"")
        scope.advanceUntilIdle()
        controller.onLog("Point-to-Point Distance")
        scope.advanceUntilIdle()
        // The log shows the stored format, also after a change of the display unit.
        harness.updateWorkspace { workspace ->
            workspace.copy(axisStates = workspace.axisStates + (AxisId.X to workspace.axisStates.getValue(AxisId.X).copy(metric = false)))
        }
        val log = MeasurementLogPresenter(harness.readout, harness.deps)
        advanceUntilIdle()
        val row = log.uiState.value.rows.single()
        assertEquals(1, row.sequence)
        assertEquals("2026-10-12 08:00:00 +0000", row.timeText)
        assertEquals("50.000 mm", row.values.first().second)
        assertFalse(row.warn)
        log.onExportCsv()
        scope.advanceUntilIdle()
        val (name, mime, bytes) = harness.filePicker.created.single()
        assertTrue(name.startsWith("superdro-inspection_log-Default_Workspace-Mill-mill-"), name)
        assertTrue(name.endsWith(".csv"), name)
        assertEquals("text/csv", mime)
        val lines = bytes.decodeToString().split("\r\n")
        assertEquals("", lines.last())
        val header = lines[0].split("\",\"")
        assertEquals(23, header.size)
        assertTrue(lines[0].startsWith("\"Seq\",\"Timestamp\""), lines[0])
        assertTrue(lines[1].startsWith("\"1\",\"2026-10-12 08:00:00 +0000\",\"Point-to-Point Distance\""), lines[1])
        assertTrue(lines[1].contains("\"Slot \"\"A\"\"\""), lines[1])
        log.onExportSheet(InspectionSheetLabels())
        scope.advanceUntilIdle()
        val (sheetName, sheetMime, sheet) = harness.filePicker.created[1]
        assertTrue(sheetName.endsWith(".txt"))
        assertEquals("text/plain", sheetMime)
        assertTrue(sheet.decodeToString().contains("  Distance    50.000 mm"), sheet.decodeToString())
        log.onClearAsk(true)
        scope.advanceUntilIdle()
        assertTrue(log.uiState.value.clearAsked)
        log.onClearConfirm()
        scope.advanceUntilIdle()
        assertTrue(log.uiState.value.rows.isEmpty())
        assertFalse(log.uiState.value.clearAsked)
        log.close()
        controller.close()
    }
}
