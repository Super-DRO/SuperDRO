package org.superdro.app.adapterprobe.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.adapterprobe.ProbeTestId
import org.superdro.core.adapterprobe.ProbeTexts
import org.superdro.core.adapterprobe.RunStatus
import org.superdro.core.adapterprobe.RunnerState

/** The Test screen shows only the live messages of a run of its own test. */
class TestUiStateTest {

    private val t10Run = RunnerState(test = ProbeTestId.T10, status = RunStatus.Done, live = listOf("   38566.9 ms  TX #set_mode:x,2;"))

    @Test
    fun theScreenOfTheTestOfTheRunShowsItsLiveMessages() {
        assertEquals(listOf("   38566.9 ms  TX #set_mode:x,2;"), TestUiState(test = ProbeTestId.T10, run = t10Run).liveLines)
    }

    @Test
    fun theScreenOfADifferentTestShowsNoLiveMessage() {
        assertEquals(emptyList(), TestUiState(test = ProbeTestId.T11, run = t10Run).liveLines)
    }

    @Test
    fun theScreenShowsTheStepsOfThePlanBeforeAndAfterTheRun() {
        val steps = TestUiState(test = ProbeTestId.T08, run = t10Run).planSteps
        assertEquals(ProbeTexts.testSteps(ProbeTestId.T08), steps)
        assertEquals("8. Do the steps 3 to 7 again at a second speed.", steps.last())
        assertEquals(ProbeTexts.testSteps(ProbeTestId.T10), TestUiState(test = ProbeTestId.T10, run = t10Run).planSteps)
    }

    @Test
    fun theStepsOfThePlanGiveTheirPlaceToTheRunOfTheTest() {
        val running = RunnerState(test = ProbeTestId.T08, status = RunStatus.Running)
        assertEquals(emptyList(), TestUiState(test = ProbeTestId.T08, run = running).planSteps)
        // A run of a different test does not hide the steps.
        assertEquals(ProbeTexts.testSteps(ProbeTestId.T09), TestUiState(test = ProbeTestId.T09, run = running).planSteps)
    }

    @Test
    fun theCautionAboutTheOneLinkIsNotOnTheScreenOfTt2() {
        assertFalse(TestUiState(test = ProbeTestId.TT2).oneLinkCaution)
        assertTrue(ProbeTestId.entries.filter { it != ProbeTestId.TT2 }.all { TestUiState(test = it).oneLinkCaution })
    }
}
