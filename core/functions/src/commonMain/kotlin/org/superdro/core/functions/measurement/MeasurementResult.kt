package org.superdro.core.functions.measurement

import org.superdro.core.model.LoggedResult

/** The quantity of a result, and its number of CSV cells. */
enum class Quantity(val csvCells: Int) {
    /** A length in µm. One cell. */
    Length(1),

    /** An angle in degrees. One cell. */
    Angle(1),

    /** A position (x, y) in µm. Two cells. */
    Position(2),
}

/**
 * The 12 result keys in the CSV order. The KDoc of
 * each value gives the English label of the results table and of the text sheet.
 *
 * @property wire The wire name in the log payload.
 * @property csvHeaders The CSV headers. `<u>` is the unit text "mm" or "in".
 */
enum class ResultKey(val wire: String, val quantity: Quantity, val csvHeaders: List<String>) {
    /** "Circle" */
    Diameter("diameter", Quantity.Length, listOf("Diameter (<u>)")),

    /** "Center" */
    Center("center", Quantity.Position, listOf("Center X (<u>)", "Center Y (<u>)")),

    /** "Distance" */
    Distance("distance", Quantity.Length, listOf("Distance (<u>)")),

    /** "ΔX" */
    Dx("dx", Quantity.Length, listOf("DX (<u>)")),

    /** "ΔY" */
    Dy("dy", Quantity.Length, listOf("DY (<u>)")),

    /** "Midpoint" */
    Midpoint("midpoint", Quantity.Position, listOf("Midpoint X (<u>)", "Midpoint Y (<u>)")),

    /** "Angle" */
    IncludedAngle("included_angle", Quantity.Angle, listOf("Angle (°)")),

    /** "Supplement" */
    Supplement("supplement", Quantity.Angle, listOf("Supplement (°)")),

    /** "Corner" */
    Corner("corner", Quantity.Position, listOf("Corner X (<u>)", "Corner Y (<u>)")),

    /** "Angle to X" */
    AngleToX("angle_to_x", Quantity.Angle, listOf("Angle to X (°)")),

    /** "Angle to Y" */
    AngleToY("angle_to_y", Quantity.Angle, listOf("Angle to Y (°)")),

    /** "Position" */
    Position("position", Quantity.Position, listOf("Position X (<u>)", "Position Y (<u>)")),
    ;

    /** The English label of the key. The text sheet uses it when the UI gives no label. */
    val englishLabel: String
        get() = when (this) {
            Diameter -> "Circle"
            Center -> "Center"
            Distance -> "Distance"
            Dx -> "ΔX"
            Dy -> "ΔY"
            Midpoint -> "Midpoint"
            IncludedAngle -> "Angle"
            Supplement -> "Supplement"
            Corner -> "Corner"
            AngleToX -> "Angle to X"
            AngleToY -> "Angle to Y"
            Position -> "Position"
        }

    companion object {
        /** Gives the key of the wire name [wire], or null. */
        fun ofWire(wire: String): ResultKey? = entries.firstOrNull { key -> key.wire == wire }
    }
}

/** The value of a result. Lengths are µm, angles degrees. */
sealed interface ResultValue {

    /** A length, for example a distance. */
    data class Length(val um: Double) : ResultValue

    /** A diameter. The text has the sign "Ø". */
    data class Diameter(val um: Double) : ResultValue

    /** A position in the plane. */
    data class Position(val xUm: Double, val yUm: Double) : ResultValue

    /** An angle. */
    data class Angle(val degrees: Double) : ResultValue
}

/**
 * The name of a sub-datum that an add action makes. The
 * KDoc of each value gives the English name. The tag is always [MeasurementAddActions.MEASURED_TAG].
 */
enum class MeasuredSubDatumName {
    /** "Measured point" */
    MeasuredPoint,

    /** "Midpoint" */
    Midpoint,

    /** "Fitted center" */
    FittedCenter,

    /** "Corner" */
    Corner,
}

/** The add action of a result row. */
sealed interface AddAction {

    /**
     * Adds the position of the result as a sub-datum with [name] and the tag "Measured". The KDoc
     * of [MeasuredSubDatumName] gives the button texts.
     *
     * - [MeasuredSubDatumName.MeasuredPoint]: "Add this position as a sub-datum"
     * - [MeasuredSubDatumName.Midpoint]: "Add the midpoint as a sub-datum"
     * - [MeasuredSubDatumName.FittedCenter]: "Add center as a sub-datum"
     * - [MeasuredSubDatumName.Corner]: "Add the corner as a sub-datum"
     */
    data class AddSubDatum(val name: MeasuredSubDatumName) : AddAction

    /** "Add the fitted circle to the drawing": a circle shape on the layer "Measured". */
    data object AddCircleToDrawing : AddAction
}

/**
 * One row of the results table.
 *
 * @property value The value, or null while the set is not complete or when the function fails.
 *   The UI then shows "—".
 * @property addAction The add action of the row, or null.
 * @property addEnabled True when the add button is enabled: the value exists, and for the corner
 *   the distance rule agrees.
 */
data class MeasurementResult(
    val key: ResultKey,
    val value: ResultValue? = null,
    val addAction: AddAction? = null,
    val addEnabled: Boolean = false,
) {
    /** Gives the log form of this result, or null when the result has no value. */
    fun toLogged(): LoggedResult? = when (val result = value) {
        null -> null
        is ResultValue.Length -> LoggedResult(key = key.wire, value = result.um)
        is ResultValue.Diameter -> LoggedResult(key = key.wire, value = result.um)
        is ResultValue.Angle -> LoggedResult(key = key.wire, value = result.degrees)
        is ResultValue.Position -> LoggedResult(key = key.wire, x = result.xUm, y = result.yUm)
    }

    companion object {
        /**
         * Gives the value of the log result [logged], or null when the key is not known or the value
         * is missing. The key `diameter` gives a [ResultValue.Diameter]. The other lengths give a
         * [ResultValue.Length].
         */
        fun valueOf(logged: LoggedResult): Pair<ResultKey, ResultValue>? {
            val key = ResultKey.ofWire(logged.key) ?: return null
            val value: ResultValue = when (key.quantity) {
                Quantity.Position -> {
                    val x = logged.x ?: return null
                    val y = logged.y ?: return null
                    ResultValue.Position(x, y)
                }
                Quantity.Angle -> ResultValue.Angle(logged.value ?: return null)
                Quantity.Length -> {
                    val um = logged.value ?: return null
                    if (key == ResultKey.Diameter) ResultValue.Diameter(um) else ResultValue.Length(um)
                }
            }
            return key to value
        }
    }
}
