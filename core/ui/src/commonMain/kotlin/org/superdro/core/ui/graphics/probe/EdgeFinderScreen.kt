package org.superdro.core.ui.graphics.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.probe.CoordinateMode
import org.superdro.core.functions.probe.EdgeSide
import org.superdro.core.functions.probe.EdgeStatus
import org.superdro.core.functions.probe.FeatureType
import org.superdro.core.functions.probe.OriginOption
import org.superdro.core.model.AxisId
import org.superdro.core.resources.Res
import org.superdro.core.resources.edgefinder_absolute
import org.superdro.core.resources.edgefinder_add_sub_datum
import org.superdro.core.resources.edgefinder_add_sub_datum_hint
import org.superdro.core.resources.edgefinder_armed
import org.superdro.core.resources.edgefinder_auto
import org.superdro.core.resources.edgefinder_auto_description
import org.superdro.core.resources.edgefinder_axis_title
import org.superdro.core.resources.edgefinder_camera_center
import org.superdro.core.resources.edgefinder_camera_center_clear
import org.superdro.core.resources.edgefinder_cancel
import org.superdro.core.resources.edgefinder_center
import org.superdro.core.resources.edgefinder_edge_description
import org.superdro.core.resources.edgefinder_edge_far
import org.superdro.core.resources.edgefinder_edge_left
import org.superdro.core.resources.edgefinder_edge_near
import org.superdro.core.resources.edgefinder_edge_right
import org.superdro.core.resources.edgefinder_external
import org.superdro.core.resources.edgefinder_incremental
import org.superdro.core.resources.edgefinder_internal
import org.superdro.core.resources.edgefinder_off
import org.superdro.core.resources.edgefinder_on
import org.superdro.core.resources.edgefinder_origin
import org.superdro.core.resources.edgefinder_set_origin
import org.superdro.core.resources.edgefinder_state_located
import org.superdro.core.resources.edgefinder_state_scanning
import org.superdro.core.resources.edgefinder_state_unknown
import org.superdro.core.resources.edgefinder_sync
import org.superdro.core.resources.edgefinder_title
import org.superdro.core.resources.edgefinder_z_arm
import org.superdro.core.resources.edgefinder_z_arm_description
import org.superdro.core.resources.edgefinder_z_position
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_hourglass
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_radio_unchecked
import org.superdro.core.ui.graphics.common.ValueField
import org.superdro.core.ui.graphics.common.unitText
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.readout.DroButton
import org.superdro.core.ui.readout.DroButtonStyle
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.adapter.probe.ProbeSelector

/** The test tag of the edge finder screen. */
const val EDGE_FINDER_TAG: String = "edge-finder"

/** The test tag of the AUTO button. */
const val EDGE_FINDER_AUTO_TAG: String = "edge-finder-auto"

/** The test tag of the camera center button. */
const val EDGE_FINDER_CAMERA_TAG: String = "edge-finder-camera-center"

/** The test tag of the origin text. */
const val EDGE_FINDER_ORIGIN_TAG: String = "edge-finder-origin"

/** The test tag of the message line. */
const val EDGE_FINDER_MESSAGE_TAG: String = "edge-finder-message"

/** The test tag of "Set Origin". */
const val EDGE_FINDER_SET_ORIGIN_TAG: String = "edge-finder-set-origin"

/** The test tag of "Add Sub-Datum". */
const val EDGE_FINDER_ADD_TAG: String = "edge-finder-add-sub-datum"

/** The test tag of "Cancel". */
const val EDGE_FINDER_CANCEL_TAG: String = "edge-finder-cancel"

/** The test tag of the Z arm button. */
const val EDGE_FINDER_Z_ARM_TAG: String = "edge-finder-z-arm"

/** Gives the test tag of the button of the edge [side]. */
fun edgeTag(side: EdgeSide): String = "edge-finder-edge-${side.name}"

/** Gives the test tag of the origin option [option] of [axis]. */
fun optionTag(axis: AxisId, option: OriginOption): String = "edge-finder-option-${axis.name}-${option.name}"

/** Gives the test tag of the feature type [feature] of [axis]. */
fun featureTag(axis: AxisId, feature: FeatureType): String = "edge-finder-feature-${axis.name}-${feature.name}"

