package org.superdro.core.functions.graphics

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The result of the gray weight search.
 *
 * @property searched True when the weights come from the search. False when the search failed
 *   and the weights are the BT.601 weights.
 * @property thumbnailWidth The width of the thumbnail of the search, or 0 when there is no thumbnail.
 * @property thumbnailHeight The height of the thumbnail of the search.
 */
data class GrayWeightResult(
    val weights: GrayWeights,
    val searched: Boolean,
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
)

/**
 * The gray conversion of the reference image that keeps the color contrast. The method is
 * the "contrast
 * preserving decolorization" method with a linear model `g = wr·R + wg·G + wb·B`:
 *
 * 1. Halve the image while both sides are even and the larger side is more than 128 px.
 * 2. Scale the result to a thumbnail with a larger side of 64 px.
 * 3. Convert the thumbnail pixels to CIELab (D65 white point 0.95047, 1.0, 1.08883).
 * 4. Make pixel pairs: each pixel with its right and lower neighbor, and each pixel with a
 *    pixel of a fixed pseudo-random permutation. The contrast of a pair is its Lab distance
 *    ÷ 100. Pairs with a contrast below [MIN_CONTRAST] are not used.
 * 5. There are 66 weight sets with the steps 0.1 and the sum 1. For each set, calculate the
 *    energy `Σ ln(exp(−(Δg − δ)²/σ²) + exp(−(Δg + δ)²/σ²))` with σ = [SIGMA]. The set with
 *    the largest energy wins. On equal energy, the first set wins.
 * 6. If no pair is usable or the energy is not finite, use the BT.601 weights 0.299, 0.587, 0.114.
 *
 * The pixels are ARGB values in an [IntArray], row by row. The function is deterministic.
 */
object ContrastPreservingGray {

    /** The fallback weights (BT.601, the same as OpenCV `COLOR_RGB2GRAY`). */
    val BT601: GrayWeights = GrayWeights(0.299, 0.587, 0.114)

    /** The larger side of the thumbnail. */
    const val THUMBNAIL_SIZE_PX: Int = 64

    /** The image is halved while the larger side is more than this value. */
    const val HALVE_LIMIT_PX: Int = 128

    /** The width of the two modes of the energy. */
    const val SIGMA: Double = 0.05

    /** The smallest contrast of a usable pair. */
    const val MIN_CONTRAST: Double = 0.05

    /** The seed of the permutation of the global pairs. */
    const val PAIR_SEED: Int = 64

    /** The D65 white point. */
    private const val WHITE_X: Double = 0.95047
    private const val WHITE_Y: Double = 1.0
    private const val WHITE_Z: Double = 1.08883

    /** The 66 weight sets (wr, wg, wb) with the steps 0.1 and the sum 1. */
    val CANDIDATES: List<GrayWeights> = buildList {
        for (r in 0..10) {
            for (g in 0..(10 - r)) {
                add(GrayWeights(r / 10.0, g / 10.0, (10 - r - g) / 10.0))
            }
        }
    }

