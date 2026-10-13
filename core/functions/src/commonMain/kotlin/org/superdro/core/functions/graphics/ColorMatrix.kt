package org.superdro.core.functions.graphics

import kotlin.math.roundToInt

/** The channel weights of a gray image. The sum is 1, so white stays white. */
data class GrayWeights(val r: Double, val g: Double, val b: Double)

/**
 * A 4 × 5 color matrix, row by row, as Android `ColorMatrix`. The rows give R, G, B, and A from
 * the column (R, G, B, A, 1). The channel values are 0 to 255.
 */
data class ColorMatrix(val values: List<Double>) {

    init {
        require(values.size == 20) { "A color matrix has 20 values, not ${values.size}" }
    }

    /** Gives the matrix that applies this matrix first and [next] after it. */
    fun then(next: ColorMatrix): ColorMatrix {
        val result = DoubleArray(20)
        for (row in 0 until 4) {
            for (column in 0 until 5) {
                var sum = 0.0
                for (k in 0 until 4) sum += next.values[row * 5 + k] * values[k * 5 + column]
                if (column == 4) sum += next.values[row * 5 + 4]
                result[row * 5 + column] = sum
            }
        }
        return ColorMatrix(result.toList())
    }

    /** Gives the pixel [argb] through this matrix. Each channel is rounded and clamped to 0…255. */
    fun apply(argb: Int): Int {
        val a = (argb ushr 24 and 0xFF).toDouble()
        val r = (argb ushr 16 and 0xFF).toDouble()
        val g = (argb ushr 8 and 0xFF).toDouble()
        val b = (argb and 0xFF).toDouble()
        fun channel(row: Int): Int {
            val o = row * 5
            val value = values[o] * r + values[o + 1] * g + values[o + 2] * b + values[o + 3] * a + values[o + 4]
            return value.roundToInt().coerceIn(0, 255)
        }
        return (channel(3) shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    companion object {
        val IDENTITY: ColorMatrix = ColorMatrix(
            listOf(
                1.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0, 0.0,
            ),
        )

        /** The invert matrix `[−1 0 0 0 255; 0 −1 0 0 255; 0 0 −1 0 255; 0 0 0 1 0]`. */
        val INVERT: ColorMatrix = ColorMatrix(
            listOf(
                -1.0, 0.0, 0.0, 0.0, 255.0,
                0.0, -1.0, 0.0, 0.0, 255.0,
                0.0, 0.0, -1.0, 0.0, 255.0,
                0.0, 0.0, 0.0, 1.0, 0.0,
            ),
        )

        /** Gives the gray matrix: the same [weights] in the R, G, and B rows. */
        fun gray(weights: GrayWeights): ColorMatrix = ColorMatrix(
            listOf(
                weights.r, weights.g, weights.b, 0.0, 0.0,
                weights.r, weights.g, weights.b, 0.0, 0.0,
                weights.r, weights.g, weights.b, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0, 0.0,
            ),
        )

        /**
         * Gives the process matrix of the reference image. The invert matrix comes first when
         * [invert] is true. The gray matrix comes
         * after it when [grayWeights] is not null.
         */
        fun process(invert: Boolean, grayWeights: GrayWeights?): ColorMatrix {
            val matrix = if (invert) INVERT else IDENTITY
            return if (grayWeights == null) matrix else matrix.then(gray(grayWeights))
        }
    }
}
