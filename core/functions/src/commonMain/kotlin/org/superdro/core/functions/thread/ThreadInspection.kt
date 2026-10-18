package org.superdro.core.functions.thread

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The Go and No-Go gauge limits of an internal thread, in µm.
 *
 * @property minorGoUm The minor diameter Go pin-gauge limit (`D1min`).
 * @property minorNoGoUm The minor diameter No-Go pin-gauge limit (`D1max`).
 * @property pitchGoUm The pitch diameter Go plug-gauge limit (`D2min`).
 * @property pitchNoGoUm The pitch diameter No-Go plug-gauge limit (`D2max`).
 */
data class InternalGaugeLimits(
    val minorGoUm: Double,
    val minorNoGoUm: Double,
    val pitchGoUm: Double,
    val pitchNoGoUm: Double,
)

/**
 * Three-wire external thread inspection and internal Go/No-Go gauge limit calculations.
 * All lengths are µm.
 */
object ThreadInspection {

    private const val DEG_TO_RAD: Double = PI / 180.0

    /**
     * Gives the best wire diameter `W_best = P / (2 × cos(α / 2))` that touches the thread flanks
     * at the pitch line.
     */
    fun bestWireDiameterUm(pitchUm: Double, includedAngleDeg: Double): Double {
        if (!pitchUm.isFinite() || pitchUm <= 0.0 || !includedAngleDeg.isFinite() || includedAngleDeg <= 0.0) {
            return 0.0
        }
        val halfAngleRad = (includedAngleDeg / 2.0) * DEG_TO_RAD
        return pitchUm / (2.0 * cos(halfAngleRad))
    }

    /**
     * Gives the valid wire diameter range `[W_min, W_max]` for a three-wire measurement of a thread
     * with pitch [pitchUm] and flank angle [includedAngleDeg].
     */
    fun wireRangeUm(pitchUm: Double, includedAngleDeg: Double): ClosedFloatingPointRange<Double> {
        val best = bestWireDiameterUm(pitchUm, includedAngleDeg)
        if (best <= 0.0) return 0.0..0.0
        val minWire = min(0.50 * pitchUm, 0.85 * best)
        val maxWire = max(0.90 * pitchUm, 1.75 * best)
        return minWire..maxWire
    }

    /**
     * Calculates the three-wire measurement values using the Buckingham lead-angle correction and
     * the taper correction factor `sec²(β)`.
     */
    fun threeWire(
        pitchUm: Double,
        includedAngleDeg: Double,
        starts: Int = 1,
        targetPitchDiameterUm: Double,
        limits: ClassLimits?,
        customWireDiameterUm: Double? = null,
        measuredOverWiresUm: Double? = null,
        taperRatio: Double = 0.0,
    ): ThreeWireValues {
        val validStarts = starts.coerceAtLeast(1)
        val bestWire = bestWireDiameterUm(pitchUm, includedAngleDeg)
        val wire = customWireDiameterUm?.takeIf { it.isFinite() && it > 0.0 } ?: bestWire
        val range = wireRangeUm(pitchUm, includedAngleDeg)
        val wireValid = wire in range

        val theta = (includedAngleDeg / 2.0) * DEG_TO_RAD
        val sinTheta = sin(theta)
        val cosTheta = cos(theta)
        val tanTheta = tan(theta)
        val cotTheta = cosTheta / sinTheta

        val leadUm = validStarts * pitchUm
        val lambda = if (targetPitchDiameterUm.isFinite() && targetPitchDiameterUm > 0.0) {
            atan(leadUm / (PI * targetPitchDiameterUm))
        } else {
            0.0
        }
        val tanLambda = tan(lambda)
        val secBetaSq = 1.0 + (taperRatio / 2.0) * (taperRatio / 2.0)

        val cWire =
            wire * (1.0 + 1.0 / sinTheta + 0.5 * tanLambda * tanLambda * cosTheta * cotTheta) * secBetaSq -
                pitchUm / (2.0 * tanTheta)

        val measurementUm = targetPitchDiameterUm + cWire
        val measurementMinUm = limits?.let { it.pitchMinUm + cWire }
        val measurementMaxUm = limits?.let { it.pitchMaxUm + cWire }

        val validMeasured = measuredOverWiresUm?.takeIf { it.isFinite() && it > 0.0 }
        val actualPitchDiameterUm = validMeasured?.let { it - cWire }
        val remainingRadialDepthUm = validMeasured?.let { max(0.0, (it - measurementUm) / 2.0) }

        return ThreeWireValues(
            wireDiameterUm = wire,
            measurementUm = measurementUm,
            measurementMinUm = measurementMinUm,
            measurementMaxUm = measurementMaxUm,
            actualPitchDiameterUm = actualPitchDiameterUm,
            remainingRadialDepthUm = remainingRadialDepthUm,
            wireValid = wireValid,
        )
    }

    /**
     * Gives the Go and No-Go minor pin-gauge and pitch plug-gauge limits for an internal thread,
     * or null when no class limits exist.
     */
    fun internalGaugeLimits(limits: ClassLimits?): InternalGaugeLimits? =
        limits?.let {
            InternalGaugeLimits(
                minorGoUm = it.crestMinUm,
                minorNoGoUm = it.crestMaxUm,
                pitchGoUm = it.pitchMinUm,
                pitchNoGoUm = it.pitchMaxUm,
            )
        }
}
