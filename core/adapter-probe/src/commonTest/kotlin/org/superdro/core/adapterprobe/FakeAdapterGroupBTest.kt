package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.superdro.core.fakeadapter.CommandAnswers
import org.superdro.core.fakeadapter.FakeScenario

/** The tests of the groups B, C, and D (T10 to T16) against the SuperDRO fake adapter, in virtual time. */
class FakeAdapterGroupBTest {

    private fun text(record: RunRecord): String = record.summary.joinToString("\n")

    @Test
    fun t10AcceptsTheCommandsWithoutGetSig() = runTest {
        val h = ProbeHarness(this)
        val record = h.run(ProbeTestId.T10, "commands", TestOptions(axis = 'x'))
        val lines = record.summary
        assertEquals("Power cycle before the test, with no link of the other DRO app: yes.", lines[0])
        assertEquals("Q4: #set_mode without #get_sig: ACCEPTED. Answer: \$060;. Read-back mode: 2.", lines[1], text(record))
        assertTrue(lines[2].startsWith("Q4: #clear_counter without #get_sig: ACKNOWLEDGED. Answer: \$0aa3;. Later messages: none. X before 10000, after 0."), lines[2])
        // The prompts before #set_mode and #clear_counter are typed confirmations, and the first shows the current mode.
        val confirmations = h.prompts.filter { it.kind == PromptKind.TypedYes }
        assertEquals(listOf("#set_mode:x,2;", "#clear_counter:x;"), confirmations.map { it.command })
        assertEquals("2 (Quadrature A/B)", confirmations.first().currentMode)
        // No #get_sig in T10.
        assertTrue(h.factory.adapter("commands")!!.received.none { it.startsWith("#get_sig") })
        assertEquals(listOf("#set_mode:x,2", "#get_mode:x", "#clear_counter:x"), h.factory.adapter("commands")!!.received.drop(11))
    }

    @Test
    fun t10CancelsWithoutTheTypedYes() = runTest {
        val h = ProbeHarness(this)
        h.typedYes = "no"
        val record = h.run(ProbeTestId.T10, "commands", TestOptions(axis = 'x'))
        assertTrue(record.summary.none { it.startsWith("Q4:") }, text(record))
        assertTrue(h.factory.adapter("commands")!!.received.none { it.startsWith("#set_mode") || it.startsWith("#clear_counter") })
        assertTrue(h.files.let { it as MemoryProbeFiles }.runs.single().logLines.any { it.endsWith("NOTE Cancelled. The tool did not send #set_mode:x,2;.") })
    }

    @Test
    fun t10ReportsAnErrorOfSetMode() = runTest {
        // The fake adapter answers each command with !0a21; (Bad data).
        val record = ProbeHarness(this).run(ProbeTestId.T10, "bad-data", TestOptions(axis = 'x'))
        assertTrue(record.summary.contains("No valid #get_mode:x; answer. The tool does not send #set_mode."), text(record))
        assertTrue(record.summary.any { it.startsWith("Q4: #clear_counter without #get_sig: NOT acknowledged. Answer: !0a21;.") }, text(record))
    }

