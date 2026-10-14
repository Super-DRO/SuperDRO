package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The path of the bytes from the transport to the parser, the probe events, and the record. */
class LinkPathTest {

    private val mill = DeviceId("mill")

    @Test
    fun theLastBytesBeforeTheEndOfTheStreamGoToTheParser() = runTest {
        val h = Harness(this)
        h.setups[mill] = defaultSetup()
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        val samples = h.record(session.samples)
        runCurrent()
        val link = h.links.last
        link.receive("X100;")
        runCurrent()
        // The last chunk and the failure come together: the parser must get the chunk first.
        link.receive("X123;Y7;")
        link.lose()
        runCurrent()
        assertEquals(listOf('x' to 100L, 'x' to 123L, 'y' to 7L), samples.map { sample -> sample.input to sample.rawCount })
        assertEquals(123L, session.inputs.value.channels.getValue('x').rawCount)
        assertTrue(session.state.value is SessionState.Reconnecting, "state ${session.state.value}")
        h.manager.disconnectAll()
        runCurrent()
    }
}
