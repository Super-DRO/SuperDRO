package org.superdro.core.functions.thread

import org.superdro.core.model.ThreadForm

/**
 * One row of a thread size table. All lengths are µm.
 *
 * @property designation The size text, for example `M10 × 1.5` (with U+00D7) or `1/4-20`.
 * @property nominalDiameterUm The basic major diameter D.
 * @property pitchUm The pitch P. For a UN row, P = 25400 / TPI.
 * @property threadsPerInch The threads per inch of a UN row, or null for an ISO row.
 * @property lengthOfEngagementUm The length of engagement LE of the ASME B1.1 formulas: LE = D
 *   for UNC and UNF, LE = 9P for UNEF, null for an ISO row.
 */
data class ThreadSize(
    val designation: String,
    val form: ThreadForm,
    val nominalDiameterUm: Double,
    val pitchUm: Double,
    val threadsPerInch: Double? = null,
    val lengthOfEngagementUm: Double? = null,
)

/**
 * The thread size tables of the standard forms.
 *
 * - ISO metric coarse: the ISO 261 coarse pitches from M3 to M68 (31 rows).
 * - ISO metric fine: the ISO 261 fine pitches from M3 × 0.35 to M68 × 1.5. For each diameter, the
 *   rows go from the largest pitch to the smallest pitch.
 * - UNC, UNF, UNEF: the ASME B1.1 series from #5-40, #5-44, and #12-32.
 *
 * The rows of a custom form are empty.
 */
object ThreadTables {

    /** Gives the rows of the form, in the order of the size list. */
    fun sizes(form: ThreadForm): List<ThreadSize> = when (form) {
        ThreadForm.IsoMetricCoarse -> isoCoarse
        ThreadForm.IsoMetricFine -> isoFine
        ThreadForm.Unc -> unc
        ThreadForm.Unf -> unf
        ThreadForm.Unef -> unef
        ThreadForm.CustomMetric, ThreadForm.CustomImperial -> emptyList()
    }

    /** Gives the row of the form with this designation, or null. */
    fun find(form: ThreadForm, designation: String): ThreadSize? =
        sizes(form).firstOrNull { size -> size.designation == designation }

    private fun iso(form: ThreadForm, diameterMm: Double, pitchMm: Double): ThreadSize = ThreadSize(
        designation = "M${trimNumber(diameterMm, 4)} × ${trimNumber(pitchMm, 4)}",
        form = form,
        nominalDiameterUm = diameterMm * 1000.0,
        pitchUm = pitchMm * 1000.0,
    )

    private val isoCoarse: List<ThreadSize> = listOf(
        3.0 to 0.5, 3.5 to 0.6, 4.0 to 0.7, 4.5 to 0.75, 5.0 to 0.8, 6.0 to 1.0, 7.0 to 1.0,
        8.0 to 1.25, 9.0 to 1.25, 10.0 to 1.5, 11.0 to 1.5, 12.0 to 1.75, 14.0 to 2.0, 16.0 to 2.0,
        18.0 to 2.5, 20.0 to 2.5, 22.0 to 2.5, 24.0 to 3.0, 27.0 to 3.0, 30.0 to 3.5, 33.0 to 3.5,
        36.0 to 4.0, 39.0 to 4.0, 42.0 to 4.5, 45.0 to 4.5, 48.0 to 5.0, 52.0 to 5.0, 56.0 to 5.5,
        60.0 to 5.5, 64.0 to 6.0, 68.0 to 6.0,
    ).map { (d, p) -> iso(ThreadForm.IsoMetricCoarse, d, p) }

    private val isoFinePitches: List<Pair<Double, List<Double>>> = listOf(
        3.0 to listOf(0.35),
        3.5 to listOf(0.35),
        4.0 to listOf(0.5),
        4.5 to listOf(0.5),
        5.0 to listOf(0.5),
        5.5 to listOf(0.5),
        6.0 to listOf(0.75),
        7.0 to listOf(0.75),
        8.0 to listOf(1.0, 0.75),
        9.0 to listOf(1.0, 0.75),
        10.0 to listOf(1.25, 1.0, 0.75),
        11.0 to listOf(1.0, 0.75),
        12.0 to listOf(1.5, 1.25, 1.0),
        14.0 to listOf(1.5, 1.25, 1.0),
        15.0 to listOf(1.5, 1.0),
        16.0 to listOf(1.5, 1.0),
        17.0 to listOf(1.5, 1.0),
        18.0 to listOf(2.0, 1.5, 1.0),
        20.0 to listOf(2.0, 1.5, 1.0),
        22.0 to listOf(2.0, 1.5, 1.0),
        24.0 to listOf(2.0, 1.5, 1.0),
        25.0 to listOf(2.0, 1.5, 1.0),
        26.0 to listOf(1.5),
        27.0 to listOf(2.0, 1.5, 1.0),
        28.0 to listOf(2.0, 1.5, 1.0),
        30.0 to listOf(3.0, 2.0, 1.5, 1.0),
        32.0 to listOf(2.0, 1.5),
        33.0 to listOf(3.0, 2.0, 1.5),
        35.0 to listOf(1.5),
        36.0 to listOf(3.0, 2.0, 1.5),
        38.0 to listOf(1.5),
        39.0 to listOf(3.0, 2.0, 1.5),
        40.0 to listOf(3.0, 2.0, 1.5),
        42.0 to listOf(4.0, 3.0, 2.0, 1.5),
        45.0 to listOf(4.0, 3.0, 2.0, 1.5),
        48.0 to listOf(4.0, 3.0, 2.0, 1.5),
        50.0 to listOf(3.0, 2.0, 1.5),
        52.0 to listOf(4.0, 3.0, 2.0, 1.5),
        55.0 to listOf(4.0, 3.0, 2.0, 1.5),
        56.0 to listOf(4.0, 3.0, 2.0, 1.5),
        58.0 to listOf(4.0, 3.0, 2.0, 1.5),
        60.0 to listOf(4.0, 3.0, 2.0, 1.5),
        62.0 to listOf(4.0, 3.0, 2.0, 1.5),
        64.0 to listOf(4.0, 3.0, 2.0, 1.5),
        65.0 to listOf(4.0, 3.0, 2.0, 1.5),
        68.0 to listOf(4.0, 3.0, 2.0, 1.5),
    )

