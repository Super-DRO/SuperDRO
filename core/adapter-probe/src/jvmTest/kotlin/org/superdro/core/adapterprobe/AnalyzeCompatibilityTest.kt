package org.superdro.core.adapterprobe

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.superdro.core.fakeadapter.FakeScenario
import org.superdro.core.fakeadapter.Motion
import org.superdro.core.fakeadapter.ProbeScenario

/**
 * The `.log` files of the app are files of format 1: `adapter_probe.py --analyze` reads them, and
 * it gives the same phase reports as the app.
 *
 * The test needs Python 3 and the PC tool. The path of the tool is the system property
 * `adapterProbeScript`, else the environment variable `ADAPTER_PROBE_SCRIPT`. Without the tool, the
 * test writes a note and gives no result.
 */
class AnalyzeCompatibilityTest {

    private val script: File? = (System.getProperty("adapterProbeScript") ?: System.getenv("ADAPTER_PROBE_SCRIPT"))?.let(::File)

    private fun available(): Boolean {
        if (script?.isFile != true) {
            println("AnalyzeCompatibilityTest: no tool at $script. The test gives no result.")
            return false
        }
        return true
    }

    /** Runs `python3 -I adapter_probe.py --analyze <log>` and gives the output lines. */
    private fun analyze(log: File, pair: String? = null): List<String> {
        val command = mutableListOf("python3", "-I", checkNotNull(script).path, "--analyze", log.path)
        if (pair != null) command += listOf("--pair", pair)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "The analysis did not end.")
        assertEquals(0, process.exitValue(), output)
        return output.lines()
    }

    /** The report of the phase [name] in the analysis output: its lines up to the special lines of the phase. */
    private fun analyzedPhase(output: List<String>, name: String): List<String> {
        val start = output.indexOfFirst { it.startsWith("Phase '$name':") }
        assertTrue(start >= 0, "The analysis has no phase '$name':\n${output.joinToString("\n")}")
        return output.drop(start).takeWhile { it.isNotEmpty() }.filterNot { it.startsWith("    Q") }
    }

    private fun appPhase(record: RunRecord, name: String): List<String> = block(record.summary, "Phase '$name':")

    private fun folder(): Pair<File, DirectoryProbeFiles> {
        val dir = Files.createTempDirectory("probe-logs").toFile()
        return dir to DirectoryProbeFiles(Path(dir.path))
    }

    /**
     * A test can list fewer events than the analysis (T05: 6, T09: 4; the analysis: 12). The list of
     * the analysis is cut to the number of events of the app.
     */
    private fun cutEvents(analysis: List<String>, app: List<String>): List<String> {
        val header = Regex("^(    Status, error, and info messages: \\d+ \\(first )(\\d+)( below\\)\\.)$")
        val appMatch = app.firstNotNullOfOrNull { header.find(it) } ?: return analysis
        val shown = appMatch.groupValues[2].toInt()
        val out = ArrayList<String>()
        var index = 0
        while (index < analysis.size) {
            val match = header.find(analysis[index])
            if (match == null) {
                out += analysis[index++]
                continue
            }
            val listed = match.groupValues[2].toInt()
            out += match.groupValues[1] + minOf(shown, listed) + match.groupValues[3]
            out += analysis.subList(index + 1, index + 1 + minOf(shown, listed))
            index += 1 + listed
        }
        return out
    }

    private suspend fun compare(record: RunRecord, dir: File, phases: List<String>, pair: String? = null) {
        val log = File(dir, checkNotNull(record.logFile))
        val output = analyze(log, pair)
        for (phase in phases) {
            val app = appPhase(record, phase)
            assertTrue(app.isNotEmpty(), "The summary of the app has no phase '$phase'.")
            assertEquals(app, cutEvents(analyzedPhase(output, phase), app), "phase '$phase' of ${log.name}")
        }
    }

    @Test
    fun t03IdleGivesTheSameCounts() = runTest {
        if (!available()) return@runTest
        val (dir, files) = folder()
        val record = ProbeHarness(this, files = files).run(ProbeTestId.T03, "still", TestOptions(durationS = 10.0))
        compare(record, dir, listOf("idle"))
        assertTrue(File(dir, checkNotNull(record.binFile)).length() > 0)
    }

    @Test
    fun t05PhasesWithAndWithoutTheBelByteGiveTheSameCounts() = runTest {
        if (!available()) return@runTest
        val (dir, files) = folder()
        val record = ProbeHarness(this, files = files).run(ProbeTestId.T05, "still", TestOptions(durationS = 10.0))
        compare(record, dir, listOf("open, no writes", "BEL on", "BEL off", "BEL on again"))
    }

    @Test
    fun t04GroupsWithTimeStampsGiveTheSamePairLine() = runTest {
        if (!available()) return@runTest
        val (dir, files) = folder()
        val record = ProbeHarness(this, files = files).run(ProbeTestId.T04, "two-inputs", TestOptions(pair = "X" to "W", durationS = 5.0))
        compare(record, dir, listOf("motion"), pair = "X,W")
    }

    @Test
    fun t09ProbeWithJunkAndNoiseGivesTheSameCounts() = runTest {
        if (!available()) return@runTest
        val (dir, files) = folder()
        val scenario = FakeScenario(
            "probe-noise", motion = Motion.SlowMove, timestamps = true, dirtyFlags = true,
            probe = ProbeScenario(closeAtX = listOf(16_000L, 31_000L), closedMs = 2_000L, repeatClosed = true),
            faults = org.superdro.core.fakeadapter.FakeFaults(noise = true),
        )
        val record = ProbeHarness(this, listOf(scenario), files = files).run(ProbeTestId.T09, "probe-noise")
        compare(record, dir, listOf("open", "closed", "open again", "taps"))
    }

    /**
     * A probe clock with µs parts, as on a device: each read in one ms is 45 µs after the read
     * before. Thus the times of the blocks have µs parts, and a note comes after the event that it
     * tells.
     */
    private val deviceClock: (Long, Int) -> Long = { ms, read -> minOf(999L, ms * 7_919L % 500L + 45L * read) }

    private val probeNoise = FakeScenario(
        "probe-noise", motion = Motion.SlowMove, timestamps = true, dirtyFlags = true,
        probe = ProbeScenario(closeAtX = listOf(16_000L, 31_000L), closedMs = 2_000L, repeatClosed = true),
        faults = org.superdro.core.fakeadapter.FakeFaults(noise = true),
    )

    @Test
    fun timesWithMicrosecondPartsGiveTheSameReports() = runTest {
        if (!available()) return@runTest
        val (dir, files) = folder()
        val t14 = ProbeHarness(this, files = files, fraction = deviceClock).run(ProbeTestId.T14, "still")
        compare(t14, dir, listOf("close, open again after 750 ms", "second link, first link silent"))
        val t09 = ProbeHarness(this, listOf(probeNoise), files = files, fraction = deviceClock).run(ProbeTestId.T09, "probe-noise")
        compare(t09, dir, listOf("open", "closed", "open again", "taps"))
        val t05 = ProbeHarness(this, files = files, fraction = deviceClock).run(ProbeTestId.T05, "slow-move", TestOptions(durationS = 10.0))
        compare(t05, dir, listOf("open, no writes", "BEL on", "BEL off", "BEL on again"))
    }

    @Test
    fun theNewFakeScenariosGiveTheSameCounts() = runTest {
        if (!available()) return@runTest
        val (dir, files) = folder()
        val t09 = ProbeHarness(this, files = files, fraction = deviceClock).run(ProbeTestId.T09, "probe-timed")
        compare(t09, dir, listOf("open", "closed", "open again", "taps"))
        val t11 = ProbeHarness(this, files = files, fraction = deviceClock).run(ProbeTestId.T11, "ref-timeout", TestOptions(axis = 'x'))
        compare(t11, dir, listOf("ref no motion"))
    }

    @Test
    fun theConsoleStatisticsGiveTheSameCounts() = runTest {
        if (!available()) return@runTest
        val (dir, files) = folder()
        val h = ProbeHarness(this, files = files)
        val console = ProbeConsole(backgroundScope, h.env) { h.records += it }
        console.start(h.link("still"), TestOptions())
        kotlinx.coroutines.delay(3_000)
        console.submit("quit")
        console.join()
        val log = File(dir, checkNotNull(h.records.single().logFile))
        val notes = log.readLines().filter { " NOTE " in it }.map { it.substringAfter(" NOTE ") }
        val app = block(notes, "Phase 'all':")
        assertTrue(app.isNotEmpty(), notes.joinToString("\n"))
        assertEquals(app, analyzedPhase(analyze(log), "all"))
    }

    @Test
    fun t02CommandWritesAreTxLines() = runTest {
        if (!available()) return@runTest
        val (dir, files) = folder()
        val record = ProbeHarness(this, files = files).run(ProbeTestId.T02, "commands")
        val log = File(dir, checkNotNull(record.logFile))
        val output = analyze(log)
        // 5 "id" queries, 6 #get_mode queries, and 4 secondary queries: 15 command writes.
        val all = output.first { it.startsWith("Phase 'all':") }
        assertTrue(all.contains(" 15 command writes, "), all)
        val bel = log.readLines().count { it.contains(" TX       1 07 | .") }
        assertTrue(all.endsWith(" $bel BEL bytes."), all)
        assertTrue(output.first { it.startsWith("Phase 'id group':") }.contains(" 5 command writes,"), output.joinToString("\n"))
        assertTrue(output.first { it.startsWith("Phase 'inputs group':") }.contains(" 6 command writes,"), output.joinToString("\n"))
        assertTrue(output.first { it.startsWith("Phase 'secondary group':") }.contains(" 4 command writes,"), output.joinToString("\n"))
    }

    @Test
    fun t14SecondLinkBlocksAreRx2Lines() = runTest {
        if (!available()) return@runTest
        val (dir, files) = folder()
        val record = ProbeHarness(this, files = files).run(ProbeTestId.T14, "still")
        val log = File(dir, checkNotNull(record.logFile))
        val lines = log.readLines()
        assertTrue(lines.any { it.contains(" RX2  ") }, "The log has no RX2 line.")
        // The analysis counts only the RX lines of the first link. The phase "close, open again after 750 ms" has a new link.
        compare(record, dir, listOf("close, open again after 750 ms", "second link, first link silent"))
        assertTrue(lines.count { it.contains(" NOTE link open: ") } == 2)
    }
}
