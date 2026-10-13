package org.superdro.core.ui.adapter.probe

import org.jetbrains.compose.resources.StringResource
import org.superdro.core.functions.tipcal.TipCalibrationSetup
import org.superdro.core.model.Channels
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeRoles
import org.superdro.core.model.ProbeType
import org.superdro.core.model.Units
import org.superdro.core.readout.NumberPattern
import org.superdro.core.resources.Res
import org.superdro.core.resources.probelib_function_edge_finder
import org.superdro.core.resources.probelib_function_measurement
import org.superdro.core.resources.probelib_function_z_reference
import org.superdro.core.resources.probelib_type_camera
import org.superdro.core.resources.probelib_type_edge_finder
import org.superdro.core.resources.probelib_type_tool_setter
import org.superdro.core.resources.probelib_type_touch_probe
import org.superdro.core.resources.probelib_unit_inch
import org.superdro.core.resources.probelib_unit_mm

/**
 * A function that uses a probe profile.
 * Each machine profile keeps one probe profile for each function in [ProbeRoles].
 */
enum class ProbeFunction {
    /** The edge finder: a touch probe, a 2D edge finder, or a camera. */
    EdgeFinder,

    /** The Z reference: a touch probe or a tool height setter. */
    ZReference,

    /** The measurement instrument: a profile with X and Y offsets that is a camera or has a trigger port. */
    MeasurementInstrument,
    ;

    /** True when [profile] can serve this function. */
    fun accepts(profile: ProbeProfile): Boolean = when (this) {
        EdgeFinder -> profile.type == ProbeType.TouchProbe || profile.type == ProbeType.EdgeFinder2D || profile.type == ProbeType.Camera
        ZReference -> profile.type == ProbeType.TouchProbe || profile.type == ProbeType.ToolHeightSetter
        MeasurementInstrument -> ProbeTypeRules.usesXyOffsets(profile.type) && (profile.type == ProbeType.Camera || profile.port != null)
    }

    /** The probe profile of this function in [roles], or null. */
    fun selected(roles: ProbeRoles): String? = when (this) {
        EdgeFinder -> roles.edgeFinderProfileId
        ZReference -> roles.zReferenceProfileId
        MeasurementInstrument -> roles.measurementInstrumentProfileId
    }

    /** Gives [roles] with [id] as the probe profile of this function. Null clears the selection. */
    fun withSelection(roles: ProbeRoles, id: String?): ProbeRoles = when (this) {
        EdgeFinder -> roles.copy(edgeFinderProfileId = id)
        ZReference -> roles.copy(zReferenceProfileId = id)
        MeasurementInstrument -> roles.copy(measurementInstrumentProfileId = id)
    }

    /** The name of the function in the UI. */
    val label: StringResource
        get() = when (this) {
            EdgeFinder -> Res.string.probelib_function_edge_finder
            ZReference -> Res.string.probelib_function_z_reference
            MeasurementInstrument -> Res.string.probelib_function_measurement
        }
}

/**
 * The fields that each probe type uses.
 *
 * | Type | Port | Diameter | X and Y offsets | Z offset |
 * |---|---|---|---|---|
 * | Touch probe | P or Q (default P) | yes | yes | yes |
 * | 2D edge finder | none, P, or Q | yes | yes | no |
 * | Tool height setter | none, P, or Q | no | no | yes ("Setter height") |
 * | Camera | none | no | yes | no |
 */
object ProbeTypeRules {

    /** True for the two types that use a tip diameter: the touch probe and the 2D edge finder. */
    fun usesDiameter(type: ProbeType): Boolean = TipCalibrationSetup.usesDiameter(type)

    /** True for each type except the tool height setter. */
    fun usesXyOffsets(type: ProbeType): Boolean = type != ProbeType.ToolHeightSetter

    /** True for the touch probe and the tool height setter ("Setter height"). */
    fun usesZOffset(type: ProbeType): Boolean = type == ProbeType.TouchProbe || type == ProbeType.ToolHeightSetter

    /** The trigger ports that the user can select. Null is "None". A camera has no port. */
    fun ports(type: ProbeType): List<Char?> = when (type) {
        ProbeType.TouchProbe -> listOf(Channels.P, Channels.Q)
        ProbeType.Camera -> emptyList()
        ProbeType.EdgeFinder2D, ProbeType.ToolHeightSetter -> listOf(null, Channels.P, Channels.Q)
    }

    /** The port of a new profile of [type]: P for a touch probe, else none. */
    fun defaultPort(type: ProbeType): Char? = if (type == ProbeType.TouchProbe) Channels.P else null

