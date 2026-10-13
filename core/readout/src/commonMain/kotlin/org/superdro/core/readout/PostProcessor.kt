package org.superdro.core.readout

import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineType
import org.superdro.core.model.Units

/**
 * The post-processor of a readout. [toShown] is `h(v)`: the internal
 * value to the shown value. [toInternal] is `c(v)`: the shown value to the internal value.
 */
sealed interface PostProcessor {

    /** `h(v)`: gives the shown value of the internal value [value]. */
    fun toShown(value: Double): Double

    /** `c(v)`: gives the internal value of the shown value [shown]. */
    fun toInternal(shown: Double): Double

    /** X on a lathe with the diameter mode on: `2v` and `v / 2`. */
    data object Diameter : PostProcessor {
        override fun toShown(value: Double): Double = 2.0 * value
        override fun toInternal(shown: Double): Double = shown / 2.0
    }

    /** The angular axis: `v % 1296000` in both directions. The sign of `v` stays. */
    data object AngularWrap : PostProcessor {
        override fun toShown(value: Double): Double = value % Units.ARCSEC_PER_REVOLUTION
        override fun toInternal(shown: Double): Double = shown % Units.ARCSEC_PER_REVOLUTION
    }

    /** All other readouts: `v` and `v`. */
    data object Identity : PostProcessor {
        override fun toShown(value: Double): Double = value
        override fun toInternal(shown: Double): Double = shown
    }

    companion object {
        /** Gives the post-processor of [axis] with [state] on a machine of [machineType]. */
        fun of(axis: AxisId, state: AxisState, machineType: MachineType): PostProcessor = when {
            axis == AxisId.X && machineType.isLathe && state.diameterMode -> Diameter
            axis == AxisId.Angular -> AngularWrap
            else -> Identity
        }
    }
}
