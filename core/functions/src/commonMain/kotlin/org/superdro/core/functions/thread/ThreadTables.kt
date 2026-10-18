package org.superdro.core.functions.thread

import org.superdro.core.model.ThreadForm

/**
 * One row of a thread size table. All lengths are µm.
 *
 * @property designation The size text, for example `M10 × 1.5` (with U+00D7) or `1/4-20`.
 * @property nominalDiameterUm The basic major diameter D.
 * @property pitchUm The pitch P. For an inch row, P = 25400 / TPI.
 * @property threadsPerInch The threads per inch of an inch or pipe row, or null for a metric row.
 * @property lengthOfEngagementUm The length of engagement LE of the tolerance formulas, or null.
 * @property e0PitchDiameterUm The pitch diameter `E0` at the small end of a tapered pipe thread,
 *   or null.
 * @property e1PitchDiameterUm The pitch diameter `E1` at the hand-tight gauge plane `L1` of a
 *   tapered pipe thread, or null.
 * @property l1LengthUm The hand-tight engagement length `L1` of a tapered pipe thread, or null.
 * @property l2LengthUm The effective thread length `L2` of a tapered pipe thread, or null.
 */
data class ThreadSize(
    val designation: String,
    val form: ThreadForm,
    val nominalDiameterUm: Double,
    val pitchUm: Double,
    val threadsPerInch: Double? = null,
    val lengthOfEngagementUm: Double? = null,
    val e0PitchDiameterUm: Double? = null,
    val e1PitchDiameterUm: Double? = null,
    val l1LengthUm: Double? = null,
    val l2LengthUm: Double? = null,
)

