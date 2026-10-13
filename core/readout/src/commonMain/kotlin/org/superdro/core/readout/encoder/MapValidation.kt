package org.superdro.core.readout.encoder

import org.superdro.core.model.MapPoint
import org.superdro.core.readout.ReadoutMessage
import org.superdro.core.readout.ReadoutText

/** One problem of an error map or a backlash map. */
sealed interface MapProblem {

    /** The text of the problem for the UI. */
    val message: ReadoutMessage

    /** The map has more than [limit] points. */
    data class TooManyPoints(val limit: Int = MapText.MAX_POINTS) : MapProblem {
        override val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.MapTooManyPoints, listOf(limit))
    }

    /** The line [line] (from 1) is not a point. */
    data class MalformedLine(val line: Int) : MapProblem {
        override val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.MapMalformedLine, listOf(line))
    }

    /** A map text that is not blank has no point. */
    data object NoPoints : MapProblem {
        override val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.MapNoPoints)
    }

    /** The position [position] occurs more than one time. */
    data class DuplicatePosition(val position: Long) : MapProblem {
        override val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.MapDuplicatePosition, listOf(position))
    }

    /** The corrected position `p + c` decreases from [first] to [second]. */
    data class Reversal(val first: Long, val second: Long) : MapProblem {
        override val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.MapReversal, listOf(first, second))
    }

    /** The corrected position `p + c` is the same at [first] and [second]. */
    data class DeadBand(val first: Long, val second: Long) : MapProblem {
        override val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.MapDeadBand, listOf(first, second))
    }

    /** A backlash map has the negative value [value] at [position]. */
    data class NegativeBacklash(val position: Long, val value: Long) : MapProblem {
        override val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.MapNegativeBacklash, listOf(position, value))
    }

    /** A backlash map value is not less than the counts of one screw turn. */
    data class BacklashExceedsScrewTurn(val position: Long, val value: Long, val countsPerScrewRev: Long) : MapProblem {
        override val message: ReadoutMessage
            get() = ReadoutMessage(ReadoutText.MapBacklashExceedsTurn, listOf(position, value, countsPerScrewRev))
    }
}

/**
 * The validation of an error map and a backlash map. SuperDRO validates a map at each import and at
 * each
 * load, and gives a problem list.
 */
object MapValidation {

    /**
     * Gives the problems of the error map [points]:
     * 1. Each position that occurs more than one time. When a duplicate exists, the check stops.
     * 2. For each pair of neighbors after a sort: `p2 + c2 < p1 + c1` is a reversal, and
     *    `p2 + c2 == p1 + c1` is a dead band. Thus the corrected position must increase strictly.
     *
     * More than [MapText.MAX_POINTS] points is also a problem.
     */
    fun errorMapProblems(points: List<MapPoint>): List<MapProblem> {
        val problems = ArrayList<MapProblem>()
        if (points.size > MapText.MAX_POINTS) problems.add(MapProblem.TooManyPoints())
        val duplicates = duplicatePositions(points)
        if (duplicates.isNotEmpty()) return problems + duplicates
        val sorted = points.sortedBy { point -> point.position }
        sorted.zipWithNext().forEach { (first, second) ->
            val before = first.position + first.value
            val after = second.position + second.value
            when {
                after < before -> problems.add(MapProblem.Reversal(first.position, second.position))
                after == before -> problems.add(MapProblem.DeadBand(first.position, second.position))
            }
        }
        return problems
    }

    /**
     * Gives the problems of the backlash map [points]: duplicate positions, negative values,
     * and values that are not less than [countsPerScrewRev] (only when it is known and more
     * than 0). More than [MapText.MAX_POINTS] points is also a problem.
     */
    fun backlashMapProblems(points: List<MapPoint>, countsPerScrewRev: Double?): List<MapProblem> {
        val problems = ArrayList<MapProblem>()
        if (points.size > MapText.MAX_POINTS) problems.add(MapProblem.TooManyPoints())
        problems.addAll(duplicatePositions(points))
        points.forEach { point ->
            if (point.value < 0L) problems.add(MapProblem.NegativeBacklash(point.position, point.value))
            if (countsPerScrewRev != null && countsPerScrewRev > 0.0 && point.value >= countsPerScrewRev) {
                problems.add(MapProblem.BacklashExceedsScrewTurn(point.position, point.value, countsPerScrewRev.toLong()))
            }
        }
        return problems
    }

    /**
     * Parses and validates an error map text. A blank text gives an empty map with no problem.
     * Each malformed line, more than [MapText.MAX_POINTS] points, a text with no points, and
     * each problem of [errorMapProblems] go into the list.
     */
    fun checkErrorMapText(text: String?): MapCheck {
        val parse = MapText.parse(text)
        val problems = textProblems(text, parse)
        if (!parse.exceededLimit) problems.addAll(errorMapProblems(parse.points))
        return MapCheck(parse, problems)
    }

    /** Parses and validates a backlash map text, as [checkErrorMapText]. */
    fun checkBacklashMapText(text: String?, countsPerScrewRev: Double?): MapCheck {
        val parse = MapText.parse(text)
        val problems = textProblems(text, parse)
        if (!parse.exceededLimit) problems.addAll(backlashMapProblems(parse.points, countsPerScrewRev))
        return MapCheck(parse, problems)
    }

    private fun textProblems(text: String?, parse: MapParseResult): MutableList<MapProblem> {
        val problems = ArrayList<MapProblem>()
        if (parse.exceededLimit) {
            problems.add(MapProblem.TooManyPoints())
            return problems
        }
        parse.malformedLines.forEach { line -> problems.add(MapProblem.MalformedLine(line)) }
        if (!text.isNullOrBlank() && parse.points.isEmpty()) problems.add(MapProblem.NoPoints)
        return problems
    }

    private fun duplicatePositions(points: List<MapPoint>): List<MapProblem> =
        points.groupingBy { point -> point.position }.eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()
            .map { position -> MapProblem.DuplicatePosition(position) }
}

/**
 * The result of a map check.
 *
 * @property problems An empty list means that the map is valid.
 */
data class MapCheck(val parse: MapParseResult, val problems: List<MapProblem>) {
    /** True when the map has no problem. */
    val valid: Boolean get() = problems.isEmpty()

    /** The points of the map. */
    val points: List<MapPoint> get() = parse.points
}
