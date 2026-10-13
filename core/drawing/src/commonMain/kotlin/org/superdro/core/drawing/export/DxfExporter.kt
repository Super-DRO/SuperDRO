package org.superdro.core.drawing.export

import kotlin.math.PI
import kotlin.math.max
import org.superdro.core.drawing.Bounds
import org.superdro.core.drawing.geometry.Vec
import org.superdro.core.drawing.text.DxfCharset
import org.superdro.core.drawing.text.DxfText
import org.superdro.core.model.AxisId
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace

/**
 * The input of the DXF export of a workspace.
 *
 * @property metric The export units: true for mm, false for inch. `$INSUNITS` and `$MEASUREMENT`
 *   follow this value.
 * @property includeHiddenLayers True when the user selects the hidden drawing layers for the
 *   export. With false, the hidden layers and their shapes stay out of the file.
 * @property measurementLayerName The name of the drawing layer of the measurement function. The
 *   export writes it as the layer `MEASURED`.
 */
data class DxfExportRequest(
    val workspace: Workspace,
    val drawing: Drawing? = null,
    val metric: Boolean = true,
    val includeHiddenLayers: Boolean = false,
    val measurementLayerName: String? = DxfExporter.MEASUREMENT_LAYER_NAME,
)

/**
 * The DXF export of a workspace: the fixed AC1015 template,
 * windows-1252, CR LF, and the tags of the sub-datums as layers. SuperDRO has no Plus gate.
 */
object DxfExporter {

    /** The name of the measurement layer in a drawing. */
    const val MEASUREMENT_LAYER_NAME: String = "Measured"

    /** The ACI colors of the tag layers: the tag index mod 9 selects the color. */
    val TAG_COLORS: List<Int> = listOf(140, 30, 101, 231, 40, 161, 11, 121, 61)

    /** The ACI color of the other layers. */
    const val DEFAULT_COLOR: Int = 7

    /** The first handle of the records and entities of the export (hexadecimal 200). */
    const val FIRST_HANDLE: Int = 0x200

    /** The handle of the BLOCK_RECORD `*Model_Space` in the template: the owner of each entity. */
    const val MODEL_SPACE_HANDLE: String = "1F"

    /** The handle of the plot style `Normal` in the template: group 390 of each layer. */
    const val PLOT_STYLE_HANDLE: String = "F"

    /** The smallest size of the origin mark: 2.5 mm or 0.0984252 in. */
    const val MIN_MARK_MM: Double = 2.5
    const val MIN_MARK_INCH: Double = 0.0984252

    /** Gives the DXF file of [request] as windows-1252 bytes. */
    fun export(request: DxfExportRequest): ByteArray = DxfCharset.WINDOWS_1252.encode(exportText(request))

    /** Gives the DXF text of [request], with CR LF line ends. [export] encodes this text. */
    fun exportText(request: DxfExportRequest): String = Export(request).build()

    /**
     * Gives the file name `superdro-workspace-<workspace>-<profile>-<mill or lathe>-<stamp>.dxf`.
     * Each part has the runs of characters that are not letters or digits (ASCII) replaced by `_`,
     * no `_` at its ends, and an empty part goes away.
     *
     * @param stamp The local time `yyyyMMdd_HHmmss`.
     */
    fun fileName(workspaceName: String, profileName: String, machineType: MachineType, stamp: String): String {
        val kind = if (machineType == MachineType.Lathe) "lathe" else "mill"
        val parts = listOf("superdro-workspace", clean(workspaceName), clean(profileName), kind, clean(stamp))
            .filter { part -> part.isNotEmpty() }
        return parts.joinToString("-") + ".dxf"
    }

    private val NOT_ALPHANUMERIC = Regex("[^A-Za-z0-9]+")

    private fun clean(part: String): String = part.replace(NOT_ALPHANUMERIC, "_").trim('_')

