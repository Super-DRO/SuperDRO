package org.superdro.core.ui.graphics.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.probe.CoordinateMode
import org.superdro.core.functions.probe.EdgeSide
import org.superdro.core.functions.probe.EdgeStatus
import org.superdro.core.functions.probe.FeatureType
import org.superdro.core.functions.probe.OriginOption
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeConfig
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.resources.Res
import org.superdro.core.resources.edgefinder_not_connected
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.text.UiText

/** The presenter tests of the edge finder "Indicate Workpiece". */
class EdgeFinderPresenterTest {

    private suspend fun TestScope.started(
        harness: GraphicsHarness,
        type: MachineType = MachineType.VerticalMill,
        probe: ProbeConfig = ProbeConfig(enabled = true),
        selected: ProbeProfile? = null,
        mode: MachineMode = MachineMode.Cutting,
        instrument: ProbeProfile? = null,
    ): EdgeFinderPresenter {
        harness.start(probeMachine(type, probe))
        val stored = selected?.let { probeProfile -> harness.storeProbe(probeProfile) }
        val storedInstrument = instrument?.let { probeProfile -> harness.storeProbe(probeProfile) }
        harness.updateProfile { profile ->
            profile.copy(
                machineMode = mode,
                probeRoles = profile.probeRoles.copy(
                    edgeFinderProfileId = stored?.id,
                    measurementInstrumentProfileId = storedInstrument?.id,
                ),
            )
        }
        harness.moveTo(0L, x = 0L, y = 0L, z = 0L)
        val presenter = EdgeFinderPresenter(harness.deviceId, harness.readout, harness.deps)
        advanceUntilIdle()
        return presenter
    }

    private fun EdgeFinderPresenter.edge(side: EdgeSide) = uiState.value.edge(side)