    /** Gives the gray weights of the image [argb] of [width] × [height] pixels. */
    fun weights(argb: IntArray, width: Int, height: Int): GrayWeightResult {
        if (width <= 0 || height <= 0 || argb.size < width * height) return GrayWeightResult(BT601, false, 0, 0)
        val (thumb, tw, th) = thumbnail(argb, width, height)
        val count = tw * th
        val rgb = Array(count) { index -> channels(thumb[index]) }
        val lab = Array(count) { index -> toLab(rgb[index]) }
        val pairs = ArrayList<IntArray>()
        for (y in 0 until th) {
            for (x in 0 until tw) {
                val index = y * tw + x
                if (x + 1 < tw) pairs += intArrayOf(index, index + 1)
                if (y + 1 < th) pairs += intArrayOf(index, index + tw)
            }
        }
        val permutation = IntArray(count) { index -> index }
        val random = Random(PAIR_SEED)
        for (index in count - 1 downTo 1) {
            val other = random.nextInt(index + 1)
            val swap = permutation[index]
            permutation[index] = permutation[other]
            permutation[other] = swap
        }
        for (index in 0 until count) {
            if (permutation[index] != index) pairs += intArrayOf(index, permutation[index])
        }
        val deltas = ArrayList<DoubleArray>()
        for (pair in pairs) {
            val first = pair[0]
            val second = pair[1]
            val l1 = lab[first]
            val l2 = lab[second]
            val contrast = sqrt((l1[0] - l2[0]).pow(2) + (l1[1] - l2[1]).pow(2) + (l1[2] - l2[2]).pow(2)) / 100.0
            if (contrast < MIN_CONTRAST) continue
            val c1 = rgb[first]
            val c2 = rgb[second]
            deltas += doubleArrayOf(c1[0] - c2[0], c1[1] - c2[1], c1[2] - c2[2], contrast)
        }
        if (deltas.isEmpty()) return GrayWeightResult(BT601, false, tw, th)
        var best: GrayWeights? = null
        var bestEnergy = Double.NEGATIVE_INFINITY
        val sigma2 = SIGMA * SIGMA
        for (candidate in CANDIDATES) {
            var energy = 0.0
            for (delta in deltas) {
                val dg = candidate.r * delta[0] + candidate.g * delta[1] + candidate.b * delta[2]
                val a = -(dg - delta[3]).pow(2) / sigma2
                val b = -(dg + delta[3]).pow(2) / sigma2
                energy += max(a, b) + ln(1.0 + exp(-abs(a - b)))
            }
            if (energy.isFinite() && energy > bestEnergy) {
                bestEnergy = energy
                best = candidate
            }
        }
        val weights = best ?: return GrayWeightResult(BT601, false, tw, th)
        return GrayWeightResult(weights, true, tw, th)
    }

    /**
     * Gives the thumbnail of the search: the halved image, then a nearest-pixel scale to a larger
     * side of [THUMBNAIL_SIZE_PX]. The result is (pixels, width, height).
     */
    fun thumbnail(argb: IntArray, width: Int, height: Int): Triple<IntArray, Int, Int> {
        // The function only reads the input, so it does not copy a large image.
        var pixels = argb
        var w = width
        var h = height
        while (w % 2 == 0 && h % 2 == 0 && max(w, h) > HALVE_LIMIT_PX) {
            val hw = w / 2
            val hh = h / 2
            val half = IntArray(hw * hh)
            for (y in 0 until hh) {
                for (x in 0 until hw) {
                    half[y * hw + x] = average(
                        pixels[(2 * y) * w + 2 * x],
                        pixels[(2 * y) * w + 2 * x + 1],
                        pixels[(2 * y + 1) * w + 2 * x],
                        pixels[(2 * y + 1) * w + 2 * x + 1],
                    )
                }
            }
            pixels = half
            w = hw
            h = hh
        }
        val factor = THUMBNAIL_SIZE_PX.toDouble() / max(w, h)
        val tw = max(1, (w * factor).roundToInt())
        val th = max(1, (h * factor).roundToInt())
        val thumb = IntArray(tw * th)
        for (y in 0 until th) {
            val sy = ((y + 0.5) * h / th).toInt().coerceIn(0, h - 1)
            for (x in 0 until tw) {
                val sx = ((x + 0.5) * w / tw).toInt().coerceIn(0, w - 1)
                thumb[y * tw + x] = pixels[sy * w + sx]
            }
        }
        return Triple(thumb, tw, th)
    }

    /** Gives the CIELab values (L, a, b) of the sRGB channels [rgb] (0 to 1). */
    fun toLab(rgb: DoubleArray): DoubleArray {
        val r = linear(rgb[0])
        val g = linear(rgb[1])
        val b = linear(rgb[2])
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / WHITE_X
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / WHITE_Y
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / WHITE_Z
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun linear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun f(t: Double): Double {
        val delta = 6.0 / 29.0
        return if (t > delta * delta * delta) cbrt(t) else t / (3.0 * delta * delta) + 4.0 / 29.0
    }

    private fun channels(argb: Int): DoubleArray = doubleArrayOf(
        (argb ushr 16 and 0xFF) / 255.0,
        (argb ushr 8 and 0xFF) / 255.0,
        (argb and 0xFF) / 255.0,
    )

    private fun average(p1: Int, p2: Int, p3: Int, p4: Int): Int {
        fun channel(shift: Int): Int =
            ((p1 ushr shift and 0xFF) + (p2 ushr shift and 0xFF) + (p3 ushr shift and 0xFF) + (p4 ushr shift and 0xFF) + 2) / 4
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
