package org.superdro.core.ui.functions.subdatums

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import org.superdro.core.functions.patterns.PatternTags
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.SubDatum
import org.superdro.core.readout.PostProcessor
import org.superdro.core.readout.ReadoutFrame

/**
 * The coordinates of a new sub-datum at the current position:
 *
 * - X always.
 * - Y if the machine is not a lathe.
 * - Z if Z is enabled and the machine is a lathe or 3D sub-datums are on.
 * - A if the angular axis is enabled.
 *
 * The values are the absolute display values: the absolute position with the tool offset, before
 * the diameter display (a stored lathe X is a radius). The angular value is in one revolution.
 */
object AddPointRule {

    /** The axes of a new point of [frame], in the order X, Y, Z, A. */
    fun axes(frame: ReadoutFrame): List<AxisId> = buildList {
        val profile = frame.profile
        val lathe = profile.machineType.isLathe
        add(AxisId.X)
        if (!lathe && frame.enabled(AxisId.Y)) add(AxisId.Y)
        if (frame.enabled(AxisId.Z) && (lathe || profile.use3dSubDatums)) add(AxisId.Z)
        if (frame.enabled(AxisId.Angular)) add(AxisId.Angular)
    }

    /**
     * Gives the new point with no name (the store gives the name `Point <n>`), or null when an
     * axis of the point has no finite value.
     */
    fun point(frame: ReadoutFrame): SubDatum? {
        val values = LinkedHashMap<AxisId, Double>()
        for (axis in axes(frame)) {
            val reading = frame.reading(axis)
            if (!reading.hasValue || !reading.absolute.isFinite()) return null
            values[axis] = if (axis == AxisId.Angular) PostProcessor.AngularWrap.toShown(reading.absolute) else reading.absolute
        }
        return SubDatum(
            x = values[AxisId.X],
            y = values[AxisId.Y],
            z = values[AxisId.Z],
            a = values[AxisId.Angular],
        )
    }
}

/** The rules of the sub-datum edit sheet. */
object SubDatumNames {

    /** A name has 1 to 15 characters. The same limit as a machine datum. */
    const val MAX_NAME_LENGTH: Int = MachineDatum.MAX_NAME_LENGTH

    /** True when [name] (after the trim) has 1 to 15 characters. */
    fun valid(name: String): Boolean = name.trim().length in 1..MAX_NAME_LENGTH

    /**
     * Reads the tag field: the tags are separated by white space. Each tag is normalized
     * ([PatternTags.normalize]). A tag that is already in the list (any case) does not repeat. All
     * tags stay.
     */
    fun parseTags(text: String): List<String> {
        val result = ArrayList<String>()
        text.split(WHITE_SPACE).forEach { part ->
            val tag = PatternTags.normalize(part) ?: return@forEach
            if (result.none { PatternTags.key(it) == PatternTags.key(tag) }) result += tag
        }
        return result
    }

    /** The text of the tag field of [tags]. */
    fun tagText(tags: List<String>): String = tags.joinToString(" ")

    private val WHITE_SPACE = Regex("""\s+""")
}

/**
 * The tag colors: a fixed list of 23 colors, assigned in
 * the order of the tags. SuperDRO checks each color against the surface color of the theme and
 * moves it toward black (a light surface) or white (a dark surface) until the contrast is 3 : 1
 * or more. The mark of a tag also has an icon and the tag text, so the color is not the only sign.
 */
object TagColors {

    /** The 23 tag colors in ARGB. */
    val BASE: List<Long> = listOf(
        0xFFB2FFFF, 0xFFFFC0CB, 0xFFD1EEEE, 0xFFFFB7CE, 0xFFA2CFFE, 0xFFFFDAB9, 0xFFC8A2C8, 0xFFFFFACD,
        0xFFB0E0E6, 0xFFFFABAB, 0xFF98FF98, 0xFFD6A4A4, 0xFF77DD77, 0xFFFFD1B2, 0xFFE6B0AA, 0xFFA3C1DA,
        0xFFFDEBD0, 0xFFF9E79F, 0xFFD5DBDB, 0xFFFADBD8, 0xFFA9CCE3, 0xFFF5CBA7, 0xFFAED6F1,
    )

    /** The smallest contrast of a tag color on the surface. */
    const val MIN_CONTRAST: Double = 3.0

    /** The WCAG contrast ratio of [a] and [b]: `(L1 + 0.05) / (L2 + 0.05)`, L1 the lighter. */
    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Gives the color of the tag with the index [index] (the order of the tags) on [surface]. */
    fun color(index: Int, surface: Color): Color {
        val base = Color(BASE[index.mod(BASE.size)].toInt())
        if (contrast(base, surface) >= MIN_CONTRAST) return base
        val target = if (surface.luminance() > 0.5f) Color.Black else Color.White
        var fraction = 0.05f
        while (fraction <= 1f) {
            val candidate = lerp(base, target, fraction)
            if (contrast(candidate, surface) >= MIN_CONTRAST) return candidate
            fraction += 0.05f
        }
        return target
    }
}
