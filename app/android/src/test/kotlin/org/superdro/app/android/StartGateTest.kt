package org.superdro.app.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The start of the activity after the DI graph is ready. */
class StartGateTest {

    @Test
    fun anActionBeforeTheStartWaitsAndRunsAfterTheStartInOrder() {
        val gate = StartGate()
        val calls = mutableListOf<String>()
        gate.whenReady { calls += "intent" }
        gate.whenReady { calls += "resume" }
        assertEquals(emptyList(), calls)
        assertFalse(gate.ready.value)

        gate.start {
            assertFalse(gate.ready.value)
            calls += "start"
        }

        assertEquals(listOf("start", "intent", "resume"), calls)
        assertTrue(gate.ready.value)
    }

    @Test
    fun anActionAfterTheStartRunsAtOnce() {
        val gate = StartGate()
        val calls = mutableListOf<String>()
        gate.start { calls += "start" }
        gate.whenReady { calls += "result" }
        assertEquals(listOf("start", "result"), calls)
    }

    @Test
    fun aSecondStartDoesNothing() {
        val gate = StartGate()
        var starts = 0
        gate.start { starts++ }
        gate.start { starts++ }
        assertEquals(1, starts)
    }

    @Test
    fun anActionThatWaitsCanAddAnActionThatRunsAtOnce() {
        val gate = StartGate()
        val calls = mutableListOf<String>()
        gate.whenReady {
            calls += "first"
            gate.whenReady { calls += "nested" }
        }
        gate.whenReady { calls += "second" }
        gate.start { calls += "start" }
        assertEquals(listOf("start", "first", "nested", "second"), calls)
    }
}
