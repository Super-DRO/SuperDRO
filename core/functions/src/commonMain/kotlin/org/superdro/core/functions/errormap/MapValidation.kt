package org.superdro.core.functions.errormap

import org.superdro.core.model.MapPoint

/**
 * One problem of an error map or a backlash map. The UI shows the English text of the KDoc of each
 * type, with the values in place of the
 * format arguments.
 */
sealed interface MapProblem {

    /** "Position %1$d is used more than once." */
    data class DuplicatePosition(val position: Long) : MapProblem

    /**
     * "The correction reverses direction between readings %1$d and %2$d." The two values are the
     * positions of the two neighbor points: `p2 + c2 < p1 + c1`.
     */
    data class Reversal(val firstPosition: Long, val secondPosition: Long) : MapProblem

    /**
     * "The correction leaves a dead band (no movement) between readings %1$d and %2$d." The two
     * values are the positions of the two neighbor points: `p2 + c2 == p1 + c1`.
     */
    data class DeadBand(val firstPosition: Long, val secondPosition: Long) : MapProblem

    /** "Backlash of %2$d at position %1$d is negative." */
    data class NegativeBacklash(val position: Long, val backlash: Long) : MapProblem

    /**
     * The backlash at a position is equal to or more than the counts of one screw turn. The
     * SuperDRO text: "Backlash of %2$d at position %1$d is
     * not less than one screw turn (%3$d counts)."
     */
    data class BacklashExceedsScrewRev(val position: Long, val backlash: Long, val countsPerScrewRev: Long) :
        MapProblem
}

/** The validators of the two map kinds. */
object MapValidation {

    /**
     * Validates an error map.
     *
     * 1. Each position that occurs more than once gives [MapProblem.DuplicatePosition]. When a
     *    duplicate exists, the check stops here.
     * 2. The check sorts the points by position. For each pair of neighbors, `p2 + c2 < p1 + c1`
     *    gives [MapProblem.Reversal], and `p2 + c2 == p1 + c1` gives [MapProblem.DeadBand]. The
     *    sums are 64-bit.
     *
     * Thus the corrected position must increase strictly from node to node.
     */
    fun validateErrorMap(points: List<MapPoint>): List<MapProblem> {
        val duplicates = duplicates(points)
        if (duplicates.isNotEmpty()) return duplicates
        val sorted = points.sortedBy { it.position }
        val problems = ArrayList<MapProblem>()
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            val first = a.position + a.value
            val second = b.position + b.value
            when {
                second < first -> problems.add(MapProblem.Reversal(a.position, b.position))
                second == first -> problems.add(MapProblem.DeadBand(a.position, b.position))
            }
        }
        return problems
    }

    /**
     * Validates a backlash map.
     *
     * 1. Duplicate positions, as in [validateErrorMap]. When a duplicate exists, the check stops.
     * 2. A negative value gives [MapProblem.NegativeBacklash].
     * 3. A value equal to or more than [countsPerScrewRev] gives
     *    [MapProblem.BacklashExceedsScrewRev]. The editor does this check only when it knows the
     *    screw turn count (a value more than 0). The save layer gives null and does not check it.
     */
    fun validateBacklashMap(points: List<MapPoint>, countsPerScrewRev: Long?): List<MapProblem> {
        val duplicates = duplicates(points)
        if (duplicates.isNotEmpty()) return duplicates
        val problems = ArrayList<MapProblem>()
        val screw = countsPerScrewRev?.takeIf { it > 0 }
        for (point in points.sortedBy { it.position }) {
            if (point.value < 0) problems.add(MapProblem.NegativeBacklash(point.position, point.value))
            if (screw != null && point.value >= screw) {
                problems.add(MapProblem.BacklashExceedsScrewRev(point.position, point.value, screw))
            }
        }
        return problems
    }

    /** Validates [points] with the validator of [kind]. */
    fun validate(kind: MapKind, points: List<MapPoint>, countsPerScrewRev: Long? = null): List<MapProblem> =
        when (kind) {
            MapKind.Error -> validateErrorMap(points)
            MapKind.Backlash -> validateBacklashMap(points, countsPerScrewRev)
        }

    private fun duplicates(points: List<MapPoint>): List<MapProblem> =
        points.groupingBy { it.position }.eachCount()
            .filter { it.value > 1 }
            .keys
            .sorted()
            .map { MapProblem.DuplicatePosition(it) }
}

/** The kind of a map in the map editor. */
enum class MapKind {
    /** An error correction map: the value is the correction in counts. */
    Error,

    /** A backlash map: the value is the lost motion in counts. */
    Backlash,
}
