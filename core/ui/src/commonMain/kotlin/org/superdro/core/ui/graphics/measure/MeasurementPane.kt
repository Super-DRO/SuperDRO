package org.superdro.core.ui.graphics.measure

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.measurement.MeasurementVerb
import org.superdro.core.functions.measurement.ResultKey
import org.superdro.core.functions.measurement.SlotState
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_add
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_measure_capture
import org.superdro.core.resources.ic_measure_log
import org.superdro.core.resources.ic_measure_mode
import org.superdro.core.resources.ic_measure_reset
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.measure_help
import org.superdro.core.resources.measure_instrument_in_use
import org.superdro.core.resources.measure_log_button
import org.superdro.core.resources.measure_log_sequence
import org.superdro.core.resources.measure_log_short
import org.superdro.core.resources.measure_log_this
import org.superdro.core.resources.measure_logged
import org.superdro.core.resources.measure_mode
import org.superdro.core.resources.measure_note_hint
import org.superdro.core.resources.measure_pending
import org.superdro.core.resources.measure_reset
import org.superdro.core.resources.measure_slot_armed
import org.superdro.core.resources.measure_slot_blank
import org.superdro.core.resources.measure_slot_filled
import org.superdro.core.resources.measure_slot_number
import org.superdro.core.resources.measure_slot_suspect
import org.superdro.core.resources.measure_state_off
import org.superdro.core.resources.measure_state_on
import org.superdro.core.ui.components.InfoPanel
import org.superdro.core.ui.components.openState
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.Theme
import org.superdro.core.ui.theme.accentColor
import org.superdro.core.ui.theme.accentTextFieldColors
import org.superdro.core.ui.theme.accentFilterChipColors

/** The test tag of the measurement pane. */
const val MEASUREMENT_PANE_TAG: String = "measure-pane"

/** The test tag of the log button of the measurement pane. */
const val MEASUREMENT_LOG_TAG: String = "measure-log"

/** The test tag of the reset button of the measurement pane. */
const val MEASUREMENT_RESET_TAG: String = "measure-reset"

/** The test tag of the mode toggle. */
const val MEASUREMENT_MODE_TAG: String = "measure-mode"

/** The test tag of the log button of the top strip. */
const val MEASUREMENT_LOG_BUTTON_TAG: String = "measure-log-button"

/** The test tag of the help button of the open function. */
const val MEASUREMENT_HELP_BUTTON_TAG: String = "measure-help-button"

/** The test tag of the help text of the open function (in the pane, not in a dialog). */
const val MEASUREMENT_HELP_TAG: String = "measure-help"

/** Gives the test tag of the function button of [verb]. */
fun verbTag(verb: MeasurementVerb): String = "measure-verb-${verb.wire}"

/** Gives the test tag of the slot [index]. */
fun slotTag(index: Int): String = "measure-slot-$index"

/** Gives the test tag of the add button of the result [key]. */
fun addTag(key: ResultKey): String = "measure-add-${key.wire}"

/** Gives the test tag of the value of the result [key]. */
fun resultTag(key: ResultKey): String = "measure-result-${key.wire}"

/** The events of the measurement pane. [MeasurementController] gives them ([ControllerMeasurementEvents]). */
interface MeasurementEvents {
    fun onVerbTap(verb: MeasurementVerb)
    fun onVerbLongPress(verb: MeasurementVerb)
    fun onSlotTap(index: Int)
    fun onSlotLongPress(index: Int)
    fun onReset()
    fun onNoteChange(text: String)
    fun onHelp(open: Boolean)

    /** The log button. [title] is the title of the function in the app language. */
    fun onLog(title: String)

    /** The add button of [key]. [name] is the name of the new sub-datum in the app language. */
    fun onAdd(key: ResultKey, name: String)
}