    private val BAD_LAYER_CHARACTERS = setOf(':', ';', '<', '>', '/', '\\', '"', '?', '*', '|', '=', '`')

    /**
     * Gives a valid layer name: tab, CR, and LF become a space; control characters, the characters
     * `:;<>/\"?*|=` and the back quote become `_`; a character that windows-1252 does not have
     * becomes `_`; the runs of spaces collapse to one space.
     */
    fun layerName(name: String): String {
        val builder = StringBuilder(name.length)
        for (char in name) {
            val mapped = when {
                char == '\t' || char == '\r' || char == '\n' -> ' '
                char.code < 0x20 || char.code == 0x7F || char in BAD_LAYER_CHARACTERS -> '_'
                !DxfCharset.WINDOWS_1252.canEncode(char) -> '_'
                else -> char
            }
            if (mapped == ' ' && builder.isNotEmpty() && builder.last() == ' ') continue
            builder.append(mapped)
        }
        return builder.toString().trim()
    }

    /** The ACI color of the tag with the index [index] in the sorted tag list. */
    fun tagColor(index: Int): Int = TAG_COLORS[index % TAG_COLORS.size]
}

/** One export run. */
private class Export(private val request: DxfExportRequest) {
    private val metric = request.metric
    private val workspace = request.workspace
    private val plane = workspace.plane

    /** nm per export unit and µm per export unit. */
    private val nmPerUnit = if (metric) 1_000_000.0 else 25_400_000.0
    private val umPerUnit = if (metric) 1000.0 else 25_400.0

    private var nextHandle = DxfExporter.FIRST_HANDLE
    private fun handle(): String = (nextHandle++).toString(16).uppercase()

    private class Layer(val name: String, val color: Int)

    private val layers = ArrayList<Layer>()
    private val usedNames = HashSet<String>()

    /** Adds a layer with a unique name (the test ignores case). Gives the name. */
    private fun addLayer(base: String, color: Int): String {
        val clean = DxfExporter.layerName(base).ifEmpty { "_" }
        var name = clean
        var counter = 2
        while (!usedNames.add(name.uppercase())) name = "${clean}_${counter++}"
        layers += Layer(name, color)
        return name
    }

    /** One sub-datum in export units: (h, v, third coordinate) of the plane. */
    private class Point(val subDatum: SubDatum, val h: Double, val v: Double, val z: Double, val layer: String)

