package org.superdro.core.functions.thread

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The tolerances of a thread class, in µm.
 *
 * @property deviationUm The fundamental deviation: `es` (external, 0 or less) or `EI` (internal).
 * @property pitchDiameterToleranceUm `Td2` (external) or `TD2` (internal).
 * @property crestDiameterToleranceUm `Td` of the major diameter (external) or `TD1` of the minor
 *   diameter (internal).
 */
data class ClassTolerances(
    val deviationUm: Double,
    val pitchDiameterToleranceUm: Double,
    val crestDiameterToleranceUm: Double,
)

/**
 * The status of a selected class that has no class values. The dialog
 * then shows "No tolerance is tabulated for this size — the diameters below are basic profile,
 * with no class allowance."
 */
enum class ClassStatus {
    /** The ISO 965 tables have no value for the diameter or the pitch. */
    OutsideTable,

    /** An ISO class on an inch thread, or an ASME class on a metric thread. */
    WrongUnit,

    /** An external class on an internal thread, or an internal class on an external thread. */
    WrongType,
}

/**
 * The ISO 965-1 tolerance grade 6 values for the classes 6g and 6H, in µm. The tables go by the
 * pitch, and the pitch diameter tables also go by the range of the basic major diameter D:
 * (1.5, 2.8], (2.8, 5.6], (5.6, 11.2], (11.2, 22.4], (22.4, 45], (45, 90], (90, 180], (180, 355]
 * mm. A value that is not in a table gives null ([ClassStatus.OutsideTable]).
 */
object Iso965 {

    /** The upper limits of the D ranges in mm. Each range excludes its lower limit. */
    private val rangeUpperMm = doubleArrayOf(2.8, 5.6, 11.2, 22.4, 45.0, 90.0, 180.0, 355.0)

    private const val RANGE_LOWEST_MM = 1.5

    /** The fundamental deviation `es` of the position g by the pitch in µm. */
    private val esG: Map<Long, Double> = mapOf(
        200L to -17.0, 250L to -18.0, 300L to -18.0, 350L to -19.0, 400L to -19.0, 450L to -20.0,
        500L to -20.0, 600L to -21.0, 700L to -22.0, 750L to -22.0, 800L to -24.0, 1000L to -26.0,
        1250L to -28.0, 1500L to -32.0, 1750L to -34.0, 2000L to -38.0, 2500L to -42.0,
        3000L to -48.0, 3500L to -53.0, 4000L to -60.0, 4500L to -63.0, 5000L to -71.0,
        5500L to -75.0, 6000L to -80.0, 8000L to -100.0,
    )

    /** The major diameter tolerance `Td` of grade 6 by the pitch in µm. */
    private val td6: Map<Long, Double> = mapOf(
        200L to 56.0, 250L to 67.0, 300L to 75.0, 350L to 85.0, 400L to 95.0, 450L to 100.0,
        500L to 106.0, 600L to 125.0, 700L to 140.0, 750L to 140.0, 800L to 150.0, 1000L to 180.0,
        1250L to 212.0, 1500L to 236.0, 1750L to 265.0, 2000L to 280.0, 2500L to 335.0,
        3000L to 375.0, 3500L to 425.0, 4000L to 475.0, 4500L to 500.0, 5000L to 530.0,
        5500L to 560.0, 6000L to 600.0, 8000L to 750.0,
    )

    /** The minor diameter tolerance `TD1` of grade 6 by the pitch in µm. */
    private val tD1Grade6: Map<Long, Double> = mapOf(
        250L to 71.0, 300L to 85.0, 350L to 100.0, 400L to 112.0, 450L to 125.0, 500L to 140.0,
        600L to 160.0, 700L to 180.0, 750L to 190.0, 800L to 200.0, 1000L to 236.0, 1250L to 265.0,
        1500L to 300.0, 1750L to 335.0, 2000L to 375.0, 2500L to 450.0, 3000L to 500.0,
        3500L to 560.0, 4000L to 600.0, 4500L to 670.0, 5000L to 710.0, 5500L to 750.0,
        6000L to 800.0, 8000L to 1000.0,
    )

