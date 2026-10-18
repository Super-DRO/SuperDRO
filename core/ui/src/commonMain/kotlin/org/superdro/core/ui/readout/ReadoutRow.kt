package org.superdro.core.ui.readout

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.readout.DisplayUnit
import org.superdro.core.readout.ReadoutPatterns
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_lock
import org.superdro.core.resources.readout_auxiliary
import org.superdro.core.resources.readout_button_abs
import org.superdro.core.resources.readout_button_cd
import org.superdro.core.resources.readout_button_divisions
import org.superdro.core.resources.readout_button_half
import org.superdro.core.resources.readout_button_hold
import org.superdro.core.resources.readout_button_hold_set
import org.superdro.core.resources.readout_button_inc
import org.superdro.core.resources.readout_button_surface_speed
import org.superdro.core.resources.readout_button_units
import org.superdro.core.resources.readout_button_zero
import org.superdro.core.resources.readout_label_semantics
import org.superdro.core.resources.readout_state_semantics
import org.superdro.core.resources.readout_unit_degrees
import org.superdro.core.resources.readout_unit_divisions
import org.superdro.core.resources.readout_value_semantics
import org.superdro.core.resources.readout_value_state_semantics
import org.superdro.core.ui.presenter.collectLiveState
import org.superdro.core.ui.theme.LocalReadoutTypography
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the row of [axis]. */
fun rowTag(axis: AxisId): String = "readout-row-${axis.name}"

/** The test tag of the value of [axis]. */
fun valueTag(axis: AxisId): String = "readout-value-${axis.name}"

/** The test tag of an action button. */
fun actionTag(axis: AxisId, mode: AxisButtonMode): String = "readout-action-${axis.name}-${mode.name}"

/** The height of the position slider below a linear value. */
private val SliderHeight: Dp = 32.dp

/** The width of the unit column at the right of the digits. */
private val UnitWidth: Dp = 64.dp

/** The smallest height of the position slider in a short row. */
private val MinSliderHeight: Dp = 12.dp

/** The part of the row height that the slider uses, up to [SliderHeight]. */
private const val SLIDER_FRACTION: Float = 0.2f

/** The height of the line of the display state above the digits. */
private val StateLineHeight: Dp = 24.dp

/** The smallest height of the state line in a short row. */
private val MinStateLineHeight: Dp = 14.dp

/** The part of the row height that the state line uses, up to [StateLineHeight]. */
private const val STATE_LINE_FRACTION: Float = 0.16f

/**
 * The fitted text sizes of the rows, in px. All rows use the smallest size, so the digits of each
 * row have one size. [ReadoutRows] gives the map.
 */
private val LocalRowTextSizes = staticCompositionLocalOf<MutableMap<AxisId, Float>?> { null }

@Immutable
private data class ReadoutRowItems(val entries: List<ReadoutRowUiState>)

private fun rowRightButtonSlots(row: ReadoutRowUiState, collapsed: Boolean): Int = when {
    row.kind == RowKind.Tachometer -> 1
    collapsed -> row.collapsedButtons.size
    else -> row.buttons.size
}

/**
 * The readout rows. Each row gets 1 / max(3, count) of the height of the pane.
 * Each row collects only the value flow of its own axis.
 *
 * @param collapsed True when the side panel is open next to the readout: the buttons with
 *   `show_collapsed` show.
 */
