package org.superdro.platform.android.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import androidx.core.graphics.createBitmap
import com.toxicbakery.logging.Arbor
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraTimeouts
import org.superdro.core.camera.FrameWatchdog
import org.superdro.core.camera.H264Stream
import org.superdro.core.camera.H264Timestamps
import org.superdro.core.camera.StreamRules
import org.superdro.core.camera.StreamStatistics
import org.superdro.core.camera.UvcCodec

/** The events of a [CameraStreamWorker] to the camera service. */
interface StreamWorkerEvents {
    fun onFirstFrame(run: Int, width: Int, height: Int)

    fun onStats(run: Int, frames: Int, width: Int, height: Int, fps: Double)

    fun onDiagnostic(run: Int, text: String)

    /** The run ended. [error] is null for a stop by the user. */
    fun onEnded(run: Int, error: CameraError?)
}

/**
 * The render loop of one run in the camera process. The thread
 * waits for each frame, decodes it, and draws it into the [surface]. Frames do not leave the
 * process.
 *
 * - MJPEG: `BitmapFactory` and `Surface.lockCanvas`.
 * - YUY2: the native conversion into a bitmap, then `Surface.lockCanvas`.
 * - H.264: `MediaCodec` "video/avc" with the output [surface]. The decoder starts at the first frame
 *   with an SPS. The time stamps come from the capture time of each frame.
 */
