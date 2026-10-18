package org.superdro.core.functions.thread

import kotlin.math.max
import kotlin.math.min
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
 * The three-wire values of an external thread, in µm.
 *
 * @property wireDiameterUm The wire diameter `W` used for the calculation.
 * @property measurementUm `M` for the target pitch diameter.
 * @property measurementMinUm `M` for the smallest permitted pitch diameter, or null with no class.
 * @property measurementMaxUm `M` for the largest permitted pitch diameter, or null with no class.
 * @property actualPitchDiameterUm The actual pitch diameter derived from a measured micrometer
 *   reading over three wires, or null.
 * @property remainingRadialDepthUm The remaining radial depth to reach [measurementUm], or null.
 * @property wireValid True when [wireDiameterUm] is inside the valid wire range for the pitch.
 */
data class ThreeWireValues(
    val wireDiameterUm: Double,
    val measurementUm: Double,
    val measurementMinUm: Double?,
    val measurementMaxUm: Double?,
    val actualPitchDiameterUm: Double? = null,
    val remainingRadialDepthUm: Double? = null,
    val wireValid: Boolean = true,
)

/**
 * The diameters and tooling geometry of a thread. All lengths are µm and all angles are arc
 * seconds.
 *
 * @property nominalDiameterUm The basic major diameter D.
 * @property heightUm The height of the fundamental triangle `H`.
 * @property basicPitchDiameterUm The basic pitch diameter `d2`.
 * @property basicMinorDiameterUm The basic minor diameter `D1`.
 * @property externalRootDiameterUm The basic external root diameter `d3`.
 * @property unrMinorDiameterUm The rounded external root diameter.
 * @property classStatus Null when the class values exist or when no class is selected. Else the
 *   reason that the diameters use the basic profile.
 * @property targetPitchDiameterUm `dp`: `D2min` (internal) or `d2max` (external); the basic d2
 *   with no class values.
 * @property preOp The pre-op diameter: "Bore to (D₁)" (internal) or "Turn to (D)" (external).
 * @property rootRadiusUm The root radius `r` of the cut-to formula.
 * @property cutToDiameterUm "Cut to (D)" (internal) or "Cut to (D₁)" (external).
 * @property threeWire The three-wire values, or null for an internal thread.
 * @property starts The number of thread starts (`1` or more).
 * @property leftHand True for a left-hand thread, false for a right-hand thread.
 * @property leadUm The axial thread lead (`starts × pitchUm`).
 * @property leadAngleArcSec The helix lead angle `λ` in arc seconds.
 * @property needsAnvilTilt True when [leadAngleArcSec] exceeds `9000` arc seconds (`2.5°`).
 * @property reliefDiameterUm The relief groove diameter (`dg` external, `Dg` internal).
 * @property entryChamferDiameterUm The entry chamfer (`minor − 200 µm` external) or countersink
 *   (`major + 300 µm` internal) diameter.
 * @property rootFlatWidthUm The cutter tip flat width `w` at the root.
 * @property internalGaugeLimits The internal Go/No-Go minor pin-gauge and pitch plug-gauge limits,
 *   or null.
 * @property internalBarRecommendation The internal threading bar holder recommendation, or null for
 *   an external thread.
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
    val starts: Int = 1,
    val leftHand: Boolean = false,
    val leadUm: Double = pitchUm * starts,
    val leadAngleArcSec: Double = ThreadSyncRules.leadAngleArcSec(leadUm, targetPitchDiameterUm),
    val needsAnvilTilt: Boolean = ThreadSyncRules.needsAnvilTilt(leadAngleArcSec),
    val reliefDiameterUm: Double = if (internal) {
        cutToDiameterUm + 2.0 * min(200.0, 0.15 * pitchUm)
    } else {
        cutToDiameterUm - 2.0 * min(200.0, 0.15 * pitchUm)
    },
    val entryChamferDiameterUm: Double = if (internal) {
        cutToDiameterUm + 300.0
    } else {
        cutToDiameterUm - 200.0
    },
    val rootFlatWidthUm: Double = 0.0,
    val internalGaugeLimits: InternalGaugeLimits? = if (internal) {
        ThreadInspection.internalGaugeLimits(limits)
    } else {
        null
    },
    val internalBarRecommendation: InternalBarRecommendation? = if (internal) {
        ThreadSyncRules.internalBarRecommendation(preOp.valueUm, null)
    } else {
        null
    },
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

    /** `H = 0.8660254 × P` for a 60° thread. */
    const val HEIGHT_FACTOR: Double = 0.8660254

    /** `H = 0.9604911 × P` for a 55° Whitworth or BSPP thread. */
    const val WHITWORTH_HEIGHT_FACTOR: Double = 0.9604911

    /** `h3 = 0.6403274 × P` for a 55° Whitworth or BSPP thread. */
    const val WHITWORTH_DEPTH_FACTOR: Double = 0.6403274

    /** `H1 = 1.8660254 × P` for a 30° ISO trapezoidal thread. */
    const val TRAPEZOIDAL_HEIGHT_FACTOR: Double = 1.8660254

    /** `H = 1.9333565 × P` for a 29° Acme thread. */
    const val ACME_HEIGHT_FACTOR: Double = 1.9333565

    /** `h = 0.800 × P` for a 60° NPT tapered pipe thread. */
    const val NPT_TRUNCATION_FACTOR: Double = 0.800

    /** `W = 0.5773503 × P`, the best wire diameter for a 60° thread. */
    const val BEST_WIRE_FACTOR: Double = 0.5773503

    /**
     * Gives the radial crest and root clearance `ac` in µm for an ISO metric trapezoidal thread.
     */
    fun trapezoidalClearanceUm(pitchUm: Double): Double = when {
        pitchUm <= 1500.0 -> 150.0
        pitchUm <= 5000.0 -> 250.0
        else -> 500.0
    }

    /**
     * Gives the radial crest and root clearance `c` in µm for an Acme thread (`254 µm` for 10 TPI
     * or coarser, `127 µm` for finer than 10 TPI).
     */
    fun acmeClearanceUm(pitchUm: Double): Double =
        if (pitchUm >= 2540.0 - 1e-6) 254.0 else 127.0

    /**
     * Gives the cutter tip flat width `w` at the root in µm (`Fr` for Acme, `f` for
     * IsoTrapezoidal, `P / 4` for FullForm on 60°/55°, and `0` otherwise).
     */
    fun rootFlatWidthUm(form: ThreadForm, cutter: ThreadCutter, pitchUm: Double): Double = when (form) {
        ThreadForm.Acme -> {
            val c = acmeClearanceUm(pitchUm)
            0.3706912 * pitchUm - 0.5172352 * c
        }
        ThreadForm.IsoTrapezoidal -> {
            val ac = trapezoidalClearanceUm(pitchUm)
            0.3660254 * pitchUm - 0.5358984 * ac
        }
        else -> if (cutter == ThreadCutter.FullForm) pitchUm / 4.0 else 0.0
    }

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
        starts: Int = 1,
        leftHand: Boolean = false,
        customWireDiameterUm: Double? = null,
        measuredOverWiresUm: Double? = null,
    ): ThreadGeometry {
        val validStarts = starts.coerceAtLeast(1)
        val d = size.nominalDiameterUm
        val p = size.pitchUm
        val form = size.form
        val (tolerances, status) = classValues(size, internal, threadClass)

        val h: Double
        val d2: Double
        val d1: Double
        val d3: Double
        val unrMinor: Double
        when (form) {
            ThreadForm.Bsw, ThreadForm.Bsf, ThreadForm.Bspp -> {
                h = WHITWORTH_HEIGHT_FACTOR * p
                val h3 = WHITWORTH_DEPTH_FACTOR * p
                d2 = d - h3
                d1 = d - 2.0 * h3
                d3 = d1
                unrMinor = d1
            }
            ThreadForm.IsoTrapezoidal -> {
                h = TRAPEZOIDAL_HEIGHT_FACTOR * p
                val ac = trapezoidalClearanceUm(p)
                d2 = d - 0.5 * p
                d1 = d - p
                d3 = d - (p + 2.0 * ac)
                unrMinor = d3
            }
            ThreadForm.Acme -> {
                h = ACME_HEIGHT_FACTOR * p
                val c = acmeClearanceUm(p)
                d2 = d - 0.5 * p
                d1 = d - p
                d3 = d - p - 2.0 * c
                unrMinor = d3
            }
            ThreadForm.Npt -> {
                h = HEIGHT_FACTOR * p
                val hTrunc = NPT_TRUNCATION_FACTOR * p
                d2 = size.e1PitchDiameterUm ?: (d - hTrunc)
                d1 = d2 - hTrunc
                d3 = d1
                unrMinor = d1
            }
            ThreadForm.Bspt -> {
                h = WHITWORTH_HEIGHT_FACTOR * p
                val h3 = WHITWORTH_DEPTH_FACTOR * p
                d2 = size.e1PitchDiameterUm ?: (d - h3)
                d1 = d2 - h3
                d3 = d1
                unrMinor = d1
            }
            else -> {
                h = HEIGHT_FACTOR * p
                d2 = d - 0.75 * h
                d1 = d - 1.25 * h
                d3 = d - 1.75 * h + 2.0 * (h / 6.0)
                unrMinor = d1 - h / 8.0
            }
        }

        val limits = tolerances?.let { t ->
            if (internal) {
                val pitchMin = d2 + t.deviationUm
                val crestMin = d1 + t.deviationUm
                ClassLimits(
                    pitchMinUm = pitchMin,
                    pitchMaxUm = pitchMin + t.pitchDiameterToleranceUm,
                    crestMinUm = crestMin,
                    crestMaxUm = crestMin + t.crestDiameterToleranceUm,
                )
            } else {
                val pitchMax = d2 + t.deviationUm
                val crestMax = d + t.deviationUm
                ClassLimits(
                    pitchMinUm = pitchMax - t.pitchDiameterToleranceUm,
                    pitchMaxUm = pitchMax,
                    crestMinUm = crestMax - t.crestDiameterToleranceUm,
                    crestMaxUm = crestMax,
                )
            }
        }
        val dp = when {
            limits == null -> d2
            internal -> limits.pitchMinUm
            else -> limits.pitchMaxUm
        }
        val preOp = when {
            form.isTapered -> if (internal) DiameterTarget(d1, d1, d1) else DiameterTarget(d, d, d)
            form == ThreadForm.IsoTrapezoidal || form == ThreadForm.Acme -> {
                if (internal) {
                    if (limits != null) DiameterTarget(limits.crestMinUm, limits.crestMinUm, limits.crestMaxUm) else DiameterTarget(d1, d1, d1)
                } else {
                    if (limits != null) DiameterTarget(limits.crestMaxUm, limits.crestMinUm, limits.crestMaxUm) else DiameterTarget(d, d, d)
                }
            }
            cutter == ThreadCutter.FullForm && internal -> DiameterTarget(d1, d1 - 0.5 * h, d1)
            cutter == ThreadCutter.FullForm -> DiameterTarget(d, d, d + 0.25 * h)
            internal -> if (limits != null) DiameterTarget(limits.crestMinUm, limits.crestMinUm, limits.crestMaxUm) else DiameterTarget(d1, d1, d1)
            else -> if (limits != null) DiameterTarget(limits.crestMaxUm, limits.crestMinUm, limits.crestMaxUm) else DiameterTarget(d, d, d)
        }

        val r: Double
        val cutTo: Double
        when (form) {
            ThreadForm.Bsw, ThreadForm.Bsf, ThreadForm.Bspp -> {
                val fullForm = h / 6.0
                r = when (cutter) {
                    ThreadCutter.SharpV -> 0.0
                    ThreadCutter.FullForm -> fullForm
                    ThreadCutter.Partial -> cutterTipRadiusUm?.takeIf { it.isFinite() }?.coerceIn(0.0, fullForm) ?: fullForm
                }
                cutTo = if (internal) dp + h - 2.0 * r else dp - h + 2.0 * r
            }
            ThreadForm.IsoTrapezoidal -> {
                val ac = trapezoidalClearanceUm(p)
                r = 0.0
                cutTo = if (internal) dp + (0.5 * p + 2.0 * ac) else dp - (0.5 * p + 2.0 * ac)
            }
            ThreadForm.Acme -> {
                val c = acmeClearanceUm(p)
                r = 0.0
                cutTo = if (internal) dp + (0.5 * p + 2.0 * c) else dp - (0.5 * p + 2.0 * c)
            }
            ThreadForm.Npt -> {
                val hTrunc = NPT_TRUNCATION_FACTOR * p
                r = 0.0
                cutTo = if (internal) dp + hTrunc else dp - hTrunc
            }
            ThreadForm.Bspt -> {
                val h3 = WHITWORTH_DEPTH_FACTOR * p
                r = 0.0
                cutTo = if (internal) dp + h3 else dp - h3
            }
            else -> {
                r = rootRadius(cutter, internal, h, cutterTipRadiusUm)
                cutTo = if (internal) dp + h - 2.0 * r else dp - h + 2.0 * r
            }
        }

        val minorResultUm = if (internal) preOp.valueUm else cutTo
        val majorResultUm = if (internal) cutTo else preOp.valueUm
        val reliefStep = 2.0 * min(200.0, 0.15 * p)
        val reliefDiameterUm = if (internal) cutTo + reliefStep else cutTo - reliefStep
        val entryChamferDiameterUm = if (internal) majorResultUm + 300.0 else minorResultUm - 200.0
        val rootFlat = rootFlatWidthUm(form, cutter, p)
        val leadUm = p * validStarts
        val leadAngleArcSec = ThreadSyncRules.leadAngleArcSec(leadUm, dp)

        return ThreadGeometry(
            form = form,
            internal = internal,
            cutter = cutter,
            pitchUm = p,
            nominalDiameterUm = d,
            heightUm = h,
            basicPitchDiameterUm = d2,
            basicMinorDiameterUm = d1,
            externalRootDiameterUm = d3,
            unrMinorDiameterUm = unrMinor,
            threadClass = threadClass,
            tolerances = tolerances,
            classStatus = status,
            limits = limits,
            targetPitchDiameterUm = dp,
            preOp = preOp,
            rootRadiusUm = r,
            cutToDiameterUm = cutTo,
            threeWire = threeWire(
                form = form,
                internal = internal,
                pitchUm = p,
                starts = validStarts,
                dp = dp,
                limits = limits,
                customWireDiameterUm = customWireDiameterUm,
                measuredOverWiresUm = measuredOverWiresUm,
            ),
            starts = validStarts,
            leftHand = leftHand,
            leadUm = leadUm,
            leadAngleArcSec = leadAngleArcSec,
            needsAnvilTilt = ThreadSyncRules.needsAnvilTilt(leadAngleArcSec),
            reliefDiameterUm = reliefDiameterUm,
            entryChamferDiameterUm = entryChamferDiameterUm,
            rootFlatWidthUm = rootFlat,
            internalGaugeLimits = if (internal) ThreadInspection.internalGaugeLimits(limits) else null,
            internalBarRecommendation = if (internal) ThreadSyncRules.internalBarRecommendation(minorResultUm, null) else null,
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
        starts: Int = 1,
        leftHand: Boolean = false,
        customWireDiameterUm: Double? = null,
        measuredOverWiresUm: Double? = null,
    ): ThreadGeometry {
        val validStarts = starts.coerceAtLeast(1)
        val h = HEIGHT_FACTOR * pitchUm
        val d2 = majorDiameterUm - 0.75 * h
        val d1 = majorDiameterUm - 1.25 * h
        val preOpValue = if (internal) minorDiameterUm else majorDiameterUm
        val cutTo = if (internal) majorDiameterUm else minorDiameterUm
        val reliefStep = 2.0 * min(200.0, 0.15 * pitchUm)
        val reliefDiameterUm = if (internal) cutTo + reliefStep else cutTo - reliefStep
        val entryChamferDiameterUm = if (internal) majorDiameterUm + 300.0 else minorDiameterUm - 200.0
        val rootFlat = rootFlatWidthUm(form, cutter, pitchUm)
        val leadUm = pitchUm * validStarts
        val leadAngleArcSec = ThreadSyncRules.leadAngleArcSec(leadUm, d2)

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
            cutToDiameterUm = cutTo,
            threeWire = threeWire(
                form = form,
                internal = internal,
                pitchUm = pitchUm,
                starts = validStarts,
                dp = d2,
                limits = null,
                customWireDiameterUm = customWireDiameterUm,
                measuredOverWiresUm = measuredOverWiresUm,
            ),
            starts = validStarts,
            leftHand = leftHand,
            leadUm = leadUm,
            leadAngleArcSec = leadAngleArcSec,
            needsAnvilTilt = ThreadSyncRules.needsAnvilTilt(leadAngleArcSec),
            reliefDiameterUm = reliefDiameterUm,
            entryChamferDiameterUm = entryChamferDiameterUm,
            rootFlatWidthUm = rootFlat,
            internalGaugeLimits = null,
            internalBarRecommendation = if (internal) ThreadSyncRules.internalBarRecommendation(minorDiameterUm, null) else null,
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
        if (size.form == ThreadForm.Bspp && internal && threadClass == null) {
            val bsppInternal = Iso228.internal(size.pitchUm)
            return if (bsppInternal == null) null to ClassStatus.OutsideTable else bsppInternal to null
        }
        if (threadClass == null) return null to null
        val typeMatches = if (internal) threadClass.isInternal else threadClass.isExternal
        if (!typeMatches) return null to ClassStatus.WrongType
        val unitMatches = threadClass.unit == size.form.unit
        if (!unitMatches || size.form.isCustom) return null to ClassStatus.WrongUnit
        val values = when (threadClass) {
            ThreadClass.Class6g -> if (size.form.isIso) Iso965.class6g(size.nominalDiameterUm, size.pitchUm) else null
            ThreadClass.Class6H -> if (size.form.isIso) Iso965.class6H(size.nominalDiameterUm, size.pitchUm) else null
            ThreadClass.Class2A, ThreadClass.Class3A -> if (size.form.isUnified) {
                AsmeB11.external(
                    class3A = threadClass == ThreadClass.Class3A,
                    majorUm = size.nominalDiameterUm,
                    pitchUm = size.pitchUm,
                    engagementUm = size.lengthOfEngagementUm ?: size.nominalDiameterUm,
                )
            } else {
                null
            }
            ThreadClass.Class2B, ThreadClass.Class3B -> if (size.form.isUnified) {
                AsmeB11.internal(
                    class3B = threadClass == ThreadClass.Class3B,
                    majorUm = size.nominalDiameterUm,
                    pitchUm = size.pitchUm,
                    engagementUm = size.lengthOfEngagementUm ?: size.nominalDiameterUm,
                )
            } else {
                null
            }
            ThreadClass.ClassA, ThreadClass.ClassB -> if (size.form == ThreadForm.Bspp && !internal) {
                Iso228.external(
                    classB = threadClass == ThreadClass.ClassB,
                    pitchUm = size.pitchUm,
                )
            } else {
                null
            }
            ThreadClass.ClassMedium -> if (size.form == ThreadForm.Bsw || size.form == ThreadForm.Bsf) {
                Bs84.medium(
                    internal = internal,
                    majorUm = size.nominalDiameterUm,
                    pitchUm = size.pitchUm,
                    engagementUm = size.lengthOfEngagementUm ?: size.nominalDiameterUm,
                )
            } else {
                null
            }
            ThreadClass.Class7e -> if (size.form == ThreadForm.IsoTrapezoidal && !internal) {
                Iso2903.class7e(size.nominalDiameterUm, size.pitchUm)
            } else {
                null
            }
            ThreadClass.Class7H -> if (size.form == ThreadForm.IsoTrapezoidal && internal) {
                Iso2903.class7H(size.nominalDiameterUm, size.pitchUm)
            } else {
                null
            }
            ThreadClass.Class2G, ThreadClass.Class3G -> if (size.form == ThreadForm.Acme) {
                AsmeB15.generalPurpose(
                    class3G = threadClass == ThreadClass.Class3G,
                    internal = internal,
                    majorUm = size.nominalDiameterUm,
                    pitchUm = size.pitchUm,
                )
            } else {
                null
            }
        }
        return if (values == null) null to ClassStatus.OutsideTable else values to null
    }

    private fun threeWire(
        form: ThreadForm,
        internal: Boolean,
        pitchUm: Double,
        starts: Int,
        dp: Double,
        limits: ClassLimits?,
        customWireDiameterUm: Double?,
        measuredOverWiresUm: Double?,
    ): ThreeWireValues? {
        if (internal) return null
        val validCustomWire = customWireDiameterUm?.takeIf { it.isFinite() && it > 0.0 }
        val validMeasured = measuredOverWiresUm?.takeIf { it.isFinite() && it > 0.0 }
        if (starts == 1 && validCustomWire == null && !form.isTapered && form.includedAngleDeg == 60.0) {
            val wire = BEST_WIRE_FACTOR * pitchUm
            val cWire = 3.0 * wire - HEIGHT_FACTOR * pitchUm
            val measurementUm = dp + cWire
            return ThreeWireValues(
                wireDiameterUm = wire,
                measurementUm = measurementUm,
                measurementMinUm = limits?.let { it.pitchMinUm + cWire },
                measurementMaxUm = limits?.let { it.pitchMaxUm + cWire },
                actualPitchDiameterUm = validMeasured?.let { it - cWire },
                remainingRadialDepthUm = validMeasured?.let { max(0.0, (it - measurementUm) / 2.0) },
                wireValid = true,
            )
        }
        return ThreadInspection.threeWire(
            pitchUm = pitchUm,
            includedAngleDeg = form.includedAngleDeg,
            starts = starts,
            targetPitchDiameterUm = dp,
            limits = limits,
            customWireDiameterUm = validCustomWire,
            measuredOverWiresUm = validMeasured,
            taperRatio = form.taperRatio,
        )
    }
}
