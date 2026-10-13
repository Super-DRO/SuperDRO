package org.superdro.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** The type of a drawing shape. */
@Serializable
enum class ShapeType { Line, Point, Circle, Arc, Polyline, Bezier }

/** The file format of an imported drawing. */
@Serializable
enum class DrawingFormat { AsciiDxf, BinaryDxf, Wireframe }

/** The source file of a drawing. */
@Serializable
data class DrawingSource(
    val fileName: String = "",
    val format: DrawingFormat = DrawingFormat.AsciiDxf,
)

/** One layer of a drawing. */
@Serializable
data class DrawingLayer(
    val index: Int = 0,
    val name: String = "",
    val hidden: Boolean = false,
)

/**
 * One shape of a drawing. All coordinates are integer nanometers in the plane coordinates of
 * the workspace. The arc angles are radians.
 *
 * - [ShapeType.Line]: ([x1], [y1]) to ([x2], [y2]).
 * - [ShapeType.Point]: ([x1], [y1]).
 * - [ShapeType.Circle]: center ([x1], [y1]), radius [radius].
 * - [ShapeType.Arc]: center ([x1], [y1]), radius [radius], start [startAngle], sweep [sweepAngle].
 * - [ShapeType.Polyline]: [points] as x0, y0, x1, y1, …; [bulges] gives the bulge of a segment
 *   by its index; [closed].
 * - [ShapeType.Bezier]: [points] and [closed].
 *
 * @property style The stroke style 0 to 5.
 * @property name The name of a [ShapeType.Point] (the name of a DXF `PT` entity), or null. The
 *   name stays after "Apply" and becomes the name of a sub-datum from a snap point.
 */
@Serializable
data class DrawingShape(
    val type: ShapeType = ShapeType.Line,
    val layerIndex: Int = 0,
    val style: Int = 1,
    val groupId: String? = null,
    val closed: Boolean = false,
    val x1: Long? = null,
    val y1: Long? = null,
    val x2: Long? = null,
    val y2: Long? = null,
    val radius: Long? = null,
    val startAngle: Double? = null,
    val sweepAngle: Double? = null,
    val points: List<Long> = emptyList(),
    val bulges: Map<Int, Double> = emptyMap(),
    val name: String? = null,
)

/** The drawing of one workspace. */
@Serializable
data class Drawing(
    val id: String = newId(),
    val workspaceId: String = "",
    val layers: List<DrawingLayer> = emptyList(),
    val shapes: List<DrawingShape> = emptyList(),
    val source: DrawingSource? = null,
    val createdOn: Instant = Clock.System.now(),
)
