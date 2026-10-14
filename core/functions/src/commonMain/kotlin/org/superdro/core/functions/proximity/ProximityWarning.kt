package org.superdro.core.functions.proximity

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.superdro.core.model.MachineMode

/** The tone of the proximity warning. */
sealed interface ProximityTone {
    /** Level 0: no tone. */
    data object Off : ProximityTone

    /** Level 1: a continuous 800 Hz tone. */
    data object Continuous : ProximityTone

    /** A level below 1: a 0.1 s beep of 800 Hz that starts again after [periodMs]. */
    data class Beep(val periodMs: Double) : ProximityTone
}

/**
 * The state of the proximity warning of one axis. The values are µm. The times are ms of the
 * session clock.
 *
 * @property targetUm The target. The linear axis details use the target 0 on the shown value.
 * @property level The level, 0 to 1.
 * @property passed True after the value went to the target or across it.
 * @property previousUm The last value, or null before the first value.
 * @property lastUpdateMs The time of the last value, or null before the first value.
 */
data class ProximityState(
    val targetUm: Double = 0.0,
    val level: Double = 0.0,
    val passed: Boolean = false,
    val previousUm: Double? = null,
    val lastUpdateMs: Long? = null,
) {

    /**
     * Gives the state after the new value [valueUm] at [timeMs]:
     *
     * ```text
     * d = |target − value|; dOld = |target − previous value|
     * if value == target, or the value went across the target: level = 0; passed = true
     * else if the axis moves away from the target: level = 0
     * else if passed and d <= Z0: level = 0
     * else if d > Z1: level = 0
     * else if d <= Z0: level = 1.0                                   (continuous tone)
     * else if the axis moves to the target: level = 0.95 − floor((d − Z0) / Z1 × 20) / 20; passed = false
     * else: level does not change
     * ```
     *
     * Before the first value, the previous value is the new value (no motion).
     */
    fun update(valueUm: Double, timeMs: Long): ProximityState {
        val previous = previousUm ?: valueUm
        val distance = abs(targetUm - valueUm)
        val oldDistance = abs(targetUm - previous)
        val across = (previous - targetUm) * (valueUm - targetUm) < 0.0
        var nextLevel = level
        var nextPassed = passed
        when {
            valueUm == targetUm || across -> {
                nextLevel = 0.0
                nextPassed = true
            }
            distance > oldDistance -> nextLevel = 0.0
            passed && distance <= ProximityWarning.CONTACT_ZONE_UM -> nextLevel = 0.0
            distance > ProximityWarning.NEAR_ZONE_UM -> nextLevel = 0.0
            distance <= ProximityWarning.CONTACT_ZONE_UM -> nextLevel = 1.0
            distance < oldDistance -> {
                nextLevel = ProximityWarning.approachLevel(distance)
                nextPassed = false
            }
        }
        return copy(level = nextLevel, passed = nextPassed, previousUm = valueUm, lastUpdateMs = timeMs)
    }

    /** The level at [nowMs]: 0 when no value came for 5000 ms. */
    fun levelAt(nowMs: Long): Double {
        val last = lastUpdateMs ?: return 0.0
        return if (nowMs - last >= ProximityWarning.SILENCE_MS) 0.0 else level
    }

    /** The tone at [nowMs]. The measurement mode stops the warning. */
    fun tone(nowMs: Long, machineMode: MachineMode = MachineMode.Cutting): ProximityTone =
        if (machineMode == MachineMode.Measurement) ProximityTone.Off else ProximityWarning.tone(levelAt(nowMs))
}

/** The constants and the tone rule of the proximity warning. */
object ProximityWarning {
    /** `Z1`: the near zone, 2540 µm (0.1 in). */
    const val NEAR_ZONE_UM: Double = 2540.0

    /** `Z0`: the contact zone, 127 µm (0.005 in). */
    const val CONTACT_ZONE_UM: Double = 127.0

    /** The number of level steps. */
    const val STEPS: Int = 20

    /** The level goes to 0 after 5000 ms with no new value. */
    const val SILENCE_MS: Long = 5000L

    /**
     * The still time of a point target: an axis that did not move for 500 ms gives no tone. The
     * tone of a point target stops when no axis of the point moved for this time.
     */
    const val STILL_MS: Long = 500L

    /** The beep length: 0.1 s. */
    const val BEEP_MS: Double = 100.0

    /** The longest beep period: 2 s. */
    const val MAX_PERIOD_MS: Double = 2000.0

    /** The tone frequency: 800 Hz. */
    const val TONE_HZ: Int = 800

    /** A new warning for [targetUm]. */
    fun start(targetUm: Double): ProximityState = ProximityState(targetUm = targetUm)

    /**
     * The level when the axis moves to the target in the near zone:
     * `0.95 − floor((d − Z0) / Z1 × 20) / 20`. The function calculates `(19 − k) / 20`, which is
     * the same value with no round-off error.
     */
    fun approachLevel(distanceUm: Double): Double {
        val step = floor((distanceUm - CONTACT_ZONE_UM) / NEAR_ZONE_UM * STEPS).toInt()
        return max(0, STEPS - 1 - step).toDouble() / STEPS
    }

    /**
     * The beep period for [level] below 1: `max(0.1 s, min(0.1 s / level, 2 s))`, in ms.
     */
    fun beepPeriodMs(level: Double): Double = max(BEEP_MS, min(BEEP_MS / level, MAX_PERIOD_MS))

    /** The tone for [level]: 0 stops the tone, 1 gives a continuous tone, else a beep. */
    fun tone(level: Double): ProximityTone = when {
        level <= 0.0 -> ProximityTone.Off
        level >= 1.0 -> ProximityTone.Continuous
        else -> ProximityTone.Beep(beepPeriodMs(level))
    }
}
