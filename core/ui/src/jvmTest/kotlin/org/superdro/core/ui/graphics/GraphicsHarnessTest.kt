package org.superdro.core.ui.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId

/** Checks the test harness of the graphics area: the probe session gives the latch of the input stage. */
class GraphicsHarnessTest {

    @Test
    fun theProbeSessionLatchesThePositionAtTheTriggerTime() = runTest {
        val harness = GraphicsHarness(this)
        try {
            harness.start()
            harness.moveTo(100L, x = 1000L, y = 0L, z = 0L)
            harness.moveTo(300L, x = 3000L)
            harness.session.probe(active = true, timeMs = 200L)
            harness.moveTo(400L, x = 4000L)
            // Y and Z have no sample after the trigger: the latch waits for the deadline t + 500 ms.
            harness.session.tick(700L)
            val event = harness.session.sentEvents.single()
            assertEquals(200L, event.timeMs)
            assertEquals(2000.0, event.axisPositions.getValue(AxisId.X), 1e-9)
            assertEquals(0.0, event.axisPositions.getValue(AxisId.Y), 1e-9)
            assertEquals(4000.0, harness.readout.frame.value!!.reading(AxisId.X).absolute, 1e-9)
        } finally {
            harness.close()
        }
    }
}
