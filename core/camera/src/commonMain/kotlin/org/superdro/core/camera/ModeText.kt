package org.superdro.core.camera

import kotlin.math.abs
import kotlin.math.floor
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus

/**
 * The number text of a frame rate. Common code has no locale. The UI gives the decimal separator
 * of the locale.
 */
object FrameRateText {

    /** The decimals of a nominal rate from a frame interval, for example `29.97`. */
    const val NOMINAL_DECIMALS: Int = 2

    /** The decimals of a measured rate, for example `29.8`. */
    const val MEASURED_DECIMALS: Int = 1

    /**
     * Gives [rate] with not more than [decimals] decimals and no trailing zeros, for example `30`,
     * `7.5`, or `29.97`. A rate that is not more than 0 gives null.
     */
    fun format(rate: Double, decimals: Int, decimalSeparator: Char = '.'): String? {
        if (!(rate > 0.0) || rate.isInfinite()) return null
        var factor = 1L
        repeat(decimals) { factor *= 10L }
        // Half up: 7.25 gives 7.3 with 1 decimal.
        val scaled = floor(rate * factor + 0.5).toLong()
        val whole = scaled / factor
        var fraction = (scaled % factor).toString().padStart(decimals, '0')
        fraction = fraction.trimEnd('0')
        return if (fraction.isEmpty()) whole.toString() else "$whole$decimalSeparator$fraction"
    }
}

/**
 * The values of one line of the mode list. The rate comes from the real frame
 * interval of the mode, not from the default interval of the frame descriptor. When the mode
 * memory has a measured rate, the line also shows it.
 *
 * The string resources of `strings_camera.xml` give the text: `camera_mode_format`,
 * `camera_mode_format_measured`, and `camera_mode_format_no_rate`. [englishText] gives the same
 * English text for the log and the tests.
 *
 * @property nominalFps The rate of the frame interval, or null when the interval is 0.
 * @property measuredFps The measured rate of the mode memory, or null.
 * @property status The status of the mode memory: GOOD shows in bold, BAD with a line through
 *   it. Null for a mode with no test result.
 */
data class ModeLabel(
    val codec: UvcCodec,
    val width: Int,
    val height: Int,
    val nominalFps: String?,
    val measuredFps: String?,
    val status: CameraModeStatus?,
) {
    /** The resource name of the line format. */
    val formatKey: String
        get() = when {
            nominalFps == null -> "camera_mode_format_no_rate"
            measuredFps != null -> "camera_mode_format_measured"
            else -> "camera_mode_format"
        }

    /** The English text, the same as the base strings of `strings_camera.xml`. */
    fun englishText(): String {
        val head = "${codecName(codec)} $width × $height"
        return when {
            nominalFps == null -> head
            measuredFps != null -> "$head, $nominalFps fps (measured $measuredFps fps)"
            else -> "$head, $nominalFps fps"
        }
    }

    companion object {
        /**
         * Gives the line of [mode]. [result] is the mode memory row of the mode, or null. A
         * measured rate that is less than 0.05 fps from the nominal rate does not show.
         */
        fun of(mode: UvcMode, result: CameraModeResult?, decimalSeparator: Char = '.'): ModeLabel {
            val nominal = FrameRateText.format(mode.frameRate, FrameRateText.NOMINAL_DECIMALS, decimalSeparator)
            val measuredRate = result?.measuredFps
            val measured = if (nominal != null && measuredRate != null && abs(measuredRate - mode.frameRate) >= 0.05) {
                FrameRateText.format(measuredRate, FrameRateText.MEASURED_DECIMALS, decimalSeparator)
            } else {
                null
            }
            return ModeLabel(mode.codec, mode.width, mode.height, nominal, measured, result?.status)
        }

        /** The resource name of the codec text. */
        fun codecKey(codec: UvcCodec): String = when (codec) {
            UvcCodec.Mjpeg -> "camera_codec_mjpeg"
            UvcCodec.H264 -> "camera_codec_h264"
            UvcCodec.Uncompressed -> "camera_codec_yuy2"
            UvcCodec.Unknown -> "camera_codec_unknown"
        }

        /** The English codec text, the same as the base strings. */
        fun codecName(codec: UvcCodec): String = when (codec) {
            UvcCodec.Mjpeg -> "MJPEG"
            UvcCodec.H264 -> "H.264"
            UvcCodec.Uncompressed -> "YUY2"
            UvcCodec.Unknown -> "Other format"
        }
    }
}
