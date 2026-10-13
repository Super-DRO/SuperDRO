package org.superdro.core.ui.graphics.drawing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.ShapeType
import org.superdro.core.resources.Res
import org.superdro.core.resources.drawing_delete_message
import org.superdro.core.resources.drawing_delete_shape_message
import org.superdro.core.resources.drawing_delete_shape_title
import org.superdro.core.resources.drawing_delete_title
import org.superdro.core.resources.drawing_export_hidden_layers
import org.superdro.core.resources.drawing_export_title
import org.superdro.core.resources.drawing_layer_unnamed
import org.superdro.core.resources.drawingui_cancel
import org.superdro.core.resources.drawingui_delete
import org.superdro.core.resources.drawingui_export
import org.superdro.core.resources.drawingui_layer_hidden
import org.superdro.core.resources.drawingui_layer_shown
import org.superdro.core.resources.drawingui_layer_toggle
import org.superdro.core.resources.drawingui_type_arc
import org.superdro.core.resources.drawingui_type_circle
import org.superdro.core.resources.drawingui_type_curve
import org.superdro.core.resources.drawingui_type_line
import org.superdro.core.resources.drawingui_type_point
import org.superdro.core.resources.drawingui_type_polyline
import org.superdro.core.resources.ic_drawing_visibility
import org.superdro.core.resources.ic_drawing_visibility_off
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor
import org.superdro.core.ui.theme.accentCheckboxColors

/** The test tag of the confirm button of [DeleteShapeDialog] and [DeleteDrawingDialog]. */
const val DRAWING_DELETE_CONFIRM_TAG: String = "drawing-delete-confirm"

/** The test tag of a row of [DrawingLayerList]: the tag and the layer index. */
fun drawingLayerTag(index: Int): String = "drawing-layer-$index"

/** Gives the name of a shape type for "Are you sure you want to remove this %1$s?". */
fun shapeTypeName(type: ShapeType): StringResource = when (type) {
    ShapeType.Line -> Res.string.drawingui_type_line
    ShapeType.Point -> Res.string.drawingui_type_point
    ShapeType.Circle -> Res.string.drawingui_type_circle
    ShapeType.Arc -> Res.string.drawingui_type_arc
    ShapeType.Polyline -> Res.string.drawingui_type_polyline
    ShapeType.Bezier -> Res.string.drawingui_type_curve
}

/** "Delete Geometry?" for one shape of the drawing. */
@Composable
fun DeleteShapeDialog(type: ShapeType, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.drawing_delete_shape_title)) },
        text = { Text(stringResource(Res.string.drawing_delete_shape_message, stringResource(shapeTypeName(type)))) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = AccentButtons.text(), modifier = Modifier.testTag(DRAWING_DELETE_CONFIRM_TAG)) {
                Text(stringResource(Res.string.drawingui_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.drawingui_cancel)) } },
    )
}

/** "Delete Drawing?" for the drawing of the workspace. The sub-datums stay. */
@Composable
fun DeleteDrawingDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.drawing_delete_title)) },
        text = { Text(stringResource(Res.string.drawing_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = AccentButtons.text(), modifier = Modifier.testTag(DRAWING_DELETE_CONFIRM_TAG)) {
                Text(stringResource(Res.string.drawingui_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.drawingui_cancel)) } },
    )
}

/** The DXF export of the workspace with the choice of the hidden layers. */
@Composable
fun DxfExportDialog(includeHidden: Boolean, onIncludeHidden: (Boolean) -> Unit, onExport: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.drawing_export_title)) },
        text = {
            Row(
                Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button)
                    .clickable(role = Role.Checkbox) { onIncludeHidden(!includeHidden) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = includeHidden,
                    onCheckedChange = onIncludeHidden,
                    colors = accentCheckboxColors(),
                )
                Text(stringResource(Res.string.drawing_export_hidden_layers), style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = { TextButton(onClick = onExport, colors = AccentButtons.text()) { Text(stringResource(Res.string.drawingui_export)) } },
        dismissButton = { TextButton(onClick = onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.drawingui_cancel)) } },
    )
}

/**
 * The layer list of the drawing: the name, or "Layer %1$d"
 * for a layer with no name, an eye icon, and the state as a text. A tap shows or hides the
 * layer.
 */
@Composable
fun DrawingLayerList(layers: List<DrawingLayer>, onToggle: (layerIndex: Int, hidden: Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        layers.sortedBy { layer -> layer.index }.forEach { layer ->
            val name = layer.name.ifEmpty { stringResource(Res.string.drawing_layer_unnamed, layer.index) }
            val state = stringResource(if (layer.hidden) Res.string.drawingui_layer_hidden else Res.string.drawingui_layer_shown)
            val description = stringResource(Res.string.drawingui_layer_toggle, name)
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = LocalTouchTargets.current.button)
                    .clickable(role = Role.Switch) { onToggle(layer.index, !layer.hidden) }
                    .semantics {
                        contentDescription = description
                        stateDescription = state
                    }
                    .padding(horizontal = 8.dp)
                    .testTag(drawingLayerTag(layer.index)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (layer.hidden) Res.drawable.ic_drawing_visibility_off else Res.drawable.ic_drawing_visibility),
                    contentDescription = null,
                    tint = if (layer.hidden) MaterialTheme.colorScheme.onSurfaceVariant else accentColor(),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(state, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
