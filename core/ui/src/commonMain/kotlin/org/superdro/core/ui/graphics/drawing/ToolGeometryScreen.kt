package org.superdro.core.ui.graphics.drawing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.drawing.tool.ToolGeometryCodec
import org.superdro.core.functions.drawing.ShapeGeometry
import org.superdro.core.resources.Res
import org.superdro.core.resources.drawing_tool_geometry_attach
import org.superdro.core.resources.drawing_tool_geometry_drawn_in
import org.superdro.core.resources.drawing_tool_geometry_import
import org.superdro.core.resources.drawing_tool_geometry_not_defined
import org.superdro.core.resources.drawing_tool_geometry_remove
import org.superdro.core.resources.drawing_tool_geometry_size
import org.superdro.core.resources.drawing_tool_geometry_title
import org.superdro.core.resources.drawing_tool_geometry_title_import
import org.superdro.core.resources.drawingui_cancel
import org.superdro.core.resources.drawingui_done
import org.superdro.core.resources.drawingui_problem_line
import org.superdro.core.resources.drawingui_thumbnail_description
import org.superdro.core.resources.drawingui_tool_checks_ok
import org.superdro.core.resources.drawingui_tool_not_found
import org.superdro.core.resources.drawingui_tool_size
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_warning
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.graphics.common.LengthTexts
import org.superdro.core.ui.graphics.common.unitText
import org.superdro.core.ui.graphics.render.ShapePaths
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor

/** The test tag of the import button of the cutter geometry screen. */
const val TOOL_GEOMETRY_IMPORT_TAG: String = "tool-geometry-import"

/** The test tag of the "Attach" button. */
const val TOOL_GEOMETRY_ATTACH_TAG: String = "tool-geometry-attach"

/** The test tag of the "Remove cutter geometry" button. */
const val TOOL_GEOMETRY_REMOVE_TAG: String = "tool-geometry-remove"

/** The test tag of the thumbnail. */
const val TOOL_GEOMETRY_THUMBNAIL_TAG: String = "tool-geometry-thumbnail"

/** The events of the cutter geometry screen. */
interface ToolGeometryEvents {
    fun onImport()
    fun onDrawnIn(inch: Boolean)
    fun onAttach()
    fun onCancelPreview()
    fun onRemove()
    fun onDone()
}

/** Gives the [ToolGeometryEvents] of [presenter]. */
fun ToolGeometryPresenter.events(): ToolGeometryEvents = object : ToolGeometryEvents {
    override fun onImport() = this@events.onImport()
    override fun onDrawnIn(inch: Boolean) = this@events.onDrawnIn(inch)
    override fun onAttach() = this@events.onAttach()
    override fun onCancelPreview() = this@events.onCancelPreview()
    override fun onRemove() = this@events.onRemove()
    override fun onDone() = this@events.onDone()
}

