package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
/** The runner state machine that a shell drives: the prompt with "Next", the countdown, and the stop. */
class ProbeRunnerTest {

    @Test
    fun aPromptWaitsForNextAndATimedStepShowsACountdown() = runTest {
        val h = ProbeHarness(this)
        assertTrue(h.runner.start(ProbeTestId.T04, h.link("slow-move"), TestOptions(durationS = 5.0)))
        assertFalse(h.runner.start(ProbeTestId.T03, h.link("still"), TestOptions()), "One test at a time.")
        val prompt = h.awaitPrompt()
        assertEquals(PromptKind.Next, prompt.kind)
        assertEquals("Push Next to start the motion phase: ", prompt.text)
        assertEquals("Move an axis back and forth during the full test (5 s).", h.runner.state.value.step)
        assertNull(h.runner.state.value.countdownEndNanos, "No countdown while the prompt waits.")
        val before = testScheduler.currentTime
        h.runner.next()
        val counting = h.runner.state.first { it.countdownEndNanos != null }
        assertEquals((before + 5_000L) * 1_000_000L, counting.countdownEndNanos)
        assertNull(counting.prompt)
        h.runner.join()
        val done = h.runner.state.value
        assertEquals(RunStatus.Done, done.status)
        assertNull(done.countdownEndNanos)
        assertTrue(done.summary.first().startsWith("Phase 'motion': 5.0 s,"))
        assertEquals("Summary of test T04 (motion):", done.live.dropWhile { it != "" }.drop(1).first())
    }

    @Test
    fun stopEndsTheTestAndClosesTheFiles() = runTest {
        val h = ProbeHarness(this)
        h.runner.start(ProbeTestId.T03, h.link("still"), TestOptions())
        delay(5_000)
        h.runner.stop()
        h.runner.join()
        assertEquals(RunStatus.Stopped, h.runner.state.value.status)
        assertEquals(RunStatus.Stopped, h.records.single().status)
        val log = (h.files as MemoryProbeFiles).runs.single()
        assertTrue(log.closed)
        assertTrue(log.logLines.any { it.endsWith("NOTE Stopped by the user.") })
        assertTrue(log.logLines.any { it.endsWith("NOTE link closed by the tool (normal close)") })
        assertEquals("# end", log.logLines.last())
    }

    @Test
    fun aLinkThatDoesNotOpenGivesAFailedRun() = runTest {
        val h = ProbeHarness(this)
        h.runner.start(ProbeTestId.T03, h.link("no-such-scenario"), TestOptions())
        h.runner.join()
        val record = h.records.single()
        assertEquals(RunStatus.Failed, record.status)
        assertEquals("The link did not open: No fake scenario \"no-such-scenario\"", record.summary.single())
    }

    @Test
    fun theFirstLinesOfARunAreTheLinesOfTheTool() = runTest {
        val h = ProbeHarness(this)
        h.run(ProbeTestId.T03, "still", TestOptions(durationS = 1.0))
        val notes = (h.files as MemoryProbeFiles).runs.single().logLines.filter { !it.startsWith("#") && " NOTE " in it }.map { it.substringAfter(" NOTE ") }
        assertTrue(notes[0].startsWith("link open: fake adapter, scenario still (open took 0.0 ms)"), notes[0])
        // The open ends before the first block comes, so the first line of the test comes first, as in the tool.
        assertEquals("Test T03 (idle): Idle stream (default 30 s): readouts when no axis moves. Questions: Q15, Q16, Q18, Q19, Q37.", notes[1])
        assertEquals("first valid message 'X10000;': state Connected", notes[2])
        assertEquals("Do not move an axis and do not touch the probe for 1 s.", notes[3])
        assertEquals("phase idle", notes[4])
        assertEquals("phase end", notes[5])
        assertEquals("Summary of test T03 (idle):", notes[6])
    }

