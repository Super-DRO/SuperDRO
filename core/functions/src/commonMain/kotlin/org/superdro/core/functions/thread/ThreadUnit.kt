package org.superdro.core.functions.thread

import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadForm

/**
 * The unit of a thread series. ISO and custom metric threads use mm.
 * UN and custom imperial threads use the inch. SuperDRO keeps all lengths in µm. The unit
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
        ThreadForm.IsoMetricCoarse, ThreadForm.IsoMetricFine, ThreadForm.CustomMetric -> ThreadUnit.Millimeter
        ThreadForm.Unc, ThreadForm.Unf, ThreadForm.Unef, ThreadForm.CustomImperial -> ThreadUnit.Inch
    }

/** True for the two custom forms. A custom form has typed diameters and no class math. */
val ThreadForm.isCustom: Boolean
    get() = this == ThreadForm.CustomMetric || this == ThreadForm.CustomImperial

/** True for the ISO metric forms (ISO 965 classes). */
val ThreadForm.isIso: Boolean
    get() = this == ThreadForm.IsoMetricCoarse || this == ThreadForm.IsoMetricFine

/** True for the UN forms (ASME B1.1 classes). */
val ThreadForm.isUnified: Boolean
    get() = this == ThreadForm.Unc || this == ThreadForm.Unf || this == ThreadForm.Unef

/** True for an external class (6g, 2A, 3A). */
val ThreadClass.isExternal: Boolean
    get() = this == ThreadClass.Class6g || this == ThreadClass.Class2A || this == ThreadClass.Class3A

/** True for an ISO 965 class (6g, 6H). Else the class is an ASME B1.1 class. */
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
    }

/**
 * Gives the classes that the setup offers for a form and a type: ISO
 * internal {6H}, ISO external {6g}, ASME internal {2B, 3B}, ASME external {2A, 3A}. A custom form
 * has no class.
 */
fun classesFor(form: ThreadForm, internal: Boolean): List<ThreadClass> = when {
    form.isCustom -> emptyList()
    form.isIso -> if (internal) listOf(ThreadClass.Class6H) else listOf(ThreadClass.Class6g)
    else -> if (internal) listOf(ThreadClass.Class2B, ThreadClass.Class3B) else listOf(ThreadClass.Class2A, ThreadClass.Class3A)
}
