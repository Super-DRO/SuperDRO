package org.superdro.core.functions.errormap

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sums `G(k) = Σ u_j · exp(i·2π·k·t_j)`, for `k` from 0 to `count − 1`, of complex weights
 * `u_j` at non-uniform points `t_j` (in turns). The periodic error scan of [ErrorMapBuilder] uses
 * them.
 *
 * A direct calculation needs `count × n` values of `sin` and `cos`. This object uses the
 * non-uniform fast Fourier transform with Gaussian kernels (L. Greengard and J.-Y. Lee, "Accelerating
 * the nonuniform fast Fourier transform", SIAM Review 46, 2004):
 *
 * 1. Each weight goes to the [SPREAD] × 2 nearest points of a uniform grid of `r` points
 *    (2 times the number of modes), multiplied by a Gaussian kernel.
 * 2. A fast Fourier transform of the grid.
 * 3. A division by the Fourier transform of the kernel.
 *
 * The run time is `n × 2 × SPREAD + r × log2(r)`. The error of each sum is less than about
 * 1e-11 × `Σ |u_j|`.
 */
internal object NonUniformFourier {

    /** The half width of the kernel, in grid points. 12 gives an error of about 1e-12. */
    const val SPREAD: Int = 12

    /**
     * Calculates the sums of each weight set for the same points.
     *
     * @param turns The points `t_j`, in turns. Each value is in `[0, 1)`.
     * @param weightsRe The real parts of the weights, one array for each weight set.
     * @param weightsIm The imaginary parts of the weights, one array for each weight set.
     * @param count The number of sums `k = 0 … count − 1`, 1 or more.
     * @return For each weight set: the real parts (index `2 × set`) and the imaginary parts
     *   (index `2 × set + 1`) of `G(0) … G(count − 1)`.
     */
    fun sums(turns: DoubleArray, weightsRe: List<DoubleArray>, weightsIm: List<DoubleArray>, count: Int): List<DoubleArray> {
        require(count >= 1)
        val sets = weightsRe.size
        var gridSize = 64
        while (gridSize < 2 * count) gridSize *= 2
        val modes = gridSize / 2
        val half = modes / 2
        // The kernel width of Greengard and Lee for an oversampling ratio of 2.
        val tau = PI * SPREAD / (modes.toDouble() * modes * 2.0 * 1.5)
        val h = 2.0 * PI / gridSize
        val e3 = DoubleArray(SPREAD + 1) { exp(-(it * h) * (it * h) / (4.0 * tau)) }
        val gridRe = Array(sets) { DoubleArray(gridSize) }
        val gridIm = Array(sets) { DoubleArray(gridSize) }
        val kernel = DoubleArray(2 * SPREAD)
        for (j in turns.indices) {
            val t = turns[j]
            // The modes go from −half to half − 1: G(k) is the sum of mode k − half of
            // u_j · exp(i·2π·half·t_j).
            val shift = 2.0 * PI * frac(half * t)
            val shiftC = cos(shift)
            val shiftS = sin(shift)
            val scaled = t * gridSize
            val m0 = floor(scaled).toInt().coerceIn(0, gridSize - 1)
            val d = (scaled - m0) * h
            val e1 = exp(-d * d / (4.0 * tau))
            val e2 = exp(d * h / (2.0 * tau))
            // kernel[SPREAD − 1 + l] is the kernel at grid point m0 + l, l = −(SPREAD − 1) … SPREAD.
            var up = e1
            for (l in 0..SPREAD) {
                kernel[SPREAD - 1 + l] = up * e3[l]
                up *= e2
            }
            val e2Inverse = 1.0 / e2
            var down = e1 * e2Inverse
            for (l in 1 until SPREAD) {
                kernel[SPREAD - 1 - l] = down * e3[l]
                down *= e2Inverse
            }
            val first = m0 - (SPREAD - 1)
            for (set in 0 until sets) {
                val ur = weightsRe[set][j]
                val ui = weightsIm[set][j]
                val vr = ur * shiftC - ui * shiftS
                val vi = ur * shiftS + ui * shiftC
                val re = gridRe[set]
                val im = gridIm[set]
                for (p in 0 until 2 * SPREAD) {
                    val m = (first + p + gridSize) and (gridSize - 1)
                    re[m] += kernel[p] * vr
                    im[m] += kernel[p] * vi
                }
            }
        }
        val result = ArrayList<DoubleArray>(2 * sets)
        val norm = sqrt(PI / tau) / gridSize
        for (set in 0 until sets) {
            fft(gridRe[set], gridIm[set])
            val outRe = DoubleArray(count)
            val outIm = DoubleArray(count)
            for (k in 0 until count) {
                val mode = k - half
                val q = (mode + gridSize) and (gridSize - 1)
                val scale = norm * exp(mode.toDouble() * mode * tau)
                outRe[k] = gridRe[set][q] * scale
                outIm[k] = gridIm[set][q] * scale
            }
            result.add(outRe)
            result.add(outIm)
        }
        return result
    }

    /** Gives `v − floor(v)`, in `[0, 1)`. */
    fun frac(v: Double): Double {
        val f = v - floor(v)
        return if (f >= 1.0) 0.0 else f
    }

    /**
     * The in-place fast Fourier transform `X(q) = Σ x(m) · exp(+i·2π·q·m / size)`. The size is a
     * power of 2.
     */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val size = re.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
        val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }
        var length = 2
        while (length <= size) {
            val halfLength = length / 2
            val stride = size / length
            var start = 0
            while (start < size) {
                for (k in 0 until halfLength) {
                    val wr = cosTable[k * stride]
                    val wi = sinTable[k * stride]
                    val a = start + k
                    val b = a + halfLength
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += length
            }
            length *= 2
        }
    }
}
