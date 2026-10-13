package org.superdro.core.ui.graphics.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.GraphicalDisplay
import org.superdro.core.resources.Res
import org.superdro.core.resources.graphics_camera_setup
import org.superdro.core.resources.graphics_clear
import org.superdro.core.resources.graphics_dim
import org.superdro.core.resources.graphics_display
import org.superdro.core.resources.graphics_export_dxf
import org.superdro.core.resources.graphics_import
import org.superdro.core.resources.graphics_layer_camera
import org.superdro.core.resources.graphics_layer_crosshairs
import org.superdro.core.resources.graphics_layer_drawing
import org.superdro.core.resources.graphics_layer_ghosts
import org.superdro.core.resources.graphics_layer_guides
import org.superdro.core.resources.graphics_layer_reference
import org.superdro.core.resources.graphics_layer_sub_datums
import org.superdro.core.resources.graphics_layer_tool
import org.superdro.core.resources.graphics_lock
import org.superdro.core.resources.graphics_no_drawing
import org.superdro.core.resources.graphics_no_image
import org.superdro.core.resources.graphics_select_image
import org.superdro.core.resources.graphics_shown
import org.superdro.core.resources.graphics_take_photo

/** The test tag of the display pane. */
const val DISPLAY_PANE_TAG: String = "graphical-display-pane"

/** The actions of the display pane that are not display state of the profile. */
interface DisplayPaneEvents {
    /** Changes the display state of the profile. */
    fun onDisplay(change: (GraphicalDisplay) -> GraphicalDisplay)

    /** "Clear" of the guides row: delete all guides with undo. */
    fun onClearGuides()

    /** "Import" of the drawing row. */
    fun onImportDrawing()

    /** "Export DXF" of the drawing row. */
    fun onExportDrawing()

    /** "Clear" of the drawing row: "Delete Drawing?". */
    fun onClearDrawing()

    /** Shows or hides the drawing layer [index]. */
    fun onLayerHidden(index: Int, hidden: Boolean)

    /** "Setup" of the camera row. */
    fun onCameraSetup()

    /** The alpha of the reference image: 255 shown, 127 dimmed, 0 hidden. */
    fun onReferenceAlpha(alpha: Int)

    /** "Clear" of the reference image row: "Delete Reference Image?". */
    fun onClearReference()

    /** "Take photo" of the reference image row. */
    fun onTakePhoto()

    /** "Select" of the reference image row. */
    fun onSelectImage()
}

