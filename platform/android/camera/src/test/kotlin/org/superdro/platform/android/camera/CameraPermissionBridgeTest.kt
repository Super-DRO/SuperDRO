package org.superdro.platform.android.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.superdro.core.camera.CameraPermissionResult

/** The CAMERA permission through the request bus of the app. */
class CameraPermissionBridgeTest {

    @Test
    fun theRequestWaitsForTheResultOfTheDialog() = runTest {
        var sent = 0
        val bridge = CameraPermissionBridge { sent += 1 }
        val first = async(start = CoroutineStart.UNDISPATCHED) { bridge.request() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { bridge.request() }
        assertEquals(2, sent)
        bridge.onResult(granted = true, showRationale = false)
        assertEquals(CameraPermissionResult.Granted, first.await())
        assertEquals(CameraPermissionResult.Granted, second.await())
    }

    @Test
    fun aRefusalWithNoRationaleIsBlocked() {
        assertEquals(CameraPermissionResult.Granted, CameraPermissionBridge.resultOf(granted = true, showRationale = true))
        assertEquals(CameraPermissionResult.Denied, CameraPermissionBridge.resultOf(granted = false, showRationale = true))
        assertEquals(CameraPermissionResult.Blocked, CameraPermissionBridge.resultOf(granted = false, showRationale = false))
    }
}
