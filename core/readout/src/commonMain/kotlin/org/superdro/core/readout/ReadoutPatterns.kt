package org.superdro.core.readout

import org.superdro.core.model.ReadoutFormat

/**
 * The number patterns of the readout values.
 *
 * | `display_format` | Inch pattern | mm pattern |
 * |---|---|---|
 * | 2 | `##0.000` | `###0.00` |
 * | 3 (default) | `##0.0000` | `###0.000` |
 * | 5 | `##0.00000` | `###0.0000` |
 *
 * The codes 0 and 1 of a TouchDRO settings file map to 2 and 3. The code 4 maps to 5 ([formatForCode]).
 */
object ReadoutPatterns {

    /** The pattern of the angular axis in degrees and in divisions. */
    val ANGULAR: NumberPattern = NumberPattern("##0.00")

    /** The pattern of the tachometer RPM. */
    val RPM: NumberPattern = NumberPattern("####0")

    /** The pattern of the surface speed (SFM or m/min). */
    val SURFACE_SPEED: NumberPattern = NumberPattern("###0.00")

    /** The pattern of the thread pitch in mm. */
    val THREAD_PITCH_MM: NumberPattern = NumberPattern("###0.00")

    /** The pattern of the threads per inch. */
    val THREADS_PER_INCH: NumberPattern = NumberPattern("###0.0")

    private val INCH: Map<ReadoutFormat, NumberPattern> = ReadoutFormat.entries.associateWith { format ->
        NumberPattern("##0." + "0".repeat(format.inchDecimals))
    }

    private val MM: Map<ReadoutFormat, NumberPattern> = ReadoutFormat.entries.associateWith { format ->
        NumberPattern("###0." + "0".repeat(format.mmDecimals))
    }

    /** Gives the pattern of a linear value for [format]: mm when [metric] is true, else inch. */
    fun linear(format: ReadoutFormat, metric: Boolean): NumberPattern =
        if (metric) MM.getValue(format) else INCH.getValue(format)

    /**
     * Gives the format of a `display_format` code. The codes 0 and 1 give 2 and 3, and the code
     * 4 gives 5. An unknown code gives the default format 3.
     */
    fun formatForCode(code: Int): ReadoutFormat = ReadoutFormat.fromTouchDroCode(code) ?: ReadoutFormat.Format3
}
