package org.superdro.core.readout.encoder

import kotlin.math.max
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType

/**
 * The backlash stage of an input. The stage gets the corrected
 * count `x` and the raw delta `d = previousRaw − raw`.
 */
sealed interface BacklashStage {

    /** Gives the output count of the corrected count [x] with the raw delta [d]. */
    fun apply(x: Long, d: Long): Long

    /** No backlash compensation. */
    data object None : BacklashStage {
        override fun apply(x: Long, d: Long): Long = x
    }

    companion object {
        /**
         * Gives the stage of [profile]:
         *
         * | Backlash mode | Encoder type | Model |
         * |---|---|---|
         * | `None` | any | none |
         * | `Constant` or `Map` | scale, rotary encoder, rack and pinion | LAG with the constant |
         * | `Constant` | leadscrew encoder | DEAD_ZONE with the constant |
         * | `Map` | leadscrew encoder | DEAD_ZONE with the backlash map |
         *
         * A constant that is not more than 0, or an empty backlash map, gives no backlash. A
         * `Map` mode on a type that is not a leadscrew encoder is not valid. The
         * validation reports it; the run time uses the constant.
         */
        fun of(profile: EncoderProfile): BacklashStage {
            val leadscrew = profile.type == EncoderType.LeadscrewEncoder
            return when (profile.backlashMode) {
                BacklashMode.None -> None
                BacklashMode.Constant ->
                    when {
                        profile.backlashCounts <= 0L -> None
                        leadscrew -> DeadZoneBacklash.constant(profile.backlashCounts)
                        else -> LagBacklash(profile.backlashCounts)
                    }
                BacklashMode.Map ->
                    when {
                        leadscrew -> {
                            val table = MapTable.of(profile.backlashMap)
                            if (table.isEmpty) None else DeadZoneBacklash.map(table)
                        }
                        profile.backlashCounts > 0L -> LagBacklash(profile.backlashCounts)
                        else -> None
                    }
            }
        }
    }
}

/**
 * The LAG backlash model of a scale and a direct encoder.
 *
 * ```text
 * first sample: up = (d < 0); k = x
 * else if up:   if x >= k: k = x  else if k − x > 4: up = false; k = x
 * else:         if x <= k: k = x  else if x − k > 4: up = true;  k = x
 * output = up ? x + B : x
 * ```
 *
 * The output is `x + B` while the axis moves in the positive count direction. The direction
 * changes only after more than [REVERSAL_COUNTS] counts of reverse motion past the last extreme.
 *
 * @property backlash The constant `B` in counts.
 */
class LagBacklash(val backlash: Long) : BacklashStage {

    private var started = false
    private var extreme = 0L

    /** True while the direction state is "up". */
    var up: Boolean = false
        private set

    override fun apply(x: Long, d: Long): Long {
        if (!started) {
            started = true
            up = d < 0L
            extreme = x
        } else if (up) {
            if (x >= extreme) {
                extreme = x
            } else if (extreme - x > REVERSAL_COUNTS) {
                up = false
                extreme = x
            }
        } else {
            if (x <= extreme) {
                extreme = x
            } else if (x - extreme > REVERSAL_COUNTS) {
                up = true
                extreme = x
            }
        }
        return if (up) x + backlash else x
    }

    companion object {
        /** The reverse motion that changes the direction state: more than 4 counts. */
        const val REVERSAL_COUNTS: Long = 4L
    }
}

/**
 * The DEAD_ZONE backlash model of a leadscrew encoder. `B(p)` comes from the constant or
 * the backlash map at the output position. A negative value gives 0.
 *
 * ```text
 * first sample: out = x; last = x; gap = 0
 * step = x − last; last = x
 * if step > 0: gap += step; excess = gap − max(0, B(out)); if excess > 0: out += excess; gap = max(0, B(out))
 * if step < 0: gap += step; if gap < 0: out += gap; gap = 0
 * output = out
 * ```
 */
class DeadZoneBacklash private constructor(private val backlashAt: (Long) -> Long) : BacklashStage {

    private var started = false
    private var out = 0L
    private var last = 0L
    private var gap = 0L

    override fun apply(x: Long, d: Long): Long {
        if (!started) {
            started = true
            out = x
            last = x
            gap = 0L
            return x
        }
        val step = x - last
        last = x
        if (step > 0L) {
            gap += step
            val excess = gap - max(0L, backlashAt(out))
            if (excess > 0L) {
                out += excess
                gap = max(0L, backlashAt(out))
            }
        } else if (step < 0L) {
            gap += step
            if (gap < 0L) {
                out += gap
                gap = 0L
            }
        }
        return out
    }

    companion object {
        /** The model with the constant [backlash]. */
        fun constant(backlash: Long): DeadZoneBacklash = DeadZoneBacklash { backlash }

        /** The model with the backlash map [table], read at the output position. */
        fun map(table: MapTable): DeadZoneBacklash = DeadZoneBacklash { position -> table.lookup(position) }
    }
}
