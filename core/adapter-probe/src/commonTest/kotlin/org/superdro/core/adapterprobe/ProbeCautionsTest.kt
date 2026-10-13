package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The warnings, the cautions, the steps, and the prompts in the probe app: the Test screen, the
 * tablet test TT1, and the prompts of T10, T13, and T15.
 */
class ProbeCautionsTest {

    private fun MemoryProbeFiles.log(record: RunRecord): List<String> = runs.first { it.logName == record.logFile }.logLines

    @Test
    fun theTestScreenHasTheWarningsAndTheCautionsOfThePlan() {
        assertEquals(
            listOf(
                "WARNING: Keep your hands, your clothes, and the cables away from the spindle. Use an optical tachometer and read it " +
                    "from a safe distance.",
            ),
            ProbeTexts.testCautions(ProbeTestId.T08),
        )
        assertEquals(
            listOf("CAUTION: Make sure that the probe tip and the part cannot move into each other. Move the axis by hand only."),
            ProbeTexts.testCautions(ProbeTestId.T09),
        )
        assertEquals(
            listOf("CAUTION: Do T02 first and record the input modes. You need them if the mode changes."),
            ProbeTexts.testCautions(ProbeTestId.T10),
        )
        // The caution of T13 is in the steps, not at the top.
        assertEquals(emptyList(), ProbeTexts.testCautions(ProbeTestId.T13))
        assertTrue("CAUTION: A power cycle can set the counts to zero. The machine datums can become incorrect." in ProbeTexts.testSteps(ProbeTestId.T13))
        assertEquals(
            listOf(
                "CAUTION: When you turn off Bluetooth, a Bluetooth keyboard or mouse of the tablet also disconnects. Use the touch " +
                    "screen for this test.",
                "CAUTION: When you turn off Wi-Fi, the tablet disconnects from the network. If you control the tablet through the " +
                    "network (for example with wireless debugging), you lose that control.",
            ),
            ProbeTexts.testCautions(ProbeTestId.T15),
        )
        val withCautions = setOf(ProbeTestId.T08, ProbeTestId.T09, ProbeTestId.T10, ProbeTestId.T15)
        assertEquals(withCautions, ProbeTestId.entries.filter { ProbeTexts.testCautions(it).isNotEmpty() }.toSet())
    }

    @Test
    fun tt1HasTheStepsOfThePlan() {
        assertEquals(
            listOf(
                "Do not give the local network permission before this test. TT1 measures the link before and after the permission request.",
                "Disconnect the PC tool and the other DRO app from the adapter.",
                "On the Link screen, select Wi-Fi TCP. Type the IP address and the TCP port of the adapter.",
                "Push Start. The app records the Android version, the security patch level, and the state of the permission. Then it does " +
                    "T01 on the TCP link.",
                "When the app asks for the permission, push Next. Then answer the Android dialog.",
                "The app does T01 again. Read the line Q42 of the summary.",
            ),
            TabletTests.TT1_STEPS,
        )
    }

    @Test
    fun t13ShowsTheCautionInThePromptOfThePowerCycle() = runTest {
        val h = ProbeHarness(this)
        val record = h.run(ProbeTestId.T13, "commands")
        val prompt = h.prompts.single { it.kind == PromptKind.NextOrSkip }
        assertEquals(ProbeTexts.CAUTION_POWER_CYCLE, prompt.caution)
        val log = (h.files as MemoryProbeFiles).log(record)
        val caution = log.indexOfFirst { it.endsWith(" NOTE CAUTION: A power cycle can set the counts to zero. The machine datums can become incorrect.") }
        val question = log.indexOfFirst { it.contains(" NOTE question: Step 2: turn the adapter power off.") }
        assertTrue(caution in 0 until question, "The caution comes before the question: $caution, $question")
    }

    @Test
    fun t15WaitsTwentySecondsWithTheLinkBroken() = runTest {
        val h = ProbeHarness(this)
        val record = h.run(ProbeTestId.T15, "still")
        val line = record.summary[0]
        assertTrue(Regex("Link broken at \\d+\\.\\d ms, PC link restored at \\d+\\.\\d ms \\(20\\.0 s later\\)\\.").matches(line), line)
        // The fake link is not a Bluetooth link: the break of the network.
        val breakPrompt = h.prompts.first { it.text.startsWith("Break the link now") }
        assertEquals(ProbeTexts.CAUTION_BREAK_WIFI, breakPrompt.caution)
        val log = (h.files as MemoryProbeFiles).log(record)
        assertTrue(log.any { it.endsWith(" NOTE Wait 20 s with the link broken. This time is shorter than the 30 s limits of the session.") })
    }

    @Test
    fun t10AsksThePowerCycleQuestionForTheButtons() = runTest {
        val h = ProbeHarness(this)
        h.run(ProbeTestId.T10, "commands", TestOptions(axis = 'x'))
        val prompt = h.prompts.single { it.kind == PromptKind.YesNo }
        assertEquals("Did you turn the adapter power off and on, and keep the other DRO app disconnected since then?", prompt.text)
    }
}