    @Test
    fun t11SeesTheEndOfTheSearch() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T11, "commands", TestOptions(axis = 'x'))
        val lines = record.summary
        assertTrue(lines[0].startsWith("Run 'no motion': answer \$78a0; after "), text(record))
        assertTrue(lines[1].startsWith("  End: \$78a1; (Finished) 2.00 s after the start answer. Other messages: none."), text(record))
        assertEquals("Q13: the search found the mark (Finished). Do a run with no motion to measure the time limit.", lines[3])
    }

    @Test
    fun t11NeedsAReferenceChannel() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T11, "commands", TestOptions(axis = 'u'))
        assertEquals(listOf("T11 needs --axis x, y, z, or w (the protocol defines #ref_axis only for these)."), record.summary)
    }

    /** A slow search: the fake adapter answers `$78a1;` 15 s after `$78a0;`. */
    private val slowScenario = FakeScenario("slow-reference", answers = CommandAnswers(referenceFindMs = 15_000L))

    @Test
    fun t11MeasuresTheTimeOfTheSearch() = runTest {
        val record = ProbeHarness(this, listOf(slowScenario)).run(ProbeTestId.T11, "slow-reference", TestOptions(axis = 'x'))
        assertTrue(record.summary[1].startsWith("  End: \$78a1; (Finished) 15.00 s after the start answer."), text(record))
    }

    @Test
    fun t12SeesTheRestart() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T12, "commands")
        val lines = record.summary
        assertEquals("Ack of #reboot:1000; after 0.0 ms.", lines[0], text(record))
        assertTrue(lines[1].startsWith("Q13: the stream stopped "), text(record))
        assertTrue(lines.any { it.startsWith("The link closed 1000.0 ms after the ack.") }, text(record))
        assertTrue(lines.any { it.startsWith("Open again: success after 2 attempts,") }, text(record))
        assertTrue(lines.any { it.startsWith("First valid message after the restart: ") && it.endsWith(" ms after the ack.") }, text(record))
        assertTrue(lines.any { it.startsWith("Uptime before ") && it.endsWith(": the adapter restarted.") }, text(record))
        assertTrue(lines.contains("Q41: counts before and after the restart:"), text(record))
    }

    @Test
    fun t13ComparesTheCountsAfterAReconnect() = runTest {
        val h = ProbeHarness(this)
        val record = h.run(ProbeTestId.T13, "commands")
        val lines = record.summary
        assertTrue(lines[0].startsWith("Uptime before "), text(record))
        assertEquals("Q41:  key   start        after a reconnect   after a power cycle", lines[1])
        assertEquals("      X    10000        10000               -", lines[2])
        assertEquals("Q41: after a reconnect: the counts are the same.", lines.last())
        assertEquals(PromptKind.NextOrSkip, h.prompts.single().kind)
    }

    @Test
    fun t14OpensAgainAfter750MsAndOpensASecondLink() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T14, "commands")
        val lines = record.summary
        assertEquals(
            "Phase 1 (NO_DATA recovery, DC20: close, then open after 750 ms): open success after 1 attempts; first valid message " +
                "0.0 ms after the open.",
            lines[0],
            text(record),
        )
        assertTrue(lines[1].startsWith("Q50: the second link opened in 0.0 ms. It got "), text(record))
        assertEquals("Q50: after the second link closed, the first link is open, and it gets data.", lines[2])
    }

    @Test
    fun t15OpensAgainAfterTheBreak() = runTest {
        val h = ProbeHarness(this)
        val record = h.run(ProbeTestId.T15, "still")
        val lines = record.summary
        assertTrue(lines[0].startsWith("Link broken at "), text(record))
        assertTrue(lines[1].startsWith("Q50: open again: success after 1 attempts, "), text(record))
        assertEquals("Q50: first valid message on the new link: 0.0 ms after the open.", lines[2])
        assertEquals(2, h.prompts.count { it.kind == PromptKind.Next })
    }

    @Test
    fun t16SendsGetSigOnce() = runTest {
        val h = ProbeHarness(this)
        val record = h.run(ProbeTestId.T16, "commands")
        assertEquals("#get_sig: ack 0.0 ms, answer - ms, answer kind none, Base64 length -.", record.summary[0], text(record))
        assertEquals("Q13: an RSA-3072 signature has 512 Base64 characters. The log has the answer.", record.summary[1])
        val sent = h.factory.adapter("commands")!!.received.filter { it.startsWith("#get_sig") }
        // 2026-10-12 08:00 UTC plus about 4 s of the test: the seconds since 2012-01-12 12:00 UTC.
        assertEquals(1, sent.size)
        assertTrue(sent.single().startsWith("#get_sig:4654"), sent.single())
        assertTrue(sent.single().endsWith(",1"))
    }
}
