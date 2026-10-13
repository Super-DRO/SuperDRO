package org.superdro.core.functions.patterns

import org.superdro.core.functions.FunctionMessage
import org.superdro.core.functions.MessageTemplate

/**
 * The texts of the hole patterns and of the arc cut. The
 * strings are in `core:resources`, file `strings_patterns.xml`.
 */
object PatternTexts {
    val ERROR_HEADER = MessageTemplate(
        "pattern_error_header",
        "This operation could not be completed. Please fix the errors below:",
    )
    val VALUE_REQUIRED = MessageTemplate("pattern_error_value_required", "%1\$s value is required")
    val VALUE_TOO_SMALL = MessageTemplate("pattern_error_value_too_small", "%1\$s value is too small")
    val VALUE_TOO_LARGE = MessageTemplate("pattern_error_value_too_large", "%1\$s value is too large")
    val TEXT_TOO_SHORT = MessageTemplate("pattern_error_text_too_short", "%1\$s must be at least 3 characters long.")
    val TEXT_TOO_LONG = MessageTemplate("pattern_error_text_too_long", "%1\$s can't be longer than 16 characters.")

    val TWO_ANGLES = MessageTemplate("pattern_error_two_angles", "At least two hole angles are required.")
    val REPEAT_TOO_SMALL = MessageTemplate(
        "pattern_error_repeat_too_small",
        "%1\$s or %2\$s value must be larger than 1",
    )
    val HOLE_COUNT_NOT_VALID = MessageTemplate(
        "pattern_error_hole_count_not_valid",
        "Please enter a valid number of holes.",
    )
    val HOLE_COUNT_TOO_SMALL = MessageTemplate(
        "pattern_error_hole_count_too_small",
        "Number of holes can't be less than 3.",
    )
    val START_NOT_VALID = MessageTemplate(
        "pattern_error_start_not_valid",
        "Please enter a valid number for the starting %1\$s position.",
    )
    val END_NOT_VALID = MessageTemplate(
        "pattern_error_end_not_valid",
        "Please enter a valid number for the ending %1\$s position.",
    )
    val CUTTER_TOO_LARGE = MessageTemplate(
        "arc_error_cutter_too_large",
        "Cutter Diameter value too large for this sweep angle.",
    )
    val STEPOVER_TOO_LARGE = MessageTemplate(
        "arc_error_stepover_too_large",
        "Stepover value too large for this sweep angle.",
    )

    /** Only in SuperDRO: an internal cut needs a cutter diameter that is less than 2 R. */
    val CUTTER_TOO_LARGE_FOR_RADIUS = MessageTemplate(
        "arc_error_cutter_too_large_for_radius",
        "Cutter Diameter value too large for an internal cut of this radius.",
    )

    val TITLE_HOLE_CIRCLE = MessageTemplate("pattern_title_hole_circle", "Hole Circle")
    val TITLE_HOLE_CIRCLE_LATHE = MessageTemplate("pattern_title_hole_circle_lathe", "Circular Point Array")
    val TITLE_ARBITRARY = MessageTemplate("pattern_title_arbitrary_hole_circle", "Arbitrary Hole Circle")
    val TITLE_HOLE_GRID = MessageTemplate("pattern_title_hole_grid", "Hole Grid")
    val TITLE_HOLE_GRID_LATHE = MessageTemplate("pattern_title_hole_grid_lathe", "Linear Point Array")
    val TITLE_STEP_AND_REPEAT = MessageTemplate("pattern_title_step_and_repeat", "Step and Repeat %1\$d Sub-Datums")
    val TITLE_STEP_AND_REPEAT_WORKSPACE = MessageTemplate(
        "pattern_title_step_and_repeat_workspace",
        "Step and Repeat Workspace",
    )
    val TITLE_EQUIDISTANT = MessageTemplate("pattern_title_equidistant", "Equidistant Hole Array")
    val TITLE_EQUIDISTANT_LATHE = MessageTemplate("pattern_title_equidistant_lathe", "Equidistant Point Array")
    val TITLE_ARC = MessageTemplate("arc_title", "Radius/Arc Cutting")

