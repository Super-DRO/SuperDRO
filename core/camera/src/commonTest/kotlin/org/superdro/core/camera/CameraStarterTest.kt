package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

class CameraStarterTest {

    private val camera = CameraIdentity(0x046D, 0x0825, deviceName = "a")
    private val mode = UvcMode(UvcCodec.Mjpeg, 640, 480, 333_333L, "MJPG")

    @Test
    fun aStartGoesAgainAfter400MsForTheRetrySet() = runTest {
        val times = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        val result = CameraStarter.withRetry(log = { text -> logs += text }) { attempt ->
            times += currentTime
            if (attempt < 3) Result.failure(CameraException(CameraError(CameraErrorKind.UsbTransferMemory, "(-11)"))) else Result.success(attempt)
        }
        assertEquals(Result.success(3), result)
        assertEquals(listOf(0L, 400L, 800L), times)
        assertEquals(
            listOf("start failed (UsbTransferMemory), attempt 2 of 3", "start failed (UsbTransferMemory), attempt 3 of 3"),
            logs,
        )
    }

    @Test
    fun theThirdFailureEndsTheStart() = runTest {
        var attempts = 0
        val result = CameraStarter.withRetry<Unit> {
            attempts += 1
            Result.failure(CameraException(CameraError(CameraErrorKind.ModeFailed)))
        }
        assertEquals(3, attempts)
        assertEquals(CameraErrorKind.ModeFailed, (result.exceptionOrNull() as CameraException).error.kind)
    }

    @Test
    fun anErrorOutsideTheRetrySetEndsAtOnce() = runTest {
        var attempts = 0
        val result = CameraStarter.withRetry<Unit> {
            attempts += 1
            Result.failure(CameraException(CameraCodes.startError("open failed: Other (-99)")))
        }
        assertEquals(1, attempts)
        assertEquals(CameraErrorKind.NeedsPoweredHub, (result.exceptionOrNull() as CameraException).error.kind)
        assertEquals(0L, currentTime)
    }

    @Test
    fun theFakeBackendGivesTheEventsOfARun() = runTest {
        val backend = FakeUvcCameraBackend(listOf(camera), modes = mapOf(camera.key to listOf(mode)))
        assertEquals(listOf(mode), backend.listModes(camera).getOrThrow())
        assertTrue(backend.requestAccess(camera).isSuccess)
        val events = backend.start(camera, mode, FakeUvcCameraBackend.NoTarget).take(3).toList()
        assertEquals(StreamEvent.Started(333_333L), events[0])
        assertEquals(StreamEvent.FirstFrame(640, 480, 200L), events[1])
        assertTrue(events[2] is StreamEvent.Stats)
        val unknown = CameraIdentity(1, 1, deviceName = "b")
        assertEquals(CameraErrorKind.OpenFailed, (backend.listModes(unknown).exceptionOrNull() as CameraException).error.kind)
    }

    @Test
    fun theFakeBackendReportsAttachAndDetach() = runTest {
        val backend = FakeUvcCameraBackend()
        val other = CameraIdentity(2, 2, deviceName = "c")
        backend.attach(other)
        assertEquals(listOf(other), backend.cameras.value)
        backend.detach(other)
        assertEquals(emptyList(), backend.cameras.value)
        val failing = FakeUvcCameraBackend(stream = { _, _ -> FakeStream(startErrors = listOf(CameraError(CameraErrorKind.AlreadyStreaming))) })
        val ended = failing.start(camera, mode, FakeUvcCameraBackend.NoTarget).first()
        assertEquals(StreamEvent.Ended(CameraError(CameraErrorKind.AlreadyStreaming)), ended)
    }
}
