package org.superdro.core.camera

/**
 * The video codec of a UVC format.
 *
 * @property code The value in the mode list of the native layer and in the mode key.
 * @property storeName The value of `CameraModeResult.codec`.
 */
enum class UvcCodec(val code: Int, val storeName: String) {
    /** `VS_FORMAT_MJPEG` (subtype `0x06`). The camera process decodes it with `BitmapFactory`. */
    Mjpeg(code = 0, storeName = "MJPEG"),

    /**
     * H.264: a frame-based format (subtype `0x10`) with a GUID that starts with `H264`, or the
     * UVC 1.5 H.264 format (subtype `0x13`). `MediaCodec` decodes it into the `Surface`.
     */
    H264(code = 1, storeName = "H264"),

    /** `VS_FORMAT_UNCOMPRESSED` (subtype `0x04`). Only YUY2 is usable (see [UvcMode.isUsable]). */
    Uncompressed(code = 2, storeName = "UNCOMPRESSED"),

    /** A format that SuperDRO cannot show. */
    Unknown(code = -1, storeName = "UNKNOWN");

    companion object {
        /** Gives the codec of a native [code]. An unknown value gives [Unknown]. */
        fun fromCode(code: Int): UvcCodec = entries.firstOrNull { codec -> codec.code == code } ?: Unknown

        /** Gives the codec of a stored [name]. An unknown name gives [Unknown]. */
        fun fromStoreName(name: String): UvcCodec = entries.firstOrNull { codec -> codec.storeName == name } ?: Unknown
    }
}

/**
 * One video mode of a UVC camera: a codec, a frame size, and one
 * frame interval. SuperDRO gives one mode for each frame interval of a frame descriptor, not only
 * for the default interval.
 *
 * @property frameInterval The frame interval in 100 ns units. 0 tells that the interval is not known.
 * @property fourcc The first 4 bytes of the format GUID as text, for example `YUY2` or `MJPG`.
 */
data class UvcMode(
    val codec: UvcCodec,
    val width: Int,
    val height: Int,
    val frameInterval: Long,
    val fourcc: String = "",
) {
    /** The mode key `codec/width/height/frameInterval`, for example `0/1920/1080/333333`. */
    val key: String get() = "${codec.code}/$width/$height/$frameInterval"

    /** The number of pixels of one frame. */
    val pixels: Long get() = width.toLong() * height.toLong()

    /** The frame rate of the real interval in frames for each second. 0.0 when the interval is 0. */
    val frameRate: Double
        get() = if (frameInterval > 0L) INTERVAL_UNITS_PER_SECOND.toDouble() / frameInterval.toDouble() else 0.0

    /**
     * True when SuperDRO can show the mode: MJPEG and H.264 always, an uncompressed mode only with
     * the FourCC `YUY2` or `YUYV` (upper case compare).
     */
    val isUsable: Boolean
        get() = when (codec) {
            UvcCodec.Mjpeg, UvcCodec.H264 -> width > 0 && height > 0
            UvcCodec.Uncompressed -> width > 0 && height > 0 && fourcc.uppercase() in YUY2_FOURCCS
            UvcCodec.Unknown -> false
        }

    companion object {
        /** 1 s in the 100 ns units of a frame interval. */
        const val INTERVAL_UNITS_PER_SECOND: Long = 10_000_000L

        /** The FourCC values of a usable uncompressed mode. */
        val YUY2_FOURCCS: Set<String> = setOf("YUY2", "YUYV")

        /** Gives the frame interval of [framesPerSecond], rounded to the nearest 100 ns unit. */
        fun intervalOf(framesPerSecond: Double): Long =
            if (framesPerSecond > 0.0) kotlin.math.round(INTERVAL_UNITS_PER_SECOND / framesPerSecond).toLong() else 0L
    }
}

/** The FourCC text of a format GUID. */
object FourCc {

    /**
     * Gives the text of a packed FourCC: 4 ASCII characters, high byte first. A byte outside the
     * range 32 to 126 gives `?`.
     */
    fun unpack(packed: Int): String = buildString {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val byte = (packed ushr shift) and 0xFF
            append(if (byte in 32..126) byte.toChar() else '?')
        }
    }

    /** Packs the first 4 characters of [text], high byte first. A missing character gives a space. */
    fun pack(text: String): Int {
        var packed = 0
        for (index in 0 until 4) {
            val code = text.getOrNull(index)?.code?.and(0xFF) ?: ' '.code
            packed = (packed shl 8) or code
        }
        return packed
    }
}

/**
 * The reply of the native mode list: `[0]` is the status (0 = success, else an error code of
 * [CameraCodes]), then 5 values for each mode: codec, width, height, frame interval (100 ns
 * units, unsigned), and the packed FourCC.
 */
data class UvcModeList(val status: Int, val modes: List<UvcMode>) {

    /** True when the camera gave its modes. */
    val isSuccess: Boolean get() = status == 0

    companion object {
        /** The values for each mode after the status value. */
        const val VALUES_PER_MODE: Int = 5

        /** The maximum number of modes in one reply. The native layer stops the list at this number. */
        const val MAX_MODES: Int = 512

        /** The status of a reply that did not come (no reply from the camera process). */
        const val NO_REPLY: Int = Int.MIN_VALUE + 1

        /** Reads the native reply. A null or empty reply gives the status [NO_REPLY]. */
        fun parse(values: IntArray?): UvcModeList {
            if (values == null || values.isEmpty()) return UvcModeList(NO_REPLY, emptyList())
            val status = values[0]
            if (status != 0) return UvcModeList(status, emptyList())
            val count = minOf((values.size - 1) / VALUES_PER_MODE, MAX_MODES)
            val modes = (0 until count).map { index ->
                val base = 1 + index * VALUES_PER_MODE
                UvcMode(
                    codec = UvcCodec.fromCode(values[base]),
                    width = values[base + 1],
                    height = values[base + 2],
                    frameInterval = values[base + 3].toLong() and 0xFFFF_FFFFL,
                    fourcc = FourCc.unpack(values[base + 4]),
                )
            }
            return UvcModeList(status, modes)
        }

        /** Writes [modes] in the native format with the status 0. The fake back end uses it. */
        fun encode(modes: List<UvcMode>): IntArray {
            val values = IntArray(1 + modes.size * VALUES_PER_MODE)
            modes.forEachIndexed { index, mode ->
                val base = 1 + index * VALUES_PER_MODE
                values[base] = mode.codec.code
                values[base + 1] = mode.width
                values[base + 2] = mode.height
                values[base + 3] = mode.frameInterval.toInt()
                values[base + 4] = FourCc.pack(mode.fourcc)
            }
            return values
        }
    }
}
