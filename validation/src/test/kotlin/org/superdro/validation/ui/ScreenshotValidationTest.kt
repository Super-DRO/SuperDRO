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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType
import org.superdro.core.model.SidePanelMode
import org.superdro.core.ui.App
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.navigation.GlobalRoute
import org.superdro.core.ui.navigation.ReadoutRoute
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.readout.valueTag
import org.superdro.core.ui.tabTag
import org.superdro.core.ui.theme.Theme

/**
 * The screenshot tests in light and dark mode at the widths 600 dp, 840 dp, and 1200 dp, landscape
 * and portrait. The app operates on a tablet in landscape and in portrait: the main screen
 * and each other screen show with no clipped control at 1280 × 800 dp and 800 × 1280 dp. The PNG
 * files go to `validation/build/ui-screenshots`.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotValidationTest {

    /** The window sizes: each width in portrait and in landscape (16 : 10). */
    private val sizes: List<Pair<Int, Int>> = listOf(600, 840, 1200).flatMap { width ->
        listOf(width to width * 16 / 10, width to width * 10 / 16)
    }

    @Test
    fun screenshots_of_the_main_screen_in_light_and_dark_mode() {
        val files = ArrayList<String>()
        listOf(false, true).forEach { dark ->
            (sizes + listOf(1280 to 800, 800 to 1280)).forEach { (width, height) ->
                runWindowTest(width, height) {
                    setContent { SampleMainScreen(MainScreenSamples.mill(), dark = dark) }
                    waitForIdle()
                    val name = "main-${if (dark) "dark" else "light"}-${width}x$height"
                    files += writePng(captureToImage(), name).name
                }
            }
            // The side panel in the supporting pane (landscape) and in its bottom sheet (portrait).
            listOf(1280 to 800, 800 to 1280).forEach { (width, height) ->
                runWindowTest(width, height) {
                    setContent { SampleMainScreen(MainScreenSamples.mill(sidePanel = SidePanelMode.SubDatums, reminder = true), dark = dark) }
                    waitForIdle()
                    mainClock.advanceTimeBy(1_000L)
                    files += writePng(captureToImage(), "main-panel-${if (dark) "dark" else "light"}-${width}x$height").name
                }
            }
        }
        assertEquals(2 * (sizes.size + 4), files.size)
    }

    @Test
    fun readout_digits_are_onSurface_on_surface_in_the_two_modes() {
        listOf(false, true).forEach { dark ->
            runWindowTest(1280, 800) {
                setContent { SampleMainScreen(MainScreenSamples.mill(), dark = dark) }
                waitForIdle()
                val scheme = if (dark) Theme.darkScheme else Theme.lightScheme
                val box = onNodeWithTag(valueTag(AxisId.X)).rect()
                val image = captureToImage()
                val all = pixels(image)
                val surface = scheme.surface.hex()
                val onSurface = scheme.onSurface.hex()
                var surfaceCount = 0
                var digitCount = 0
                for (y in (box.top + 30f).toInt() until box.bottom.toInt()) {
                    for (x in box.left.toInt() until (box.right - 72f).toInt()) {
                        when ("#%06X".format(all[y * image.width + x] and 0xFFFFFF)) {
                            surface -> surfaceCount++
                            onSurface -> digitCount++
                        }
                    }
                }
                assertTrue(surfaceCount > 1000, "the value box has the surface color $surface (dark $dark)")
                assertTrue(digitCount > 500, "the digits have the onSurface color $onSurface: $digitCount pixels (dark $dark)")
            }
        }
    }

    @Test
    fun light_main_screen_has_no_primary_text_or_line() {
        listOf(1280 to 800, 800 to 1280).forEach { (width, height) ->
            runWindowTest(width, height) {
                setContent { SampleMainScreen(MainScreenSamples.mill(reminder = true), dark = false) }
                waitForIdle()
                val primary = Theme.lightScheme.primary.toArgbOpaque()
                val count = pixels(captureToImage()).count { pixel -> pixel or 0xFF000000.toInt() == primary }
                assertEquals(0, count, "pixels of primary #FF6D00 in light mode at $width x $height")
            }
        }
    }

    private fun androidx.compose.ui.graphics.Color.toArgbOpaque(): Int = hex().removePrefix("#").toInt(16) or 0xFF000000.toInt()

    // ---------------------------------------------------------------------------------------
    // No clipped control

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
    private fun SkikoComposeUiTest.clipped(width: Int, height: Int): List<String> {
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

    @Test
    fun main_screen_has_no_clipped_control_at_the_tablet_sizes() {
        val problems = ArrayList<String>()
        listOf(1280 to 800, 800 to 1280).forEach { (width, height) ->
            listOf(SidePanelMode.Off, SidePanelMode.SubDatums).forEach { panel ->
                runWindowTest(width, height) {
                    setContent { SampleMainScreen(MainScreenSamples.mill(sidePanel = panel, reminder = true), dark = false) }
                    waitForIdle()
                    mainClock.advanceTimeBy(1_000L)
                    problems += clipped(width, height).map { "$width x $height, panel $panel: $it" }
                }
            }
        }
        assertEquals(emptyList(), problems)
    }

    /** The routes of the screens that show with no pending data. */
    private val routes: List<Pair<Any, String>> = listOf(
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

    private fun screensClipped(width: Int, height: Int): List<String> = AppGraph().use { graph ->
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

    @Test
    fun each_screen_of_7_4_has_no_clipped_control_in_landscape() {
        assertEquals(emptyList(), screensClipped(1280, 800))
    }

    @Test
    fun each_screen_of_7_4_has_no_clipped_control_in_portrait() {
        assertEquals(emptyList(), screensClipped(800, 1280))
    }

    /** One group of connected pixels of one color: its bounds and its number of pixels. */
    private data class Blob(val left: Int, val top: Int, val right: Int, val bottom: Int, val count: Int) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1

        /** True for a text or a thin line: a narrow group, or a group that fills less than 45 % of its bounds. */
        val thin: Boolean get() = minOf(width, height) < 6 || count < width * height * 0.45
    }

    /** Gives the 4-connected groups of the pixels of [argb] in [image]. */
    private fun blobs(image: androidx.compose.ui.graphics.ImageBitmap, argb: Int): List<Blob> {
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
    private fun lightThinPrimary(width: Int, height: Int): Map<String, List<Blob>> = AppGraph().use { graph ->
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

    @Test
    fun light_screens_have_no_text_or_thin_line_in_primary() {
        // The color rule: primary is "not for text or thin lines in light mode". A
        // filled shape in primary (the origin symbol of the graphical view, the track of a checked
        // Switch) is permitted.
        assertEquals(emptyMap(), lightThinPrimary(1280, 800))
    }

    @Test
    fun measurement_mode_chip_has_no_primary_icon_in_light_mode() = runWindowTest(400, 120) {
        setContent {
            org.superdro.core.ui.theme.AppTheme(systemInDarkTheme = false) {
                androidx.compose.material3.Surface {
                    org.superdro.core.ui.graphics.measure.MeasurementModeToggle(on = false, onToggle = {})
                }
            }
        }
        waitForIdle()
        val primary = Theme.lightScheme.primary.toArgbOpaque()
        val count = pixels(captureToImage()).count { pixel -> pixel or 0xFF000000.toInt() == primary }
        assertEquals(0, count, "pixels of primary #FF6D00 in the chip (an outline icon is a thin line)")
    }
}
