package org.superdro.core.drawing

/** The severity of a [DrawingProblem]. */
enum class ProblemSeverity {
    /** The import continues. The setup screen can show the text. */
    Warning,

    /** The import stops. The "Drawing Not Imported" dialog shows the problem list. */
    Error,
}

/**
 * One problem of a drawing import. [code] is one
 * of [DrawingProblemCodes]. [message] is the English technical text of the problem list. The
 * problem list is technical data, as the error codes, so it has no translation.
 */
data class DrawingProblem(
    val code: String,
    val message: String,
    val severity: ProblemSeverity = ProblemSeverity.Error,
) {
    /** True for [ProblemSeverity.Error]. */
    val isError: Boolean get() = severity == ProblemSeverity.Error
}

/**
 * The problem codes of the DXF converter and of the wireframe reader. SuperDRO adds some codes
 * for the warnings and for the wireframe reader.
 */
object DrawingProblemCodes {
    // The limits.
    const val SOURCE_TOO_LARGE: String = "SOURCE_TOO_LARGE"
    const val PARSE_TIME_EXCEEDED: String = "PARSE_TIME_EXCEEDED"
    const val ENTITY_LIMIT_EXCEEDED: String = "ENTITY_LIMIT_EXCEEDED"
    const val BEZIER_SEGMENTS_EXCEEDED: String = "BEZIER_SEGMENTS_EXCEEDED"
    const val NESTING_DEPTH_EXCEEDED: String = "NESTING_DEPTH_EXCEEDED"
    const val COORDINATE_OVERFLOW: String = "COORDINATE_OVERFLOW"
    const val POLYLINE_POINTS_WARNING: String = "POLYLINE_POINTS_WARNING"
    const val CURVE_TOLERANCE_NOT_MET: String = "CURVE_TOLERANCE_NOT_MET"
    const val CURVE_TOO_COMPLEX: String = "CURVE_TOO_COMPLEX"

    // The other failure codes.
    const val NON_FINITE_COORDINATE: String = "NON_FINITE_COORDINATE"
    const val POLYLINE_POINTS_EXCEEDED: String = "POLYLINE_POINTS_EXCEEDED"
    const val INVALID_CURVE_TOLERANCE: String = "INVALID_CURVE_TOLERANCE"
    const val PARSE_ERROR: String = "PARSE_ERROR"
    const val UNSUPPORTED_FORMAT: String = "UNSUPPORTED_FORMAT"

    // Warnings.
    const val UNITS_ASSUMED: String = "UNITS_ASSUMED"
    const val OWNER_FLAG_DISAGREEMENT: String = "OWNER_FLAG_DISAGREEMENT"

    // Warnings that SuperDRO adds.
    /** The converter dropped entities that are not geometry, for example TEXT. */
    const val ENTITY_DROPPED: String = "ENTITY_DROPPED"

    /** The converter dropped entities of a type that it does not read. */
    const val UNSUPPORTED_ENTITY: String = "UNSUPPORTED_ENTITY"

    /** The converter dropped the edge boundaries of a HATCH. */
    const val HATCH_EDGES_DROPPED: String = "HATCH_EDGES_DROPPED"

    /** The converter dropped a shape with bad data, for example a circle with a radius of 0. */
    const val INVALID_GEOMETRY: String = "INVALID_GEOMETRY"

    /** An INSERT names a block that the file does not have. */
    const val MISSING_BLOCK: String = "MISSING_BLOCK"

    /** The block `*Model_Space` has entities. The converter reads only the ENTITIES section. */
    const val MODEL_SPACE_BLOCK_IGNORED: String = "MODEL_SPACE_BLOCK_IGNORED"

    /** `$DWGCODEPAGE` names a code page that SuperDRO does not have. */
    const val CODE_PAGE_UNKNOWN: String = "CODE_PAGE_UNKNOWN"

    // Codes of the wireframe reader.
    /** The wireframe document has a problem. */
    const val WIREFRAME_INVALID: String = "WIREFRAME_INVALID"

    /** A warning of the wireframe reader, for example a version other than 3.0. */
    const val WIREFRAME_WARNING: String = "WIREFRAME_WARNING"

    /** The converter result has the status `failed`. */
    const val CONVERSION_FAILED: String = "CONVERSION_FAILED"

    /** The reader stopped after 50 problems. */
    const val TOO_MANY_PROBLEMS: String = "TOO_MANY_PROBLEMS"
}

/** An internal stop of the converter or the reader with one error. */
internal class DrawingFailure(val problem: DrawingProblem) : RuntimeException(problem.message) {
    constructor(code: String, message: String) : this(DrawingProblem(code, message))
}
