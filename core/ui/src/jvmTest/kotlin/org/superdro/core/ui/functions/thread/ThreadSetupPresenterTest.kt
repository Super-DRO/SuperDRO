package org.superdro.core.ui.functions.thread

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.thread.DrillSeries
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PartialInsertPreset
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.model.ThreadInfeedMode
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.functions.TestEnvironment
import org.superdro.core.ui.functions.common.loadString

/** The thread setup. */
class ThreadSetupPresenterTest {

    private val scope = TestScope()
    private val harness = FunctionsHarness(scope)

    @AfterTest
    fun close() = harness.close()

    private suspend fun TestScope.presenter(machineType: MachineType = MachineType.Lathe): ThreadSetupPresenter {
        harness.start(MachineProfile.create(machineType, machineType.name).copy(metricDefault = true))
        return ThreadSetupPresenter(harness.presenter, harness.services).also { advanceUntilIdle() }
    }

    private fun ThreadSetupPresenter.select(form: ThreadForm, size: String, internal: Boolean, cutter: ThreadCutter, threadClass: ThreadClass? = null) {
        onForm(form)
        onInternal(internal)
        onSize(size)
        threadClass?.let(::onClass)
        onCutter(cutter)
    }

    @Test
    fun theWorkedExamplesOfTheDiameterMath() = scope.runTest {
        val setup = presenter()
        val state = setup.uiState
        assertEquals(ThreadForm.IsoMetricCoarse, state.value.form)
        assertTrue(state.value.internal)
        setup.select(ThreadForm.IsoMetricCoarse, "M10 × 1.5", internal = false, cutter = ThreadCutter.SharpV)
        assertEquals(ThreadClass.Class6g, state.value.threadClass)
        var results = assertNotNull(state.value.results)
        assertEquals("Turn to (D)", results.preOpLabel.loadString(TestEnvironment))
        assertEquals("9.968", results.preOp)
        assertEquals("9.732 to 9.968", results.preOpRange.loadString(TestEnvironment))
        assertEquals("Cut to (D₁)", results.cutToLabel.loadString(TestEnvironment))
        assertEquals("7.695", results.cutTo)
        assertNotNull(results.wireDiameter)
        setup.onCutter(ThreadCutter.FullForm)
        results = state.value.results!!
        assertEquals("10.000", results.preOp)
        assertEquals("8.128", results.cutTo)
        setup.select(ThreadForm.IsoMetricCoarse, "M10 × 1.5", internal = true, cutter = ThreadCutter.SharpV)
        assertEquals(ThreadClass.Class6H, state.value.threadClass)
        results = state.value.results!!
        assertEquals("Bore to (D₁)", results.preOpLabel.loadString(TestEnvironment))
        assertEquals("8.376", results.preOp)
        assertEquals("10.325", results.cutTo)
        assertNull(results.wireDiameter)
        setup.onCutter(ThreadCutter.FullForm)
        assertEquals("8.376", state.value.results!!.preOp)
        assertEquals("10.000", state.value.results!!.cutTo)
        setup.select(ThreadForm.Unc, "1/4-20", internal = false, cutter = ThreadCutter.SharpV, threadClass = ThreadClass.Class2A)
        results = state.value.results!!
        assertEquals("0.2489", results.preOp)
        assertEquals("0.1731", results.cutTo)
        setup.onCutter(ThreadCutter.FullForm)
        assertEquals("0.2500", state.value.results!!.preOp)
        assertEquals("0.1876", state.value.results!!.cutTo)
        setup.select(ThreadForm.Unc, "1/4-20", internal = true, cutter = ThreadCutter.SharpV, threadClass = ThreadClass.Class2B)
        assertEquals("0.1959", state.value.results!!.preOp)
        assertEquals("0.2608", state.value.results!!.cutTo)
        setup.onCutter(ThreadCutter.FullForm)
        assertEquals("0.1959", state.value.results!!.preOp)
        assertEquals("0.2500", state.value.results!!.cutTo)
        setup.close()
    }