    @Test
    fun aTapWithTheProbeEdgeFinderOffCapturesTheCurrentPosition() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, probe = ProbeConfig(enabled = false))
            harness.moveTo(100L, x = 12_700L)
            presenter.onEdgeTap(EdgeSide.Left)
            advanceUntilIdle()
            assertEquals(EdgeStatus.Located, presenter.edge(EdgeSide.Left).status)
            assertEquals("12.700", presenter.edge(EdgeSide.Left).valueText)
            // A tap on a LOCATED edge tells how to clear it.
            presenter.onEdgeTap(EdgeSide.Left)
            advanceUntilIdle()
            assertEquals("Long press this button to clear current value", presenter.uiState.value.message.english())
            // A long press on a LOCATED edge clears it.
            presenter.onEdgeLongPress(EdgeSide.Left)
            advanceUntilIdle()
            assertEquals(EdgeStatus.Unknown, presenter.edge(EdgeSide.Left).status)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun aLongPressCapturesAlsoWithTheProbeOnAndAddsNoOffset() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, selected = ProbeProfile(type = ProbeType.TouchProbe, port = 'p', xOffsetUm = 250.0))
            harness.moveTo(100L, x = 5_000L)
            presenter.onEdgeLongPress(EdgeSide.Right)
            advanceUntilIdle()
            assertEquals("5.000", presenter.edge(EdgeSide.Right).valueText)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun theSamplerTakesTheMeanOfThreeTouchesAndWarnsOnTheSpread() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(
                harness,
                selected = ProbeProfile(type = ProbeType.TouchProbe, port = 'p', sampleCount = 3, spreadLimitUm = 20.0),
            )
            presenter.onEdgeTap(EdgeSide.Left)
            advanceUntilIdle()
            assertEquals(EdgeStatus.Scanning, presenter.edge(EdgeSide.Left).status)
            listOf(1_000L to 1_000L, 3_000L to 1_030L, 5_000L to 1_020L).forEach { (time, x) ->
                harness.moveTo(time, x = x)
                harness.touch(this, time)
            }
            assertEquals(EdgeStatus.Located, presenter.edge(EdgeSide.Left).status)
            // The mean of 1000, 1030, and 1020 µm is 1016.667 µm.
            assertEquals("1.017", presenter.edge(EdgeSide.Left).valueText)
            assertEquals("Warning: maximum spread between the samples is 0.030 mm", presenter.uiState.value.message.english())
            assertEquals(listOf(UiSound.Short, UiSound.Short, UiSound.Long), harness.sounds.played)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun aTapWithTheProbeClosedIsRefused() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness)
            harness.session.probe(active = true, timeMs = 100L)
            advanceUntilIdle()
            presenter.onEdgeTap(EdgeSide.Near)
            advanceUntilIdle()
            assertEquals(EdgeStatus.Unknown, presenter.edge(EdgeSide.Near).status)
            assertEquals(
                "Probe is in triggered state. Please move the probe away from the workpiece.",
                presenter.uiState.value.message.english(),
            )
            presenter.close()
        } finally {
            harness.close()
        }
    }

    /** Moves X from [from] by [step] µm each 100 ms from [startMs] to [endMs]. */
    private fun GraphicsHarness.ramp(channel: Char, startMs: Long, endMs: Long, from: Long, step: Long) {
        var time = startMs
        var value = from
        while (time <= endMs) {
            session.move(channel, value, time)
            time += 100L
            value += step
        }
    }

    @Test
    fun autoFindsTheLeftEdgeOfAnExternalFeatureForAMotionInPlusX() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, selected = ProbeProfile(type = ProbeType.TouchProbe, port = 'p', stylusDiameterUm = 2_000.0))
            presenter.onAutoToggle()
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.autoOn)
            harness.ramp('x', 100L, 500L, 0L, 100L)
            harness.session.probe(active = true, timeMs = 450L)
            harness.session.move('x', 500L, 500L)
            harness.session.tick(1_100L)
            advanceUntilIdle()
            assertEquals(EdgeStatus.Located, presenter.edge(EdgeSide.Left).status)
            // The latch interpolates X between 300 µm (400 ms) and 400 µm (500 ms): 350 µm.
            assertEquals("0.350", presenter.edge(EdgeSide.Left).valueText)
            assertEquals(listOf(UiSound.Long), harness.sounds.played)
            // One edge with the external feature: the A side, O = A + r.
            assertEquals(OriginOption.SideA, presenter.uiState.value.xOption)
            assertEquals("x: 1.350 mm y: _.___ mm", presenter.uiState.value.originText)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun autoFindsTheRightEdgeOfAnInternalFeatureForAMotionInPlusX() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness)
            presenter.onFeature(AxisId.X, FeatureType.Internal)
            presenter.onAutoToggle()
            advanceUntilIdle()
            harness.ramp('x', 100L, 500L, 0L, 100L)
            harness.session.probe(active = true, timeMs = 450L)
            harness.session.tick(1_100L)
            advanceUntilIdle()
            assertEquals(EdgeStatus.Located, presenter.edge(EdgeSide.Right).status)
            assertEquals(EdgeStatus.Unknown, presenter.edge(EdgeSide.Left).status)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun autoRefusesTwoMovingAxesAndNoMotion() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness)
            presenter.onAutoToggle()
            advanceUntilIdle()
            harness.ramp('x', 100L, 500L, 0L, 100L)
            harness.ramp('y', 100L, 500L, 0L, 100L)
            harness.session.probe(active = true, timeMs = 450L)
            harness.session.tick(1_100L)
            harness.session.probe(active = false, timeMs = 1_150L)
            advanceUntilIdle()
            assertEquals("Please move one axis at a time", presenter.uiState.value.message.english())
            assertEquals(listOf(UiSound.Error), harness.sounds.played)
            // No motion for more than 1000 ms before the trigger.
            harness.session.probe(active = true, timeMs = 3_000L)
            harness.session.tick(3_600L)
            advanceUntilIdle()
            assertEquals("No axis was moving when the probe triggered.", presenter.uiState.value.message.english())
            assertTrue(presenter.uiState.value.autoOn)
            assertTrue(EdgeSide.entries.all { side -> presenter.edge(side).status == EdgeStatus.Unknown })
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun autoRefusesAChangeOfDirectionInOneEdge() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, selected = ProbeProfile(type = ProbeType.TouchProbe, port = 'p', sampleCount = 2))
            presenter.onAutoToggle()
            advanceUntilIdle()
            harness.ramp('x', 100L, 500L, 0L, 100L)
            harness.session.probe(active = true, timeMs = 450L)
            harness.session.tick(1_100L)
            harness.session.probe(active = false, timeMs = 1_150L)
            advanceUntilIdle()
            assertEquals(EdgeStatus.Scanning, presenter.edge(EdgeSide.Left).status)
            harness.ramp('x', 2_000L, 2_400L, 400L, -100L)
            harness.session.probe(active = true, timeMs = 2_350L)
            harness.session.tick(3_000L)
            advanceUntilIdle()
            assertEquals("Change in direction detected during probe sampling", presenter.uiState.value.message.english())
            assertEquals(listOf(UiSound.Short, UiSound.Error), harness.sounds.played)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun theOriginsUseTheStylusRadiusOfTheProfile() = runTest {
        val harness = GraphicsHarness(this)
        try {
            // A 2D edge finder with no trigger input: a tap captures the position (no probe).
            val presenter = started(harness, selected = ProbeProfile(type = ProbeType.EdgeFinder2D, port = null, stylusDiameterUm = 2_000.0))
            harness.moveTo(100L, x = 1_000L)
            presenter.onEdgeTap(EdgeSide.Left)
            harness.moveTo(200L, x = 5_000L)
            presenter.onEdgeTap(EdgeSide.Right)
            advanceUntilIdle()
            val state = presenter.uiState.value
            assertEquals(setOf(OriginOption.SideA, OriginOption.Center, OriginOption.SideB), state.xOptions)
            assertEquals(OriginOption.Center, state.xOption)
            assertEquals("x: 3.000 mm y: _.___ mm", state.originText)
            presenter.onOption(AxisId.X, OriginOption.SideA)
            advanceUntilIdle()
            assertEquals("x: 2.000 mm y: _.___ mm", presenter.uiState.value.originText)
            presenter.onOption(AxisId.X, OriginOption.SideB)
            advanceUntilIdle()
            assertEquals("x: 4.000 mm y: _.___ mm", presenter.uiState.value.originText)
            presenter.onFeature(AxisId.X, FeatureType.Internal)
            advanceUntilIdle()
            // An internal feature: O = B − s × r with s = −1.
            assertEquals(OriginOption.Center, presenter.uiState.value.xOption)
            presenter.onOption(AxisId.X, OriginOption.SideB)
            advanceUntilIdle()
            assertEquals("x: 6.000 mm y: _.___ mm", presenter.uiState.value.originText)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun setOriginInAbsoluteModeSetsTheAbsoluteValue() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, probe = ProbeConfig(enabled = false))
            harness.moveTo(100L, x = 1_000L)
            presenter.onEdgeTap(EdgeSide.Left)
            harness.moveTo(200L, x = 5_000L)
            presenter.onEdgeTap(EdgeSide.Right)
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.setOriginEnabled)
            assertTrue(presenter.onSetOrigin())
            advanceUntilIdle()
            val x = harness.workspace().axisStates.getValue(AxisId.X)
            // P = 5000 µm, O = 3000 µm: h(2000), so A = 2000 − 5000.
            assertEquals(-3_000.0, x.absoluteOffset, 1e-9)
            assertFalse(x.relative)
            assertEquals(2_000.0, harness.readout.frame.value!!.reading(AxisId.X).absolute, 1e-9)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun setOriginInIncrementalModeSetsTheIncrementalValue() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, probe = ProbeConfig(enabled = false))
            harness.moveTo(100L, x = 1_000L, y = 2_000L)
            presenter.onEdgeTap(EdgeSide.Left)
            presenter.onEdgeTap(EdgeSide.Near)
            harness.moveTo(200L, x = 5_000L, y = 4_000L)
            presenter.onMode(CoordinateMode.Incremental)
            assertTrue(presenter.onSetOrigin())
            advanceUntilIdle()
            val workspace = harness.workspace()
            val x = workspace.axisStates.getValue(AxisId.X)
            val y = workspace.axisStates.getValue(AxisId.Y)
            assertTrue(x.relative)
            assertEquals(4_000.0 - 5_000.0, x.incrementalOffset, 1e-9)
            assertTrue(y.relative)
            assertEquals(2_000.0 - 4_000.0, y.incrementalOffset, 1e-9)
            assertEquals(4_000.0, harness.readout.frame.value!!.reading(AxisId.X).shown, 1e-9)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun addSubDatumWithATapClearsTheEdgesAndWithALongPressKeepsThem() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, probe = ProbeConfig(enabled = false))
            harness.moveTo(100L, x = 1_000L)
            presenter.onEdgeTap(EdgeSide.Left)
            harness.moveTo(200L, x = 5_000L, y = 7_000L, z = -300L)
            presenter.onEdgeTap(EdgeSide.Right)
            advanceUntilIdle()
            presenter.onAddSubDatum(keepEdges = true)
            advanceUntilIdle()
            assertEquals(EdgeStatus.Located, presenter.edge(EdgeSide.Left).status)
            presenter.onAddSubDatum(keepEdges = false)
            advanceUntilIdle()
            assertTrue(EdgeSide.entries.all { side -> presenter.edge(side).status == EdgeStatus.Unknown })
            val points = harness.workspace().subDatums
            assertEquals(2, points.size)
            val first = points.first()
            assertEquals("Point 1", first.name)
            assertEquals(3_000.0, first.x)
            assertEquals(7_000.0, first.y)
            assertEquals(-300.0, first.z)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun theEdgeFinderWorksOnAHorizontalMill() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, type = MachineType.HorizontalMill, probe = ProbeConfig(enabled = false))
            harness.moveTo(100L, x = 2_000L)
            presenter.onEdgeTap(EdgeSide.Left)
            advanceUntilIdle()
            assertEquals("2.000", presenter.edge(EdgeSide.Left).valueText)
            assertTrue(presenter.onSetOrigin())
            advanceUntilIdle()
            assertEquals(0.0, harness.readout.frame.value!!.reading(AxisId.X).absolute, 1e-9)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun theCameraCenterButtonSetsAndClearsTheCenterOrigin() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(
                harness,
                selected = ProbeProfile(type = ProbeType.Camera, port = null, xOffsetUm = 500.0, yOffsetUm = -250.0),
            )
            assertTrue(presenter.uiState.value.cameraCenterVisible)
            assertFalse(presenter.uiState.value.autoVisible)
            harness.moveTo(100L, x = 1_000L, y = 2_000L)
            presenter.onCameraCenter()
            advanceUntilIdle()
            val state = presenter.uiState.value
            assertTrue(state.cameraCenterSet)
            assertEquals(OriginOption.Center, state.xOption)
            assertEquals("x: 1.500 mm y: 1.750 mm", state.originText)
            presenter.onCameraCenter()
            advanceUntilIdle()
            assertFalse(presenter.uiState.value.cameraCenterSet)
            assertEquals("x: _.___ mm y: _.___ mm", presenter.uiState.value.originText)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun aProbeSampleInMeasurementModeRemovesTheInstrumentOffset() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(
                harness,
                selected = ProbeProfile(type = ProbeType.TouchProbe, port = 'p', xOffsetUm = 100.0),
                mode = MachineMode.Measurement,
                instrument = ProbeProfile(type = ProbeType.Camera, port = null, xOffsetUm = 300.0),
            )
            assertEquals(300.0, harness.readout.frame.value!!.instrumentOffset(AxisId.X), 1e-9)
            presenter.onEdgeTap(EdgeSide.Left)
            harness.moveTo(1_000L, x = 1_000L)
            harness.touch(this, 1_000L)
            // The latch is R + H = 1300 µm; the sample adds 100 − 300: the edge is R + 100 µm.
            assertEquals("1.100", presenter.edge(EdgeSide.Left).valueText)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun theSelectorListsTheAcceptedProfilesAndStoresTheSelection() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness)
            val touch = harness.storeProbe(ProbeProfile(name = "Touch", type = ProbeType.TouchProbe, port = 'p'))
            harness.storeProbe(ProbeProfile(name = "Setter", type = ProbeType.ToolHeightSetter, port = 'q'))
            advanceUntilIdle()
            val names = presenter.uiState.value.selector.options.map { item -> item.name }
            assertTrue("Touch" in names)
            assertFalse("Setter" in names)
            assertNull(presenter.uiState.value.selectedProbeId)
            presenter.onSelectProbe(touch.id)
            advanceUntilIdle()
            assertEquals(touch.id, harness.fullProfile().probeRoles.edgeFinderProfileId)
            assertEquals(touch.id, presenter.uiState.value.selectedProbeId)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun aNewProfileFromNewProfileBecomesTheSelectionOfTheEdgeFinder() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness)
            advanceUntilIdle()
            presenter.onNewProbe()
            // The probe profile screen saves a new touch probe.
            val created = harness.storeProbe(ProbeProfile(name = "New touch", type = ProbeType.TouchProbe, port = 'p'))
            advanceUntilIdle()
            assertEquals(created.id, harness.fullProfile().probeRoles.edgeFinderProfileId)
            assertEquals(created.id, presenter.uiState.value.selectedProbeId)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun aDisconnectionStopsAutoAndBlocksSetOrigin() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness)
            presenter.onAutoToggle()
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.autoOn)
            harness.session.state.value = SessionState.AwaitingData
            advanceUntilIdle()
            assertFalse(presenter.uiState.value.autoOn)
            assertFalse(presenter.uiState.value.connected)
            assertEquals(UiText.of(Res.string.edgefinder_not_connected), presenter.uiState.value.message)
            presenter.close()
        } finally {
            harness.close()
        }
    }
}
