package org.superdro.core.ui.functions.tools

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.model.AxisId
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The state of the tool edit sheet.
 *
 * @property metric The unit of the length fields: the unit of a mill tool, or the metric default
 *   of the profile for a lathe tool.
 * @property errors The error of each field after "Save".
 */
@Immutable
data class ToolEditUiState(
    val loaded: Boolean = false,
    val newTool: Boolean = true,
    val kind: ToolKind = ToolKind.Mill,
    val name: String = "",
    val description: String = "",
    val metric: Boolean = false,
    val diameter: LengthFieldState = LengthFieldState(),
    val toothCount: NumberFieldState = NumberFieldState(),
    val zOffset: LengthFieldState = LengthFieldState(),
    val xOffset: LengthFieldState = LengthFieldState(),
    val internal: Boolean = false,
    val errors: Map<ToolField, UiText> = emptyMap(),
    val message: UiText? = null,
    val toolId: String? = null,
    val geometryDefined: Boolean = false,
) {
    val lathe: Boolean get() = kind == ToolKind.Lathe

    /**
     * True when the cutter geometry button opens the geometry screen: a lathe tool
     * that the library has. A new tool must be saved first.
     */
    val geometryAvailable: Boolean get() = lathe && toolId != null
}

/**
 * The presenter of the tool edit sheet.
 * A new tool goes into the library that the profile uses now. A horizontal mill uses the mill
 * tools. A lathe tool stores "OD/Facing" or "Boring".
 *
 * @param toolId The tool to edit, or null for a new tool.
 * @param frame The readout of the tab, for the sync buttons of a lathe tool.
 * @param positionUnavailable The check of the position for the sync buttons: null when the session is
 *   `Connected`, else the text.
 * @param latestFrame Gives the frame with the newest inputs (`TabReadout.latestFrame`). A sync
 *   button uses it, because [frame] can be one display frame older.
 */