    fun build(): String {
        addLayer("0", DxfExporter.DEFAULT_COLOR)
        val datumLayer = addLayer("DATUM", DxfExporter.DEFAULT_COLOR)

        // Tags as layers: the sorted tag list of the workspace gives the colors.
        val subDatums = workspace.subDatums
        val tags = subDatums.flatMap { subDatum -> subDatum.tags }.distinctBy { tag -> tag.lowercase() }
            .sortedWith(compareBy<String> { tag -> tag.lowercase() }.thenBy { tag -> tag })
        val tagLayers = HashMap<String, String>()
        val usedTags = subDatums.mapNotNull { subDatum -> subDatum.tags.firstOrNull()?.lowercase() }.toSet()
        tags.forEachIndexed { index, tag ->
            if (tag.lowercase() in usedTags) {
                val base = if (tag.isBlank()) "TAG_UNNAMED" else "TAG_$tag"
                tagLayers[tag.lowercase()] = addLayer(base, DxfExporter.tagColor(index))
            }
        }
        val untaggedLayer = if (subDatums.any { subDatum -> subDatum.tags.isEmpty() }) addLayer("TAG_UNTAGGED", DxfExporter.DEFAULT_COLOR) else null
        val points = subDatums.map { subDatum ->
            val (h, v, z) = planeCoordinates(subDatum)
            val layer = subDatum.tags.firstOrNull()?.let { tag -> tagLayers.getValue(tag.lowercase()) } ?: untaggedLayer!!
            Point(subDatum, h, v, z, layer)
        }
        val labelLayer = if (subDatums.any { subDatum -> subDatum.name.isNotBlank() }) addLayer("DATUM_LABELS", DxfExporter.DEFAULT_COLOR) else null

        // Guides: a guide of the horizontal axis gives a vertical line, and the opposite.
        val (hAxis, vAxis) = planeAxes()
        val guides = workspace.guides.mapNotNull { guide ->
            when (guide.axis) {
                hAxis -> true to guide.valueUm / umPerUnit
                vAxis -> false to vSign() * guide.valueUm / umPerUnit
                else -> null
            }
        }
        val guideLayer = if (guides.isNotEmpty()) addLayer("GUIDES", DxfExporter.DEFAULT_COLOR) else null

        // Drawing layers and shapes.
        val drawing = request.drawing
        val drawingLayers = drawing?.layers.orEmpty().sortedBy { layer -> layer.index }
        val exportedLayers = drawingLayers.filter { layer -> request.includeHiddenLayers || !layer.hidden }
        val layerNames = HashMap<Int, String>()
        for (layer in exportedLayers) {
            val base = when {
                request.measurementLayerName != null && layer.name == request.measurementLayerName -> "MEASURED"
                layer.name.isEmpty() -> "LAYER_${layer.index}"
                else -> layer.name
            }
            layerNames[layer.index] = addLayer(base, DxfExporter.DEFAULT_COLOR)
        }
        // A shape with a layer index that the layer list does not have gets the layer LAYER_<index>.
        val knownLayers = drawingLayers.map { layer -> layer.index }.toSet()
        drawing?.shapes.orEmpty().map { shape -> shape.layerIndex }.distinct().filter { index -> index !in knownLayers }
            .forEach { index -> layerNames[index] = addLayer("LAYER_$index", DxfExporter.DEFAULT_COLOR) }
        val shapes = drawing?.shapes.orEmpty().filter { shape -> shape.layerIndex in layerNames }
        fun shapeLayer(shape: DrawingShape): String = layerNames.getValue(shape.layerIndex)

        // Extents of all exported objects and the origin.
        val bounds = Bounds()
        bounds.add(0.0, 0.0)
        shapes.forEach { shape -> addShape(bounds, shape) }
        points.forEach { point -> bounds.add(point.h, point.v) }
        guides.forEach { (vertical, value) -> if (vertical) bounds.add(value, 0.0) else bounds.add(0.0, value) }
        val width = bounds.maxX - bounds.minX
        val height = bounds.maxY - bounds.minY
        val mark = max(0.02 * max(width, height), if (metric) DxfExporter.MIN_MARK_MM else DxfExporter.MIN_MARK_INCH)

        // Line type records: CENTER and HIDDEN only when a shape uses the style 4 or 5.
        val needsCenter = shapes.any { shape -> shape.style == 4 }
        val needsHidden = shapes.any { shape -> shape.style == 5 }
        val lineTypeRecords = StringBuilder()
        var lineTypeCount = 3
        if (needsCenter) {
            lineTypeCount++
            lineTypeRecords.append(lineType("CENTER", "Center ____ _ ____ _ ____ _ ____ _ ____", if (metric) listOf(31.75, -6.35, 6.35, -6.35) else listOf(1.25, -0.25, 0.25, -0.25)))
        }
        if (needsHidden) {
            lineTypeCount++
            lineTypeRecords.append(lineType("HIDDEN", "Hidden __ __ __ __ __ __ __ __ __ __", if (metric) listOf(6.35, -3.175) else listOf(0.25, -0.125)))
        }
        val layerRecords = StringBuilder()
        for (layer in layers) {
            layerRecords.tag(0, "LAYER").tag(5, handle()).tag(330, "2").tag(100, "AcDbSymbolTableRecord").tag(100, "AcDbLayerTableRecord")
                .tag(2, layer.name).tag(70, "0").tag(62, layer.color.toString()).tag(6, "Continuous").tag(370, "-3")
                .tag(390, DxfExporter.PLOT_STYLE_HANDLE)
        }

        // Entities: the origin mark, the drawing, the sub-datum points, the guides, the labels.
        val entities = StringBuilder()
        entities.entity("POINT", datumLayer, null).tag(100, "AcDbPoint").xyz(10, 0.0, 0.0, 0.0)
        entities.entity("LINE", datumLayer, null).tag(100, "AcDbLine").xyz(10, -mark, 0.0, 0.0).xyz(11, mark, 0.0, 0.0)
        entities.entity("LINE", datumLayer, null).tag(100, "AcDbLine").xyz(10, 0.0, -mark, 0.0).xyz(11, 0.0, mark, 0.0)
        for (shape in shapes) writeShape(entities, shape, shapeLayer(shape))
        for (point in points) {
            entities.entity("POINT", point.layer, null).tag(100, "AcDbPoint").xyz(10, point.h, point.v, point.z)
        }
        for ((vertical, value) in guides) {
            entities.entity("XLINE", guideLayer!!, null).tag(100, "AcDbXline")
            if (vertical) entities.xyz(10, value, 0.0, 0.0).xyz(11, 0.0, 1.0, 0.0) else entities.xyz(10, 0.0, value, 0.0).xyz(11, 1.0, 0.0, 0.0)
        }
        for (point in points) {
            val name = point.subDatum.name
            if (name.isBlank()) continue
            entities.entity("TEXT", labelLayer!!, null).tag(100, "AcDbText")
                .xyz(10, point.h + 0.6 * mark, point.v + 0.6 * mark, 0.0)
                .tag(40, DxfNumber.coordinate(mark))
                .tag(1, DxfText.encodeUnicodeEscapes(name.replace('\r', ' ').replace('\n', ' ')))
                .tag(7, "Standard")
                .tag(100, "AcDbText")
        }

        val values = mapOf(
            "EXTMIN_X" to DxfNumber.coordinate(bounds.minX),
            "EXTMIN_Y" to DxfNumber.coordinate(bounds.minY),
            "EXTMAX_X" to DxfNumber.coordinate(bounds.maxX),
            "EXTMAX_Y" to DxfNumber.coordinate(bounds.maxY),
            "LIMMAX_X" to if (metric) "420.0" else "17.0",
            "LIMMAX_Y" to if (metric) "297.0" else "11.0",
            "VIEW_X" to DxfNumber.coordinate((bounds.minX + bounds.maxX) / 2),
            "VIEW_Y" to DxfNumber.coordinate((bounds.minY + bounds.maxY) / 2),
            "VIEW_HEIGHT" to DxfNumber.coordinate(max(max(width, height) * 1.1, mark * 4)),
            "INSUNITS" to if (metric) "4" else "1",
            "MEASUREMENT" to if (metric) "1" else "0",
            "PAPER_UNITS" to if (metric) "1" else "0",
            "LTYPE_COUNT" to lineTypeCount.toString(),
            "LAYER_COUNT" to layers.size.toString(),
            "HANDSEED" to handle(),
        )
        return render(values, lineTypeRecords.toString(), layerRecords.toString(), entities.toString())
    }

