package org.superdro.core.ui.readout

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.AxisId
import org.superdro.core.model.GraphicalViewPlacement
import org.superdro.core.model.SidePanelMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.strip_functions
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WidthClass
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.LocalReadoutTypography
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.readoutTypography
import kotlin.math.roundToInt

/** The test tag of the readout screen. */
const val READOUT_SCREEN_TAG: String = "readout-screen"

/** The test tag of the supporting pane (expanded layout). */
const val SUPPORTING_PANE_TAG: String = "readout-supporting-pane"

/** The test tag of the graphical view pane. */
const val GRAPHICAL_PANE_TAG: String = "readout-graphical-pane"

/** The test tag of the draggable split handle between the readout and the viewer/supporting pane. */
const val SPLIT_HANDLE_TAG: String = "readout-split-handle"

/** The width of a readout pane below which the readout uses the collapsed buttons. */
private val COLLAPSED_PANE_WIDTH = 600.dp

/**
 * The default part of the window width that the readout column uses in the expanded layout. The readout
 * column holds the readout and the function grid. The supporting pane gets the remaining width.
 */
const val READOUT_PANE_FRACTION: Float = 0.45f

/** The minimum fraction of the window width that the readout column uses in the expanded layout. */
const val READOUT_PANE_MIN_FRACTION: Float = 0.25f

/** The maximum fraction of the window width that the readout column uses in the expanded layout. */
const val READOUT_PANE_MAX_FRACTION: Float = 0.75f

/** The minimum width of the readout column in the expanded layout. */
val MIN_READOUT_PANE_WIDTH: Dp = 280.dp

/** The minimum width of the supporting pane in the expanded layout. */
val MIN_SUPPORTING_PANE_WIDTH: Dp = 280.dp

/** The largest part of the body height that the function grid uses below the readout (expanded layout). */
const val FUNCTION_GRID_MAX_FRACTION: Float = 0.5f

/** The default part of the content height that the graphical view uses below or above the readout in portrait. */
const val GRAPHICAL_PANE_WEIGHT: Float = 0.6f

/** The minimum height weight of the graphical view in portrait. */
const val GRAPHICAL_PANE_MIN_WEIGHT: Float = 0.25f

/** The maximum height weight of the graphical view in portrait. */
const val GRAPHICAL_PANE_MAX_WEIGHT: Float = 0.75f

/** The minimum height of the readout section in portrait. */
val MIN_READOUT_PANE_HEIGHT: Dp = 180.dp

/** The minimum height of the graphical view section in portrait. */
val MIN_GRAPHICAL_PANE_HEIGHT: Dp = 180.dp

/** The width of the vertical rail: one button of the strip and the gaps. */
private val RailWidth: Dp = 136.dp

/**
 * The readout screen of one tab. It is stateless: it gets
 * the [state] of the presenter, the value flow of each axis ([axisValue]), the RPM text
 * ([rpmText]), and the [events].
 *
 * The layout follows the window class:
 * - Expanded (840 dp and more): the readout column (about 45 % of the width) with the readout
 *   and the grid of the function strip below it, and the supporting pane with the side panel or
 *   the graphical view at the full height. A narrow readout column uses the collapsed buttons.
 *   The window class comes from the width only, thus a portrait window of 840 dp or more also
 *   uses this layout.
 * - Medium (600 dp to 839 dp): the readout and the vertical rail of the function strip at its
 *   right, the graphical view (about 60 % of the height) below or above them, and the side
 *   panel tabs with "+". The side panel opens as a bottom sheet.
 * - Compact (less than 600 dp): as medium, with the readout at the full width and the function
 *   strip in a bottom sheet.
 * - A medium or compact window in landscape (for example a split screen) is not tall enough for
 *   the two rows of buttons: the readout gets the full height and the function strip is the rail.
 *
 * @param sidePanelContent The content of the side panel for a mode (the functions area fills it).
 * @param graphicalViewPane The graphical view pane, or null when no area registered it.
 * @param statusItems The items of the other areas at the end of the status strip.
 */
