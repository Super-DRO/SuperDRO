package org.superdro.core.camera

import kotlin.math.roundToInt

/**
 * The rules of the render loop in the camera process. The
 * Android stream worker uses these values. They are common code, so that a unit test can examine
 * them.
 */
object StreamRules {
    /** The minimum frame buffer: 1 MiB. */
    const val MIN_BUFFER_BYTES: Int = 1_048_576

    /** The maximum frame size: 64 MiB. A larger frame size gives [CameraErrorKind.ModeFailed]. */
    const val MAX_FRAME_BYTES: Int = 67_108_864

    /** The time limit of one frame wait. */
    const val FRAME_WAIT_MS: Int = 250

    /** The number of empty frame waits in sequence that stop the run: 12 × 250 ms = 3000 ms. */
    const val EMPTY_WAIT_LIMIT: Int = 12

    /** The number of frames in sequence that do not decode and stop the run. */
    const val DECODE_FAILURE_LIMIT: Int = 30

    /** The minimum time between two statistics events. */
    const val STATS_INTERVAL_MS: Long = 250L

    /** The minimum size of an accepted MJPEG frame. */
    const val MIN_MJPEG_BYTES: Int = 1024

    /** An accepted H.264 frame has more bytes than this value. */
    const val MIN_H264_BYTES_EXCLUSIVE: Int = 7

    /** The wait for a free input buffer of the H.264 decoder, in µs. */
    const val H264_INPUT_TIMEOUT_US: Long = 100_000L

    /**
     * Gives the frame buffer size for the negotiated `dwMaxVideoFrameSize`, or null when the size
     * is more than [MAX_FRAME_BYTES].
     */
    fun bufferSize(maxVideoFrameSize: Long): Int? = when {
        maxVideoFrameSize > MAX_FRAME_BYTES -> null
        else -> maxOf(maxVideoFrameSize, MIN_BUFFER_BYTES.toLong()).toInt()
    }

    /** The text of a frame size that is too large. */
    fun frameSizeText(maxVideoFrameSize: Long): String = "frame size $maxVideoFrameSize exceeds the ceiling"

    /**
     * True when the native frame check accepts a frame of [length] bytes. An MJPEG frame has 1024
     * bytes or more and starts with `FF D8`. An H.264 frame has more
     * than 7 bytes. A YUY2 frame has `width × height × 2` bytes or more.
     */
    fun accepts(codec: UvcCodec, data: ByteArray, length: Int, width: Int, height: Int): Boolean = when (codec) {
        UvcCodec.Mjpeg -> length >= MIN_MJPEG_BYTES && data.size >= 2 &&
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
        UvcCodec.H264 -> length > MIN_H264_BYTES_EXCLUSIVE
        UvcCodec.Uncompressed -> length.toLong() >= yuy2Bytes(width, height)
        UvcCodec.Unknown -> false
    }

    /** The bytes of one YUY2 frame. */
    fun yuy2Bytes(width: Int, height: Int): Long = width.toLong() * height.toLong() * 2L

    /**
     * Gives the error kind when a run ends with no frame for [EMPTY_WAIT_LIMIT] waits, or with a
     * stream end of the native layer: [CameraErrorKind.NoFrames] when no frame came, else
     * [CameraErrorKind.Disconnected].
     */
    fun endKind(framesSeen: Long): CameraErrorKind =
        if (framesSeen == 0L) CameraErrorKind.NoFrames else CameraErrorKind.Disconnected
}

/**
 * Counts the empty frame waits of the render loop.
 * [onWait] gives true when the run must stop: 12 empty waits in sequence.
 */
class FrameWatchdog(private val limit: Int = StreamRules.EMPTY_WAIT_LIMIT) {
    private var emptyWaits = 0

    /** Records one wait. [gotFrame] is true when the wait gave a frame. */
    fun onWait(gotFrame: Boolean): Boolean {
        emptyWaits = if (gotFrame) 0 else emptyWaits + 1
        return emptyWaits >= limit
    }

    /** The time with no frame, in ms. */
    val silentMs: Long get() = emptyWaits.toLong() * StreamRules.FRAME_WAIT_MS

    /** The end text, for example "no frames for 3000 ms". */
    fun endText(): String = "no frames for $silentMs ms"
}

/**
 * The statistics of a run: the frames that the worker drew, and the
 * rate `(frames − 1) × 1000 / (tLast − tFirst)` in fps. [onFrame] gives true when the worker must
 * send a statistics event: at most one time each [StreamRules.STATS_INTERVAL_MS].
 */
class StreamStatistics(private val intervalMs: Long = StreamRules.STATS_INTERVAL_MS) {
    private var firstMs = 0L
    private var lastMs = 0L
    private var lastSentMs = Long.MIN_VALUE

    /** The frames that the worker drew. */
    var frames: Int = 0
        private set