@Composable
fun ReadoutRows(
    rows: List<ReadoutRowUiState>,
    axisValue: (AxisId) -> StateFlow<AxisValueUiState>,
    collapsed: Boolean,
    format: ReadoutFormat,
    events: ReadoutEvents,
    modifier: Modifier = Modifier,
) {
    val sizes = remember { HashMap<AxisId, Float>() }
    val rowItems = remember(rows) { ReadoutRowItems(rows) }
    val uniformRows = remember(rows, collapsed) {
        rows.size <= 1 || rows.map { rowRightButtonSlots(it, collapsed) to it.auxiliary }.distinct().size <= 1
    }
    val minRow = LocalTouchTargets.current.button + 8.dp
    val density = LocalDensity.current
    val minRowPx = with(density) { minRow.roundToPx() }
    val slots = ReadoutGeometry.slots(rows.size)
    var scrollable by remember { mutableStateOf(false) }
    val sizeModifier = modifier.fillMaxSize().onSizeChanged { size ->
        val next = size.height > 0 && size.height / slots < minRowPx
        if (scrollable != next) {
            scrollable = next
        }
    }
    CompositionLocalProvider(LocalRowTextSizes provides if (uniformRows) null else sizes) {
        if (!scrollable) {
            Layout(
                content = {
                    rowItems.entries.forEach { row ->
                        key(row.axis) {
                            ReadoutRowPlaced(
                                row = row,
                                value = axisValue(row.axis),
                                collapsed = collapsed,
                                format = format,
                                events = events,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                },
                modifier = sizeModifier,
            ) { measurables, constraints ->
                if (constraints.maxHeight > 0 && constraints.maxHeight / slots < minRowPx && !scrollable) {
                    scrollable = true
                }
                val rowHeightPx = (constraints.maxHeight / slots).coerceAtLeast(0)
                val rowConstraints = Constraints.fixed(constraints.maxWidth, rowHeightPx)
                val placeables = measurables.map { it.measure(rowConstraints) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeables.forEachIndexed { index, placeable ->
                        placeable.placeRelative(0, index * rowHeightPx)
                    }
                }
            }
        } else {
            // A window that is too short for the touch targets: the rows keep the
            // minimum height and scroll.
            Column(sizeModifier.verticalScroll(rememberScrollState())) {
                rowItems.entries.forEach { row ->
                    key(row.axis) {
                        ReadoutRowPlaced(
                            row = row,
                            value = axisValue(row.axis),
                            collapsed = collapsed,
                            format = format,
                            events = events,
                            modifier = Modifier.height(minRow).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadoutRowPlaced(
    row: ReadoutRowUiState,
    value: StateFlow<AxisValueUiState>,
    collapsed: Boolean,
    format: ReadoutFormat,
    events: ReadoutEvents,
    modifier: Modifier,
) {
    val current by value.collectLiveState()
    val baseGap = LocalTouchTargets.current.gap
    ReadoutRowContent(row, current, collapsed, baseGap, format, events, modifier.testTag(rowTag(row.axis)))
}

/** One readout row: the label, the value, the unit, the slider, and the action buttons. */
@Composable
fun ReadoutRow(
    row: ReadoutRowUiState,
    value: StateFlow<AxisValueUiState>,
    collapsed: Boolean,
    format: ReadoutFormat,
    events: ReadoutEvents,
    modifier: Modifier = Modifier,
) {
    // The delegate reads the value only in the content of the box: a new value does not
    // recompose this function.
    val current by value.collectLiveState()
    val baseGap = LocalTouchTargets.current.gap
    ReadoutRowContent(row, current, collapsed, baseGap, format, events, modifier.testTag(rowTag(row.axis)))
}

@Composable
private fun ReadoutRowContent(
    row: ReadoutRowUiState,
    current: AxisValueUiState,
    collapsed: Boolean,
    baseGap: Dp,
    format: ReadoutFormat,
    events: ReadoutEvents,
    modifier: Modifier = Modifier,
) {
    val hasSlider = row.kind == RowKind.Linear && row.slider
    val buttons = if (collapsed) row.collapsedButtons else row.buttons
    Layout(
        content = {
            AxisLabel(row, onClick = { events.onLabelTap(row.axis) })
            ValueBox(
                label = row.label,
                value = current,
                format = format,
                onClick = { events.onValueTap(row.axis) },
                dynamicStateLine = true,
            )
            if (hasSlider) {
                PositionIndicator(
                    positionUm = current.positionUm,
                    toolSizeUm = current.toolSizeUm,
                    toolOffsetUm = current.toolOffsetUm,
                    metric = current.metric,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (row.kind == RowKind.Tachometer) {
                TachometerButton(row.label, surfaceSpeed = current.surfaceSpeed, metric = current.metric, events = events)
            } else {
                buttons.forEach { mode ->
                    ActionButton(
                        row = row,
                        mode = mode,
                        incremental = current.incremental,
                        metric = current.metric,
                        divisions = current.divisions,
                        held = current.held,
                        events = events,
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val rowWidth = constraints.maxWidth
        val rowHeight = constraints.maxHeight
        val compactRow = rowWidth < 420.dp.roundToPx()
        val gapPx = (if (compactRow) baseGap / 2 else baseGap).roundToPx()
        val hPadPx = (if (compactRow) 4.dp else 8.dp).roundToPx()
        val vPadPx = 4.dp.roundToPx()
        val innerHeight = (rowHeight - vPadPx * 2).coerceAtLeast(0)
        val sideConstraints = Constraints(minWidth = 0, maxWidth = 4096, minHeight = 0, maxHeight = innerHeight)

        val labelPlaceable = measurables[0].measure(sideConstraints)
        val buttonStartIndex = if (hasSlider) 3 else 2
        val buttonCount = measurables.size - buttonStartIndex
        val buttonPlaceables = if (buttonCount > 0) {
            Array(buttonCount) { idx -> measurables[buttonStartIndex + idx].measure(sideConstraints) }
        } else {
            emptyArray()
        }
        var buttonsTotalWidth = 0
        for (i in buttonPlaceables.indices) {
            buttonsTotalWidth += buttonPlaceables[i].width
            if (i > 0) buttonsTotalWidth += gapPx
        }
        val rightSectionWidth = if (buttonPlaceables.isNotEmpty()) gapPx + buttonsTotalWidth else 0
        val centerWidth = (rowWidth - hPadPx * 2 - labelPlaceable.width - gapPx - rightSectionWidth).coerceAtLeast(0)

        val rowHeightDp = rowHeight.toDp()
        val sliderHeightPx = if (hasSlider) {
            (rowHeightDp * SLIDER_FRACTION).coerceIn(MinSliderHeight, SliderHeight).roundToPx().coerceAtMost(innerHeight)
        } else {
            0
        }
        val valueBoxHeightPx = (innerHeight - sliderHeightPx).coerceAtLeast(0)
        val valueBoxPlaceable = measurables[1].measure(Constraints.fixed(centerWidth, valueBoxHeightPx))
        val sliderPlaceable = if (hasSlider) {
            val sliderPadEndPx = if (compactRow) 0 else UnitWidth.roundToPx()
            val sliderWidthPx = (centerWidth - sliderPadEndPx).coerceAtLeast(0)
            measurables[2].measure(Constraints.fixed(sliderWidthPx, sliderHeightPx))
        } else {
            null
        }

        layout(rowWidth, rowHeight) {
            val labelY = vPadPx + (innerHeight - labelPlaceable.height) / 2
            labelPlaceable.placeRelative(hPadPx, labelY)

            val centerX = hPadPx + labelPlaceable.width + gapPx
            valueBoxPlaceable.placeRelative(centerX, vPadPx)
            sliderPlaceable?.placeRelative(centerX, vPadPx + valueBoxHeightPx)

            var buttonX = centerX + centerWidth + gapPx
            for (buttonPlaceable in buttonPlaceables) {
                val buttonY = vPadPx + (innerHeight - buttonPlaceable.height) / 2
                buttonPlaceable.placeRelative(buttonX, buttonY)
                buttonX += buttonPlaceable.width + gapPx
            }
        }
    }
}

@Composable
private fun AxisLabel(row: ReadoutRowUiState, onClick: () -> Unit) {
    val description = stringResource(Res.string.readout_label_semantics, row.label)
    DroButton(
        onClick = onClick,
        style = DroButtonStyle.Neutral,
        contentDescription = description,
        minWidth = LocalTouchTargets.current.button + 8.dp,
        modifier = Modifier.fillMaxHeight(0.9f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                row.label,
                style = if (row.auxiliary) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                maxLines = 1,
            )
            if (row.auxiliary) {
                Text(
                    stringResource(Res.string.readout_auxiliary),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.width(64.dp),
                )
            }
        }
    }
}

/**
 * The value of an axis: a fixed sign column, the digits with a fixed decimal position (tabular
 * figures of the readout font), and the unit. A tap opens the keypad and changes no value.
 * The size of the digits follows the digit size rule. The digits change at once: only the
 * container of a value that is not live pulses. The line above the digits shows the
 * display state with an icon and a text.
 */
@Composable
fun ValueBox(
    label: String,
    value: AxisValueUiState,
    format: ReadoutFormat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stateLineHeight: Dp = StateLineHeight,
    dynamicStateLine: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val color = when (value.state) {
        ValueState.Normal, ValueState.Pending -> colors.onSurface
        ValueState.Modified -> colors.tertiary
        ValueState.Error -> colors.error
        ValueState.NoInput, ValueState.Held, ValueState.Offline -> colors.onSurfaceVariant
    }
    val stateText = ReadoutTexts.stateText(value.state)?.let { text -> stringResource(text) }
    val unit = stringResource(ReadoutTexts.unitSymbol(value.unit))
    val unitName = stringResource(ReadoutTexts.unitName(value.unit))
    // The templates have no live argument, so their resources load one time. The semantics
    // block puts the live value into them only when the semantics need the text.
    val templates = ValueSemanticsTemplates(
        value = stringResource(Res.string.readout_value_semantics),
        state = stringResource(Res.string.readout_state_semantics),
        valueState = stringResource(Res.string.readout_value_state_semantics),
    )
    val text = value.text
    val state = value.state
    // Only the draw phase reads the pulse, so the pulse does not recompose the box.
    val pulse = if (state == ValueState.Pending) rememberPulseAlpha() else null
    val vTag = valueTag(value.axis)
    val boxModifier = modifier
        .hintAnchor(vTag)
        .testTag(vTag)
        .clickable(onClick = onClick)
        .clearAndSetSemantics {
            contentDescription = ValueSemantics.description(templates, label, state, text, unitName, stateText)
            role = Role.Button
            onClick {
                onClick()
                true
            }
        }
        .graphicsLayer { alpha = pulse?.value ?: 1f }

    val showDigits = state != ValueState.Error && state != ValueState.NoInput
    if (!showDigits) {
        Column(boxModifier.fillMaxSize()) {
            Spacer(Modifier.fillMaxWidth().height(stateLineHeight))
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReadoutTexts.stateIcon(state)?.let { icon ->
                    Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stateText.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }

    val density = LocalDensity.current
    val style = LocalReadoutTypography.current.digits
    val measurer = rememberTextMeasurer()
    val template = remember(format) { ReadoutPatterns.linear(format, metric = true).format(TEMPLATE_VALUE) }
    val referencePx = 100f
    val referenceFontSize = with(density) { referencePx.toSp() }
    val reference = remember(style, referenceFontSize) { style.copy(fontSize = referenceFontSize) }
    val digitsStyle = remember(reference, color) { reference.copy(lineHeight = referenceFontSize, color = color) }
    val templateWidth = remember(reference, template) { measurer.measure(template, reference).size.width.toFloat() }
    val signWidth = remember(reference) { measurer.measure("-", reference).size.width.toFloat() }
    val signDp = with(density) { signWidth.toDp() }
    val digitsDp = with(density) { (templateWidth - signWidth).toDp() }
    val sizes = LocalRowTextSizes.current
    if (sizes != null) {
        DisposableEffect(value.axis) { onDispose { sizes.remove(value.axis) } }
    }

    Layout(
        content = {
            // Child 0: Reference-sized sign and digits row. Measured with fixed unbounded constraints
            // and scaled via placeWithLayer so resize gestures never re-layout or re-rasterize glyphs.
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.width(signDp), contentAlignment = Alignment.BottomEnd) {
                    if (value.negative) {
                        Text(
                            "-",
                            style = digitsStyle,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
                Box(Modifier.widthIn(min = digitsDp), contentAlignment = Alignment.BottomEnd) {
                    Text(
                        value.digits,
                        style = digitsStyle,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.End,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
            // Child 1: Side unit (when width >= 260.dp).
            Text(
                unit,
                style = LocalReadoutTypography.current.unit,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(UnitWidth).padding(start = 8.dp),
            )
            // Child 2: Compact top-right unit (when width < 260.dp).
            Text(
                unit,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(end = 4.dp),
            )
            // Child 3 (optional): StateChip.
            if (stateText != null) {
                StateChip(state, stateText, color, stateLineHeight, Modifier)
            }
        },
        modifier = boxModifier.fillMaxSize(),
    ) { measurables, constraints ->
        val boxWidth = constraints.maxWidth
        val boxHeight = constraints.maxHeight
        val compactUnit = boxWidth < 260.dp.roundToPx()
        val stateLineHeightPx = if (dynamicStateLine) {
            val estimatedRowHeightDp = boxHeight.toDp() / (1f - SLIDER_FRACTION)
            (estimatedRowHeightDp * STATE_LINE_FRACTION).coerceIn(MinStateLineHeight, StateLineHeight).roundToPx().coerceAtMost(boxHeight)
        } else {
            stateLineHeight.roundToPx().coerceAtMost(boxHeight)
        }
        val digitsHeightPx = (boxHeight - stateLineHeightPx).coerceAtLeast(0).toFloat()
        val unitWidthPx = UnitWidth.toPx()
        val unitPx = if (!compactUnit) unitWidthPx else 0f
        val heightPx = digitsHeightPx * DIGIT_HEIGHT_FRACTION
        val widthPx = (boxWidth.toFloat() - unitPx).coerceAtLeast(0f)
        val fitted = ReadoutGeometry.textSize(heightPx, widthPx, templateWidth * heightPx / referencePx)
        if (sizes != null) {
            sizes[value.axis] = fitted
        }

        val unbounded = Constraints()
        val digitsPlaceable = if (fitted >= 1f) measurables[0].measure(unbounded) else null
        val sideUnitPlaceable = if (!compactUnit && fitted >= 1f) measurables[1].measure(unbounded) else null
        val compactUnitPlaceable = if (compactUnit) measurables[2].measure(unbounded) else null
        val stateChipPlaceable = if (measurables.size > 3) {
            measurables[3].measure(Constraints(maxWidth = 4096, maxHeight = stateLineHeightPx))
        } else {
            null
        }

        layout(boxWidth, boxHeight) {
            if (stateChipPlaceable != null) {
                val chipY = ((stateLineHeightPx - stateChipPlaceable.height) / 2).coerceAtLeast(0)
                stateChipPlaceable.placeRelative(0, chipY)
            }
            if (compactUnitPlaceable != null) {
                val unitX = (boxWidth - compactUnitPlaceable.width).coerceAtLeast(0)
                val unitY = ((stateLineHeightPx - compactUnitPlaceable.height) / 2).coerceAtLeast(0)
                compactUnitPlaceable.placeRelative(unitX, unitY)
            }
            val sizePx = minOf(fitted, sizes?.values?.minOrNull() ?: fitted)
            if (digitsPlaceable != null && sizePx >= 1f) {
                val scale = sizePx / referencePx
                val scaledW = digitsPlaceable.width * scale
                val scaledH = digitsPlaceable.height * scale
                val sideUnitW = sideUnitPlaceable?.width ?: 0
                val availW = (boxWidth - sideUnitW).coerceAtLeast(0)
                val rowH = if (sideUnitPlaceable != null) maxOf(scaledH, sideUnitPlaceable.height.toFloat()) else scaledH
                val rowBottom = stateLineHeightPx + ((digitsHeightPx + rowH) / 2f).roundToInt()
                val digitsX = (availW - scaledW).roundToInt()
                val digitsY = (rowBottom - scaledH).roundToInt()
                digitsPlaceable.placeWithLayer(digitsX, digitsY) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                }
                if (sideUnitPlaceable != null) {
                    sideUnitPlaceable.placeRelative(availW, rowBottom - sideUnitPlaceable.height)
                }
            }
        }
    }
}

@Composable
private fun StateChip(state: ValueState, text: String, color: Color, height: Dp, modifier: Modifier) {
    val small = height < StateLineHeight
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ReadoutTexts.stateIcon(state)?.let { icon ->
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(if (small) 12.dp else 16.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(text, style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** The alpha of the pulse of a value that is not live. Read it only in the draw phase. */
@Composable
private fun rememberPulseAlpha(): State<Float> {
    val transition = rememberInfiniteTransition()
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = PULSE_MIN_ALPHA,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
    )
}

/**
 * One action button of an axis with its mode. The button gets only the flags of the value that
 * it shows, so that it does not recompose for a new position.
 */
@Composable
fun ActionButton(
    row: ReadoutRowUiState,
    mode: AxisButtonMode,
    incremental: Boolean,
    metric: Boolean,
    divisions: Boolean,
    held: Boolean,
    events: ReadoutEvents,
) {
    val angular = row.kind == RowKind.Angular
    val text: String
    val name: String
    var active = false
    when (mode) {
        AxisButtonMode.SetZero -> {
            text = "0"
            name = stringResource(Res.string.readout_button_zero)
        }
        AxisButtonMode.ToggleIncremental -> {
            text = stringResource(if (incremental) Res.string.readout_button_inc else Res.string.readout_button_abs)
            name = text
            active = incremental
        }
        AxisButtonMode.ToggleUnits -> {
            text = when {
                angular -> stringResource(if (divisions) Res.string.readout_unit_divisions else Res.string.readout_unit_degrees)
                else -> stringResource(ReadoutTexts.unitSymbol(if (metric) DisplayUnit.Millimeter else DisplayUnit.Inch))
            }
            name = "${stringResource(Res.string.readout_button_units)} $text"
        }
        AxisButtonMode.SetCenter -> {
            text = "½"
            name = stringResource(Res.string.readout_button_half)
        }
        AxisButtonMode.DivideCircle -> {
            text = "÷"
            name = stringResource(Res.string.readout_button_divisions)
        }
        AxisButtonMode.Hold -> {
            text = stringResource(Res.string.readout_button_hold)
            name = stringResource(if (held) Res.string.readout_button_hold_set else Res.string.readout_button_hold)
            active = held
        }
    }
    val longPress: (() -> Unit)? = when (mode) {
        AxisButtonMode.SetZero, AxisButtonMode.ToggleIncremental, AxisButtonMode.Hold -> { -> events.onActionLongPress(row.axis, mode) }
        else -> null
    }
    val tag = actionTag(row.axis, mode)
    DroButton(
        onClick = { events.onActionTap(row.axis, mode) },
        onLongClick = longPress,
        contentDescription = stringResource(Res.string.readout_button_cd, row.label, name),
        style = if (active) DroButtonStyle.Active else DroButtonStyle.Primary,
        modifier = Modifier.hintAnchor(tag).testTag(tag),
    ) {
        if (mode == AxisButtonMode.Hold) {
            Icon(painterResource(Res.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.titleLarge, maxLines = 1)
    }
}

/** The RPM or SFM button of the tachometer row. */
@Composable
private fun TachometerButton(label: String, surfaceSpeed: Boolean, metric: Boolean, events: ReadoutEvents) {
    val other = if (surfaceSpeed) DisplayUnit.Rpm else if (metric) DisplayUnit.MetersPerMinute else DisplayUnit.SurfaceFeetPerMinute
    DroButton(
        onClick = events::onTachometerToggle,
        contentDescription = stringResource(Res.string.readout_button_cd, label, stringResource(Res.string.readout_button_surface_speed)),
        style = if (surfaceSpeed) DroButtonStyle.Active else DroButtonStyle.Primary,
        modifier = Modifier.testTag("readout-tachometer-toggle"),
    ) {
        Text(stringResource(ReadoutTexts.unitSymbol(other)), style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

/** The value of the template text of the size rule: the mm text of −9000. */
private const val TEMPLATE_VALUE: Double = -9000.0

/** The part of the value box height that the digits use. The rest is the margin of the rule. */
private const val DIGIT_HEIGHT_FRACTION: Float = 0.8f

private const val PULSE_MS: Int = 1_100
private const val PULSE_MIN_ALPHA: Float = 0.72f
