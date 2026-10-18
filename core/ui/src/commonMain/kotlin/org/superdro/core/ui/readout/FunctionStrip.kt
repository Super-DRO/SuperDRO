package org.superdro.core.ui.readout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.readout.operations.GlobalModeState
import org.superdro.core.readout.operations.GlobalUnitsState
import org.superdro.core.resources.Res
import org.superdro.core.resources.readout_button_cd
import org.superdro.core.resources.strip_active
import org.superdro.core.resources.strip_mode
import org.superdro.core.resources.strip_units
import org.superdro.core.resources.strip_zero_set
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the function strip. */
const val FUNCTION_STRIP_TAG: String = "readout-function-strip"

/** The test tag of a function button. */
fun functionTag(button: FunctionButton): String = "readout-function-${button.name}"

/** The test tags of the global buttons. */
const val ZERO_SET_TAG: String = "readout-zero-set"
const val MODE_TAG: String = "readout-mode"
const val UNITS_TAG: String = "readout-units"

/** The form of the function strip. */
enum class StripForm {
    /** A vertical rail at the right edge (landscape). It scrolls when the buttons do not fit. */
    Rail,

    /** A grid of two rows (the bottom sheet of a compact window). It scrolls when the buttons do not fit. */
    TwoRows,

    /**
     * A grid below the readout (expanded layout). The width gives the number of columns: each
     * column is approximately the width of one button. It scrolls when the buttons do not fit.
     */
    Grid,
}

/** The width of a button of the strip. */
private val StripButtonWidth: Dp = 120.dp

/**
 * The function strip: the three global buttons Zero Set, Mode, and Units,
 * then the function buttons in the order of [functions] (`FunctionStripRules`).
 */
@Composable
fun FunctionStrip(
    global: GlobalButtonsUiState,
    functions: List<FunctionButtonUiState>,
    events: ReadoutEvents,
    form: StripForm,
    modifier: Modifier = Modifier,
) {
    val targets = LocalTouchTargets.current
    val gap = targets.gap
    val items = remember(functions) {
        StripItems(listOf(StripItem.ZeroSet, StripItem.Mode, StripItem.Units) + functions.map(StripItem::Function))
    }
    when (form) {
        StripForm.Rail -> Column(
            modifier = modifier.testTag(FUNCTION_STRIP_TAG).verticalScroll(rememberScrollState()).padding(gap / 2),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            items.entries.forEach { entry -> StripItemButton(entry, global, events, Modifier.fillMaxWidth()) }
        }
        StripForm.TwoRows -> Row(
            modifier = modifier.testTag(FUNCTION_STRIP_TAG).horizontalScroll(rememberScrollState()).padding(gap / 2),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            items.entries.chunked(2).forEach { pair ->
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    pair.forEach { entry -> StripItemButton(entry, global, events, Modifier.width(StripButtonWidth)) }
                }
            }
        }
        StripForm.Grid -> FunctionGrid(
            items = items,
            global = global,
            events = events,
            buttonWidth = StripButtonWidth * (targets.button / 64.dp),
            gap = gap,
            modifier = modifier,
        )
    }
}

@Composable
private fun FunctionGrid(
    items: StripItems,
    global: GlobalButtonsUiState,
    events: ReadoutEvents,
    buttonWidth: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            items.entries.forEach { entry -> StripItemButton(entry, global, events, Modifier) }
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag(FUNCTION_STRIP_TAG)
            .verticalScroll(rememberScrollState())
            .padding(gap / 2),
    ) { measurables, constraints ->
        val columns = gridColumns(constraints.maxWidth.toDp(), buttonWidth, gap)
        val gapPx = gap.roundToPx()
        val rawCellWidth = ((constraints.maxWidth - gapPx * (columns - 1)) / columns).coerceAtLeast(0)
        val minButtonWidthPx = buttonWidth.roundToPx().coerceAtMost(rawCellWidth)
        val stepPx = 16
        val cellWidth = if (rawCellWidth > stepPx) {
            ((rawCellWidth / stepPx) * stepPx).coerceAtLeast(minButtonWidthPx)
        } else {
            rawCellWidth
        }
        val rowCount = (measurables.size + columns - 1) / columns
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val rowHeights = IntArray(rowCount)
        var totalHeight = 0
        val widthConstraints = Constraints.fixedWidth(cellWidth)
        for (row in 0 until rowCount) {
            val start = row * columns
            val end = minOf(start + columns, measurables.size)
            var maxH = 0
            for (i in start until end) {
                val p = measurables[i].measure(widthConstraints)
                placeables[i] = p
                if (p.height > maxH) maxH = p.height
            }
            rowHeights[row] = maxH
            totalHeight += maxH
            if (row > 0) totalHeight += gapPx
        }
        layout(constraints.maxWidth, totalHeight) {
            var y = 0
            for (row in 0 until rowCount) {
                val start = row * columns
                val end = minOf(start + columns, measurables.size)
                var x = 0
                for (i in start until end) {
                    placeables[i]!!.placeRelative(x, y)
                    x += cellWidth + gapPx
                }
                y += rowHeights[row] + gapPx
            }
        }
    }
}

