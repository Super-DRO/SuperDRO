package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.superdro.core.fakeadapter.FakeScenario
import org.superdro.core.fakeadapter.Motion
import org.superdro.core.fakeadapter.ProbeScenario

/** The tests of group A (T01 to T09) against the SuperDRO fake adapter, in virtual time. */
class FakeAdapterGroupATest {

    private fun MemoryProbeFiles.log(record: RunRecord): List<String> = runs.first { it.logName == record.logFile }.logLines

    @Test
    fun t01WritesNothingBeforeTheBelPhase() = runTest {
        val files = MemoryProbeFiles()
        val h = ProbeHarness(this, files = files)
        val record = h.run(ProbeTestId.T01, "still")
        assertEquals(RunStatus.Done, record.status)
        val lines = record.summary
        assertEquals("Link: fake adapter, scenario still.", lines[0])
        assertEquals("First byte 0.0 ms after the open. First valid message: 0.0 ms after the open.", lines[1])
        assertEquals("Checklist item 1: the adapter sends valid messages with no write from the app: yes.", lines[2])
        assertEquals("Q19: message forms: numeric. Only numeric readouts: this agrees with the one-way (legacy) dialect.", lines[3])
        assertTrue(lines.any { it.startsWith("Phase 'no writes': 5.0 s,") && it.endsWith("0 command writes, 0 BEL bytes.") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("Phase 'keep-alive on': 10.0 s,") && it.endsWith("0 command writes, 20 BEL bytes.") }, lines.joinToString("\n"))
        // The log: the header, a TX line only for the BEL byte, and no TX before the phase "keep-alive on".
        val log = files.log(record)
        assertEquals("# adapter_probe.py log, format 1", log[0])
        assertTrue(log[1].startsWith("# start (UTC): 2026-10-12T08:00:00Z; mode: T01-link; label: -"))
        assertEquals("# keep-alive at start: off; time scale: 1.0", log[3])
        val belPhase = log.indexOfFirst { it.endsWith("NOTE phase keep-alive on") }
        assertTrue(log.take(belPhase).none { it.contains(" TX ") })
        assertTrue(log.drop(belPhase).filter { it.contains(" TX ") }.all { it.endsWith(" TX       1 07 | .") })
        assertEquals("# end", log.last())
        assertEquals("20261012-080000-T01-link-fake.log", record.logFile)
    }

    @Test
    fun t02SendsTheConnectSequenceThroughTheQueue() = runTest {
        val h = ProbeHarness(this, paired = listOf(PairedAdapter("24:6F:28:A1:B2:C5", "mill adapter")))
        val record = h.run(ProbeTestId.T02, "commands")
        val lines = record.summary
        assertEquals(
            "Connect sequence of 3.32.00 (five \"#get:\" queries, then six #get_mode queries), first query 2000.0 ms after the first valid message.",
            lines[0],
        )
        assertTrue(lines[1].startsWith("Checklist item 10: the five id values came "), lines[1])
        assertTrue(lines[1].endsWith("(limit: 5000 ms): in the limit."), lines[1])
        assertEquals("  request          ack ms  answer ms  answer", lines[2])
        assertEquals(
            "  mac                 0.0        0.0  ?05246f28a1b2c3;  MAC + 2 = 24:6F:28:A1:B2:C5 (display with no carry: 24:6F:28:A1:B2:C5)",
            lines[3],
        )
        assertTrue(lines.any { it == "Record these input modes before a set_mode test: x=2 y=2 z=2 w=2 u=8 v=4." }, lines.joinToString("\n"))
        assertTrue(
            lines.any { it == "Q51: reported MAC 246f28a1b2c3 + 2 = 24:6F:28:A1:B2:C5. Bluetooth address 24:6F:28:A1:B2:C5 (paired device \"mill adapter\"): the same." },
            lines.joinToString("\n"),
        )
        assertEquals("tda-fake", record.adapter.model)
        assertEquals("1.0.0", record.adapter.version)
        assertEquals(mapOf("x" to 2, "y" to 2, "z" to 2, "w" to 2, "u" to 8, "v" to 4), record.adapter.modes)
        // The fake adapter got the start queries in the order x y z w u v, then the secondary group.
        val received = h.factory.adapter("commands")!!.received
        assertEquals(
            listOf(
                "#get:mac", "#get:uuid", "#get:features", "#get:uptime", "#get:model",
                "#get_mode:x", "#get_mode:y", "#get_mode:z", "#get_mode:w", "#get_mode:u", "#get_mode:v",
                "#get:name", "#get:version", "#get:build", "#get:date",
            ),
            received,
        )
    }

