package org.superdro.core.ui.graphics.drawing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.drawingui_apply
import org.superdro.core.resources.drawingui_cancel
import org.superdro.core.resources.drawingui_clear
import org.superdro.core.resources.drawingui_create_sub_datums
import org.superdro.core.resources.drawingui_mirror_horizontal
import org.superdro.core.resources.drawingui_mirror_vertical
import org.superdro.core.resources.drawingui_pick_hint
import org.superdro.core.resources.drawingui_position
import org.superdro.core.resources.drawingui_preview_description
import org.superdro.core.resources.drawingui_reference
import org.superdro.core.resources.drawingui_reference_not_set
import org.superdro.core.resources.drawingui_reference_set
import org.superdro.core.resources.drawingui_reset
import org.superdro.core.resources.drawingui_rotate_left
import org.superdro.core.resources.drawingui_rotate_right
import org.superdro.core.resources.drawingui_set
import org.superdro.core.resources.drawingui_setup_title
import org.superdro.core.resources.drawingui_show_on_drawing
import org.superdro.core.resources.drawingui_snap_points
import org.superdro.core.resources.drawingui_sync
import org.superdro.core.resources.drawingui_sync_description
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_drawing_flip
import org.superdro.core.resources.ic_drawing_rotate_left
import org.superdro.core.resources.ic_drawing_rotate_right
import org.superdro.core.resources.ic_radio_unchecked
import org.superdro.core.resources.ic_warning
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.graphics.common.ValueField
import org.superdro.core.ui.graphics.common.unitText
import org.superdro.core.ui.graphics.render.ShapePaths
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor

/** The test tag of the preview of "Set Up Drawing". */
const val DRAWING_PREVIEW_TAG: String = "drawing-setup-preview"

/** The test tag of the "Apply" button. */
const val DRAWING_APPLY_TAG: String = "drawing-setup-apply"

/** The test tag of the unit banner. */
const val DRAWING_BANNER_TAG: String = "drawing-setup-banner"

/** The test tag of the status line. */
const val DRAWING_STATUS_TAG: String = "drawing-setup-status"

/** The test tag of the "Set" or "Clear" button of the card [index]. */
fun drawingCardSetTag(index: Int): String = "drawing-setup-card-set-$index"

/** The test tag of the sync button of the card [index]. */
fun drawingCardSyncTag(index: Int): String = "drawing-setup-card-sync-$index"

/** The test tag of the "Create as sub-datums" chip of the group [index]. */
fun drawingGroupCreateTag(index: Int): String = "drawing-setup-group-create-$index"

/** The test tag of the mirror and rotate buttons: the name of the step. */
fun drawingStepTag(step: String): String = "drawing-setup-step-$step"

/** The events of "Set Up Drawing". [DrawingSetupPresenter] has a function for each one. */
interface DrawingSetupEvents {
    fun onPreviewSize(width: Double, height: Double)
    fun onPreviewPan(dx: Double, dy: Double)
    fun onPreviewZoom(factor: Double, focusX: Double, focusY: Double)
    fun onPreviewFit()
    fun onPreviewTap(x: Double, y: Double, density: Double)
    fun onRotateLeft()
    fun onRotateRight()
    fun onMirrorHorizontal()
    fun onMirrorVertical()
    fun onCardXText(index: Int, text: String)
    fun onCardYText(index: Int, text: String)
    fun onCardSync(index: Int)
    fun onCardSet(index: Int)
    fun onGroupShown(index: Int, shown: Boolean)
    fun onGroupCreate(index: Int, create: Boolean)
    fun onApply()
    fun onCancel()
    fun onReset()
}

/** Gives the [DrawingSetupEvents] of [presenter]. */
fun DrawingSetupPresenter.events(): DrawingSetupEvents = object : DrawingSetupEvents {
    override fun onPreviewSize(width: Double, height: Double) = this@events.onPreviewSize(width, height)
    override fun onPreviewPan(dx: Double, dy: Double) = this@events.onPreviewPan(dx, dy)
    override fun onPreviewZoom(factor: Double, focusX: Double, focusY: Double) = this@events.onPreviewZoom(factor, focusX, focusY)
    override fun onPreviewFit() = this@events.onPreviewFit()
    override fun onPreviewTap(x: Double, y: Double, density: Double) = this@events.onPreviewTap(x, y, density)
    override fun onRotateLeft() = this@events.onRotateLeft()
    override fun onRotateRight() = this@events.onRotateRight()
    override fun onMirrorHorizontal() = this@events.onMirrorHorizontal()
    override fun onMirrorVertical() = this@events.onMirrorVertical()
    override fun onCardXText(index: Int, text: String) = this@events.onCardXText(index, text)
    override fun onCardYText(index: Int, text: String) = this@events.onCardYText(index, text)
    override fun onCardSync(index: Int) = this@events.onCardSync(index)
    override fun onCardSet(index: Int) = this@events.onCardSet(index)
    override fun onGroupShown(index: Int, shown: Boolean) = this@events.onGroupShown(index, shown)
    override fun onGroupCreate(index: Int, create: Boolean) = this@events.onGroupCreate(index, create)
    override fun onApply() = this@events.onApply()
    override fun onCancel() = this@events.onCancel()
    override fun onReset() = this@events.onReset()
}

