package org.superdro.core.functions.thread

import kotlin.math.abs

/** The series of a standard twist drill. */
enum class DrillSeries {
    Metric,
    Fractional,
    Number,
    Letter,
}

/**
 * One standard drill size. All lengths are µm.
 *
 * @property label The size designation, for example `8.5 mm`, `#7`, `C`, or `1/4 in`.
 * @property series The drill series.
 * @property diameterUm The drill diameter in µm.
 */
data class StandardDrill(
    val label: String,
    val series: DrillSeries,
    val diameterUm: Double,
)

/**
 * The standard drills next to a target diameter.
 *
 * @property smallerOrEqual The largest drill with a diameter less than or equal to the target, or
 *   null when the target is smaller than the smallest drill in the table.
 * @property largerOrEqual The smallest drill with a diameter greater than or equal to the target,
 *   or null when the target is larger than the largest drill in the table.
 * @property closest The drill with the smallest absolute difference from the target diameter.
 */
data class NearestDrills(
    val smallerOrEqual: StandardDrill?,
    val largerOrEqual: StandardDrill?,
    val closest: StandardDrill,
)

/**
 * Standard twist drill tables in µm:
 * - Metric: 0.1 mm steps from 1.0 mm to 68.0 mm plus standard 0.05 mm tap drill sizes.
 * - Number: #80 (`0.0135 in`) to #1 (`0.2280 in`).
 * - Letter: A (`0.2340 in`) to Z (`0.4130 in`).
 * - Fractional: 1/64 in steps from 1/64 in to 2 in.
 */
object StandardDrillTables {

    private const val UM_PER_MM: Double = 1000.0
    private const val UM_PER_INCH: Double = 25400.0
    private const val TOLERANCE_UM: Double = 1e-6

    private val extraMetricTapSizesTenthsMm: IntArray = intArrayOf(
        125, 175, 205, 225, 275, 325, 375, 425, 475,
        675, 725, 775, 825, 875, 925, 975, 1025,
        1050, 1150, 1250, 1350, 1450, 1550, 1650, 1750,
        1850, 1950, 2050, 2150, 2250, 2350, 2450, 2550,
    )

    /** The metric drill table sorted by diameter from smallest to largest. */
    val metricDrills: List<StandardDrill> = buildMetricDrills()

    /** The ANSI/ASME number drill table (#80 to #1) sorted by diameter from smallest to largest. */
    val numberDrills: List<StandardDrill> = buildNumberDrills()

    /** The ANSI/ASME letter drill table (A to Z) sorted by diameter from smallest to largest. */
    val letterDrills: List<StandardDrill> = buildLetterDrills()

    /** The fractional inch drill table (1/64 in to 2 in) sorted by diameter from smallest to largest. */
    val fractionalDrills: List<StandardDrill> = buildFractionalDrills()

    /** All imperial drills (Number, Letter, Fractional) sorted by diameter from smallest to largest. */
    val imperialDrills: List<StandardDrill> = buildImperialDrills()

    /** Gives the drill table of the [series]. */
    fun drillsForSeries(series: DrillSeries): List<StandardDrill> = when (series) {
        DrillSeries.Metric -> metricDrills
        DrillSeries.Fractional -> fractionalDrills
        DrillSeries.Number -> numberDrills
        DrillSeries.Letter -> letterDrills
    }

    /**
     * Gives the nearest drills to [targetDiameterUm] from the metric table when [metricPreferred]
     * is true, or from the combined imperial tables (Number, Letter, Fractional) when
     * [metricPreferred] is false.
     */
    fun nearest(targetDiameterUm: Double, metricPreferred: Boolean = true): NearestDrills {
        val table = if (metricPreferred) metricDrills else imperialDrills
        return checkNotNull(findNearestInList(targetDiameterUm, table))
    }

    /** Alias of [nearest] for callers that lookup the nearest standard drills. */
    fun nearestDrills(targetDiameterUm: Double, metricPreferred: Boolean = true): NearestDrills =
        nearest(targetDiameterUm, metricPreferred)

    /** Gives the nearest drills to [targetDiameterUm] in a single [series], or null if the series is empty. */
    fun nearestInSeries(targetDiameterUm: Double, series: DrillSeries): NearestDrills? =
        findNearestInList(targetDiameterUm, drillsForSeries(series))

    private fun findNearestInList(
        targetDiameterUm: Double,
        drills: List<StandardDrill>,
    ): NearestDrills? {
        if (drills.isEmpty()) return null
        var smallerOrEqual: StandardDrill? = null
        var largerOrEqual: StandardDrill? = null
        for (drill in drills) {
            if (drill.diameterUm <= targetDiameterUm + TOLERANCE_UM) {
                smallerOrEqual = drill
            }
            if (largerOrEqual == null && drill.diameterUm >= targetDiameterUm - TOLERANCE_UM) {
                largerOrEqual = drill
            }
        }
        val closest = when {
            smallerOrEqual == null -> checkNotNull(largerOrEqual)
            largerOrEqual == null -> smallerOrEqual
            abs(smallerOrEqual.diameterUm - targetDiameterUm) <= abs(largerOrEqual.diameterUm - targetDiameterUm) ->
                smallerOrEqual
            else -> largerOrEqual
        }
        return NearestDrills(
            smallerOrEqual = smallerOrEqual,
            largerOrEqual = largerOrEqual,
            closest = closest,
        )
    }

