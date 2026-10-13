package org.superdro.core.functions.thread

import kotlin.time.Clock
import kotlin.time.Instant
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.model.ThreadSpec

/**
 * The inputs of the thread setup. All lengths are µm.
 * The UI converts the typed values from the thread unit. An empty field is null. A value that the
 * evaluator cannot read is `Double.NaN`.
 *
 * @property size The designation of the table row (standard forms only).
 * @property threadClass The selected class, or null for no class.
 * @property customPitchUm The pitch of a custom form. For Custom Imperial the UI gives `25400 / TPI`.
 * @property customMajorDiameterUm D of a custom form.
 * @property customMinorDiameterUm D₁ of a custom form.
 * @property cutterTipRadiusUm The tip radius of a Partial cutter, or null when it is not known.
 * @property startZUm "Start (Z)", the face or the mouth of the thread.
 * @property lengthUm "Thread Length (L)", from the start toward −Z.
 * @property leftReliefUm W₁ (at the shoulder or the bottom). Null or 0 means no relief.
 * @property rightReliefUm W₂ (at the face or the mouth). Null or 0 means no relief.
 */
data class ThreadSetupInput(
    val form: ThreadForm = ThreadForm.IsoMetricCoarse,
    val internal: Boolean = true,
    val size: String? = null,
    val threadClass: ThreadClass? = null,
    val cutter: ThreadCutter = ThreadCutter.SharpV,
    val customPitchUm: Double? = null,
    val customMajorDiameterUm: Double? = null,
    val customMinorDiameterUm: Double? = null,
    val cutterTipRadiusUm: Double? = null,
    val startZUm: Double? = 0.0,
    val lengthUm: Double? = null,
    val leftReliefUm: Double? = null,
    val rightReliefUm: Double? = null,
    val tag: String = "",
)

/**
 * The label of a relief field.
 *
 * - [Bottom]: "Relief at bottom (W₁)" (internal W₁).
 * - [Mouth]: "Relief at mouth (W₂)" (internal W₂).
 * - [Shoulder]: "Relief at shoulder (W₁)" (external W₁).
 * - [Face]: "Relief at face (W₂)" (external W₂).
 */
enum class ReliefLabel { Bottom, Mouth, Shoulder, Face }

/**
 * A problem of the thread setup at "Create". The dialog joins the
 * texts with a space and stays open.
 */
sealed interface ThreadSetupProblem {
    /** "There is no open workspace to create the thread in." */
    data object NoWorkspace : ThreadSetupProblem

    /** "Start (Z) value is required" */
    data object StartZRequired : ThreadSetupProblem

    /** "Start (Z) value could not be converted to a number" */
    data object StartZNotANumber : ThreadSetupProblem

    /** "Thread Length must be greater than zero." */
    data object LengthNotPositive : ThreadSetupProblem

    /** "Pitch must be a positive number." */
    data object PitchNotPositive : ThreadSetupProblem

    /** "TPI must be a positive number." */
    data object TpiNotPositive : ThreadSetupProblem

    /** "Minor Diameter must be a positive number." */
    data object MinorNotPositive : ThreadSetupProblem

    /** "Major Diameter must be a positive number." */
    data object MajorNotPositive : ThreadSetupProblem

    /** "Minor Diameter must be smaller than Major Diameter." */
    data object MinorNotSmallerThanMajor : ThreadSetupProblem

    /** "<relief label> must be zero or a positive number." */
    data class ReliefInvalid(val label: ReliefLabel) : ThreadSetupProblem

    /** "Thread Length must be longer than the two reliefs together." */
    data object ReliefsTooLong : ThreadSetupProblem

    /** "Select a thread size." */
    data object NoSize : ThreadSetupProblem
}

/** The Z positions of a thread in µm. */
data class ThreadZPositions(val startZUm: Double, val stopZUm: Double)

/** The rules and the values of the thread setup. */
object ThreadSetup {

    private val tagPattern = Regex("^Thread[ _](\\d+)$")

    /** Gives the label of the W₁ field (true) or the W₂ field (false). */
    fun reliefLabel(internal: Boolean, w1: Boolean): ReliefLabel = when {
        internal && w1 -> ReliefLabel.Bottom
        internal -> ReliefLabel.Mouth
        w1 -> ReliefLabel.Shoulder
        else -> ReliefLabel.Face
    }