/** Gives the test tag of the coordinate system [mode]. */
fun modeTag(mode: CoordinateMode): String = "edge-finder-mode-${mode.name}"

/** The events of the edge finder screen. The presenter gives them; [EdgeFinderEntry] connects them. */
interface EdgeFinderEvents {
    fun onEdgeTap(side: EdgeSide)
    fun onEdgeLongPress(side: EdgeSide)
    fun onAuto()
    fun onCameraCenter()
    fun onFeature(axis: AxisId, feature: FeatureType)
    fun onOption(axis: AxisId, option: OriginOption)
    fun onMode(mode: CoordinateMode)
    fun onZArm()
    fun onZText(text: String)
    fun onZSync()
    fun onSetOrigin()
    fun onAddSubDatum(keepEdges: Boolean)
    fun onCancel()
    fun onSelectProbe(id: String?)
    fun onNewProbe()

    /** The edit button of the probe selector: opens the probe profile [id]. */
    fun onEditProbe(id: String) {}
}

/**
 * The edge finder screen "Indicate Workpiece" (`EdgeFinderRoute`). It is
 * stateless: it gets the [state] of [EdgeFinderPresenter] and the [events].
 *
 * The four edge buttons are a cross: Far at the top, Left and Right at the sides, Near at the
 * bottom. AUTO (or the camera center button) is in the middle. A wide window has the cross at the
 * left and the origin controls at the right. A narrow window has one column that scrolls.
 */
@Composable
fun EdgeFinderScreen(state: EdgeFinderUiState, events: EdgeFinderEvents, modifier: Modifier = Modifier) {
    ScreenScaffold(title = stringResource(Res.string.edgefinder_title), onBack = events::onCancel, modifier = modifier.testTag(EDGE_FINDER_TAG)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            if (wide) {
                Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ProbeRow(state, events)
                        MessageLine(state.message)
                        EdgeCross(state, events, Modifier.align(Alignment.CenterHorizontally))
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OriginControls(state, events)
                        Actions(state, events)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ProbeRow(state, events)
                    MessageLine(state.message)
                    EdgeCross(state, events, Modifier.align(Alignment.CenterHorizontally))
                    OriginControls(state, events)
                    Actions(state, events)
                }
            }
        }
    }
}

@Composable
private fun ProbeRow(state: EdgeFinderUiState, events: EdgeFinderEvents) {
    ProbeSelector(
        state = state.selector,
        onSelect = events::onSelectProbe,
        onNewProfile = events::onNewProbe,
        onEdit = events::onEditProbe,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The message line: an icon and the text. Nothing when [message] is null. */
@Composable
internal fun MessageLine(message: UiText?, modifier: Modifier = Modifier) {
    if (message == null) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(message.resolve(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(EDGE_FINDER_MESSAGE_TAG))
        }
    }
}

@Composable
private fun EdgeCross(state: EdgeFinderUiState, events: EdgeFinderEvents, modifier: Modifier) {
    val gap = LocalTouchTargets.current.gap
    val size = EdgeButtonWidth
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
        EdgeButton(state.edge(EdgeSide.Far), events)
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            EdgeButton(state.edge(EdgeSide.Left), events)
            CenterButton(state, events, size)
            EdgeButton(state.edge(EdgeSide.Right), events)
        }
        EdgeButton(state.edge(EdgeSide.Near), events)
    }
}