    @Test
    fun tt1RunsT01BeforeAndAfterThePermissionRequest() = runTest {
        val h = ProbeHarness(this)
        var granted = false
        val access = object : LocalNetworkAccess {
            override val exists: Boolean = true
            override fun granted(): Boolean = granted
            override suspend fun request(): Boolean {
                granted = true
                return true
            }
        }
        val tablet = TabletInfo("samsung", "SM-X230", "16", 36, "2026-09-01")
        val answers = backgroundScope.launch { h.runner.state.collect { st -> if (st.prompt != null) h.runner.next() } }
        assertTrue(h.runner.startTt1(h.link("still"), TestOptions(), access, tablet))
        h.runner.join()
        answers.cancel()
        assertEquals(listOf("T01", "T01", "TT1"), h.records.map { it.test })
        val tt1 = h.records.last().summary
        assertEquals("TT1: tablet samsung SM-X230, Android 16 (API 36), security patch 2026-09-01.", tt1[0])
        assertEquals("Q42: ACCESS_LOCAL_NETWORK before the request: not granted.", tt1[1])
        assertEquals("Q42: the request of ACCESS_LOCAL_NETWORK gave: granted.", tt1[2])
        assertEquals("Q42: ACCESS_LOCAL_NETWORK after the request: granted.", tt1[3])
        assertTrue(tt1[4].startsWith("Q42: T01 before the request (20261012-080000-TT1-T01-before-fake.log): First byte 0.0 ms after the open."), tt1[4])
        assertEquals("Q42: the link works with no grant of ACCESS_LOCAL_NETWORK on this Android version.", tt1[6])
        assertEquals(4, h.records.last().files.size)
    }

    @Test
    fun tt1DecisionLines() {
        fun t01(works: Boolean) = RunRecord(
            "T01", "tcp", "TCP", "tcp:x:8080", 0L, if (works) RunStatus.Done else RunStatus.Failed,
            if (works) listOf("First byte 1.0 ms after the open. First valid message: 2.0 ms after the open.", "Checklist item 1: the adapter sends valid messages with no write from the app: yes.") else listOf("The link did not open: refused"),
            logFile = "a.log",
        )
        assertEquals(
            "Q42: the link fails with no grant, and works after the grant. The run-time request of F1.32 is necessary on this Android version.",
            TabletTests.tt1Lines(t01(false), t01(true), exists = true, before = "not granted", granted = true).last(),
        )
        assertEquals(
            "Q42: the link fails also with the grant. Do T01 on the TCP link from the PC. If T01 works there, report the result as a tablet problem.",
            TabletTests.tt1Lines(t01(false), t01(false), exists = true, before = "not granted", granted = true).last(),
        )
        assertEquals(
            "Q42: the link works with no grant; this Android version has no ACCESS_LOCAL_NETWORK.",
            TabletTests.tt1Lines(t01(true), t01(true), exists = false, before = "not in this Android version", granted = false).last(),
        )
        assertEquals("Q42: T01 before the request (a.log): The link did not open: refused.", TabletTests.tt1Lines(t01(false), t01(true), true, "not granted", true).first())
    }

    @Test
    fun tt2LinesFromTheAttaches() {
        val tablet = TabletInfo("samsung", "SM-X230", "16", 36, "2026-09-01")
        val first = CameraAttach(ProbeHarness.WALL_START_MS, bootCount = 7, vendorId = 0x0c45, productId = 0x6366, name = "USB Camera", hasPermission = false, dialogShown = true)
        val again = first.copy(epochMs = first.epochMs + 60_000L, hasPermission = true, dialogShown = false)
        val restart = again.copy(epochMs = again.epochMs + 600_000L, bootCount = 8)
        val lines = TabletTests.tt2Lines(listOf(first, again, restart), tablet)
        assertEquals("TT2: 3 attaches of a UVC camera (USB video class 14).", lines[1])
        assertEquals("  attach 1: 2026-10-12 08:00 UTC, vendor 0x0c45, product 0x6366, USB Camera, boot 7, hasPermission() false, dialog yes.", lines[2])
        assertEquals(
            "Q49: no dialog after the first \"use by default\", also after a restart: the attach filter gives a permanent permission. F28.7 can use the attach filter.",
            lines.last(),
        )
        assertEquals(
            "Q49: no dialog after the first \"use by default\". Restart the tablet and connect the camera again to complete the test.",
            TabletTests.tt2Lines(listOf(first, again), tablet).last(),
        )
        assertTrue(TabletTests.tt2Lines(listOf(first, again.copy(dialogShown = true)), tablet).last().startsWith("Q49: a dialog showed at a later attach"))
        assertEquals("Q49: no attach recorded. Connect the UVC camera to the tablet.", TabletTests.tt2Lines(emptyList(), tablet).last())
        assertEquals(first, CameraAttach.fromJsonLine(first.toJsonLine()))
    }

    @Test
    fun runRecordsAreJsonLines() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T02, "commands")
        val copy = assertNotNull(RunRecord.fromJsonLine(record.toJsonLine()))
        assertEquals(record, copy)
        assertNull(RunRecord.fromJsonLine("not json"))
    }
}
