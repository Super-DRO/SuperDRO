package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.superdro.core.util.TestClock

class CameraAccessControllerTest {

    private class FakePlatform : CameraAccessPlatform {
        override var nativeAvailable: Boolean? = null
        var cameraPermission = true
        var cameraRequestResult = CameraPermissionResult.Granted
        val usbGrants = mutableSetOf<String>()
        val prompts = mutableListOf<String>()
        var binds = 0
        var registrations = 0
        var cameraRequests = 0

        override fun startServiceBind() {
            binds += 1
        }

        override fun hasCameraPermission(): Boolean = cameraPermission

        override suspend fun requestCameraPermission(): CameraPermissionResult {
            cameraRequests += 1
            if (cameraRequestResult == CameraPermissionResult.Granted) cameraPermission = true
            return cameraRequestResult
        }

        override fun registerReceivers() {
            registrations += 1
        }

        override fun hasUsbPermission(camera: CameraIdentity): Boolean = camera.deviceName in usbGrants

        override fun requestUsbPermission(camera: CameraIdentity) {
            prompts += camera.deviceName
        }
    }

    private val first = CameraIdentity(0x046D, 0x0825, deviceName = "/dev/bus/usb/001/002")
    private val second = CameraIdentity(0x1D6B, 0x0102, deviceName = "/dev/bus/usb/001/003")

    private fun refusedKind(result: CameraAccessResult): CameraErrorKind? = (result as? CameraAccessResult.Refused)?.error?.kind

    @Test
    fun noNativeLayerStopsBeforeTheBind() = runTest {
        val platform = FakePlatform().apply { nativeAvailable = false }
        val controller = CameraAccessController(platform, TestClock())
        assertEquals(CameraErrorKind.NativeUnavailable, refusedKind(controller.requestAccess(first)))
        assertEquals(0, platform.binds)
    }

    @Test
    fun theCameraPermissionComesFirst() = runTest {
        val platform = FakePlatform().apply {
            cameraPermission = false
            usbGrants += first.deviceName
        }
        val controller = CameraAccessController(platform, TestClock())
        assertEquals(CameraAccessResult.Granted, controller.requestAccess(first))
        assertEquals(1, platform.cameraRequests)
        assertEquals(1, platform.binds)

        val denied = FakePlatform().apply {
            cameraPermission = false
            cameraRequestResult = CameraPermissionResult.Denied
        }
        val refused = CameraAccessController(denied, TestClock()).requestAccess(first)
        assertEquals(CameraErrorKind.CameraPermissionMissing, refusedKind(refused))
        assertFalse((refused as CameraAccessResult.Refused).openAppSettings)
        assertTrue(denied.prompts.isEmpty())

        val blocked = FakePlatform().apply {
            cameraPermission = false
            cameraRequestResult = CameraPermissionResult.Blocked
        }
        val blockedResult = CameraAccessController(blocked, TestClock()).requestAccess(first)
        assertEquals(CameraErrorKind.CameraPermissionMissing, refusedKind(blockedResult))
        assertTrue((blockedResult as CameraAccessResult.Refused).openAppSettings)

        // The back end result carries the flag in its error, so the UI does not read the detail text.
        val blockedError = (blockedResult.toBackendResult().exceptionOrNull() as CameraException).error
        assertTrue(blockedError.openAppSettings)
        assertEquals(CameraErrorKind.CameraPermissionMissing, blockedError.kind)
        assertFalse((refused.toBackendResult().exceptionOrNull() as CameraException).error.openAppSettings)
        assertTrue(CameraAccessResult.Granted.toBackendResult().isSuccess)
        assertTrue(
            (CameraAccessResult.Refused(CameraError(CameraErrorKind.CameraPermissionMissing), openAppSettings = true).toBackendResult()
                .exceptionOrNull() as CameraException).error.openAppSettings,
        )
    }

    @Test
    fun noCameraGivesNoDeviceAndTheReceiversRegisterOneTime() = runTest {
        val platform = FakePlatform()
        val controller = CameraAccessController(platform, TestClock())
        assertEquals(CameraErrorKind.NoDevice, refusedKind(controller.requestAccess(null)))
        assertEquals(CameraErrorKind.NoDevice, refusedKind(controller.requestAccess(null)))
        assertEquals(1, platform.registrations)
    }

    @Test
    fun aGrantedDeviceNeedsNoPrompt() = runTest {
        val platform = FakePlatform().apply { usbGrants += first.deviceName }
        assertEquals(CameraAccessResult.Granted, CameraAccessController(platform, TestClock()).requestAccess(first))
        assertTrue(platform.prompts.isEmpty())
    }