    private fun lineType(name: String, description: String, pattern: List<Double>): String {
        val builder = StringBuilder()
        builder.tag(0, "LTYPE").tag(5, handle()).tag(330, "5").tag(100, "AcDbSymbolTableRecord").tag(100, "AcDbLinetypeTableRecord")
            .tag(2, name).tag(70, "0").tag(3, description).tag(72, "65").tag(73, pattern.size.toString())
            .tag(40, DxfNumber.coordinate(pattern.sumOf { length -> kotlin.math.abs(length) }))
        for (length in pattern) builder.tag(49, DxfNumber.coordinate(length)).tag(74, "0")
        return builder.toString()
    }

    /** The plane coordinates (h, v) and the third coordinate of [subDatum] in export units. */
    private fun planeCoordinates(subDatum: SubDatum): Triple<Double, Double, Double> {
        val x = (subDatum.x ?: 0.0) / umPerUnit
        val y = (subDatum.y ?: 0.0) / umPerUnit
        val z = (subDatum.z ?: 0.0) / umPerUnit
        return when (plane) {
            Plane.XY -> Triple(x, y, z)
            Plane.XZ -> Triple(x, z, y)
            Plane.YZ -> Triple(y, z, x)
            Plane.ZX -> Triple(z, -x, y)
        }
    }

