package org.superdro.core.ui.graphics.image

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.graphics.CursorGeometry
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.ScreenMetrics
import org.superdro.core.functions.graphics.TickKind
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.AxisId
import org.superdro.core.resources.Res
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_refimage_arrow_up
import org.superdro.core.resources.ic_refimage_flip
import org.superdro.core.resources.ic_refimage_gray
import org.superdro.core.resources.ic_refimage_invert
import org.superdro.core.resources.ic_refimage_rotate_left
import org.superdro.core.resources.ic_refimage_rotate_right
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.refimage_apply
import org.superdro.core.resources.refimage_clear
import org.superdro.core.resources.refimage_clear_all_hint
import org.superdro.core.resources.refimage_flip_horizontal
import org.superdro.core.resources.refimage_flip_vertical
import org.superdro.core.resources.refimage_gray
import org.superdro.core.resources.refimage_image_description
import org.superdro.core.resources.refimage_invert
import org.superdro.core.resources.refimage_nudge_down
import org.superdro.core.resources.refimage_nudge_left
import org.superdro.core.resources.refimage_nudge_right
import org.superdro.core.resources.refimage_nudge_step
import org.superdro.core.resources.refimage_nudge_up
import org.superdro.core.resources.refimage_pixel
import org.superdro.core.resources.refimage_preview
import org.superdro.core.resources.refimage_reference
import org.superdro.core.resources.refimage_reticle_hint
import org.superdro.core.resources.refimage_return
import org.superdro.core.resources.refimage_rotate_left
import org.superdro.core.resources.refimage_rotate_right
import org.superdro.core.resources.refimage_set
import org.superdro.core.resources.refimage_setup_title
import org.superdro.core.resources.refimage_state_open
import org.superdro.core.resources.refimage_state_set
import org.superdro.core.resources.refimage_sub_datum
import org.superdro.core.resources.refimage_sub_datum_none
import org.superdro.core.resources.refimage_sync
import org.superdro.core.resources.refimage_zero
import org.superdro.core.resources.refimage_zero_description
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.graphics.common.ValueField
import org.superdro.core.ui.graphics.common.unitText
import org.superdro.core.ui.graphics.render.ShapePaths
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.readout.DroButton
import org.superdro.core.ui.readout.DroButtonStyle
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor

/** The test tag of the image view of the setup screen. */
const val REFERENCE_SETUP_IMAGE_TAG: String = "reference-setup-image"

/** The test tag of the "Apply" button. */
const val REFERENCE_SETUP_APPLY_TAG: String = "reference-setup-apply"

/** The test tag of the "Preview" button. */
const val REFERENCE_SETUP_PREVIEW_TAG: String = "reference-setup-preview"

/** The test tag of the "Cancel" button. */
const val REFERENCE_SETUP_CANCEL_TAG: String = "reference-setup-cancel"

/** The test tag of the fit message. */
const val REFERENCE_SETUP_FIT_TAG: String = "reference-setup-fit"

/** Gives the test tag of the "Set" button of the card [index]. */
fun referenceSetTag(index: Int): String = "reference-setup-set-$index"

/** Gives the test tag of the horizontal field of the card [index]. */
fun referenceHorizontalTag(index: Int): String = "reference-setup-h-$index"

/** Gives the test tag of the vertical field of the card [index]. */
fun referenceVerticalTag(index: Int): String = "reference-setup-v-$index"

/** The events of the reference image setup screen. [ReferenceImageSetupPresenter] implements them. */
interface ReferenceImageSetupEvents {
    fun onViewSize(widthPx: Double, heightPx: Double)
    fun onPan(dxPx: Double, dyPx: Double)
    fun onZoom(factor: Double)
    fun onResetView()
    fun onNudgeStep(step: Int)
    fun onNudge(dx: Int, dy: Int)
    fun onGray()
    fun onInvert()
    fun onRotateLeft()
    fun onRotateRight()
    fun onFlipHorizontal()
    fun onFlipVertical()
    fun onHorizontalText(index: Int, text: String)
    fun onVerticalText(index: Int, text: String)
    fun onSyncHorizontal(index: Int)
    fun onSyncVertical(index: Int)
    fun onSubDatum(index: Int, subDatumId: String)
    fun onSetOrClear(index: Int)
    fun onClearAll()
    fun onZero()
    fun onPreview()
    fun onApply()
}

