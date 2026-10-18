package org.superdro.core.functions.thread

import org.superdro.core.model.PartialInsertPreset
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadForm

/**
 * The unit of a thread series. ISO and custom metric threads use mm.
 * Imperial and pipe threads use the inch. SuperDRO keeps all lengths in µm. The unit
 * selects only the display format and the class math.
 *
 * @property umPerUnit The µm in one unit: 1000 (mm) or 25400 (inch).
 * @property decimals The decimals of a length in the thread dialog: 3 (mm) or 4 (inch).
 */
enum class ThreadUnit(val umPerUnit: Double, val decimals: Int) {
    Millimeter(1000.0, 3),
    Inch(25400.0, 4),
}

/** The unit of the series of this form. */
val ThreadForm.unit: ThreadUnit
    get() = when (this) {
        ThreadForm.IsoMetricCoarse,
        ThreadForm.IsoMetricFine,
        ThreadForm.IsoTrapezoidal,
        ThreadForm.CustomMetric,
        -> ThreadUnit.Millimeter
        ThreadForm.Unc,
        ThreadForm.Unf,
        ThreadForm.Unef,
        ThreadForm.Bsw,
        ThreadForm.Bsf,
        ThreadForm.Bspp,
        ThreadForm.Acme,
        ThreadForm.Npt,
        ThreadForm.Bspt,
        ThreadForm.CustomImperial,
        -> ThreadUnit.Inch
    }

/** The included flank angle `α` in degrees. */
val ThreadForm.includedAngleDeg: Double
    get() = when (this) {
        ThreadForm.IsoMetricCoarse,
        ThreadForm.IsoMetricFine,
        ThreadForm.Unc,
        ThreadForm.Unf,
        ThreadForm.Unef,
        ThreadForm.Npt,
        ThreadForm.CustomMetric,
        ThreadForm.CustomImperial,
        -> 60.0
        ThreadForm.Bsw,
        ThreadForm.Bsf,
        ThreadForm.Bspp,
        ThreadForm.Bspt,
        -> 55.0
        ThreadForm.IsoTrapezoidal -> 30.0
        ThreadForm.Acme -> 29.0
    }

/** True for a 1:16 tapered pipe thread (`Npt` or `Bspt`). */
val ThreadForm.isTapered: Boolean
    get() = this == ThreadForm.Npt || this == ThreadForm.Bspt

/** The diameter taper ratio `ΔD / ΔZ`: `0.0625` (1:16) for a tapered pipe thread, else `0.0`. */
val ThreadForm.taperRatio: Double
    get() = if (isTapered) 0.0625 else 0.0

/** True for the two custom forms. A custom form has typed diameters and no class math. */
val ThreadForm.isCustom: Boolean
    get() = this == ThreadForm.CustomMetric || this == ThreadForm.CustomImperial

/** True for the ISO metric forms (ISO 965 classes). */
val ThreadForm.isIso: Boolean
    get() = this == ThreadForm.IsoMetricCoarse || this == ThreadForm.IsoMetricFine

/** True for the UN forms (ASME B1.1 classes). */
val ThreadForm.isUnified: Boolean
    get() = this == ThreadForm.Unc || this == ThreadForm.Unf || this == ThreadForm.Unef

/** True for an external class. */
val ThreadClass.isExternal: Boolean
    get() = when (this) {
        ThreadClass.Class6g,
        ThreadClass.Class2A,
        ThreadClass.Class3A,
        ThreadClass.ClassA,
        ThreadClass.ClassB,
        ThreadClass.ClassMedium,
        ThreadClass.Class7e,
        ThreadClass.Class2G,
        ThreadClass.Class3G,
        -> true
        ThreadClass.Class6H,
        ThreadClass.Class2B,
        ThreadClass.Class3B,
        ThreadClass.Class7H,
        -> false
    }

/** True for an internal class. */
val ThreadClass.isInternal: Boolean
    get() = when (this) {
        ThreadClass.Class6H,
        ThreadClass.Class2B,
        ThreadClass.Class3B,
        ThreadClass.ClassMedium,
        ThreadClass.Class7H,
        ThreadClass.Class2G,
        ThreadClass.Class3G,
        -> true
        ThreadClass.Class6g,
        ThreadClass.Class2A,
        ThreadClass.Class3A,
        ThreadClass.ClassA,
        ThreadClass.ClassB,
        ThreadClass.Class7e,
        -> false
    }

