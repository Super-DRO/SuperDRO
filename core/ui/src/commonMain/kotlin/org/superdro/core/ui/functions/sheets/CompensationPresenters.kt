package org.superdro.core.ui.functions.sheets

import androidx.compose.runtime.Immutable
import kotlin.math.sign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.superdro.core.functions.compensation.CompensationResult
import org.superdro.core.functions.compensation.ToolCompensation
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.model.AxisId
import org.superdro.core.model.Tool
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.resources.Res
import org.superdro.core.resources.fns_undo_height
import org.superdro.core.resources.fns_undo_height_clear
import org.superdro.core.resources.fns_undo_lathe_clear
import org.superdro.core.resources.fns_undo_lathe_offset
import org.superdro.core.resources.fns_undo_radius
import org.superdro.core.resources.fns_undo_radius_clear
import org.superdro.core.resources.fns_undo_select_tool
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.tools.ToolTexts
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** One tool of the tool choice of a compensation sheet. */
@Immutable
data class ToolChoice(val id: String, val name: String, val detail: UiText)

/**
 * The common state of the three compensation sheets: the tools of the library and the selected
 * tool (null: a custom value).
 */
abstract class CompensationPresenter<S>(
    protected val readout: TabReadout,
    protected val services: FunctionServices,
    initial: S,
) : ScopedPresenter(services.context) {

    protected val mutable = MutableStateFlow(initial)
    protected var tools: List<Tool> = emptyList()
    protected var frame: ReadoutFrame? = null
    private var initialized = false

    /** The UI state of the sheet. */
    val uiState: StateFlow<S> = mutable.asStateFlow()

    init {
        scope.launch {
            combine(services.tools.library(readout.deviceId.value), readout.frame) { library, current -> library to current }
                .collect { (library, current) ->
                    tools = library
                    frame = current
                    if (current == null) return@collect
                    if (!initialized) {
                        initialized = true
                        initialize(current)
                    }
                    refresh()
                }
        }
    }

    /** The tool choices: the tools of the library, sorted by name. */
    protected fun choices(): List<ToolChoice> {
        val current = frame ?: return emptyList()
        val profile = current.profile
        return tools.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { tool: Tool -> tool.name }).map { tool ->
            ToolChoice(tool.id, tool.name, ToolTexts.row(tool, profile.metricDefault, profile.display.readoutFormat))
        }
    }

    /** The tool [id] of the library, or null. */
    protected fun tool(id: String?): Tool? = id?.let { wanted -> tools.firstOrNull { tool -> tool.id == wanted } }

    /** Sets the first values of the fields from the workspace of [frame]. */
    protected abstract fun initialize(frame: ReadoutFrame)

    /** Calculates the state again after a change of the inputs or of the readout. */
    protected abstract fun refresh()

    /**
     * Applies [change] to the current workspace through the readout of the tab, with the undo,
     * when [check] gives a new workspace. Gives the result of [check].
     */
    protected fun commit(label: UiText, check: (Workspace) -> CompensationResult): CompensationResult {
        val current = frame ?: return CompensationResult.NoChange
        val result = check(current.workspace)
        if (result is CompensationResult.Applied) {
            readout.applyWorkspace(label) { latest -> (check(latest) as? CompensationResult.Applied)?.workspace ?: latest }
        }
        return result
    }
}

/**
 * The state of the radius compensation sheet.
 *
 * @property active True when the radius compensation is active: the "Clear" button works.
 */
@Immutable
data class RadiusCompensationUiState(
    val loaded: Boolean = false,
    val tools: List<ToolChoice> = emptyList(),
    val toolId: String? = null,
    val diameter: LengthFieldState = LengthFieldState(),
    val position: ToolPosition = ToolPosition.Center,
    val active: Boolean = false,
    val error: UiText? = null,
)

/**
 * The presenter of the radius compensation sheet:
 *
 * ```text
 * store D (µm) and the tooth count in the workspace
 * X tool offset = dirX × D / 2      (cleared if dirX == 0)
 * Y tool offset = dirY × D / 2      (cleared if dirY == 0)
 * ```
 *
 * A custom diameter clears the active tool. A library tool that is not the active tool first
 * clears the height compensation. The direction "none" with a diameter of 0 changes nothing.
 */
