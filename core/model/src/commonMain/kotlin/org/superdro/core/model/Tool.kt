package org.superdro.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** The kind of a tool. A horizontal mill uses the mill tools. */
@Serializable
enum class ToolKind { Mill, Lathe }

/**
 * The cutter outline of a lathe tool: shapes in integer nm.
 *
 * @property drawnIn The unit factor of the drawing: 1.0 when the drawing is in mm, 25.4 when the
 *   drawing is in inch. The outline is [shapes] multiplied by this factor.
 * @property layers The layers of [shapes].
 */
@Serializable
data class ToolGeometry(
    val shapes: List<DrawingShape> = emptyList(),
    val source: DrawingSource? = null,
    val drawnIn: Double = 1.0,
    val layers: List<DrawingLayer> = emptyList(),
)

/**
 * A tool of a profile or of the shared library. Lengths are µm.
 *
 * @property metric The display unit of this tool.
 * @property internal Lathe only: OD or facing (false), boring (true).
 */
@Serializable
data class Tool(
    val id: String = newId(),
    val kind: ToolKind = ToolKind.Mill,
    val name: String = "",
    val description: String = "",
    val metric: Boolean = false,
    val diameterUm: Double = 0.0,
    val toothCount: Int = 0,
    val zOffsetUm: Double = 0.0,
    val xOffsetUm: Double = 0.0,
    val internal: Boolean = false,
    val geometry: ToolGeometry? = null,
    val createdOn: Instant = Clock.System.now(),
    val updatedOn: Instant = Clock.System.now(),
)