    /**
     * Gives the default tag "Thread N": N = 1 + the largest number in the tags "Thread_N" or
     * "Thread N" of the workspace.
     */
    fun defaultTag(existingTags: Iterable<String>): String {
        val largest = existingTags.mapNotNull { tag -> tagPattern.matchEntire(tag.trim())?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "Thread ${largest + 1}"
    }

    /** Gives the default W₁: 3 × the pitch. */
    fun defaultLeftReliefUm(pitchUm: Double): Double = 3.0 * pitchUm

    /** Gives the pitch of the input: the table pitch or the custom pitch, or null. */
    fun pitchUm(input: ThreadSetupInput): Double? = if (input.form.isCustom) {
        input.customPitchUm
    } else {
        input.size?.let { ThreadTables.find(input.form, it) }?.pitchUm
    }

    /**
     * Checks the inputs at "Create". Gives the problems in the order
     * of the dialog, or an empty list.
     *
     * @param hasWorkspace False when no workspace is open.
     */
    fun validate(input: ThreadSetupInput, hasWorkspace: Boolean = true): List<ThreadSetupProblem> {
        val problems = mutableListOf<ThreadSetupProblem>()
        if (!hasWorkspace) problems += ThreadSetupProblem.NoWorkspace
        when {
            input.startZUm == null -> problems += ThreadSetupProblem.StartZRequired
            !input.startZUm.isFinite() -> problems += ThreadSetupProblem.StartZNotANumber
        }
        val length = input.lengthUm
        val lengthValid = length != null && length.isFinite() && length > 0.0
        if (!lengthValid) problems += ThreadSetupProblem.LengthNotPositive
        if (input.form.isCustom) {
            val pitch = input.customPitchUm
            if (pitch == null || !pitch.isFinite() || pitch <= 0.0) {
                problems += if (input.form == ThreadForm.CustomImperial) ThreadSetupProblem.TpiNotPositive else ThreadSetupProblem.PitchNotPositive
            }
            val minor = input.customMinorDiameterUm
            val major = input.customMajorDiameterUm
            val minorValid = minor != null && minor.isFinite() && minor > 0.0
            val majorValid = major != null && major.isFinite() && major > 0.0
            if (!minorValid) problems += ThreadSetupProblem.MinorNotPositive
            if (!majorValid) problems += ThreadSetupProblem.MajorNotPositive
            if (minorValid && majorValid && minor >= major) problems += ThreadSetupProblem.MinorNotSmallerThanMajor
        }
        val w1Valid = reliefValid(input.leftReliefUm)
        val w2Valid = reliefValid(input.rightReliefUm)
        if (!w1Valid) problems += ThreadSetupProblem.ReliefInvalid(reliefLabel(input.internal, w1 = true))
        if (!w2Valid) problems += ThreadSetupProblem.ReliefInvalid(reliefLabel(input.internal, w1 = false))
        if (lengthValid && w1Valid && w2Valid) {
            val reliefs = (input.leftReliefUm ?: 0.0) + (input.rightReliefUm ?: 0.0)
            if (reliefs >= length) problems += ThreadSetupProblem.ReliefsTooLong
        }
        if (!input.form.isCustom && (input.size == null || ThreadTables.find(input.form, input.size) == null)) {
            problems += ThreadSetupProblem.NoSize
        }
        return problems
    }

    private fun reliefValid(value: Double?): Boolean = value == null || (value.isFinite() && value >= 0.0)

    /**
     * Calculates the diameters of the input, or gives null when the size or a custom value is
     * missing or not valid.
     */
    fun calculate(input: ThreadSetupInput): ThreadGeometry? {
        if (input.form.isCustom) {
            val pitch = input.customPitchUm?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val major = input.customMajorDiameterUm?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val minor = input.customMinorDiameterUm?.takeIf { it.isFinite() && it > 0.0 && it < major } ?: return null
            return ThreadCalculator.custom(input.form, input.internal, input.cutter, major, minor, pitch)
        }
        val size = input.size?.let { ThreadTables.find(input.form, it) } ?: return null
        return ThreadCalculator.standard(size, input.internal, input.cutter, input.threadClass, input.cutterTipRadiusUm)
    }

    /**
     * Gives the Z positions: `StartZ = sz − W₂` and
     * `StopZ = sz − (L − W₁)`. Thus L is the full length from the face toward −Z.
     */
    fun zPositions(startZUm: Double, lengthUm: Double, leftReliefUm: Double?, rightReliefUm: Double?): ThreadZPositions =
        ThreadZPositions(
            startZUm = startZUm - (rightReliefUm ?: 0.0),
            stopZUm = startZUm - (lengthUm - (leftReliefUm ?: 0.0)),
        )

    /**
     * Gives the designation: the table designation; custom mm
     * "M<D> × <P>"; custom inch "<D>-<TPI>" (for example "0.5-13"); "Custom" when a value is not
     * available. The custom numbers have at most 4 decimals and no zeros at the end.
     */
    fun designation(input: ThreadSetupInput): String {
        if (!input.form.isCustom) return input.size ?: "Custom"
        val d = input.customMajorDiameterUm?.takeIf { it.isFinite() && it > 0.0 }
        val p = input.customPitchUm?.takeIf { it.isFinite() && it > 0.0 }
        if (d == null || p == null) return "Custom"
        return if (input.form == ThreadForm.CustomMetric) {
            "M${trimNumber(d / 1000.0, 4)} × ${trimNumber(p / 1000.0, 4)}"
        } else {
            "${trimNumber(d / 25400.0, 4)}-${trimNumber(25400.0 / p, 4)}"
        }
    }

    /**
     * Makes the stored thread from a valid input. All lengths are µm. A relief of 0
     * is stored as null (no relief). Gives null when the input has problems.
     *
     * @param existingTags The tags of the workspace, for the default tag.
     */
    fun toSpec(
        input: ThreadSetupInput,
        workspaceId: String,
        existingTags: Iterable<String> = emptyList(),
        now: Instant = Clock.System.now(),
    ): ThreadSpec? {
        if (validate(input).isNotEmpty()) return null
        val geometry = calculate(input) ?: return null
        val length = input.lengthUm ?: return null
        val startZ = input.startZUm ?: return null
        val w1 = input.leftReliefUm?.takeIf { it > 0.0 }
        val w2 = input.rightReliefUm?.takeIf { it > 0.0 }
        val z = zPositions(startZ, length, w1, w2)
        return ThreadSpec(
            workspaceId = workspaceId,
            tag = input.tag.trim().ifEmpty { defaultTag(existingTags) },
            form = input.form,
            size = designation(input),
            threadClass = input.threadClass ?: classesFor(input.form, input.internal).firstOrNull() ?: ThreadClass.Class6g,
            internal = input.internal,
            cutter = input.cutter,
            pitchUm = geometry.pitchUm,
            majorDiameterUm = geometry.majorDiameterUm,
            minorDiameterUm = geometry.minorDiameterUm,
            pitchDiameterUm = geometry.targetPitchDiameterUm,
            preOpDiameterUm = geometry.preOp.valueUm,
            cutToDiameterUm = geometry.cutToDiameterUm,
            startZUm = z.startZUm,
            stopZUm = z.stopZUm,
            leftReliefUm = w1,
            rightReliefUm = w2,
            cutterTipRadiusUm = if (input.cutter == ThreadCutter.Partial) input.cutterTipRadiusUm else null,
            createdOn = now,
            updatedOn = now,
        )
    }

    /**
     * Restores all fields of the setup from a stored thread (the prefill also restores
     * Start Z, the length, and the reliefs). `sz = StartZ + W₂` and `L = sz − StopZ + W₁`. The tag
     * is empty, so that the new thread gets the next default tag.
     */
    fun restore(spec: ThreadSpec): ThreadSetupInput {
        val w1 = spec.leftReliefUm
        val w2 = spec.rightReliefUm
        val sz = spec.startZUm + (w2 ?: 0.0)
        val length = sz - spec.stopZUm + (w1 ?: 0.0)
        val custom = spec.form.isCustom
        return ThreadSetupInput(
            form = spec.form,
            internal = spec.internal,
            size = if (custom) null else spec.size,
            threadClass = if (custom) null else spec.threadClass,
            cutter = spec.cutter,
            customPitchUm = if (custom) spec.pitchUm else null,
            customMajorDiameterUm = if (custom) spec.majorDiameterUm else null,
            customMinorDiameterUm = if (custom) spec.minorDiameterUm else null,
            cutterTipRadiusUm = spec.cutterTipRadiusUm,
            startZUm = sz,
            lengthUm = length,
            leftReliefUm = w1,
            rightReliefUm = w2,
            tag = "",
        )
    }
}
