package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The keep-alive state for the tab warning, and the defaults of the contracts. */
class KeepAliveStatusTest {

    @Test
    fun onlyARefusedStartAndAMissingPrerequisiteShowTheWarning() {
        assertFalse(KeepAliveStatus.Idle.linksCanStopInBackground)
        assertFalse(KeepAliveStatus.Running.linksCanStopInBackground)
        assertTrue(KeepAliveStatus.StartNotAllowed.linksCanStopInBackground)
        assertTrue(KeepAliveStatus.PrerequisiteMissing.linksCanStopInBackground)
    }

    @Test
    fun aKeepAliveWithNoStateReportsIdle() {
        val keepAlive = object : ConnectionKeepAlive {
            override fun update(summary: KeepAliveSummary?) = Unit
        }
        assertEquals(KeepAliveStatus.Idle, keepAlive.status.value)
    }

    @Test
    fun aPlatformWithNoBatteryOptimizationIsExempt() {
        NoBatteryOptimization.refresh()
        assertTrue(NoBatteryOptimization.exempt.value)
    }
}
