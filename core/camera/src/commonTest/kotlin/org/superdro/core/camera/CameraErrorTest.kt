package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraErrorTest {

    @Test
    fun thereAreThirteenKindsWithAMessageEach() {
        assertEquals(13, CameraErrorKind.entries.size)
        assertEquals(13, CameraErrorKind.entries.map { kind -> kind.messageKey }.toSet().size)
        assertEquals("camera_error_powered_hub", CameraErrorKind.NeedsPoweredHub.messageKey)
        assertEquals(CameraErrorKind.NoFrames, CameraErrorKind.fromName("NoFrames"))
        assertNull(CameraErrorKind.fromName("NotAKind"))
    }

    @Test
    fun theCodeIsTheLastNumberInBracketsAtTheEnd() {
        assertEquals(-100, CameraCodes.codeOf("already streaming (-100)"))
        assertEquals(-51, CameraCodes.codeOf("stream failed: Invalid mode (-51)  "))
        assertEquals(-11, CameraCodes.codeOf("open failed (2) and then (-11)"))
        assertNull(CameraCodes.codeOf("camera offers no MJPEG format"))
        assertNull(CameraCodes.codeOf("code (-5) is not at the end"))
        assertNull(CameraCodes.codeOf(null))
    }

    @Test
    fun eachStartCodeHasItsKind() {
        val table = mapOf(
            -11 to CameraErrorKind.UsbTransferMemory,
            -99 to CameraErrorKind.NeedsPoweredHub,
            -100 to CameraErrorKind.AlreadyStreaming,
            -101 to CameraErrorKind.NoDevice,
            -102 to CameraErrorKind.CameraServiceUnavailable,
            -103 to CameraErrorKind.CameraServiceUnavailable,
            -104 to CameraErrorKind.RenderFailed,
            -105 to CameraErrorKind.NativeUnavailable,
            -4 to CameraErrorKind.NoDevice,
            -51 to CameraErrorKind.ModeFailed,
            -2 to CameraErrorKind.ModeFailed,
            -6 to CameraErrorKind.ModeFailed,
            -7 to CameraErrorKind.ModeFailed,
        )
        table.forEach { (code, kind) -> assertEquals(kind, CameraCodes.startKind(code), "code $code") }
        assertEquals(CameraErrorKind.ModeFailed, CameraCodes.startKind(null))
        assertEquals(
            CameraError(CameraErrorKind.ModeFailed, "camera offers no H.264 format"),
            CameraCodes.startError("camera offers no H.264 format"),
        )
    }

    @Test
    fun anInitFailureDoesNotShowThePoweredHubText() {
        assertEquals(CameraErrorKind.NativeUnavailable, CameraCodes.startError("context init failed (-105)").kind)
        assertEquals(CameraErrorKind.NativeUnavailable, CameraCodes.listKind(CameraCodes.INIT_FAILED))
        assertEquals(CameraErrorKind.NeedsPoweredHub, CameraCodes.startError("open failed: Other (-99)").kind)
        assertEquals(CameraErrorKind.NeedsPoweredHub, CameraCodes.listKind(-99))
    }

    @Test
    fun eachListStatusHasItsKind() {
        assertNull(CameraCodes.listKind(0))
        assertEquals(CameraErrorKind.CameraServiceUnavailable, CameraCodes.listKind(Int.MIN_VALUE))
        assertEquals(CameraErrorKind.CameraServiceUnavailable, CameraCodes.listKind(-102))
        assertEquals(CameraErrorKind.CameraServiceUnavailable, CameraCodes.listKind(-103))
        assertEquals(CameraErrorKind.NoDevice, CameraCodes.listKind(-101))
        assertEquals(CameraErrorKind.NoDevice, CameraCodes.listKind(-1))
        assertEquals(CameraErrorKind.NeedsPoweredHub, CameraCodes.listKind(-99))
        assertEquals(CameraErrorKind.OpenFailed, CameraCodes.listKind(-3))
        assertEquals(CameraErrorKind.OpenFailed, CameraCodes.listKind(UvcModeList.NO_REPLY))
        assertEquals(
            CameraError(CameraErrorKind.OpenFailed, "mode list status -12"),
            CameraCodes.listError(UvcModeList(-12, emptyList())),
        )
        assertNull(CameraCodes.listError(UvcModeList(0, emptyList())))
    }

    @Test
    fun onlyTransferMemoryAndModeFailedGoAgainUpToThreeAttempts() {
        assertEquals(setOf(CameraErrorKind.UsbTransferMemory, CameraErrorKind.ModeFailed), CameraRetry.RETRY_SET)
        assertEquals(3, CameraRetry.MAX_ATTEMPTS)
        assertEquals(400L, CameraRetry.PAUSE_MS)
        val mode = CameraError(CameraErrorKind.ModeFailed)
        assertTrue(CameraRetry.shouldRetry(mode, attempt = 1))
        assertTrue(CameraRetry.shouldRetry(mode, attempt = 2))
        assertFalse(CameraRetry.shouldRetry(mode, attempt = 3))
        assertFalse(CameraRetry.shouldRetry(CameraError(CameraErrorKind.NeedsPoweredHub), attempt = 1))
        assertTrue(CameraErrorKind.UsbTransferMemory.isRetried)
        assertFalse(CameraErrorKind.NoFrames.isRetried)
        assertEquals("start failed (ModeFailed), attempt 2 of 3", CameraRetry.logText(mode, 2))
    }

    @Test
    fun theVideoErrorDialogOffersTheSetupForAModeThatDidNotStart() {
        assertEquals(CameraErrorDialog("camera_play_needs_setup", offersSetup = true), CameraErrorDialog.of(CameraErrorKind.NoFrames))
        assertEquals(CameraErrorDialog("camera_play_needs_setup", offersSetup = true), CameraErrorDialog.of(CameraErrorKind.ModeFailed))
        assertEquals(
            CameraErrorDialog("camera_error_camera_permission", offersSetup = true),
            CameraErrorDialog.of(CameraErrorKind.CameraPermissionMissing),
        )
        assertEquals(CameraErrorDialog("camera_error_disconnected", offersSetup = false), CameraErrorDialog.of(CameraErrorKind.Disconnected))
    }
}
