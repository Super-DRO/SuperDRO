package org.superdro.core.ui.functions.tools

import org.superdro.core.functions.compensation.CompensationResult
import org.superdro.core.functions.compensation.ToolCompensation
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.model.Workspace
import org.superdro.core.resources.Res
import org.superdro.core.resources.tools_error_description_too_long
import org.superdro.core.resources.tools_error_diameter
import org.superdro.core.resources.tools_error_name_blank
import org.superdro.core.resources.tools_error_name_too_long
import org.superdro.core.resources.tools_error_not_number
import org.superdro.core.resources.tools_error_tooth_count
import org.superdro.core.resources.tools_error_z_offset_required
import org.superdro.core.resources.tools_row_lathe
import org.superdro.core.resources.tools_row_mill
import org.superdro.core.resources.tools_row_mill_one
import org.superdro.core.resources.tools_row_id
import org.superdro.core.resources.tools_type_od_facing
import org.superdro.core.store.files.tool.ToolColumns
import org.superdro.core.ui.functions.common.LengthTexts
import org.superdro.core.ui.text.UiText

/** The fields of the tool edit sheet with a rule. */
enum class ToolField { Name, Description, Diameter, ToothCount, ZOffset, XOffset }

/**
 * The values of the tool edit sheet before the checks. A length is µm: null for an empty field,
 * `Double.NaN` for a text that the evaluator refused. [toothCount] is the value of the field, or
 * null for an empty field.
 */
data class ToolInput(
    val kind: ToolKind,
    val name: String,
    val description: String,
    val metric: Boolean,
    val diameterUm: Double? = null,
    val toothCount: Double? = null,
    val zOffsetUm: Double? = null,
    val xOffsetUm: Double? = null,
    val internal: Boolean = false,
)

/** The result of [ToolRules.check]: the tool, or the error of each field. */
sealed interface ToolCheck {
    data class Valid(val tool: Tool) : ToolCheck
    data class Invalid(val errors: Map<ToolField, UiText>) : ToolCheck
}

/**
 * The rules of the tools:
 *
 * | Field | Mill | Lathe |
 * |---|---|---|
 * | Name | Required, 50 characters or less | the same |
 * | Description | Optional, 100 characters or less | the same |
 * | Units | Imperial or metric, for the display of this tool | the metric default of the profile |
 * | Diameter | Required, not 0 | — |
 * | Tooth count | Integer, 0 or more | 1 |
 * | X offset | — | Optional, blank = 0 |
 * | Z offset | Required number (height offset) | Optional, blank = 0 |
 * | Internal | — | OD/facing (false) or boring (true), stored |
 *
 * A horizontal mill uses the mill tools.
 */
object ToolRules {

    /** The tooth count of a lathe tool. */
    const val LATHE_TOOTH_COUNT: Int = 1

    /** Gives the kind of the tools of [machineType]: the mill tools on both mill types. */
    fun kindOf(machineType: MachineType): ToolKind = if (machineType.isLathe) ToolKind.Lathe else ToolKind.Mill

    /** Checks [input] and gives the tool with the ID and the times of [base] (an edit), or a new tool. */
    fun check(input: ToolInput, base: Tool? = null): ToolCheck {
        val errors = LinkedHashMap<ToolField, UiText>()
        val name = input.name.trim()
        when {
            name.isEmpty() -> errors[ToolField.Name] = UiText.of(Res.string.tools_error_name_blank)
            name.length > ToolColumns.MAX_NAME_LENGTH ->
                errors[ToolField.Name] = UiText.of(Res.string.tools_error_name_too_long, ToolColumns.MAX_NAME_LENGTH)
        }
        val description = input.description.trim()
        if (description.length > ToolColumns.MAX_DESCRIPTION_LENGTH) {
            errors[ToolField.Description] = UiText.of(Res.string.tools_error_description_too_long, ToolColumns.MAX_DESCRIPTION_LENGTH)
        }
        val lathe = input.kind == ToolKind.Lathe
        var diameter = 0.0
        var teeth = LATHE_TOOTH_COUNT
        var z = 0.0
        var x = 0.0
        if (lathe) {
            x = optionalLength(input.xOffsetUm, ToolField.XOffset, errors)
            z = optionalLength(input.zOffsetUm, ToolField.ZOffset, errors)
        } else {
            val d = input.diameterUm
            if (d == null || !d.isFinite() || d == 0.0) {
                errors[ToolField.Diameter] = UiText.of(Res.string.tools_error_diameter)
            } else {
                diameter = d
            }
            val count = input.toothCount
            if (count == null || !count.isFinite() || count < 0.0 || count != kotlin.math.floor(count) || count > Int.MAX_VALUE) {
                errors[ToolField.ToothCount] = UiText.of(Res.string.tools_error_tooth_count)
            } else {
                teeth = count.toInt()
            }
            val zValue = input.zOffsetUm
            when {
                zValue == null -> errors[ToolField.ZOffset] = UiText.of(Res.string.tools_error_z_offset_required)
                !zValue.isFinite() -> errors[ToolField.ZOffset] = UiText.of(Res.string.tools_error_not_number)
                else -> z = zValue
            }
        }
        if (errors.isNotEmpty()) return ToolCheck.Invalid(errors)
        val template = base ?: Tool()
        return ToolCheck.Valid(
            template.copy(
                kind = input.kind,
                name = name,
                description = description,
                metric = input.metric,
                diameterUm = if (lathe) 0.0 else diameter,
                toothCount = teeth,
                zOffsetUm = z,
                xOffsetUm = if (lathe) x else 0.0,
                internal = lathe && input.internal,
            ),
        )
    }

