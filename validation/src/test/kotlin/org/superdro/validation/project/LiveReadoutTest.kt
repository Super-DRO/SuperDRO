package org.superdro.validation.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSetup
import org.superdro.core.connection.DeviceSetupSource
import org.superdro.core.connection.SessionState
import org.superdro.core.fakeadapter.FakeTransportFactory
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.TransportKind
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.util.Clock
import org.superdro.validation.protocol.SessionHarness

/**
 * The app connects to an adapter and shows live axis positions. The path of the test is the
 * path of the app: the fake adapter sends
 * the protocol bytes, the session of `core:connection` parses them and runs the input stage of
 * `core:readout`, and the readout math of `core:readout` gives the shown text of each axis.
 */
class LiveReadoutTest {

    private val mill = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true)

    /** The shown X, Y, and Z texts of the axis positions [inputs] with the workspace of a new mill. */
    private fun shown(inputs: Map<AxisId, Double?>): Map<AxisId, String> {
        val frame = ReadoutFrame(mill, Workspace.forMachine(MachineType.VerticalMill), inputs)
        return listOf(AxisId.X, AxisId.Y, AxisId.Z).associateWith { axis -> frame.reading(axis).text }
    }

    @Test
    fun theFakeAdapterThroughTheConnectionAndTheReadoutGivesLiveAxisPositions() = runTest {
        val fakes = FakeTransportFactory(clock = Clock { testScheduler.currentTime })
        val setups = DeviceSetupSource { flowOf(DeviceSetup(mill, AdapterProfile())) }
        val harness = SessionHarness(this, deviceSetups = setups, extraFactories = mapOf(TransportKind.Fake to fakes))
        // The "Slow move" scenario: the X count changes linearly; Y and Z do not change.
        val session = harness.connect(DeviceRef(DeviceId(mill.id), mill.name, AdapterAddress.Fake("slow-move")))

        harness.advanceTo(2_000L)
        assertIs<SessionState.Connected>(session.state.value, "4.1: the session is online")
        val first = session.inputs.value.axisValues()
        harness.advanceTo(4_000L)
        val second = session.inputs.value.axisValues()
        harness.advanceTo(6_000L)
        val third = session.inputs.value.axisValues()

        // Each axis has a position (µm) from the default encoder profile: 25400 counts per inch.
        listOf(first, second, third).forEach { values ->
            listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertNotNull(values[axis], "$axis has a position") }
        }
        // The X position moves, with the same step in the two equal times; Y and Z stay.
        val step1 = second.getValue(AxisId.X)!! - first.getValue(AxisId.X)!!
        val step2 = third.getValue(AxisId.X)!! - second.getValue(AxisId.X)!!
        assertTrue(step1 > 0.0, "X moves: $step1")
        assertTrue(abs(step1 - step2) <= 100.0, "a linear move: $step1 and $step2 µm in 2 s")
        assertEquals(first[AxisId.Y], third[AxisId.Y])
        assertEquals(first[AxisId.Z], third[AxisId.Z])

        // The readout shows the position of a metric profile in mm with 3 decimals.
        val textFirst = shown(first)
        val textThird = shown(third)
        listOf(textFirst, textThird).flatMap { it.values }.forEach { text ->
            assertTrue(Regex("""-?\d+\.\d{3}""").matches(text), "the text $text has the mm format")
        }
        assertNotEquals(textFirst.getValue(AxisId.X), textThird.getValue(AxisId.X), "the X text is live")
        assertEquals(textFirst.getValue(AxisId.Y), textThird.getValue(AxisId.Y))
        harness.manager.disconnectAll()
        harness.settle()
    }
}
