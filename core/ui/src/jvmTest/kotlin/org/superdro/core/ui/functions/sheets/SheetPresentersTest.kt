package org.superdro.core.ui.functions.sheets

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.model.AuxiliaryConfig
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.CaliperConfig
import org.superdro.core.model.Channels
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.functions.TestEnvironment
import org.superdro.core.ui.functions.common.loadString
import org.superdro.core.ui.readout.FunctionButton
import org.superdro.core.ui.readout.FunctionStripRules

/** The presenters of the measure, compensation, Z reference, and projection sheets. */
class SheetPresentersTest {

    private val scope = TestScope()
    private val harness = FunctionsHarness(scope)

    @AfterTest
    fun close() = harness.close()

    private val metricMill = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true)
    private val metricLathe = MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true)

    @Test
    fun theMeasureSheetShowsTheDistanceTheLineAngleAndTheDirection() = scope.runTest {
        harness.start(metricMill, states = mapOf(AxisId.X to AxisState(metric = true), AxisId.Y to AxisState(metric = true)))
        harness.inputs(AxisId.X to 30_000.0, AxisId.Y to 40_000.0)
        val presenter = AngleMeasurePresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        var state = presenter.uiState.value
        assertEquals("X", state.aLabel)
        assertEquals("Y", state.bLabel)
        assertEquals("50.000", state.distance)
        assertEquals("53.13", state.lineAngle)
        assertEquals("53.13", state.direction)
        assertEquals("36.87", state.complement)
        harness.inputs(AxisId.X to -30_000.0, AxisId.Y to 40_000.0)
        advanceUntilIdle()
        state = presenter.uiState.value
        assertEquals("-53.13", state.lineAngle)
        assertEquals("126.87", state.direction)
        presenter.onUnit(false)
        assertEquals("1.9685", presenter.uiState.value.distance)
        presenter.close()
    }

    @Test
    fun aLatheMeasuresZAndX() = scope.runTest {
        harness.start(metricLathe)
        harness.inputs(AxisId.Z to 30_000.0, AxisId.X to 20_000.0)
        val presenter = AngleMeasurePresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        assertEquals("Z", presenter.uiState.value.aLabel)
        assertEquals("X", presenter.uiState.value.bLabel)
        // X shows the diameter (40 mm): d = sqrt(30² + 40²) = 50 mm.
        assertEquals("50.000", presenter.uiState.value.distance)
        presenter.close()
    }

    @Test
    fun copyToXUsesHalfTheCaliperValueInTheRadiusMode() = scope.runTest {
        harness.start(
            metricLathe.copy(caliper = CaliperConfig(enabled = true)),
            states = mapOf(AxisId.X to AxisState(diameterMode = false), AxisId.Caliper to AxisState(metric = true)),
        )
        harness.inputs(AxisId.X to 3_000.0, AxisId.Z to 1_000.0, AxisId.Caliper to 25_000.0)
        val presenter = DimensionMeasurePresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        assertEquals("25.000", presenter.uiState.value.value)
        assertFalse(presenter.uiState.value.xDiameter)
        assertNull(presenter.onCopyToX())
        advanceUntilIdle()
        assertEquals("12.500", harness.readout.text(AxisId.X))
        // The caliper zero changes the caliper and not Z.
        val zBefore = harness.readout.axisState(AxisId.Z)
        assertNull(presenter.onZero())
        advanceUntilIdle()
        assertEquals("0.000", presenter.uiState.value.value)
        assertEquals(zBefore, harness.readout.axisState(AxisId.Z))
        presenter.close()
    }

    @Test
    fun copyToXNeedsAConnectedSession() = scope.runTest {
        harness.start(metricLathe.copy(caliper = CaliperConfig(enabled = true)), state = SessionState.Disconnected)
        val presenter = DimensionMeasurePresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        assertEquals("No adapter connected", presenter.onCopyToX()!!.loadString(TestEnvironment))
        presenter.close()
    }

    @Test
    fun backLeftWithTenMillimetersGivesPlusAndMinusFiveMillimeters() = scope.runTest {
        harness.start(metricMill)
        val presenter = RadiusCompensationPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        presenter.onDiameter("10")
        presenter.onPosition(ToolPosition.BackLeft)
        assertTrue(presenter.onApply())
        runCurrent()
        assertEquals(5_000.0, harness.readout.axisState(AxisId.X).toolOffset)
        assertEquals(-5_000.0, harness.readout.axisState(AxisId.Y).toolOffset)
        assertEquals(10_000.0, harness.readout.workspace().toolXUm)
        assertNotNull(harness.presenter.uiState.value.undo)
        assertTrue(presenter.uiState.value.active)
        assertTrue(presenter.onClear())
        runCurrent()
        assertFalse(harness.readout.axisState(AxisId.X).toolOffsetActive)
        presenter.close()
    }

    @Test
    fun aLibraryToolGivesItsDiameterAndAnEmptyFieldIsAnError() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.HorizontalMill, "Horizontal").copy(metricDefault = true))
        harness.store.tools.addTool(harness.profile.id, Tool(name = "End Mill", diameterUm = 12_000.0, toothCount = 4, metric = true))
        val presenter = RadiusCompensationPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        assertFalse(presenter.onApply())
        assertEquals("Please enter a valid tool diameter", presenter.uiState.value.error!!.loadString(TestEnvironment))
        val tool = presenter.uiState.value.tools.single()
        presenter.onTool(tool.id)
        assertEquals(12_000.0, presenter.uiState.value.diameter.valueUm)
        presenter.onPosition(ToolPosition.MiddleRight)
        assertTrue(presenter.onApply())
        runCurrent()
        val workspace = harness.readout.workspace()
        assertEquals(tool.id, workspace.activeToolId)
        assertEquals(4, workspace.toothCount)
        assertEquals(-6_000.0, workspace.axisStates.getValue(AxisId.X).toolOffset)
        assertFalse(workspace.axisStates.getValue(AxisId.Y).toolOffsetActive)
        presenter.close()
    }

    @Test
    fun theHeightCompensationSetsTheZToolOffsetAndRefusesZero() = scope.runTest {
        harness.start(metricMill)
        val presenter = HeightCompensationPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        presenter.onZOffset("0")
        assertFalse(presenter.onApply())
        assertEquals("Z offset can't be 0", presenter.uiState.value.error!!.loadString(TestEnvironment))
        presenter.onZOffset("-12.5")
        assertTrue(presenter.onApply())
        runCurrent()
        assertEquals(AxisState(toolOffset = -12_500.0, toolOffsetActive = true), harness.readout.axisState(AxisId.Z))
        assertEquals(-12_500.0, harness.readout.workspace().toolZUm)
        presenter.close()
    }

    @Test
    fun aLatheOffsetOfPlusAndMinusHalfAMillimeterIsValid() = scope.runTest {
        harness.start(metricLathe)
        val presenter = LatheToolOffsetPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        presenter.onXOffset("0")
        presenter.onZOffset("0")
        assertFalse(presenter.onApply())
        assertEquals("Please set an offset on at least one axis", presenter.uiState.value.error!!.loadString(TestEnvironment))
        // "Lathe tool offset": X = 0.5 mm and Z = −0.5 mm is a valid offset.
        presenter.onXOffset("0.5")
        presenter.onZOffset("-0.5")
        assertTrue(presenter.onApply())
        runCurrent()
        assertEquals(500.0, harness.readout.axisState(AxisId.X).toolOffset)
        assertEquals(-500.0, harness.readout.axisState(AxisId.Z).toolOffset)
        assertTrue(presenter.uiState.value.active)
        presenter.close()
    }

    @Test
    fun selectToolKeepsTheAbsoluteXAndZ() = scope.runTest {
        harness.start(metricLathe)
        harness.inputs(AxisId.X to 10_000.0, AxisId.Z to -5_000.0)
        harness.store.tools.addTool(harness.profile.id, Tool(kind = ToolKind.Lathe, name = "Boring Bar", xOffsetUm = 1_000.0, zOffsetUm = 2_000.0, internal = true))
        val presenter = LatheToolOffsetPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        val xBefore = harness.readout.text(AxisId.X)
        val zBefore = harness.readout.text(AxisId.Z)
        val tool = presenter.uiState.value.tools.single()
        presenter.onTool(tool.id)
        assertTrue(presenter.uiState.value.canSelect)
        assertTrue(presenter.onSelectTool())
        runCurrent()
        advanceUntilIdle()
        assertEquals(xBefore, harness.readout.text(AxisId.X))
        assertEquals(zBefore, harness.readout.text(AxisId.Z))
        assertEquals(1_000.0, harness.readout.axisState(AxisId.X).toolOffset)
        assertEquals(tool.id, harness.readout.workspace().activeToolId)
        // The lathe tool of the library keeps "Boring".
        assertTrue(harness.store.tools.loadLibrary(harness.profile.id).single().internal)
        presenter.close()
    }

    @Test
    fun theCompensationButtonsShowOnAHorizontalMillAndALongPressClearsWithUndo() = scope.runTest {
        val horizontal = MachineProfile.create(MachineType.HorizontalMill, "Horizontal").copy(metricDefault = true)
        val buttons = FunctionStripRules.buttons(horizontal, horizontal.workspaces.first()).map { it.button }
        assertTrue(FunctionButton.ZReference in buttons)
        assertTrue(FunctionButton.RadiusCompensation in buttons)
        assertTrue(FunctionButton.HeightCompensation in buttons)
        assertTrue(FunctionButton.EdgeFinder in buttons)
        assertTrue(FunctionButton.ArbitraryHoleCircle in buttons)
        harness.start(horizontal)
        val height = HeightCompensationPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        height.onZOffset("-12.5")
        assertTrue(height.onApply())
        runCurrent()
        advanceUntilIdle()
        val active = harness.presenter.uiState.value.functions.first { it.button == FunctionButton.HeightCompensation }
        assertTrue(active.active)
        harness.presenter.onFunctionLongPress(FunctionButton.HeightCompensation)
        runCurrent()
        assertFalse(harness.readout.axisState(AxisId.Z).toolOffsetActive)
        val undo = assertNotNull(harness.presenter.uiState.value.undo)
        harness.presenter.onUndo(undo.id)
        runCurrent()
        assertEquals(-12_500.0, harness.readout.axisState(AxisId.Z).toolOffset)
        assertTrue(harness.readout.axisState(AxisId.Z).toolOffsetActive)
        height.close()
    }

    @Test
    fun theZReferenceSetsAPlusBAndStoresTheSetterHeight() = scope.runTest {
        harness.start(metricMill, states = mapOf(AxisId.Z to AxisState(relative = true, metric = true)))
        harness.inputs(AxisId.Z to 1_234.0)
        val presenter = ZReferencePresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        assertNull(presenter.uiState.value.block)
        assertFalse(presenter.onApply())
        assertEquals("Tool setter height must be a positive number", presenter.uiState.value.error!!.loadString(TestEnvironment))
        presenter.onSetterHeight("-2")
        assertFalse(presenter.onApply())
        presenter.onSetterHeight("50")
        presenter.onDesiredZ("1/2")
        assertTrue(presenter.onApply())
        advanceUntilIdle()
        assertEquals("50.500", harness.readout.text(AxisId.Z))
        assertFalse(harness.readout.axisState(AxisId.Z).relative)
        val profile = harness.store.machines.loadProfile(harness.profile.id)!!
        assertEquals(50_000.0, profile.zSetterHeightUm)
        assertEquals(true, profile.zSetterMetric)
        presenter.close()
    }

    @Test
    fun theZReferenceIsBlockedWithNoZOrNoAdapter() = scope.runTest {
        harness.start(metricMill.copy(z = metricMill.z.copy(enabled = false)))
        val noZ = ZReferencePresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        assertEquals("Z readout must be enabled", noZ.uiState.value.block!!.loadString(TestEnvironment))
        noZ.close()
        harness.readout.session.state.value = SessionState.Disconnected
        harness.store.machines.updateProfile(harness.profile.id) { it.copy(z = it.z.copy(enabled = true)) }
        val noAdapter = ZReferencePresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        assertEquals("The adapter must be connected", noAdapter.uiState.value.block!!.loadString(TestEnvironment))
        noAdapter.close()
    }

    private fun compoundLathe(vectoring: Boolean = true, type: AuxiliaryType = AuxiliaryType.CompoundSlide, reference: Int = 0) =
        metricLathe.copy(auxiliary = AuxiliaryConfig(type = type, vectoring = vectoring, angleReferenceDegrees = reference))

    @Test
    fun theCompoundSheetGivesTheWeightsAndStoresTheProjection() = scope.runTest {
        harness.start(compoundLathe())
        val presenter = CompoundPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        assertNull(presenter.uiState.value.problem)
        assertEquals("Compound slide", presenter.uiState.value.auxiliaryType!!.loadString(TestEnvironment))
        presenter.onAngle("30")
        assertEquals("0.50000", presenter.uiState.value.xWeight)
        assertEquals("0.86603", presenter.uiState.value.zWeight)
        presenter.onApplyZ(false)
        assertEquals("—", presenter.uiState.value.zWeight)
        presenter.onApplyZ(true)
        assertTrue(presenter.onApply())
        advanceUntilIdle()
        val stored = harness.readout.workspace().compoundProjection
        assertTrue(stored.active)
        assertEquals(108_000.0, stored.angleArcSec)
        assertTrue(presenter.uiState.value.active)
        assertTrue(presenter.onClear())
        advanceUntilIdle()
        assertFalse(harness.readout.workspace().compoundProjection.active)
        presenter.onAngle("400")
        assertFalse(presenter.onApply())
        assertEquals("The angle must be from −360° to 360°.", presenter.uiState.value.error!!.loadString(TestEnvironment))
        presenter.close()
    }

    @Test
    fun eachReferenceAngleHasAnEffect() = scope.runTest {
        // ref 90 and a = 29.5°: a_eff = 60.5°. ref 45 and a = 15°: a_eff = 30°.
        harness.start(compoundLathe(reference = 90))
        val presenter = CompoundPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        presenter.onAngle("29.5")
        assertEquals("0.87036", presenter.uiState.value.xWeight)
        assertEquals("0.49242", presenter.uiState.value.zWeight)
        presenter.close()
        harness.store.machines.updateProfile(harness.profile.id) { it.copy(auxiliary = it.auxiliary.copy(angleReferenceDegrees = 45)) }
        advanceUntilIdle()
        val other = CompoundPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        other.onAngle("15")
        assertEquals("0.50000", other.uiState.value.xWeight)
        other.close()
    }

    @Test
    fun theCompoundSheetTellsWhyTheProjectionHasNoEffect() = scope.runTest {
        harness.start(compoundLathe(type = AuxiliaryType.Tailstock))
        assertFalse(FunctionStripRules.hasCompoundInput(harness.profile))
        val presenter = CompoundPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        assertNotNull(presenter.uiState.value.problem)
        assertFalse(presenter.onApply())
        presenter.close()
        assertNotNull(ProjectionRules.compoundProblem(compoundLathe(vectoring = false)))
        assertNull(ProjectionRules.compoundProblem(compoundLathe()))
        assertTrue(FunctionStripRules.hasCompoundInput(compoundLathe()))
    }

    @Test
    fun theHeadTiltSheetGivesTheWeightsAndRefusesTooFar() = scope.runTest {
        val mill = metricMill.copy(z = metricMill.z.copy(secondary = InputConfig(Channels.W)))
        harness.start(mill)
        val presenter = HeadTiltPresenter(harness.presenter, harness.services)
        advanceUntilIdle()
        assertNull(presenter.uiState.value.problem)
        presenter.onTilt("10")
        presenter.onNod("5")
        assertEquals("-0.17365", presenter.uiState.value.xWeight)
        assertEquals("-0.08716", presenter.uiState.value.yWeight)
        assertEquals("0.98094", presenter.uiState.value.zWeight)
        assertTrue(presenter.onApply())
        advanceUntilIdle()
        assertTrue(harness.readout.workspace().headTilt.active)
        assertEquals(36_000.0, harness.readout.workspace().headTilt.tiltArcSec)
        presenter.onTilt("60")
        presenter.onNod("50")
        assertFalse(presenter.onApply())
        assertEquals(
            "The head cannot lean this far toward X and Y together. Make the tilt angle or the nod angle smaller.",
            presenter.uiState.value.error!!.loadString(TestEnvironment),
        )
        presenter.close()
        assertNotNull(ProjectionRules.headTiltProblem(metricMill))
    }
}
