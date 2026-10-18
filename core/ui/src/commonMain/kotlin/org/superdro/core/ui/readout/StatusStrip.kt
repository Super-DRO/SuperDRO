package org.superdro.core.ui.readout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.readout.operations.GlobalModeState
import org.superdro.core.readout.operations.GlobalUnitsState
import org.superdro.core.resources.Res
import org.superdro.core.resources.frame_close
import org.superdro.core.resources.ic_close
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.readout_setup_calibrate
import org.superdro.core.resources.readout_setup_settings
import org.superdro.core.resources.readout_setup_text
import org.superdro.core.resources.readout_setup_title
import org.superdro.core.resources.readout_status_datum
import org.superdro.core.resources.readout_status_measurement
import org.superdro.core.resources.readout_status_no_tool
import org.superdro.core.resources.readout_status_rpm
import org.superdro.core.resources.readout_status_tool
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the status strip. */
const val STATUS_STRIP_TAG: String = "readout-status-strip"

/** The test tag of the hint and message tooltip. */
const val MESSAGE_LINE_TAG: String = "readout-message-line"

/** The test tag of the dismiss button on the hint and message tooltip. */
const val MESSAGE_DISMISS_TAG: String = "readout-message-dismiss"

/** The RPM text of a readout with no tachometer value. */
val NoRpmText: StateFlow<String?> = MutableStateFlow(null)

/**
 * The status strip: the units, ABS or INC, the active tool, the active sub-datum, the RPM,
 * and the "Measurement" mode chip. Each status has an icon and a text.
 *
 * @param rpmText The RPM text of the tachometer, or null when the tachometer is off. Only the RPM
 *   chip reads it, so a new RPM value does not recompose the other chips.
 * @param trailing The items of the other areas after the frame items, for example the status
 *   chip of the data output (`EntryRegistry.statusStripItem`).
 */
@Composable
fun StatusStrip(
    state: StatusStripUiState,
    modifier: Modifier = Modifier,
    rpmText: StateFlow<String?> = NoRpmText,
    trailing: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.testTag(STATUS_STRIP_TAG).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mixedUnits = state.units == GlobalUnitsState.Mixed
        StatusChip(stringResource(ReadoutTexts.unitsText(state.units)), ReadoutTexts.mixedIcon(mixedUnits))
        StatusChip(stringResource(ReadoutTexts.modeText(state.mode)), ReadoutTexts.mixedIcon(state.mode == GlobalModeState.Mixed))
        StatusChip(state.toolName?.let { name -> stringResource(Res.string.readout_status_tool, name) } ?: stringResource(Res.string.readout_status_no_tool))
        state.subDatumName?.let { name -> StatusChip(stringResource(Res.string.readout_status_datum, name)) }
        RpmChip(rpmText)
        if (state.measurementMode) {
            StatusChip(stringResource(Res.string.readout_status_measurement), Res.drawable.ic_info, colors.secondaryContainer, colors.onSecondaryContainer)
        }
        trailing()
    }
}

/** The RPM chip: the only reader of the RPM text. No chip shows when the text is null. */
@Composable
private fun RpmChip(rpmText: StateFlow<String?>) {
    val rpm by rpmText.collectAsState()
    rpm?.let { text -> StatusChip(stringResource(Res.string.readout_status_rpm, text)) }
}

@Composable
private fun StatusChip(
    text: String,
    icon: DrawableResource? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * The setup reminder banner: "Calibration required" with
 * the actions "Settings" and "Calibrate". The app setting "Show the setup reminder" turns it off.
 */
@Composable
fun SetupReminderBanner(onSettings: () -> Unit, onCalibrate: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().testTag("readout-setup-reminder"), color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.readout_setup_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.readout_setup_text), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onSettings, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                Text(stringResource(Res.string.readout_setup_settings))
            }
            Spacer(Modifier.width(LocalTouchTargets.current.gap))
            FilledTonalButton(onClick = onCalibrate, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                Text(stringResource(Res.string.readout_setup_calibrate))
            }
        }
    }
}

/**
 * Tracks the root bounds of the currently active hint/message anchor button only when a
 * hint or message is visible, so no layout callbacks run during normal readout updates or split resizing.
 */
@Stable
internal class HintAnchorTracker(
    val activeTag: String?,
    val onBoundsChanged: (Rect) -> Unit,
)

internal val LocalHintAnchorTracker = compositionLocalOf<HintAnchorTracker?> { null }

@Composable
internal fun Modifier.hintAnchor(tag: String): Modifier {
    val tracker = LocalHintAnchorTracker.current
    return if (tracker != null && tracker.activeTag == tag) {
        this.onGloballyPositioned { coords ->
            if (coords.isAttached) {
                tracker.onBoundsChanged(coords.boundsInRoot())
            }
        }
    } else {
        this
    }
}

/**
 * A floating, dismissable tooltip that points to the pressed button ([anchorBoundsInRoot])
 * and never shifts or consumes vertical layout space in the readout column.
 */
