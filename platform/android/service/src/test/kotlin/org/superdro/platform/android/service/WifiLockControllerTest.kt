package org.superdro.platform.android.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Wi-Fi lock while the MCP server runs. */
class WifiLockControllerTest {

    private class CountingHandle : WakeLockHandle {
        var acquires = 0
        var releases = 0
        override fun acquire() {
            acquires += 1
        }
        override fun release() {
            releases += 1
        }
    }

    @Test
    fun theLockIsHeldWhileTheServerRunsAndGoesWithTheStop() {
        val handle = CountingHandle()
        val controller = WifiLockController(handle)
        controller.update(serverRuns = false)
        assertFalse(controller.isHeld)
        assertEquals(0, handle.acquires)
        controller.update(serverRuns = true)
        controller.update(serverRuns = true)
        assertTrue(controller.isHeld)
        assertEquals(1, handle.acquires)
        controller.update(serverRuns = false)
        assertFalse(controller.isHeld)
        assertEquals(1, handle.releases)
    }

    @Test
    fun aReleaseWithNoLockDoesNothing() {
        val handle = CountingHandle()
        val controller = WifiLockController(handle)
        controller.release()
        assertEquals(0, handle.releases)
        controller.update(serverRuns = true)
        controller.release()
        controller.release()
        assertEquals(1, handle.releases)
    }

    @Test
    fun theLowLatencyModeStartsAtApi29() {
        assertEquals(29, WifiLockRules.LOW_LATENCY_API)
        assertFalse(WifiLockRules.lowLatency(24))
        assertFalse(WifiLockRules.lowLatency(28))
        assertTrue(WifiLockRules.lowLatency(29))
        assertTrue(WifiLockRules.lowLatency(36))
    }
}