    private fun optionalLength(value: Double?, field: ToolField, errors: MutableMap<ToolField, UiText>): Double = when {
        value == null -> 0.0
        !value.isFinite() -> {
            errors[field] = UiText.of(Res.string.tools_error_not_number)
            0.0
        }
        else -> value
    }

    /**
     * The value of a sync button of a lathe tool: `−(shown position −
     * active tool offset)` of the axis. [positionUm] is the position of the axis before the diameter
     * display (the radius on a lathe X), with the tool offset in use.
     */
    fun syncValue(positionUm: Double, activeToolOffsetUm: Double): Double {
        val value = -(positionUm - activeToolOffsetUm)
        return if (value == 0.0) 0.0 else value
    }
}

/**
 * The activation of a tool from the tool panel. A tap on a tool
 * activates it. A tap on the active tool clears it.
 *
 * - Lathe: X tool offset = X offset, Z tool offset = Z offset (a value of 0 clears the offset).
 * - Mill: Z tool offset = Z offset (the height compensation), the X and Y offsets clear (the
 *   radius compensation with no direction), and the workspace keeps the diameter and the tooth
 *   count.
 */
object ToolActivation {

    /** True when [tool] is the active tool of [workspace]. */
    fun isActive(workspace: Workspace, tool: Tool): Boolean = workspace.activeToolId == tool.id

    /** Gives [workspace] after a tap on [tool]. */
    fun toggle(workspace: Workspace, tool: Tool, machineType: MachineType): Workspace {
        if (isActive(workspace, tool)) return clear(workspace, machineType)
        return if (machineType.isLathe) {
            (ToolCompensation.latheOffset(workspace, null, null, tool) as? CompensationResult.Applied)?.workspace ?: workspace
        } else {
            val radius = when (val result = ToolCompensation.radius(workspace, ToolPosition.Center, null, tool)) {
                is CompensationResult.Applied -> result.workspace
                else -> ToolCompensation.clearRadius(ToolCompensation.clearHeight(workspace))
            }
            (ToolCompensation.height(radius.copy(activeToolId = tool.id), null, tool) as? CompensationResult.Applied)?.workspace ?: radius
        }
    }

    /** Gives [workspace] with no active tool: all tool offsets of the tool clear. */
    fun clear(workspace: Workspace, machineType: MachineType): Workspace =
        if (machineType.isLathe) {
            ToolCompensation.clearLatheOffset(workspace)
        } else {
            ToolCompensation.clearRadius(ToolCompensation.clearHeight(workspace)).copy(activeToolId = null)
        }
}

/** The texts of the tool rows. */
object ToolTexts {

    /**
     * The row text of [tool]: lathe `<x> <unit> Δx • <z> <unit> Δz • ID` (or `OD/Facing`), mill
     * `⌀ <d> <unit> • <n> flute(s) • <z> <unit> Δz`. A mill tool uses its own unit. A lathe tool
     * uses the metric default of the profile.
     */
    fun row(tool: Tool, profileMetric: Boolean, format: ReadoutFormat = ReadoutFormat.Format3): UiText =
        if (tool.kind == ToolKind.Lathe) {
            val unit = UiText.of(LengthTexts.unit(profileMetric))
            UiText.of(
                Res.string.tools_row_lathe,
                LengthTexts.format(tool.xOffsetUm, profileMetric, format),
                unit,
                LengthTexts.format(tool.zOffsetUm, profileMetric, format),
                unit,
                UiText.of(if (tool.internal) Res.string.tools_row_id else Res.string.tools_type_od_facing),
            )
        } else {
            val unit = UiText.of(LengthTexts.unit(tool.metric))
            UiText.of(
                if (tool.toothCount == 1) Res.string.tools_row_mill_one else Res.string.tools_row_mill,
                LengthTexts.format(tool.diameterUm, tool.metric, format),
                unit,
                tool.toothCount,
                LengthTexts.format(tool.zOffsetUm, tool.metric, format),
                unit,
            )
        }
}

/** The sort orders of the tool list. */
enum class ToolSort { NameAscending, NameDescending, Newest, Oldest }

/** Gives [tools] in the order [sort]. Two equal keys keep the order of the names. */
fun List<Tool>.sortedFor(sort: ToolSort): List<Tool> = when (sort) {
    ToolSort.NameAscending -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { tool: Tool -> tool.name })
    ToolSort.NameDescending -> sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { tool: Tool -> tool.name })
    ToolSort.Newest -> sortedWith(compareByDescending<Tool> { it.createdOn }.thenBy { it.name })
    ToolSort.Oldest -> sortedWith(compareBy<Tool> { it.createdOn }.thenBy { it.name })
}
