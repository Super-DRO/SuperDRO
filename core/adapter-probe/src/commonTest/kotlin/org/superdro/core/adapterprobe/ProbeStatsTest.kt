package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The statistics and the summary lines of the tool. The expected lines are the output of the
 * `Stats` class and the line functions of `adapter_probe.py` for the same stream (made with Python
 * 3 from the script).
 */
class ProbeStatsTest {

    private fun blocks(): List<Pair<Double, ByteArray>> {
        val out = ArrayList<Pair<Double, ByteArray>>()
        var adapter = 0x1000L
        var x = 100
        for (i in 0 until 40) {
            val t = (50 * i) / 1000.0
            adapter += 50
            x += if (i % 4 != 0) 3 else 0
            val p = if (i in 10 until 20) 1 else 0
            out += t to "@${adapter.toString(16)};X$x;W${x - 1};Y-20;P$p;".encodeToByteArray()
        }
        out += 2.05 to "\$0aa3;?0530aea4a1b2c3;".encodeToByteArray()
        out += 2.10 to (byteArrayOf() + "X12 3;".encodeToByteArray() + byteArrayOf(0x0D, 0x0A, 0x00, 0xFF.toByte()))
        out += 2.15 to "!0a21;\$3205;.3;".encodeToByteArray()
        out += 2.20 to "?64QUJD;\$5;junk".encodeToByteArray()
        out += 10.5 to "X200;Y-20;".encodeToByteArray()
        out += 10.55 to "T1500;".encodeToByteArray()
        return out
    }

    private fun stats(): PhaseStats {
        val st = PhaseStats("synthetic", 0.0, keep = true)
        val feed = MessageFeed()
        for ((t, data) in blocks()) {
            val result = feed.feed(data, t)
            st.addRx(data.size, result.dropped, t)
            result.messages.forEach(st::add)
            if (result.rejected.isNotEmpty()) st.addRejected(result.rejected)
        }
        st.addTx(11, bel = false, t = 2.0)
        st.addTx(1, bel = true, t = 2.5)
        st.close(11.0)
        return st
    }

    @Test
    fun phaseReportLinesAgreeWithTheTool() {
        val expected = listOf(
            "Phase 'synthetic': 11.0 s, 1037 bytes in 46 blocks, 169 valid messages (15.4/s), 1 command writes, 1 BEL bytes.",
            "    key     count  rate/s  median ms   p95 ms    max ms  values  repeats  first -> last",
            "    X          41    3.73       50.0     50.0    8550.0      32        9  100 -> 200",
            "    Y          41    3.73       50.0     50.0    8550.0       1       40  -20 -> -20",
            "    W          40    3.64       50.0     50.0      50.0      31        9  99 -> 189",
            "    T           1    0.09          -        -         -       1        0  1500 -> 1500",
            "    .           1    0.09          -        -         -       1        0  3 -> 3",
            "    P          40    3.64       50.0     50.0      50.0       2       37  0 -> 0",
            "    @          40    3.64       50.0     50.0      50.0      40        0  0x1032 -> 0x17d0",
            "    \$           2    0.18      100.0    100.0     100.0       2        0  ",
            "    !           1    0.09          -        -         -       1        0  ",
            "    ?           2    0.18      150.0    150.0     150.0       2        0  ",
            "    Time stamps '@': 40, median period 50.0 ms, values 0x1032 -> 0x17d0, decreases 0.",
            "    '@' rate: 1000.00 units/s, probable unit: ms. Largest difference from a line: 0.0 ms of PC time.",
            "    Readout groups (readouts after one '@', else readouts in one received block): 40.",
            "      X Y W P                        97.5 %",
            "      X Y W T . P                     2.5 %",
            "    Pair X + W: both in the same group in 100.0 % of the 40 groups with X or W.",
            "    Longest time between two valid messages: 8300.0 ms. Times of more than 7 s (the NO_DATA limit): 1.",
            "    Dirty status: 1 '.' messages, 1 '\$32' messages.",
            "    Status, error, and info messages: 5 (first 5 below).",
            "          2050.0 ms  \$0aa3;                   \$ sub 0a RX, code a3 Received",
            "          2050.0 ms  ?0530aea4a1b2c3;         ?05 mac = '30aea4a1b2c3'",
            "          2150.0 ms  !0a21;                   ! sub 0a RX, code 21 Bad data",
            "          2150.0 ms  \$3205;                   \$ sub 32 Dirty Axes, code 5 Not supported",
            "          2200.0 ms  ?64QUJD;                 ?64 signature, 4 Base64 characters",
            "    Messages that are not valid: 3. Characters outside valid messages: 13. Examples: 'X12 3;' | '\$5;junk'",
            "    Bytes below 0x20 or above 0x7E (removed): 4.",
        )
        assertEquals(expected, stats().report("X" to "W"))
    }

