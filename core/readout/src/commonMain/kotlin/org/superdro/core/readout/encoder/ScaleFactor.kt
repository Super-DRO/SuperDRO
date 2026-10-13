package org.superdro.core.readout.encoder

import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.Units

/**
 * The scale factor `k` of an input. SuperDRO computes `k`
 * as `Double`.
 *
 * | Input type | Formula | Unit of the result |
 * |---|---|---|
 * | Linear | `k = 25400 / ppu` | µm per count (`ppu` = counts per inch) |
 * | Angular | `k = 1296000 / ppu` | arc seconds per count (`ppu` = counts per revolution) |
 * | Rotary (tachometer) | `k = 1 / ppu` if `ppu` > 0, else `k = 0` | revolutions per count |
 *
 * `k = −k` when the input is inverted (the encoder direction is reversed).
 */
object ScaleFactor {

    /** `k = 25400 / ppu` µm for each count. */
    fun linear(ppu: Double): Double = Units.UM_PER_INCH / ppu

    /** `k = 1296000 / ppu` arc seconds for each count. */
    fun angular(ppu: Double): Double = Units.ARCSEC_PER_REVOLUTION / ppu

    /** `k = 1 / ppu` revolutions for each count, or 0 when `ppu` is not more than 0. */
    fun rotary(ppu: Double): Double = if (ppu > 0.0) 1.0 / ppu else 0.0

    /** Gives `−k` when [invert] is true, else `k`. */
    fun withDirection(k: Double, invert: Boolean): Double = if (invert) -k else k

    /**
     * Gives the scale factor of the adapter input [input] with [profile], with the direction
     * sign. The input `t` always uses the rotary formula. The kind [EncoderKind.Angular] uses
     * the angular formula. All other inputs use the linear formula.
     *
     * Gives null ("no value") when the input has no profile, when the kind is
     * [EncoderKind.Disabled], or when the resolution is not more than 0.
     */
    fun of(input: Char, profile: EncoderProfile?): Double? {
        if (profile == null || !EncoderProfile.givesValue(profile)) return null
        val k = when {
            Channels.normalize(input) == Channels.T -> rotary(profile.resolution)
            profile.kind == EncoderKind.Angular -> angular(profile.resolution)
            else -> linear(profile.resolution)
        }
        return withDirection(k, profile.reversed)
    }
}
