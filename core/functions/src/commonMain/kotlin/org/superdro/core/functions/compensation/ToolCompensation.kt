package org.superdro.core.functions.compensation

import org.superdro.core.functions.FunctionMessage
import org.superdro.core.functions.MessageTemplate
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Tool
import org.superdro.core.model.Workspace

/** The texts of the tool compensation. File `strings_compensation.xml`. */
object CompensationTexts {
    val TOOL_DIAMETER_NOT_VALID = MessageTemplate("compensation_error_tool_diameter", "Please enter a valid tool diameter")
    val Z_OFFSET_ZERO = MessageTemplate("compensation_error_z_offset_zero", "Z offset can't be 0")
    val Z_OFFSET_NOT_VALID = MessageTemplate("compensation_error_z_offset_not_valid", "Please enter a valid Z offset")
    val NO_LATHE_OFFSET = MessageTemplate("compensation_error_no_lathe_offset", "Please set an offset on at least one axis")

    /** All templates of this object, for the check against the string file. */
    val all: List<MessageTemplate> by lazy { listOf(TOOL_DIAMETER_NOT_VALID, Z_OFFSET_ZERO, Z_OFFSET_NOT_VALID, NO_LATHE_OFFSET) }
}

/**
 * The 3 × 3 direction control of the radius compensation. The
 * direction is `(dirX, dirY)`. Back is the far side, front is the near side.
 */
enum class ToolPosition(val dirX: Int, val dirY: Int) {
    BackLeft(+1, -1),
    BackCenter(0, -1),
    BackRight(-1, -1),
    MiddleLeft(+1, 0),

    /** "none": no offset on X and Y. */
    Center(0, 0),
    MiddleRight(-1, 0),
    FrontLeft(+1, +1),
    FrontCenter(0, +1),
    FrontRight(-1, +1),
}

/** The result of a compensation function. */
sealed interface CompensationResult {
    /** The new workspace state. Keep the old state for the undo. */
    data class Applied(val workspace: Workspace) : CompensationResult

    /** The dialog closes and the state does not change. */
    data object NoChange : CompensationResult

    /** The dialog shows [message] and stays open. */
    data class Failure(val message: FunctionMessage) : CompensationResult
}

/**
 * The mill tool radius compensation, the mill tool height compensation, and the lathe tool
 * offset.
 *
 * The tool offset `T` of an axis adds to the absolute position before the absolute offset:
 * `abs = R + (T if T.active) + A`. A value of 0 clears the offset of that axis.
 * The functions change only the workspace state. The readout reads the new state.
 */
object ToolCompensation {

    /**
     * The radius compensation:
     *
     * ```text
     * store D (µm) and the tooth count in the workspace
     * X tool offset = dirX × D / 2      (cleared if dirX == 0)
     * Y tool offset = dirY × D / 2      (cleared if dirY == 0)
     * ```
     *
     * A custom diameter clears the active tool and stores the tooth count 1. A library tool that is
     * not the active tool first clears the height compensation. With the direction "none" and a
     * diameter of 0, nothing changes.
     *
     * @param customDiameterUm The custom diameter, or null when the field is empty or not valid.
     *   The function ignores it when [tool] is not null.
     * @param tool The library tool, or null for a custom diameter.
     */
    fun radius(workspace: Workspace, position: ToolPosition, customDiameterUm: Double?, tool: Tool? = null): CompensationResult {
        val diameter = tool?.diameterUm ?: customDiameterUm
        if (diameter == null || diameter.isNaN() || diameter < 0.0) {
            return CompensationResult.Failure(CompensationTexts.TOOL_DIAMETER_NOT_VALID.message())
        }
        if (position == ToolPosition.Center && diameter == 0.0) return CompensationResult.NoChange
        var state = workspace
        if (tool != null && tool.id != workspace.activeToolId) state = clearHeight(state)
        val radius = diameter / 2.0
        state = state
            .withToolOffset(AxisId.X, position.dirX * radius)
            .withToolOffset(AxisId.Y, position.dirY * radius)
            .copy(
                toolXUm = diameter,
                toothCount = tool?.toothCount ?: CUSTOM_TOOTH_COUNT,
                activeToolId = tool?.id,
            )
        return CompensationResult.Applied(state)
    }

    /**
     * The height compensation: the Z tool offset is the Z offset of the library tool, or the
     * custom value. A custom value of 0 (also an empty field) gives "Z offset can't be 0". A custom
     * value clears the active tool. A library tool that is not the active tool first clears the
     * radius compensation.
     *
     * @param customZOffsetUm The custom value, or null when the field is empty. Not a Number is a
     *   value that is not valid ("Please enter a valid Z offset").
     */
    fun height(workspace: Workspace, customZOffsetUm: Double?, tool: Tool? = null): CompensationResult {
        if (tool != null) {
            var state = workspace
            if (tool.id != workspace.activeToolId) state = clearRadius(state)
            state = state.withToolOffset(AxisId.Z, tool.zOffsetUm).copy(toolZUm = tool.zOffsetUm, activeToolId = tool.id)
            return CompensationResult.Applied(state)
        }
        val offset = customZOffsetUm ?: 0.0
        if (offset.isNaN()) return CompensationResult.Failure(CompensationTexts.Z_OFFSET_NOT_VALID.message())
        if (offset == 0.0) return CompensationResult.Failure(CompensationTexts.Z_OFFSET_ZERO.message())
        val state = workspace.withToolOffset(AxisId.Z, offset).copy(toolZUm = offset, activeToolId = null)
        return CompensationResult.Applied(state)
    }