/**
 * "Set Up Drawing". The screen is stateless:
 * [DrawingSetupPresenter] gives the state. In landscape the preview is at the left and the cards
 * are at the right. In portrait the preview is at the top.
 */
@Composable
fun DrawingSetupScreen(state: DrawingSetupUiState, events: DrawingSetupEvents) {
    val landscape = LocalWindowLayout.current.landscape
    ScreenScaffold(
        title = stringResource(Res.string.drawingui_setup_title),
        onBack = events::onCancel,
        actions = {
            TextButton(onClick = events::onReset, enabled = !state.busy, colors = AccentButtons.text()) {
                Text(stringResource(Res.string.drawingui_reset))
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            state.banner?.let { banner -> Banner(banner.resolve()) }
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Preview(state, events, Modifier.weight(0.6f).fillMaxHeight())
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SidePanel(state, events, Modifier.weight(0.4f).fillMaxHeight())
                }
            } else {
                Preview(state, events, Modifier.weight(0.5f).fillMaxWidth())
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SidePanel(state, events, Modifier.weight(0.5f).fillMaxWidth())
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BottomBar(state, events)
        }
    }
}

@Composable
private fun Banner(text: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.fillMaxWidth().testTag(DRAWING_BANNER_TAG)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Preview(state: DrawingSetupUiState, events: DrawingSetupEvents, modifier: Modifier) {
    val paths = remember(state.previewShapes) { DrawingPaths(state.previewShapes) }
    val colors = drawingStrokeColors()
    val scheme = MaterialTheme.colorScheme
    val accent = accentColor()
    val description = stringResource(Res.string.drawingui_preview_description)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton(Res.drawable.ic_drawing_flip, stringResource(Res.string.drawingui_mirror_horizontal), "mirror-h", 0f, events::onMirrorHorizontal)
            StepButton(Res.drawable.ic_drawing_flip, stringResource(Res.string.drawingui_mirror_vertical), "mirror-v", 90f, events::onMirrorVertical)
            StepButton(Res.drawable.ic_drawing_rotate_left, stringResource(Res.string.drawingui_rotate_left), "rotate-left", 0f, events::onRotateLeft)
            StepButton(Res.drawable.ic_drawing_rotate_right, stringResource(Res.string.drawingui_rotate_right), "rotate-right", 0f, events::onRotateRight)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxSize()
                    .testTag(DRAWING_PREVIEW_TAG)
                    .semantics { contentDescription = description }
                    .onSizeChanged { size -> events.onPreviewSize(size.width.toDouble(), size.height.toDouble()) }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (zoom != 1f) events.onPreviewZoom(zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble())
                            if (pan != Offset.Zero) events.onPreviewPan(pan.x.toDouble(), pan.y.toDouble())
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { events.onPreviewFit() },
                            onTap = { offset -> events.onPreviewTap(offset.x.toDouble(), offset.y.toDouble(), density.toDouble()) },
                        )
                    },
            ) {
                drawRect(scheme.surfaceContainerLowest)
                val view = state.view
                // The workspace origin: two short lines.
                val origin = view.toScreen(0.0, 0.0)
                val arm = 12.dp.toPx()
                val center = Offset(origin.x.toFloat(), origin.y.toFloat())
                drawLine(scheme.error, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth = 1.5.dp.toPx())
                drawLine(scheme.error, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth = 1.5.dp.toPx())
                drawDrawing(paths, ShapePaths.composeMatrix(view.matrix()), colors)
                val radius = 6.dp.toPx()
                state.marks.forEach { mark ->
                    val position = view.toScreen(mark.h, mark.v)
                    val point = Offset(position.x.toFloat(), position.y.toFloat())
                    if (mark.picked) {
                        drawCircle(accent, radius * 1.4f, point)
                    } else {
                        drawCircle(scheme.tertiary, radius, point, style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
            }
            state.message?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                ) { Text(message.resolve(), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun StepButton(icon: DrawableResource, label: String, tag: String, rotation: Float, onClick: () -> Unit) {
    val size = LocalTouchTargets.current.button
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(size).testTag(drawingStepTag(tag))) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(28.dp).rotate(rotation))
    }
}

@Composable
private fun SidePanel(state: DrawingSetupUiState, events: DrawingSetupEvents, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LiveReadout(state)
        state.cards.forEach { card -> ReferenceCard(state, card, events) }
        SnapGroups(state, events)
    }
}