/**
 * The reference image setup screen ("Align Reference Image"). It is stateless: [state] and [bitmap]
 * come from
 * [ReferenceImageSetupPresenter].
 *
 * In landscape the image is at the left and the reference cards are at the right. In portrait the
 * image is at the top and the cards are below it.
 *
 * @param metrics The density of the screen for the ruler ticks of the reticle. The default uses
 *   160 dpi for each dp.
 */
@Composable
fun ReferenceImageSetupScreen(
    state: ReferenceImageSetupUiState,
    bitmap: ImageBitmap?,
    events: ReferenceImageSetupEvents,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    metrics: ScreenMetrics? = null,
) {
    val density = LocalDensity.current.density.toDouble()
    val screen = metrics ?: ScreenMetrics(xdpi = density * DP_PER_INCH, ydpi = density * DP_PER_INCH, density = density)
    ScreenScaffold(title = stringResource(Res.string.refimage_setup_title), onBack = onCancel, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accentColor()) else Spacer(Modifier.size(4.dp))
            if (LocalWindowLayout.current.landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        LiveValues(state, events, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                        ImageView(state, bitmap, events, screen, Modifier.weight(1f).fillMaxWidth())
                        Tools(state, events, Modifier.fillMaxWidth().padding(8.dp))
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.width(400.dp).fillMaxHeight()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Panel(state, events)
                        }
                        Buttons(state, events, onCancel, Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
            } else {
                LiveValues(state, events, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                ImageView(state, bitmap, events, screen, Modifier.weight(0.5f).fillMaxWidth())
                Tools(state, events, Modifier.fillMaxWidth().padding(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(0.5f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Panel(state, events)
                }
                Buttons(state, events, onCancel, Modifier.fillMaxWidth().padding(12.dp))
            }
        }
    }
}

@Composable
private fun LiveValues(state: ReferenceImageSetupUiState, events: ReferenceImageSetupEvents, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LiveValue(axisLabel(state.horizontalAxis), state.horizontalValue, unitText(state.horizontalMetric), Modifier.weight(1f))
        LiveValue(axisLabel(state.verticalAxis), state.verticalValue, unitText(state.verticalMetric), Modifier.weight(1f))
        val description = stringResource(Res.string.refimage_zero_description)
        DroButton(onClick = events::onZero, contentDescription = description, style = DroButtonStyle.Primary) {
            Text(stringResource(Res.string.refimage_zero), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun LiveValue(label: String, value: String, unit: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(unit, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImageView(
    state: ReferenceImageSetupUiState,
    bitmap: ImageBitmap?,
    events: ReferenceImageSetupEvents,
    metrics: ScreenMetrics,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val background = colors.surfaceContainerLowest
    val reticle = colors.secondary
    val marker = colors.tertiary
    val origin = colors.error
    val labelStyle = MaterialTheme.typography.labelLarge.copy(color = marker)
    val measurer = rememberTextMeasurer()
    val description = stringResource(Res.string.refimage_image_description)
    val filter = state.colorMatrix?.let { matrix ->
        ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(RasterImages.composeColorMatrix(matrix)))
    }
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { size -> events.onViewSize(size.width.toDouble(), size.height.toDouble()) }
                .pointerInput(events) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) events.onZoom(zoom.toDouble())
                        if (pan != Offset.Zero) events.onPan(pan.x.toDouble(), pan.y.toDouble())
                    }
                }
                .pointerInput(events) { detectTapGestures(onDoubleTap = { events.onResetView() }) }
                .semantics { contentDescription = description }
                .testTag(REFERENCE_SETUP_IMAGE_TAG),
        ) {
            drawRect(background)
            val toScreen = contentToScreen(state, size.width.toDouble(), size.height.toDouble())
            if (bitmap != null && state.hasImage) {
                withTransform({ transform(ShapePaths.composeMatrix(toScreen * state.contentMatrix)) }) {
                    drawImage(bitmap, colorFilter = filter)
                }
            }
            if (state.preview) {
                val center = toScreen.map(0.0, 0.0)
                drawCross(center, 12.dp.toPx(), 3f, origin)
            }
            state.cards.forEach { card ->
                val reference = card.reference ?: return@forEach
                val point = toScreen.map(state.contentMatrix.map(reference.pixel))
                if (!point.x.isFinite() || !point.y.isFinite()) return@forEach
                val at = Offset(point.x.toFloat(), point.y.toFloat())
                drawCircle(marker, radius = 8.dp.toPx(), center = at, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                drawText(measurer, "${card.index + 1}", topLeft = at + Offset(10.dp.toPx(), -24.dp.toPx()), style = labelStyle)
            }
            drawReticle(metrics, reticle)
        }
        state.reticlePixel?.let { pixel ->
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                color = colors.surfaceContainerHigh.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    stringResource(Res.string.refimage_pixel, pixelText(pixel.x), pixelText(pixel.y)),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Gives the matrix from content points to screen pixels: `C + pan + (q − q_c) × scale`. */
private fun contentToScreen(state: ReferenceImageSetupUiState, width: Double, height: Double): Matrix3 {
    val center = state.contentMatrix.map(state.imageWidth / 2.0, state.imageHeight / 2.0)
    return Matrix3.translation(width / 2.0 + state.panX, height / 2.0 + state.panY) *
        Matrix3.scale(state.scale, state.scale) *
        Matrix3.translation(-center.x, -center.y)
}

private fun DrawScope.drawCross(center: Vec2, half: Float, width: Float, color: Color) {
    if (!center.x.isFinite() || !center.y.isFinite()) return
    val c = Offset(center.x.toFloat(), center.y.toFloat())
    drawLine(color, c - Offset(half, 0f), c + Offset(half, 0f), strokeWidth = width)
    drawLine(color, c - Offset(0f, half), c + Offset(0f, half), strokeWidth = width)
}

/**
 * The fixed reticle at the view center: a horizontal and a
 * vertical line across the view, major ticks each 1 inch on the screen with a length of
 * `0.3 × xdpi`, and minor ticks each 0.1 inch with a length of `0.12 × xdpi` when the interval is
 * 3 dp or more.
 */
private fun DrawScope.drawReticle(metrics: ScreenMetrics, color: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, cy), Offset(size.width, cy), strokeWidth = stroke)
    drawLine(color, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = stroke)
    val horizontal = CursorGeometry.rulerTicks(metrics, 1.0, metric = false, halfLengthPx = cx.toDouble(), dpiAlong = metrics.xdpi)
        .filter { tick -> tick.kind != TickKind.Fine }
    val vertical = CursorGeometry.rulerTicks(metrics, 1.0, metric = false, halfLengthPx = cy.toDouble(), dpiAlong = metrics.ydpi)
        .filter { tick -> tick.kind != TickKind.Fine }
    horizontal.forEach { tick ->
        val half = (tick.lengthPx / 2.0).toFloat()
        listOf(-1f, 1f).forEach { side ->
            val x = cx + side * tick.offsetPx.toFloat()
            drawLine(color, Offset(x, cy - half), Offset(x, cy + half), strokeWidth = stroke)
        }
    }
    vertical.forEach { tick ->
        val half = (tick.lengthPx / 2.0).toFloat()
        listOf(-1f, 1f).forEach { side ->
            val y = cy + side * tick.offsetPx.toFloat()
            drawLine(color, Offset(cx - half, y), Offset(cx + half, y), strokeWidth = stroke)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tools(state: ReferenceImageSetupUiState, events: ReferenceImageSetupEvents, modifier: Modifier) {
    val targets = LocalTouchTargets.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val enabled = state.hasImage && !state.busy
        // The icons use the accent role, not `primary`.
        val chipColors = FilterChipDefaults.filterChipColors(iconColor = accentColor(), selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer)
        FilterChip(
            selected = state.gray,
            onClick = events::onGray,
            enabled = enabled,
            label = { Text(stringResource(Res.string.refimage_gray)) },
            colors = chipColors,
            leadingIcon = { Icon(painterResource(Res.drawable.ic_refimage_gray), contentDescription = null) },
            modifier = Modifier.heightIn(min = targets.button),
        )
        FilterChip(
            selected = state.invert,
            onClick = events::onInvert,
            enabled = enabled,
            label = { Text(stringResource(Res.string.refimage_invert)) },
            colors = chipColors,
            leadingIcon = { Icon(painterResource(Res.drawable.ic_refimage_invert), contentDescription = null) },
            modifier = Modifier.heightIn(min = targets.button),
        )
        ToolButton(Res.drawable.ic_refimage_rotate_left, stringResource(Res.string.refimage_rotate_left), enabled, events::onRotateLeft)
        ToolButton(Res.drawable.ic_refimage_rotate_right, stringResource(Res.string.refimage_rotate_right), enabled, events::onRotateRight)
        ToolButton(Res.drawable.ic_refimage_flip, stringResource(Res.string.refimage_flip_horizontal), enabled, events::onFlipHorizontal)
        ToolButton(Res.drawable.ic_refimage_flip, stringResource(Res.string.refimage_flip_vertical), enabled, events::onFlipVertical, iconRotation = 90f)
        Nudge(state, events)
    }
}

@Composable
private fun ToolButton(icon: DrawableResource, label: String, enabled: Boolean, onClick: () -> Unit, iconRotation: Float = 0f) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = AccentButtons.outlined(),
        modifier = Modifier.heightIn(min = LocalTouchTargets.current.button),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp).rotate(iconRotation))
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

@Composable
private fun Nudge(state: ReferenceImageSetupUiState, events: ReferenceImageSetupEvents) {
    val targets = LocalTouchTargets.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow {
            ReferenceImageSetupUiState.NUDGE_STEPS.forEachIndexed { index, step ->
                SegmentedButton(
                    selected = state.nudgeStep == step,
                    onClick = { events.onNudgeStep(step) },
                    shape = SegmentedButtonDefaults.itemShape(index, ReferenceImageSetupUiState.NUDGE_STEPS.size),
                    modifier = Modifier.heightIn(min = targets.button),
                ) { Text(stringResource(Res.string.refimage_nudge_step, step)) }
            }
        }
        NudgeButton(stringResource(Res.string.refimage_nudge_left), 270f) { events.onNudge(-1, 0) }
        NudgeButton(stringResource(Res.string.refimage_nudge_up), 0f) { events.onNudge(0, -1) }
        NudgeButton(stringResource(Res.string.refimage_nudge_down), 180f) { events.onNudge(0, 1) }
        NudgeButton(stringResource(Res.string.refimage_nudge_right), 90f) { events.onNudge(1, 0) }
    }
}

@Composable
private fun NudgeButton(description: String, rotation: Float, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(LocalTouchTargets.current.button)) {
        Icon(painterResource(Res.drawable.ic_refimage_arrow_up), contentDescription = description, modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun Panel(state: ReferenceImageSetupUiState, events: ReferenceImageSetupEvents) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.message?.let { message -> Notice(message.resolve(), Res.drawable.ic_info, colors.secondaryContainer, colors.onSecondaryContainer) }
        state.fitMessage?.let { message ->
            Notice(message.resolve(), Res.drawable.ic_warning, colors.errorContainer, colors.onErrorContainer, Modifier.testTag(REFERENCE_SETUP_FIT_TAG))
        }
        state.banner?.let { banner -> Notice(banner.resolve(), Res.drawable.ic_warning, colors.tertiaryContainer, colors.onTertiaryContainer) }
        Text(stringResource(Res.string.refimage_reticle_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        state.cards.filter { card -> card.visible }.forEach { card -> ReferenceCard(state, card, events) }
    }
}

@Composable
private fun Notice(text: String, icon: DrawableResource, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = container, contentColor = content, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReferenceCard(state: ReferenceImageSetupUiState, card: ReferenceCardUiState, events: ReferenceImageSetupEvents) {
    val colors = MaterialTheme.colorScheme
    val accent = accentColor()
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        border = if (card.active) BorderStroke(2.dp, accent) else BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.refimage_reference, card.index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (card.isSet) Icon(painterResource(Res.drawable.ic_check), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(if (card.isSet) Res.string.refimage_state_set else Res.string.refimage_state_open),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (card.isSet) accent else colors.onSurfaceVariant,
                )
            }
            if (state.subDatums.isNotEmpty()) SubDatumMenu(state, card, events)
            val h = axisLabel(state.horizontalAxis)
            val v = axisLabel(state.verticalAxis)
            ValueField(
                label = h,
                text = card.horizontalText,
                onTextChange = { text -> events.onHorizontalText(card.index, text) },
                unit = unitText(state.horizontalMetric),
                error = card.horizontalError,
                syncLabel = stringResource(Res.string.refimage_sync, h),
                onSync = { events.onSyncHorizontal(card.index) },
                enabled = !card.isSet,
                modifier = Modifier.fillMaxWidth().testTag(referenceHorizontalTag(card.index)),
            )
            ValueField(
                label = v,
                text = card.verticalText,
                onTextChange = { text -> events.onVerticalText(card.index, text) },
                unit = unitText(state.verticalMetric),
                error = card.verticalError,
                syncLabel = stringResource(Res.string.refimage_sync, v),
                onSync = { events.onSyncVertical(card.index) },
                enabled = !card.isSet,
                modifier = Modifier.fillMaxWidth().testTag(referenceVerticalTag(card.index)),
            )
            card.error?.let { error -> Text(error.resolve(), color = colors.error, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                card.reference?.let { reference ->
                    Text(
                        stringResource(Res.string.refimage_pixel, pixelText(reference.pixel.x), pixelText(reference.pixel.y)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } ?: Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { events.onSetOrClear(card.index) },
                    enabled = state.hasImage,
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(referenceSetTag(card.index)),
                ) { Text(stringResource(if (card.isSet) Res.string.refimage_clear else Res.string.refimage_set)) }
            }
        }
    }
}

@Composable
private fun SubDatumMenu(state: ReferenceImageSetupUiState, card: ReferenceCardUiState, events: ReferenceImageSetupEvents) {
    var open by remember { mutableStateOf(false) }
    val selected = state.subDatums.firstOrNull { option -> option.id == card.subDatumId }?.name
        ?: stringResource(Res.string.refimage_sub_datum_none)
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = !card.isSet,
            colors = AccentButtons.outlined(),
            modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button),
        ) { Text("${stringResource(Res.string.refimage_sub_datum)}: $selected") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.subDatums.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        open = false
                        events.onSubDatum(card.index, option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun Buttons(state: ReferenceImageSetupUiState, events: ReferenceImageSetupEvents, onCancel: () -> Unit, modifier: Modifier) {
    val targets = LocalTouchTargets.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(targets.gap), verticalAlignment = Alignment.CenterVertically) {
        val cancelHint = stringResource(Res.string.refimage_clear_all_hint)
        DroButton(
            onClick = onCancel,
            onLongClick = events::onClearAll,
            contentDescription = "${stringResource(Res.string.frame_cancel)}. $cancelHint",
            style = DroButtonStyle.Neutral,
            modifier = Modifier.testTag(REFERENCE_SETUP_CANCEL_TAG),
        ) { Text(stringResource(Res.string.frame_cancel), style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = events::onPreview,
            enabled = (state.canApply || state.preview) && !state.busy,
            colors = AccentButtons.outlined(),
            modifier = Modifier.heightIn(min = targets.button).testTag(REFERENCE_SETUP_PREVIEW_TAG),
        ) { Text(stringResource(if (state.preview) Res.string.refimage_return else Res.string.refimage_preview)) }
        FilledTonalButton(
            onClick = events::onApply,
            enabled = state.canApply && !state.busy,
            modifier = Modifier.heightIn(min = targets.button).testTag(REFERENCE_SETUP_APPLY_TAG),
        ) { Text(stringResource(Res.string.refimage_apply), style = MaterialTheme.typography.titleMedium) }
    }
}

private fun axisLabel(axis: AxisId): String = when (axis) {
    AxisId.X -> "X"
    AxisId.Y -> "Y"
    AxisId.Z -> "Z"
    else -> axis.name
}

private fun pixelText(value: Double): String =
    if (!value.isFinite()) "—" else org.superdro.core.readout.NumberPattern("0.0").format(value)

/** The pixels for each inch at a density of 1: 160 dp for each inch. */
private const val DP_PER_INCH: Double = 160.0
