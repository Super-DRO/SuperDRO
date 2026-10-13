package org.superdro.core.ui.functions.thread

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.functions.TestEnvironment
import org.superdro.core.ui.functions.common.loadString

/** The thread setup. */
class ThreadSetupPresenterTest {

    private val scope = TestScope()
    private val harness = FunctionsHarness(scope)

    @AfterTest
    fun close() = harness.close()

    private suspend fun TestScope.presenter(): ThreadSetupPresenter {
        harness.start(MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true))
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
        setup.onText(ThreadField.TipRadius, "0.1")
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
        assertEquals(ThreadCutter.Partial, state.cutter)
        assertEquals("0.1", state.tipRadius.text)
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
}
