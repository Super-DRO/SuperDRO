package org.superdro.core.drawing.dxf

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Instant
import org.superdro.core.drawing.ConvertResult
import org.superdro.core.drawing.ConvertStatus
import org.superdro.core.drawing.DrawingFailure
import org.superdro.core.drawing.DrawingProblem
import org.superdro.core.drawing.DrawingProblemCodes
import org.superdro.core.drawing.DrawingTypeTest
import org.superdro.core.drawing.DxfLimits
import org.superdro.core.drawing.NmPoint
import org.superdro.core.drawing.ProblemSeverity
import org.superdro.core.drawing.WireframeArc
import org.superdro.core.drawing.WireframeBezier
import org.superdro.core.drawing.WireframeCircle
import org.superdro.core.drawing.WireframeDocument
import org.superdro.core.drawing.WireframeEntity
import org.superdro.core.drawing.WireframeExtents
import org.superdro.core.drawing.WireframeLayer
import org.superdro.core.drawing.WireframeLine
import org.superdro.core.drawing.WireframePoint
import org.superdro.core.drawing.WireframePolyline
import org.superdro.core.drawing.extentsOf
import org.superdro.core.drawing.formatUtc
import org.superdro.core.drawing.geometry.Affine
import org.superdro.core.drawing.geometry.Conic
import org.superdro.core.drawing.geometry.Cubic
import org.superdro.core.drawing.geometry.Nurbs
import org.superdro.core.drawing.geometry.Vec
import org.superdro.core.drawing.geometry.bulgeArc
import org.superdro.core.drawing.geometry.interpolateFitPoints
import org.superdro.core.drawing.geometry.lineCubic
import org.superdro.core.drawing.geometry.normalizeAngle
import org.superdro.core.util.Clock
import org.superdro.core.util.MonotonicClock

/**
 * The unit of a drawing and its size in nm. [insUnits] is the `$INSUNITS` code of the DXF
 * reference.
 */
enum class DrawingUnit(val insUnits: Int, val nmPerUnit: Double) {
    Inch(1, 25_400_000.0),
    Foot(2, 304_800_000.0),
    Mile(3, 1_609_344_000_000.0),
    Millimeter(4, 1_000_000.0),
    Centimeter(5, 10_000_000.0),
    Meter(6, 1_000_000_000.0),
    Kilometer(7, 1.0e12),
    Microinch(8, 25.4),
    Mil(9, 25_400.0),
    Yard(10, 914_400_000.0),
    Angstrom(11, 0.1),
    Nanometer(12, 1.0),
    Micrometer(13, 1000.0),
    Decimeter(14, 100_000_000.0),
    Decameter(15, 1.0e10),
    Hectometer(16, 1.0e11),
    Gigameter(17, 1.0e18),
    UsSurveyFoot(21, 1.2e12 / 3937.0),
    UsSurveyInch(22, 1.0e11 / 3937.0),
    UsSurveyYard(23, 3.6e12 / 3937.0),
    UsSurveyMile(24, 6.336e15 / 3937.0),
    ;

    companion object {
        /** Gives the unit of the `$INSUNITS` [code], or null for 0 (no unit) and the codes that SuperDRO does not use. */
        fun fromInsUnits(code: Long?): DrawingUnit? = entries.firstOrNull { unit -> unit.insUnits.toLong() == code }
    }
}

/**
 * The settings of the DXF import.
 *
 * @property assumedUnit The unit for a file with no `$INSUNITS` (or 0), or null. With null, the
 *   converter uses `$MEASUREMENT` (0 gives inch) or else mm, and adds the warning `UNITS_ASSUMED`.
 */
data class DxfImportSettings(
    val limits: DxfLimits = DxfLimits(),
    val assumedUnit: DrawingUnit? = null,
)

/**
 * The DXF converter on the device: ASCII DXF and binary DXF to a wireframe document in
 * integer nm. The two tokenizers give the same tag stream, and all steps after the tokenizer
 * are shared.
 */
object DxfConverter {

    /**
     * Converts the DXF file [bytes].
     *
     * @param clock The time source of the parse time limit.
     * @param now The time of `createdAt`.
     * @return [ConvertStatus.Ready] with the wireframe document and the warnings, or
     *   [ConvertStatus.Failed] with one error and the warnings until the error.
     */
    fun convert(
        bytes: ByteArray,
        settings: DxfImportSettings = DxfImportSettings(),
        clock: Clock = MonotonicClock(),
        now: Instant = kotlin.time.Clock.System.now(),
    ): ConvertResult {
        val warnings = Warnings()
        return try {
            val limits = settings.limits
            if (limits.curveToleranceNm <= 0) {
                throw DrawingFailure(
                    DrawingProblemCodes.INVALID_CURVE_TOLERANCE,
                    "the curve tolerance must be more than 0 nm, got ${limits.curveToleranceNm} nm",
                )
            }
            if (bytes.size > limits.maxSourceBytes) {
                throw DrawingFailure(
                    DrawingProblemCodes.SOURCE_TOO_LARGE,
                    "the file has ${bytes.size} bytes, more than the limit of ${limits.maxSourceBytes} bytes",
                )
            }
            if (!DxfLimits.isBinaryDxf(bytes) && !DrawingTypeTest.isAsciiDxf(bytes)) {
                throw DrawingFailure(DrawingProblemCodes.UNSUPPORTED_FORMAT, "not a DXF file")
            }
            val budget = ParseBudget(clock, limits.maxParseTimeMs)
            val stream = DxfTokenizer.tokenize(bytes, budget)
            stream.unknownCodePage?.let { codePage ->
                warnings.add(
                    DrawingProblemCodes.CODE_PAGE_UNKNOWN,
                    "DXF \$DWGCODEPAGE $codePage is not known; the strings are read as ANSI_1252",
                )
            }
            val document = DxfReader(stream.tags, budget).read()
            budget.check()
            val wireframe = DxfToWireframe(document, settings, budget, warnings, now).convert()
            budget.check()
            ConvertResult(ConvertStatus.Ready, wireframe, warnings.list())
        } catch (failure: DrawingFailure) {
            ConvertResult(ConvertStatus.Failed, null, warnings.list(), listOf(failure.problem))
        }
    }

