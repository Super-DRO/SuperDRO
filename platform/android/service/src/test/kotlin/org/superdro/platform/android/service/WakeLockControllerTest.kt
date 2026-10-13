package org.superdro.platform.android.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The partial wake lock while one or more links are open. */
class WakeLockControllerTest {

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
    fun theLockIsHeldWhileOneOrMoreLinksAreOpenAndGoesWithTheLastLink() {
        val handle = CountingHandle()
        val controller = WakeLockController(handle)
        controller.update(openLinks = 0, settingOn = true)
        assertFalse(controller.isHeld)
        controller.update(openLinks = 1, settingOn = true)
        controller.update(openLinks = 2, settingOn = true)
        assertTrue(controller.isHeld)
        assertEquals(1, handle.acquires)
        controller.update(openLinks = 1, settingOn = true)
        assertTrue(controller.isHeld)
        controller.update(openLinks = 0, settingOn = true)
        assertFalse(controller.isHeld)
        assertEquals(1, handle.releases)
    }

    @Test
    fun theSettingTurnsTheLockOff() {
        val handle = CountingHandle()
        val controller = WakeLockController(handle)
        controller.update(openLinks = 3, settingOn = false)
        assertFalse(controller.isHeld)
        assertEquals(0, handle.acquires)
        controller.update(openLinks = 3, settingOn = true)
        assertTrue(controller.isHeld)
        controller.update(openLinks = 3, settingOn = false)
        assertFalse(controller.isHeld)
        assertEquals(1, handle.releases)
    }

    @Test
    fun aReleaseWithNoLockDoesNothing() {
        val handle = CountingHandle()
        val controller = WakeLockController(handle)
        controller.release()
        assertEquals(0, handle.releases)
        controller.update(openLinks = 1, settingOn = true)
        controller.release()
        controller.release()
        assertEquals(1, handle.releases)
    }
}
