package org.superdro.core.ui.graphics.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.graphics.GuideOrientation
import org.superdro.core.functions.graphics.GuideRules
import org.superdro.core.functions.graphics.PlaneAxes
import org.superdro.core.model.AxisId
import org.superdro.core.model.Plane
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.resources.Res
import org.superdro.core.resources.graphics_add_guide_add
import org.superdro.core.resources.graphics_add_guide_duplicate
import org.superdro.core.resources.graphics_add_guide_horizontal
import org.superdro.core.resources.graphics_add_guide_invalid
import org.superdro.core.resources.graphics_add_guide_position
import org.superdro.core.resources.graphics_add_guide_title
import org.superdro.core.resources.graphics_add_guide_vertical
import org.superdro.core.resources.graphics_cancel
import org.superdro.core.resources.graphics_sync
import org.superdro.core.ui.graphics.common.LengthInput
import org.superdro.core.ui.graphics.common.LengthTexts
import org.superdro.core.ui.graphics.common.ValueField
import org.superdro.core.ui.graphics.common.evaluateLength
import org.superdro.core.ui.graphics.common.unitText
import org.superdro.core.ui.presenter.Presenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AccentButtons

/**
 * The state of the "Add guide" dialog.
 *
 * @property axis The axis that the guide stores: a horizontal guide stores the vertical axis of the
 *   plane, a vertical guide the horizontal axis.
 * @property metric The unit of the field.
 */
@Immutable
data class AddGuideUiState(
    val text: String = "",
    val orientation: GuideOrientation = GuideOrientation.Vertical,
    val axis: AxisId = AxisId.X,
    val metric: Boolean = true,
    val error: UiText? = null,
)

/**
 * The presenter of the "Add guide" dialog. The field has the position in the shown unit
 * of the axis (an expression; `++x` and `--x` use the absolute position). The sync button copies
 * the absolute position. The orientation is vertical by default. The app does not add a
 * duplicate guide.
 */
class AddGuidePresenter(private val tab: GraphicalTabPresenter) : Presenter {

    private val request = tab.addGuide.value
    private val mutable = MutableStateFlow(initial())

    /** The state of the dialog. */
    val uiState: StateFlow<AddGuideUiState> = mutable.asStateFlow()

    /** The field changed. */
    fun onText(text: String) {
        mutable.update { state -> state.copy(text = text, error = null) }
    }

    /** The orientation changed: the field gets the coordinate of the touch position on the new axis. */
    fun onOrientation(orientation: GuideOrientation) {
        val frame = tab.readout.frame.value ?: return
        val plane = planeOf(frame)
        val axis = GuideRules.axisOf(plane, orientation)
        mutable.value = AddGuideUiState(
            text = touchText(frame, plane, orientation),
            orientation = orientation,
            axis = axis,
            metric = frame.metric(axis),
        )
    }

    /** The sync button: the absolute position of the axis. */
    fun onSync() {
        val frame = tab.readout.latestFrame() ?: return
        val axis = mutable.value.axis
        val reading = frame.reading(axis)
        if (!reading.hasValue) return
        mutable.update { state -> state.copy(text = format(frame, axis, frame.postProcessor(axis).toShown(reading.absolute)), error = null) }
    }

    /** "Add Guide": gives true when the guide is stored and the dialog closes. */
    fun onAdd(): Boolean {
        val frame = tab.readout.latestFrame() ?: return false
        val state = mutable.value
        val post = frame.postProcessor(state.axis)
        val reading = frame.reading(state.axis)
        val current = if (reading.hasValue) post.toShown(reading.absolute) else 0.0
        val shown = when (val input = evaluateLength(state.text, state.metric, current)) {
            is LengthInput.Value -> input.um
            else -> {
                mutable.update { value -> value.copy(error = UiText.of(Res.string.graphics_add_guide_invalid)) }
                return false
            }
        }
        if (!tab.addGuide(state.orientation, post.toInternal(shown))) {
            mutable.update { value -> value.copy(error = UiText.of(Res.string.graphics_add_guide_duplicate)) }
            return false
        }
        tab.onAddGuideClosed()
        return true
    }

    /** "Cancel" or the back gesture. */
    fun onCancel() {
        tab.onAddGuideClosed()
    }

    override fun close() = Unit

    private fun initial(): AddGuideUiState {
        val frame = tab.readout.frame.value ?: return AddGuideUiState()
        val plane = planeOf(frame)
        val orientation = GuideOrientation.Vertical
        val axis = GuideRules.axisOf(plane, orientation)
        return AddGuideUiState(touchText(frame, plane, orientation), orientation, axis, frame.metric(axis))
    }

    private fun planeOf(frame: ReadoutFrame): Plane = request?.plane ?: PlaneAxes.planeFor(frame.profile.machineType.isLathe, frame.workspace.plane)

    /** The coordinate of the touch position on the axis of [orientation], in the shown unit. */
    private fun touchText(frame: ReadoutFrame, plane: Plane, orientation: GuideOrientation): String {
        val position = request?.position ?: return ""
        val (h, v) = PlaneAxes.fromView(plane, position)
        val axis = GuideRules.axisOf(plane, orientation)
        val internal = if (orientation == GuideOrientation.Vertical) h else v
        return format(frame, axis, frame.postProcessor(axis).toShown(internal))
    }

    private fun format(frame: ReadoutFrame, axis: AxisId, shownUm: Double): String =
        LengthTexts.format(shownUm, frame.metric(axis), frame.profile.display.readoutFormat)
}

/** The test tag of the position field of the "Add guide" dialog. */
const val ADD_GUIDE_FIELD_TAG: String = "add-guide-position"

/** The test tag of the "Add Guide" button. */
const val ADD_GUIDE_BUTTON_TAG: String = "add-guide-add"

/** The "Add guide" dialog: an `AlertDialog`. */
@Composable
fun AddGuideDialog(
    state: AddGuideUiState,
    onText: (String) -> Unit,
    onOrientation: (GuideOrientation) -> Unit,
    onSync: () -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.graphics_add_guide_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val options = listOf(GuideOrientation.Vertical to Res.string.graphics_add_guide_vertical, GuideOrientation.Horizontal to Res.string.graphics_add_guide_horizontal)
                SingleChoiceSegmentedButtonRow {
                    options.forEachIndexed { index, (orientation, label) ->
                        SegmentedButton(
                            selected = state.orientation == orientation,
                            onClick = { onOrientation(orientation) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(label)) }
                    }
                }
                ValueField(
                    label = "${stringResource(Res.string.graphics_add_guide_position)} ${state.axis.name}",
                    text = state.text,
                    onTextChange = onText,
                    unit = unitText(state.metric),
                    error = state.error,
                    syncLabel = stringResource(Res.string.graphics_sync),
                    onSync = onSync,
                    fieldModifier = Modifier.testTag(ADD_GUIDE_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd, colors = AccentButtons.text(), modifier = Modifier.testTag(ADD_GUIDE_BUTTON_TAG)) {
                Text(stringResource(Res.string.graphics_add_guide_add))
            }
        },
        dismissButton = { TextButton(onClick = onCancel, colors = AccentButtons.text()) { Text(stringResource(Res.string.graphics_cancel)) } },
    )
}