class ToolEditPresenter(
    private val profileId: String,
    private val toolId: String?,
    private val services: FunctionServices,
    private val frame: StateFlow<ReadoutFrame?>,
    private val positionUnavailable: () -> UiText?,
    private val latestFrame: () -> ReadoutFrame? = { frame.value },
) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow(ToolEditUiState())
    private var base: Tool? = null

    /** The UI state of the sheet. */
    val uiState: StateFlow<ToolEditUiState> = mutable.asStateFlow()

    init {
        scope.launch { load() }
        // The cutter geometry screen writes the geometry into the stored tool. The sheet
        // shows the stored state when the user comes back.
        if (toolId != null) {
            scope.launch {
                services.tools.library(profileId)
                    .map { tools -> tools.firstOrNull { tool -> tool.id == toolId }?.geometry != null }
                    .distinctUntilChanged()
                    .collect { defined -> mutable.update { state -> state.copy(geometryDefined = defined) } }
            }
        }
    }

    private suspend fun load() {
        val profile = services.machines.loadProfile(profileId) ?: return
        val kind = ToolRules.kindOf(profile.machineType)
        val tool = toolId?.let { id -> services.tools.loadLibrary(profileId).firstOrNull { it.id == id } }
        base = tool
        val metric = if (kind == ToolKind.Lathe) profile.metricDefault else tool?.metric ?: profile.metricDefault
        mutable.value = ToolEditUiState(
            loaded = true,
            newTool = tool == null,
            kind = kind,
            name = tool?.name.orEmpty(),
            description = tool?.description.orEmpty(),
            metric = metric,
            diameter = LengthFieldState.of(tool?.diameterUm, metric),
            toothCount = NumberFieldState.ofCount(tool?.toothCount),
            zOffset = LengthFieldState.of(tool?.zOffsetUm, metric),
            xOffset = LengthFieldState.of(tool?.xOffsetUm?.takeIf { kind == ToolKind.Lathe }, metric),
            internal = tool?.internal ?: false,
            toolId = tool?.id,
            geometryDefined = tool?.geometry != null,
        )
    }

    fun onName(text: String) = mutable.update { it.copy(name = text, errors = it.errors - ToolField.Name) }

    fun onDescription(text: String) = mutable.update { it.copy(description = text, errors = it.errors - ToolField.Description) }

    /** Sets the unit of a mill tool. The length fields show the same lengths in the new unit. */
    fun onMetric(metric: Boolean) = mutable.update { state ->
        if (state.lathe) {
            state
        } else {
            state.copy(
                metric = metric,
                diameter = state.diameter.withUnit(metric),
                zOffset = state.zOffset.withUnit(metric),
            )
        }
    }

    fun onDiameter(text: String) = mutable.update { it.copy(diameter = it.diameter.withText(text), errors = it.errors - ToolField.Diameter) }

    fun onToothCount(text: String) = mutable.update { it.copy(toothCount = it.toothCount.withText(text), errors = it.errors - ToolField.ToothCount) }

    fun onZOffset(text: String) = mutable.update { it.copy(zOffset = it.zOffset.withText(text), errors = it.errors - ToolField.ZOffset) }

    fun onXOffset(text: String) = mutable.update { it.copy(xOffset = it.xOffset.withText(text), errors = it.errors - ToolField.XOffset) }

    fun onInternal(internal: Boolean) = mutable.update { it.copy(internal = internal) }

    /**
     * A sync button of a lathe tool: the field of [axis] (X or Z) gets
     * `−(shown position − active tool offset)`. Touch the work with the new tool at the zero point
     * of the master tool, then push the button. The X value is a radius, as the stored X offset.
     */
    fun onSync(axis: AxisId) {
        positionUnavailable()?.let { refusal ->
            mutable.update { it.copy(message = refusal) }
            return
        }
        val current = latestFrame() ?: return
        val reading = current.reading(axis)
        if (!reading.hasValue) return
        val value = ToolRules.syncValue(reading.value, reading.toolOffset)
        mutable.update { state ->
            when (axis) {
                AxisId.X -> state.copy(xOffset = state.xOffset.withValue(value), errors = state.errors - ToolField.XOffset, message = null)
                AxisId.Z -> state.copy(zOffset = state.zOffset.withValue(value), errors = state.errors - ToolField.ZOffset, message = null)
                else -> state
            }
        }
    }

    /** The sheet showed [message]. */
    fun onMessageShown() = mutable.update { it.copy(message = null) }

    /**
     * Checks the fields and writes the tool. Gives true when the tool is stored and the
     * sheet can close.
     */
    suspend fun save(): Boolean {
        val state = mutable.value
        if (!state.loaded) return false
        val input = ToolInput(
            kind = state.kind,
            name = state.name,
            description = state.description,
            metric = state.metric,
            diameterUm = state.diameter.valueUm,
            toothCount = state.toothCount.value,
            zOffsetUm = state.zOffset.valueUm,
            xOffsetUm = state.xOffset.valueUm,
            internal = state.internal,
        )
        // The geometry screen can change the geometry of the stored tool while this sheet is open.
        // The save keeps the stored geometry, not the copy of the load.
        val stored = base?.let { current -> services.tools.loadLibrary(profileId).firstOrNull { tool -> tool.id == current.id } }
        val template = if (stored != null) base?.copy(geometry = stored.geometry) else base
        return when (val check = ToolRules.check(input, template)) {
            is ToolCheck.Invalid -> {
                mutable.update { it.copy(errors = check.errors) }
                false
            }
            is ToolCheck.Valid -> {
                val result = if (template == null) services.tools.addTool(profileId, check.tool) else services.tools.updateTool(check.tool)
                when (result) {
                    is StoreResult.Ok -> true
                    is StoreResult.Refused -> {
                        mutable.update { it.copy(message = result.refusal.toUiText()) }
                        false
                    }
                }
            }
        }
    }
}