/**
 * The cutter geometry screen of a lathe tool: the
 * outline, "Not defined" with no geometry, the import, the checks of 22.10.3 and the size in the
 * preview, "Drawn in" mm or inch, and "Attach", "Cancel", "Remove cutter geometry", and "Done".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolGeometryScreen(state: ToolGeometryUiState, events: ToolGeometryEvents) {
    val title = stringResource(if (state.preview) Res.string.drawing_tool_geometry_title_import else Res.string.drawing_tool_geometry_title)
    val heading = state.tool?.name?.takeIf { name -> name.isNotBlank() }?.let { name -> "$title — $name" } ?: title
    val targets = LocalTouchTargets.current
    val landscape = LocalWindowLayout.current.landscape
    ScreenScaffold(title = heading, onBack = { if (state.preview) events.onCancelPreview() else events.onDone() }) {
        if (state.loaded && state.tool == null) {
            Text(stringResource(Res.string.drawingui_tool_not_found), modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
            return@ScreenScaffold
        }
        val body: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Details(state, events)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(targets.gap), verticalArrangement = Arrangement.spacedBy(targets.gap)) {
                    if (state.preview) {
                        TextButton(onClick = events::onCancelPreview, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = targets.button)) {
                            Text(stringResource(Res.string.drawingui_cancel))
                        }
                        FilledTonalButton(
                            onClick = events::onAttach,
                            enabled = !state.busy,
                            modifier = Modifier.heightIn(min = targets.button).testTag(TOOL_GEOMETRY_ATTACH_TAG),
                        ) { Text(stringResource(Res.string.drawing_tool_geometry_attach)) }
                    } else {
                        OutlinedButton(
                            onClick = events::onImport,
                            enabled = !state.busy,
                            colors = AccentButtons.outlined(),
                            modifier = Modifier.heightIn(min = targets.button).testTag(TOOL_GEOMETRY_IMPORT_TAG),
                        ) { Text(stringResource(Res.string.drawing_tool_geometry_import)) }
                        if (state.geometry != null) {
                            OutlinedButton(
                                onClick = events::onRemove,
                                enabled = !state.busy,
                                colors = AccentButtons.outlined(),
                                modifier = Modifier.heightIn(min = targets.button).testTag(TOOL_GEOMETRY_REMOVE_TAG),
                            ) { Text(stringResource(Res.string.drawing_tool_geometry_remove)) }
                        }
                        FilledTonalButton(onClick = events::onDone, modifier = Modifier.heightIn(min = targets.button)) {
                            Text(stringResource(Res.string.drawingui_done))
                        }
                    }
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Thumbnail(state, Modifier.weight(0.5f).fillMaxHeight().padding(24.dp))
                body(Modifier.weight(0.5f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Thumbnail(state, Modifier.weight(0.45f).fillMaxWidth().padding(24.dp))
                body(Modifier.weight(0.55f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Details(state: ToolGeometryUiState, events: ToolGeometryEvents) {
    val geometry = state.geometry
    if (geometry == null) {
        Text(stringResource(Res.string.drawing_tool_geometry_not_defined), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val report = state.report
        if (report != null) {
            val unit = unitText(state.metric)
            val width = "${LengthTexts.format(report.widthUm, state.metric, state.readoutFormat)} $unit"
            val height = "${LengthTexts.format(report.heightUm, state.metric, state.readoutFormat)} $unit"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.drawingui_tool_size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(Res.string.drawing_tool_geometry_size, width, height), style = MaterialTheme.typography.titleMedium)
            }
            if (state.preview) {
                if (report.checks.isEmpty()) {
                    StatusLine(ok = true, text = stringResource(Res.string.drawingui_tool_checks_ok))
                } else {
                    report.checks.forEach { check -> StatusLine(ok = false, text = ToolGeometryPresenter.checkText(check).resolve()) }
                }
            }
        }
        Text(stringResource(Res.string.drawing_tool_geometry_drawn_in), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val inch = geometry.drawnIn == ToolGeometryCodec.DRAWN_IN_INCH
        SingleChoiceSegmentedButtonRow {
            listOf(false, true).forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = inch == choice,
                    onClick = { events.onDrawnIn(choice) },
                    enabled = state.preview,
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(unitText(metric = !choice)) }
            }
        }
    }
    state.message?.let { message -> Text(message.resolve(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
    state.problems.take(MAX_SHOWN_PROBLEMS).forEach { problem ->
        Text(stringResource(Res.string.drawingui_problem_line, problem.code, problem.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(if (ok) Res.drawable.ic_check else Res.drawable.ic_warning),
            contentDescription = null,
            tint = if (ok) accentColor() else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The outline of the cutter, fitted to the box. A geometry with closed outlines and no open
 * parts shows as a filled shape (`primaryContainer` with an `onPrimaryContainer` line). Else it
 * shows as lines. A cross marks the origin: the cutting tip.
 */
@Composable
private fun Thumbnail(state: ToolGeometryUiState, modifier: Modifier) {
    val geometry = state.geometry
    val scheme = MaterialTheme.colorScheme
    val description = stringResource(Res.string.drawingui_thumbnail_description)
    val path = remember(geometry) { geometry?.let { item -> ShapePaths.modelPath(item.shapes, item.drawnIn) } }
    val report = state.report
    val filled = report != null && report.closedOutlines > 0 && report.openChains == 0
    val fill = remember(geometry, filled) { if (geometry != null && filled) OutlineLoops.fillPath(OutlineLoops.loops(geometry.shapes, geometry.drawnIn)) else null }
    Surface(
        color = scheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().testTag(TOOL_GEOMETRY_THUMBNAIL_TAG).semantics { contentDescription = description }) {
                if (geometry == null || path == null) return@Canvas
                val box = ShapeGeometry.extents(geometry.shapes) ?: return@Canvas
                val factor = geometry.drawnIn / 1000.0
                val view = PreviewViewport(widthPx = size.width.toDouble(), heightPx = size.height.toDouble()).fit(
                    minOf(0.0, box.minX * factor), minOf(0.0, box.minY * factor),
                    maxOf(0.0, box.maxX * factor), maxOf(0.0, box.maxY * factor),
                )
                val matrix = ShapePaths.composeMatrix(view.matrix())
                fill?.let { model -> drawPath(ShapePaths.screenPath(model, matrix).apply { fillType = PathFillType.EvenOdd }, scheme.primaryContainer) }
                drawPath(ShapePaths.screenPath(path, matrix), scheme.onPrimaryContainer, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val origin = view.toScreen(0.0, 0.0)
                val center = Offset(origin.x.toFloat(), origin.y.toFloat())
                val arm = 10.dp.toPx()
                drawLine(scheme.error, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth = 2.dp.toPx())
                drawLine(scheme.error, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

private const val MAX_SHOWN_PROBLEMS: Int = 8
