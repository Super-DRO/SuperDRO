package org.superdro.core.ui.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.mcp.GateDecision
import org.superdro.core.mcp.McpClient
import org.superdro.core.mcp.McpEra
import org.superdro.core.mcp.McpLimits
import org.superdro.core.mcp.WriteRequest

/** The confirmation on the tablet with virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class McpGateTest {

    private val request = WriteRequest("Put sub-datums", "3 points on Mill (Default Workspace)", listOf("3 added"), McpClient("192.168.1.30", McpEra.Modern))

    @Test
    fun allowGivesAllowedAndClearsThePendingRequest() = runTest {
        val gate = McpGate()
        val decision = async { gate.confirm(request) }
        runCurrent()
        assertSame(request, gate.pending.value)
        gate.allow()
        runCurrent()
        assertEquals(GateDecision.Allowed, decision.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun refuseGivesRefused() = runTest {
        val gate = McpGate()
        val decision = async { gate.confirm(request) }
        runCurrent()
        gate.refuse()
        assertEquals(GateDecision.Refused, decision.await())
    }

    @Test
    fun noAnswerIn60SecondsGivesTimeout() = runTest {
        val gate = McpGate()
        val decision = async { gate.confirm(request) }
        runCurrent()
        advanceTimeBy(McpLimits.CONFIRM_TIMEOUT_MS - 1)
        runCurrent()
        assertSame(request, gate.pending.value, "The request waits until the timeout.")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(GateDecision.Timeout, decision.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun theSecondRequestWaitsForTheAnswerOfTheFirst() = runTest {
        val gate = McpGate()
        val second = request.copy(summary = "second")
        val first = async { gate.confirm(request) }
        runCurrent()
        val next = async { gate.confirm(second) }
        runCurrent()
        assertSame(request, gate.pending.value)
        assertEquals(1, gate.waiting.value)
        gate.allow()
        runCurrent()
        assertEquals(GateDecision.Allowed, first.await())
        assertSame(second, gate.pending.value)
        assertEquals(0, gate.waiting.value)
        gate.refuse()
        assertEquals(GateDecision.Refused, next.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun anAnswerWithNoRequestHasNoEffect() = runTest {
        val gate = McpGate()
        gate.allow()
        gate.refuse()
        assertNull(gate.pending.value)
    }
}
