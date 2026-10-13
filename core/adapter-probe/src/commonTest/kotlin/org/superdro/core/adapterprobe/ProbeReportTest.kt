package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The report of each adapter with one row for each question. */
class ProbeReportTest {

    private val tablet = TabletInfo("samsung", "SM-X230", "16", 36, "2026-09-01", "SuperDRO Probe 0.1.0")

    @Test
    fun theTableHasOneRowForEachQuestionOfThePlan() {
        assertEquals(
            listOf(
                "Q4", "Q13 (`15`)", "Q13 (`1000`)", "Q13 (`,1`)", "Q14", "Q15 (`@`)", "Q15 (`uptime`)", "Q15 (`T`)", "Q16", "Q17", "Q18",
                "Q19", "Q20", "Q37", "Q38", "Q39", "Q40", "Q41", "Q42", "Q49", "Q50", "Q51",
            ),
            ProbeReport.QUESTIONS.map { it.id },
        )
    }

    @Test
    fun reportOfTheFakeAdapter() = runTest {
        val h = ProbeHarness(this, paired = listOf(PairedAdapter("24:6F:28:A1:B2:C5", "mill adapter")))
        h.run(ProbeTestId.T02, "commands")
        h.run(ProbeTestId.T03, "commands", TestOptions(durationS = 5.0))
        h.run(ProbeTestId.T06, "commands")
        h.run(ProbeTestId.T10, "commands", TestOptions(axis = 'x'))
        val report = ProbeReport.build(h.records, tablet, listOf(PairedAdapter("24:6F:28:A1:B2:C5", "mill adapter")), ProbeHarness.WALL_START_MS)
        val lines = report.lines()
        assertEquals("# SuperDRO adapter probe report", lines[0])
        assertTrue(lines.contains("| Tablet model | samsung SM-X230 |"))
        assertTrue(lines.contains("| Android version | 16 (API 36) |"))
        assertTrue(lines.contains("| Security patch level | 2026-09-01 |"))
        // One adapter: T03 has no MAC, but the same link gave the MAC in T02.
        assertEquals(1, lines.count { it.startsWith("## Adapter ") })
        assertTrue(lines.contains("## Adapter 24:6F:28:A1:B2:C5"))
        assertTrue(lines.contains("| Model | tda-fake |"))
        assertTrue(lines.contains("| Firmware version | 1.0.0 |"))
        assertTrue(lines.contains("| Firmware date | 2026-01-01 00:00 UTC (0x6955b900) |"), report)
        assertTrue(lines.contains("| MAC | 246f28a1b2c3 |"))
        assertTrue(lines.contains("| MAC + 2 (with the carry) | 24:6F:28:A1:B2:C5 |"))
        assertTrue(lines.contains("| Bluetooth address | 24:6F:28:A1:B2:C5 (paired device \"mill adapter\") |"))
        assertTrue(lines.contains("| MAC + 2 check (Q51, AP11) | MAC + 2 = the Bluetooth address |"))
        assertTrue(lines.contains("| Input modes | x=2 y=2 z=2 w=2 u=8 v=4 |"))
        val q4 = lines.first { it.startsWith("| Q4 |") }
        assertTrue(q4.contains("Q4: #set_mode without #get_sig: ACCEPTED."), q4)
        assertTrue(q4.contains("The adapter accepts the configuration commands without #get_sig. DC15 is correct. F20.9 stays \"Not planned\"."), q4)
        assertTrue(q4.endsWith("| 11.2, DC15, F20.9 |"), q4)
        val q20 = lines.first { it.startsWith("| Q20 |") }
        assertTrue(q20.contains("The adapter ignores the quote bytes."), q20)
        val q51 = lines.first { it.startsWith("| Q51 |") }
        assertTrue(q51.contains("The identity rule of DC25 (MAC + 2 with the carry) is correct for this model."), q51)
        val q16 = lines.first { it.startsWith("| Q16 |") }
        assertTrue(q16.contains("Periodic readouts with no change"), q16)
        // A question with no run gives the limit of the question.
        val q19 = lines.first { it.startsWith("| Q19 |") }
        assertTrue(q19.contains("Q19: message forms: numeric."), q19)
        val q13 = lines.first { it.startsWith("| Q13 (`15`) |") }
        assertTrue(q13.contains("Open: no result of T11. Limit (plan, 1.2): The tests give evidence for the function of each constant."), q13)
        assertTrue(lines.any { it.startsWith("| T02 | fake | 2026-10-12 08:00 | 20261012-080000-T02-identity-fake.log |") }, report)
        assertTrue(lines.contains("#### T10 on fake, 2026-10-12 08:01 UTC, 20261012-080102-T10-no-sig-fake.log") || lines.any { it.startsWith("#### T10 on fake,") })
    }