/** The [MeasurementEvents] of [controller]. */
class ControllerMeasurementEvents(private val controller: MeasurementController) : MeasurementEvents {
    override fun onVerbTap(verb: MeasurementVerb) = controller.onVerbTap(verb)
    override fun onVerbLongPress(verb: MeasurementVerb) = controller.onVerbLongPress(verb)
    override fun onSlotTap(index: Int) = controller.onSlotTap(index)
    override fun onSlotLongPress(index: Int) = controller.onSlotLongPress(index)
    override fun onReset() = controller.onReset()
    override fun onNoteChange(text: String) = controller.onNoteChange(text)
    override fun onHelp(open: Boolean) = controller.onHelp(open)
    override fun onLog(title: String) = controller.onLog(title)
    override fun onAdd(key: ResultKey, name: String) = controller.onAdd(key, name)
}

/** The colors of the measurement points layer of the graphical view, from the Snapshot roles. */
object MeasurementColors {

    /** A collected point: `tertiary` (6.4 : 1 light, 6.5 : 1 dark). */
    @Composable
    fun point(): Color = MaterialTheme.colorScheme.tertiary

    /** A point with a note of the severity "suspect": `error`. */
    @Composable
    fun suspect(): Color = MaterialTheme.colorScheme.error

    /** The selected point in the measurement mode: `tertiary`. */
    @Composable
    fun selection(): Color = MaterialTheme.colorScheme.tertiary
}

/**
 * Gives the color scheme of the measurement mode: the primary and the tertiary roles
 * change places, so that the accent of the app is the tertiary role. The Snapshot color values do
 * not change. In light mode `tertiary` has 6.4 : 1 on `surface`, so [accentColor] can use it
 * for text.
 *
 * In light mode `primary` is not a color for a thin line (2.8 : 1). Thus the new
 * `tertiary` role (the selected point and the slider tool bar) gets `onPrimaryContainer` in light
 * mode (9.4 : 1 on `surface`). In dark mode it gets `primary` (5.8 : 1).
 */
fun measurementColorScheme(scheme: ColorScheme): ColorScheme = scheme.copy(
    primary = scheme.tertiary,
    onPrimary = scheme.onTertiary,
    primaryContainer = scheme.tertiaryContainer,
    onPrimaryContainer = scheme.onTertiaryContainer,
    tertiary = if (scheme.primary == Theme.lightScheme.primary) scheme.onPrimaryContainer else scheme.primary,
    onTertiary = scheme.onPrimary,
    tertiaryContainer = scheme.primaryContainer,
    onTertiaryContainer = scheme.onPrimaryContainer,
)

