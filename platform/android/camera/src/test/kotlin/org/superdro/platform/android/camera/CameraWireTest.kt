package org.superdro.platform.android.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.camera.CameraCodes
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorKind

/** The values that cross the process boundary. */
class CameraWireTest {

    @Test
    fun theRefusalTextsOfTheCameraProcessHaveTheirCodes() {
        assertEquals("the camera process could not run this (-102)", CameraWire.SERVICE_CANNOT_RUN)
        assertEquals("the camera process has no native layer (-103)", CameraWire.NO_NATIVE_LAYER)
        assertEquals("no surface (-104)", CameraWire.NO_SURFACE)
        assertEquals("no descriptor (-101)", CameraWire.NO_DESCRIPTOR)
        assertEquals(CameraErrorKind.CameraServiceUnavailable, CameraCodes.startError(CameraWire.SERVICE_CANNOT_RUN).kind)
        assertEquals(CameraErrorKind.CameraServiceUnavailable, CameraCodes.startError(CameraWire.NO_NATIVE_LAYER).kind)
        assertEquals(CameraErrorKind.RenderFailed, CameraCodes.startError(CameraWire.NO_SURFACE).kind)
        assertEquals(CameraErrorKind.NoDevice, CameraCodes.startError(CameraWire.NO_DESCRIPTOR).kind)
        assertEquals(CameraErrorKind.CameraServiceUnavailable, CameraCodes.listKind(CameraWire.listRefusal(-102)[0]))
    }

    @Test
    fun theErrorKindCrossesAsItsName() {
        val error = CameraError(CameraErrorKind.NoFrames, "no frames for 3000 ms")
        assertEquals("NoFrames", CameraWire.kindName(error))
        assertEquals(error, CameraWire.errorOf(CameraWire.kindName(error), error.detail))
        assertNull(CameraWire.kindName(null))
        assertNull(CameraWire.errorOf(null, "stopped"))
        assertEquals(CameraError(CameraErrorKind.RenderFailed, "x"), CameraWire.errorOf("FutureKind", "x"))
        CameraErrorKind.entries.forEach { kind ->
            assertEquals(kind, CameraWire.errorOf(kind.name, null)?.kind)
        }
    }

    @Test
    fun theAttachTrampolineWritesTheHexIds() {
        assertEquals("attach trampoline: C270 (046d:0825)", CameraWire.attachText("C270", 0x046D, 0x0825, true))
        assertEquals(
            "attach trampoline: USB device (1a86:7523), no video class interface",
            CameraWire.attachText(null, 0x1A86, 0x7523, false),
        )
    }

    @Test
    fun theApplicationSkipsItsStartUpInTheCameraAndAttachProcesses() {
        assertTrue(CameraWire.isAuxiliaryProcess("org.superdro:camera", "org.superdro"))
        assertTrue(CameraWire.isAuxiliaryProcess("org.superdro:usbattach", "org.superdro"))
        assertFalse(CameraWire.isAuxiliaryProcess("org.superdro", "org.superdro"))
        assertFalse(CameraWire.isAuxiliaryProcess(null, "org.superdro"))
        assertEquals("org.superdro.USB_CAMERA_PERMISSION", AndroidCameraAccessPlatform.permissionAction("org.superdro"))
    }
}