    /** Gives [port] when [type] permits it, else the default port of [type]. */
    fun fixPort(type: ProbeType, port: Char?): Char? {
        val letter = port?.let(Channels::normalize)
        return if (letter in ports(type)) letter else defaultPort(type)
    }

    /** True when the tip calibration is available: the type uses a diameter and has a port. */
    fun canCalibrate(type: ProbeType, port: Char?): Boolean = usesDiameter(type) && port != null

    /** The name of [type] in the UI. */
    fun label(type: ProbeType): StringResource = when (type) {
        ProbeType.TouchProbe -> Res.string.probelib_type_touch_probe
        ProbeType.EdgeFinder2D -> Res.string.probelib_type_edge_finder
        ProbeType.ToolHeightSetter -> Res.string.probelib_type_tool_setter
        ProbeType.Camera -> Res.string.probelib_type_camera
    }
}

/**
 * The lengths of a probe profile in the units of the profile. The model keeps µm (CLAUDE.md). A
 * metric profile shows mm with 3 decimals. An inch profile shows inches with 4 decimals.
 */
object ProbeLengths {

    private val MM = NumberPattern("##0.000")
    private val INCH = NumberPattern("##0.0000")
    private val FINE_MM = NumberPattern("##0.0000")
    private val FINE_INCH = NumberPattern("##0.00000")

    /** The µm of one unit: 1000 for mm, 25400 for inch. */
    fun umPerUnit(metric: Boolean): Double = if (metric) Units.UM_PER_MM else Units.UM_PER_INCH

    /** Gives the text of [um] in mm or inches, with no unit. */
    fun text(um: Double, metric: Boolean): String = (if (metric) MM else INCH).format(um / umPerUnit(metric))

    /**
     * Gives the text of a small length [um] (a spread or a repeatability) with one more decimal:
     * mm with 4 decimals, inches with 5 decimals.
     */
    fun fineText(um: Double, metric: Boolean): String = (if (metric) FINE_MM else FINE_INCH).format(um / umPerUnit(metric))

    /**
     * Reads a length in mm or inches and gives µm. A comma is a decimal mark. A blank text or a
     * text that is not a finite number gives null.
     */
    fun parseUm(text: String, metric: Boolean): Double? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (value.isFinite()) value * umPerUnit(metric) else null
    }

    /** The unit name: "mm" or "in". */
    fun unit(metric: Boolean): StringResource = if (metric) Res.string.probelib_unit_mm else Res.string.probelib_unit_inch

    /** Gives a field text in the other unit: the value stays, the unit changes. A text that is not a number stays. */
    fun convertText(text: String, fromMetric: Boolean, toMetric: Boolean): String {
        if (fromMetric == toMetric) return text
        val um = parseUm(text, fromMetric) ?: return text
        return text(um, toMetric)
    }
}

/**
 * The order of the probe selector: the last used profile first,
 * then the profiles that were never used, each part by name in the natural order ([NaturalNames]).
 */
fun List<ProbeProfile>.sortedForSelector(): List<ProbeProfile> = sortedWith(
    compareBy<ProbeProfile> { profile -> profile.lastUsedOn == null }
        .thenByDescending { profile -> profile.lastUsedOn }
        .thenComparator { a, b -> NaturalNames.compare(a.name, b.name) },
)

/**
 * The order of the probe library: the own profiles of the machine profile first, then the
 * profiles for all machine profiles, each part by name in the natural order. Thus the six standard
 * tips have this order: 0.100", 0.200", 0.500", 4 mm, 6 mm, 10 mm.
 */
fun List<ProbeProfile>.sortedForLibrary(): List<ProbeProfile> = sortedWith(
    compareBy<ProbeProfile> { profile -> profile.profileId == null }
        .thenComparator { a, b -> NaturalNames.compare(a.name, b.name) },
)

/**
 * The natural order of names: a number in a name compares by its value, so "Edge finder 4 mm"
 * comes before "Edge finder 10 mm". The other parts compare with no case.
 */
object NaturalNames : Comparator<String> {
    private val parts = Regex("""\d+(?:\.\d+)?|\D+""")

    override fun compare(a: String, b: String): Int {
        val left = parts.findAll(a).map { match -> match.value }.toList()
        val right = parts.findAll(b).map { match -> match.value }.toList()
        for (index in 0 until minOf(left.size, right.size)) {
            val x = left[index]
            val y = right[index]
            val xNumber = x.toDoubleOrNull()
            val yNumber = y.toDoubleOrNull()
            val result = if (xNumber != null && yNumber != null) xNumber.compareTo(yNumber) else x.compareTo(y, ignoreCase = true)
            if (result != 0) return result
        }
        return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