    /** The line types of the stroke style 4. */
    val CENTER_LINE_TYPES: Set<String> = setOf("CENTER", "CENTER2", "CENTERX2", "PHANTOM", "PHANTOM2", "PHANTOMX2")

    /** The line types of the stroke style 5. */
    val HIDDEN_LINE_TYPES: Set<String> = setOf("HIDDEN", "HIDDEN2", "HIDDENX2", "DASHED", "DASHED2", "DASHEDX2")

    /** Gives the stroke style of the line type [name]: 4, 5, or 1 (NORMAL). */
    fun styleOfLineType(name: String): Int = when (name.trim().uppercase()) {
        in CENTER_LINE_TYPES -> 4
        in HIDDEN_LINE_TYPES -> 5
        else -> 1
    }
}

/** The warnings of one conversion, in the order of the first event. */
internal class Warnings {
    private val items = ArrayList<DrawingProblem>()

    fun add(code: String, message: String) {
        items += DrawingProblem(code, message, ProblemSeverity.Warning)
    }

    fun list(): List<DrawingProblem> = items.toList()
}

/** The conversion of a [DxfDocument] to a [WireframeDocument]. */
internal class DxfToWireframe(
    private val document: DxfDocument,
    private val settings: DxfImportSettings,
    private val budget: ParseBudget,
    private val warnings: Warnings,
    private val now: Instant,
) {
    private val limits = settings.limits
    private val tolerance = limits.curveToleranceNm.toDouble()

    /** One output entity with its layer name. The layer index comes at the end. */
    private class Emitted(val layerName: String, val style: Int, val entity: WireframeEntity)

    private val emitted = ArrayList<Emitted>()
    private var bezierSegments = 0L

    // Counters of the aggregated warnings, in the order of the first event.
    private val dropped = LinkedHashMap<String, Int>()
    private val unsupported = LinkedHashMap<String, Int>()
    private val invalid = LinkedHashMap<String, Int>()
    private val missingBlocks = LinkedHashSet<String>()
    private val tooComplex = LinkedHashMap<String, Int>()
    private var toleranceMisses = 0
    private var hatchEdgePaths = 0
    private var ownerDisagreements = 0
    private var longPolylines = 0
    private var longestPolyline = 0

    fun convert(): WireframeDocument {
        val unit = selectUnit()
        val root = Affine.scale(unit, unit)
        val modelSpace = document.blocks.values.firstOrNull { block -> block.isModelSpace }
        if (modelSpace != null && modelSpace.entities.isNotEmpty()) {
            warnings.add(
                DrawingProblemCodes.MODEL_SPACE_BLOCK_IGNORED,
                "the *Model_Space block has ${modelSpace.entities.size} entities; SuperDRO reads the ENTITIES section only",
            )
        }
        val modelEntities = document.entities.filter { entity -> !isPaperSpace(entity) }
        if (ownerDisagreements > 0) {
            warnings.add(
                DrawingProblemCodes.OWNER_FLAG_DISAGREEMENT,
                "$ownerDisagreements entities have an owner and a paper space flag (67) that do not agree; the owner is used",
            )
        }
        precount(modelEntities)
        walk(modelEntities, root, 0, insertLayer = null, blockLineType = CONTINUOUS)
        if (emitted.size > limits.maxEntities) {
            throw DrawingFailure(
                DrawingProblemCodes.ENTITY_LIMIT_EXCEEDED,
                "${emitted.size} entities exceeds the ${limits.maxEntities} cap",
            )
        }
        addWarnings()
        return build()
    }

    // ---- Units ---------------------------------------------------------------------------

    private fun selectUnit(): Double {
        val insUnits = document.headerValue("\$INSUNITS", 70)?.int
        DrawingUnit.fromInsUnits(insUnits)?.let { unit -> return unit.nmPerUnit }
        settings.assumedUnit?.let { unit -> return unit.nmPerUnit }
        val measurement = document.headerValue("\$MEASUREMENT", 70)?.int
        return if (measurement == 0L) {
            warnings.add(
                DrawingProblemCodes.UNITS_ASSUMED,
                "DXF \$INSUNITS not set and no unit assumption supplied; \$MEASUREMENT is 0 (imperial), defaulting to inch",
            )
            DrawingUnit.Inch.nmPerUnit
        } else {
            warnings.add(
                DrawingProblemCodes.UNITS_ASSUMED,
                "DXF \$INSUNITS not set and no unit assumption supplied; defaulting to mm",
            )
            DrawingUnit.Millimeter.nmPerUnit
        }
    }

    // ---- Paper space --------------------------------------------------------------------

    private fun isPaperSpace(entity: DxfEntity): Boolean {
        val ownerName = entity.owner?.let { owner -> document.blockRecords[owner.uppercase()] }?.uppercase()
        val ownerPaper = ownerName != null && ownerName.startsWith("*PAPER_SPACE")
        val ownerModel = ownerName == "*MODEL_SPACE"
        if ((ownerModel && entity.paperSpace) || (ownerPaper && !entity.paperSpace)) ownerDisagreements++
        return when {
            ownerPaper -> true
            ownerModel -> false
            else -> entity.paperSpace
        }
    }

    // ---- Count and the test of the block levels ---------------------------------------------------------

    private class BlockInfo(val count: Long, val height: Int)

    private val blockInfo = HashMap<String, BlockInfo>()

    private fun precount(entities: List<DxfEntity>) {
        var total = 0L
        for (entity in entities) {
            total = saturatingAdd(total, countOf(entity, ArrayList(), 0))
        }
        if (total > limits.maxEntities) {
            throw DrawingFailure(
                DrawingProblemCodes.ENTITY_LIMIT_EXCEEDED,
                "$total entities exceeds the ${limits.maxEntities} cap",
            )
        }
    }

    /** The number of output entities of [entity]. [stack] has the names of the blocks above it. */
    private fun countOf(entity: DxfEntity, stack: MutableList<String>, depth: Int): Long {
        budget.tick()
        return when (entity.type) {
            in GEOMETRY_TYPES -> 1L
            "POLYLINE" -> if (entity.int(70) and MESH_FLAGS != 0L) 0L else 1L
            "INSERT" -> {
                val block = document.block(entity.string(2).orEmpty()) ?: return 0L
                if (block.isLayout || block.isExternal) return 0L
                val info = infoOf(block, stack, depth + 1)
                val copies = saturatingMultiply(arrayCount(entity, 70), arrayCount(entity, 71))
                saturatingMultiply(copies, info.count)
            }
            else -> 0L
        }
    }

    private fun infoOf(block: DxfBlock, stack: MutableList<String>, level: Int): BlockInfo {
        val key = block.name.uppercase()
        if (level > limits.maxBlockLevels || key in stack) nestingFailure(block.name)
        blockInfo[key]?.let { info ->
            if (level - 1 + info.height > limits.maxBlockLevels) nestingFailure(block.name)
            return info
        }
        stack += key
        var count = 0L
        var height = 1
        for (child in block.entities) {
            count = saturatingAdd(count, countOf(child, stack, level))
            if (child.type == "INSERT") {
                val inner = document.block(child.string(2).orEmpty())
                if (inner != null && !inner.isLayout && !inner.isExternal) {
                    height = maxOf(height, 1 + (blockInfo[inner.name.uppercase()]?.height ?: 1))
                }
            }
        }
        stack.removeAt(stack.size - 1)
        val info = BlockInfo(count, height)
        blockInfo[key] = info
        if (level - 1 + height > limits.maxBlockLevels) nestingFailure(block.name)
        return info
    }

    private fun nestingFailure(name: String): Nothing = throw DrawingFailure(
        DrawingProblemCodes.NESTING_DEPTH_EXCEEDED,
        "the block $name is nested more than ${limits.maxBlockLevels} levels deep",
    )

    private fun arrayCount(entity: DxfEntity, code: Int): Long = entity.int(code, 1L).coerceAtLeast(1L)

    // ---- The walk -----------------------------------------------------------------------

    /**
     * Converts [entities] with the map [transform] (block, OCS, and unit to nm).
     *
     * @param insertLayer The layer of the INSERT above, for the entities on layer `0`.
     * @param blockLineType The line type of the INSERT above, for the entities with `BYBLOCK`.
     */
    private fun walk(entities: List<DxfEntity>, transform: Affine, depth: Int, insertLayer: String?, blockLineType: String) {
        for (entity in entities) {
            budget.tick()
            val layer = if (insertLayer != null && entity.layer.trim() == "0") insertLayer else entity.layer
            val lineType = resolveLineType(entity.lineType, layer, blockLineType)
            when (entity.type) {
                "LINE" -> line(entity, transform, layer, lineType)
                "POINT" -> point(entity, transform, layer, lineType)
                "CIRCLE" -> circle(entity, transform, layer, lineType)
                "ARC" -> arc(entity, transform, layer, lineType)
                "LWPOLYLINE" -> lwPolyline(entity, transform, layer, lineType)
                "POLYLINE" -> polyline(entity, transform, layer, lineType)
                "ELLIPSE" -> ellipse(entity, transform, layer, lineType)
                "SPLINE" -> spline(entity, transform, layer, lineType)
                "HATCH" -> hatch(entity, transform, layer, lineType)
                "INSERT" -> insert(entity, transform, depth, layer, lineType)
                in IGNORED_TYPES -> Unit
                in DROPPED_TYPES -> dropped.increment(entity.type)
                else -> unsupported.increment(entity.type)
            }
        }
    }

    /** Gives a concrete line type name: BYLAYER gives the line type of the layer, BYBLOCK gives [blockLineType]. */
    private fun resolveLineType(lineType: String?, layer: String, blockLineType: String): String {
        val name = lineType?.trim().orEmpty()
        return when {
            name.isEmpty() || name.equals("BYLAYER", ignoreCase = true) -> document.layer(layer)?.lineType ?: CONTINUOUS
            name.equals("BYBLOCK", ignoreCase = true) -> blockLineType
            else -> name
        }
    }

    private fun styleOf(lineType: String): Int =
        if (document.hasLineType(lineType)) DxfConverter.styleOfLineType(lineType) else 1

    private fun emit(layer: String, lineType: String, entity: WireframeEntity) {
        emitted += Emitted(layer, styleOf(lineType), entity)
    }

    private fun DxfEntity.extrusion(): Triple<Double, Double, Double> = Triple(double(210, 0.0), double(220, 0.0), double(230, 1.0))

    private fun ocs(entity: DxfEntity, elevation: Double): Affine {
        val (nx, ny, nz) = entity.extrusion()
        return Affine.ocs(nx, ny, nz, elevation)
    }

    private fun line(entity: DxfEntity, map: Affine, layer: String, lineType: String) {
        val start = map.apply(entity.double(10), entity.double(20))
        val end = map.apply(entity.double(11), entity.double(21))
        emit(layer, lineType, WireframeLine(nm(start), nm(end)))
    }

    private fun point(entity: DxfEntity, map: Affine, layer: String, lineType: String) {
        emit(layer, lineType, WireframePoint(nm(map.apply(entity.double(10), entity.double(20)))))
    }

    private fun circle(entity: DxfEntity, map: Affine, layer: String, lineType: String) {
        val local = map * ocs(entity, entity.double(30))
        circleOrArc(local, entity.double(10), entity.double(20), entity.double(40), 0.0, 2 * PI, true, "CIRCLE", layer, lineType)
    }

    private fun arc(entity: DxfEntity, map: Affine, layer: String, lineType: String) {
        val local = map * ocs(entity, entity.double(30))
        val startDegrees = entity.double(50)
        checkFinite(startDegrees, entity.double(51))
        var sweepDegrees = (entity.double(51) - startDegrees) % 360.0
        if (sweepDegrees <= 0) sweepDegrees += 360.0
        val start = startDegrees * PI / 180.0
        val sweep = sweepDegrees * PI / 180.0
        val full = sweep <= ANGLE_EPSILON || sweep >= 2 * PI - ANGLE_EPSILON
        circleOrArc(local, entity.double(10), entity.double(20), entity.double(40), start, if (full) 2 * PI else sweep, full, "ARC", layer, lineType)
    }

    private fun circleOrArc(
        map: Affine,
        cx: Double,
        cy: Double,
        radius: Double,
        start: Double,
        sweep: Double,
        full: Boolean,
        type: String,
        layer: String,
        lineType: String,
    ) {
        checkFinite(cx, cy, radius, start, sweep)
        if (radius <= 0.0) {
            invalid.increment("$type with a radius of 0 or less")
            return
        }
        if (map.isSimilarity) {
            val center = nm(map.apply(cx, cy))
            val r = nmValue(radius * map.scale)
            if (r <= 0L) {
                invalid.increment("$type with a radius of 0 nm")
                return
            }
            if (full) {
                emit(layer, lineType, WireframeCircle(center, r))
            } else {
                val rotation = map.rotation
                val mappedStart = if (map.determinant > 0) start + rotation else rotation - start - sweep
                emit(layer, lineType, WireframeArc(center, r, normalizeAngle(mappedStart), sweep))
            }
        } else {
            val conic = Conic(Vec(cx, cy), Vec(radius, 0.0), Vec(0.0, radius), start, sweep).mapped(map)
            conicBezier(conic, full, type, layer, lineType)
        }
    }

    private fun conicBezier(conic: Conic, closed: Boolean, type: String, layer: String, lineType: String) {
        val count = conic.segmentCount(tolerance)
        if (3 * count + 1 > limits.maxEllipseReferencePoints) {
            tooComplex.increment("$type entities that need more than ${limits.maxEllipseReferencePoints} reference points (check units)")
            return
        }
        bezier(conic.cubics(count), closed, layer, lineType)
    }

    private fun lwPolyline(entity: DxfEntity, map: Affine, layer: String, lineType: String) {
        val points = ArrayList<Vec>()
        val bulges = ArrayList<Double>()
        var x = 0.0
        var pending = false
        for (tag in entity.tags) {
            when (tag.code) {
                10 -> {
                    if (pending) {
                        points += Vec(x, 0.0)
                        bulges += 0.0
                    }
                    x = tag.number
                    pending = true
                }
                20 -> if (pending) {
                    points += Vec(x, tag.number)
                    bulges += 0.0
                    pending = false
                }
                42 -> if (bulges.isNotEmpty()) bulges[bulges.size - 1] = tag.number
            }
        }
        if (pending) {
            points += Vec(x, 0.0)
            bulges += 0.0
        }
        val local = map * ocs(entity, entity.double(38))
        polylineShape(local, points, bulges, entity.int(70) and 1L != 0L, layer, lineType)
    }

    private fun polyline(entity: DxfEntity, map: Affine, layer: String, lineType: String) {
        val flags = entity.int(70)
        if (flags and MESH_FLAGS != 0L) {
            dropped.increment("POLYLINE mesh")
            return
        }
        val is3d = flags and 8L != 0L
        val points = ArrayList<Vec>()
        val bulges = ArrayList<Double>()
        for (vertex in entity.children) {
            val vertexFlags = vertex.int(70)
            if (vertexFlags and 16L != 0L) continue
            points += Vec(vertex.double(10), vertex.double(20))
            bulges += if (is3d) 0.0 else vertex.double(42)
        }
        val local = if (is3d) map else map * ocs(entity, entity.double(30))
        polylineShape(local, points, bulges, flags and 1L != 0L, layer, lineType)
    }

    private fun polylineShape(
        map: Affine,
        points: List<Vec>,
        bulges: MutableList<Double>,
        closed: Boolean,
        layer: String,
        lineType: String,
    ) {
        points.forEach { point -> checkFinite(point.x, point.y) }
        bulges.forEach { bulge -> checkFinite(bulge) }
        if (points.size < 2) {
            invalid.increment("polylines with fewer than 2 points")
            return
        }
        if (!closed) bulges[bulges.size - 1] = 0.0
        if (points.size > limits.maxPolylinePoints) {
            throw DrawingFailure(
                DrawingProblemCodes.POLYLINE_POINTS_EXCEEDED,
                "a polyline with ${points.size} points exceeds the ${limits.maxPolylinePoints} cap",
            )
        }
        if (points.size > limits.polylineWarningPoints) {
            longPolylines++
            longestPolyline = maxOf(longestPolyline, points.size)
        }
        val hasBulges = bulges.any { bulge -> bulge != 0.0 }
        if (map.isSimilarity || !hasBulges) {
            val sign = if (map.determinant < 0) -1.0 else 1.0
            val mapped = points.map { point -> nm(map.apply(point)) }
            val bulgeMap = LinkedHashMap<Int, Double>()
            bulges.forEachIndexed { index, bulge -> if (bulge != 0.0) bulgeMap[index] = bulge * sign }
            emit(layer, lineType, WireframePolyline(mapped, closed, bulgeMap))
        } else {
            val cubics = ArrayList<Cubic>()
            val segmentCount = if (closed) points.size else points.size - 1
            for (index in 0 until segmentCount) {
                val start = points[index]
                val end = points[(index + 1) % points.size]
                val bulge = bulges[index]
                if (bulge == 0.0 || start == end) {
                    cubics += lineCubic(map.apply(start), map.apply(end))
                } else {
                    val conic = bulgeArc(start, end, bulge).mapped(map)
                    val count = conic.segmentCount(tolerance)
                    if (3 * count + 1 > limits.maxEllipseReferencePoints) {
                        tooComplex.increment("polyline arcs that need more than ${limits.maxEllipseReferencePoints} reference points (check units)")
                        return
                    }
                    cubics += conic.cubics(count)
                }
            }
            bezier(cubics, closed, layer, lineType)
        }
    }

    private fun ellipse(entity: DxfEntity, map: Affine, layer: String, lineType: String) {
        val (nx, ny, nz) = entity.extrusion()
        val mx = entity.double(11)
        val my = entity.double(21)
        val mz = entity.double(31)
        val ratio = entity.double(40, 1.0)
        val startParameter = entity.double(41, 0.0)
        val endParameter = entity.double(42, 2 * PI)
        checkFinite(entity.double(10), entity.double(20), mx, my, mz, ratio, startParameter, endParameter)
        val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).takeIf { size -> size > 0.0 } ?: 1.0
        val n = Triple(nx / length, ny / length, nz / length)
        // The minor axis: ratio × (N × major).
        val minorX = ratio * (n.second * mz - n.third * my)
        val minorY = ratio * (n.third * mx - n.first * mz)
        if (ratio <= 0.0 || (mx == 0.0 && my == 0.0)) {
            invalid.increment("ELLIPSE with no size in the XY plane")
            return
        }
        var sweep = (endParameter - startParameter) % (2 * PI)
        if (sweep <= 0) sweep += 2 * PI
        val full = sweep >= 2 * PI - ANGLE_EPSILON
        val conic = Conic(
            Vec(entity.double(10), entity.double(20)),
            Vec(mx, my),
            Vec(minorX, minorY),
            if (full) 0.0 else startParameter,
            if (full) 2 * PI else sweep,
        ).mapped(map)
        conicBezier(conic, full, "ELLIPSE", layer, lineType)
    }

    private fun spline(entity: DxfEntity, map: Affine, layer: String, lineType: String) {
        val flags = entity.int(70)
        val degree = entity.int(71, 3L).toInt()
        val knots = entity.all(40).map { tag -> tag.number }
        val weights = entity.all(41).map { tag -> tag.number }
        val control = pairs(entity, 10, 20)
        val fit = pairs(entity, 11, 21)
        knots.forEach { value -> checkFinite(value) }
        weights.forEach { value -> checkFinite(value) }
        control.forEach { point -> checkFinite(point.x, point.y) }
        fit.forEach { point -> checkFinite(point.x, point.y) }
        val closedFlag = flags and 3L != 0L
        if (control.size > limits.maxSplineControlPoints || fit.size > limits.maxSplineControlPoints) {
            tooComplex.increment("SPLINE entities with more than ${limits.maxSplineControlPoints} control points or fit points")
            return
        }
        val budgetLeft = (limits.maxBezierSegments - bezierSegments).coerceAtLeast(0L).toInt()
        if (degree >= 1 && control.size >= degree + 1 && knots.size == control.size + degree + 1 &&
            knots.zipWithNext().all { (a, b) -> b >= a } &&
            (weights.isEmpty() || weights.size == control.size) && weights.all { weight -> weight > 0.0 }
        ) {
            val nurbs = Nurbs(degree, knots.toDoubleArray(), control.map { point -> map.apply(point) }, weights.takeIf { list -> list.isNotEmpty() }?.toDoubleArray())
            val approximation = nurbs.toCubics(tolerance, budgetLeft)
            if (!approximation.toleranceMet) toleranceMisses++
            if (approximation.cubics.isEmpty()) {
                invalid.increment("SPLINE entities with an empty knot range")
                return
            }
            bezier(approximation.cubics, closedFlag, layer, lineType)
        } else if (control.isEmpty() && fit.size >= 2) {
            val startTangent = entity.first(12)?.let { Vec(entity.double(12), entity.double(22)) }
            val endTangent = entity.first(13)?.let { Vec(entity.double(13), entity.double(23)) }
            val points = if (flags and 1L != 0L && fit.first() != fit.last()) fit + fit.first() else fit
            val cubics = interpolateFitPoints(points, startTangent, endTangent).map { cubic ->
                Cubic(map.apply(cubic.p0), map.apply(cubic.p1), map.apply(cubic.p2), map.apply(cubic.p3))
            }
            if (cubics.isEmpty()) {
                invalid.increment("SPLINE entities with fewer than 2 different fit points")
                return
            }
            bezier(cubics, flags and 1L != 0L, layer, lineType)
        } else {
            invalid.increment("SPLINE entities with a bad knot vector or too few control points")
        }
    }

    /** The points of the pairs of codes ([xCode], [yCode]) in tag order. */
    private fun pairs(entity: DxfEntity, xCode: Int, yCode: Int): List<Vec> {
        val points = ArrayList<Vec>()
        var x = 0.0
        var pending = false
        for (tag in entity.tags) {
            when (tag.code) {
                xCode -> {
                    if (pending) points += Vec(x, 0.0)
                    x = tag.number
                    pending = true
                }
                yCode -> if (pending) {
                    points += Vec(x, tag.number)
                    pending = false
                }
            }
        }
        if (pending) points += Vec(x, 0.0)
        return points
    }

    private fun hatch(entity: DxfEntity, map: Affine, layer: String, lineType: String) {
        val local = map * ocs(entity, entity.double(30))
        val tags = entity.tags
        var index = tags.indexOfFirst { tag -> tag.code == 91 }
        if (index < 0) return
        val pathCount = tags[index].int
        index++
        try {
            repeat(pathCount.toInt().coerceAtLeast(0)) {
                val flags = expect(tags, index++, 92).int
                if (flags and 2L != 0L) {
                    val hasBulge = expect(tags, index++, 72).int != 0L
                    // Code 73 is the closed flag. A HATCH boundary is always closed.
                    expect(tags, index++, 73)
                    val vertexCount = expect(tags, index++, 93).int.toInt()
                    val points = ArrayList<Vec>()
                    val bulges = ArrayList<Double>()
                    repeat(vertexCount) {
                        val x = expect(tags, index++, 10).number
                        val y = expect(tags, index++, 20).number
                        var bulge = 0.0
                        if (tags.getOrNull(index)?.code == 42) bulge = tags[index++].number
                        points += Vec(x, y)
                        bulges += if (hasBulge) bulge else 0.0
                    }
                    polylineShape(local, points, bulges, true, layer, lineType)
                } else {
                    hatchEdgePaths++
                    val edgeCount = expect(tags, index++, 93).int.toInt()
                    repeat(edgeCount) { index = skipEdge(tags, index) }
                }
                if (tags.getOrNull(index)?.code == 97) {
                    val sources = tags[index++].int.toInt()
                    repeat(sources) { if (tags.getOrNull(index)?.code == 330) index++ }
                }
            }
        } catch (_: HatchStructure) {
            invalid.increment("HATCH entities with a bad boundary structure")
        }
    }

    private class HatchStructure : RuntimeException()

    private fun expect(tags: List<DxfTag>, index: Int, code: Int): DxfTag {
        val tag = tags.getOrNull(index)
        if (tag == null || tag.code != code) throw HatchStructure()
        return tag
    }

    /** Skips one edge of a HATCH edge boundary. Gives the index after the edge. */
    private fun skipEdge(tags: List<DxfTag>, start: Int): Int {
        var index = start
        val type = expect(tags, index++, 72).int
        val codes = when (type) {
            1L -> intArrayOf(10, 20, 11, 21)
            2L -> intArrayOf(10, 20, 40, 50, 51, 73)
            3L -> intArrayOf(10, 20, 11, 21, 40, 50, 51, 73)
            4L -> null
            else -> throw HatchStructure()
        }
        if (codes != null) {
            for (code in codes) expect(tags, index++, code)
            return index
        }
        expect(tags, index++, 94)
        val rational = expect(tags, index++, 73).int != 0L
        expect(tags, index++, 74)
        val knotCount = expect(tags, index++, 95).int.toInt()
        val controlCount = expect(tags, index++, 96).int.toInt()
        repeat(knotCount) { expect(tags, index++, 40) }
        repeat(controlCount) {
            expect(tags, index++, 10)
            expect(tags, index++, 20)
            if (rational && tags.getOrNull(index)?.code == 42) index++
        }
        if (tags.getOrNull(index)?.code == 97) {
            val fitCount = tags[index++].int.toInt()
            repeat(fitCount) {
                expect(tags, index++, 11)
                expect(tags, index++, 21)
            }
            if (tags.getOrNull(index)?.code == 12) index += 2
            if (tags.getOrNull(index)?.code == 13) index += 2
        }
        return index
    }

    private fun insert(entity: DxfEntity, map: Affine, depth: Int, layer: String, lineType: String) {
        val name = entity.string(2).orEmpty()
        val block = document.block(name)
        if (block == null) {
            missingBlocks += name
            return
        }
        if (block.isLayout || block.isExternal) return
        if (depth + 1 > limits.maxBlockLevels) nestingFailure(block.name)
        val x = entity.double(10)
        val y = entity.double(20)
        val sx = entity.double(41, 1.0)
        val sy = entity.double(42, 1.0)
        val rotation = entity.double(50, 0.0) * PI / 180.0
        val columns = arrayCount(entity, 70)
        val rows = arrayCount(entity, 71)
        val columnSpacing = entity.double(44, 0.0)
        val rowSpacing = entity.double(45, 0.0)
        checkFinite(x, y, sx, sy, rotation, columnSpacing, rowSpacing, block.baseX, block.baseY)
        val ocs = ocs(entity, entity.double(30))
        val placement = map * ocs * Affine.translate(x, y) * Affine.rotate(rotation)
        val scale = Affine.scale(sx, sy) * Affine.translate(-block.baseX, -block.baseY)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val offset = Affine.translate(column * columnSpacing, row * rowSpacing)
                walk(block.entities, placement * offset * scale, depth + 1, layer, lineType)
            }
        }
    }

    // ---- Bézier chains ------------------------------------------------------------------

    private fun bezier(cubics: List<Cubic>, closed: Boolean, layer: String, lineType: String) {
        if (cubics.isEmpty()) return
        var chain = ArrayList<NmPoint>()
        fun flush(close: Boolean) {
            if (chain.size >= 4) {
                if (close && near(chain.first(), chain.last())) chain[chain.size - 1] = chain.first()
                val isClosed = close && chain.first() == chain.last()
                emit(layer, lineType, WireframeBezier(chain, isClosed))
            }
            chain = ArrayList()
        }
        var parts = 0
        for (cubic in cubics) {
            val p0 = nm(cubic.p0)
            if (chain.isNotEmpty() && !near(chain.last(), p0)) {
                flush(false)
                parts++
            }
            if (chain.isEmpty()) chain += p0
            chain += nm(cubic.p1)
            chain += nm(cubic.p2)
            chain += nm(cubic.p3)
        }
        // A chain is closed when the file says so or when its two ends are at the same point.
        flush(parts == 0 && (closed || (chain.isNotEmpty() && near(chain.first(), chain.last()))))
        bezierSegments += cubics.size
        if (bezierSegments > limits.maxBezierSegments) {
            throw DrawingFailure(
                DrawingProblemCodes.BEZIER_SEGMENTS_EXCEEDED,
                "more than ${limits.maxBezierSegments} Bézier segments in the document",
            )
        }
    }

    private fun near(a: NmPoint, b: NmPoint): Boolean = abs(a.x - b.x) <= JOIN_NM && abs(a.y - b.y) <= JOIN_NM

    // ---- Numbers ------------------------------------------------------------------------

    private fun checkFinite(vararg values: Double) {
        for (value in values) {
            if (!value.isFinite()) {
                throw DrawingFailure(DrawingProblemCodes.NON_FINITE_COORDINATE, "a coordinate is not a finite number ($value)")
            }
        }
    }

    private fun nmValue(value: Double): Long {
        if (!value.isFinite()) {
            throw DrawingFailure(DrawingProblemCodes.NON_FINITE_COORDINATE, "a coordinate is not a finite number ($value)")
        }
        if (abs(value) >= MAX_NM) {
            throw DrawingFailure(
                DrawingProblemCodes.COORDINATE_OVERFLOW,
                "the coordinate $value nm does not fit in a signed 64-bit integer",
            )
        }
        return floor(value + 0.5).toLong()
    }

    private fun nm(point: Vec): NmPoint = NmPoint(nmValue(point.x), nmValue(point.y))

    // ---- Result -------------------------------------------------------------------------

    private fun addWarnings() {
        for ((type, count) in dropped) {
            warnings.add(DrawingProblemCodes.ENTITY_DROPPED, "Dropped $count $type entities (not geometry)")
        }
        for ((type, count) in unsupported) {
            warnings.add(DrawingProblemCodes.UNSUPPORTED_ENTITY, "Dropped $count $type entities (not supported)")
        }
        if (hatchEdgePaths > 0) {
            warnings.add(DrawingProblemCodes.HATCH_EDGES_DROPPED, "Dropped $hatchEdgePaths HATCH edge boundaries")
        }
        for ((what, count) in invalid) {
            warnings.add(DrawingProblemCodes.INVALID_GEOMETRY, "Dropped $count $what")
        }
        for ((what, count) in tooComplex) {
            warnings.add(DrawingProblemCodes.CURVE_TOO_COMPLEX, "Dropped $count $what")
        }
        if (toleranceMisses > 0) {
            warnings.add(
                DrawingProblemCodes.CURVE_TOLERANCE_NOT_MET,
                "$toleranceMisses curves are not within the curve tolerance of ${limits.curveToleranceNm} nm",
            )
        }
        if (longPolylines > 0) {
            warnings.add(
                DrawingProblemCodes.POLYLINE_POINTS_WARNING,
                "$longPolylines polylines have more than ${limits.polylineWarningPoints} points (the largest has $longestPolyline)",
            )
        }
        if (missingBlocks.isNotEmpty()) {
            warnings.add(
                DrawingProblemCodes.MISSING_BLOCK,
                "INSERT names blocks that the file does not have: ${missingBlocks.joinToString(", ")}",
            )
        }
    }

    private fun build(): WireframeDocument {
        // The layers: the LAYER table order for the layers that the entities use, then the other
        // layers in the order of the first use.
        val used = LinkedHashSet<String>()
        emitted.forEach { item -> used += item.layerName.uppercase() }
        val names = ArrayList<String>()
        val styles = ArrayList<Int>()
        val visible = ArrayList<Boolean>()
        val indexOf = HashMap<String, Int>()
        fun addLayer(name: String, style: Int, isVisible: Boolean) {
            indexOf[name.uppercase()] = names.size
            names += name
            styles += style
            visible += isVisible
        }
        for (layer in document.layers) {
            val key = layer.name.uppercase()
            if (key in used && key !in indexOf) addLayer(layer.name, styleOf(layer.lineType), layer.visible)
        }
        for (item in emitted) {
            if (item.layerName.uppercase() !in indexOf) addLayer(item.layerName, 1, true)
        }
        val entities = emitted.map { item ->
            val index = indexOf.getValue(item.layerName.uppercase())
            val style = item.style.takeIf { value -> value != styles[index] }
            withLayer(item.entity, index, style)
        }
        val layers = names.indices.map { index -> WireframeLayer(names[index], visible[index], styles[index]) }
        return WireframeDocument(
            createdAt = formatUtc(now),
            extents = extentsOf(entities),
            layers = layers,
            entities = entities,
        )
    }

    private fun withLayer(entity: WireframeEntity, layer: Int, style: Int?): WireframeEntity = when (entity) {
        is WireframeLine -> entity.copy(layer = layer, style = style)
        is WireframePoint -> entity.copy(layer = layer, style = style)
        is WireframeCircle -> entity.copy(layer = layer, style = style)
        is WireframeArc -> entity.copy(layer = layer, style = style)
        is WireframePolyline -> entity.copy(layer = layer, style = style)
        is WireframeBezier -> entity.copy(layer = layer, style = style)
    }

    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    companion object {
        const val CONTINUOUS = "Continuous"
        const val ANGLE_EPSILON = 1e-6

        /** Two Bézier end points this near in X and Y (nm) are the same point. */
        const val JOIN_NM = 2L

        /** A coordinate in nm must be less than this value. */
        const val MAX_NM = 9.2e18

        /** POLYLINE flags of the 3D polygon mesh (16) and the polyface mesh (64). */
        const val MESH_FLAGS = 16L or 64L

        val GEOMETRY_TYPES = setOf("LINE", "POINT", "CIRCLE", "ARC", "LWPOLYLINE", "ELLIPSE", "SPLINE", "HATCH")

        /** The entities that are not geometry. */
        val DROPPED_TYPES = setOf(
            "TEXT", "MTEXT", "DIMENSION", "LEADER", "MULTILEADER", "MLEADER", "3DFACE", "3DSOLID", "MESH", "BODY",
            "REGION", "SURFACE", "VIEWPORT", "WIPEOUT", "IMAGE",
        )

        /** The entities that the converter ignores with no warning. */
        val IGNORED_TYPES = setOf("ATTRIB", "ATTDEF")
    }
}

internal fun saturatingAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

internal fun saturatingMultiply(a: Long, b: Long): Long =
    if (a == 0L || b == 0L) 0L else if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