class CameraStreamWorker(
    private val run: Int,
    private val codec: UvcCodec,
    private val width: Int,
    private val height: Int,
    private val negotiatedInterval: Long,
    private val surface: Surface,
    private val events: StreamWorkerEvents,
) {
    @Volatile
    private var active = true
    private val thread = Thread({ loop() }, "superdro-uvc-render")
    private var bitmap: Bitmap? = null
    private val statistics = StreamStatistics()
    private var firstFrameSent = false
    private var framesSeen = 0L

    /** Starts the render thread. */
    fun start() {
        thread.start()
    }

    /**
     * Stops the loop and waits [CameraTimeouts.RENDER_STOP_MS] for the thread. Gives false when the
     * thread did not stop: then the caller must not release the [surface].
     */
    fun stop(): Boolean {
        active = false
        if (Thread.currentThread() !== thread) thread.join(CameraTimeouts.RENDER_STOP_MS)
        val stopped = !thread.isAlive
        if (stopped) {
            bitmap?.recycle()
            bitmap = null
        } else {
            log.w { "the render thread did not stop in ${CameraTimeouts.RENDER_STOP_MS} ms, it keeps its surface" }
        }
        return stopped
    }

    private fun loop() {
        val error: CameraError? = try {
            val maxFrame = UvcNative.nativeMaxFrameSize().toLong() and 0xFFFF_FFFFL
            val size = StreamRules.bufferSize(maxFrame)
            if (size == null) {
                CameraError(CameraErrorKind.ModeFailed, StreamRules.frameSizeText(maxFrame))
            } else if (codec == UvcCodec.H264) {
                runH264(ByteArray(size))
            } else {
                runBitmaps(ByteArray(size))
            }
        } catch (failure: Throwable) {
            log.e(failure) { "render loop failed" }
            CameraError(CameraErrorKind.RenderFailed, failure.message ?: failure.toString())
        }
        if (statistics.frames > 0) {
            events.onStats(run, statistics.frames, width, height, statistics.fps)
        }
        events.onEnded(run, if (active) error else null)
    }

    /** Waits for a frame. Gives the bytes, 0 for a wait with no frame, or a negative end value. */
    private fun await(buffer: ByteArray, meta: LongArray): Int = UvcNative.nativeAwaitFrame(buffer, meta, StreamRules.FRAME_WAIT_MS)

    /** The result of one wait in the loop: null to go on, or the error that ends the run. */
    private fun checkWait(count: Int, watchdog: FrameWatchdog): CameraError? = when {
        count > 0 -> {
            framesSeen += 1
            watchdog.onWait(gotFrame = true)
            null
        }
        count == 0 || count == UvcNative.FRAME_TOO_LARGE ->
            if (watchdog.onWait(gotFrame = false)) CameraError(StreamRules.endKind(framesSeen), watchdog.endText()) else null
        else -> CameraError(StreamRules.endKind(framesSeen), "stream ended (code $count)")
    }

    private fun runBitmaps(buffer: ByteArray): CameraError? {
        val meta = LongArray(UvcNative.META_SIZE)
        val watchdog = FrameWatchdog()
        val options = BitmapFactory.Options().apply { inMutable = true }
        var failures = 0
        var refused = 0
        while (active && surface.isValid) {
            val count = await(buffer, meta)
            checkWait(count, watchdog)?.let { error -> return error }
            if (count <= 0) continue
            val frameWidth = meta[UvcNative.META_WIDTH].toInt()
            val frameHeight = meta[UvcNative.META_HEIGHT].toInt()
            if (codec == UvcCodec.Uncompressed) {
                // A refused YUY2 frame is not a decode failure.
                val image = yuy2Bitmap(buffer, count, frameWidth, frameHeight)
                if (image == null) {
                    refused += 1
                    if (refused % 60 == 1) log.w { "YUY2 frame refused ($refused)" }
                } else {
                    draw(image)
                    onDrawn(image.width, image.height)
                }
                continue
            }
            val image = decodeJpeg(buffer, count, options)
            if (image == null) {
                failures += 1
                if (failures >= StreamRules.DECODE_FAILURE_LIMIT) {
                    return CameraError(CameraErrorKind.RenderFailed, "${StreamRules.DECODE_FAILURE_LIMIT} consecutive frames could not be decoded")
                }
                continue
            }
            failures = 0
            draw(image)
            onDrawn(image.width, image.height)
        }
        return null
    }

    private fun decodeJpeg(buffer: ByteArray, count: Int, options: BitmapFactory.Options): Bitmap? {
        options.inBitmap = bitmap
        val image = try {
            BitmapFactory.decodeByteArray(buffer, 0, count, options)
        } catch (_: IllegalArgumentException) {
            options.inBitmap = null
            BitmapFactory.decodeByteArray(buffer, 0, count, options)
        }
        if (image != null) bitmap = image
        return image
    }

    private fun yuy2Bitmap(buffer: ByteArray, count: Int, frameWidth: Int, frameHeight: Int): Bitmap? {
        if (count.toLong() < StreamRules.yuy2Bytes(frameWidth, frameHeight)) return null
        val target = bitmap?.takeIf { image -> image.width == frameWidth && image.height == frameHeight }
            ?: createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888).also { image ->
                image.setHasAlpha(false)
                bitmap?.recycle()
                bitmap = image
            }
        return when (val result = UvcNative.nativeYuy2ToBitmap(buffer, count, frameWidth, frameHeight, target)) {
            0 -> target
            -1, -2 -> null
            else -> throw IllegalStateException("YUY2 conversion failed ($result)")
        }
    }

    private fun draw(image: Bitmap) {
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawBitmap(image, null, Rect(0, 0, canvas.width, canvas.height), null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun onDrawn(frameWidth: Int, frameHeight: Int) {
        if (!firstFrameSent) {
            firstFrameSent = true
            events.onFirstFrame(run, frameWidth, frameHeight)
        }
        if (statistics.onFrame(SystemClock.elapsedRealtime())) {
            events.onStats(run, statistics.frames, frameWidth, frameHeight, statistics.fps)
        }
    }

    private fun runH264(buffer: ByteArray): CameraError? {
        val meta = LongArray(UvcNative.META_SIZE)
        val watchdog = FrameWatchdog()
        val decoder = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height), surface, null, 0)
                start()
            }
        } catch (failure: Exception) {
            return CameraError(CameraErrorKind.ModeFailed, "H.264 path failed: ${failure.message}")
        }
        events.onDiagnostic(run, "MediaCodec: ${decoder.name}")
        val timestamps = H264Timestamps(H264Timestamps.intervalUs(negotiatedInterval))
        val info = MediaCodec.BufferInfo()
        var keyframeSeen = false
        var skipped = 0
        var stalls = 0
        try {
            while (active && surface.isValid) {
                val count = await(buffer, meta)
                checkWait(count, watchdog)?.let { error -> return error }
                if (count > 0) {
                    if (!keyframeSeen) {
                        if (!H264Stream.containsSps(buffer, count)) {
                            skipped += 1
                        } else {
                            keyframeSeen = true
                            events.onDiagnostic(run, "keyframe after $skipped frames, feeding the decoder")
                        }
                    }
                    if (keyframeSeen) {
                        val index = decoder.dequeueInputBuffer(StreamRules.H264_INPUT_TIMEOUT_US)
                        if (index < 0) {
                            stalls += 1
                            if (stalls % 30 == 1) {
                                log.w { "decoder input stalled: $stalls frames dropped, the picture breaks until the next keyframe" }
                            }
                        } else {
                            val input = decoder.getInputBuffer(index) ?: error("no input buffer $index")
                            input.clear()
                            input.put(buffer, 0, count)
                            decoder.queueInputBuffer(index, 0, count, timestamps.next(meta[UvcNative.META_TIME_NS]), 0)
                        }
                    }
                }
                while (true) {
                    val output = decoder.dequeueOutputBuffer(info, 0L)
                    if (output < 0) break
                    decoder.releaseOutputBuffer(output, true)
                    onDrawn(width, height)
                }
            }
            return null
        } catch (failure: Exception) {
            val kind = if (firstFrameSent) CameraErrorKind.RenderFailed else CameraErrorKind.ModeFailed
            return CameraError(kind, "H.264 path failed: ${failure.message}")
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    }

    private companion object {
        val log = Arbor.tag("CameraStreamWorker")
    }
}
