package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** T09 and T11 of the app on the fake scenarios "probe-timed" and "ref-timeout". */
class FakeAdapterNewScenariosTest {

    private fun text(record: RunRecord): String = record.summary.joinToString("\n")

    @Test
    fun t09OnTheTimedProbeHasProbeMessagesInEachStep() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T09, "probe-timed")
        val lines = record.summary
        assertEquals("  phase open         P0:    99  P1:     0", lines[0], text(record))
        assertEquals("  phase closed       P0:    20  P1:   180", lines[1], text(record))
        assertEquals("  phase open again   P0:   100  P1:     0", lines[2], text(record))
        assertEquals("  phase taps         P0:   100  P1:   100", lines[3], text(record))
        assertEquals("Q39: value at rest P0, value with a closed contact P1.", lines[4])
        assertEquals("Q17: longest closed run: 8.9 s, 180 messages P1, median repeat 50.0 ms, longest repeat 50.0 ms.", lines[5])
        assertTrue(lines.contains("Q39: taps: 19 changes, shortest time between two changes 500.0 ms, changes after less than 5 ms: 0."), text(record))
    }

    @Test
    fun t11OnASearchWithNoMarkEndsWithUserTimeout() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T11, "ref-timeout", TestOptions(axis = 'x'))
        val lines = record.summary
        assertTrue(lines[0].startsWith("Run 'no motion': answer \$78a0; after "), text(record))
        assertEquals("  End: \$7816; (User timeout) 15.00 s after the start answer. Other messages: none.", lines[1])
        assertEquals("Q13: the search ended with User timeout after 15.0 s. This agrees with '15' = the time limit in seconds.", lines[3])
    }
}
