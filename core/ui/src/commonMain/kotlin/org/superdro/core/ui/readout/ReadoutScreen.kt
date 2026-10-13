package org.superdro.core.ui.readout

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
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

/** The test tag of the readout screen. */
const val READOUT_SCREEN_TAG: String = "readout-screen"

/** The test tag of the supporting pane (expanded layout). */
const val SUPPORTING_PANE_TAG: String = "readout-supporting-pane"

/** The test tag of the graphical view pane. */
const val GRAPHICAL_PANE_TAG: String = "readout-graphical-pane"

/** The width of a readout pane below which the readout uses the collapsed buttons. */
private val COLLAPSED_PANE_WIDTH = 600.dp

/** The part of the window width that the readout pane uses in the expanded layout. */
const val READOUT_PANE_FRACTION: Float = 0.6f

/** The part of the content height that the graphical view uses below or above the readout in portrait. */
const val GRAPHICAL_PANE_WEIGHT: Float = 0.4f

/** The width of the vertical rail: one button of the strip and the gaps. */
private val RailWidth: Dp = 136.dp

/**
 * The readout screen of one tab. It is stateless: it gets
 * the [state] of the presenter, the value flow of each axis ([axisValue]), and the [events].
 *
 * The layout follows the window class:
 * - Expanded (840 dp and more) in landscape: the readout pane (about 60 % of the width), the
 *   supporting pane with the side panel or the graphical view, and the vertical rail of the
 *   function strip. A portrait window of 840 dp or more uses the medium layout: the app gives
 *   the expanded layout for the landscape tablet and the medium layout for the portrait tablet.
 * - Medium (600 dp to 839 dp): the readout with the full width, the graphical view below or above
 *   it, the side panel tabs with "+", and two rows of function buttons. The side panel opens
 *   as a bottom sheet.
 * - Compact (less than 600 dp): as medium, with the function strip in a bottom sheet.
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
) {
    // The font of the digits is a setting of the machine profile.
    val typography = readoutTypography(state.readoutFont)
    CompositionLocalProvider(LocalReadoutTypography provides typography) {
        Surface(modifier.fillMaxSize().testTag(READOUT_SCREEN_TAG), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                StatusStrip(state.status, trailing = statusItems)
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
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val readoutWidth = maxWidth * READOUT_PANE_FRACTION
        val railWidth = RailWidth * (targets.button / 64.dp)
        Row(Modifier.fillMaxSize()) {
            ReadoutRows(
                rows = state.rows,
                axisValue = axisValue,
                // The open side panel gives the collapsed readout. A readout pane of less
                // than 600 dp also uses the collapsed buttons, so that the digits keep a useful size.
                collapsed = panelOpen || readoutWidth < COLLAPSED_PANE_WIDTH,
                format = state.readoutFormat,
                events = events,
                modifier = Modifier.width(readoutWidth).fillMaxHeight(),
            )
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
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FunctionStrip(
                global = state.globalButtons,
                functions = state.functions,
                events = events,
                form = StripForm.Rail,
                modifier = Modifier.width(railWidth).fillMaxHeight(),
            )
        }
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
    val pane = graphicalViewPane.takeIf { state.graphicalViewPlacement != GraphicalViewPlacement.FullScreen }
    Column(modifier.fillMaxWidth()) {
        if (pane != null && state.graphicalViewPlacement == GraphicalViewPlacement.AboveReadout) {
            GraphicalPane(pane, Modifier.weight(GRAPHICAL_PANE_WEIGHT))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        ReadoutRows(
            rows = state.rows,
            axisValue = axisValue,
            collapsed = false,
            format = state.readoutFormat,
            events = events,
            modifier = Modifier.weight(1f - if (pane != null) GRAPHICAL_PANE_WEIGHT else 0f).fillMaxWidth(),
        )
        if (pane != null && state.graphicalViewPlacement == GraphicalViewPlacement.BelowReadout) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            GraphicalPane(pane, Modifier.weight(GRAPHICAL_PANE_WEIGHT))
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
        if (!compact) {
            FunctionStrip(state.globalButtons, state.functions, events, StripForm.TwoRows, Modifier.fillMaxWidth())
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
