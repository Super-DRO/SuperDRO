package org.superdro.core.functions.thread

import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm

/**
 * The class limits of a thread, in µm.
 *
 * External thread (es ≤ 0): `d2max = d2 + es`, `d2min = d2max − Td2`, `dmax = D + es`,
 * `dmin = dmax − Td`. Internal thread: `D2min = d2 + EI`, `D2max = D2min + TD2`, `D1min = D1 + EI`,
 * `D1max = D1min + TD1`.
 *
 * @property pitchMinUm `d2min` or `D2min`.
 * @property pitchMaxUm `d2max` or `D2max`.
 * @property crestMinUm `dmin` (external major diameter) or `D1min` (internal minor diameter).
 * @property crestMaxUm `dmax` or `D1max`.
 */
data class ClassLimits(
    val pitchMinUm: Double,
    val pitchMaxUm: Double,
    val crestMinUm: Double,
    val crestMaxUm: Double,
)

/** A target diameter and its permitted range, in µm. */
data class DiameterTarget(val valueUm: Double, val minUm: Double, val maxUm: Double)

/**
 * The three-wire values of an external thread, in µm (SuperDRO shows them). The best wire
 * diameter is `W = P / (2·cos 30°) = 0.5773503·P`. The measurement over three wires of the
 * diameter W is `M = E + 3·W − 0.8660254·P`, with E the pitch diameter. The formula has no
 * correction for the lead angle.
 *
 * @property measurementUm M for the target pitch diameter.
 * @property measurementMinUm M for the smallest permitted pitch diameter, or null with no class.
 * @property measurementMaxUm M for the largest permitted pitch diameter, or null with no class.
 */
data class ThreeWireValues(
    val wireDiameterUm: Double,
    val measurementUm: Double,
    val measurementMinUm: Double?,
    val measurementMaxUm: Double?,
)

/**
 * The diameters of a thread. All lengths are µm.
 *
 * @property nominalDiameterUm The basic major diameter D.
 * @property heightUm The height of the fundamental triangle `H = 0.8660254 × P`.
 * @property basicPitchDiameterUm `d2 = D − 0.75 × H`.
 * @property basicMinorDiameterUm `D1 = D − 1.25 × H`.
 * @property externalRootDiameterUm `d3 = D − 1.75 × H + 2 × (H / 6)`.
 * @property unrMinorDiameterUm `D1 − H / 8`.
 * @property classStatus Null when the class values exist or when no class is selected. Else the
 *   reason that the diameters use the basic profile.
 * @property targetPitchDiameterUm `dp`: `D2min` (internal) or `d2max` (external); the basic d2
 *   with no class values.
 * @property preOp The pre-op diameter: "Bore to (D₁)" (internal) or "Turn to (D)" (external).
 * @property rootRadiusUm The root radius `r` of the cut-to formula.
 * @property cutToDiameterUm "Cut to (D)" (internal: `dp + H − 2r`) or "Cut to (D₁)" (external:
 *   `dp − H + 2r`).
 * @property threeWire The three-wire values, or null for an internal thread.
 */
data class ThreadGeometry(
    val form: ThreadForm,
    val internal: Boolean,
    val cutter: ThreadCutter,
    val pitchUm: Double,
    val nominalDiameterUm: Double,
    val heightUm: Double,
    val basicPitchDiameterUm: Double,
    val basicMinorDiameterUm: Double,
    val externalRootDiameterUm: Double,
    val unrMinorDiameterUm: Double,
    val threadClass: ThreadClass?,
    val tolerances: ClassTolerances?,
    val classStatus: ClassStatus?,
    val limits: ClassLimits?,
    val targetPitchDiameterUm: Double,
    val preOp: DiameterTarget,
    val rootRadiusUm: Double,
    val cutToDiameterUm: Double,
    val threeWire: ThreeWireValues?,
) {
    /** The unit of the series. */
    val unit: ThreadUnit get() = form.unit

    /** The crest diameter: the pre-op diameter. */
    val crestDiameterUm: Double get() = preOp.valueUm

    /** The root diameter: the cut-to diameter. */
    val rootDiameterUm: Double get() = cutToDiameterUm

    /** The minor diameter of the result: the pre-op (internal) or the cut-to (external) diameter. */
    val minorDiameterUm: Double get() = if (internal) preOp.valueUm else cutToDiameterUm

    /** The major diameter of the result: the cut-to (internal) or the pre-op (external) diameter. */
    val majorDiameterUm: Double get() = if (internal) cutToDiameterUm else preOp.valueUm

    /** True when the dialog must show the text "No tolerance is tabulated for this size …". */
    val showsNoToleranceNotice: Boolean get() = classStatus != null
}