/** Gives [content] the accent of the measurement mode when [active] is true. */
@Composable
fun MeasurementTheme(active: Boolean, content: @Composable () -> Unit) {
    if (!active) {
        content()
        return
    }
    MaterialTheme(
        colorScheme = measurementColorScheme(MaterialTheme.colorScheme),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

/**
 * The mode switch of the measurement mode: a chip "Measurement mode" with an icon, a text,
 * and the state. The host puts it in the top strip of the graphical view. It does not show on a
 * lathe ([enabled] false hides nothing: the host does not show it there).
 */
@Composable
fun MeasurementModeToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val state = stringResource(if (on) Res.string.measure_state_on else Res.string.measure_state_off)
    FilterChip(
        selected = on,
        onClick = onToggle,
        enabled = enabled,
        label = { Text(stringResource(Res.string.measure_mode)) },
        leadingIcon = { Icon(painterResource(Res.drawable.ic_measure_mode), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        // The outline icon is a thin line, so it is not primary in light mode.
        // The chip is on the canvas of the graphical view (also over the camera video), so it has a
        // container color: the scene must not show through its text.
        colors = accentFilterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier
            .heightIn(min = LocalTouchTargets.current.button)
            .semantics { stateDescription = state }
            .testTag(MEASUREMENT_MODE_TAG),
    )
}

/**
 * The log button of the top strip: "Inspection Log" with an icon. It
 * has the alpha 0.4 when the log of the workspace has no entries. The host calls
 * [MeasurementController.onLogButton] on a tap.
 */
@Composable
fun MeasurementLogButton(count: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.measure_log_button)
    TextButton(
        onClick = onClick,
        colors = AccentButtons.text(),
        modifier = modifier
            .heightIn(min = LocalTouchTargets.current.button)
            .alpha(if (count > 0L) 1f else EMPTY_ALPHA)
            .testTag(MEASUREMENT_LOG_BUTTON_TAG),
    ) {
        Icon(painterResource(Res.drawable.ic_measure_log), contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/**
 * The measurement pane of the graphical view: the function
 * strip, and for the open function the title, the help, the slots, the message line, the results,
 * the note, and the log button. It is stateless: [state] comes from [MeasurementController].
 */
@Composable
fun MeasurementPane(state: MeasurementUiState, events: MeasurementEvents, modifier: Modifier = Modifier) {
    val targets = LocalTouchTargets.current
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.widthIn(max = PANE_MAX_WIDTH).testTag(MEASUREMENT_PANE_TAG),
        color = colors.surfaceContainerHigh.copy(alpha = PANE_ALPHA),
        contentColor = colors.onSurface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VerbStrip(state.verb, events)
            state.instrumentName?.let { name ->
                Text(
                    stringResource(Res.string.measure_instrument_in_use, name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            val verb = state.verb ?: return@Column
            val title = stringResource(MeasurementTexts.verbTitle(verb))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                val help = stringResource(Res.string.measure_help)
                IconButton(
                    onClick = { events.onHelp(!state.helpOpen) },
                    modifier = Modifier.size(targets.button).openState(state.helpOpen).testTag(MEASUREMENT_HELP_BUTTON_TAG),
                ) {
                    Icon(painterResource(Res.drawable.ic_info), contentDescription = help)
                }
            }
            // The dialog rule: the help text shows in the pane, below the title.
            // The help button closes it.
            if (state.helpOpen) {
                InfoPanel(color = colors.surfaceContainerHighest, tag = MEASUREMENT_HELP_TAG) {
                    Text(stringResource(MeasurementTexts.help(verb)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Slots(state, events)
            state.message?.let { message ->
                Text(message.resolve(), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            }
            HorizontalDivider(color = colors.outlineVariant)
            state.results.forEach { row -> ResultRow(row, events) }
            if (state.logVisible) LogRow(state, title, events)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerbStrip(active: MeasurementVerb?, events: MeasurementEvents) {
    val targets = LocalTouchTargets.current
    val verbs = if (active == null) MeasurementVerb.entries else listOf(active)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(targets.gap / 2), verticalArrangement = Arrangement.spacedBy(targets.gap / 2)) {
        verbs.forEach { verb ->
            val title = stringResource(MeasurementTexts.verbTitle(verb))
            val selected = verb == active
            val state = stringResource(if (selected) Res.string.measure_state_on else Res.string.measure_state_off)
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .heightIn(min = targets.button)
                    .clip(MaterialTheme.shapes.medium)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = { events.onVerbTap(verb) },
                        onLongClick = { events.onVerbLongPress(verb) },
                    )
                    .semantics {
                        contentDescription = title
                        stateDescription = state
                    }
                    .testTag(verbTag(verb)),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(MeasurementTexts.verbIcon(verb)), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Slots(state: MeasurementUiState, events: MeasurementEvents) {
    val targets = LocalTouchTargets.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        state.slots.forEachIndexed { position, slot ->
            if (position > 0) {
                val groupGap = state.slots[position - 1].group != slot.group
                Spacer(Modifier.width(if (groupGap) targets.gap * 2 else targets.gap / 2))
            }
            SlotButton(slot, events)
        }
        Spacer(Modifier.width(targets.gap))
        val reset = stringResource(Res.string.measure_reset)
        IconButton(
            onClick = events::onReset,
            enabled = state.canReset,
            modifier = Modifier.size(targets.button).alpha(if (state.canReset) 1f else DISABLED_ALPHA).testTag(MEASUREMENT_RESET_TAG),
        ) {
            Icon(painterResource(Res.drawable.ic_measure_reset), contentDescription = reset)
        }
    }
}

@Composable
private fun SlotButton(slot: SlotUiState, events: MeasurementEvents) {
    val colors = MaterialTheme.colorScheme
    val targets = LocalTouchTargets.current
    val (container, content) = when (slot.state) {
        SlotState.Armed -> colors.primaryContainer to colors.onPrimaryContainer
        SlotState.Filled -> colors.secondaryContainer to colors.onSecondaryContainer
        SlotState.FilledSuspect -> colors.errorContainer to colors.onErrorContainer
        SlotState.Blank -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    val description = stringResource(
        when (slot.state) {
            SlotState.Armed -> Res.string.measure_slot_armed
            SlotState.Filled -> Res.string.measure_slot_filled
            SlotState.FilledSuspect -> Res.string.measure_slot_suspect
            SlotState.Blank -> Res.string.measure_slot_blank
        },
    )
    val number = stringResource(Res.string.measure_slot_number, slot.index + 1)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        modifier = Modifier
            .size(targets.button)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                role = Role.Button,
                enabled = slot.state != SlotState.Blank,
                onClick = { events.onSlotTap(slot.index) },
                onLongClick = { events.onSlotLongPress(slot.index) },
            )
            .semantics {
                contentDescription = "$number: $description"
            }
            .testTag(slotTag(slot.index)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (slot.state) {
                SlotState.Armed -> Icon(painterResource(Res.drawable.ic_measure_capture), contentDescription = null)
                SlotState.Filled -> Icon(painterResource(Res.drawable.ic_check), contentDescription = null)
                SlotState.FilledSuspect -> Icon(painterResource(Res.drawable.ic_warning), contentDescription = null)
                SlotState.Blank -> Text("${slot.index + 1}", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ResultRow(row: ResultRowUiState, events: MeasurementEvents) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(MeasurementTexts.resultLabel(row.key)),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(LABEL_WIDTH),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val value = row.valueText
        Text(
            value ?: stringResource(Res.string.measure_pending),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = if (value == null) colors.onSurfaceVariant else accentColor(),
            modifier = Modifier.weight(1f).alpha(if (value == null) DISABLED_ALPHA else 1f).testTag(resultTag(row.key)),
        )
        val action = row.addAction ?: return@Row
        val label = stringResource(MeasurementTexts.addLabel(action))
        val name = MeasurementTexts.subDatumName(action)?.let { resource -> stringResource(resource) } ?: ""
        IconButton(
            onClick = { events.onAdd(row.key, name) },
            enabled = row.addEnabled,
            modifier = Modifier.size(LocalTouchTargets.current.button).alpha(if (row.addEnabled) 1f else DISABLED_ALPHA).testTag(addTag(row.key)),
        ) {
            Icon(painterResource(Res.drawable.ic_add), contentDescription = label)
        }
    }
}

@Composable
private fun LogRow(state: MeasurementUiState, title: String, events: MeasurementEvents) {
    val targets = LocalTouchTargets.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.note,
            onValueChange = events::onNoteChange,
            singleLine = true,
            label = { Text(stringResource(Res.string.measure_note_hint)) },
            colors = accentTextFieldColors(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        state.loggedSequence?.let { sequence ->
            Icon(painterResource(Res.drawable.ic_check), contentDescription = null, tint = accentColor())
            Text(stringResource(Res.string.measure_log_sequence, sequence), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }
        val description = stringResource(if (state.canLog) Res.string.measure_log_this else Res.string.measure_logged)
        FilledTonalButton(
            onClick = { events.onLog(title) },
            enabled = state.canLog,
            modifier = Modifier.heightIn(min = targets.button).semantics { contentDescription = description }.testTag(MEASUREMENT_LOG_TAG),
        ) {
            Icon(painterResource(Res.drawable.ic_measure_log), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.measure_log_short))
        }
    }
}

private val PANE_MAX_WIDTH = 520.dp
private val LABEL_WIDTH = 132.dp
private const val PANE_ALPHA = 0.94f
private const val DISABLED_ALPHA = 0.35f
private const val EMPTY_ALPHA = 0.4f
