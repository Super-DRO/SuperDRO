package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The contract of [SessionState]. */
class SessionStateTest {

    private val states: List<SessionState> = listOf(
        SessionState.Disconnected,
        SessionState.Connecting(attempt = 1),
        SessionState.AwaitingData,
        SessionState.Connected(sinceMs = 0L),
        SessionState.NoData,
        SessionState.Reconnecting(attempt = 2, retryAtMs = 1_000L, cause = LinkFailure.Lost),
        SessionState.WaitingForDevice(cause = LinkFailure.RadioOff),
        SessionState.Failed(cause = LinkFailure.Refused),
    )

    @Test
    fun onlyAwaitingDataConnectedAndNoDataAreOnline() {
        assertEquals(
            listOf(SessionState.AwaitingData, SessionState.Connected(0L), SessionState.NoData),
            states.filter { state -> state.isOnline },
        )
    }

    @Test
    fun theKeepAliveHoldsInEachStateExceptDisconnectedAndFailed() {
        assertEquals(
            listOf(false, true, true, true, true, true, true, false),
            states.map { state -> state.keepsAlive },
        )
    }

    @Test
    fun theCapabilityFailuresWaitForAnEvent() {
        assertTrue(LinkFailure.RadioOff.waitsForCapability)
        assertTrue(LinkFailure.PermissionMissing.waitsForCapability)
        assertTrue(LinkFailure.NetworkOff.waitsForCapability)
        assertTrue(LinkFailure.DeviceGone.waitsForCapability)
        assertFalse(LinkFailure.Refused.waitsForCapability)
        assertFalse(LinkFailure.Lost.waitsForCapability)
    }

    @Test
    fun theExceptionCarriesTheFailure() {
        val error = LinkFailureException(LinkFailure.NetworkOff)
        assertEquals(LinkFailure.NetworkOff, error.failure)
        assertEquals("NetworkOff", error.message)
    }

    @Test
    fun theSummaryCountsTheOnlineDevices() {
        val summary = KeepAliveSummary(
            listOf(
                DeviceLine(DeviceId("a"), "Mill", org.superdro.core.model.TransportKind.BluetoothSpp, SessionState.Connected(5L)),
                DeviceLine(DeviceId("b"), "Lathe", org.superdro.core.model.TransportKind.UsbSerial, SessionState.WaitingForDevice(LinkFailure.DeviceGone)),
            )
        )
        assertEquals(1, summary.onlineCount)
    }
}
