package org.superdro.core.ui.graphics.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.functions.graphics.GuideOrientation
import org.superdro.core.functions.graphics.ScreenMetrics
import org.superdro.core.functions.graphics.ZoomPreset
import org.superdro.core.model.Plane
import org.superdro.core.resources.Res
import org.superdro.core.resources.graphics_auto_proximity
import org.superdro.core.resources.graphics_auto_sub_datum
import org.superdro.core.resources.graphics_auto_tool_offset
import org.superdro.core.resources.graphics_auto_zero
import org.superdro.core.resources.graphics_automatic_functions
import org.superdro.core.resources.graphics_axis_alignment
import org.superdro.core.resources.graphics_canvas_description
import org.superdro.core.resources.graphics_cancel
import org.superdro.core.resources.graphics_direction_back
import org.superdro.core.resources.graphics_direction_back_left
import org.superdro.core.resources.graphics_direction_back_right
import org.superdro.core.resources.graphics_direction_front
import org.superdro.core.resources.graphics_direction_front_left
import org.superdro.core.resources.graphics_direction_front_right
import org.superdro.core.resources.graphics_direction_left
import org.superdro.core.resources.graphics_direction_none
import org.superdro.core.resources.graphics_direction_right
import org.superdro.core.resources.graphics_direction_title
import org.superdro.core.resources.graphics_display
import org.superdro.core.resources.graphics_display_toggle
import org.superdro.core.resources.graphics_full_screen
import org.superdro.core.resources.graphics_menu_add_horizontal
import org.superdro.core.resources.graphics_menu_add_vertical
import org.superdro.core.resources.graphics_menu_delete
import org.superdro.core.resources.graphics_menu_edit
import org.superdro.core.resources.graphics_menu_set_origin
import org.superdro.core.resources.graphics_part_rotation
import org.superdro.core.resources.graphics_plane
import org.superdro.core.resources.graphics_recenter
import org.superdro.core.resources.graphics_selected
import org.superdro.core.resources.graphics_snap_guide
import org.superdro.core.resources.graphics_snap_point
import org.superdro.core.resources.graphics_switch_off
import org.superdro.core.resources.graphics_switch_on
import org.superdro.core.resources.graphics_thread_arm
import org.superdro.core.resources.graphics_view_locked
import org.superdro.core.resources.graphics_zoom
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_lock
import org.superdro.core.resources.ic_radio_unchecked
import org.superdro.core.resources.route_edge_finder
import org.superdro.core.resources.route_rotate_part
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.graphics.thread.ThreadPanel
import org.superdro.core.ui.graphics.thread.ThreadPanelEvents
import org.superdro.core.ui.graphics.thread.ThreadPanelUiState
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.accentColor
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

/** The test tag of the top strip of the graphical view. */
const val GRAPHICAL_TOP_STRIP_TAG: String = "graphical-top-strip"

/** The test tag of a switch chip: `graphical-switch-<name>`. */
fun graphicalSwitchTag(switch: GraphicalSwitch): String = "graphical-switch-${switch.name}"

/** The test tag of the plane button of [plane]. */
fun graphicalPlaneTag(plane: Plane): String = "graphical-plane-${plane.name}"

/** The test tag of the "Re-center" button. */
const val GRAPHICAL_RECENTER_TAG: String = "graphical-recenter"

/** The test tag of the sheet of the display pane in a short view. */
const val DISPLAY_SHEET_TAG: String = "graphical-display-sheet"

/** The test tag of the view lock badge. */
const val GRAPHICAL_LOCK_BADGE_TAG: String = "graphical-lock-badge"

/** The events of the controls of the graphical view. */
interface GraphicalViewEvents : GraphicalCanvasEvents, ThreadPanelEvents {
    fun onSwitch(switch: GraphicalSwitch, on: Boolean)
    fun onPlane(plane: Plane)
    fun onDisplayPaneToggle()
    fun onRecenter()
    fun onZoomPreset(preset: ZoomPreset)
    fun onFullScreen()
    fun onRotatePart()
    fun onEdgeFinder()
    fun onThreadArm()
    fun onDirection(position: ToolPosition)
    fun onOverlayDismiss()
    fun onEditSubDatum(id: String)
    fun onDeleteSubDatum(id: String)
    fun onSetOrigin(id: String)
    fun onAddGuideAtPoint(id: String, orientation: GuideOrientation)
    fun onDeleteShapeConfirmed(index: Int)
}