    private fun buildMetricDrills(): List<StandardDrill> {
        val hundredthsMmSet = sortedSetOf<Int>()
        for (tenth in 10..680) {
            hundredthsMmSet.add(tenth * 10)
        }
        for (hundredth in extraMetricTapSizesTenthsMm) {
            hundredthsMmSet.add(hundredth)
        }
        return hundredthsMmSet.map { hundredths ->
            val diameterMm = hundredths / 100.0
            val decimals = if (hundredths % 10 == 0) 1 else 2
            StandardDrill(
                label = "${fixed(diameterMm, decimals)} mm",
                series = DrillSeries.Metric,
                diameterUm = hundredths * 10.0,
            )
        }
    }

    private fun buildNumberDrills(): List<StandardDrill> {
        val sizesInches = listOf(
            80 to 0.0135, 79 to 0.0145, 78 to 0.0160, 77 to 0.0180, 76 to 0.0200,
            75 to 0.0210, 74 to 0.0225, 73 to 0.0240, 72 to 0.0250, 71 to 0.0260,
            70 to 0.0280, 69 to 0.0292, 68 to 0.0310, 67 to 0.0320, 66 to 0.0330,
            65 to 0.0350, 64 to 0.0360, 63 to 0.0370, 62 to 0.0380, 61 to 0.0390,
            60 to 0.0400, 59 to 0.0410, 58 to 0.0420, 57 to 0.0430, 56 to 0.0465,
            55 to 0.0520, 54 to 0.0550, 53 to 0.0595, 52 to 0.0635, 51 to 0.0670,
            50 to 0.0700, 49 to 0.0730, 48 to 0.0760, 47 to 0.0785, 46 to 0.0810,
            45 to 0.0820, 44 to 0.0860, 43 to 0.0890, 42 to 0.0935, 41 to 0.0960,
            40 to 0.0980, 39 to 0.0995, 38 to 0.1015, 37 to 0.1040, 36 to 0.1065,
            35 to 0.1100, 34 to 0.1110, 33 to 0.1130, 32 to 0.1160, 31 to 0.1200,
            30 to 0.1285, 29 to 0.1360, 28 to 0.1405, 27 to 0.1440, 26 to 0.1470,
            25 to 0.1495, 24 to 0.1520, 23 to 0.1540, 22 to 0.1570, 21 to 0.1590,
            20 to 0.1610, 19 to 0.1660, 18 to 0.1695, 17 to 0.1730, 16 to 0.1770,
            15 to 0.1800, 14 to 0.1820, 13 to 0.1850, 12 to 0.1890, 11 to 0.1910,
            10 to 0.1935, 9 to 0.1960, 8 to 0.1990, 7 to 0.2010, 6 to 0.2040,
            5 to 0.2055, 4 to 0.2090, 3 to 0.2130, 2 to 0.2210, 1 to 0.2280,
        )
        return sizesInches.map { (number, inches) ->
            StandardDrill(
                label = "#$number",
                series = DrillSeries.Number,
                diameterUm = inches * UM_PER_INCH,
            )
        }
    }

    private fun buildLetterDrills(): List<StandardDrill> {
        val sizesInches = listOf(
            "A" to 0.2340, "B" to 0.2380, "C" to 0.2420, "D" to 0.2460, "E" to 0.2500,
            "F" to 0.2570, "G" to 0.2610, "H" to 0.2660, "I" to 0.2720, "J" to 0.2770,
            "K" to 0.2810, "L" to 0.2900, "M" to 0.2950, "N" to 0.3020, "O" to 0.3160,
            "P" to 0.3230, "Q" to 0.3320, "R" to 0.3390, "S" to 0.3480, "T" to 0.3580,
            "U" to 0.3680, "V" to 0.3770, "W" to 0.3860, "X" to 0.3970, "Y" to 0.4040,
            "Z" to 0.4130,
        )
        return sizesInches.map { (letter, inches) ->
            StandardDrill(
                label = letter,
                series = DrillSeries.Letter,
                diameterUm = inches * UM_PER_INCH,
            )
        }
    }

    private fun buildFractionalDrills(): List<StandardDrill> =
        (1..128).map { sixtyFourths ->
            val whole = sixtyFourths / 64
            val rem = sixtyFourths % 64
            val label = when {
                rem == 0 -> "$whole in"
                else -> {
                    val g = gcd(rem, 64)
                    val num = rem / g
                    val den = 64 / g
                    if (whole == 0) "$num/$den in" else "$whole $num/$den in"
                }
            }
            StandardDrill(
                label = label,
                series = DrillSeries.Fractional,
                diameterUm = (sixtyFourths / 64.0) * UM_PER_INCH,
            )
        }

    private fun buildImperialDrills(): List<StandardDrill> {
        val combined = (fractionalDrills + numberDrills + letterDrills)
            .sortedWith(compareBy<StandardDrill> { it.diameterUm }.thenBy { it.series.ordinal })
        val deduplicated = mutableListOf<StandardDrill>()
        for (drill in combined) {
            val prev = deduplicated.lastOrNull()
            if (prev == null || abs(prev.diameterUm - drill.diameterUm) > TOLERANCE_UM) {
                deduplicated.add(drill)
            }
        }
        return deduplicated
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