    @Test
    fun createStoresTheThreadAndItsPointsWithTheGroupKindThread() = scope.runTest {
        val setup = presenter()
        setup.select(ThreadForm.IsoMetricCoarse, "M10 × 1.5", internal = false, cutter = ThreadCutter.SharpV)
        // W₁ is 3 × the pitch until the user types a value.
        assertEquals("4.5", setup.uiState.value.leftRelief.text)
        assertEquals("Thread 1", setup.uiState.value.defaultTag)
        assertNull(setup.create())
        assertEquals("Thread Length must be greater than zero.", setup.uiState.value.error!!.loadString(TestEnvironment))
        setup.onText(ThreadField.Length, "20")
        val done = assertNotNull(setup.create())
        advanceUntilIdle()
        assertEquals("Thread Thread 1 created with 17 points.", done.loadString(TestEnvironment))
        val workspace = harness.readout.workspace()
        val thread = harness.store.machines.threads(workspace.id).first().single()
        assertEquals("M10 × 1.5", thread.size)
        assertEquals(0.0, thread.startZUm)
        assertEquals(-15_500.0, thread.stopZUm)
        assertEquals(4_500.0, thread.leftReliefUm)
        assertNull(thread.rightReliefUm)
        val points = workspace.subDatums
        assertEquals(17, points.size)
        assertTrue(points.all { it.groupKind == SubDatumGroupKind.Thread && it.tags == listOf("Thread_1") })
        val w1 = points.single { it.name == "Thread 1 - W₁" }
        assertEquals(-20_000.0, w1.z)
        assertEquals(4_984.0, points.single { it.name == "Thread 1 - D" }.x)
        setup.close()
    }

    @Test
    fun allFieldsComeBackFromTheLastThread() = scope.runTest {
        val setup = presenter()
        setup.select(ThreadForm.IsoMetricFine, "M10 × 1.25", internal = false, cutter = ThreadCutter.Partial)
        setup.onInsertPreset(PartialInsertPreset.AG60)
        setup.onLeftHand(true)
        setup.onStarts(2)
        setup.onInfeedMode(ThreadInfeedMode.Flank)
        setup.onText(ThreadField.RoughingPasses, "6")
        setup.onText(ThreadField.CustomWireDiameter, "0.75")
        setup.onText(ThreadField.StartZ, "5")
        setup.onText(ThreadField.Length, "18")
        setup.onText(ThreadField.LeftRelief, "3")
        setup.onText(ThreadField.RightRelief, "1")
        setup.onText(ThreadField.Tag, "Shaft")
        assertNotNull(setup.create())
        advanceUntilIdle()
        setup.close()
        val again = ThreadSetupPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        val state = again.uiState.value
        assertEquals(ThreadForm.IsoMetricFine, state.form)
        assertEquals("M10 × 1.25", state.size)
        assertEquals(false, state.internal)
        assertEquals(true, state.leftHand)
        assertEquals(2, state.starts)
        assertEquals(ThreadCutter.Partial, state.cutter)
        assertEquals(PartialInsertPreset.AG60, state.insertPreset)
        assertEquals(ThreadInfeedMode.Flank, state.infeedMode)
        assertEquals("6", state.roughingPasses.text)
        assertEquals("0.06", state.tipRadius.text)
        assertEquals("0.75", state.wireDiameter.text)
        assertEquals("5", state.startZ.text)
        assertEquals("18", state.length.text)
        assertEquals("3", state.leftRelief.text)
        assertEquals("1", state.rightRelief.text)
        assertEquals("", state.tag)
        assertEquals("Thread 1", state.defaultTag)
        assertEquals(1, state.threads.size)
        again.close()
    }

    @Test
    fun aCustomImperialThreadUsesTheTpiAndTheProblemsJoinWithASpace() = scope.runTest {
        val setup = presenter()
        setup.onForm(ThreadForm.CustomImperial)
        assertEquals(org.superdro.core.functions.thread.ThreadUnit.Inch, setup.uiState.value.unit)
        setup.onText(ThreadField.StartZ, "")
        assertNull(setup.create())
        assertEquals(
            "Start (Z) value is required Thread Length must be greater than zero. TPI must be a positive number. " +
                "Minor Diameter must be a positive number. Major Diameter must be a positive number.",
            setup.uiState.value.error!!.loadString(TestEnvironment),
        )
        setup.onText(ThreadField.Tpi, "13")
        setup.onText(ThreadField.Major, "0.5")
        setup.onText(ThreadField.Minor, "0.4")
        setup.onText(ThreadField.StartZ, "0")
        setup.onText(ThreadField.Length, "1")
        assertNotNull(setup.uiState.value.results)
        val done = setup.create()
        advanceUntilIdle()
        assertNotNull(done)
        assertEquals("0.5-13", harness.store.machines.threads(harness.readout.workspace().id).first().single().size)
        setup.close()
    }

    @Test
    fun theSyncButtonCopiesTheZPosition() = scope.runTest {
        val setup = presenter()
        harness.inputs(AxisId.Z to -2_500.0, AxisId.X to 0.0)
        advanceUntilIdle()
        assertNull(setup.onSyncStartZ())
        assertEquals("-2.5", setup.uiState.value.startZ.text)
        setup.onText(ThreadField.StartZ, "++1")
        assertEquals(-1_500.0, setup.uiState.value.startZ.valueUm)
        setup.close()
    }