/**
 * The thread size tables of the standard forms.
 *
 * - ISO metric coarse: the ISO 261 coarse pitches from M3 to M68 (31 rows).
 * - ISO metric fine: the ISO 261 fine pitches from M3 × 0.35 to M68 × 1.5. For each diameter, the
 *   rows go from the largest pitch to the smallest pitch.
 * - UNC, UNF, UNEF: the ASME B1.1 series from #5-40, #5-44, and #12-32.
 * - BSW, BSF: the BS 84 coarse and fine series from 1/8 in to 2 in.
 * - BSPP: the ISO 228-1 parallel pipe series from G 1/16 to G 2.
 * - ISO trapezoidal: the ISO 2902 preferred series from Tr 8 × 1.5 to Tr 60 × 9.
 * - Acme: the ASME B1.5 general purpose series from 1/4-16 to 2-4.
 * - NPT: the ASME B1.20.1 tapered pipe series from 1/16-27 to 2-11.5.
 * - BSPT: the ISO 7-1 tapered pipe series from R 1/16-28 to R 2-11.
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
        ThreadForm.Bsw -> bsw
        ThreadForm.Bsf -> bsf
        ThreadForm.Bspp -> bspp
        ThreadForm.IsoTrapezoidal -> isoTrapezoidal
        ThreadForm.Acme -> acme
        ThreadForm.Npt -> npt
        ThreadForm.Bspt -> bspt
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

    private fun tr(diameterMm: Double, pitchMm: Double): ThreadSize = ThreadSize(
        designation = "Tr ${trimNumber(diameterMm, 4)} × ${trimNumber(pitchMm, 4)}",
        form = ThreadForm.IsoTrapezoidal,
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

    /** An inch row with decimal TPI text (`2-4.5` or `1/4-20`). */
    private fun inchSeries(
        form: ThreadForm,
        size: String,
        diameterIn: Double,
        tpi: Double,
        engagementFactor: Double = 1.0,
    ): ThreadSize {
        val pitchUm = 25400.0 / tpi
        val diameterUm = diameterIn * 25400.0
        return ThreadSize(
            designation = "$size-${trimNumber(tpi, 2)}",
            form = form,
            nominalDiameterUm = diameterUm,
            pitchUm = pitchUm,
            threadsPerInch = tpi,
            lengthOfEngagementUm = engagementFactor * diameterUm,
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

    private val bsw: List<ThreadSize> = listOf(
        Triple("1/8", 0.125, 40.0),
        Triple("3/16", 0.1875, 24.0),
        Triple("1/4", 0.25, 20.0),
        Triple("5/16", 0.3125, 18.0),
        Triple("3/8", 0.375, 16.0),
        Triple("7/16", 0.4375, 14.0),
        Triple("1/2", 0.5, 12.0),
        Triple("9/16", 0.5625, 12.0),
        Triple("5/8", 0.625, 11.0),
        Triple("3/4", 0.75, 10.0),
        Triple("7/8", 0.875, 9.0),
        Triple("1", 1.0, 8.0),
        Triple("1 1/8", 1.125, 7.0),
        Triple("1 1/4", 1.25, 7.0),
        Triple("1 1/2", 1.5, 6.0),
        Triple("1 3/4", 1.75, 5.0),
        Triple("2", 2.0, 4.5),
    ).map { (size, d, tpi) -> inchSeries(ThreadForm.Bsw, size, d, tpi) }

    private val bsf: List<ThreadSize> = listOf(
        Triple("3/16", 0.1875, 32.0),
        Triple("1/4", 0.25, 26.0),
        Triple("5/16", 0.3125, 22.0),
        Triple("3/8", 0.375, 20.0),
        Triple("7/16", 0.4375, 18.0),
        Triple("1/2", 0.5, 16.0),
        Triple("9/16", 0.5625, 16.0),
        Triple("5/8", 0.625, 14.0),
        Triple("3/4", 0.75, 12.0),
        Triple("7/8", 0.875, 11.0),
        Triple("1", 1.0, 10.0),
        Triple("1 1/8", 1.125, 9.0),
        Triple("1 1/4", 1.25, 9.0),
        Triple("1 1/2", 1.5, 8.0),
    ).map { (size, d, tpi) -> inchSeries(ThreadForm.Bsf, size, d, tpi) }

    private val bspp: List<ThreadSize> = listOf(
        Triple("G 1/16", 7723.0, 28.0),
        Triple("G 1/8", 9728.0, 28.0),
        Triple("G 1/4", 13157.0, 19.0),
        Triple("G 3/8", 16662.0, 19.0),
        Triple("G 1/2", 20955.0, 14.0),
        Triple("G 5/8", 22911.0, 14.0),
        Triple("G 3/4", 26441.0, 14.0),
        Triple("G 7/8", 30201.0, 14.0),
        Triple("G 1", 33249.0, 11.0),
        Triple("G 1 1/8", 37897.0, 11.0),
        Triple("G 1 1/4", 41910.0, 11.0),
        Triple("G 1 1/2", 47803.0, 11.0),
        Triple("G 1 3/4", 53746.0, 11.0),
        Triple("G 2", 59614.0, 11.0),
    ).map { (designation, majorUm, tpi) ->
        ThreadSize(
            designation = designation,
            form = ThreadForm.Bspp,
            nominalDiameterUm = majorUm,
            pitchUm = 25400.0 / tpi,
            threadsPerInch = tpi,
        )
    }

    private val isoTrapezoidal: List<ThreadSize> = listOf(
        8.0 to 1.5,
        10.0 to 2.0,
        12.0 to 3.0,
        14.0 to 3.0,
        16.0 to 4.0,
        18.0 to 4.0,
        20.0 to 4.0,
        22.0 to 5.0,
        24.0 to 5.0,
        26.0 to 5.0,
        28.0 to 5.0,
        30.0 to 6.0,
        32.0 to 6.0,
        36.0 to 6.0,
        40.0 to 7.0,
        44.0 to 7.0,
        48.0 to 8.0,
        50.0 to 8.0,
        52.0 to 8.0,
        60.0 to 9.0,
    ).map { (d, p) -> tr(d, p) }

    private val acme: List<ThreadSize> = listOf(
        Triple("1/4", 0.25, 16.0),
        Triple("5/16", 0.3125, 14.0),
        Triple("3/8", 0.375, 12.0),
        Triple("7/16", 0.4375, 12.0),
        Triple("1/2", 0.5, 10.0),
        Triple("5/8", 0.625, 8.0),
        Triple("3/4", 0.75, 6.0),
        Triple("7/8", 0.875, 6.0),
        Triple("1", 1.0, 5.0),
        Triple("1 1/8", 1.125, 5.0),
        Triple("1 1/4", 1.25, 5.0),
        Triple("1 3/8", 1.375, 4.0),
        Triple("1 1/2", 1.5, 4.0),
        Triple("1 3/4", 1.75, 4.0),
        Triple("2", 2.0, 4.0),
    ).map { (size, d, tpi) -> inchSeries(ThreadForm.Acme, size, d, tpi, engagementFactor = 2.0) }

    private fun nptRow(
        designation: String,
        odIn: Double,
        tpi: Double,
        e0In: Double,
        e1In: Double,
        l1In: Double,
        l2In: Double,
    ): ThreadSize = ThreadSize(
        designation = designation,
        form = ThreadForm.Npt,
        nominalDiameterUm = odIn * 25400.0,
        pitchUm = 25400.0 / tpi,
        threadsPerInch = tpi,
        e0PitchDiameterUm = e0In * 25400.0,
        e1PitchDiameterUm = e1In * 25400.0,
        l1LengthUm = l1In * 25400.0,
        l2LengthUm = l2In * 25400.0,
    )

    private val npt: List<ThreadSize> = listOf(
        nptRow("1/16-27", 0.3125, 27.0, 0.27118, 0.28118, 0.160, 0.2611),
        nptRow("1/8-27", 0.405, 27.0, 0.36351, 0.37360, 0.1615, 0.2639),
        nptRow("1/4-18", 0.540, 18.0, 0.47739, 0.49163, 0.2278, 0.4018),
        nptRow("3/8-18", 0.675, 18.0, 0.61201, 0.62701, 0.240, 0.4078),
        nptRow("1/2-14", 0.840, 14.0, 0.75843, 0.77843, 0.320, 0.5337),
        nptRow("3/4-14", 1.050, 14.0, 0.96768, 0.98887, 0.339, 0.5457),
        nptRow("1-11.5", 1.315, 11.5, 1.21363, 1.23863, 0.400, 0.6828),
        nptRow("1 1/4-11.5", 1.660, 11.5, 1.55713, 1.58338, 0.420, 0.7068),
        nptRow("1 1/2-11.5", 1.900, 11.5, 1.79609, 1.82234, 0.420, 0.7235),
        nptRow("2-11.5", 2.375, 11.5, 2.26902, 2.29627, 0.436, 0.7565),
    )

    private fun bsptRow(
        designation: String,
        majorUm: Double,
        tpi: Double,
        e1Um: Double,
        l1Um: Double,
        l2Um: Double,
    ): ThreadSize = ThreadSize(
        designation = designation,
        form = ThreadForm.Bspt,
        nominalDiameterUm = majorUm,
        pitchUm = 25400.0 / tpi,
        threadsPerInch = tpi,
        e0PitchDiameterUm = e1Um - 0.0625 * l1Um,
        e1PitchDiameterUm = e1Um,
        l1LengthUm = l1Um,
        l2LengthUm = l2Um,
    )

    private val bspt: List<ThreadSize> = listOf(
        bsptRow("R 1/16-28", 7723.0, 28.0, 7142.0, 4000.0, 6500.0),
        bsptRow("R 1/8-28", 9728.0, 28.0, 9147.0, 4000.0, 6500.0),
        bsptRow("R 1/4-19", 13157.0, 19.0, 12301.0, 6000.0, 9700.0),
        bsptRow("R 3/8-19", 16662.0, 19.0, 15806.0, 6400.0, 10100.0),
        bsptRow("R 1/2-14", 20955.0, 14.0, 19793.0, 8200.0, 13200.0),
        bsptRow("R 3/4-14", 26441.0, 14.0, 25279.0, 9500.0, 14500.0),
        bsptRow("R 1-11", 33249.0, 11.0, 31770.0, 10400.0, 16800.0),
        bsptRow("R 1 1/4-11", 41910.0, 11.0, 40431.0, 12700.0, 19100.0),
        bsptRow("R 1 1/2-11", 47803.0, 11.0, 46324.0, 12700.0, 19100.0),
        bsptRow("R 2-11", 59614.0, 11.0, 58135.0, 15900.0, 23400.0),
    )
}