    private fun planeAxes(): Pair<AxisId, AxisId> = when (plane) {
        Plane.XY -> AxisId.X to AxisId.Y
        Plane.XZ -> AxisId.X to AxisId.Z
        Plane.YZ -> AxisId.Y to AxisId.Z
        Plane.ZX -> AxisId.Z to AxisId.X
    }

    /** The sign of the v axis: −1 for the lathe plane ZX (X = −v), else 1. */
    private fun vSign(): Double = if (plane == Plane.ZX) -1.0 else 1.0

    private fun units(nm: Long): Double = nm / nmPerUnit

    private fun addShape(bounds: Bounds, shape: DrawingShape) {
        val points = shape.points.chunked(2).filter { pair -> pair.size == 2 }
        when (shape.type) {
            ShapeType.Line -> {
                bounds.add(units(shape.x1 ?: 0), units(shape.y1 ?: 0))
                bounds.add(units(shape.x2 ?: 0), units(shape.y2 ?: 0))
            }
            ShapeType.Point -> bounds.add(units(shape.x1 ?: 0), units(shape.y1 ?: 0))
            ShapeType.Circle -> {
                val r = units(shape.radius ?: 0)
                bounds.add(units(shape.x1 ?: 0) - r, units(shape.y1 ?: 0) - r)
                bounds.add(units(shape.x1 ?: 0) + r, units(shape.y1 ?: 0) + r)
            }
            ShapeType.Arc -> bounds.addArc(
                Vec(units(shape.x1 ?: 0), units(shape.y1 ?: 0)),
                units(shape.radius ?: 0),
                shape.startAngle ?: 0.0,
                shape.sweepAngle ?: 0.0,
            )
            ShapeType.Polyline, ShapeType.Bezier -> points.forEach { (x, y) -> bounds.add(units(x), units(y)) }
        }
    }

    private fun lineTypeOf(shape: DrawingShape): String? = when (shape.style) {
        4 -> "CENTER"
        5 -> "HIDDEN"
        else -> null
    }

