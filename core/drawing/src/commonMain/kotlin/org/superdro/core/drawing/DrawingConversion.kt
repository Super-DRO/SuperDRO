package org.superdro.core.drawing

import kotlin.time.Clock
import kotlin.time.Instant
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.DrawingSource
import org.superdro.core.model.ShapeType
import org.superdro.core.model.newId

/**
 * The conversion between a [WireframeDocument] and the [Drawing] of `core:model`. The coordinates
 * stay integer nm, and the
 * angles stay radians.
 *
 * - A layer keeps its name; `hidden` is the opposite of `v`.
 * - A shape gets its own style, else the style of its layer (0 to 5).
 * - A point keeps its name.
 * - A polyline keeps its bulges by segment index; a Bézier chain keeps its 1 + 3·N points.
 */
object DrawingConversion {

    /** Gives the drawing of [document] for the workspace [workspaceId]. */
    fun toDrawing(
        document: WireframeDocument,
        workspaceId: String,
        source: DrawingSource? = null,
        id: String = newId(),
        createdOn: Instant = Clock.System.now(),
    ): Drawing = Drawing(
        id = id,
        workspaceId = workspaceId,
        layers = toLayers(document),
        shapes = toShapes(document),
        source = source,
        createdOn = createdOn,
    )

    /** Gives the layers of [document]. */
    fun toLayers(document: WireframeDocument): List<DrawingLayer> =
        document.layers.mapIndexed { index, layer -> DrawingLayer(index = index, name = layer.name, hidden = !layer.visible) }

    /** Gives the shapes of [document]. */
    fun toShapes(document: WireframeDocument): List<DrawingShape> = document.entities.map { entity ->
        val style = document.styleOf(entity)
        when (entity) {
            is WireframeLine -> DrawingShape(
                type = ShapeType.Line,
                layerIndex = entity.layer,
                style = style,
                groupId = entity.group,
                x1 = entity.start.x,
                y1 = entity.start.y,
                x2 = entity.end.x,
                y2 = entity.end.y,
            )
            is WireframePoint -> DrawingShape(
                type = ShapeType.Point,
                layerIndex = entity.layer,
                style = style,
                groupId = entity.group,
                x1 = entity.point.x,
                y1 = entity.point.y,
                name = entity.name,
            )
            is WireframeCircle -> DrawingShape(
                type = ShapeType.Circle,
                layerIndex = entity.layer,
                style = style,
                groupId = entity.group,
                x1 = entity.center.x,
                y1 = entity.center.y,
                radius = entity.radius,
            )
            is WireframeArc -> DrawingShape(
                type = ShapeType.Arc,
                layerIndex = entity.layer,
                style = style,
                groupId = entity.group,
                x1 = entity.center.x,
                y1 = entity.center.y,
                radius = entity.radius,
                startAngle = entity.start,
                sweepAngle = entity.sweep,
            )
            is WireframePolyline -> DrawingShape(
                type = ShapeType.Polyline,
                layerIndex = entity.layer,
                style = style,
                groupId = entity.group,
                closed = entity.closed,
                points = entity.points.flatMap { point -> listOf(point.x, point.y) },
                bulges = entity.bulges.filterValues { bulge -> bulge != 0.0 },
            )
            is WireframeBezier -> DrawingShape(
                type = ShapeType.Bezier,
                layerIndex = entity.layer,
                style = style,
                groupId = entity.group,
                closed = entity.closed,
                points = entity.points.flatMap { point -> listOf(point.x, point.y) },
            )
        }
    }

    /**
     * Gives the wireframe document of [layers] and [shapes]: the SuperDRO wireframe file. Each
     * entity gets its style as `st`. A shape that does not have all its data (for example a line
     * with no `x2`) goes away.
     */
    fun toWireframe(
        layers: List<DrawingLayer>,
        shapes: List<DrawingShape>,
        createdAt: Instant = Clock.System.now(),
    ): WireframeDocument {
        val sorted = layers.sortedBy { layer -> layer.index }
        val indexMap = sorted.mapIndexed { position, layer -> layer.index to position }.toMap()
        val wireframeLayers = sorted.map { layer -> WireframeLayer(layer.name.ifEmpty { "Layer ${layer.index}" }, !layer.hidden) }
            .ifEmpty { listOf(WireframeLayer("0")) }
        val entities = shapes.mapNotNull { shape ->
            val layer = indexMap[shape.layerIndex] ?: 0
            val style = shape.style.coerceIn(0, 5)
            when (shape.type) {
                ShapeType.Line -> {
                    val x1 = shape.x1 ?: return@mapNotNull null
                    val y1 = shape.y1 ?: return@mapNotNull null
                    val x2 = shape.x2 ?: return@mapNotNull null
                    val y2 = shape.y2 ?: return@mapNotNull null
                    WireframeLine(NmPoint(x1, y1), NmPoint(x2, y2), layer, style = style, group = shape.groupId)
                }
                ShapeType.Point -> {
                    val x = shape.x1 ?: return@mapNotNull null
                    val y = shape.y1 ?: return@mapNotNull null
                    WireframePoint(NmPoint(x, y), shape.name, layer, style = style, group = shape.groupId)
                }
                ShapeType.Circle -> {
                    val x = shape.x1 ?: return@mapNotNull null
                    val y = shape.y1 ?: return@mapNotNull null
                    val r = shape.radius?.takeIf { radius -> radius > 0 } ?: return@mapNotNull null
                    WireframeCircle(NmPoint(x, y), r, layer, style = style, group = shape.groupId)
                }
                ShapeType.Arc -> {
                    val x = shape.x1 ?: return@mapNotNull null
                    val y = shape.y1 ?: return@mapNotNull null
                    val r = shape.radius?.takeIf { radius -> radius > 0 } ?: return@mapNotNull null
                    val start = shape.startAngle ?: return@mapNotNull null
                    val sweep = shape.sweepAngle ?: return@mapNotNull null
                    WireframeArc(NmPoint(x, y), r, start, sweep, layer, style = style, group = shape.groupId)
                }
                ShapeType.Polyline -> {
                    val points = shape.points.chunked(2).filter { pair -> pair.size == 2 }.map { (x, y) -> NmPoint(x, y) }
                    if (points.size < 2) return@mapNotNull null
                    WireframePolyline(points, shape.closed, shape.bulges.filterValues { bulge -> bulge != 0.0 }, layer, style = style, group = shape.groupId)
                }
                ShapeType.Bezier -> {
                    val points = shape.points.chunked(2).filter { pair -> pair.size == 2 }.map { (x, y) -> NmPoint(x, y) }
                    if (points.size < 4 || (points.size - 1) % 3 != 0) return@mapNotNull null
                    WireframeBezier(points, shape.closed, layer, style = style, group = shape.groupId)
                }
            }
        }
        return WireframeDocument(
            createdAt = formatUtc(createdAt),
            extents = extentsOf(entities),
            layers = wireframeLayers,
            entities = entities,
        )
    }

    /** Gives the wireframe document of [drawing]. */
    fun toWireframe(drawing: Drawing, createdAt: Instant = Clock.System.now()): WireframeDocument =
        toWireframe(drawing.layers, drawing.shapes, createdAt)
}
