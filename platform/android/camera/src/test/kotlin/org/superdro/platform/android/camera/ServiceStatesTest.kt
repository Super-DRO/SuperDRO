package org.superdro.platform.android.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bind states and the run filter of the UI process. */
class ServiceStatesTest {

    @Test
    fun eachBindEventHasItsState() {
        assertEquals(ServiceState.Binding, ServiceStates.next(ServiceState.Unbound, ServiceEvent.BindStarted))
        assertEquals(ServiceState.Failed, ServiceStates.next(ServiceState.Unbound, ServiceEvent.BindRefused))
        assertEquals(ServiceState.Bound, ServiceStates.next(ServiceState.Binding, ServiceEvent.Connected))
        assertEquals(ServiceState.Binding, ServiceStates.next(ServiceState.Bound, ServiceEvent.Disconnected))
        assertEquals(ServiceState.Unbound, ServiceStates.next(ServiceState.Bound, ServiceEvent.BindingDied))
        assertEquals(ServiceState.Failed, ServiceStates.next(ServiceState.Binding, ServiceEvent.NullBinding))
        assertEquals(ServiceState.Binding, ServiceStates.next(ServiceState.Bound, ServiceEvent.BinderDied))
        assertEquals(ServiceState.Bound, ServiceStates.next(ServiceState.Bound, ServiceEvent.BindStarted))
    }

    @Test
    fun onlyAnUnboundOrFailedStateBindsAgain() {
        assertTrue(ServiceStates.needsBind(ServiceState.Unbound))
        assertTrue(ServiceStates.needsBind(ServiceState.Failed))
        assertFalse(ServiceStates.needsBind(ServiceState.Binding))
        assertFalse(ServiceStates.needsBind(ServiceState.Bound))
        assertEquals("camera process died", ServiceStates.logText(ServiceEvent.BinderDied))
        assertEquals("bindService refused", ServiceStates.logText(ServiceEvent.BindRefused))
    }

    @Test
    fun theEventsOfAnOtherRunGoAway() {
        val runs = RunFilter()
        assertFalse(runs.accepts(0))
        runs.start(4)
        assertTrue(runs.accepts(4))
        assertFalse(runs.accepts(3))
        assertEquals("stream event for run 3 ignored (current is 4)", runs.ignoredText(3))
        runs.clear(3)
        assertTrue(runs.accepts(4))
        runs.clear(4)
        assertFalse(runs.accepts(4))
        assertEquals(0, runs.current)
    }
}
