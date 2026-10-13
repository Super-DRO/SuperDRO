package org.superdro.core.ui.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState

/** The status of a tab for each session state. */
class TabStatusRulesTest {

    private suspend fun check(state: SessionState, icon: TabStatusIcon, text: String, roles: StatusRoles) {
        val status = TabStatusRules.of(state)
        assertEquals(icon, status.icon, state.toString())
        assertEquals(text, status.text.english(), state.toString())
        assertEquals(roles, status.roles, state.toString())
    }

    @Test
    fun eachStateHasAnIconATextAndRoles() = runTest {
        check(SessionState.Connected(0L), TabStatusIcon.Connected, "Connected", StatusRoles.Connected)
        check(SessionState.Connecting(1), TabStatusIcon.Progress, "Connecting", StatusRoles.Reconnect)
        check(SessionState.AwaitingData, TabStatusIcon.Progress, "Waiting for data", StatusRoles.Reconnect)
        check(SessionState.Reconnecting(2, 5_000L, LinkFailure.Lost), TabStatusIcon.Reconnecting, "Reconnecting (2)", StatusRoles.Reconnect)
        check(SessionState.NoData, TabStatusIcon.Warning, "No data", StatusRoles.Reconnect)
        check(SessionState.WaitingForDevice(LinkFailure.RadioOff), TabStatusIcon.Error, "Bluetooth off", StatusRoles.Failure)
        check(SessionState.WaitingForDevice(LinkFailure.DeviceGone), TabStatusIcon.Error, "Unplugged", StatusRoles.Failure)
        check(SessionState.WaitingForDevice(LinkFailure.NetworkOff), TabStatusIcon.Error, "No network", StatusRoles.Failure)
        check(SessionState.WaitingForDevice(LinkFailure.PermissionMissing), TabStatusIcon.Error, "Permission needed", StatusRoles.Failure)
        check(SessionState.Failed(LinkFailure.Refused), TabStatusIcon.Error, "Connect failed", StatusRoles.Failure)
        check(SessionState.Failed(LinkFailure.Lost), TabStatusIcon.Error, "Link lost", StatusRoles.Failure)
        check(SessionState.Disconnected, TabStatusIcon.Offline, "Offline", StatusRoles.Neutral)
    }

    @Test
    fun theReconnectingStatusKeepsTheAttemptNumber() {
        assertEquals(4, TabStatusRules.of(SessionState.Reconnecting(4, 0L, LinkFailure.Refused)).attempt)
        assertEquals(null, TabStatusRules.of(SessionState.Connected(0L)).attempt)
    }
}
