package org.superdro.core.readout.encoder

import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.MapPoint

/**
 * The result of the parse of a map text ([MapText.parse]).
 *
 * @property points The points in the order of the text.
 * @property malformedLines The line numbers (from 1) of the lines that are not a point, a
 *   comment, a blank line, or a header line.
 * @property comments The first [MapText.MAX_COMMENTS] comment lines, without the `#`.
 * @property exceededLimit True when the text has more than [MapText.MAX_POINTS] points or
 *   malformed lines. Then [points] and [malformedLines] are empty.
 * @property sawDecimalValues True when a malformed line has a token with a `.` that is a number.
 *   The flag also counts the lines before the limit when [exceededLimit] is true.
 */
data class MapParseResult(
    val points: List<MapPoint> = emptyList(),
    val malformedLines: List<Int> = emptyList(),
    val comments: List<String> = emptyList(),
    val exceededLimit: Boolean = false,
    val sawDecimalValues: Boolean = false,
)

/**
 * The text grammar of an error map and a backlash map. This is the one parser of the map text. The
 * map editor import
 * of `core:functions` and the file checks of `core:store` use it.
 *
 * ```text
 * map        = { line EOL }
 * EOL        = CR LF | LF | CR
 * line       = blank | comment | header | point | other
 * comment    = "#" any                      (line starts with "#" after trim)
 * point      = int SEP+ int                 (exactly 2 tokens after split)
 * SEP        = "," | TAB | " "
 * int        = base 10, optional sign, no decimal point (32-bit)
 * header     = 2 tokens, no token parses as a double, before the first point, max 5 lines
 * other      = any other line                (a "malformed line")
 * ```
 */
object MapText {

    /** The most points of one map. */
    const val MAX_POINTS: Int = EncoderProfile.MAX_MAP_POINTS

    /** The most comment lines that the parse keeps. */
    const val MAX_COMMENTS: Int = 50

    /** The most header lines that the parse skips. */
    const val MAX_HEADER_LINES: Int = 5

    private val SEPARATORS = charArrayOf(',', '\t', ' ')

    /**
     * Parses a map text. A null or blank text gives no points and no problems. The line counter
     * counts all lines from 1, also the blank lines.
     */
    fun parse(text: String?): MapParseResult {
        if (text.isNullOrBlank()) return MapParseResult()
        val points = ArrayList<MapPoint>()
        val malformed = ArrayList<Int>()
        val comments = ArrayList<String>()
        var headerLines = 0
        var sawDecimal = false
        text.lines().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("#")) {
                if (comments.size < MAX_COMMENTS) comments.add(line.substring(1).trim())
                return@forEachIndexed
            }
            val tokens = line.split(*SEPARATORS).map { token -> token.trim() }.filter { token -> token.isNotEmpty() }
            val position = if (tokens.size == 2) tokens[0].toIntOrNull() else null
            val value = if (tokens.size == 2) tokens[1].toIntOrNull() else null
            when {
                position != null && value != null -> {
                    if (points.size >= MAX_POINTS) return exceeded(comments, sawDecimal)
                    points.add(MapPoint(position.toLong(), value.toLong()))
                }
                tokens.size == 2 && points.isEmpty() && headerLines < MAX_HEADER_LINES &&
                    tokens.none { token -> token.toDoubleOrNull() != null } -> headerLines += 1
                else -> {
                    if (malformed.size >= MAX_POINTS) return exceeded(comments, sawDecimal)
                    malformed.add(lineNumber)
                    if (tokens.any { token -> '.' in token && token.toDoubleOrNull() != null }) sawDecimal = true
                }
            }
        }
        return MapParseResult(points, malformed, comments, exceededLimit = false, sawDecimalValues = sawDecimal)
    }

    private fun exceeded(comments: List<String>, sawDecimal: Boolean): MapParseResult =
        MapParseResult(comments = comments, exceededLimit = true, sawDecimalValues = sawDecimal)

    /** Gives the text of [points]: one `position,value` line for each point, with a [header] line. */
    fun write(points: List<MapPoint>, header: String = "position,correction"): String = buildString {
        append(header)
        append('\n')
        points.forEach { point ->
            append(point.position)
            append(',')
            append(point.value)
            append('\n')
        }
    }
}
