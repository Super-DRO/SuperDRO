package org.superdro.validation.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType
import org.superdro.core.ui.App
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.navigation.GlobalRoute
import org.superdro.core.ui.navigation.ReadoutRoute
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.tabTag
import org.superdro.core.ui.theme.Theme

/**
 * The checks and the screen routes of the screenshot validation tests. The tests are in more than one
 * class, so that the test task can run them on more than one fork.
 */
@OptIn(ExperimentalTestApi::class)
internal object ScreenshotChecks {

    fun androidx.compose.ui.graphics.Color.toArgbOpaque(): Int = hex().removePrefix("#").toInt(16) or 0xFF000000.toInt()

    private fun SemanticsNode.inScroll(): Boolean {
        var current: SemanticsNode? = parent
        while (current != null) {
            val config = current.config
            if (config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
                config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
            ) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun SemanticsNode.name(): String {
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString()
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        val tag = config.getOrNull(SemanticsProperties.TestTag)
        return listOfNotNull(tag, text, description).joinToString(" / ").ifEmpty { "node $id" }
    }

    /** Gives each control that a parent clips, that is outside the window, or whose text overflows. */
    fun SkikoComposeUiTest.clipped(width: Int, height: Int): List<String> {
        val problems = ArrayList<String>()
        onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            if (node.inScroll()) return@forEach
            val full = Rect(node.positionInRoot.x, node.positionInRoot.y, node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
            val shown = node.boundsInRoot
            when {
                full.width < 1f || full.height < 1f -> Unit
                full.left < -1f || full.top < -1f || full.right > width + 1f || full.bottom > height + 1f ->
                    problems += "${node.name()}: outside the window $full"
                shown.width < full.width - 1f || shown.height < full.height - 1f ->
                    problems += "${node.name()}: clipped to $shown of $full"
            }
        }
        onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            if (node.inScroll()) return@forEach
            val full = Rect(node.positionInRoot.x, node.positionInRoot.y, node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
            val shown = node.boundsInRoot
            if (full.width >= 1f && full.height >= 1f && (shown.width < full.width - 1f || shown.height < full.height - 1f)) {
                problems += "${node.name()}: the text is clipped to $shown of $full"
            }
            // A text with an ellipsis at the end of its last line does not fit.
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return@forEach
            val results = ArrayList<TextLayoutResult>()
            action.action?.invoke(results)
            if (results.any { result -> result.lineCount > 0 && result.isLineEllipsized(result.lineCount - 1) }) {
                problems += "${node.name()}: the text ends with an ellipsis"
            }
        }
        return problems
    }

    /** The routes of the screens that show with no pending data. */
    val routes: List<Pair<Any, String>> = listOf(
        ReadoutRoute, org.superdro.core.ui.navigation.MachineSettingsRoute, org.superdro.core.ui.navigation.KeypadRoute(AxisId.X),
        org.superdro.core.ui.navigation.AxisDetailsRoute(AxisId.X), org.superdro.core.ui.navigation.DivisionsRoute,
        org.superdro.core.ui.navigation.AdapterDetailsRoute, org.superdro.core.ui.navigation.HoleCircleRoute,
        org.superdro.core.ui.navigation.ArbitraryHoleCircleRoute(), org.superdro.core.ui.navigation.HoleGridRoute,
        org.superdro.core.ui.navigation.EquidistantArrayRoute, org.superdro.core.ui.navigation.ArcCutRoute,
        org.superdro.core.ui.navigation.RadiusCompensationRoute, org.superdro.core.ui.navigation.HeightCompensationRoute,
        org.superdro.core.ui.navigation.ZReferenceRoute, org.superdro.core.ui.navigation.AngleMeasureRoute,
        org.superdro.core.ui.navigation.DimensionMeasureRoute, org.superdro.core.ui.navigation.WorkspacesRoute,
        org.superdro.core.ui.navigation.ToolLibraryRoute, org.superdro.core.ui.navigation.ToolEditRoute(),
        org.superdro.core.ui.navigation.ShapeMenuRoute, org.superdro.core.ui.navigation.SubDatumEditRoute(),
        org.superdro.core.ui.navigation.GraphicalViewRoute, org.superdro.core.ui.navigation.AddGuideRoute,
        org.superdro.core.ui.navigation.ReferenceImageSetupRoute, org.superdro.core.ui.navigation.EdgeFinderRoute,
        org.superdro.core.ui.navigation.EncoderSettingsRoute('x'), org.superdro.core.ui.navigation.MapEditorRoute('x'),
        org.superdro.core.ui.navigation.ErrorMapWizardRoute('x'), org.superdro.core.ui.navigation.ProbeInputRoute,
        org.superdro.core.ui.navigation.CameraSetupRoute, org.superdro.core.ui.navigation.HeadTiltRoute,
        org.superdro.core.ui.navigation.RotatePartRoute, org.superdro.core.ui.navigation.AlignAxesRoute,
        org.superdro.core.ui.navigation.MachineDatumEditRoute(),
        org.superdro.core.ui.navigation.WorkspaceTransformRoute(org.superdro.core.ui.navigation.WorkspaceTransformKind.Rotate),
        org.superdro.core.ui.navigation.TagSubDatumsRoute, org.superdro.core.ui.navigation.MeasurementLogRoute,
        org.superdro.core.ui.navigation.ProfileFileRoute,
        org.superdro.core.ui.navigation.AddDeviceRoute, org.superdro.core.ui.navigation.AppSettingsRoute,
        org.superdro.core.ui.navigation.MachineProfilesRoute, org.superdro.core.ui.navigation.AdaptersRoute,
        org.superdro.core.ui.navigation.ProbeLibraryRoute, org.superdro.core.ui.navigation.ProbeProfileRoute(),
        org.superdro.core.ui.navigation.DataOutputRoute, org.superdro.core.ui.navigation.RememberedMachinesRoute,
        org.superdro.core.ui.navigation.AboutRoute, org.superdro.core.ui.navigation.LogsRoute,
        org.superdro.core.ui.navigation.KeyMappingRoute,
    ).map { route -> route to "mill" } + listOf(
        org.superdro.core.ui.navigation.KeypadRoute(AxisId.Tachometer), org.superdro.core.ui.navigation.TachometerPresetRoute,
        org.superdro.core.ui.navigation.LatheToolOffsetRoute, org.superdro.core.ui.navigation.ThreadSetupRoute,
        org.superdro.core.ui.navigation.CompoundRoute, org.superdro.core.ui.navigation.TachPulsesRoute,
    ).map { route -> route to "lathe" }

    fun screensClipped(width: Int, height: Int): List<String> = AppGraph().use { graph ->
        graph.connect("mill", "Mill") { profile -> profile.copy(caliper = profile.caliper.copy(enabled = true)) }
        graph.connect("lathe", "Lathe", MachineType.Lathe, scenario = "commands")
        val problems = ArrayList<String>()
        runWindowTest(width, height) {
            setContent { ProvideDI(graph.di) { App() } }
            waitUntil("tabs", 10_000L) {
                listOf("mill", "lathe").all { id -> onAllNodesWithTag(tabTag(DeviceId(id))).fetchSemanticsNodes().isNotEmpty() }
            }
            routes.forEach { (route, device) ->
                runOnUiThread {
                    when (route) {
                        is TabRoute -> if (route != ReadoutRoute) graph.holder.tab(DeviceId(device)).push(route)
                        is GlobalRoute -> graph.holder.push(route)
                    }
                }
                if (route is TabRoute) onNodeWithTag(tabTag(DeviceId(device))).performClick()
                waitForIdle()
                mainClock.advanceTimeBy(1_500L)
                waitForIdle()
                val name = route.toString().substringBefore('(').substringBefore('@')
                writePng(captureToImage(), "screen-${width}x$height-$name")
                problems += clipped(width, height).map { "$width x $height, $route: $it" }
                runOnUiThread {
                    graph.holder.clear()
                    graph.holder.tabIds.forEach { id -> graph.holder.tab(id).popToRoot() }
                }
                waitForIdle()
                mainClock.advanceTimeBy(1_000L)
            }
        }
        problems
    }

    /** One group of connected pixels of one color: its bounds and its number of pixels. */
    data class Blob(val left: Int, val top: Int, val right: Int, val bottom: Int, val count: Int) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1

        /** True for a text or a thin line: a narrow group, or a group that fills less than 45 % of its bounds. */
        val thin: Boolean get() = minOf(width, height) < 6 || count < width * height * 0.45
    }

    /** Gives the 4-connected groups of the pixels of [argb] in [image]. */
    fun blobs(image: androidx.compose.ui.graphics.ImageBitmap, argb: Int): List<Blob> {
        val all = pixels(image)
        val width = image.width
        val mask = BooleanArray(all.size) { index -> all[index] or 0xFF000000.toInt() == argb }
        val seen = BooleanArray(all.size)
        val result = ArrayList<Blob>()
        val stack = ArrayDeque<Int>()
        for (start in all.indices) {
            if (!mask[start] || seen[start]) continue
            var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = -1; var bottom = -1; var count = 0
            stack.addLast(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                val x = index % width
                val y = index / width
                count++
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                listOf(index - 1, index + 1, index - width, index + width).forEach { next ->
                    val inside = next in all.indices && (next / width == y || next % width == x)
                    if (inside && mask[next] && !seen[next]) {
                        seen[next] = true
                        stack.addLast(next)
                    }
                }
            }
            result += Blob(left, top, right, bottom, count)
        }
        return result
    }

    /**
     * Opens each screen of [routes] in `App()` in light mode (the system theme of the window is light)
     * and gives the groups of `primary` (#FF6D00) pixels that are a text or a thin line. A group in the
     * measurement mode chip (the defect of the ignored test below) is not in the result.
     */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun lightThinPrimary(width: Int, height: Int): Map<String, List<Blob>> = AppGraph().use { graph ->
        graph.connect("mill", "Mill") { profile -> profile.copy(caliper = profile.caliper.copy(enabled = true)) }
        graph.connect("lathe", "Lathe", MachineType.Lathe, scenario = "commands")
        val found = LinkedHashMap<String, List<Blob>>()
        val primary = Theme.lightScheme.primary.toArgbOpaque()
        runWindowTest(width, height) {
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.LocalSystemTheme provides androidx.compose.ui.SystemTheme.Light) {
                    ProvideDI(graph.di) { App() }
                }
            }
            waitUntil("tabs", 10_000L) {
                listOf("mill", "lathe").all { id -> onAllNodesWithTag(tabTag(DeviceId(id))).fetchSemanticsNodes().isNotEmpty() }
            }
            routes.forEach { (route, device) ->
                runOnUiThread {
                    when (route) {
                        is TabRoute -> if (route != ReadoutRoute) graph.holder.tab(DeviceId(device)).push(route)
                        is GlobalRoute -> graph.holder.push(route)
                    }
                }
                if (route is TabRoute) onNodeWithTag(tabTag(DeviceId(device))).performClick()
                waitForIdle()
                mainClock.advanceTimeBy(1_500L)
                waitForIdle()
                val name = route.toString().substringBefore('(').substringBefore('@')
                val image = captureToImage()
                writePng(image, "light-screen-${width}x$height-$name")
                val chips = onAllNodesWithTag(org.superdro.core.ui.graphics.measure.MEASUREMENT_MODE_TAG)
                    .fetchSemanticsNodes().map { node -> node.boundsInRoot }
                val thin = blobs(image, primary).filter { blob ->
                    blob.thin && chips.none { chip -> chip.contains(androidx.compose.ui.geometry.Offset(blob.left.toFloat(), blob.top.toFloat())) }
                }
                if (thin.isNotEmpty()) found["$name ($device)"] = thin
                runOnUiThread {
                    graph.holder.clear()
                    graph.holder.tabIds.forEach { id -> graph.holder.tab(id).popToRoot() }
                }
                waitForIdle()
                mainClock.advanceTimeBy(1_000L)
            }
        }
        found
    }
}