    /** The pitch diameter tolerance `Td2` of grade 6, for each D range, by the pitch in µm. */
    private val td2Grade6: List<Map<Long, Double>> = listOf(
        mapOf(200L to 50.0, 250L to 56.0, 350L to 63.0, 400L to 67.0, 450L to 71.0),
        mapOf(350L to 67.0, 500L to 75.0, 600L to 85.0, 700L to 90.0, 750L to 90.0, 800L to 95.0),
        mapOf(750L to 100.0, 1000L to 112.0, 1250L to 118.0, 1500L to 132.0),
        mapOf(1000L to 118.0, 1250L to 132.0, 1500L to 140.0, 1750L to 150.0, 2000L to 160.0, 2500L to 170.0),
        mapOf(
            1000L to 125.0, 1500L to 150.0, 2000L to 170.0, 3000L to 200.0, 3500L to 212.0, 4000L to 224.0,
            4500L to 236.0,
        ),
        mapOf(
            1500L to 160.0, 2000L to 180.0, 3000L to 212.0, 4000L to 236.0, 5000L to 250.0, 5500L to 265.0,
            6000L to 280.0,
        ),
        mapOf(2000L to 190.0, 3000L to 224.0, 4000L to 250.0, 6000L to 300.0, 8000L to 335.0),
        mapOf(3000L to 250.0, 4000L to 280.0, 6000L to 315.0, 8000L to 355.0),
    )

    /** The pitch diameter tolerance `TD2` of grade 6, for each D range, by the pitch in µm. */
    private val tD2Grade6: List<Map<Long, Double>> = listOf(
        mapOf(350L to 85.0, 400L to 90.0, 450L to 95.0),
        mapOf(350L to 90.0, 500L to 100.0, 600L to 112.0, 700L to 118.0, 750L to 118.0, 800L to 125.0),
        mapOf(750L to 132.0, 1000L to 150.0, 1250L to 160.0, 1500L to 180.0),
        mapOf(1000L to 160.0, 1250L to 180.0, 1500L to 190.0, 1750L to 200.0, 2000L to 212.0, 2500L to 224.0),
        mapOf(
            1000L to 170.0, 1500L to 200.0, 2000L to 224.0, 3000L to 265.0, 3500L to 280.0, 4000L to 300.0,
            4500L to 315.0,
        ),
        mapOf(
            1500L to 212.0, 2000L to 236.0, 3000L to 280.0, 4000L to 315.0, 5000L to 335.0, 5500L to 355.0,
            6000L to 375.0,
        ),
        mapOf(2000L to 250.0, 3000L to 300.0, 4000L to 335.0, 6000L to 400.0, 8000L to 450.0),
        mapOf(3000L to 335.0, 4000L to 375.0, 6000L to 425.0, 8000L to 475.0),
    )

    /** Gives the index of the D range of [majorUm], or −1 when D is outside all ranges. */
    internal fun rangeIndex(majorUm: Double): Int {
        val mm = majorUm / 1000.0
        if (!(mm > RANGE_LOWEST_MM)) return -1
        return rangeUpperMm.indexOfFirst { upper -> mm <= upper }
    }

    private fun pitchKey(pitchUm: Double): Long? {
        val key = pitchUm.roundToLong()
        return if (abs(pitchUm - key) < 1e-6) key else null
    }

    /** Gives the values of the class 6g (external), or null when a table has no value. */
    fun class6g(majorUm: Double, pitchUm: Double): ClassTolerances? {
        val key = pitchKey(pitchUm) ?: return null
        val range = rangeIndex(majorUm)
        if (range < 0) return null
        val es = esG[key] ?: return null
        val td2 = td2Grade6[range][key] ?: return null
        val td = td6[key] ?: return null
        return ClassTolerances(es, td2, td)
    }

