package org.superdro.platform.android.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.connection.KeepAliveStatus

/** The phase of the service that the keep-alive reads, and the state of the tab warning. */
class ConnectionServiceStateTest {

    @Test
    fun aStartGoesToRunningAndADestroyToStopped() {
        val state = ConnectionServiceState()
        assertEquals(ServicePhase.Stopped, state.phase.value)
        assertEquals(KeepAliveStatus.Idle, state.status.value)
        state.onStartRequested()
        assertTrue(state.isActive)
        val token = state.onCreated()
        state.onForeground(token)
        assertEquals(ServicePhase.Running, state.phase.value)
        assertEquals(KeepAliveStatus.Running, state.status.value)
        state.onStopping(token)
        assertEquals(ServicePhase.Stopping, state.phase.value)
        assertFalse(state.isActive)
        state.onDestroyed(token)
        assertEquals(ServicePhase.Stopped, state.phase.value)
        assertEquals(KeepAliveStatus.Idle, state.status.value)
    }

    @Test
    fun aNewStartBeforeTheDestroyOfTheOldInstanceStaysActive() {
        val state = ConnectionServiceState()
        val old = state.onCreated()
        state.onForeground(old)
        state.onStopping(old)
        // The keep-alive sees no active instance and starts again.
        state.onStartRequested()
        state.onDestroyed(old)
        assertEquals(ServicePhase.Starting, state.phase.value)
        val new = state.onCreated()
        state.onForeground(new)
        // An old instance cannot change the phase of the new one.
        state.onStopping(old)
        state.onDestroyed(old)
        assertEquals(ServicePhase.Running, state.phase.value)
    }

    @Test
    fun aRefusedStartAndAMissingPrerequisiteShowTheWarning() {
        val state = ConnectionServiceState()
        state.onStartRequested()
        state.onStartRefused(KeepAliveStatus.StartNotAllowed)
        assertEquals(ServicePhase.Stopped, state.phase.value)
        assertTrue(state.status.value.linksCanStopInBackground)
        state.onPrerequisiteMissing()
        assertEquals(KeepAliveStatus.PrerequisiteMissing, state.status.value)
        state.onIdle()
        assertEquals(KeepAliveStatus.Idle, state.status.value)
    }

    @Test
    fun aMissingPrerequisiteDoesNotChangeARunningService() {
        val state = ConnectionServiceState()
        val token = state.onCreated()
        state.onForeground(token)
        state.onPrerequisiteMissing()
        state.onIdle()
        assertEquals(KeepAliveStatus.Running, state.status.value)
    }
}