    @Test
    fun millStartsInTapAndDrillModeAndCalculatesTapAndHolePrep() = scope.runTest {
        val setup = presenter(MachineType.VerticalMill)
        val state = setup.uiState
        assertFalse(state.value.isLathe)
        assertEquals(ThreadToolMode.TapAndDrill, state.value.mode)
        setup.onForm(ThreadForm.IsoMetricCoarse)
        setup.onSize("M10 × 1.5")
        setup.onText(ThreadField.BlindHoleDepth, "15")
        val tap = assertNotNull(state.value.tapResults)
        assertEquals("8.539", tap.cutTheoreticalDiameter)
        assertEquals("8.5 mm", tap.cutClosestDrill.label)
        assertEquals(
            listOf(DrillSeries.Metric, DrillSeries.Fractional, DrillSeries.Letter),
            tap.cutSeriesDrills.map { it.series },
        )
        assertEquals(true, tap.cutWithinMinorLimits)
        assertNotNull(tap.formTheoreticalDiameter)
        assertEquals("10.500", tap.closeClearance)
        assertEquals("11.000", tap.normalClearance)
        assertEquals("12.000", tap.looseClearance)
        assertEquals("10.300", tap.countersink90)
        assertEquals("21.000", tap.blindBottomingDepth)
        assertEquals("24.000", tap.blindPlugDepth)
        assertEquals("30.750", tap.blindTaperDepth)

        // Small taps omit Letter drills; large taps omit Number and Letter drills.
        setup.onSize("M3 × 0.5")
        val m3Tap = assertNotNull(state.value.tapResults)
        assertEquals(
            listOf(DrillSeries.Metric, DrillSeries.Fractional, DrillSeries.Number),
            m3Tap.cutSeriesDrills.map { it.series },
        )
        setup.onSize("M16 × 2")
        val m16Tap = assertNotNull(state.value.tapResults)
        assertEquals(
            listOf(DrillSeries.Metric, DrillSeries.Fractional),
            m16Tap.cutSeriesDrills.map { it.series },
        )
        setup.close()
    }

    @Test
    fun newThreadFormsPassScheduleThreeWireAndInternalGauges() = scope.runTest {
        val setup = presenter(MachineType.Lathe)
        assertTrue(setup.uiState.value.isLathe)
        assertEquals(ThreadToolMode.SinglePoint, setup.uiState.value.mode)

        // All new standard forms populate sizes and calculate geometry.
        for (form in listOf(
            ThreadForm.Bsw,
            ThreadForm.Bsf,
            ThreadForm.Bspp,
            ThreadForm.IsoTrapezoidal,
            ThreadForm.Acme,
            ThreadForm.Npt,
            ThreadForm.Bspt,
        )) {
            setup.onForm(form)
            assertTrue(setup.uiState.value.sizes.isNotEmpty(), "Expected sizes for $form")
            assertNotNull(setup.uiState.value.results, "Expected results for $form")
        }

        // External M10 × 1.5 with multi-start, preset warning, custom 3-wire, and pass schedule.
        setup.select(ThreadForm.IsoMetricCoarse, "M10 × 1.5", internal = false, cutter = ThreadCutter.Partial)
        setup.onStarts(2)
        setup.onInsertPreset(PartialInsertPreset.G60)
        setup.onInfeedMode(ThreadInfeedMode.ModifiedFlank)
        setup.onText(ThreadField.RoughingPasses, "5")
        setup.onText(ThreadField.CustomWireDiameter, "0.895")
        setup.onText(ThreadField.MeasuredOverWires, "10.450")

        var results = assertNotNull(setup.uiState.value.results)
        assertEquals(2, results.starts)
        assertEquals("3.000", results.lead)
        assertTrue(results.insertPresetPitchWarning)
        assertTrue(results.wireValid)
        assertNotNull(results.actualPitchDiameter)
        assertNotNull(results.remainingRadialDepth)
        assertEquals("29.5", results.scheduleInfeedAngleDeg)
        assertEquals(7, results.schedulePasses.size)

        // Internal M10 × 1.5 with Go/No-Go gauges and boring bar deflection warning.
        setup.select(ThreadForm.IsoMetricCoarse, "M10 × 1.5", internal = true, cutter = ThreadCutter.SharpV)
        setup.onText(ThreadField.Length, "35")
        results = assertNotNull(setup.uiState.value.results)
        assertEquals("8.376", results.minorGo)
        assertEquals("8.676", results.minorNoGo)
        assertEquals("9.026", results.pitchGo)
        assertEquals("9.206", results.pitchNoGo)
        assertEquals("08IR", results.internalBarHolder)
        assertTrue(results.internalBarDeflectionRisk)

        setup.close()
    }
}
