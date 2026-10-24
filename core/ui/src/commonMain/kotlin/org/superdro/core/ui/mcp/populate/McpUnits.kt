package org.superdro.core.ui.mcp.populate

import org.superdro.core.model.MachineProfile
import org.superdro.core.model.Units

/**
 * The units at the edge of the MCP tools. The arguments and the results of a tool are in `mm`,
 * `inch`, or `um`, and the angles are in degrees. The stored models keep µm and arc seconds.
 */
object McpUnits {
    const val MM: String = "mm"
    const val INCH: String = "inch"
    const val UM: String = "um"

    /** The valid values of the `units` argument. */
    val ALL: List<String> = listOf(MM, INCH, UM)

    /** Gives the µm of [value] in [units]. An unknown unit throws [IllegalArgumentException]. */
    fun lengthToUm(value: Double, units: String): Double = value * factor(units)

    /** Gives [um] in [units]. An unknown unit throws [IllegalArgumentException]. */
    fun lengthFromUm(um: Double, units: String): Double = um / factor(units)

    /** Gives the arc seconds of [degrees]. */
    fun angleToArcSec(degrees: Double): Double = degrees * Units.ARCSEC_PER_DEGREE

    /** Gives the degrees of [arcSec]. */
    fun angleFromArcSec(arcSec: Double): Double = arcSec / Units.ARCSEC_PER_DEGREE

    /** `mm` when the profile has the metric default, else `inch`. */
    fun defaultUnits(profile: MachineProfile): String = if (profile.metricDefault) MM else INCH

    /** True for `mm` and `um`, false for `inch`. */
    fun isMetric(units: String): Boolean = units != INCH

    /** Gives true when [units] is one of [ALL]. */
    fun isValid(units: String): Boolean = units in ALL

    private fun factor(units: String): Double = when (units) {
        MM -> Units.UM_PER_MM
        INCH -> Units.UM_PER_INCH
        UM -> 1.0
        else -> throw IllegalArgumentException("The units must be mm, inch, or um, not \"$units\".")
    }
}