@Composable
private fun CenterButton(state: EdgeFinderUiState, events: EdgeFinderEvents, width: Dp) {
    when {
        state.cameraCenterVisible -> {
            val label = stringResource(if (state.cameraCenterSet) Res.string.edgefinder_camera_center_clear else Res.string.edgefinder_camera_center)
            DroButton(
                onClick = events::onCameraCenter,
                contentDescription = label,
                style = if (state.cameraCenterSet) DroButtonStyle.Active else DroButtonStyle.Primary,
                minWidth = width,
                minHeight = EdgeButtonHeight,
                modifier = Modifier.widthIn(max = width).testTag(EDGE_FINDER_CAMERA_TAG),
            ) { Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
        }
        state.autoVisible -> {
            val label = stringResource(Res.string.edgefinder_auto)
            val status = stringResource(if (state.autoOn) Res.string.edgefinder_on else Res.string.edgefinder_off)
            DroButton(
                onClick = events::onAuto,
                contentDescription = stringResource(Res.string.edgefinder_auto_description),
                stateDescription = status,
                style = if (state.autoOn) DroButtonStyle.Active else DroButtonStyle.Neutral,
                minWidth = width,
                minHeight = EdgeButtonHeight,
                modifier = Modifier.testTag(EDGE_FINDER_AUTO_TAG),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    Text(status, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        else -> Spacer(Modifier.width(width).heightIn(min = EdgeButtonHeight))
    }
}

@Composable
private fun EdgeButton(edge: EdgeButtonUiState, events: EdgeFinderEvents) {
    val label = stringResource(edgeLabel(edge.side))
    val status = stringResource(statusText(edge.status))
    DroButton(
        onClick = { events.onEdgeTap(edge.side) },
        onLongClick = { events.onEdgeLongPress(edge.side) },
        contentDescription = stringResource(Res.string.edgefinder_edge_description, label, status),
        stateDescription = status,
        style = when (edge.status) {
            EdgeStatus.Unknown -> DroButtonStyle.Neutral
            EdgeStatus.Scanning -> DroButtonStyle.Primary
            EdgeStatus.Located -> DroButtonStyle.Active
        },
        minWidth = EdgeButtonWidth,
        minHeight = EdgeButtonHeight,
        modifier = Modifier.testTag(edgeTag(edge.side)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(statusIcon(edge.status)), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(status, style = MaterialTheme.typography.labelMedium)
            }
            Text(edge.valueText ?: "—", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun OriginControls(state: EdgeFinderUiState, events: EdgeFinderEvents) {
    AxisPanel(AxisId.X, state.xFeature, state.xOption, state.xOptions, state.featureEnabled, events)
    AxisPanel(AxisId.Y, state.yFeature, state.yOption, state.yOptions, state.featureEnabled, events)
    if (state.zRowVisible) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ValueField(
                label = stringResource(Res.string.edgefinder_z_position),
                text = state.zText,
                onTextChange = events::onZText,
                unit = unitText(state.metric),
                error = state.zError,
                syncLabel = stringResource(Res.string.edgefinder_sync),
                onSync = events::onZSync,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val arm = stringResource(if (state.zArmed) Res.string.edgefinder_armed else Res.string.edgefinder_z_arm)
            DroButton(
                onClick = events::onZArm,
                contentDescription = stringResource(Res.string.edgefinder_z_arm_description),
                stateDescription = stringResource(if (state.zArmed) Res.string.edgefinder_on else Res.string.edgefinder_off),
                style = if (state.zArmed) DroButtonStyle.Active else DroButtonStyle.Neutral,
                modifier = Modifier.testTag(EDGE_FINDER_Z_ARM_TAG),
            ) { Text(arm, style = MaterialTheme.typography.labelLarge) }
        }
    }
    Choice(
        title = null,
        items = listOf(CoordinateMode.Absolute to Res.string.edgefinder_absolute, CoordinateMode.Incremental to Res.string.edgefinder_incremental),
        selected = state.mode,
        enabled = { true },
        onSelect = events::onMode,
        tag = ::modeTag,
    )
    Column {
        Text(stringResource(Res.string.edgefinder_origin), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            state.originText,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(EDGE_FINDER_ORIGIN_TAG),
        )
    }
}

@Composable
private fun AxisPanel(
    axis: AxisId,
    feature: FeatureType,
    option: OriginOption?,
    options: Set<OriginOption>,
    featureEnabled: Boolean,
    events: EdgeFinderEvents,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.edgefinder_axis_title, axis.name), style = MaterialTheme.typography.titleMedium)
        Choice(
            title = null,
            items = listOf(FeatureType.External to Res.string.edgefinder_external, FeatureType.Internal to Res.string.edgefinder_internal),
            selected = feature,
            enabled = { featureEnabled },
            onSelect = { value -> events.onFeature(axis, value) },
            tag = { value -> featureTag(axis, value) },
        )
        val sideA = if (axis == AxisId.X) Res.string.edgefinder_edge_left else Res.string.edgefinder_edge_near
        val sideB = if (axis == AxisId.X) Res.string.edgefinder_edge_right else Res.string.edgefinder_edge_far
        // The Y list is "Far, Center, Near": the B side first.
        val items = if (axis == AxisId.X) {
            listOf(OriginOption.SideA to sideA, OriginOption.Center to Res.string.edgefinder_center, OriginOption.SideB to sideB)
        } else {
            listOf(OriginOption.SideB to sideB, OriginOption.Center to Res.string.edgefinder_center, OriginOption.SideA to sideA)
        }
        Choice(
            title = null,
            items = items,
            selected = option,
            enabled = { value -> value in options },
            onSelect = { value -> events.onOption(axis, value) },
            tag = { value -> optionTag(axis, value) },
        )
    }
}

@Composable
private fun <T> Choice(
    title: String?,
    items: List<Pair<T, StringResource>>,
    selected: T?,
    enabled: (T) -> Boolean,
    onSelect: (T) -> Unit,
    tag: (T) -> String,
) {
    if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, (value, text) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled(value),
                shape = SegmentedButtonDefaults.itemShape(index, items.size),
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(tag(value)),
            ) { Text(stringResource(text)) }
        }
    }
}

