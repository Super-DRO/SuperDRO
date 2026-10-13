package org.superdro.core.ui.functions.common

import org.jetbrains.compose.resources.StringResource
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.Units
import org.superdro.core.readout.ReadoutPatterns
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_unit_inch
import org.superdro.core.resources.fn_unit_mm

/** The texts of the lengths and the angles of the functions area. */
object LengthTexts {

    /**
     * Gives the text of the length [um] in mm ([metric]) or in inch, with the readout pattern of
     * [format], for example "12.500" or "0.4921".
     */
    fun format(um: Double, metric: Boolean, format: ReadoutFormat = ReadoutFormat.Format3): String =
        ReadoutPatterns.linear(format, metric).format(um / FieldMath.umPerUnit(metric))

    /** Gives the text of the angle [arcSec] in degrees with 2 decimals (`##0.00`), for example "53.13". */
    fun degrees(arcSec: Double): String = ReadoutPatterns.ANGULAR.format(arcSec / Units.ARCSEC_PER_DEGREE)

    /** The unit text of a length: "mm" or "in". */
    fun unit(metric: Boolean): StringResource = if (metric) Res.string.fn_unit_mm else Res.string.fn_unit_inch
}