class RadiusCompensationPresenter(readout: TabReadout, services: FunctionServices) :
    CompensationPresenter<RadiusCompensationUiState>(readout, services, RadiusCompensationUiState()) {

    override fun initialize(frame: ReadoutFrame) {
        val workspace = frame.workspace
        val active = tool(workspace.activeToolId)?.takeIf { ToolCompensation.radiusActive(workspace) }
        val metric = frame.profile.metricDefault
        val x = workspace.axisStates[AxisId.X]
        val y = workspace.axisStates[AxisId.Y]
        val dirX = if (x?.toolOffsetActive == true) x.toolOffset.sign.toInt() else 0
        val dirY = if (y?.toolOffsetActive == true) y.toolOffset.sign.toInt() else 0
        mutable.value = mutable.value.copy(
            toolId = active?.id,
            diameter = LengthFieldState.of(workspace.toolXUm.takeIf { it > 0.0 }, metric),
            position = ToolPosition.entries.firstOrNull { it.dirX == dirX && it.dirY == dirY } ?: ToolPosition.Center,
        )
    }

    override fun refresh() {
        val current = frame ?: return
        val state = mutable.value
        val selected = tool(state.toolId)
        mutable.value = state.copy(
            loaded = true,
            tools = choices(),
            toolId = selected?.id,
            diameter = if (selected != null) state.diameter.withValue(selected.diameterUm) else state.diameter,
            active = ToolCompensation.radiusActive(current.workspace),
        )
    }

    /** Selects the library tool [id], or the custom diameter (null). */
    fun onTool(id: String?) {
        mutable.value = mutable.value.copy(toolId = id, error = null)
        refresh()
    }

    fun onDiameter(text: String) {
        mutable.value = mutable.value.copy(diameter = mutable.value.diameter.withText(text), error = null)
    }

    fun onDiameterUnit(metric: Boolean) {
        mutable.value = mutable.value.copy(diameter = mutable.value.diameter.withUnit(metric))
    }

    /** Selects the cell of the 3 × 3 direction control. */
    fun onPosition(position: ToolPosition) {
        mutable.value = mutable.value.copy(position = position, error = null)
    }

    /** "Set Radius Compensation". Gives true when the sheet can close. */
    fun onApply(): Boolean {
        val state = mutable.value
        val selected = tool(state.toolId)
        val result = commit(UiText.of(Res.string.fns_undo_radius)) { workspace ->
            ToolCompensation.radius(workspace, state.position, state.diameter.valueUm, selected)
        }
        if (result is CompensationResult.Failure) {
            mutable.value = state.copy(error = result.message.toUiText())
            return false
        }
        return true
    }

    /** "Clear Compensation": clears the radius compensation with undo. */
    fun onClear(): Boolean {
        if (frame == null) return false
        readout.applyWorkspace(UiText.of(Res.string.fns_undo_radius_clear), ToolCompensation::clearRadius)
        return true
    }
}

/** The state of the height compensation sheet. */
@Immutable
data class HeightCompensationUiState(
    val loaded: Boolean = false,
    val tools: List<ToolChoice> = emptyList(),
    val toolId: String? = null,
    val zOffset: LengthFieldState = LengthFieldState(),
    val active: Boolean = false,
    val error: UiText? = null,
)

/**
 * The presenter of the height compensation sheet: the Z tool
 * offset is the Z offset of the library tool, or a custom value. A custom value of 0 is an error
 * ("Z offset can't be 0"). A library tool that is not the active tool first clears the radius
 * compensation. Example: −12.5 mm gives a Z tool offset of −12500 µm.
 */
class HeightCompensationPresenter(readout: TabReadout, services: FunctionServices) :
    CompensationPresenter<HeightCompensationUiState>(readout, services, HeightCompensationUiState()) {

    override fun initialize(frame: ReadoutFrame) {
        val workspace = frame.workspace
        val z = workspace.axisStates[AxisId.Z]
        val active = tool(workspace.activeToolId)?.takeIf { ToolCompensation.heightActive(workspace) }
        mutable.value = mutable.value.copy(
            toolId = active?.id,
            zOffset = LengthFieldState.of(z?.toolOffset?.takeIf { z.toolOffsetActive }, frame.profile.metricDefault),
        )
    }

    override fun refresh() {
        val current = frame ?: return
        val state = mutable.value
        val selected = tool(state.toolId)
        mutable.value = state.copy(
            loaded = true,
            tools = choices(),
            toolId = selected?.id,
            zOffset = if (selected != null) state.zOffset.withValue(selected.zOffsetUm) else state.zOffset,
            active = ToolCompensation.heightActive(current.workspace),
        )
    }

    fun onTool(id: String?) {
        mutable.value = mutable.value.copy(toolId = id, error = null)
        refresh()
    }

    fun onZOffset(text: String) {
        mutable.value = mutable.value.copy(zOffset = mutable.value.zOffset.withText(text), error = null)
    }

    fun onZOffsetUnit(metric: Boolean) {
        mutable.value = mutable.value.copy(zOffset = mutable.value.zOffset.withUnit(metric))
    }

    /** "Set Height Compensation". Gives true when the sheet can close. */
    fun onApply(): Boolean {
        val state = mutable.value
        val selected = tool(state.toolId)
        val result = commit(UiText.of(Res.string.fns_undo_height)) { workspace ->
            ToolCompensation.height(workspace, state.zOffset.valueUm, selected)
        }
        if (result is CompensationResult.Failure) {
            mutable.value = state.copy(error = result.message.toUiText())
            return false
        }
        return true
    }

    /** "Clear Compensation". */
    fun onClear(): Boolean {
        if (frame == null) return false
        readout.applyWorkspace(UiText.of(Res.string.fns_undo_height_clear), ToolCompensation::clearHeight)
        return true
    }
}

