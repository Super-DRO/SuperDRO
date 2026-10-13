package org.superdro.platform.android.camera

import android.graphics.Bitmap
import com.toxicbakery.logging.Arbor
import org.superdro.core.camera.CameraTimeouts

/**
 * The JNI layer of the camera (`src/main/cpp/superdro_uvc.c`). Only the camera process `:camera`
 * uses it. It loads `libusb1.0.so` (libusb, LGPL 2.1) first, then `libsuperdro_uvc.so` (libuvc and
 * the JNI functions). The header of `superdro_uvc.c` gives the rules of the native layer.
 */
object UvcNative {
    /** The index values of the meta array of [nativeAwaitFrame]. */
    const val META_WIDTH: Int = 0
    const val META_HEIGHT: Int = 1
    const val META_CODEC: Int = 2
    const val META_TIME_NS: Int = 3
    const val META_SEQUENCE: Int = 4
    const val META_DROPPED: Int = 5
    const val META_RUNTS: Int = 6
    const val META_SIZE: Int = 7

    /** The result of [nativeAwaitFrame] for a frame that is larger than the buffer. */
    const val FRAME_TOO_LARGE: Int = -3

    private val log = Arbor.tag("UvcNative")

    /** The load error, or null when the libraries loaded. */
    var loadError: String? = null
        private set

    /** True when both libraries loaded. */
    var loaded: Boolean = false
        private set

    /** The result of [nativeInit]: 0, or the code −105 when the context did not start. */
    var initResult: Int = 0
        private set

    /** Loads the libraries and starts the context with the control transfer limit. */
    @Synchronized
    fun load(): Boolean {
        if (loaded || loadError != null) return loaded
        try {
            System.loadLibrary("usb1.0")
            System.loadLibrary("superdro_uvc")
            loaded = true
            initResult = nativeInit(CameraTimeouts.CONTROL_TRANSFER_MS)
            log.i { "native layer ${nativeVersions()}, init $initResult" }
        } catch (error: UnsatisfiedLinkError) {
            loadError = error.message ?: error.toString()
            log.e(error) { "the native libraries did not load" }
        }
        return loaded
    }

    /** Starts the context. Gives 0, or −105. */
    @JvmStatic
    external fun nativeInit(controlTimeoutMs: Int): Int

    /** Gives "libusb a.b.c.d / libuvc x.y.z". */
    @JvmStatic
    external fun nativeVersions(): String

    /** Gives the mode list: the status, then codec, width, height, interval, and FourCC for each mode. */
    @JvmStatic
    external fun nativeListModes(fd: Int, connection: Int): IntArray?

    /** Starts the stream. Gives null, or an error text that ends with "(code)". */
    @JvmStatic
    external fun nativeStartStream(fd: Int, connection: Int, codec: Int, width: Int, height: Int, frameInterval: Int): String?

    /** The frame interval that the camera accepted, in 100 ns units. */
    @JvmStatic
    external fun nativeNegotiatedInterval(): Int

    /** The negotiated `dwMaxVideoFrameSize`. */
    @JvmStatic
    external fun nativeMaxFrameSize(): Int

    /**
     * Copies the next frame into [buffer]. Gives the byte count, 0 on a time-out, −1 when the
     * stream stopped, −2 with no stream, or [FRAME_TOO_LARGE].
     */
    @JvmStatic
    external fun nativeAwaitFrame(buffer: ByteArray, meta: LongArray, timeoutMs: Int): Int

    /** Converts a YUY2 frame into an `ARGB_8888` bitmap. Gives 0, −1, −2, or −3. */
    @JvmStatic
    external fun nativeYuy2ToBitmap(data: ByteArray, length: Int, width: Int, height: Int, bitmap: Bitmap): Int

    @JvmStatic
    external fun nativeStopStream()

    @JvmStatic
    external fun nativeCloseCamera()
}