@Composable
private fun ColumnScope.Actions(state: EdgeFinderUiState, events: EdgeFinderEvents) {
    val gap = LocalTouchTargets.current.gap
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = events::onCancel,
            colors = AccentButtons.outlined(),
            modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(EDGE_FINDER_CANCEL_TAG),
        ) { Text(stringResource(Res.string.edgefinder_cancel)) }
        val add = stringResource(Res.string.edgefinder_add_sub_datum)
        DroButton(
            onClick = { if (state.addSubDatumEnabled) events.onAddSubDatum(keepEdges = false) },
            onLongClick = { if (state.addSubDatumEnabled) events.onAddSubDatum(keepEdges = true) },
            contentDescription = "$add. ${stringResource(Res.string.edgefinder_add_sub_datum_hint)}",
            style = if (state.addSubDatumEnabled) DroButtonStyle.Primary else DroButtonStyle.Neutral,
            modifier = Modifier.testTag(EDGE_FINDER_ADD_TAG),
        ) { Text(add, style = MaterialTheme.typography.labelLarge) }
        val set = stringResource(Res.string.edgefinder_set_origin)
        DroButton(
            onClick = { if (state.setOriginEnabled) events.onSetOrigin() },
            contentDescription = set,
            style = if (state.setOriginEnabled) DroButtonStyle.Primary else DroButtonStyle.Neutral,
            modifier = Modifier.testTag(EDGE_FINDER_SET_ORIGIN_TAG),
        ) { Text(set, style = MaterialTheme.typography.labelLarge) }
    }
    Text(
        stringResource(Res.string.edgefinder_add_sub_datum_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.End),
    )
}

private fun edgeLabel(side: EdgeSide): StringResource = when (side) {
    EdgeSide.Left -> Res.string.edgefinder_edge_left
    EdgeSide.Right -> Res.string.edgefinder_edge_right
    EdgeSide.Near -> Res.string.edgefinder_edge_near
    EdgeSide.Far -> Res.string.edgefinder_edge_far
}

private fun statusText(status: EdgeStatus): StringResource = when (status) {
    EdgeStatus.Unknown -> Res.string.edgefinder_state_unknown
    EdgeStatus.Scanning -> Res.string.edgefinder_state_scanning
    EdgeStatus.Located -> Res.string.edgefinder_state_located
}

private fun statusIcon(status: EdgeStatus): DrawableResource = when (status) {
    EdgeStatus.Unknown -> Res.drawable.ic_radio_unchecked
    EdgeStatus.Scanning -> Res.drawable.ic_hourglass
    EdgeStatus.Located -> Res.drawable.ic_check
}

/** The minimum size of an edge button. */
private val EdgeButtonWidth: Dp = 160.dp
private val EdgeButtonHeight: Dp = 112.dp
