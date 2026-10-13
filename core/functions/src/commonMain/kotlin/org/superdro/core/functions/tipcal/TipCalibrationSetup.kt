package org.superdro.core.functions.tipcal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType

/**
 * A clock position of the touches in the ring gauge. The
 * order of the entries is the order of the procedure: 9, 3, 12, and then 6 o'clock.
 *
 * The UI title of a position is "Touch Off at %1$d o'clock" with [hour].
 *
 * @property hour The hour on the clock face: 9, 3, 12, or 6.
 */
@Serializable
enum class ClockPosition(val hour: Int) {
    /** 9 o'clock: the touch at −X. */
    @SerialName("NINE")
    Nine(9),

    /** 3 o'clock: the touch at +X. */
    @SerialName("THREE")
    Three(3),

    /** 12 o'clock: the touch at +Y. */
    @SerialName("TWELVE")
    Twelve(12),

    /** 6 o'clock: the touch at −Y. */
    @SerialName("SIX")
    Six(6),
    ;

    companion object {
        /** The procedure order: [Nine], [Three], [Twelve], [Six]. */
        val procedureOrder: List<ClockPosition> = entries
    }
}

/**
 * The result of the readiness check before each step of the tip calibration.
 * The checks occur in the order of the entries.
 */
enum class TipCalibrationReadiness {
    /**
     * The profile is not in the library. Title "Probe Profile Missing". Text "This profile is no
     * longer in the library — deleted, or belonging to another machine profile."
     */
    ProfileGone,

    /**
     * The type uses no diameter, or the profile has no trigger source. Title "Nothing to
     * Calibrate". Text "Choose a trigger source first. Calibration needs one to time the touches."
     */
    NotCalibratable,

    /**
     * There is no connection. Title "Not Connected". Text "Connect your adapter first —
     * calibration reads the probe and the axes as you touch off."
     */
    NotConnected,

    /**
     * X or Y has no position history. Title "X and Y Are Needed". Text "Calibration needs both X
     * and Y. One of them has not reported a position yet."
     */
    NoXyAxes,

    /** All checks pass. */
    Ready,
}

/** The rules of the tip calibration before the first touch. */
object TipCalibrationSetup {

    /** 1 mm = 1000 µm. */
    const val UM_PER_MM: Double = 1000.0

    /** 1 in = 25400 µm. */
    const val UM_PER_INCH: Double = 25400.0

    /**
     * Gives the readiness of the calibration. The checks occur in this order: the profile, the
     * type and the trigger source, the connection, and the X and Y history.
     *
     * @param profile The profile from the library, or null when the library does not have it.
     * @param connected True when the adapter of the probe is connected.
     * @param xHasPosition True when X has a position history.
     * @param yHasPosition True when Y has a position history.
     */
    fun readiness(
        profile: ProbeProfile?,
        connected: Boolean,
        xHasPosition: Boolean,
        yHasPosition: Boolean,
    ): TipCalibrationReadiness = when {
        profile == null -> TipCalibrationReadiness.ProfileGone
        !usesDiameter(profile.type) || profile.port == null -> TipCalibrationReadiness.NotCalibratable
        !connected -> TipCalibrationReadiness.NotConnected
        !xHasPosition || !yHasPosition -> TipCalibrationReadiness.NoXyAxes
        else -> TipCalibrationReadiness.Ready
    }

    /** Gives true for the two types that use a tip diameter: the touch probe and the 2D edge finder. */
    fun usesDiameter(type: ProbeType): Boolean = type == ProbeType.TouchProbe || type == ProbeType.EdgeFinder2D

    /**
     * Changes the typed ring gauge diameter to µm.
     *
     * @param value The diameter that is marked on the ring gauge.
     * @param metric True for mm (× 1000), false for inch (× 25400).
     */
    fun gaugeDiameterUm(value: Double, metric: Boolean): Double = value * if (metric) UM_PER_MM else UM_PER_INCH

    /**
     * Gives true when the ring gauge is usable. A gauge that is 0 or less, that is not finite, or
     * that is not larger than the nominal tip diameter is not usable. Then the UI shows the
     * dialog of [TipCalibrationError.GaugeUnusable]: title "Ring Gauge Too Small", text "The
     * stylus will not fit in a bore that size. Check the diameter — and that it is a ring gauge,
     * not a pin."
     *
     * @param gaugeUm The ring gauge diameter in µm.
     * @param nominalTipUm The nominal tip diameter of the profile in µm.
     */
    fun isGaugeUsable(gaugeUm: Double, nominalTipUm: Double): Boolean =
        gaugeUm.isFinite() && gaugeUm > 0.0 && gaugeUm > nominalTipUm
}

/**
 * The marker of the "Back to the Middle" step. The step comes
 * before 12 o'clock only. The marker uses the mean X of 9 o'clock and of 3 o'clock.
 *
 * @property centerXUm `(x9 + x3) / 2`.
 * @property spanUm `|x3 − x9|`.
 */
data class RecenterMarker(val centerXUm: Double, val spanUm: Double) {

    /** The slider range is ±[rangeUm]: `span / 2`. */
    val rangeUm: Double get() = spanUm / 2.0

    /** The size of the hash mark: `span / 8`. */
    val hashMarkUm: Double get() = spanUm / 8.0

    /** Gives the marker position for the live X position, limited to ±[rangeUm]. */
    fun markerOffsetUm(liveXUm: Double): Double = (liveXUm - centerXUm).coerceIn(-rangeUm, rangeUm)

    companion object {
        /** Makes the marker from the mean X of 9 o'clock ([x9Um]) and of 3 o'clock ([x3Um]). */
        fun of(x9Um: Double, x3Um: Double): RecenterMarker =
            RecenterMarker(centerXUm = (x9Um + x3Um) / 2.0, spanUm = kotlin.math.abs(x3Um - x9Um))
    }
}
