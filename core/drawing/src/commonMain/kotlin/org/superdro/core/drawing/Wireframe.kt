package org.superdro.core.drawing

/** A point in integer nanometers. */
data class NmPoint(val x: Long, val y: Long)

/** The extents of a wireframe document (key `ext`): the lower left and the upper right corner in nm. */
data class WireframeExtents(val min: NmPoint, val max: NmPoint)

/**
 * One layer of a wireframe document: key `n` (name), `v` (0 or 1),
 * and `st` (stroke style 0 to 5).
 */
data class WireframeLayer(
    val name: String,
    val visible: Boolean = true,
    val style: Int = WireframeDocument.DEFAULT_STYLE,
)

/**
 * One entity of a wireframe document. All coordinates and radii are
 * integer nm. The angles are radians.
 *
 * @property layer The layer index (key `l`).
 * @property id The optional unique ID (key `id`).
 * @property style The stroke style 0 to 5 (key `st`), or null for the style of the layer.
 * @property group The optional group ID (key `g`).
 */
sealed interface WireframeEntity {
    val layer: Int
    val id: String?
    val style: Int?
    val group: String?

    /** The type key `t`: `L`, `PT`, `C`, `A`, `P`, or `B`. */
    val typeKey: String
}

/** A line (`t` = `L`): `p` = [[x1, y1], [x2, y2]]. */
data class WireframeLine(
    val start: NmPoint,
    val end: NmPoint,
    override val layer: Int = 0,
    override val id: String? = null,
    override val style: Int? = null,
    override val group: String? = null,
) : WireframeEntity {
    override val typeKey: String get() = "L"
}

/** A point (`t` = `PT`): `p` = [x, y] and an optional `name` (60 characters or less). */
data class WireframePoint(
    val point: NmPoint,
    val name: String? = null,
    override val layer: Int = 0,
    override val id: String? = null,
    override val style: Int? = null,
    override val group: String? = null,
) : WireframeEntity {
    override val typeKey: String get() = "PT"
}

/** A circle (`t` = `C`): center `c` and radius `r` > 0. */
data class WireframeCircle(
    val center: NmPoint,
    val radius: Long,
    override val layer: Int = 0,
    override val id: String? = null,
    override val style: Int? = null,
    override val group: String? = null,
) : WireframeEntity {
    override val typeKey: String get() = "C"
}

/** An arc (`t` = `A`): center `c`, radius `r` > 0, and `a` = [start, sweep] in radians. */
data class WireframeArc(
    val center: NmPoint,
    val radius: Long,
    val start: Double,
    val sweep: Double,
    override val layer: Int = 0,
    override val id: String? = null,
    override val style: Int? = null,
    override val group: String? = null,
) : WireframeEntity {
    override val typeKey: String get() = "A"
}

/**
 * A polyline (`t` = `P`): `pts` (2 to 10 000 points), `cl` (closed), and `bul` (the bulge of a
 * segment by its index). A bulge is `tan(angle / 4)`, positive for a counterclockwise arc.
 */
data class WireframePolyline(
    val points: List<NmPoint>,
    val closed: Boolean = false,
    val bulges: Map<Int, Double> = emptyMap(),
    override val layer: Int = 0,
    override val id: String? = null,
    override val style: Int? = null,
    override val group: String? = null,
) : WireframeEntity {
    override val typeKey: String get() = "P"
}

/** A Bézier chain (`t` = `B`): `p` has 1 + 3·N points for N cubic segments; `cl` (closed). */
data class WireframeBezier(
    val points: List<NmPoint>,
    val closed: Boolean = false,
    override val layer: Int = 0,
    override val id: String? = null,
    override val style: Int? = null,
    override val group: String? = null,
) : WireframeEntity {
    override val typeKey: String get() = "B"

    /** The number of cubic segments. */
    val segmentCount: Int get() = (points.size - 1) / 3
}

/**
 * A wireframe document: schema `touchdro.wireframe`, version 3.0.
 *
 * @property createdAt The UTC time `yyyy-MM-dd'T'HH:mm:ss'Z'`.
 */
data class WireframeDocument(
    val version: String = VERSION,
    val createdAt: String = "",
    val axes: List<String> = DEFAULT_AXES,
    val extents: WireframeExtents? = null,
    val layers: List<WireframeLayer> = emptyList(),
    val entities: List<WireframeEntity> = emptyList(),
) {
    /** The style of [entity]: its own style, else the style of its layer, else 1. */
    fun styleOf(entity: WireframeEntity): Int = entity.style ?: layers.getOrNull(entity.layer)?.style ?: DEFAULT_STYLE

    /** The total number of Bézier segments. */
    val bezierSegmentCount: Int get() = entities.sumOf { entity -> (entity as? WireframeBezier)?.segmentCount ?: 0 }

    companion object {
        const val SCHEMA: String = "touchdro.wireframe"
        const val VERSION: String = "3.0"
        const val DEFAULT_STYLE: Int = 1
        val DEFAULT_AXES: List<String> = listOf("X", "Y")
    }
}

/** The status of a converter result. */
enum class ConvertStatus { Ready, Failed }

/**
 * The converter result envelope: schema `touchdro.convert.result`.
 * The DXF converter of SuperDRO gives this envelope, and the reader reads it from a file.
 *
 * @property wireframe The document when [status] is [ConvertStatus.Ready].
 */
data class ConvertResult(
    val status: ConvertStatus,
    val wireframe: WireframeDocument? = null,
    val warnings: List<DrawingProblem> = emptyList(),
    val errors: List<DrawingProblem> = emptyList(),
    val format: String = "dxf",
    val converterBuild: String = CONVERTER_BUILD,
) {
    companion object {
        const val SCHEMA: String = "touchdro.convert.result"
        const val ENVELOPE_VERSION: String = "1"

        /** The value of `converterBuild` that SuperDRO writes. The reader ignores it. */
        const val CONVERTER_BUILD: String = "superdro/dxf-converter"
    }
}
