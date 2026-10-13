package org.superdro.platform.android.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceLine
import org.superdro.core.connection.KeepAliveSummary
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.model.TransportKind

/** The start and stop rules of the connection service. */
class ServiceRulesTest {

    private val summary = KeepAliveSummary(
        listOf(DeviceLine(DeviceId("mill"), "Mill", TransportKind.BluetoothSpp, SessionState.Connected(sinceMs = 0L))),
    )

    @Test
    fun aStickyRestartWithANullIntentIsARestore() {
        assertEquals(ServiceCommand.Restore, ConnectionServiceRules.commandOf(hasIntent = false, action = null))
        assertEquals(ServiceCommand.Restore, ConnectionServiceRules.commandOf(hasIntent = true, action = ConnectionServiceRules.ACTION_RESTORE))
        assertEquals(ServiceCommand.DisconnectAll, ConnectionServiceRules.commandOf(hasIntent = true, action = ConnectionServiceRules.ACTION_DISCONNECT_ALL))
        assertEquals(ServiceCommand.Start, ConnectionServiceRules.commandOf(hasIntent = true, action = ConnectionServiceRules.ACTION_START))
        assertEquals(ServiceCommand.Start, ConnectionServiceRules.commandOf(hasIntent = true, action = null))
    }

    @Test
    fun aStartForANullSummaryStopsAtOnceAfterStartForeground() {
        assertTrue(ConnectionServiceRules.stopsAtOnce(ServiceCommand.Start, summary = null))
        assertFalse(ConnectionServiceRules.stopsAtOnce(ServiceCommand.Start, summary))
    }

    @Test
    fun onlyDisconnectAllStopsAtOnceAndARestoreWaitsForTheSessions() {
        assertTrue(ConnectionServiceRules.stopsAtOnce(ServiceCommand.DisconnectAll, summary))
        assertTrue(ConnectionServiceRules.stopsAtOnce(ServiceCommand.DisconnectAll, summary = null))
        assertFalse(ConnectionServiceRules.stopsAtOnce(ServiceCommand.Restore, summary = null))
        assertFalse(ConnectionServiceRules.stopsAtOnce(ServiceCommand.Restore, summary))
    }

    @Test
    fun theServiceStopsOnlyForANullSummaryAfterASummaryAndHasNoBackgroundTimer() {
        // A summary that is not null never stops the service: no 30 s timer in the background.
        assertFalse(ConnectionServiceRules.stopsForSummary(summary, sawSummary = true))
        assertFalse(ConnectionServiceRules.stopsForSummary(summary, sawSummary = false))
        // A machine that reconnects in the background keeps the summary, so the service stays.
        val reconnecting = KeepAliveSummary(
            listOf(DeviceLine(DeviceId("mill"), "Mill", TransportKind.BluetoothSpp, SessionState.Reconnecting(3, 1000L, LinkFailure.Lost))),
            offlineRememberedCount = 1,
        )
        assertFalse(ConnectionServiceRules.stopsForSummary(reconnecting, sawSummary = true))
        // Before the first summary (a restore in progress), a null value does not stop the service.
        assertFalse(ConnectionServiceRules.stopsForSummary(null, sawSummary = false))
        assertTrue(ConnectionServiceRules.stopsForSummary(null, sawSummary = true))
    }

    @Test
    fun theRestoreWaitsLongerThanTheDebounceOfTheController() {
        assertTrue(ConnectionServiceRules.RESTORE_SETTLE_MS > 300L)
        assertTrue(ConnectionServiceRules.NOTIFICATION_ID != ConnectionServiceRules.BOOT_FALLBACK_NOTIFICATION_ID)
    }

    @Test
    fun theBootStartReportsARefusalOfAndroid() {
        assertTrue(BootStart.start { })
        assertFalse(BootStart.start { throw IllegalStateException("ForegroundServiceStartNotAllowedException") })
        assertFalse(BootStart.start { throw SecurityException("connectedDevice") })
    }
}