    @Test
    fun t02WithNoPairedDeviceAsksForTheAddress() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T02, "commands")
        assertTrue(
            record.summary.any {
                it == "Q51: reported MAC 246f28a1b2c3 + 2 = 24:6F:28:A1:B2:C5. No paired Bluetooth device has this address. Compare it with " +
                    "the Bluetooth address of the adapter, or select the address in the test options."
            },
        )
        val option = ProbeHarness(this).run(ProbeTestId.T02, "commands", TestOptions(btAddress = "24:6f:28:a1:b2:c4"))
        assertTrue(option.summary.any { it == "Q51: reported MAC 246f28a1b2c3 + 2 = 24:6F:28:A1:B2:C5. Bluetooth address 24:6F:28:A1:B2:C4: NOT the same." })
    }

    @Test
    fun t02OnALegacyAdapterGetsNoAnswer() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T02, "legacy")
        assertTrue(
            record.summary.contains(
                "Q19: the adapter answered no query. This agrees with a one-way (legacy) adapter, or with an adapter that ignores the commands on this link.",
            ),
            record.summary.joinToString("\n"),
        )
    }

    @Test
    fun t03FindsRepeatsAtRest() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T03, "still")
        val lines = record.summary
        assertTrue(lines.first().startsWith("Phase 'idle': 30.0 s,"), lines.first())
        assertTrue(
            lines.contains(
                "Q16: X: 599 readouts in 30.0 s (19.97/s), 1 values, 598 repeats of the last value, median period 50.0 ms. The adapter sends " +
                    "this input also when the count does not change.",
            ),
            lines.joinToString("\n"),
        )
        assertTrue(lines.contains("Q18: 'Q' messages: 0. '.' messages: 0. '\$32' messages: 0."))
        assertTrue(lines.contains("Q37: '@' time stamps: none."))
        assertTrue(lines.contains("Q19: message forms: numeric. Only numeric readouts: this agrees with the one-way (legacy) dialect."))
    }

    @Test
    fun t04FindsThePairInOneGroup() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T04, "two-inputs", TestOptions(pair = "X" to "W", durationS = 10.0))
        val lines = record.summary
        assertTrue(lines.contains("    Pair X + W: both in the same group in 100.0 % of the 199 groups with X or W."), lines.joinToString("\n"))
        assertTrue(lines.contains("Q15: '@' rate 1000.00 units/s, probable unit ms."), lines.joinToString("\n"))
        assertTrue(lines.contains("Q40: see the line 'Pair X + W' above. 100 % means that the two inputs get equal time stamps."))
    }

    @Test
    fun t05TurnsTheBelByteOff() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T05, "still", TestOptions(durationS = 30.0))
        val lines = record.summary
        assertEquals("  phase              time s  valid/s  max gap ms  link", lines[0], lines.take(8).joinToString("\n"))
        // The fake adapter sends X, Y, and Z 20 times each second: 60 valid messages each second.
        assertEquals("  open, no writes      10.0    60.00        50.0  open", lines[1], lines.take(8).joinToString("\n"))
        assertEquals("  BEL on               20.0    60.00        50.0  open", lines[2])
        assertEquals("  BEL off              30.0    60.00        50.0  open", lines[3])
        assertEquals("  BEL on again         20.0    60.00        50.0  open", lines[4])
        assertEquals(
            "Q14: the data rate stayed 60.00/s -> 60.00/s without the BEL byte for 30 s. The adapter does not need the BEL byte on this link.",
            lines[5],
        )
        assertTrue(lines.any { it.startsWith("Phase 'BEL on': 20.0 s,") && it.endsWith("0 command writes, 40 BEL bytes.") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("Phase 'BEL off': 30.0 s,") && it.endsWith("0 command writes, 0 BEL bytes.") }, lines.joinToString("\n"))
    }

    @Test
    fun t06FindsNoEffectOfTheQuoteBytes() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T06, "commands")
        val lines = record.summary
        assertEquals(
            "  \"#get:mac;\"    protocol form, with the two quote bytes    ack 0.0     answer ?05246f28a1b2c3; (same as the protocol form)",
            lines[0],
        )
        assertEquals("  #get:mac;      no quote bytes                             ack 0.0     answer ?05246f28a1b2c3; (same as the protocol form)", lines[1])
        assertEquals("Q20: the adapter gives the same answer with and without the quote bytes. It ignores them.", lines[4])
    }

    @Test
    fun t07FindsTheUptimeUnit() = runTest {
        val record = ProbeHarness(this).run(ProbeTestId.T07, "commands")
        val lines = record.summary
        assertTrue(lines[0].startsWith("Uptime answers: 7 of 7. Values: "), lines[0])
        // The fake adapter gives the uptime in seconds.
        assertEquals("Q15: uptime rate 1.000 units/s, probable unit: s.", lines[1])
    }

    @Test
    fun t08ComparesTheTValueWithTheSpeed() = runTest {
        val h = ProbeHarness(this)
        h.numbers += listOf("1200", "1")
        val record = h.run(ProbeTestId.T08, "lathe")
        val lines = record.summary
        assertEquals("T: 399 messages (19.95/s), mean 1200.0, last value 1200, 1 values (min 1200, max 1200).", lines[0])
        assertEquals("Q15: T / RPM = 1.0000.", lines[1])
        assertEquals("  T is RPM                             ratio 1.000  <- agrees (5 %)", lines[2])
        assertEquals("  T is pulses for each minute          ratio 1.000  <- agrees (5 %)", lines[3])
        assertEquals("The present formula (Q15): RPM = T / (pulses for each revolution).", lines[7])
        assertEquals(
            listOf(
                "Type the measured speed in RPM (leave the field empty to skip): ",
                "Type the pulses for each revolution of the sensor (leave the field empty to skip): ",
            ),
            h.prompts.filter { it.kind == PromptKind.Number }.map { it.text },
        )
    }

    /** A probe that closes for 3 s at four X counts: two closures in the step "closed", two in the step "taps". */
    private val probeScenario = FakeScenario(
        "probe-test", motion = Motion.SlowMove, timestamps = true,
        probe = ProbeScenario(closeAtX = listOf(11_000L, 16_000L, 19_000L, 31_000L, 35_000L), closedMs = 3_000L, repeatClosed = true),
    )

    @Test
    fun t09RecordsTheProbe() = runTest {
        val record = ProbeHarness(this, listOf(probeScenario)).run(ProbeTestId.T09, "probe-test")
        val lines = record.summary
        assertTrue(lines[0].startsWith("  phase open         P0: "), lines[0])
        assertEquals("Q17: longest closed run: 2.9 s, 60 messages P1, median repeat 50.0 ms, longest repeat 50.0 ms.", lines[5])
        // The closure at X 11000 is in the step "open", so the rest value is also P1.
        assertEquals("Q39: value at rest P1, value with a closed contact P1. The values are the same: check the wires.", lines[4])
        assertEquals("  phase closed       P0:     2  P1:   120", lines[1])
        assertTrue(
            lines.contains("Q17: the adapter sends P1 again while the contact stays closed. The longest repeat is less than the probe watchdog period (1000 ms)."),
            lines.joinToString("\n"),
        )
        assertEquals("Q39: taps: 3 changes, shortest time between two changes 1000.0 ms, changes after less than 5 ms: 0.", lines[7])
        assertTrue(lines.contains("Q39: no change after less than 5 ms: the adapter debounces, or the contact did not bounce."))
        assertTrue(lines.contains("Q18: 0 Q messages in the test."))
        assertTrue(lines.any { it.startsWith("Q37: ") && it.contains(" edges of P.") }, lines.joinToString("\n"))
    }
}
