package org.superdro.core.functions.probe

import org.superdro.core.functions.FunctionMessage
import org.superdro.core.functions.MessageTemplate
import org.superdro.core.functions.formatFixed
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.Units

/**
 * The texts of the edge finder. The strings are in `core:resources`,
 * file `strings_edge_finder.xml`.
 */
object EdgeFinderTexts {
    val SPREAD_WARNING = MessageTemplate(
        "edge_finder_spread_warning",
        "Warning: maximum spread between the samples is %1\$s %2\$s",
    )
    val PROBE_TRIGGERED = MessageTemplate(
        "edge_finder_probe_triggered",
        "Probe is in triggered state. Please move the probe away from the workpiece.",
    )
    val LONG_PRESS_TO_CLEAR = MessageTemplate(
        "edge_finder_long_press_to_clear",
        "Long press this button to clear current value",
    )
    val NO_AXIS_MOVING = MessageTemplate(
        "edge_finder_no_axis_moving",
        "No axis was moving when the probe triggered.",
    )

    val ONE_AXIS_AT_A_TIME = MessageTemplate("edge_finder_one_axis_at_a_time", "Please move one axis at a time")
    val Z_NOT_SUPPORTED = MessageTemplate("edge_finder_z_not_supported", "Z axis probing is not supported yet")
    val CHANGE_IN_DIRECTION = MessageTemplate(
        "edge_finder_change_in_direction",
        "Change in direction detected during probe sampling",
    )
    val POSITION_UNKNOWN = MessageTemplate(
        "edge_finder_position_unknown",
        "The axis position at the trigger is unknown. Try again.",
    )

    /** The unit symbol of a length in mm. A unit symbol is a technical name. */
    const val UNIT_MM: String = "mm"

    /** The unit symbol of a length in inch. */
    const val UNIT_INCH: String = "in."

    /** All templates of this object, for the check against the string file. */
    val all: List<MessageTemplate> by lazy {
        listOf(
            SPREAD_WARNING, PROBE_TRIGGERED, LONG_PRESS_TO_CLEAR, NO_AXIS_MOVING, ONE_AXIS_AT_A_TIME,
            Z_NOT_SUPPORTED, CHANGE_IN_DIRECTION, POSITION_UNKNOWN,
        )
    }

    /**
     * The spread warning: "Warning: maximum spread between the samples is %s %s", with the value
     * in the display unit and the display format, and the unit `mm` or `in.`.
     */
    fun spreadWarning(spreadUm: Double, metric: Boolean, format: ReadoutFormat): FunctionMessage {
        val value = if (metric) {
            formatFixed(spreadUm / Units.UM_PER_MM, format.mmDecimals)
        } else {
            formatFixed(spreadUm / Units.UM_PER_INCH, format.inchDecimals)
        }
        return SPREAD_WARNING.format(value, if (metric) UNIT_MM else UNIT_INCH)
    }
}
