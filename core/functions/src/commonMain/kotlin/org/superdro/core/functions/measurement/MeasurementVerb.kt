package org.superdro.core.functions.measurement

/**
 * The step text of the armed slot. The KDoc of each value gives
 * the English text. The UI maps each value to its string.
 */
enum class MeasurementStep {
    /** "Locate the point" */
    LocateThePoint,

    /** "Locate the first point" */
    LocateTheFirstPoint,

    /** "Locate the second point" */
    LocateTheSecondPoint,

    /** "Locate a point on the circle" */
    LocateAPointOnTheCircle,

    /** "Locate a second point, well apart" */
    LocateASecondPointWellApart,

    /** "Locate the third point" */
    LocateTheThirdPoint,

    /** "Locate point #1 on the line" */
    LocatePoint1OnTheLine,

    /** "Locate point #2 on the line" */
    LocatePoint2OnTheLine,

    /** "Locate point #1 on the first line" */
    LocatePoint1OnTheFirstLine,

    /** "Locate point #2 on the first line" */
    LocatePoint2OnTheFirstLine,

    /** "Locate point #1 on the second line" */
    LocatePoint1OnTheSecondLine,

    /** "Locate point #2 on the second line" */
    LocatePoint2OnTheSecondLine,
}

/**
 * The five measurement functions, in the order of the
 * function strip. The KDoc of each value gives the English title.
 *
 * @property wire The wire ID of the function in the log.
 * @property slotGroups The group of each slot. A wider gap in the UI separates two groups.
 * @property steps The step text of each slot.
 * @property helpName The name of the help text of the function.
 */
enum class MeasurementVerb(
    val wire: String,
    val slotGroups: List<Int>,
    val steps: List<MeasurementStep>,
    val helpName: String,
) {
    /** "Point" */
    Point(
        wire = "point",
        slotGroups = listOf(0),
        steps = listOf(MeasurementStep.LocateThePoint),
        helpName = "point",
    ),

    /** "Point-to-Point Distance" */
    Distance(
        wire = "distance_2_points",
        slotGroups = listOf(0, 0),
        steps = listOf(MeasurementStep.LocateTheFirstPoint, MeasurementStep.LocateTheSecondPoint),
        helpName = "distance",
    ),

    /** "Circle from 3 points" */
    Circle(
        wire = "circle_from_3_points",
        slotGroups = listOf(0, 0, 0),
        steps = listOf(
            MeasurementStep.LocateAPointOnTheCircle,
            MeasurementStep.LocateASecondPointWellApart,
            MeasurementStep.LocateTheThirdPoint,
        ),
        helpName = "circle3",
    ),

    /** "Line Angle" */
    LineAngle(
        wire = "line_angle",
        slotGroups = listOf(0, 0),
        steps = listOf(MeasurementStep.LocatePoint1OnTheLine, MeasurementStep.LocatePoint2OnTheLine),
        helpName = "line_angle",
    ),

    /** "Angle Between Lines" */
    AngleBetweenLines(
        wire = "angle_between_lines",
        slotGroups = listOf(0, 0, 1, 1),
        steps = listOf(
            MeasurementStep.LocatePoint1OnTheFirstLine,
            MeasurementStep.LocatePoint2OnTheFirstLine,
            MeasurementStep.LocatePoint1OnTheSecondLine,
            MeasurementStep.LocatePoint2OnTheSecondLine,
        ),
        helpName = "angle_lines",
    ),
    ;

    /** The number of slots. */
    val slotCount: Int get() = slotGroups.size

    /**
     * True when the function does not compensate the tip radius. Then a point with a tip radius
     * gets the note [PointNote.Uncompensated].
     */
    val uncompensated: Boolean get() = this == Point || this == Distance

    companion object {
        /** Gives the function of the wire ID [wire], or null. */
        fun ofWire(wire: String): MeasurementVerb? = entries.firstOrNull { verb -> verb.wire == wire }
    }
}