/**
 * The graphical view with its controls: the
 * canvas, the top strip (display pane, plane selector, rotate part, edge finder, thread arm, full
 * screen), the automatic function switches, "Re-center", the zoom presets, the view lock badge,
 * the angle labels of Rotate Part and Align Axes, the display pane, the thread cut panel, and the
 * dialogs of the view. It is stateless.
 *
 * @param showFullScreen True for the pane of the readout: the button "Full screen" shows.
 * @param extraTopStrip More buttons of the top strip, for example the measurement mode switch.
 * @param leftPanel A panel at the top left, for example the measurement pane.
 * @param displayPane The display pane. It shows when [displayPaneOpen] is true: an overlay at the
 *   right edge, or a sheet in a short view (for example the pane below the readout in portrait).
 * @param deleteShapeDialog The "Delete Geometry?" dialog of the drawing area for a shape index.
 */
@Composable
fun GraphicalView(
    view: GraphicalViewUiState,
    controls: GraphicalControlsUiState,
    metrics: ScreenMetrics,
    events: GraphicalViewEvents,
    modifier: Modifier = Modifier,
    showFullScreen: Boolean = false,
    displayPaneOpen: Boolean = false,
    threadPanel: ThreadPanelUiState? = null,
    referenceImage: ReferenceLayer? = null,
    measurementMarks: List<MeasurementMark> = emptyList(),
    extraTopStrip: @Composable () -> Unit = {},
    leftPanel: (@Composable () -> Unit)? = null,
    displayPane: (@Composable () -> Unit)? = null,
    deleteShapeDialog: @Composable (index: Int, typeName: org.superdro.core.model.ShapeType, onConfirm: () -> Unit, onDismiss: () -> Unit) -> Unit =
        { _, _, onConfirm, onDismiss -> DefaultDeleteShapeDialog(onConfirm, onDismiss) },
    backdrop: (@Composable () -> Unit)? = null,
    transparentBackground: Boolean = false,
    cursorColor: Color? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val narrow = maxWidth < 600.dp
        val compactWidth = maxWidth < 460.dp
        val short = maxHeight < SHORT_VIEW_HEIGHT
        val displayAsSheet = short || compactWidth
        // The camera video goes under the canvas.
        if (backdrop != null) Box(Modifier.fillMaxSize()) { backdrop() }
        GraphicalCanvas(
            state = view,
            metrics = metrics,
            events = events,
            referenceImage = referenceImage,
            measurementMarks = measurementMarks,
            description = stringResource(Res.string.graphics_canvas_description, controls.workspaceName),
            modifier = Modifier.fillMaxSize(),
            transparentBackground = transparentBackground,
            cursorColor = cursorColor,
        )
        Column(Modifier.fillMaxSize()) {
            TopStrip(controls, events, showFullScreen, displayPaneOpen, extraTopStrip)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                val leftWidth = when {
                    leftPanel != null && narrow -> 300.dp
                    leftPanel != null -> 440.dp
                    narrow -> 220.dp
                    else -> 280.dp
                }
                // In a short view the left panel (the measurement pane) gets the full height: the
                // automatic functions go to the right edge, where the display pane is not.
                val switchesAtRight = short && leftPanel != null
                Column(
                    Modifier.padding(8.dp).widthIn(max = leftWidth).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // A panel can scroll by itself (the measurement pane), so it gets a bounded height.
                    if (leftPanel != null) Box(Modifier.weight(1f, fill = false)) { leftPanel() }
                    if (!switchesAtRight) SwitchArea(controls, events, threadPanel, menu = short || narrow || leftPanel != null, narrow = narrow)
                }
                Spacer(Modifier.weight(1f))
                if (switchesAtRight) {
                    Column(Modifier.padding(8.dp)) { SwitchArea(controls, events, threadPanel, menu = true, narrow = narrow) }
                }
                // A short or compact-width view opens the display pane as a sheet (see below), because an
                // overlay in the view shows only the first rows or leaves no room for the canvas.
                if (displayPane != null && displayPaneOpen && !displayAsSheet) {
                    Surface(
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 64.dp).width(if (narrow) 260.dp else 320.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = PANEL_ALPHA),
                        tonalElevation = 3.dp,
                    ) { displayPane() }
                }
            }
        }
        if (displayPane != null && displayPaneOpen && displayAsSheet) {
            // The sheet closes with the same event as the "Display" chip.
            AdaptiveSheet(onDismissRequest = events::onDisplayPaneToggle, modifier = Modifier.testTag(DISPLAY_SHEET_TAG), scrollable = false) {
                displayPane()
            }
        }
        if (maxWidth >= 520.dp) {
            StatusBadges(view, controls, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
            if (view.recenterVisible && !view.locked) {
                FilledTonalButton(
                    onClick = events::onRecenter,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).heightIn(min = 48.dp).testTag(GRAPHICAL_RECENTER_TAG),
                ) { Text(stringResource(Res.string.graphics_recenter)) }
            }
            ZoomPresets(events, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        } else {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusBadges(view, controls, Modifier.align(Alignment.CenterHorizontally))
                if (view.recenterVisible && !view.locked) {
                    FilledTonalButton(
                        onClick = events::onRecenter,
                        modifier = Modifier.align(Alignment.Start).heightIn(min = 48.dp).testTag(GRAPHICAL_RECENTER_TAG),
                    ) { Text(stringResource(Res.string.graphics_recenter)) }
                }
                ZoomPresets(events, Modifier.align(Alignment.End), wrap = true)
            }
        }
        Overlays(view, events, deleteShapeDialog)
    }
}