@Composable
internal fun MessageTooltipOverlay(
    text: UiText,
    anchorBoundsInRoot: Rect?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overlayOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val marginPx = with(density) { 8.dp.roundToPx() }
    val gapPx = with(density) { 4.dp.roundToPx() }
    val cornerMarginPx = with(density) { 14.dp.roundToPx() }
    val maxTooltipWidthPx = with(density) { 380.dp.roundToPx() }
    val hasAnchor = anchorBoundsInRoot != null && anchorBoundsInRoot.width > 0f && anchorBoundsInRoot.height > 0f

    Layout(
        content = {
            Surface(
                modifier = Modifier
                    .testTag(MESSAGE_LINE_TAG)
                    .clickable(onClick = onDismiss)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                shape = MaterialTheme.shapes.medium,
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer,
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = text.resolve(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp).testTag(MESSAGE_DISMISS_TAG),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close),
                            contentDescription = stringResource(Res.string.frame_close),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (hasAnchor) {
                val fillColor = colors.secondaryContainer
                val borderColor = colors.outlineVariant
                Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
                    val path = Path().apply {
                        moveTo(size.width / 2f, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, color = fillColor)
                    drawLine(borderColor, Offset(0f, size.height), Offset(size.width / 2f, 0f), strokeWidth = 1.dp.toPx())
                    drawLine(borderColor, Offset(size.width / 2f, 0f), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                }
            }
        },
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    val origin = coords.positionInRoot()
                    if (origin != overlayOriginInRoot) {
                        overlayOriginInRoot = origin
                    }
                }
            },
    ) { measurables, constraints ->
        val maxCardWidth = minOf(maxTooltipWidthPx, (constraints.maxWidth - marginPx * 2).coerceAtLeast(0))
        val cardPlaceable = measurables[0].measure(
            Constraints(minWidth = 0, maxWidth = maxCardWidth, minHeight = 0, maxHeight = constraints.maxHeight),
        )
        val arrowPlaceable = if (measurables.size > 1) measurables[1].measure(Constraints()) else null
        val anchor = if (hasAnchor && arrowPlaceable != null) {
            anchorBoundsInRoot.translate(-overlayOriginInRoot.x, -overlayOriginInRoot.y)
        } else {
            null
        }

        if (anchor != null && arrowPlaceable != null) {
            val spaceBelow = constraints.maxHeight - anchor.bottom.roundToInt() - gapPx - arrowPlaceable.height - marginPx
            val spaceAbove = anchor.top.roundToInt() - gapPx - arrowPlaceable.height - marginPx
            val placeBelow = if (anchor.center.y <= constraints.maxHeight * 0.55f) {
                spaceBelow >= cardPlaceable.height || spaceBelow >= spaceAbove
            } else {
                spaceAbove < cardPlaceable.height && spaceBelow > spaceAbove
            }

            val minCardX = marginPx
            val maxCardX = (constraints.maxWidth - cardPlaceable.width - marginPx).coerceAtLeast(minCardX)
            val cardX = (anchor.center.x.roundToInt() - cardPlaceable.width / 2).coerceIn(minCardX, maxCardX)

            val minArrowX = cardX + cornerMarginPx
            val maxArrowX = (cardX + cardPlaceable.width - arrowPlaceable.width - cornerMarginPx).coerceAtLeast(minArrowX)
            val arrowX = (anchor.center.x.roundToInt() - arrowPlaceable.width / 2).coerceIn(minArrowX, maxArrowX)

            val (cardY, arrowY) = if (placeBelow) {
                val maxArrowY = (constraints.maxHeight - cardPlaceable.height - arrowPlaceable.height - marginPx).coerceAtLeast(marginPx)
                val ay = (anchor.bottom.roundToInt() + gapPx).coerceIn(marginPx, maxArrowY)
                (ay + arrowPlaceable.height) to ay
            } else {
                val minArrowY = (marginPx + cardPlaceable.height).coerceAtMost(
                    (constraints.maxHeight - arrowPlaceable.height - marginPx).coerceAtLeast(marginPx),
                )
                val maxArrowY = (constraints.maxHeight - arrowPlaceable.height - marginPx).coerceAtLeast(minArrowY)
                val ay = (anchor.top.roundToInt() - gapPx - arrowPlaceable.height).coerceIn(minArrowY, maxArrowY)
                (ay - cardPlaceable.height) to ay
            }

            layout(constraints.maxWidth, constraints.maxHeight) {
                cardPlaceable.placeRelative(cardX, cardY)
                if (placeBelow) {
                    arrowPlaceable.placeRelative(arrowX, arrowY)
                } else {
                    arrowPlaceable.placeWithLayer(arrowX, arrowY) {
                        rotationZ = 180f
                    }
                }
            }
        } else {
            val cardX = ((constraints.maxWidth - cardPlaceable.width) / 2).coerceAtLeast(marginPx)
            val cardY = marginPx
            layout(constraints.maxWidth, constraints.maxHeight) {
                cardPlaceable.placeRelative(cardX, cardY)
            }
        }
    }
}

/**
 * The line of a one-time hint or a message. It shows a short text and never
 * blocks an action.
 */
@Composable
fun MessageLine(text: UiText, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().testTag(MESSAGE_LINE_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text.resolve(), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
