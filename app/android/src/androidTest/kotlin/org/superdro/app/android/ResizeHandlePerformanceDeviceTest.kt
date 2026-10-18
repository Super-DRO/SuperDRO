package org.superdro.app.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.fakeadapter.FakeDebugAdapter
import org.superdro.core.model.AxisId
import org.superdro.core.model.DisplaySettings
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.readout.GRAPHICAL_PANE_TAG
import org.superdro.core.ui.readout.GRAPHICAL_PANE_WEIGHT
import org.superdro.core.ui.readout.READOUT_PANE_FRACTION
import org.superdro.core.ui.readout.SPLIT_HANDLE_TAG
import org.superdro.core.ui.readout.rowTag

/**
 * End-to-end Android device performance and persistence test for sliding the readout/viewer split
 * handle in `MainActivity` with hardware `FrameMetrics` collection.
 */
@OptIn(InternalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class ResizeHandlePerformanceDeviceTest {

    private val context = DeviceTestSupport.context
    private var scenario: ActivityScenario<MainActivity>? = null

    private val showOverLockscreenCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @Before
    fun setUp() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        uiAutomation.executeShellCommand("wm set-ignore-orientation-request false").close()
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(showOverLockscreenCallbacks)
        DeviceTestSupport.grant(Manifest.permission.BLUETOOTH_CONNECT)
        DeviceTestSupport.grant(Manifest.permission.POST_NOTIFICATIONS)
        DeviceTestSupport.reset()
    }

    @After
    fun tearDown() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(showOverLockscreenCallbacks)
        runCatching {
            scenario?.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        uiAutomation.executeShellCommand("wm set-ignore-orientation-request true").close()
        runCatching { scenario?.close() }
        DeviceTestSupport.reset()
    }

    @Test
    fun slidingTheSplitHandleInLandscapeAndPortraitMeasuresFramePerformanceAndPersistsDisplaySettings() {
        val manager: ConnectionManager = DeviceTestSupport.get()
        val machines: MachineRepository = DeviceTestSupport.get()
        val appSettings: org.superdro.core.store.json.AppSettingsStore = DeviceTestSupport.get()
        val millId = FakeDebugAdapter.DEMO_MILL_ID

        val demo = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEMO, true)
        scenario = ActivityScenario.launch(demo)

        DeviceTestSupport.waitUntil("Demo mill is online") {
            manager.session(DeviceId(millId))?.state?.value?.isOnline == true
        }

        // Reset both global AppSettings and the demo mill's DisplaySettings split values to defaults before starting.
        runBlocking {
            appSettings.update { settings ->
                settings.copy(
                    readoutPaneFraction = READOUT_PANE_FRACTION,
                    graphicalPaneWeight = GRAPHICAL_PANE_WEIGHT,
                    paneSizeScope = org.superdro.core.model.PaneSizeScope.EachMachine,
                )
            }
            appSettings.awaitWrites()
            machines.updateProfile(millId) { profile ->
                profile.copy(
                    display = profile.display.copy(
                        readoutPaneFraction = READOUT_PANE_FRACTION,
                        graphicalPaneWeight = GRAPHICAL_PANE_WEIGHT,
                    ),
                )
            }
        }

        // 1. Landscape / Expanded horizontal split handle slide performance & persistence.
        setOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        DeviceTestSupport.waitUntil("Vertical split handle is visible in landscape") {
            val bounds = windowSizePx()
            val handle = findNodeBoundsInRoot(SPLIT_HANDLE_TAG)
            val row = findNodeBoundsInRoot(rowTag(AxisId.X))
            bounds != null && bounds.first > bounds.second && handle != null && handle.height > handle.width && row != null
        }
        Thread.sleep(300L)

        val landscapeHandle = assertNotNull(findNodeBoundsInRoot(SPLIT_HANDLE_TAG))

        // Warm-up drag gesture.
        performTouchSlide(
            startX = landscapeHandle.center.x,
            startY = landscapeHandle.center.y,
            stepDeltas = List(12) { 10f to 0f } + List(12) { -10f to 0f },
        )
        Thread.sleep(300L)

        // Reset to exact default before measured slide.
        runBlocking {
            machines.updateProfile(millId) { profile ->
                profile.copy(display = profile.display.copy(readoutPaneFraction = READOUT_PANE_FRACTION))
            }
        }
        DeviceTestSupport.waitUntil("Landscape handle reset to default fraction") {
            val bounds = windowSizePx()
            val handle = findNodeBoundsInRoot(SPLIT_HANDLE_TAG)
            bounds != null && handle != null && abs(handle.center.x / bounds.first - READOUT_PANE_FRACTION) < 0.04f
        }

        val initialLandscapeRow = assertNotNull(findNodeBoundsInRoot(rowTag(AxisId.X)))
        val landscapeHandleReady = assertNotNull(findNodeBoundsInRoot(SPLIT_HANDLE_TAG))
        val landscapeSlideDeltas = List(35) { 8f to 0f } + List(15) { -4f to 0f } + List(10) { 6f to 0f }
        val landscapeSamples = recordFrameMetricsDuring {
            performTouchSlide(
                startX = landscapeHandleReady.center.x,
                startY = landscapeHandleReady.center.y,
                stepDeltas = landscapeSlideDeltas,
            )
        }

        DeviceTestSupport.waitUntil("Expanded split fraction is saved to MachineProfile.display and AppSettings") {
            val profile = runBlocking { machines.loadProfile(millId) }
            profile != null && abs(profile.display.readoutPaneFraction - READOUT_PANE_FRACTION) > 0.05f
        }
        val finalLandscapeRow = assertNotNull(findNodeBoundsInRoot(rowTag(AxisId.X)))
        val afterLandscapeDisplay: DisplaySettings = assertNotNull(runBlocking { machines.loadProfile(millId) }).display
        assertTrue(
            finalLandscapeRow.width > initialLandscapeRow.width + 80f,
            "Expected readout row width to increase after sliding handle right (before=${initialLandscapeRow.width}, after=${finalLandscapeRow.width})",
        )
        assertTrue(
            afterLandscapeDisplay.readoutPaneFraction > READOUT_PANE_FRACTION + 0.05f,
            "Expected persisted readoutPaneFraction > ${READOUT_PANE_FRACTION + 0.05f}, got ${afterLandscapeDisplay.readoutPaneFraction}",
        )
        assertEqualsCompat(afterLandscapeDisplay.readoutPaneFraction, appSettings.settings.value.readoutPaneFraction, 1e-4f)

        val landscapeReport = summarizeFrameMetrics("Android Landscape Horizontal Split Slide", landscapeSamples)
        Log.i(TAG, landscapeReport.summary)
        println(landscapeReport.summary)
        assertTrue(landscapeSamples.isNotEmpty(), "Expected hardware FrameMetrics samples during landscape split handle slide.")

        // 2. Portrait vertical split handle slide performance & persistence (when portrait width < 840dp).
        setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        DeviceTestSupport.waitUntil("Horizontal split handle is visible in portrait") {
            val bounds = windowSizePx()
            val handle = findNodeBoundsInRoot(SPLIT_HANDLE_TAG)
            bounds != null && bounds.first < bounds.second && handle != null && handle.width > handle.height
        }
        Thread.sleep(400L)

        val portraitHandle = assertNotNull(findNodeBoundsInRoot(SPLIT_HANDLE_TAG))
        val isVerticalSplit = portraitHandle.width > portraitHandle.height
        var portraitReport: FrameMetricsSummary? = null
        if (isVerticalSplit) {
            // Warm-up vertical drag.
            performTouchSlide(
                startX = portraitHandle.center.x,
                startY = portraitHandle.center.y,
                stepDeltas = List(12) { 0f to 10f } + List(12) { 0f to -10f },
            )
            Thread.sleep(300L)

            runBlocking {
                machines.updateProfile(millId) { profile ->
                    profile.copy(display = profile.display.copy(graphicalPaneWeight = GRAPHICAL_PANE_WEIGHT))
                }
            }
            Thread.sleep(250L)

            val initialGraphical = assertNotNull(findNodeBoundsInRoot(GRAPHICAL_PANE_TAG))
            val portraitHandleReady = assertNotNull(findNodeBoundsInRoot(SPLIT_HANDLE_TAG))
            val portraitSlideDeltas = List(35) { 0f to 8f } + List(15) { 0f to -4f } + List(10) { 0f to 6f }
            val portraitSamples = recordFrameMetricsDuring {
                performTouchSlide(
                    startX = portraitHandleReady.center.x,
                    startY = portraitHandleReady.center.y,
                    stepDeltas = portraitSlideDeltas,
                )
            }

            DeviceTestSupport.waitUntil("Portrait graphicalPaneWeight is saved to MachineProfile.display and AppSettings") {
                val profile = runBlocking { machines.loadProfile(millId) }
                profile != null && abs(profile.display.graphicalPaneWeight - GRAPHICAL_PANE_WEIGHT) > 0.04f
            }
            val finalGraphical = assertNotNull(findNodeBoundsInRoot(GRAPHICAL_PANE_TAG))
            val afterPortraitDisplay: DisplaySettings = assertNotNull(runBlocking { machines.loadProfile(millId) }).display
            assertTrue(
                finalGraphical.height < initialGraphical.height - 80f,
                "Expected graphical pane height to decrease after sliding handle down (before=${initialGraphical.height}, after=${finalGraphical.height})",
            )
            assertTrue(
                afterPortraitDisplay.graphicalPaneWeight < GRAPHICAL_PANE_WEIGHT - 0.04f,
                "Expected persisted graphicalPaneWeight < ${GRAPHICAL_PANE_WEIGHT - 0.04f}, got ${afterPortraitDisplay.graphicalPaneWeight}",
            )
            assertEqualsCompat(afterPortraitDisplay.graphicalPaneWeight, appSettings.settings.value.graphicalPaneWeight, 1e-4f)
            // Verify the landscape readoutPaneFraction remained saved on the same profile as well.
            assertEqualsCompat(afterLandscapeDisplay.readoutPaneFraction, afterPortraitDisplay.readoutPaneFraction, 1e-4f)

            val report = summarizeFrameMetrics("Android Portrait Vertical Split Slide", portraitSamples)
            portraitReport = report
            Log.i(TAG, report.summary)
            println(report.summary)
            assertTrue(portraitSamples.isNotEmpty(), "Expected hardware FrameMetrics samples during portrait split handle slide.")
        }

        assertTrue(
            landscapeReport.workP50Ms < 33.0,
            "Expected landscape split slide median frame work < 33 ms, got ${landscapeReport.workP50Ms} ms (${landscapeReport.summary})",
        )
        if (portraitReport != null) {
            assertTrue(
                portraitReport.workP50Ms < 33.0,
                "Expected portrait split slide median frame work < 33 ms, got ${portraitReport.workP50Ms} ms (${portraitReport.summary})",
            )
        }
    }

    private fun assertEqualsCompat(expected: Float, actual: Float, tolerance: Float) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected ± $tolerance, got $actual")
    }

    private fun setOrientation(orientation: Int) {
        scenario?.onActivity { activity ->
            activity.requestedOrientation = orientation
        }
    }

    private fun windowSizePx(): Pair<Int, Int>? {
        var size: Pair<Int, Int>? = null
        scenario?.onActivity { activity ->
            val root = findComposeRoot(activity.window.decorView) as? View ?: activity.window.decorView
            size = root.width to root.height
        }
        return size
    }

    private fun findNodeBoundsInRoot(tag: String): Rect? {
        var found: Rect? = null
        scenario?.onActivity { activity ->
            val root = findComposeRoot(activity.window.decorView) ?: return@onActivity
            val node = findSemanticsNodeByTag(root.semanticsOwner.unmergedRootSemanticsNode, tag) ?: return@onActivity
            found = node.boundsInRoot
        }
        return found
    }

    private fun findComposeRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findComposeRoot(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findSemanticsNodeByTag(node: SemanticsNode, tag: String): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) return node
        node.children.forEach { child ->
            findSemanticsNodeByTag(child, tag)?.let { return it }
        }
        return null
    }

    private fun performTouchSlide(startX: Float, startY: Float, stepDeltas: List<Pair<Float, Float>>) {
        val latch = java.util.concurrent.CountDownLatch(1)
        scenario?.onActivity { activity ->
            val rootView = findComposeRoot(activity.window.decorView) as? View ?: run {
                latch.countDown()
                return@onActivity
            }
            val downTime = SystemClock.uptimeMillis()
            var currentX = startX
            var currentY = startY

            fun dispatchNow(action: Int, x: Float, y: Float) {
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try {
                    rootView.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }

            dispatchNow(MotionEvent.ACTION_DOWN, currentX, currentY)
            var index = 0
            val choreographer = android.view.Choreographer.getInstance()
            val callback = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (index < stepDeltas.size) {
                        val (dx, dy) = stepDeltas[index++]
                        currentX += dx
                        currentY += dy
                        dispatchNow(MotionEvent.ACTION_MOVE, currentX, currentY)
                        choreographer.postFrameCallback(this)
                    } else {
                        dispatchNow(MotionEvent.ACTION_UP, currentX, currentY)
                        latch.countDown()
                    }
                }
            }
            choreographer.postFrameCallback(callback)
        }
        assertTrue(
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS),
            "Timed out waiting for Choreographer touch slide gesture to complete",
        )
    }

    private data class FrameSample(
        val totalNs: Long,
        val workNs: Long,
        val unknownDelayNs: Long,
        val inputNs: Long,
        val animationNs: Long,
        val layoutMeasureNs: Long,
        val drawNs: Long,
        val syncNs: Long,
        val commandIssueNs: Long,
    )

    private data class FrameMetricsSummary(
        val frameCount: Int,
        val workP50Ms: Double,
        val totalP50Ms: Double,
        val totalP90Ms: Double,
        val totalP95Ms: Double,
        val totalP99Ms: Double,
        val layoutP50Ms: Double,
        val drawP50Ms: Double,
        val summary: String,
    )

    private fun recordFrameMetricsDuring(block: () -> Unit): List<FrameSample> {
        val samples = CopyOnWriteArrayList<FrameSample>()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val copy = FrameMetrics(metrics)
            val unknownDelayNs = copy.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION).coerceAtLeast(0L)
            val inputNs = copy.getMetric(FrameMetrics.INPUT_HANDLING_DURATION).coerceAtLeast(0L)
            val animationNs = copy.getMetric(FrameMetrics.ANIMATION_DURATION).coerceAtLeast(0L)
            val layoutMeasureNs = copy.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION).coerceAtLeast(0L)
            val drawNs = copy.getMetric(FrameMetrics.DRAW_DURATION).coerceAtLeast(0L)
            val syncNs = copy.getMetric(FrameMetrics.SYNC_DURATION).coerceAtLeast(0L)
            val commandIssueNs = copy.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION).coerceAtLeast(0L)
            val workNs = inputNs + animationNs + layoutMeasureNs + drawNs + syncNs + commandIssueNs
            samples += FrameSample(
                totalNs = copy.getMetric(FrameMetrics.TOTAL_DURATION),
                workNs = workNs,
                unknownDelayNs = unknownDelayNs,
                inputNs = inputNs,
                animationNs = animationNs,
                layoutMeasureNs = layoutMeasureNs,
                drawNs = drawNs,
                syncNs = syncNs,
                commandIssueNs = commandIssueNs,
            )
        }
        val handlerThread = HandlerThread("ResizePerfFrameMetrics").apply { start() }
        val metricsHandler = Handler(handlerThread.looper)
        scenario?.onActivity { activity ->
            activity.window.addOnFrameMetricsAvailableListener(listener, metricsHandler)
        }
        try {
            block()
            SystemClock.sleep(100L)
        } finally {
            scenario?.onActivity { activity ->
                activity.window.removeOnFrameMetricsAvailableListener(listener)
            }
            handlerThread.quitSafely()
        }
        return samples.filter { it.totalNs in 1L..500_000_000L }
    }

    private fun summarizeFrameMetrics(label: String, samples: List<FrameSample>): FrameMetricsSummary {
        if (samples.isEmpty()) {
            return FrameMetricsSummary(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "[$label] frames=0")
        }
        fun percentileMs(valuesNs: List<Long>, p: Double): Double {
            val sorted = valuesNs.sorted()
            val idx = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.lastIndex)
            return sorted[idx] / 1_000_000.0
        }
        val totals = samples.map { it.totalNs }
        val works = samples.map { it.workNs }
        val delays = samples.map { it.unknownDelayNs }
        val inputs = samples.map { it.inputNs }
        val anims = samples.map { it.animationNs }
        val layouts = samples.map { it.layoutMeasureNs }
        val draws = samples.map { it.drawNs }
        val syncs = samples.map { it.syncNs }
        val commands = samples.map { it.commandIssueNs }

        val workMinMs = works.min() / 1_000_000.0
        val workMeanMs = works.average() / 1_000_000.0
        val workP50Ms = percentileMs(works, 0.50)
        val workP90Ms = percentileMs(works, 0.90)
        val workP95Ms = percentileMs(works, 0.95)
        val totalMinMs = totals.min() / 1_000_000.0
        val totalMeanMs = totals.average() / 1_000_000.0
        val totalP50Ms = percentileMs(totals, 0.50)
        val totalP90Ms = percentileMs(totals, 0.90)
        val totalP95Ms = percentileMs(totals, 0.95)
        val totalP99Ms = percentileMs(totals, 0.99)
        val totalMaxMs = totals.max() / 1_000_000.0
        val delayP50Ms = percentileMs(delays, 0.50)
        val inputP50Ms = percentileMs(inputs, 0.50)
        val animP50Ms = percentileMs(anims, 0.50)
        val layoutP50Ms = percentileMs(layouts, 0.50)
        val layoutP95Ms = percentileMs(layouts, 0.95)
        val drawP50Ms = percentileMs(draws, 0.50)
        val drawP95Ms = percentileMs(draws, 0.95)
        val syncP50Ms = percentileMs(syncs, 0.50)
        val cmdP50Ms = percentileMs(commands, 0.50)
        val janky16ms = works.count { it > 16_666_667L }

        val summary = buildString {
            append("[$label] frames=${samples.size} ")
            append("work(min=${"%.2f".format(workMinMs)}ms, mean=${"%.2f".format(workMeanMs)}ms, ")
            append("p50=${"%.2f".format(workP50Ms)}ms, p90=${"%.2f".format(workP90Ms)}ms, p95=${"%.2f".format(workP95Ms)}ms) ")
            append("total(min=${"%.2f".format(totalMinMs)}ms, mean=${"%.2f".format(totalMeanMs)}ms, ")
            append("p50=${"%.2f".format(totalP50Ms)}ms, p90=${"%.2f".format(totalP90Ms)}ms, ")
            append("p95=${"%.2f".format(totalP95Ms)}ms, p99=${"%.2f".format(totalP99Ms)}ms, max=${"%.2f".format(totalMaxMs)}ms) ")
            append("delay(p50=${"%.2f".format(delayP50Ms)}ms) ")
            append("input(p50=${"%.2f".format(inputP50Ms)}ms) ")
            append("anim(p50=${"%.2f".format(animP50Ms)}ms) ")
            append("layout(p50=${"%.2f".format(layoutP50Ms)}ms, p95=${"%.2f".format(layoutP95Ms)}ms) ")
            append("draw(p50=${"%.2f".format(drawP50Ms)}ms, p95=${"%.2f".format(drawP95Ms)}ms) ")
            append("sync(p50=${"%.2f".format(syncP50Ms)}ms) ")
            append("gpuIssue(p50=${"%.2f".format(cmdP50Ms)}ms) ")
            append("workOver16.7ms=$janky16ms/${samples.size}")
        }
        return FrameMetricsSummary(
            frameCount = samples.size,
            workP50Ms = workP50Ms,
            totalP50Ms = totalP50Ms,
            totalP90Ms = totalP90Ms,
            totalP95Ms = totalP95Ms,
            totalP99Ms = totalP99Ms,
            layoutP50Ms = layoutP50Ms,
            drawP50Ms = drawP50Ms,
            summary = summary,
        )
    }

    private companion object {
        const val TAG = "ResizePerf"
    }
}