    /** All templates of this object, for the check against the string file. */
    val all: List<MessageTemplate> by lazy {
        listOf(
            ERROR_HEADER, VALUE_REQUIRED, VALUE_TOO_SMALL, VALUE_TOO_LARGE, TEXT_TOO_SHORT, TEXT_TOO_LONG,
            TWO_ANGLES, REPEAT_TOO_SMALL, HOLE_COUNT_NOT_VALID, HOLE_COUNT_TOO_SMALL, START_NOT_VALID,
            END_NOT_VALID, CUTTER_TOO_LARGE, STEPOVER_TOO_LARGE, CUTTER_TOO_LARGE_FOR_RADIUS,
            TITLE_HOLE_CIRCLE, TITLE_HOLE_CIRCLE_LATHE, TITLE_ARBITRARY, TITLE_HOLE_GRID, TITLE_HOLE_GRID_LATHE,
            TITLE_STEP_AND_REPEAT, TITLE_STEP_AND_REPEAT_WORKSPACE, TITLE_EQUIDISTANT, TITLE_EQUIDISTANT_LATHE,
            TITLE_ARC,
        ) + PatternField.entries.map { field -> field.label }
    }
}

/** The name of an axis in a pattern message: "X", "Y", or "Z". An axis name is a technical name. */
enum class AxisLetter(val text: String) {
    X("X"),
    Y("Y"),
    Z("Z"),
}

/**
 * The input fields of the pattern dialogs. The label is the text in the field messages, for
 * example "Circle Radius value is too small".
 */
enum class PatternField(val label: MessageTemplate) {
    Prefix(MessageTemplate("pattern_label_prefix", "Sub-Datum Prefix")),
    CircleRadius(MessageTemplate("pattern_label_circle_radius", "Circle Radius")),
    HoleCount(MessageTemplate("pattern_label_hole_count", "Hole Count")),
    StartAngle(MessageTemplate("pattern_label_start_angle", "Start Angle")),
    SweepAngle(MessageTemplate("pattern_label_sweep_angle", "Sweep Angle")),
    HoleAngle(MessageTemplate("pattern_label_hole_angle", "Hole Angle")),
    RepeatX(MessageTemplate("pattern_label_repeat_x", "Repeat X")),
    RepeatY(MessageTemplate("pattern_label_repeat_y", "Repeat Y")),
    RepeatZ(MessageTemplate("pattern_label_repeat_z", "Repeat Z")),
    StepX(MessageTemplate("pattern_label_step_x", "Step X")),
    StepY(MessageTemplate("pattern_label_step_y", "Step Y")),
    StepZ(MessageTemplate("pattern_label_step_z", "Step Z")),
    GridAngle(MessageTemplate("pattern_label_grid_angle", "Angle")),
    NumberOfHoles(MessageTemplate("pattern_label_number_of_holes", "Number of Holes")),
    StartX(MessageTemplate("pattern_label_start_x", "Starting Position X")),
    StartY(MessageTemplate("pattern_label_start_y", "Starting Position Y")),
    StartZ(MessageTemplate("pattern_label_start_z", "Starting Position Z")),
    EndX(MessageTemplate("pattern_label_end_x", "Ending Position X")),
    EndY(MessageTemplate("pattern_label_end_y", "Ending Position Y")),
    EndZ(MessageTemplate("pattern_label_end_z", "Ending Position Z")),
    PartRadius(MessageTemplate("arc_label_radius", "Radius")),
    CutterDiameter(MessageTemplate("arc_label_cutter_diameter", "Cutter Diameter")),
    Stepover(MessageTemplate("arc_label_stepover", "Stepover")),
}

/**
 * One error of a pattern dialog. [field] is the field that the error applies to, or null for
 * an error of the full input (for example the arc checks).
 */
data class PatternError(val field: PatternField?, val message: FunctionMessage) {
    /** The English text. */
    val text: String get() = message.text

    companion object {
        /**
         * Gives the English error text of the dialog: the header, then one line `\t• <message>`
         * for each error.
         */
        fun summary(errors: List<PatternError>): String = buildString {
            append(PatternTexts.ERROR_HEADER.template)
            errors.forEach { error -> append("\n\t• ").append(error.text) }
        }
    }
}