    @Test
    fun tabletQuestionsAreInTheTabletPart() {
        val tt2 = RunRecord(
            "TT2", "usb", "camera", "usb:camera", ProbeHarness.WALL_START_MS, RunStatus.Done,
            listOf("TT2: tablet x", "Q49: no dialog after the first \"use by default\", also after a restart: the attach filter gives a permanent permission. F28.7 can use the attach filter."),
        )
        val report = ProbeReport.build(listOf(tt2), tablet, emptyList(), ProbeHarness.WALL_START_MS)
        val q49 = report.lines().first { it.startsWith("| Q49 |") }
        assertTrue(q49.contains("| no dialog after the first \"use by default\", also after a restart"), q49)
        val q42 = report.lines().first { it.startsWith("| Q42 |") }
        assertTrue(q42.contains("Open: no result of TT1."), q42)
        assertTrue(report.contains("No adapter test ran."))
    }

    @Test
    fun identityOfARun() {
        val bt = RunRecord("T03", "bt", "BT", "bt:24:6F:28:A1:B2:C5", 0L, RunStatus.Done, emptyList())
        assertEquals("24:6F:28:A1:B2:C5", ProbeReport.identity(bt, emptyMap()))
        val usb = RunRecord("T03", "usb", "USB", "usb:10C4:EA60:0:", 0L, RunStatus.Done, emptyList())
        assertEquals("link usb:10C4:EA60:0:", ProbeReport.identity(usb, emptyMap()))
        assertEquals("24:6F:28:A1:B2:C5", ProbeReport.identity(usb, mapOf("usb:10C4:EA60:0:" to "24:6F:28:A1:B2:C5")))
        assertEquals("24:6F:28:A1:B2:C5", ProbeReport.identity(usb.copy(adapter = AdapterSnapshot(mac = "246f28a1b2c3")), emptyMap()))
    }

    @Test
    fun theShortResultIsAnAnswerLine() {
        fun record(test: String, vararg lines: String) = RunRecord(test, "fake", "fake", "fake:commands", 0L, RunStatus.Done, lines.toList())
        val t13 = record(
            "T13", "Uptime before 54, after the power cycle 78.", "Q41:  key   start        after a reconnect   after a power cycle",
            "      X    0            0                   0", "Q41: after a reconnect: the counts are the same.",
        )
        assertEquals("Q41: after a reconnect: the counts are the same.", t13.shortResult)
        val t16 = record(
            "T16", "#get_sig: ack 0.2 ms, answer - ms, answer kind none, Base64 length -.",
            "Q13: an RSA-3072 signature has 512 Base64 characters. The log has the answer.",
        )
        assertEquals("#get_sig: ack 0.2 ms, answer - ms, answer kind none, Base64 length -.", t16.shortResult)
        val t12 = record(
            "T12", "Ack of #reboot:1000; after 0.2 ms.",
            "Q13: the stream stopped 997.5 ms after the ack. The first block after the stop came 2010.2 ms after the ack.",
        )
        assertEquals("Q13: the stream stopped 997.5 ms after the ack. The first block after the stop came 2010.2 ms after the ack.", t12.shortResult)
        assertEquals(null, record("T01").shortResult)
    }

    @Test
    fun theReportShowsTheShortResultOfEachRun() = runTest {
        val h = ProbeHarness(this)
        h.run(ProbeTestId.T13, "commands")
        h.run(ProbeTestId.T16, "commands")
        val lines = ProbeReport.build(h.records, TabletInfo("x", "y", "16", 36, "", ""), emptyList(), ProbeHarness.WALL_START_MS).lines()
        val t13 = lines.single { it.startsWith("| T13 | fake |") }
        assertTrue(t13.endsWith(" | Q41: after a reconnect: the counts are the same. | Done |"), t13)
        val t16 = lines.single { it.startsWith("| T16 | fake |") }
        assertTrue(t16.endsWith(" | #get_sig: ack 0.0 ms, answer - ms, answer kind none, Base64 length -. | Done |"), t16)
    }

    @Test
    fun theReportUsesTheBluetoothAddressOfTheT02Options() = runTest {
        val h = ProbeHarness(this)
        h.run(ProbeTestId.T02, "commands", TestOptions(btAddress = "24:6f:28:a1:b2:c5"))
        assertEquals("24:6f:28:a1:b2:c5", h.records.single().btAddress)
        val lines = ProbeReport.build(h.records, TabletInfo("x", "y", "16", 36, "", ""), emptyList(), ProbeHarness.WALL_START_MS).lines()
        assertTrue(lines.contains("| Bluetooth address | 24:6F:28:A1:B2:C5 (the Bluetooth address of the T02 options) |"), lines.joinToString("\n"))
        assertTrue(lines.contains("| MAC + 2 check (Q51, AP11) | MAC + 2 = the Bluetooth address |"), lines.joinToString("\n"))
        // The line Q51 of the summary of T02 gives the same result.
        assertTrue(h.records.single().summary.contains("Q51: reported MAC 246f28a1b2c3 + 2 = 24:6F:28:A1:B2:C5. Bluetooth address 24:6F:28:A1:B2:C5: the same."))
    }
}