    /** Records one drawn frame at [timeMs]. True when a statistics event is due. */
    fun onFrame(timeMs: Long): Boolean {
        if (frames == 0) firstMs = timeMs
        lastMs = timeMs
        frames += 1
        if (lastSentMs == Long.MIN_VALUE || timeMs - lastSentMs >= intervalMs) {
            lastSentMs = timeMs
            return true
        }
        return false
    }

    /** The measured rate, or 0.0 with less than 2 frames or no time between them. */
    val fps: Double
        get() = if (frames < 2 || lastMs <= firstMs) 0.0 else (frames - 1) * 1000.0 / (lastMs - firstMs).toDouble()
}

/** The H.264 rules of the camera process. */
object H264Stream {
    /** The NAL unit type of a sequence parameter set. */
    const val NAL_SPS: Int = 7

    /**
     * True when [data] has an Annex B start code (`00 00 01` or `00 00 00 01`) with a NAL unit of
     * the type 7 (SPS) after it. The decoder starts at the first frame with an SPS.
     */
    fun containsSps(data: ByteArray, length: Int = data.size): Boolean {
        val end = minOf(length, data.size)
        var index = 0
        while (index + 3 < end) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte()) {
                val nalType = data[index + 3].toInt() and 0x1F
                if (nalType == NAL_SPS) return true
                index += 3
            } else {
                index += 1
            }
        }
        return false
    }
}

/**
 * The presentation time stamps of the H.264 decoder. A time stamp comes from the capture
 * time of the frame in the native layer, relative to the first frame. When the capture time is not
 * there or does not increase, the next time stamp is the last one plus the negotiated frame
 * interval. The time stamps always increase.
 *
 * @param frameIntervalUs The negotiated frame interval in µs. A value that is not more than 0
 *   gives 33 333 µs.
 */
class H264Timestamps(frameIntervalUs: Long) {
    private val stepUs = if (frameIntervalUs > 0L) frameIntervalUs else DEFAULT_STEP_US
    private var firstCaptureNs = Long.MIN_VALUE
    private var lastUs = Long.MIN_VALUE

    /** Gives the time stamp in µs of a frame with the capture time [captureTimeNs] (0 = not known). */
    fun next(captureTimeNs: Long): Long {
        val candidate = if (captureTimeNs > 0L) {
            if (firstCaptureNs == Long.MIN_VALUE) firstCaptureNs = captureTimeNs
            (captureTimeNs - firstCaptureNs) / 1000L
        } else {
            null
        }
        val value = when {
            lastUs == Long.MIN_VALUE -> candidate ?: 0L
            candidate != null && candidate > lastUs -> candidate
            else -> lastUs + stepUs
        }
        lastUs = value
        return value
    }

    companion object {
        /** The step for an unknown interval: 30 fps. */
        const val DEFAULT_STEP_US: Long = 33_333L

        /** Gives the interval in µs of a frame interval in 100 ns units. */
        fun intervalUs(frameInterval: Long): Long = frameInterval / 10L
    }
}

/**
 * The fit and fill rules of the video view. The setup preview fits
 * the video in the view. The backdrop fills the view and the parent cuts the parts outside.
 */
object VideoFit {
    /** The aspect ratio when the mode size is not known: 16:9. */
    const val DEFAULT_ASPECT: Double = 16.0 / 9.0

    /** The aspect ratio `width / height` of a video, or [DEFAULT_ASPECT]. */
    fun aspect(videoWidth: Int, videoHeight: Int): Double =
        if (videoWidth > 0 && videoHeight > 0) videoWidth.toDouble() / videoHeight.toDouble() else DEFAULT_ASPECT

    /**
     * Gives the size of the video view in a space of [maxWidth] × [maxHeight] px. [fill] true:
     * the view covers the space and keeps the aspect ratio. [fill] false: the view is in the space.
     */
    fun size(maxWidth: Int, maxHeight: Int, aspect: Double, fill: Boolean): Pair<Int, Int> {
        if (maxWidth <= 0 || maxHeight <= 0) return maxWidth.coerceAtLeast(0) to maxHeight.coerceAtLeast(0)
        val widthForHeight = (maxHeight * aspect).roundToInt()
        val heightForWidth = (maxWidth / aspect).roundToInt()
        val widthLimited = heightForWidth <= maxHeight
        return if (widthLimited != fill) {
            maxWidth to heightForWidth
        } else {
            widthForHeight to maxHeight
        }
    }

    /**
     * The scale from video pixels to view pixels. [fill] true gives the larger scale (the video
     * covers the view), false the smaller scale.
     */
    fun scale(viewWidth: Double, viewHeight: Double, videoWidth: Int, videoHeight: Int, fill: Boolean): Double {
        if (videoWidth <= 0 || videoHeight <= 0) return 1.0
        val sx = viewWidth / videoWidth
        val sy = viewHeight / videoHeight
        return if (fill) maxOf(sx, sy) else minOf(sx, sy)
    }
}