/**
 * The automatic functions (a column of chips, or one chip with a menu when [menu] is true) and
 * the thread cut panel. The area scrolls when it does not fit.
 */
@Composable
private fun SwitchArea(
    controls: GraphicalControlsUiState,
    events: GraphicalViewEvents,
    threadPanel: ThreadPanelUiState?,
    menu: Boolean,
    narrow: Boolean,
) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (menu) SwitchMenu(controls, events) else SwitchColumn(controls, events)
        if (threadPanel != null) ThreadPanel(threadPanel, events, Modifier.width(if (narrow) 220.dp else 280.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopStrip(
    controls: GraphicalControlsUiState,
    events: GraphicalViewEvents,
    showFullScreen: Boolean,
    displayPaneOpen: Boolean,
    extra: @Composable () -> Unit,
) {
    // The controls wrap to a second row in a narrow view (the supporting pane), so that no control
    // is clipped at the edge.
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag(GRAPHICAL_TOP_STRIP_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleChip(
            label = stringResource(Res.string.graphics_display),
            selected = displayPaneOpen,
            onClick = events::onDisplayPaneToggle,
            description = stringResource(Res.string.graphics_display_toggle),
        )
        PlaneSelector(controls, events)
        extra()
        if (!controls.machineType.isLathe) {
            ActionChip(stringResource(Res.string.route_rotate_part), events::onRotatePart, active = controls.partRotationDegrees != null)
            if (controls.edgeFinderVisible) ActionChip(stringResource(Res.string.route_edge_finder), events::onEdgeFinder)
        } else {
            ToggleChip(stringResource(Res.string.graphics_thread_arm), selected = controls.threadArmed, onClick = events::onThreadArm)
        }
        if (showFullScreen) ActionChip(stringResource(Res.string.graphics_full_screen), events::onFullScreen)
    }
}

@Composable
private fun PlaneSelector(controls: GraphicalControlsUiState, events: GraphicalViewEvents) {
    val label = stringResource(Res.string.graphics_plane)
    SingleChoiceSegmentedButtonRow(Modifier.semantics { contentDescription = label }) {
        controls.planes.forEachIndexed { index, plane ->
            SegmentedButton(
                selected = controls.plane == plane,
                onClick = { events.onPlane(plane) },
                shape = SegmentedButtonDefaults.itemShape(index, controls.planes.size),
                colors = SegmentedButtonDefaults.colors(inactiveContainerColor = overlayContainer()),
                modifier = Modifier.heightIn(min = 48.dp).testTag(graphicalPlaneTag(plane)),
            ) { Text(plane.name) }
        }
    }
}

@Composable
private fun SwitchColumn(controls: GraphicalControlsUiState, events: GraphicalViewEvents) {
    controls.switches.forEach { (switch, on) ->
        ToggleChip(
            label = stringResource(switchLabel(switch)),
            selected = on,
            onClick = { events.onSwitch(switch, !on) },
            modifier = Modifier.testTag(graphicalSwitchTag(switch)),
        )
    }
}

/** The test tag of the chip of the automatic functions in a short view. */
const val GRAPHICAL_SWITCH_MENU_TAG: String = "graphical-switch-menu"

/**
 * The automatic functions in a short view, for example the pane below the readout: one chip that
 * opens a menu. Each item shows its state with a check icon.
 */
@Composable
private fun SwitchMenu(controls: GraphicalControlsUiState, events: GraphicalViewEvents) {
    var open by remember { mutableStateOf(false) }
    val count = controls.switches.count { (_, on) -> on }
    Box {
        ToggleChip(
            label = stringResource(Res.string.graphics_automatic_functions, count),
            selected = count > 0,
            onClick = { open = true },
            modifier = Modifier.testTag(GRAPHICAL_SWITCH_MENU_TAG),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            controls.switches.forEach { (switch, on) ->
                DropdownMenuItem(
                    text = { Text(stringResource(switchLabel(switch))) },
                    leadingIcon = {
                        Icon(
                            painterResource(if (on) Res.drawable.ic_check else Res.drawable.ic_radio_unchecked),
                            contentDescription = stringResource(if (on) Res.string.graphics_switch_on else Res.string.graphics_switch_off),
                        )
                    },
                    onClick = { events.onSwitch(switch, !on) },
                    modifier = Modifier.testTag(graphicalSwitchTag(switch)),
                )
            }
        }
    }
}

/** The container color of a control above the canvas: the canvas lines do not cross the text. */
@Composable
private fun overlayContainer() = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = PANEL_ALPHA)

/** Gives the label of [switch]. */
fun switchLabel(switch: GraphicalSwitch): StringResource = when (switch) {
    GraphicalSwitch.SnapToPoint -> Res.string.graphics_snap_point
    GraphicalSwitch.SnapToGuide -> Res.string.graphics_snap_guide
    GraphicalSwitch.AutomaticToolOffset -> Res.string.graphics_auto_tool_offset
    GraphicalSwitch.AutomaticProximityWarning -> Res.string.graphics_auto_proximity
    GraphicalSwitch.AutomaticZero -> Res.string.graphics_auto_zero
    GraphicalSwitch.AutomaticSubDatum -> Res.string.graphics_auto_sub_datum
}

/**
 * A chip of an on and off state. The state shows as a check icon and as a state description,
 * not only as a color.
 */
@Composable
fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    val state = stringResource(if (selected) Res.string.graphics_switch_on else Res.string.graphics_switch_off)
    FilterChip(
        selected = selected,
        onClick = onClick,
        colors = FilterChipDefaults.filterChipColors(containerColor = overlayContainer()),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (selected) {
            { Icon(painterResource(Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        modifier = modifier.heightIn(min = 48.dp).semantics {
            stateDescription = state
            if (description != null) contentDescription = description
        },
    )
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit, active: Boolean = false) {
    AssistChip(
        onClick = onClick,
        colors = AssistChipDefaults.assistChipColors(containerColor = overlayContainer()),
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (active) {
            { Icon(painterResource(Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoomPresets(events: GraphicalViewEvents, modifier: Modifier, wrap: Boolean = false) {
    @Composable
    fun buttons() {
        ZoomPreset.entries.forEach { preset ->
            val description = stringResource(Res.string.graphics_zoom, preset.label)
            OutlinedButton(
                onClick = { events.onZoomPreset(preset) },
                colors = ButtonDefaults.outlinedButtonColors(containerColor = overlayContainer(), contentColor = accentColor()),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
            ) { Text(preset.label) }
        }
    }
    if (wrap) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { buttons() }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) { buttons() }
    }
}

@Composable
private fun StatusBadges(view: GraphicalViewUiState, controls: GraphicalControlsUiState, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (view.locked) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(GRAPHICAL_LOCK_BADGE_TAG),
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(Res.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.graphics_view_locked), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        controls.partRotationDegrees?.let { degrees -> AngleLabel(stringResource(Res.string.graphics_part_rotation, twoDecimals(degrees))) }
        controls.alignmentDegrees?.let { degrees -> AngleLabel(stringResource(Res.string.graphics_axis_alignment, twoDecimals(degrees))) }
    }
}

@Composable
private fun AngleLabel(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = PANEL_ALPHA), shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun Overlays(
    view: GraphicalViewUiState,
    events: GraphicalViewEvents,
    deleteShapeDialog: @Composable (Int, org.superdro.core.model.ShapeType, () -> Unit, () -> Unit) -> Unit,
) {
    when (val overlay = view.overlay) {
        is ViewOverlay.DirectionPicker -> DirectionPicker(overlay.current, events)
        is ViewOverlay.SubDatumMenu -> SubDatumMenu(overlay, events)
        is ViewOverlay.DeleteShape -> deleteShapeDialog(overlay.index, overlay.type, { events.onDeleteShapeConfirmed(overlay.index) }, events::onOverlayDismiss)
        null -> Unit
    }
}

/** The test tag of a button of the direction picker. */
fun directionTag(position: ToolPosition): String = "graphical-direction-${position.name}"

/** The tool offset direction picker: a 3 × 3 grid. The button of the current signs is selected. */
@Composable
private fun DirectionPicker(current: ToolPosition, events: GraphicalViewEvents) {
    val rows = listOf(
        listOf(ToolPosition.BackLeft, ToolPosition.BackCenter, ToolPosition.BackRight),
        listOf(ToolPosition.MiddleLeft, ToolPosition.Center, ToolPosition.MiddleRight),
        listOf(ToolPosition.FrontLeft, ToolPosition.FrontCenter, ToolPosition.FrontRight),
    )
    val selectedText = stringResource(Res.string.graphics_selected)
    AlertDialog(
        onDismissRequest = events::onOverlayDismiss,
        title = { Text(stringResource(Res.string.graphics_direction_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { position ->
                            val label = stringResource(directionLabel(position))
                            val selected = position == current
                            val modifier = Modifier.size(width = 96.dp, height = 64.dp).testTag(directionTag(position))
                                .semantics { if (selected) stateDescription = selectedText }
                            if (selected) {
                                FilledTonalButton(onClick = { events.onDirection(position) }, modifier = modifier) { Text(label, maxLines = 2) }
                            } else {
                                OutlinedButton(
                                    onClick = { events.onDirection(position) },
                                    colors = AccentButtons.outlined(),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
                                    modifier = modifier,
                                ) { Text(label, maxLines = 2) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = events::onOverlayDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.graphics_cancel)) } },
    )
}

/** Gives the label of a direction of the picker. */
fun directionLabel(position: ToolPosition): StringResource = when (position) {
    ToolPosition.BackLeft -> Res.string.graphics_direction_back_left
    ToolPosition.BackCenter -> Res.string.graphics_direction_back
    ToolPosition.BackRight -> Res.string.graphics_direction_back_right
    ToolPosition.MiddleLeft -> Res.string.graphics_direction_left
    ToolPosition.Center -> Res.string.graphics_direction_none
    ToolPosition.MiddleRight -> Res.string.graphics_direction_right
    ToolPosition.FrontLeft -> Res.string.graphics_direction_front_left
    ToolPosition.FrontCenter -> Res.string.graphics_direction_front
    ToolPosition.FrontRight -> Res.string.graphics_direction_front_right
}

/** The test tag of the sub-datum context menu. */
const val SUB_DATUM_MENU_TAG: String = "graphical-sub-datum-menu"

/** The sub-datum context menu at the touch position. */
@Composable
private fun SubDatumMenu(menu: ViewOverlay.SubDatumMenu, events: GraphicalViewEvents) {
    val density = LocalDensity.current
    Box(Modifier.offset { IntOffset(menu.anchor.x.roundToInt(), menu.anchor.y.roundToInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = events::onOverlayDismiss, modifier = Modifier.testTag(SUB_DATUM_MENU_TAG)) {
            Text(
                menu.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = with(density) { 8.dp }),
            )
            DropdownMenuItem(text = { Text(stringResource(Res.string.graphics_menu_edit)) }, onClick = { events.onEditSubDatum(menu.subDatumId) })
            DropdownMenuItem(text = { Text(stringResource(Res.string.graphics_menu_delete)) }, onClick = { events.onDeleteSubDatum(menu.subDatumId) })
            DropdownMenuItem(text = { Text(stringResource(Res.string.graphics_menu_set_origin)) }, onClick = { events.onSetOrigin(menu.subDatumId) })
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.graphics_menu_add_horizontal)) },
                enabled = menu.buttons.horizontalEnabled,
                onClick = { events.onAddGuideAtPoint(menu.subDatumId, GuideOrientation.Horizontal) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.graphics_menu_add_vertical)) },
                enabled = menu.buttons.verticalEnabled,
                onClick = { events.onAddGuideAtPoint(menu.subDatumId, GuideOrientation.Vertical) },
            )
        }
    }
}

/** The default "Delete Geometry?" question. The drawing area replaces it with its own dialog. */
@Composable
internal fun DefaultDeleteShapeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(Res.string.graphics_menu_delete)) },
        confirmButton = { TextButton(onClick = onConfirm, colors = AccentButtons.text()) { Text(stringResource(Res.string.graphics_menu_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.graphics_cancel)) } },
    )
}

/** Gives [value] with two decimals and `.` as the decimal mark. */
internal fun twoDecimals(value: Double): String {
    val scaled = kotlin.math.floor(value * 100.0 + 0.5).toLong()
    val negative = scaled < 0L
    val magnitude = kotlin.math.abs(scaled)
    return (if (negative) "-" else "") + "${magnitude / 100}." + (magnitude % 100).toString().padStart(2, '0')
}

/** The alpha of the panels above the canvas. */
private const val PANEL_ALPHA: Float = 0.92f

/**
 * A view lower than this is short: it shows the automatic functions in a menu, and it opens the
 * display pane as a sheet.
 */
private val SHORT_VIEW_HEIGHT = 560.dp