/** The unit of the tolerance class. */
val ThreadClass.unit: ThreadUnit
    get() = when (this) {
        ThreadClass.Class6g,
        ThreadClass.Class6H,
        ThreadClass.Class7e,
        ThreadClass.Class7H,
        -> ThreadUnit.Millimeter
        ThreadClass.Class2A,
        ThreadClass.Class3A,
        ThreadClass.Class2B,
        ThreadClass.Class3B,
        ThreadClass.ClassA,
        ThreadClass.ClassB,
        ThreadClass.ClassMedium,
        ThreadClass.Class2G,
        ThreadClass.Class3G,
        -> ThreadUnit.Inch
    }

/** True for an ISO 965 class (6g, 6H). Else the class is not an ISO 965 class. */
val ThreadClass.isIso: Boolean
    get() = this == ThreadClass.Class6g || this == ThreadClass.Class6H

/** The text of the class, for example `6g` or `2A`. */
val ThreadClass.label: String
    get() = when (this) {
        ThreadClass.Class6g -> "6g"
        ThreadClass.Class6H -> "6H"
        ThreadClass.Class2A -> "2A"
        ThreadClass.Class3A -> "3A"
        ThreadClass.Class2B -> "2B"
        ThreadClass.Class3B -> "3B"
        ThreadClass.ClassA -> "A"
        ThreadClass.ClassB -> "B"
        ThreadClass.ClassMedium -> "Medium"
        ThreadClass.Class7e -> "7e"
        ThreadClass.Class7H -> "7H"
        ThreadClass.Class2G -> "2G"
        ThreadClass.Class3G -> "3G"
    }

/**
 * Gives the classes that the setup offers for a form and a type. A custom form, a tapered pipe
 * form, or an internal `Bspp` thread has no class selector.
 */
fun classesFor(form: ThreadForm, internal: Boolean): List<ThreadClass> = when (form) {
    ThreadForm.IsoMetricCoarse, ThreadForm.IsoMetricFine ->
        if (internal) listOf(ThreadClass.Class6H) else listOf(ThreadClass.Class6g)
    ThreadForm.Unc, ThreadForm.Unf, ThreadForm.Unef ->
        if (internal) listOf(ThreadClass.Class2B, ThreadClass.Class3B) else listOf(ThreadClass.Class2A, ThreadClass.Class3A)
    ThreadForm.Bspp ->
        if (internal) emptyList() else listOf(ThreadClass.ClassA, ThreadClass.ClassB)
    ThreadForm.Bsw, ThreadForm.Bsf ->
        listOf(ThreadClass.ClassMedium)
    ThreadForm.IsoTrapezoidal ->
        if (internal) listOf(ThreadClass.Class7H) else listOf(ThreadClass.Class7e)
    ThreadForm.Acme ->
        listOf(ThreadClass.Class2G, ThreadClass.Class3G)
    ThreadForm.Npt, ThreadForm.Bspt, ThreadForm.CustomMetric, ThreadForm.CustomImperial ->
        emptyList()
}

/** The tip radius of the preset in µm, or null for [PartialInsertPreset.Custom]. */
val PartialInsertPreset.tipRadiusUm: Double?
    get() = when (this) {
        PartialInsertPreset.Custom -> null
        PartialInsertPreset.A60 -> 55.0
        PartialInsertPreset.G60 -> 200.0
        PartialInsertPreset.AG60 -> 60.0
        PartialInsertPreset.N60 -> 400.0
    }

/** The smallest valid pitch of the preset in µm. */
val PartialInsertPreset.minPitchUm: Double
    get() = when (this) {
        PartialInsertPreset.Custom -> 0.0
        PartialInsertPreset.A60, PartialInsertPreset.AG60 -> 500.0
        PartialInsertPreset.G60 -> 1750.0
        PartialInsertPreset.N60 -> 3500.0
    }

/** The largest valid pitch of the preset in µm. */
val PartialInsertPreset.maxPitchUm: Double
    get() = when (this) {
        PartialInsertPreset.Custom -> Double.POSITIVE_INFINITY
        PartialInsertPreset.A60 -> 1587.5
        PartialInsertPreset.G60, PartialInsertPreset.AG60 -> 3175.0
        PartialInsertPreset.N60 -> 5080.0
    }

/** True when [pitchUm] is inside the pitch range of the preset. */
fun PartialInsertPreset.validForPitch(pitchUm: Double): Boolean =
    pitchUm.isFinite() && pitchUm in minPitchUm..maxPitchUm