    /** Gives the values of the class 6H (internal, `EI` = 0), or null when a table has no value. */
    fun class6H(majorUm: Double, pitchUm: Double): ClassTolerances? {
        val key = pitchKey(pitchUm) ?: return null
        val range = rangeIndex(majorUm)
        if (range < 0) return null
        val tD2 = tD2Grade6[range][key] ?: return null
        val tD1 = tD1Grade6[key] ?: return null
        return ClassTolerances(0.0, tD2, tD1)
    }
}

/**
 * The ASME B1.1 class formulas for the classes 2A, 3A, 2B, and 3B. The
 * formulas use inch values. Each tolerance is rounded to 4 decimals, with a half away from zero.
 * The functions take and give µm.
 *
 * ```
 * base = 0.015·∛(P²) + 0.0015·√LE + 0.0015·∛D
 * 2A:  es = −round4(0.3·base);  Td2 = round4(1.0·base);    Td = round4(0.06·∛(P²))
 * 3A:  es = 0;                  Td2 = round4(0.75·base);   Td = round4(0.06·∛(P²))
 * 2B:  EI = 0;  TD2 = round4(1.3·base);    TD1 = round4(min(max(t, 0.25·P − 0.4·P²), 0.394·P))
 * 3B:  EI = 0;  TD2 = round4(0.975·base);  TD1 = round4(min(max(t, 0.12·P), 0.394·P))
 *      t = 0.03·P/D + 0.05·∛(P²) − 0.002
 * ```
 */
object AsmeB11 {

    private const val UM_PER_INCH = 25400.0

    /** Rounds an inch value to 4 decimals, with a half away from zero. */
    internal fun round4(inch: Double): Double {
        val scaled = floor(abs(inch) * 10000.0 + 0.5) / 10000.0
        return if (inch < 0) -scaled else scaled
    }

    /** The `base` value in inch. */
    private fun base(majorIn: Double, pitchIn: Double, engagementIn: Double): Double =
        0.015 * cbrt(pitchIn * pitchIn) + 0.0015 * sqrt(engagementIn) + 0.0015 * cbrt(majorIn)

    /**
     * Gives the values of an external class (2A or 3A) for the major diameter [majorUm], the pitch
     * [pitchUm], and the length of engagement [engagementUm].
     */
    fun external(class3A: Boolean, majorUm: Double, pitchUm: Double, engagementUm: Double): ClassTolerances {
        val d = majorUm / UM_PER_INCH
        val p = pitchUm / UM_PER_INCH
        val b = base(d, p, engagementUm / UM_PER_INCH)
        val es = if (class3A) 0.0 else -round4(0.3 * b)
        val td2 = round4((if (class3A) 0.75 else 1.0) * b)
        val td = round4(0.06 * cbrt(p * p))
        return ClassTolerances(es * UM_PER_INCH, td2 * UM_PER_INCH, td * UM_PER_INCH)
    }

    /**
     * Gives the values of an internal class (2B or 3B) for the major diameter [majorUm], the pitch
     * [pitchUm], and the length of engagement [engagementUm].
     */
    fun internal(class3B: Boolean, majorUm: Double, pitchUm: Double, engagementUm: Double): ClassTolerances {
        val d = majorUm / UM_PER_INCH
        val p = pitchUm / UM_PER_INCH
        val b = base(d, p, engagementUm / UM_PER_INCH)
        val tD2 = round4((if (class3B) 0.975 else 1.3) * b)
        val t = 0.03 * p / d + 0.05 * cbrt(p * p) - 0.002
        val floorValue = if (class3B) 0.12 * p else 0.25 * p - 0.4 * p * p
        val tD1 = round4(min(max(t, floorValue), 0.394 * p))
        return ClassTolerances(0.0, tD2 * UM_PER_INCH, tD1 * UM_PER_INCH)
    }
}
