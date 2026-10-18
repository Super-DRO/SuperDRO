package org.superdro.validation.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisId
import org.superdro.core.model.SubDatum
import org.superdro.core.ui.App
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.readout.GRAPHICAL_PANE_TAG
import org.superdro.core.ui.readout.GRAPHICAL_PANE_WEIGHT
import org.superdro.core.ui.readout.READOUT_PANE_FRACTION
import org.superdro.core.ui.readout.SPLIT_HANDLE_TAG
import org.superdro.core.ui.readout.SUPPORTING_PANE_TAG
import org.superdro.core.ui.readout.rowTag
import org.superdro.core.ui.tabTag

/**
 * End-to-end validation and performance measurement of sliding the readout/viewer split handle in
 * `App()` with the full DI graph, SQLite persistence, and live fake adapter session.
 */
@OptIn(ExperimentalTestApi::class)
class ResizeHandlePerformanceValidationTest {

    private fun SkikoComposeUiTest.app(graph: AppGraph) = setContent { ProvideDI(graph.di) { App() } }

    private fun SkikoComposeUiTest.waitFor(tag: String, timeoutMs: Long = 10_000L) =
        waitUntil("node $tag", timeoutMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun connectMillWithSubDatums(graph: AppGraph) {
        val points = (1..24).map { index ->
            SubDatum(
                id = "pt-$index",
                name = "P$index",
                x = (index * 5_000).toDouble(),
                y = ((index % 6) * 8_000 - 20_000).toDouble(),
                z = -1_000.0,
            )
        }
        graph.connect("mill", "Mill", scenario = "still") { profile ->
            profile.copy(
                workspaces = profile.workspaces.map { workspace ->
                    workspace.copy(subDatums = points, activeSubDatumId = points.first().id)
                },
            )
        }
    }

    @Test
    fun sliding_the_horizontal_split_handle_in_expanded_layout_renders_smoothly_and_persists_to_profile() = AppGraph().use { graph ->
        runWindowTest(1280, 800) {
            app(graph)
            connectMillWithSubDatums(graph)
            waitFor(tabTag(DeviceId("mill")))
            waitFor(SPLIT_HANDLE_TAG)
            waitFor(rowTag(AxisId.X))
            waitFor(SUPPORTING_PANE_TAG)
            waitFor(GRAPHICAL_PANE_TAG)

            val initialProfile = runBlocking { graph.machines.loadProfile("mill") }
            assertNotNull(initialProfile)
            assertEquals(READOUT_PANE_FRACTION, initialProfile.display.readoutPaneFraction, 1e-4f)

            val handleNode = onNodeWithTag(SPLIT_HANDLE_TAG)
            val rowXNode = onNodeWithTag(rowTag(AxisId.X))

            // Warm-up drag gesture so JIT and initial layout caches are primed before timing.
            handleNode.performTouchInput {
                down(center)
                moveBy(Offset(16f, 0f))
                moveBy(Offset(-16f, 0f))
                up()
            }
            waitForIdle()

            // Measure per-step frame render latency across a 60-step continuous horizontal slide:
            // 30 steps right (+8 px each = +240 px), then 15 steps left (-6 px each = -90 px), net +150 px.
            val stepDeltasPx = List(30) { 8f } + List(15) { -6f } + List(15) { 4f }
            val stepDurationsNs = LongArray(stepDeltasPx.size)
            val observedWidths = FloatArray(stepDeltasPx.size)

            handleNode.performTouchInput {
                down(center)
            }
            waitForIdle()

            stepDeltasPx.forEachIndexed { index, deltaX ->
                val startNs = System.nanoTime()
                handleNode.performTouchInput {
                    moveBy(Offset(deltaX, 0f))
                }
                stepDurationsNs[index] = System.nanoTime() - startNs
                observedWidths[index] = rowXNode.rect().width
            }

            handleNode.performTouchInput {
                up()
            }
            waitForIdle()

            val finalRowWidth = onNodeWithTag(rowTag(AxisId.X)).rect().width
            val expectedFraction = (finalRowWidth / 1280f).coerceIn(0.25f, 0.75f)

            waitUntil("persisted readoutPaneFraction in SQLite", 5_000L) {
                val stored = runBlocking { graph.machines.loadProfile("mill") }
                stored != null && abs(stored.display.readoutPaneFraction - expectedFraction) < 0.02f
            }

            val storedProfile = assertNotNull(runBlocking { graph.machines.loadProfile("mill") })
            assertEquals(expectedFraction, storedProfile.display.readoutPaneFraction, 0.02f)
            assertEquals(storedProfile.display.readoutPaneFraction, graph.appSettings.settings.value.readoutPaneFraction, 1e-4f)
            assertTrue(
                storedProfile.display.readoutPaneFraction > READOUT_PANE_FRACTION + 0.10f,
                "Expected saved readoutPaneFraction > ${READOUT_PANE_FRACTION + 0.10f}, got ${storedProfile.display.readoutPaneFraction}",
            )

            // Verify the readout width tracked the handle movement monotonically during the first 30 rightwards steps.
            assertTrue(observedWidths[29] > observedWidths[0] + 180f, "Readout width must grow smoothly during rightward drag steps.")

            // Connect a second machine with its own per-machine split fraction and verify PaneSizeScope.App vs PaneSizeScope.EachMachine.
            graph.connect("lathe", "Lathe", org.superdro.core.model.MachineType.Lathe, scenario = "commands") { profile ->
                profile.copy(display = profile.display.copy(readoutPaneFraction = 0.35f))
            }
            waitFor(tabTag(DeviceId("lathe")))
            onNodeWithTag(tabTag(DeviceId("lathe"))).performTouchInput {
                down(center)
                up()
            }
            waitForIdle()
            // In PaneSizeScope.App (default), the second machine uses the global readoutPaneFraction.
            val latheGlobalRowWidth = onNodeWithTag(rowTag(AxisId.X)).rect().width
            assertEquals(finalRowWidth, latheGlobalRowWidth, 6f)

            // Switching paneSizeScope to EachMachine applies the second machine's own DisplaySettings.readoutPaneFraction (0.35f).
            graph.appSettings.update { settings -> settings.copy(paneSizeScope = org.superdro.core.model.PaneSizeScope.EachMachine) }
            waitUntil("lathe uses per-machine readoutPaneFraction", 5_000L) {
                abs(onNodeWithTag(rowTag(AxisId.X)).rect().width - 1280f * 0.35f) < 12f
            }

            val stats = computeFrameStats("JVM Expanded Horizontal Split Slide (1280x800)", stepDurationsNs)
            println(stats.report)
            assertTrue(stats.p50Ms < 25.0, "Expected median frame time < 25 ms, got ${stats.p50Ms} ms (${stats.report})")
            assertTrue(stats.p95Ms < 50.0, "Expected p95 frame time < 50 ms, got ${stats.p95Ms} ms (${stats.report})")
        }
    }

    @Test
    fun sliding_the_vertical_split_handle_in_portrait_layout_renders_smoothly_and_persists_to_profile() = AppGraph().use { graph ->
        runWindowTest(800, 1280) {
            app(graph)
            connectMillWithSubDatums(graph)
            waitFor(tabTag(DeviceId("mill")))
            waitFor(SPLIT_HANDLE_TAG)
            waitFor(rowTag(AxisId.X))
            waitFor(GRAPHICAL_PANE_TAG)

            val initialProfile = runBlocking { graph.machines.loadProfile("mill") }
            assertNotNull(initialProfile)
            assertEquals(GRAPHICAL_PANE_WEIGHT, initialProfile.display.graphicalPaneWeight, 1e-4f)

            val handleNode = onNodeWithTag(SPLIT_HANDLE_TAG)
            val graphicalNode = onNodeWithTag(GRAPHICAL_PANE_TAG)

            // Warm-up drag gesture.
            handleNode.performTouchInput {
                down(center)
                moveBy(Offset(0f, 16f))
                moveBy(Offset(0f, -16f))
                up()
            }
            waitForIdle()

            // Measure per-step frame render latency across a 60-step continuous vertical slide:
            // 30 steps down (+6 px each = +180 px), then 15 steps up (-4 px each = -60 px), then 15 steps down (+4 px = +60 px), net +180 px down.
            val stepDeltasPx = List(30) { 6f } + List(15) { -4f } + List(15) { 4f }
            val stepDurationsNs = LongArray(stepDeltasPx.size)
            val observedGraphicalHeights = FloatArray(stepDeltasPx.size)

            handleNode.performTouchInput {
                down(center)
            }
            waitForIdle()

            stepDeltasPx.forEachIndexed { index, deltaY ->
                val startNs = System.nanoTime()
                handleNode.performTouchInput {
                    moveBy(Offset(0f, deltaY))
                }
                stepDurationsNs[index] = System.nanoTime() - startNs
                observedGraphicalHeights[index] = graphicalNode.rect().height
            }

            handleNode.performTouchInput {
                up()
            }
            waitForIdle()

            waitUntil("persisted graphicalPaneWeight in SQLite", 5_000L) {
                val stored = runBlocking { graph.machines.loadProfile("mill") }
                stored != null && stored.display.graphicalPaneWeight < GRAPHICAL_PANE_WEIGHT - 0.08f
            }

            val storedProfile = assertNotNull(runBlocking { graph.machines.loadProfile("mill") })
            assertEquals(storedProfile.display.graphicalPaneWeight, graph.appSettings.settings.value.graphicalPaneWeight, 1e-4f)
            assertTrue(
                storedProfile.display.graphicalPaneWeight < GRAPHICAL_PANE_WEIGHT - 0.08f,
                "Expected saved graphicalPaneWeight < ${GRAPHICAL_PANE_WEIGHT - 0.08f}, got ${storedProfile.display.graphicalPaneWeight}",
            )
            assertTrue(
                observedGraphicalHeights[29] < observedGraphicalHeights[0] - 120f,
                "Graphical pane height must shrink smoothly during downward drag steps.",
            )

            val stats = computeFrameStats("JVM Portrait Vertical Split Slide (800x1280)", stepDurationsNs)
            println(stats.report)
            assertTrue(stats.p50Ms < 25.0, "Expected median frame time < 25 ms, got ${stats.p50Ms} ms (${stats.report})")
            assertTrue(stats.p95Ms < 50.0, "Expected p95 frame time < 50 ms, got ${stats.p95Ms} ms (${stats.report})")
        }
    }

    private data class FrameStats(
        val count: Int,
        val minMs: Double,
        val meanMs: Double,
        val p50Ms: Double,
        val p90Ms: Double,
        val p95Ms: Double,
        val p99Ms: Double,
        val maxMs: Double,
        val report: String,
    )

    private fun computeFrameStats(label: String, durationsNs: LongArray): FrameStats {
        val sortedMs = durationsNs.map { it / 1_000_000.0 }.sorted()
        fun percentile(p: Double): Double {
            val idx = ((sortedMs.size - 1) * p).roundToInt().coerceIn(0, sortedMs.lastIndex)
            return sortedMs[idx]
        }
        val minMs = sortedMs.first()
        val maxMs = sortedMs.last()
        val meanMs = sortedMs.average()
        val p50Ms = percentile(0.50)
        val p90Ms = percentile(0.90)
        val p95Ms = percentile(0.95)
        val p99Ms = percentile(0.99)
        val report = buildString {
            append("[$label] frames=${sortedMs.size} ")
            append("min=${"%.2f".format(minMs)}ms ")
            append("mean=${"%.2f".format(meanMs)}ms ")
            append("p50=${"%.2f".format(p50Ms)}ms ")
            append("p90=${"%.2f".format(p90Ms)}ms ")
            append("p95=${"%.2f".format(p95Ms)}ms ")
            append("p99=${"%.2f".format(p99Ms)}ms ")
            append("max=${"%.2f".format(maxMs)}ms")
        }
        return FrameStats(sortedMs.size, minMs, meanMs, p50Ms, p90Ms, p95Ms, p99Ms, maxMs, report)
    }
}