/**
 * The diameter math of the thread setup.
 *
 * Root radius `r` of the cut-to formula for each cutter:
 *
 * - Sharp V: `r = 0`. The tip of the cutter is the apex of the V.
 * - Full Form: the root radius of the profile, `H / 8` (internal) or `H / 6` (external). The
 *   cutter also forms the crest, so the pre-op diameter is the basic diameter.
 * - Partial: the cutter does not form the crest, so the pre-op diameter is the class limit, as
 *   for Sharp V. The tip of a partial cutter has a radius for the smallest pitch of its range.
 *   SuperDRO does not use the Sharp V depth for it: that depth puts the flanks too deep by
 *   `2 × r` and makes the pitch diameter wrong. SuperDRO uses the tip radius of the cutter, when
 *   it is known, limited to 0 to the Full Form radius. With no known tip radius, SuperDRO uses
 *   the Full Form radius. The cut-to diameter is then never too deep: the pitch diameter is at
 *   the target or not yet at it, and the three-wire values show the remaining depth.
 */
object ThreadCalculator {

    /** `H = 0.8660254 × P`. */
    const val HEIGHT_FACTOR: Double = 0.8660254

    /** `W = 0.5773503 × P`, the best wire diameter for a 60° thread. */
    const val BEST_WIRE_FACTOR: Double = 0.5773503

    /**
     * Calculates a standard thread from a table row.
     *
     * @param threadClass The selected class, or null for no class.
     * @param cutterTipRadiusUm The tip radius of a Partial cutter, or null when it is not known.
     */
    fun standard(
        size: ThreadSize,
        internal: Boolean,
        cutter: ThreadCutter,
        threadClass: ThreadClass?,
        cutterTipRadiusUm: Double? = null,
    ): ThreadGeometry {
        val d = size.nominalDiameterUm
        val p = size.pitchUm
        val h = HEIGHT_FACTOR * p
        val d2 = d - 0.75 * h
        val d1 = d - 1.25 * h
        val (tolerances, status) = classValues(size, internal, threadClass)
        val limits = tolerances?.let { t ->
            if (internal) {
                val pitchMin = d2 + t.deviationUm
                val crestMin = d1 + t.deviationUm
                ClassLimits(pitchMin, pitchMin + t.pitchDiameterToleranceUm, crestMin, crestMin + t.crestDiameterToleranceUm)
            } else {
                val pitchMax = d2 + t.deviationUm
                val crestMax = d + t.deviationUm
                ClassLimits(pitchMax - t.pitchDiameterToleranceUm, pitchMax, crestMax - t.crestDiameterToleranceUm, crestMax)
            }
        }
        val dp = when {
            limits == null -> d2
            internal -> limits.pitchMinUm
            else -> limits.pitchMaxUm
        }
        val preOp = when {
            cutter == ThreadCutter.FullForm && internal -> DiameterTarget(d1, d1 - 0.5 * h, d1)
            cutter == ThreadCutter.FullForm -> DiameterTarget(d, d, d + 0.25 * h)
            internal -> if (limits != null) DiameterTarget(limits.crestMinUm, limits.crestMinUm, limits.crestMaxUm) else DiameterTarget(d1, d1, d1)
            else -> if (limits != null) DiameterTarget(limits.crestMaxUm, limits.crestMinUm, limits.crestMaxUm) else DiameterTarget(d, d, d)
        }
        val r = rootRadius(cutter, internal, h, cutterTipRadiusUm)
        val cutTo = if (internal) dp + h - 2.0 * r else dp - h + 2.0 * r
        return ThreadGeometry(
            form = size.form,
            internal = internal,
            cutter = cutter,
            pitchUm = p,
            nominalDiameterUm = d,
            heightUm = h,
            basicPitchDiameterUm = d2,
            basicMinorDiameterUm = d1,
            externalRootDiameterUm = d - 1.75 * h + 2.0 * (h / 6.0),
            unrMinorDiameterUm = d1 - h / 8.0,
            threadClass = threadClass,
            tolerances = tolerances,
            classStatus = status,
            limits = limits,
            targetPitchDiameterUm = dp,
            preOp = preOp,
            rootRadiusUm = r,
            cutToDiameterUm = cutTo,
            threeWire = threeWire(internal, p, dp, limits),
        )
    }