    /**
     * "Set Tool Offset" of the lathe: X tool offset = x, Z tool offset = z (0 clears the
     * offset). With no library tool, an x of 0 and a z of 0 give "Please set an offset on at least
     * one axis". An empty field counts as 0. A custom value clears the active tool.
     */
    fun latheOffset(workspace: Workspace, customXUm: Double?, customZUm: Double?, tool: Tool? = null): CompensationResult {
        val x = tool?.xOffsetUm ?: customXUm.orZero()
        val z = tool?.zOffsetUm ?: customZUm.orZero()
        if (tool == null && x == 0.0 && z == 0.0) {
            return CompensationResult.Failure(CompensationTexts.NO_LATHE_OFFSET.message())
        }
        val state = workspace
            .withToolOffset(AxisId.X, x)
            .withToolOffset(AxisId.Z, z)
            .copy(toolXUm = x, toolZUm = z, activeToolId = tool?.id)
        return CompensationResult.Applied(state)
    }

    /**
     * "Select Tool" of the lathe. The four steps:
     *
     * 1. Read the absolute X and Z.
     * 2. Clear all tool offsets.
     * 3. Apply the offsets of the selected library tool.
     * 4. Set the absolute X and Z to the values of step 1.
     *
     * Step 4 changes the absolute offset: `A' = A + T_old − T_new`. Thus `abs = R + T + A` does not
     * change for X and Z, for each raw value `R`.
     */
    fun selectTool(workspace: Workspace, tool: Tool): Workspace {
        val before = workspace.axisStates
        var state = clearAllToolOffsets(workspace)
        state = state
            .withToolOffset(AxisId.X, tool.xOffsetUm)
            .withToolOffset(AxisId.Z, tool.zOffsetUm)
            .copy(toolXUm = tool.xOffsetUm, toolZUm = tool.zOffsetUm, activeToolId = tool.id)
        val states = state.axisStates.toMutableMap()
        for (axis in listOf(AxisId.X, AxisId.Z)) {
            val old = before[axis] ?: AxisState()
            val new = states[axis] ?: AxisState()
            states[axis] = new.copy(absoluteOffset = old.absoluteOffset + old.effectiveToolOffset - new.effectiveToolOffset)
        }
        return state.copy(axisStates = states)
    }

    /** The lathe tool offset button is active when X or Z has an active tool offset. */
    fun latheOffsetActive(workspace: Workspace): Boolean =
        workspace.toolOffsetActive(AxisId.X) || workspace.toolOffsetActive(AxisId.Z)

    /** The height compensation button is active when Z has an active tool offset. */
    fun heightActive(workspace: Workspace): Boolean = workspace.toolOffsetActive(AxisId.Z)

    /**
     * The radius compensation button is active when X or Y has an active tool offset, or
     * the stored tool diameter is more than 0.
     */
    fun radiusActive(workspace: Workspace): Boolean =
        workspace.toolOffsetActive(AxisId.X) || workspace.toolOffsetActive(AxisId.Y) || workspace.toolXUm > 0.0

    /**
     * The long press on the active radius compensation button: clears the X and Y tool
     * offsets, the stored diameter, and the tooth count. The active tool stays only while it
     * still gives a Z tool offset.
     */
    fun clearRadius(workspace: Workspace): Workspace {
        val state = workspace
            .withToolOffset(AxisId.X, 0.0)
            .withToolOffset(AxisId.Y, 0.0)
            .copy(toolXUm = 0.0, toothCount = 0)
        return state.copy(activeToolId = workspace.activeToolId.takeIf { state.toolOffsetActive(AxisId.Z) })
    }

    /**
     * The long press on the active height compensation button: clears the Z tool offset
     * and the stored Z value. The active tool stays only while it still gives a radius
     * compensation.
     */
    fun clearHeight(workspace: Workspace): Workspace {
        val state = workspace.withToolOffset(AxisId.Z, 0.0).copy(toolZUm = 0.0)
        return state.copy(activeToolId = workspace.activeToolId.takeIf { radiusActive(state) })
    }

    /** The long press on the active lathe tool offset button: clears all tool offsets and the tool. */
    fun clearLatheOffset(workspace: Workspace): Workspace = clearAllToolOffsets(workspace)

    private fun clearAllToolOffsets(workspace: Workspace): Workspace = workspace.copy(
        axisStates = workspace.axisStates.mapValues { (_, state) -> state.copy(toolOffset = 0.0, toolOffsetActive = false) },
        toolXUm = 0.0,
        toolZUm = 0.0,
        toothCount = 0,
        activeToolId = null,
    )

    /** The tooth count that a custom diameter stores. */
    const val CUSTOM_TOOTH_COUNT: Int = 1
}

private val AxisState.effectiveToolOffset: Double get() = if (toolOffsetActive) toolOffset else 0.0

private fun Double?.orZero(): Double = if (this == null || isNaN()) 0.0 else this

private fun Workspace.toolOffsetActive(axis: AxisId): Boolean = axisStates[axis]?.toolOffsetActive == true

private fun Workspace.withToolOffset(axis: AxisId, valueUm: Double): Workspace {
    val state = axisStates[axis] ?: AxisState()
    val updated = state.copy(toolOffset = valueUm, toolOffsetActive = valueUm != 0.0)
    return copy(axisStates = axisStates + (axis to updated))
}