    @Test
    fun theReceiverDoesNotTrustTheExtras() = runTest {
        val platform = FakePlatform()
        val logs = mutableListOf<String>()
        val controller = CameraAccessController(platform, TestClock(), log = { text -> logs += text })
        val request = async(start = CoroutineStart.UNDISPATCHED) { controller.requestAccess(first) }
        assertEquals(listOf(first.deviceName), platform.prompts)
        assertEquals(first, controller.openPrompt)
        // An immutable PendingIntent: no extras. The grant is in place.
        platform.usbGrants += first.deviceName
        controller.onUsbPermissionResult(deviceName = null, permissionExtra = null)
        assertEquals(CameraAccessResult.Granted, request.await())
        assertNull(controller.openPrompt)

        val next = async(start = CoroutineStart.UNDISPATCHED) { controller.requestAccess(second) }
        platform.usbGrants += second.deviceName
        controller.onUsbPermissionResult(second.deviceName, permissionExtra = false)
        assertEquals(CameraAccessResult.Granted, next.await())
        assertTrue("usb permission broadcast said refused, but the grant is in place" in logs)
    }

    @Test
    fun aRefusalStartsABackOffOf5000Ms() = runTest {
        val platform = FakePlatform()
        val clock = TestClock(1_000L)
        val controller = CameraAccessController(platform, clock)
        val request = async(start = CoroutineStart.UNDISPATCHED) { controller.requestAccess(first) }
        controller.onUsbPermissionResult(first.deviceName, permissionExtra = false)
        assertEquals(CameraErrorKind.UsbPermissionDenied, refusedKind(request.await()))

        clock.advanceBy(4_999L)
        val inBackOff = controller.requestAccess(first)
        assertEquals(CameraError(CameraErrorKind.UsbPermissionDenied, "within back-off"), (inBackOff as CameraAccessResult.Refused).error)
        assertEquals(1, platform.prompts.size)

        clock.advanceBy(1L)
        val again = async(start = CoroutineStart.UNDISPATCHED) { controller.requestAccess(first) }
        assertEquals(2, platform.prompts.size)
        platform.usbGrants += first.deviceName
        controller.onUsbPermissionResult(first.deviceName, permissionExtra = true)
        assertEquals(CameraAccessResult.Granted, again.await())
    }

    @Test
    fun anAttachClearsTheBackOff() = runTest {
        val platform = FakePlatform()
        val controller = CameraAccessController(platform, TestClock())
        val request = async(start = CoroutineStart.UNDISPATCHED) { controller.requestAccess(first) }
        controller.onUsbPermissionResult(first.deviceName, permissionExtra = false)
        request.await()
        controller.onAttached(first)
        val again = async(start = CoroutineStart.UNDISPATCHED) { controller.requestAccess(first) }
        assertEquals(2, platform.prompts.size)
        again.cancel()
    }

    @Test
    fun aSecondCameraWaitsBehindTheOpenPrompt() = runTest {
        val platform = FakePlatform()
        val logs = mutableListOf<String>()
        val controller = CameraAccessController(platform, TestClock(), log = { text -> logs += text })
        val one = async(start = CoroutineStart.UNDISPATCHED) { controller.requestAccess(first) }
        val two = async(start = CoroutineStart.UNDISPATCHED) { controller.requestAccess(second) }
        assertEquals(listOf(first.deviceName), platform.prompts)
        assertEquals(listOf("queued behind the open prompt (1 waiting)"), logs)
        assertEquals(2, controller.waiting)

        platform.usbGrants += first.deviceName
        controller.onUsbPermissionResult(first.deviceName, permissionExtra = true)
        assertEquals(CameraAccessResult.Granted, one.await())
        assertEquals(listOf(first.deviceName, second.deviceName), platform.prompts)

        controller.onUsbPermissionResult(second.deviceName, permissionExtra = false)
        assertEquals(CameraErrorKind.UsbPermissionDenied, refusedKind(two.await()))
        assertEquals(0, controller.waiting)
    }

    @Test
    fun aDetachFailsTheWaitingRequests() = runTest {
        val platform = FakePlatform()
        val controller = CameraAccessController(platform, TestClock())
        val one = async(start = CoroutineStart.UNDISPATCHED) { controller.requestAccess(first) }
        val two = async(start = CoroutineStart.UNDISPATCHED) { controller.requestAccess(second) }
        controller.onDetached(first)
        assertEquals(CameraError(CameraErrorKind.Disconnected, "detached while asking"), (one.await() as CameraAccessResult.Refused).error)
        assertEquals(second, controller.openPrompt)
        assertEquals(listOf(first.deviceName, second.deviceName), platform.prompts)
        platform.usbGrants += second.deviceName
        controller.onUsbPermissionResult(null, null)
        assertEquals(CameraAccessResult.Granted, two.await())
    }
}
