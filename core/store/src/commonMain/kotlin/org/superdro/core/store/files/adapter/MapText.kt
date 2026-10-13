package org.superdro.core.store.files.adapter

import org.superdro.core.model.MapPoint
import org.superdro.core.readout.encoder.MapText as MapGrammar
import org.superdro.core.store.files.ImportMessage
import org.superdro.core.store.files.MessageKey

/**
 * The checks of an error map or a backlash map text at each import, and the text of an
 * export.
 *
 * The grammar of the text is in `core:readout` ([MapGrammar], `org.superdro.core.readout.encoder.MapText`):
 * one point on each line, two whole numbers separated by `,`, TAB, or spaces. A line that starts
 * with `#` is a comment. Up to 5 header lines with two words come before the first point. Each
 * other line is a malformed line. This object gives the problems as [ImportMessage] values for the
 * import report.
 *
 * The readout module applies the map at run time. This object only checks and writes the text.
 */
object MapText {

    /** The most points of one map: 20000. */
    const val MAX_POINTS: Int = MapGrammar.MAX_POINTS

    /** The header line of an exported error map. */
    const val ERROR_MAP_HEADER: String = "position,correction"

    /** The header line of an exported backlash map. */
    const val BACKLASH_MAP_HEADER: String = "position,backlash"

    /**
     * The result of a check: the points of a valid map, or the problems. A blank text gives no
     * points and no problems.
     *
     * @property comments The comment lines of a valid map, each with its `#` (the provenance,
     *   for [org.superdro.core.model.EncoderProfile.errorMapComments]). Empty for a map with
     *   problems.
     */
    data class Check(val points: List<MapPoint>, val problems: List<ImportMessage>, val comments: List<String> = emptyList()) {
        val valid: Boolean get() = problems.isEmpty()
    }

    /**
     * The validation of the points of an error map:
     * a position that is in the map more than one time, then for each pair of neighbors
     * `(p1, c1)`, `(p2, c2)`: `p2 + c2 < p1 + c1` is a reversal, and `p2 + c2 == p1 + c1` is a dead
     * band. Thus the corrected position must increase from node to node.
     */
    fun validateErrorMap(points: List<MapPoint>): List<ImportMessage> {
        duplicates(points).takeIf(List<ImportMessage>::isNotEmpty)?.let { return it }
        val sorted = points.sortedBy(MapPoint::position)
        return sorted.zipWithNext().mapNotNull { (first, second) ->
            val a = first.position + first.value
            val b = second.position + second.value
            when {
                b < a -> ImportMessage(MessageKey.MapReversal, first.position, second.position)
                b == a -> ImportMessage(MessageKey.MapDeadBand, first.position, second.position)
                else -> null
            }
        }
    }

    /**
     * The validation of the points of a backlash map: no
     * position more than one time, no value less than 0, and each value less than one turn of
     * the leadscrew when [countsPerScrewRev] is known.
     */
    fun validateBacklashMap(points: List<MapPoint>, countsPerScrewRev: Double?): List<ImportMessage> {
        duplicates(points).takeIf(List<ImportMessage>::isNotEmpty)?.let { return it }
        return points.sortedBy(MapPoint::position).mapNotNull { point ->
            when {
                point.value < 0 -> ImportMessage(MessageKey.MapNegativeBacklash, point.position)
                countsPerScrewRev != null && point.value >= countsPerScrewRev -> ImportMessage(MessageKey.MapBacklashExceedsTurn, point.position)
                else -> null
            }
        }
    }

    /** Layer 1 and layer 2 for an error map text. */
    fun checkErrorMap(text: String?): Check = check(text) { points -> validateErrorMap(points) }

    /** The checks of a backlash map text. */
    fun checkBacklashMap(text: String?, countsPerScrewRev: Double?): Check = check(text) { points -> validateBacklashMap(points, countsPerScrewRev) }

    private fun check(text: String?, validate: (List<MapPoint>) -> List<ImportMessage>): Check {
        if (text.isNullOrBlank()) return Check(emptyList(), emptyList())
        val result = MapGrammar.parse(text)
        val problems = buildList {
            if (result.exceededLimit) add(ImportMessage(MessageKey.MapTooManyPoints, MAX_POINTS))
            result.malformedLines.forEach { line -> add(ImportMessage(MessageKey.MapMalformedLine, line)) }
            if (result.sawDecimalValues) add(ImportMessage(MessageKey.MapDecimalValues))
            if (!result.exceededLimit && result.points.isEmpty()) add(ImportMessage(MessageKey.MapNoPoints))
            if (isEmpty()) addAll(validate(result.points))
        }
        return if (problems.isEmpty()) {
            Check(result.points, emptyList(), result.comments.map { text -> "$COMMENT_PREFIX$text" })
        } else {
            Check(emptyList(), problems)
        }
    }

    private fun duplicates(points: List<MapPoint>): List<ImportMessage> =
        points.groupingBy(MapPoint::position).eachCount().filterValues { count -> count > 1 }.keys
            .map { position -> ImportMessage(MessageKey.MapDuplicatePosition, position) }

    /**
     * Writes [points] with the header line [header], one point on each line, with LF between the
     * lines and no LF after the last line. The [comments] lines come first: each line starts with
     * `#`, so that an import gives the same comments again.
     */
    fun write(points: List<MapPoint>, header: String = ERROR_MAP_HEADER, comments: List<String> = emptyList()): String =
        (comments.map(::commentLine) + MapGrammar.write(points, header).removeSuffix("\n")).joinToString("\n")

    private fun commentLine(comment: String): String {
        val text = comment.trim()
        return if (text.startsWith("#")) text else "$COMMENT_PREFIX$text"
    }

    private const val COMMENT_PREFIX: String = "# "
}