    @Test
    fun specialReadoutAndFormLinesAgreeWithTheTool() {
        val st = stats()
        assertEquals(
            listOf("Q18: 'Q' messages: 0. '.' messages: 1. '\$32' messages: 1.", "Q37: '@' time stamps: 40.", "Q15: '@' rate 1000.00 units/s, probable unit ms."),
            Analysis.specialLines(st),
        )
        assertEquals(
            listOf(
                "Q16: X: 41 readouts in 11.0 s (3.73/s), 32 values, 9 repeats of the last value, median period 50.0 ms.",
                "Q16: Y: 41 readouts in 11.0 s (3.73/s), 1 values, 40 repeats of the last value, median period 50.0 ms. The adapter sends " +
                    "this input also when the count does not change.",
                "Q16: W: 40 readouts in 11.0 s (3.64/s), 31 values, 9 repeats of the last value, median period 50.0 ms.",
                "Q16: T: 1 readouts in 11.0 s (0.09/s), 1 values, 0 repeats of the last value, median period - ms.",
                "Q16: .: 1 readouts in 11.0 s (0.09/s), 1 values, 0 repeats of the last value, median period - ms.",
            ),
            Analysis.readoutLines(st, "synthetic"),
        )
        assertEquals("Q19: message forms: !, \$, ?, @, P/Q, numeric. Forms of the ESP32 two-way adapter are present.", Analysis.formsLine(st))
    }

    @Test
    fun edgeLinesAgreeWithTheTool() {
        assertEquals(
            listOf(
                "Q37: 2 edges of P. An '@' came directly before the edge in the same block at 0 edges. Messages near the first edges (key, " +
                    "time in ms from the edge):",
                "    edge 1 (P1): @(+0.0) X(+0.0) W(+0.0) Y(+0.0) P1(+0.0) @(+50.0) X(+50.0) W(+50.0) Y(+50.0) P1(+50.0) @(+100.0)",
                "    edge 2 (P0): @(+0.0) X(+0.0) W(+0.0) Y(+0.0) P0(+0.0) @(+50.0) X(+50.0) W(+50.0) Y(+50.0) P0(+50.0) @(+100.0)",
                "Q37: next X readout after an edge: median 50.0 ms (normal period 50.0 ms).",
                "Q37: next Y readout after an edge: median 50.0 ms (normal period 50.0 ms).",
                "Q37: next W readout after an edge: median 50.0 ms (normal period 50.0 ms).",
            ),
            Analysis.edgeLines(stats(), "P"),
        )
    }

    @Test
    fun medianPercentileAndLine() {
        assertEquals(2.5, Analysis.median(listOf(4.0, 1.0, 2.0, 3.0)))
        assertEquals(null, Analysis.median(emptyList()))
        // round(0.95 × 10) = round(9.5) = 10 (half to even).
        assertEquals(10.0, Analysis.pct((0..10).map { it.toDouble() }, 0.95))
        // round(0.95 × 30) = round(28.5) = 28.
        assertEquals(28.0, Analysis.pct((0..30).map { it.toDouble() }, 0.95))
        val fit = Analysis.fitLine(listOf(0.0 to 0.0, 1.0 to 1000.0, 2.0 to 2000.0))!!
        assertEquals(1000.0, fit.first, 1e-9)
        assertEquals(null, Analysis.fitLine(listOf(0.0 to 0.0, 1.0 to 1.0)))
        assertEquals(listOf(3.0 to 1L, 4.0 to 2L, 5.0 to 3L), Analysis.atPointsSegment(listOf(1.0 to 5L, 2.0 to 6L, 3.0 to 1L, 4.0 to 2L, 5.0 to 3L)))
    }
}