@Composable
fun ReadoutScreen(
    state: ReadoutUiState,
    axisValue: (AxisId) -> StateFlow<AxisValueUiState>,
    events: ReadoutEvents,
    modifier: Modifier = Modifier,
    layout: WindowLayout = LocalWindowLayout.current,
    sidePanelContent: @Composable (SidePanelMode) -> Unit = {},
    graphicalViewPane: (@Composable () -> Unit)? = null,
    statusItems: @Composable () -> Unit = {},
    rpmText: StateFlow<String?> = NoRpmText,
) {
    // The font of the digits is a setting of the machine profile.
    val typography = readoutTypography(state.readoutFont)
    CompositionLocalProvider(LocalReadoutTypography provides typography) {
        Surface(modifier.fillMaxSize().testTag(READOUT_SCREEN_TAG), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                StatusStrip(state.status, rpmText = rpmText, trailing = statusItems)
                state.setupReminder?.let { reminder ->
                    SetupReminderBanner(onSettings = events::onSetupSettings, onCalibrate = { events.onSetupCalibrate(reminder.axis) })
                }
                (state.message?.text ?: state.hint)?.let { text -> MessageLine(text) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when {
                    layout.expanded ->
                        ExpandedBody(state, axisValue, events, sidePanelContent, graphicalViewPane, Modifier.weight(1f))
                    layout.landscape -> ShortLandscapeBody(state, axisValue, events, sidePanelContent, Modifier.weight(1f))
                    else -> PortraitBody(
                        state, axisValue, events, sidePanelContent, graphicalViewPane,
                        compact = layout.widthClass == WidthClass.Compact, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedBody(
    state: ReadoutUiState,
    axisValue: (AxisId) -> StateFlow<AxisValueUiState>,
    events: ReadoutEvents,
    sidePanelContent: @Composable (SidePanelMode) -> Unit,
    graphicalViewPane: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val panelOpen = state.sidePanel.mode != SidePanelMode.Off
    val targets = LocalTouchTargets.current
    var fraction by remember(state.readoutPaneFraction) { mutableFloatStateOf(state.readoutPaneFraction) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val totalWidth = maxWidth
        val minByDp = if (totalWidth > 0.dp) (MIN_READOUT_PANE_WIDTH / totalWidth) else READOUT_PANE_MIN_FRACTION
        val maxByDp = if (totalWidth > 0.dp) 1f - (MIN_SUPPORTING_PANE_WIDTH / totalWidth) else READOUT_PANE_MAX_FRACTION
        val minFraction = maxOf(READOUT_PANE_MIN_FRACTION, minByDp).coerceAtMost(0.5f)
        val maxFraction = minOf(READOUT_PANE_MAX_FRACTION, maxByDp).coerceAtLeast(minFraction)
        val clampedFraction = fraction.coerceIn(minFraction, maxFraction)
        val readoutWidth = totalWidth * clampedFraction
        val gridMaxHeight = maxHeight * FUNCTION_GRID_MAX_FRACTION
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(readoutWidth).fillMaxHeight()) {
                ReadoutRows(
                    rows = state.rows,
                    axisValue = axisValue,
                    // The open side panel gives the collapsed readout. A readout pane of less
                    // than 600 dp also uses the collapsed buttons, so that the digits keep a useful size.
                    collapsed = panelOpen || readoutWidth < COLLAPSED_PANE_WIDTH,
                    format = state.readoutFormat,
                    events = events,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FunctionStrip(
                    global = state.globalButtons,
                    functions = state.functions,
                    events = events,
                    form = StripForm.Grid,
                    modifier = Modifier.fillMaxWidth().heightIn(max = gridMaxHeight),
                )
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).fillMaxHeight().testTag(SUPPORTING_PANE_TAG)) {
                // The "+" button is in the row of the tabs, not above the pane: the graphical view
                // has its zoom presets at the bottom end (no control covers a different one).
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(targets.gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SidePanelTabs(state.sidePanel, events, Modifier.weight(1f))
                    if (state.sidePanel.addVisible) AddPointButton(events)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        panelOpen -> sidePanelContent(state.sidePanel.mode)
                        graphicalViewPane != null -> Box(Modifier.fillMaxSize().testTag(GRAPHICAL_PANE_TAG)) { graphicalViewPane() }
                        else -> Spacer(Modifier.fillMaxSize())
                    }
                }
            }
        }
        val totalWidthPx = constraints.maxWidth.toFloat()
        SplitHandle(
            vertical = true,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset {
                    val splitX = (totalWidthPx * clampedFraction).roundToInt()
                    val halfTouch = (SplitHandleTouchThickness / 2).roundToPx()
                    IntOffset(x = splitX - halfTouch, y = 0)
                }
                .pointerInput(minFraction, maxFraction, totalWidthPx) {
                    if (totalWidthPx <= 0f) return@pointerInput
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        val updated = (fraction + dragAmount / totalWidthPx).coerceIn(minFraction, maxFraction)
                        if (updated != fraction) {
                            fraction = updated
                            events.onReadoutPaneFractionChange(updated)
                        }
                    }
                },
        )
    }
}

@Composable
private fun PortraitBody(
    state: ReadoutUiState,
    axisValue: (AxisId) -> StateFlow<AxisValueUiState>,
    events: ReadoutEvents,
    sidePanelContent: @Composable (SidePanelMode) -> Unit,
    graphicalViewPane: (@Composable () -> Unit)?,
    compact: Boolean,
    modifier: Modifier,
) {
    val targets = LocalTouchTargets.current
    val railWidth = RailWidth * (targets.button / 64.dp)
    val pane = graphicalViewPane.takeIf { state.graphicalViewPlacement != GraphicalViewPlacement.FullScreen }
    var weight by remember(state.graphicalPaneWeight) { mutableFloatStateOf(state.graphicalPaneWeight) }
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val totalHeight = maxHeight
            val minByDp = if (totalHeight > 0.dp) (MIN_GRAPHICAL_PANE_HEIGHT / totalHeight) else GRAPHICAL_PANE_MIN_WEIGHT
            val maxByDp = if (totalHeight > 0.dp) 1f - (MIN_READOUT_PANE_HEIGHT / totalHeight) else GRAPHICAL_PANE_MAX_WEIGHT
            val minWeight = maxOf(GRAPHICAL_PANE_MIN_WEIGHT, minByDp).coerceAtMost(0.5f)
            val maxWeight = minOf(GRAPHICAL_PANE_MAX_WEIGHT, maxByDp).coerceAtLeast(minWeight)
            val clampedWeight = weight.coerceIn(minWeight, maxWeight)
            val above = state.graphicalViewPlacement == GraphicalViewPlacement.AboveReadout
            Column(Modifier.fillMaxSize()) {
                if (pane != null && above) {
                    GraphicalPane(pane, Modifier.weight(clampedWeight))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                // The rail at the right of the readout frees the height below it for the graphical view.
                Row(Modifier.weight(1f - if (pane != null) clampedWeight else 0f).fillMaxWidth()) {
                    ReadoutRows(
                        rows = state.rows,
                        axisValue = axisValue,
                        collapsed = false,
                        format = state.readoutFormat,
                        events = events,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    if (!compact) {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FunctionStrip(state.globalButtons, state.functions, events, StripForm.Rail, Modifier.width(railWidth).fillMaxHeight())
                    }
                }
                if (pane != null && state.graphicalViewPlacement == GraphicalViewPlacement.BelowReadout) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GraphicalPane(pane, Modifier.weight(clampedWeight))
                }
            }
            if (pane != null) {
                val totalHeightPx = constraints.maxHeight.toFloat()
                val splitFractionFromTop = if (above) clampedWeight else (1f - clampedWeight)
                SplitHandle(
                    vertical = false,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset {
                            val splitY = (totalHeightPx * splitFractionFromTop).roundToInt()
                            val halfTouch = (SplitHandleTouchThickness / 2).roundToPx()
                            IntOffset(x = 0, y = splitY - halfTouch)
                        }
                        .pointerInput(minWeight, maxWeight, totalHeightPx, above) {
                            if (totalHeightPx <= 0f) return@pointerInput
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                val delta = if (above) dragAmount / totalHeightPx else -dragAmount / totalHeightPx
                                val updated = (weight + delta).coerceIn(minWeight, maxWeight)
                                if (updated != weight) {
                                    weight = updated
                                    events.onGraphicalPaneWeightChange(updated)
                                }
                            }
                        },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(targets.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SidePanelTabs(state.sidePanel, events, Modifier.weight(1f))
            if (compact) {
                DroButton(onClick = { events.onFunctionSheet(true) }, style = DroButtonStyle.Neutral) {
                    Text(stringResource(Res.string.strip_functions), style = MaterialTheme.typography.titleSmall)
                }
            }
            if (state.sidePanel.addVisible) AddPointButton(events)
        }
    }
    if (state.sidePanel.mode != SidePanelMode.Off) {
        ModalBottomSheet(onDismissRequest = events::onSidePanelClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Box(Modifier.fillMaxWidth().height(SidePanelSheetHeight)) { sidePanelContent(state.sidePanel.mode) }
        }
    }
    if (compact && state.functionSheetOpen) {
        ModalBottomSheet(onDismissRequest = { events.onFunctionSheet(false) }) {
            FunctionStrip(state.globalButtons, state.functions, events, StripForm.TwoRows, Modifier.fillMaxWidth().padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun SplitHandle(vertical: Boolean, modifier: Modifier = Modifier) {
    val touchWidth = if (vertical) SplitHandleTouchThickness else SplitHandleTouchLength
    val touchHeight = if (vertical) SplitHandleTouchLength else SplitHandleTouchThickness
    val pillWidth = if (vertical) SplitHandlePillThickness else SplitHandlePillLength
    val pillHeight = if (vertical) SplitHandlePillLength else SplitHandlePillThickness
    Box(
        modifier = modifier
            .size(width = touchWidth, height = touchHeight)
            .testTag(SPLIT_HANDLE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(width = pillWidth, height = pillHeight),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 4.dp,
            shadowElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                val gripWidth = if (vertical) 2.dp else 24.dp
                val gripHeight = if (vertical) 24.dp else 2.dp
                Box(
                    Modifier
                        .size(width = gripWidth, height = gripHeight)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun ShortLandscapeBody(
    state: ReadoutUiState,
    axisValue: (AxisId) -> StateFlow<AxisValueUiState>,
    events: ReadoutEvents,
    sidePanelContent: @Composable (SidePanelMode) -> Unit,
    modifier: Modifier,
) {
    val targets = LocalTouchTargets.current
    val railWidth = RailWidth * (targets.button / 64.dp)
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ReadoutRows(
                rows = state.rows,
                axisValue = axisValue,
                collapsed = false,
                format = state.readoutFormat,
                events = events,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(targets.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SidePanelTabs(state.sidePanel, events, Modifier.weight(1f))
                if (state.sidePanel.addVisible) AddPointButton(events)
            }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FunctionStrip(state.globalButtons, state.functions, events, StripForm.Rail, Modifier.width(railWidth).fillMaxHeight())
    }
    if (state.sidePanel.mode != SidePanelMode.Off) {
        ModalBottomSheet(onDismissRequest = events::onSidePanelClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Box(Modifier.fillMaxWidth().height(SidePanelSheetHeight)) { sidePanelContent(state.sidePanel.mode) }
        }
    }
}

@Composable
private fun GraphicalPane(pane: @Composable () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxWidth().testTag(GRAPHICAL_PANE_TAG)) { pane() }
}

/** The height of the side panel in its bottom sheet (portrait). */
private val SidePanelSheetHeight: Dp = 420.dp

private val SplitHandleTouchThickness: Dp = 48.dp
private val SplitHandleTouchLength: Dp = 88.dp
private val SplitHandlePillThickness: Dp = 10.dp
private val SplitHandlePillLength: Dp = 56.dp