    private fun writeShape(out: StringBuilder, shape: DrawingShape, layer: String) {
        val lineType = lineTypeOf(shape)
        val points = shape.points.chunked(2).filter { pair -> pair.size == 2 }
        when (shape.type) {
            ShapeType.Line -> {
                if (shape.x1 == null || shape.y1 == null || shape.x2 == null || shape.y2 == null) return
                out.entity("LINE", layer, lineType).tag(100, "AcDbLine")
                    .xyz(10, units(shape.x1!!), units(shape.y1!!), 0.0)
                    .xyz(11, units(shape.x2!!), units(shape.y2!!), 0.0)
            }
            ShapeType.Point -> {
                if (shape.x1 == null || shape.y1 == null) return
                out.entity("POINT", layer, lineType).tag(100, "AcDbPoint").xyz(10, units(shape.x1!!), units(shape.y1!!), 0.0)
            }
            ShapeType.Circle -> {
                val radius = shape.radius ?: return
                if (shape.x1 == null || shape.y1 == null) return
                out.entity("CIRCLE", layer, lineType).tag(100, "AcDbCircle")
                    .xyz(10, units(shape.x1!!), units(shape.y1!!), 0.0).tag(40, DxfNumber.coordinate(units(radius)))
            }
            ShapeType.Arc -> {
                val radius = shape.radius ?: return
                val start = shape.startAngle ?: return
                val sweep = shape.sweepAngle ?: return
                if (shape.x1 == null || shape.y1 == null) return
                // A negative sweep swaps the start and the end.
                val from = if (sweep < 0) start + sweep else start
                val to = if (sweep < 0) start else start + sweep
                out.entity("ARC", layer, lineType).tag(100, "AcDbCircle")
                    .xyz(10, units(shape.x1!!), units(shape.y1!!), 0.0).tag(40, DxfNumber.coordinate(units(radius)))
                    .tag(100, "AcDbArc").tag(50, DxfNumber.coordinate(degrees(from))).tag(51, DxfNumber.coordinate(degrees(to)))
            }
            ShapeType.Polyline -> {
                if (points.size < 2) return
                out.entity("LWPOLYLINE", layer, lineType).tag(100, "AcDbPolyline")
                    .tag(90, points.size.toString()).tag(70, if (shape.closed) "1" else "0")
                points.forEachIndexed { index, (x, y) ->
                    out.tag(10, DxfNumber.coordinate(units(x))).tag(20, DxfNumber.coordinate(units(y)))
                    val bulge = shape.bulges[index] ?: 0.0
                    if (bulge != 0.0) out.tag(42, DxfNumber.bulge(bulge))
                }
            }
            ShapeType.Bezier -> {
                if (points.size < 4 || (points.size - 1) % 3 != 0) return
                val segments = (points.size - 1) / 3
                out.entity("SPLINE", layer, lineType).tag(100, "AcDbSpline")
                    .tag(210, "0.0").tag(220, "0.0").tag(230, "1.0")
                    .tag(70, "8").tag(71, "3").tag(72, (points.size + 4).toString()).tag(73, points.size.toString()).tag(74, "0")
                val knots = ArrayList<Int>()
                repeat(4) { knots += 0 }
                for (segment in 1 until segments) repeat(3) { knots += segment }
                repeat(4) { knots += segments }
                for (knot in knots) out.tag(40, DxfNumber.coordinate(knot.toDouble()))
                for ((x, y) in points) out.xyz(10, units(x), units(y), 0.0)
            }
        }
    }

    private fun degrees(radians: Double): Double {
        var value = (radians * 180.0 / PI) % 360.0
        if (value < 0) value += 360.0
        if (value >= 360.0) value -= 360.0
        return value
    }

    private fun StringBuilder.tag(code: Int, value: String): StringBuilder {
        append(code.toString().padStart(3)).append("\r\n").append(value).append("\r\n")
        return this
    }

    private fun StringBuilder.xyz(code: Int, x: Double, y: Double, z: Double): StringBuilder =
        tag(code, DxfNumber.coordinate(x)).tag(code + 10, DxfNumber.coordinate(y)).tag(code + 20, DxfNumber.coordinate(z))

    private fun StringBuilder.entity(type: String, layer: String, lineType: String?): StringBuilder {
        tag(0, type).tag(5, handle()).tag(330, DxfExporter.MODEL_SPACE_HANDLE).tag(100, "AcDbEntity").tag(8, layer)
        if (lineType != null) tag(6, lineType)
        return this
    }

    private fun render(values: Map<String, String>, lineTypes: String, layerRecords: String, entities: String): String {
        val out = StringBuilder(DXF_TEMPLATE.length * 2 + entities.length)
        for (line in DXF_TEMPLATE.lineSequence()) {
            when (line) {
                "__RECORDS_LTYPE__" -> out.append(lineTypes)
                "__RECORDS_LAYER__" -> out.append(layerRecords)
                "__ENTITIES__" -> out.append(entities)
                else -> {
                    val separator = line.indexOf('|')
                    val code = line.substring(0, separator).toInt()
                    var value = line.substring(separator + 1)
                    if (value.startsWith("{") && value.endsWith("}")) value = values.getValue(value.substring(1, value.length - 1))
                    out.tag(code, value)
                }
            }
        }
        return out.toString()
    }
}