/**
 * The state of the lathe tool offset sheet.
 *
 * @property canSelect True when a library tool is selected: "Select Tool" shows.
 */
@Immutable
data class LatheToolOffsetUiState(
    val loaded: Boolean = false,
    val tools: List<ToolChoice> = emptyList(),
    val toolId: String? = null,
    val xOffset: LengthFieldState = LengthFieldState(),
    val zOffset: LengthFieldState = LengthFieldState(),
    val active: Boolean = false,
    val error: UiText? = null,
) {
    val canSelect: Boolean get() = toolId != null
}

/**
 * The presenter of the lathe tool offset sheet:
 * X tool offset = x, Z tool offset = z. With no library tool, an X and a Z of 0 give "Please set an
 * offset on at least one axis". The X offset is a radius, as the X offset of a lathe tool.
 *
 * "Select Tool" does these steps (`ToolCompensation.selectTool`):
 * 1. Read the absolute X and Z. 2. Clear all tool offsets. 3. Apply the offsets of the selected
 * library tool. 4. Set the absolute X and Z to the values of step 1. It needs a `Connected`
 * session.
 */
class LatheToolOffsetPresenter(readout: TabReadout, services: FunctionServices) :
    CompensationPresenter<LatheToolOffsetUiState>(readout, services, LatheToolOffsetUiState()) {

    override fun initialize(frame: ReadoutFrame) {
        val workspace = frame.workspace
        val metric = frame.profile.metricDefault
        val x = workspace.axisStates[AxisId.X]
        val z = workspace.axisStates[AxisId.Z]
        mutable.value = mutable.value.copy(
            toolId = tool(workspace.activeToolId)?.id,
            xOffset = LengthFieldState.of(x?.toolOffset?.takeIf { x.toolOffsetActive }, metric),
            zOffset = LengthFieldState.of(z?.toolOffset?.takeIf { z.toolOffsetActive }, metric),
        )
    }

    override fun refresh() {
        val current = frame ?: return
        val state = mutable.value
        val selected = tool(state.toolId)
        mutable.value = state.copy(
            loaded = true,
            tools = choices(),
            toolId = selected?.id,
            xOffset = if (selected != null) state.xOffset.withValue(selected.xOffsetUm) else state.xOffset,
            zOffset = if (selected != null) state.zOffset.withValue(selected.zOffsetUm) else state.zOffset,
            active = ToolCompensation.latheOffsetActive(current.workspace),
        )
    }

    fun onTool(id: String?) {
        mutable.value = mutable.value.copy(toolId = id, error = null)
        refresh()
    }

    fun onXOffset(text: String) {
        mutable.value = mutable.value.copy(xOffset = mutable.value.xOffset.withText(text), error = null)
    }

    fun onZOffset(text: String) {
        mutable.value = mutable.value.copy(zOffset = mutable.value.zOffset.withText(text), error = null)
    }

    fun onXUnit(metric: Boolean) {
        mutable.value = mutable.value.copy(xOffset = mutable.value.xOffset.withUnit(metric))
    }

    fun onZUnit(metric: Boolean) {
        mutable.value = mutable.value.copy(zOffset = mutable.value.zOffset.withUnit(metric))
    }

    /** "Set Tool Offset". Gives true when the sheet can close. */
    fun onApply(): Boolean {
        val state = mutable.value
        val selected = tool(state.toolId)
        if (selected == null && (state.xOffset.error != null || state.zOffset.error != null)) {
            mutable.value = state.copy(error = state.xOffset.error ?: state.zOffset.error)
            return false
        }
        val result = commit(UiText.of(Res.string.fns_undo_lathe_offset)) { workspace ->
            ToolCompensation.latheOffset(workspace, state.xOffset.valueUm, state.zOffset.valueUm, selected)
        }
        if (result is CompensationResult.Failure) {
            mutable.value = state.copy(error = result.message.toUiText())
            return false
        }
        return true
    }

    /** "Select Tool". Gives true when the sheet can close. */
    fun onSelectTool(): Boolean {
        val state = mutable.value
        val selected = tool(state.toolId) ?: return false
        if (frame == null) return false
        readout.positionUnavailable()?.let { refusal ->
            mutable.value = state.copy(error = refusal)
            return false
        }
        readout.applyWorkspace(UiText.of(Res.string.fns_undo_select_tool, selected.name)) { workspace ->
            ToolCompensation.selectTool(workspace, selected)
        }
        return true
    }

    /** "Clear Offset": clears all tool offsets and the tool, with undo. */
    fun onClear(): Boolean {
        if (frame == null) return false
        readout.applyWorkspace(UiText.of(Res.string.fns_undo_lathe_clear), ToolCompensation::clearLatheOffset)
        return true
    }
}