/**
 * The display pane of the graphical view: show, hide, dim, lock, and clear for each layer. Each
 * toggle shows its state with an icon
 * and a text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplayPane(
    display: GraphicalDisplay,
    hasDrawing: Boolean,
    layers: List<DrawingLayer>,
    referenceAlpha: Int?,
    events: DisplayPaneEvents,
    modifier: Modifier = Modifier,
    layerList: @Composable (List<DrawingLayer>) -> Unit = { list -> DefaultLayerList(list, events) },
) {
    Column(
        modifier.padding(12.dp).verticalScroll(rememberScrollState()).testTag(DISPLAY_PANE_TAG),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(Res.string.graphics_display), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        val shown = stringResource(Res.string.graphics_shown)
        val dim = stringResource(Res.string.graphics_dim)
        val clear = stringResource(Res.string.graphics_clear)
        Row(Res.string.graphics_layer_tool) {
            ToggleChip(shown, display.toolShown, { events.onDisplay { value -> value.copy(toolShown = !value.toolShown) } }, rowTag("tool-shown"))
            ToggleChip(dim, display.toolDimmed, { events.onDisplay { value -> value.copy(toolDimmed = !value.toolDimmed) } }, rowTag("tool-dim"))
        }
        Row(Res.string.graphics_layer_guides) {
            ToggleChip(shown, display.guidesShown, { events.onDisplay { value -> value.copy(guidesShown = !value.guidesShown) } }, rowTag("guides-shown"))
            ActionButton(clear, events::onClearGuides)
        }
        Row(Res.string.graphics_layer_crosshairs) {
            ToggleChip(shown, display.crosshairsShown, { events.onDisplay { value -> value.copy(crosshairsShown = !value.crosshairsShown) } }, rowTag("crosshairs-shown"))
            ToggleChip(dim, display.crosshairsDimmed, { events.onDisplay { value -> value.copy(crosshairsDimmed = !value.crosshairsDimmed) } }, rowTag("crosshairs-dim"))
            ToggleChip(
                stringResource(Res.string.graphics_lock),
                display.viewLocked,
                { events.onDisplay { value -> value.copy(viewLocked = !value.viewLocked) } },
                rowTag("crosshairs-lock"),
            )
        }
        Row(Res.string.graphics_layer_sub_datums) {
            ToggleChip(shown, display.subDatumsShown, { events.onDisplay { value -> value.copy(subDatumsShown = !value.subDatumsShown) } }, rowTag("sub-datums-shown"))
        }
        Row(Res.string.graphics_layer_ghosts) {
            val ghosts = display.ghostSubDatumsShown && display.ghostGuidesShown
            ToggleChip(shown, ghosts, { events.onDisplay { value -> value.copy(ghostSubDatumsShown = !ghosts, ghostGuidesShown = !ghosts) } }, rowTag("ghosts-shown"))
        }
        Row(Res.string.graphics_layer_drawing) {
            if (hasDrawing) {
                ToggleChip(shown, display.drawingShown, { events.onDisplay { value -> value.copy(drawingShown = !value.drawingShown) } }, rowTag("drawing-shown"))
                ToggleChip(dim, display.drawingDimmed, { events.onDisplay { value -> value.copy(drawingDimmed = !value.drawingDimmed) } }, rowTag("drawing-dim"))
                ActionButton(clear, events::onClearDrawing)
            } else {
                Text(stringResource(Res.string.graphics_no_drawing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActionButton(stringResource(Res.string.graphics_import), events::onImportDrawing)
            ActionButton(stringResource(Res.string.graphics_export_dxf), events::onExportDrawing)
        }
        if (hasDrawing && layers.isNotEmpty()) layerList(layers)
        Row(Res.string.graphics_layer_camera) {
            ToggleChip(shown, display.cameraShown, { events.onDisplay { value -> value.copy(cameraShown = !value.cameraShown) } }, rowTag("camera-shown"))
            ActionButton(stringResource(Res.string.graphics_camera_setup), events::onCameraSetup)
        }
        Row(Res.string.graphics_layer_reference) {
            if (referenceAlpha != null) {
                ToggleChip(shown, referenceAlpha > 0, { events.onReferenceAlpha(if (referenceAlpha > 0) 0 else FULL_ALPHA) }, rowTag("reference-shown"))
                ToggleChip(dim, referenceAlpha == HALF_ALPHA, { events.onReferenceAlpha(if (referenceAlpha == HALF_ALPHA) FULL_ALPHA else HALF_ALPHA) }, rowTag("reference-dim"))
                ActionButton(clear, events::onClearReference)
            } else {
                Text(stringResource(Res.string.graphics_no_image), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActionButton(stringResource(Res.string.graphics_take_photo), events::onTakePhoto)
            ActionButton(stringResource(Res.string.graphics_select_image), events::onSelectImage)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Row(title: org.jetbrains.compose.resources.StringResource, content: @Composable () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
}

/** The default layer list: one chip for each layer with its state. The drawing area gives a better list. */
@Composable
private fun DefaultLayerList(layers: List<DrawingLayer>, events: DisplayPaneEvents) {
    layers.forEach { layer ->
        ToggleChip(layer.name.ifEmpty { "${layer.index}" }, !layer.hidden, { events.onLayerHidden(layer.index, !layer.hidden) })
    }
}

/** The test tag of a toggle of the display pane: `graphical-display-<name>`. */
fun rowTag(name: String): Modifier = Modifier.testTag("graphical-display-$name")

private const val FULL_ALPHA: Int = 255
private const val HALF_ALPHA: Int = 127