    private val isoFine: List<ThreadSize> = isoFinePitches.flatMap { (d, pitches) ->
        pitches.map { p -> iso(ThreadForm.IsoMetricFine, d, p) }
    }

    /** A UN row: [diameterIn] in inch, [tpi] threads per inch, [unef] for LE = 9P. */
    private fun un(form: ThreadForm, size: String, diameterIn: Double, tpi: Double): ThreadSize {
        val pitchUm = 25400.0 / tpi
        val diameterUm = diameterIn * 25400.0
        val tpiText = when (tpi) {
            4.5 -> "4 1/2"
            else -> trimNumber(tpi, 2)
        }
        return ThreadSize(
            designation = "$size-$tpiText",
            form = form,
            nominalDiameterUm = diameterUm,
            pitchUm = pitchUm,
            threadsPerInch = tpi,
            lengthOfEngagementUm = if (form == ThreadForm.Unef) 9.0 * pitchUm else diameterUm,
        )
    }

    /** The major diameter of a number size: 0.060 + 0.013 × N inch. */
    private fun numberSize(n: Int): Double = 0.060 + 0.013 * n

    private val unc: List<ThreadSize> = listOf(
        Triple("#5", numberSize(5), 40.0), Triple("#6", numberSize(6), 32.0),
        Triple("#8", numberSize(8), 32.0), Triple("#10", numberSize(10), 24.0),
        Triple("#12", numberSize(12), 24.0), Triple("1/4", 0.25, 20.0), Triple("5/16", 0.3125, 18.0),
        Triple("3/8", 0.375, 16.0), Triple("7/16", 0.4375, 14.0), Triple("1/2", 0.5, 13.0),
        Triple("9/16", 0.5625, 12.0), Triple("5/8", 0.625, 11.0), Triple("3/4", 0.75, 10.0),
        Triple("7/8", 0.875, 9.0), Triple("1", 1.0, 8.0), Triple("1 1/8", 1.125, 7.0),
        Triple("1 1/4", 1.25, 7.0), Triple("1 3/8", 1.375, 6.0), Triple("1 1/2", 1.5, 6.0),
        Triple("1 3/4", 1.75, 5.0), Triple("2", 2.0, 4.5), Triple("2 1/4", 2.25, 4.5),
        Triple("2 1/2", 2.5, 4.0), Triple("2 3/4", 2.75, 4.0), Triple("3", 3.0, 4.0),
        Triple("3 1/4", 3.25, 4.0), Triple("3 1/2", 3.5, 4.0), Triple("3 3/4", 3.75, 4.0),
        Triple("4", 4.0, 4.0),
    ).map { (size, d, tpi) -> un(ThreadForm.Unc, size, d, tpi) }

    private val unf: List<ThreadSize> = listOf(
        Triple("#5", numberSize(5), 44.0), Triple("#6", numberSize(6), 40.0),
        Triple("#8", numberSize(8), 36.0), Triple("#10", numberSize(10), 32.0),
        Triple("#12", numberSize(12), 28.0), Triple("1/4", 0.25, 28.0), Triple("5/16", 0.3125, 24.0),
        Triple("3/8", 0.375, 24.0), Triple("7/16", 0.4375, 20.0), Triple("1/2", 0.5, 20.0),
        Triple("9/16", 0.5625, 18.0), Triple("5/8", 0.625, 18.0), Triple("3/4", 0.75, 16.0),
        Triple("7/8", 0.875, 14.0), Triple("1", 1.0, 12.0), Triple("1", 1.0, 14.0),
        Triple("1 1/8", 1.125, 12.0), Triple("1 1/4", 1.25, 12.0), Triple("1 3/8", 1.375, 12.0),
        Triple("1 1/2", 1.5, 12.0),
    ).map { (size, d, tpi) -> un(ThreadForm.Unf, size, d, tpi) }

    private val unef: List<ThreadSize> = listOf(
        Triple("#12", numberSize(12), 32.0), Triple("1/4", 0.25, 32.0), Triple("5/16", 0.3125, 32.0),
        Triple("3/8", 0.375, 32.0), Triple("7/16", 0.4375, 28.0), Triple("1/2", 0.5, 28.0),
        Triple("9/16", 0.5625, 24.0), Triple("5/8", 0.625, 24.0), Triple("11/16", 0.6875, 24.0),
        Triple("3/4", 0.75, 20.0), Triple("13/16", 0.8125, 20.0), Triple("7/8", 0.875, 20.0),
        Triple("15/16", 0.9375, 20.0), Triple("1", 1.0, 20.0), Triple("1 1/16", 1.0625, 18.0),
        Triple("1 1/8", 1.125, 18.0), Triple("1 3/16", 1.1875, 18.0), Triple("1 1/4", 1.25, 18.0),
        Triple("1 5/16", 1.3125, 18.0), Triple("1 3/8", 1.375, 18.0), Triple("1 7/16", 1.4375, 18.0),
        Triple("1 1/2", 1.5, 18.0), Triple("1 9/16", 1.5625, 18.0), Triple("1 5/8", 1.625, 18.0),
        Triple("1 11/16", 1.6875, 18.0),
    ).map { (size, d, tpi) -> un(ThreadForm.Unef, size, d, tpi) }
}
