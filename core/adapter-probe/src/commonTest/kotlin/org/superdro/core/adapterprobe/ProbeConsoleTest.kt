package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.superdro.core.fakeadapter.FakeFaults
import org.superdro.core.fakeadapter.FakeScenario

/** The console on the fake adapter. */
class ProbeConsoleTest {

    private val silent = FakeScenario("silent", faults = FakeFaults(stopDataAfterMs = 0L))

    private fun MemoryProbeFiles.log(record: RunRecord): List<String> = runs.first { it.logName == record.logFile }.logLines

    @Test
    fun consoleCommandsGoThroughTheQueue() = runTest {
        val h = ProbeHarness(this)
        val console = ProbeConsole(backgroundScope, h.env) { h.records += it }
        assertTrue(console.start(h.link("commands"), TestOptions()))
        delay(3_000)
        console.submit("get version")
        delay(1_000)
        console.submit("set_mode x 2")
        val prompt = console.state.first { it.prompt != null }.prompt!!
        assertEquals(PromptKind.TypedYes, prompt.kind)
        assertEquals("#set_mode:x,2;", prompt.command)
        assertEquals("2 (Quadrature A/B)", prompt.currentMode)
        assertEquals(ProbeTexts.cautionSetMode('x'), prompt.caution)
        console.answer("yes")
        delay(3_000)
        console.submit("raw #get:mac;")
        delay(3_000)
        console.submit("bad")
        console.submit("print all")
        delay(500)
        console.submit("quit")
        console.join()
        val lines = console.state.value.lines
        assertTrue(lines.any { it.contains("request '\"#get:version;\"': ack 0.0 ms, answer after 0.0 ms: ?061.0.0; (?06 version = '1.0.0')") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.contains("request '#set_mode:x,2;': ack 0.0 ms, answer after 0.0 ms: \$060; (\$ sub 06 Inputs, code 0 OK)") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.contains("request '#get:mac;': ack 0.0 ms, answer after 0.0 ms: ?05246f28a1b2c3;") }, lines.joinToString("\n"))
        assertTrue(lines.contains("unknown command 'bad'. Type help."))
        assertTrue(lines.any { it.startsWith("Phase 'all':") }, "The end of the console shows the statistics.")
        assertEquals(PrintLevel.All, console.state.value.level)
        // The start queries, then the commands of the user, in this order.
        val received = h.factory.adapter("commands")!!.received
        assertEquals(listOf("#get:version", "#set_mode:x,2", "#get_mode:x", "#get:mac"), received.drop(11))
        assertEquals("console", h.records.single().test)
        assertTrue(h.records.single().logFile!!.endsWith("-interactive-fake.log"))
    }

    @Test
    fun consoleSendsNothingBeforeTheFirstValidMessage() = runTest {
        val h = ProbeHarness(this, listOf(silent))
        val console = ProbeConsole(backgroundScope, h.env)
        console.start(h.link("silent"), TestOptions())
        delay(1_000)
        console.submit("get mac")
        delay(5_000)
        console.submit("quit")
        console.join()
        assertTrue(console.state.value.lines.contains("No valid message came yet. The app sends nothing before the first valid message (AP7)."))
        assertEquals(emptyList(), h.factory.adapter("silent")!!.received)
        val log = (h.files as MemoryProbeFiles).runs.single().logLines
        assertTrue(log.none { it.contains(" TX ") }, "No byte goes out: no command and no BEL byte.")
    }

    @Test
    fun consoleCancelsAStateCommandWithoutTheTypedYes() = runTest {
        val h = ProbeHarness(this)
        val console = ProbeConsole(backgroundScope, h.env)
        console.start(h.link("commands"), TestOptions())
        delay(3_000)
        console.submit("reboot")
        val prompt = console.state.first { it.prompt != null }.prompt!!
        assertEquals(ProbeTexts.CAUTION_REBOOT, prompt.caution)
        console.answer("Yes please")
        delay(1_000)
        console.submit("quit")
        console.join()
        assertTrue(console.state.value.lines.contains("Cancelled. The tool did not send #reboot:1000;."))
        assertTrue(h.factory.adapter("commands")!!.received.none { it.startsWith("#reboot") })
    }

    @Test
    fun noBelByteBeforeTheFirstValidMessageInATest() = runTest {
        val h = ProbeHarness(this, listOf(silent))
        val record = h.run(ProbeTestId.T03, "silent")
        assertEquals(listOf("No valid message in 10 s after the link opened. The test stops."), record.summary)
        val log = (h.files as MemoryProbeFiles).runs.single().logLines
        assertTrue(log.none { it.contains(" TX ") })
    }

    @Test
    fun theConsoleStatisticsCountTheLastReadoutGroup() = runTest {
        val h = ProbeHarness(this)
        val console = ProbeConsole(backgroundScope, h.env) { h.records += it }
        console.start(h.link("still"), TestOptions())
        delay(3_000)
        console.submit("quit")
        console.join()
        val log = (h.files as MemoryProbeFiles).log(h.records.single())
        // Each frame of the scenario "still" is one block with X, Y, and Z: one readout group for each block.
        val readoutBlocks = log.count { it.contains(" RX ") && it.endsWith("| X10000;Y20000;Z-5000;") }
        val groups = log.single { it.contains(" NOTE     Readout groups (readouts after one '@', else readouts in one received block): ") }
        assertEquals(60, readoutBlocks)
        assertTrue(groups.endsWith("in one received block): $readoutBlocks."), groups)
    }
}