@Composable
private fun StripItemButton(
    entry: StripItem,
    global: GlobalButtonsUiState,
    events: ReadoutEvents,
    modifier: Modifier,
) = when (entry) {
    StripItem.ZeroSet -> ZeroSetButton(events, modifier)
    StripItem.Mode -> ModeButton(global.mode, events, modifier)
    StripItem.Units -> UnitsButton(global.units, events, modifier)
    is StripItem.Function -> FunctionButtonView(entry.state, events, modifier)
}

/** The smallest number of columns of [StripForm.Grid]. */
const val MIN_GRID_COLUMNS: Int = 2

/**
 * Gives the number of columns of [StripForm.Grid] for the inner [width]: the number of buttons
 * of [buttonWidth] with the [gap] between them that fit, and at least [MIN_GRID_COLUMNS].
 */
fun gridColumns(width: Dp, buttonWidth: Dp, gap: Dp): Int =
    maxOf(MIN_GRID_COLUMNS, ((width + gap) / (buttonWidth + gap)).toInt())

@Immutable
private data class StripItems(val entries: List<StripItem>)

/** One button of the strip. */
@Immutable
private sealed interface StripItem {
    data object ZeroSet : StripItem
    data object Mode : StripItem
    data object Units : StripItem
    data class Function(val state: FunctionButtonUiState) : StripItem
}

@Composable
private fun ZeroSetButton(events: ReadoutEvents, modifier: Modifier) {
    val label = stringResource(Res.string.strip_zero_set)
    DroButton(
        onClick = events::onZeroSetTap,
        onLongClick = events::onZeroSetLongPress,
        contentDescription = label,
        modifier = modifier.testTag(ZERO_SET_TAG),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ModeButton(mode: GlobalModeState, events: ReadoutEvents, modifier: Modifier) {
    val text = stringResource(ReadoutTexts.modeText(mode))
    val name = stringResource(Res.string.strip_mode)
    DroButton(
        onClick = events::onModeTap,
        onLongClick = events::onModeLongPress,
        contentDescription = stringResource(Res.string.readout_button_cd, name, text),
        modifier = modifier.testTag(MODE_TAG),
    ) {
        StateLabel(text, mixed = mode == GlobalModeState.Mixed)
    }
}

@Composable
private fun UnitsButton(units: GlobalUnitsState, events: ReadoutEvents, modifier: Modifier) {
    val text = stringResource(ReadoutTexts.unitsText(units))
    val name = stringResource(Res.string.strip_units)
    DroButton(
        onClick = events::onUnitsTap,
        contentDescription = stringResource(Res.string.readout_button_cd, name, text),
        modifier = modifier.testTag(UNITS_TAG),
    ) {
        StateLabel(text, mixed = units == GlobalUnitsState.Mixed)
    }
}

/** The text of a global button, with the icon of the mixed state. */
@Composable
private fun StateLabel(text: String, mixed: Boolean) {
    ReadoutTexts.mixedIcon(mixed)?.let { icon ->
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
    }
    Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1)
}

@Composable
private fun FunctionButtonView(button: FunctionButtonUiState, events: ReadoutEvents, modifier: Modifier) {
    val label = stringResource(ReadoutTexts.functionLabel(button))
    val activeText = stringResource(Res.string.strip_active)
    DroButton(
        onClick = { events.onFunctionTap(button.button) },
        onLongClick = { events.onFunctionLongPress(button.button) },
        contentDescription = label,
        stateDescription = if (button.active) activeText else null,
        style = when {
            button.active -> DroButtonStyle.Active
            else -> DroButtonStyle.Neutral
        },
        modifier = modifier.testTag(functionTag(button.button)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2)
            if (button.active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(ReadoutTexts.activeIcon), contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(activeText, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