    /**
     * Calculates a custom thread. The typed diameters are the result:
     * pre-op = `internal ? D₁ : D`, cut-to = `internal ? D : D₁`. There is no class and no cutter
     * math. The target pitch diameter for the three-wire values is the basic `d2`.
     */
    fun custom(
        form: ThreadForm,
        internal: Boolean,
        cutter: ThreadCutter,
        majorDiameterUm: Double,
        minorDiameterUm: Double,
        pitchUm: Double,
    ): ThreadGeometry {
        val h = HEIGHT_FACTOR * pitchUm
        val d2 = majorDiameterUm - 0.75 * h
        val d1 = majorDiameterUm - 1.25 * h
        val preOpValue = if (internal) minorDiameterUm else majorDiameterUm
        return ThreadGeometry(
            form = form,
            internal = internal,
            cutter = cutter,
            pitchUm = pitchUm,
            nominalDiameterUm = majorDiameterUm,
            heightUm = h,
            basicPitchDiameterUm = d2,
            basicMinorDiameterUm = d1,
            externalRootDiameterUm = majorDiameterUm - 1.75 * h + 2.0 * (h / 6.0),
            unrMinorDiameterUm = d1 - h / 8.0,
            threadClass = null,
            tolerances = null,
            classStatus = null,
            limits = null,
            targetPitchDiameterUm = d2,
            preOp = DiameterTarget(preOpValue, preOpValue, preOpValue),
            rootRadiusUm = 0.0,
            cutToDiameterUm = if (internal) majorDiameterUm else minorDiameterUm,
            threeWire = threeWire(internal, pitchUm, d2, null),
        )
    }

    /**
     * Gives the root radius `r` of the cut-to formula for the cutter (see the rule of this object).
     *
     * @param heightUm H.
     * @param cutterTipRadiusUm The tip radius of a Partial cutter, or null.
     */
    fun rootRadius(cutter: ThreadCutter, internal: Boolean, heightUm: Double, cutterTipRadiusUm: Double?): Double {
        val fullForm = if (internal) heightUm / 8.0 else heightUm / 6.0
        return when (cutter) {
            ThreadCutter.SharpV -> 0.0
            ThreadCutter.FullForm -> fullForm
            ThreadCutter.Partial -> cutterTipRadiusUm?.takeIf { radius -> radius.isFinite() }?.coerceIn(0.0, fullForm) ?: fullForm
        }
    }

    /**
     * Gives the class values and the class status. The status is null when the class values exist
     * or when no class is selected.
     */
    private fun classValues(size: ThreadSize, internal: Boolean, threadClass: ThreadClass?): Pair<ClassTolerances?, ClassStatus?> {
        if (threadClass == null) return null to null
        if (threadClass.isExternal == internal) return null to ClassStatus.WrongType
        val unitMatches = if (threadClass.isIso) size.form.unit == ThreadUnit.Millimeter else size.form.unit == ThreadUnit.Inch
        if (!unitMatches || size.form.isCustom) return null to ClassStatus.WrongUnit
        val values = when (threadClass) {
            ThreadClass.Class6g -> Iso965.class6g(size.nominalDiameterUm, size.pitchUm)
            ThreadClass.Class6H -> Iso965.class6H(size.nominalDiameterUm, size.pitchUm)
            ThreadClass.Class2A, ThreadClass.Class3A -> AsmeB11.external(
                class3A = threadClass == ThreadClass.Class3A,
                majorUm = size.nominalDiameterUm,
                pitchUm = size.pitchUm,
                engagementUm = size.lengthOfEngagementUm ?: size.nominalDiameterUm,
            )
            ThreadClass.Class2B, ThreadClass.Class3B -> AsmeB11.internal(
                class3B = threadClass == ThreadClass.Class3B,
                majorUm = size.nominalDiameterUm,
                pitchUm = size.pitchUm,
                engagementUm = size.lengthOfEngagementUm ?: size.nominalDiameterUm,
            )
        }
        return if (values == null) null to ClassStatus.OutsideTable else values to null
    }

    private fun threeWire(internal: Boolean, pitchUm: Double, dp: Double, limits: ClassLimits?): ThreeWireValues? {
        if (internal) return null
        val wire = BEST_WIRE_FACTOR * pitchUm
        fun over(pitchDiameter: Double): Double = pitchDiameter + 3.0 * wire - HEIGHT_FACTOR * pitchUm
        return ThreeWireValues(
            wireDiameterUm = wire,
            measurementUm = over(dp),
            measurementMinUm = limits?.let { over(it.pitchMinUm) },
            measurementMaxUm = limits?.let { over(it.pitchMaxUm) },
        )
    }
}