@Composable
private fun LiveReadout(state: DrawingSetupUiState) {
    val unit = unitText(state.metric)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.drawingui_position), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text("${state.horizontalAxis.name} ${state.horizontalValue} $unit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(16.dp))
        Text("${state.verticalAxis.name} ${state.verticalValue} $unit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ReferenceCard(state: DrawingSetupUiState, card: ReferenceCardUiState, events: DrawingSetupEvents) {
    val unit = unitText(state.metric)
    val targets = LocalTouchTargets.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.drawingui_reference, card.index + 1), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    painterResource(if (card.set) Res.drawable.ic_check else Res.drawable.ic_radio_unchecked),
                    contentDescription = null,
                    tint = if (card.set) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(if (card.set) Res.string.drawingui_reference_set else Res.string.drawingui_reference_not_set),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!card.hasPick) {
                Text(stringResource(Res.string.drawingui_pick_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ValueField(state.horizontalAxis.name, card.xText, { text -> events.onCardXText(card.index, text) }, unit, enabled = !card.set)
            ValueField(state.verticalAxis.name, card.yText, { text -> events.onCardYText(card.index, text) }, unit, enabled = !card.set, error = card.error)
            Row(horizontalArrangement = Arrangement.spacedBy(targets.gap)) {
                val syncDescription = stringResource(Res.string.drawingui_sync_description, "${state.horizontalAxis.name}, ${state.verticalAxis.name}")
                OutlinedButton(
                    onClick = { events.onCardSync(card.index) },
                    enabled = !card.set,
                    colors = AccentButtons.outlined(),
                    modifier = Modifier.heightIn(min = targets.button).semantics { contentDescription = syncDescription }.testTag(drawingCardSyncTag(card.index)),
                ) { Text(stringResource(Res.string.drawingui_sync)) }
                FilledTonalButton(
                    onClick = { events.onCardSet(card.index) },
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = targets.button).testTag(drawingCardSetTag(card.index)),
                ) { Text(stringResource(if (card.set) Res.string.drawingui_clear else Res.string.drawingui_set)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.SnapGroups(state: DrawingSetupUiState, events: DrawingSetupEvents) {
    if (state.groups.isEmpty()) return
    Text(stringResource(Res.string.drawingui_snap_points), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    val accent = accentColor()
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer)
    state.groups.forEachIndexed { index, group ->
        Column(Modifier.fillMaxWidth()) {
            Text(group.label.resolve(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = group.shown,
                    onClick = { events.onGroupShown(index, !group.shown) },
                    label = { Text(stringResource(Res.string.drawingui_show_on_drawing)) },
                    leadingIcon = if (group.shown) {
                        { Icon(painterResource(Res.drawable.ic_check), contentDescription = null, tint = accent, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    colors = chipColors,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = group.create,
                    onClick = { events.onGroupCreate(index, !group.create) },
                    label = { Text(stringResource(Res.string.drawingui_create_sub_datums)) },
                    leadingIcon = if (group.create) {
                        { Icon(painterResource(Res.drawable.ic_check), contentDescription = null, tint = accent, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    colors = chipColors,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(drawingGroupCreateTag(index)),
                )
            }
        }
    }
}

@Composable
private fun BottomBar(state: DrawingSetupUiState, events: DrawingSetupEvents) {
    val targets = LocalTouchTargets.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            state.status?.resolve().orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag(DRAWING_STATUS_TAG),
        )
        Spacer(Modifier.width(targets.gap))
        TextButton(onClick = events::onCancel, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = targets.button)) {
            Text(stringResource(Res.string.drawingui_cancel))
        }
        Spacer(Modifier.width(targets.gap))
        FilledTonalButton(
            onClick = events::onApply,
            enabled = !state.busy && state.previewShapes.isNotEmpty(),
            modifier = Modifier.heightIn(min = targets.button).testTag(DRAWING_APPLY_TAG),
        ) { Text(stringResource(Res.string.drawingui_apply)) }
    }
}
