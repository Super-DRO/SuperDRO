@file:OptIn(ExperimentalCoroutinesApi::class)

package org.superdro.validation.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AdapterInfo
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceLine
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.KeepAliveController
import org.superdro.core.connection.KeepAliveSummary
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.ReconnectDecision
import org.superdro.core.connection.ReconnectPolicy
import org.superdro.core.connection.RestartResult
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.CommandResult
import org.superdro.core.readout.input.InputHistory
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.ProbeEvent

/**
 * The reconnect delays (1, 2, 4, 8,
 * 16, then 30 s, each ±20 %), the reset after 10 s in `Connected`, no limit, and the
 * keep-alive summary with its 300 ms debounce.
 */
class ReconnectAndKeepAliveValidationTest {

    // The reconnect policy.

    @Test
    fun delays_are_1_2_4_8_16_then_30_s_for_each_attempt() {
        val policy = ReconnectPolicy()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), (1..8).map { policy.baseDelayMs(it) })
        assertEquals(30_000L, policy.baseDelayMs(1_000))
    }

    @Test
    fun each_delay_has_a_random_variation_of_plus_or_minus_20_percent() {
        val policy = ReconnectPolicy(Random(42))
        assertEquals(800L..1_200L, policy.delayRangeMs(1))
        assertEquals(1_600L..2_400L, policy.delayRangeMs(2))
        assertEquals(24_000L..36_000L, policy.delayRangeMs(6))
        for (failures in 1..7) {
            val base = policy.baseDelayMs(failures).toDouble()
            val samples = (1..5_000).map { policy.delayMs(failures) }
            assertTrue(samples.all { it.toDouble() in base * 0.8..base * 1.2 }, "failures $failures")
            assertTrue(samples.min() < base * 0.84, "the variation does not reach −20 % for $failures")
            assertTrue(samples.max() > base * 1.16, "the variation does not reach +20 % for $failures")
        }
    }

    @Test
    fun the_delay_starts_at_1_s_again_after_10_s_in_connected() {
        val policy = ReconnectPolicy()
        assertEquals(0, policy.failuresAfterConnected(5, 10_000L))
        assertEquals(0, policy.failuresAfterConnected(5, 60_000L))
        assertEquals(5, policy.failuresAfterConnected(5, 9_999L))
    }

    @Test
    fun capability_failures_wait_for_the_capability_and_the_others_retry_with_no_limit() {
        val policy = ReconnectPolicy(Random(1), variation = 0.0)
        for (failure in listOf(LinkFailure.RadioOff, LinkFailure.PermissionMissing, LinkFailure.DeviceGone, LinkFailure.NetworkOff)) {
            assertEquals(ReconnectDecision.WaitForCapability(failure), policy.decide(failure, 3))
        }
        assertEquals(ReconnectDecision.Retry(failures = 1, attempt = 2, delayMs = 1_000L), policy.decide(LinkFailure.Refused, 0))
        val late = policy.decide(LinkFailure.Lost, 10_000)
        assertIs<ReconnectDecision.Retry>(late)
        assertEquals(30_000L, late.delayMs)
    }

    // The keep-alive summary.

    /** A session with only a state, for the keep-alive controller. */
    private class StateOnlySession(override val id: DeviceId, address: AdapterAddress) : DeviceSession {
        val mutableState = MutableStateFlow<SessionState>(SessionState.Disconnected)
        override val device = MutableStateFlow(DeviceRef(id, "Machine $id", address))
        override val state = mutableState
        override val inputs = MutableStateFlow(InputSnapshot.EMPTY)
        override val probeEvents = MutableSharedFlow<ProbeEvent>()
        override val adapterInfo = MutableStateFlow(AdapterInfo())
        override val history: InputHistory get() = error("not used")
        override val messages = MutableSharedFlow<AdapterMessage>()
        override val events = MutableSharedFlow<SessionEvent>()
        override suspend fun send(command: AdapterCommand): CommandResult = CommandResult.NotSent
        override suspend fun restartAdapter(): RestartResult = RestartResult.NoSession
        override fun openAdapterDetails() = Unit
        override fun closeAdapterDetails() = Unit
        override suspend fun <T> readHistory(block: (InputHistory) -> T): T = error("not used")
    }

    private fun TestScope.advanceTo(timeMs: Long) {
        advanceTimeBy(timeMs - testScheduler.currentTime)
        runCurrent()
    }

    @Test
    fun summary_is_not_null_in_the_states_that_want_a_link() {
        val states = listOf(
            SessionState.Connecting(1), SessionState.AwaitingData, SessionState.Connected(0L), SessionState.NoData,
            SessionState.Reconnecting(2, 1_000L, LinkFailure.Lost), SessionState.WaitingForDevice(LinkFailure.RadioOff),
        )
        for (state in states) {
            val summary = KeepAliveController.summaryOf(listOf(DeviceLine(DeviceId("a"), "A", TransportKind.BluetoothSpp, state)))
            assertNotNull(summary, "$state")
        }
        for (state in listOf(SessionState.Disconnected, SessionState.Failed(LinkFailure.Lost))) {
            assertNull(KeepAliveController.summaryOf(listOf(DeviceLine(DeviceId("a"), "A", TransportKind.BluetoothSpp, state))), "$state")
        }
        assertNull(KeepAliveController.summaryOf(emptyList()))
        val mixed = KeepAliveController.summaryOf(
            listOf(
                DeviceLine(DeviceId("a"), "A", TransportKind.BluetoothSpp, SessionState.Disconnected),
                DeviceLine(DeviceId("b"), "B", TransportKind.Tcp, SessionState.Reconnecting(3, 5_000L, LinkFailure.Refused)),
            ),
        )
        assertNotNull(mixed)
        assertEquals(listOf(DeviceId("b")), mixed.devices.map { it.id })
    }

    @Test
    fun a_change_waits_300_ms_and_repeated_values_are_removed() = runTest {
        val updates = ArrayList<Pair<Long, KeepAliveSummary?>>()
        val keepAlive = object : ConnectionKeepAlive {
            override fun update(summary: KeepAliveSummary?) {
                updates += testScheduler.currentTime to summary
            }
        }
        val a = StateOnlySession(DeviceId("a"), AdapterAddress.Fake("a"))
        val sessions = MutableStateFlow<Map<DeviceId, DeviceSession>>(mapOf(a.id to a))
        KeepAliveController(sessions, keepAlive, backgroundScope)
        runCurrent()
        advanceTo(1_000L)
        a.mutableState.value = SessionState.Connecting(1)
        advanceTo(1_100L)
        a.mutableState.value = SessionState.AwaitingData
        advanceTo(1_200L)
        a.mutableState.value = SessionState.Connected(1_200L)
        advanceTo(1_499L)
        assertEquals(emptyList(), updates)
        advanceTo(1_500L)
        assertEquals(1, updates.size)
        assertEquals(1_500L, updates.single().first)
        assertEquals(
            listOf(DeviceLine(a.id, "Machine a", TransportKind.Fake, SessionState.Connected(1_200L))),
            updates.single().second!!.devices,
        )
        // A quick change and back gives the same summary: no new update.
        advanceTo(2_000L)
        a.mutableState.value = SessionState.NoData
        advanceTo(2_100L)
        a.mutableState.value = SessionState.Connected(1_200L)
        advanceTo(3_000L)
        assertEquals(1, updates.size)
        advanceTo(5_000L)
        a.mutableState.value = SessionState.Disconnected
        advanceTo(5_299L)
        assertEquals(1, updates.size)
        advanceTo(5_300L)
        assertEquals(5_300L to null, updates.last())
    }
}
