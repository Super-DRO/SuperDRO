package org.superdro.core.functions.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.model.ThreadSpec

class ThreadCutTest {

    private val now = Instant.fromEpochMilliseconds(1_760_000_000_000)

    /** M10 × 1.5 6g external, Full Form: crest 10.000 mm, root 8.128 mm; Z 0 to −20000 µm, W₁ 4500. */
    private val spec: ThreadSpec = assertNotNull(
        ThreadSetup.toSpec(
            ThreadSetupInput(
                form = ThreadForm.IsoMetricCoarse,
                internal = false,
                size = "M10 × 1.5",
                threadClass = ThreadClass.Class6g,
                cutter = ThreadCutter.FullForm,
                startZUm = 0.0,
                lengthUm = 20000.0,
                leftReliefUm = 4500.0,
                tag = "Thread 1",
            ),
            workspaceId = "w1",
            now = now,
        ),
    ).copy(id = "t1")

    @Test
    fun theWorkedExampleOfTheCutPanelGivesItsValues() {
        assertEquals("10.000", ThreadText.length(spec.preOpDiameterUm, ThreadUnit.Millimeter))
        assertEquals("8.128", ThreadText.length(spec.cutToDiameterUm, ThreadUnit.Millimeter))
        val values = ThreadCut.values(spec, ThreadPassRecord(deepestUm = 9600.0, threadId = "t1"), xUm = 4700.0, readoutXUm = 636.0, zUm = -8000.0)
        assertEquals(400.0, ThreadCut.depthUm(spec, 9600.0), 1e-9)
        assertEquals(600.0, ThreadCut.depthUm(spec, 9400.0), 1e-9)
        assertEquals(0.098094, ThreadCut.chipArea(spec, 400.0), 5e-7)
        assertEquals(0.164462, ThreadCut.chipArea(spec, 600.0), 5e-7)
        assertEquals(0.68, assertNotNull(values.cutLoadRatio), 0.005)
        assertEquals("0.7", ThreadText.cutLoad(values.cutLoadRatio))
        assertEquals(100.0, assertNotNull(values.infeedUm), 1e-9)
        assertEquals(736.0, assertNotNull(values.lastPassUm), 1e-9)
        assertEquals(11000.0, values.clearDiameterUm, 1e-9)
        assertEquals(RetractState.Engaged, values.retract)
        assertEquals(DepthPhase.Cutting, values.phase)
        assertEquals(ThreadCutStatus.InTheCut, values.status)
        assertTrue(values.warnings.isEmpty())
    }

    @Test
    fun noRecordedPassGivesNoLastPassNoInfeedAndNoRatio() {
        val values = ThreadCut.values(spec, ThreadPassRecord(), xUm = 4700.0, readoutXUm = 636.0, zUm = -8000.0)
        assertNull(values.lastPassUm)
        assertNull(values.infeedUm)
        assertNull(values.cutLoadRatio)
        assertEquals("—", ThreadText.cutLoad(values.cutLoadRatio))
    }

    @Test
    fun theSecondRecordGivesTheLastArea() {
        // Deepest 9400, previous 9600: lastArea = A(600) − A(400); the tool at 9200 (depth 800).
        val record = ThreadPassRecord(deepestUm = 9400.0, previousDeepestUm = 9600.0, threadId = "t1")
        val values = ThreadCut.values(spec, record, xUm = 4600.0, readoutXUm = 536.0, zUm = -8000.0)
        val last = ThreadCut.chipArea(spec, 600.0) - ThreadCut.chipArea(spec, 400.0)
        val next = ThreadCut.chipArea(spec, 800.0) - ThreadCut.chipArea(spec, 600.0)
        assertEquals(next / last, assertNotNull(values.cutLoadRatio), 1e-12)
        // A tool at the recorded depth gives no next area and thus no ratio.
        assertNull(ThreadCut.values(spec, record, xUm = 4700.0, readoutXUm = 636.0, zUm = -8000.0).cutLoadRatio)
    }

    @Test
    fun theRetractStateAndThePhaseFollowTheDiameter() {
        val clear = ThreadCut.values(spec, ThreadPassRecord(), xUm = 5500.0, readoutXUm = 0.0, zUm = -8000.0)
        assertEquals(RetractState.Clear, clear.retract)
        assertEquals(DepthPhase.Safe, clear.phase)
        assertEquals(ThreadCutStatus.ClearOfCrests, clear.status)
        val justInside = ThreadCut.values(spec, ThreadPassRecord(), xUm = 5499.0, readoutXUm = 0.0, zUm = -8000.0)
        assertEquals(RetractState.Engaged, justInside.retract)
        val deep = ThreadCut.values(spec, ThreadPassRecord(), xUm = 4000.0, readoutXUm = 0.0, zUm = -8000.0)
        assertEquals(DepthPhase.PastFinal, deep.phase)
        assertEquals(ThreadCutStatus.PastTheRoot, deep.status)
        // The other side of the spindle axis uses |x|.
        assertEquals(RetractState.Clear, ThreadCut.retractState(spec, -5500.0, -8000.0))
        val none = ThreadCut.values(spec, ThreadPassRecord(), xUm = 4700.0, readoutXUm = 0.0, zUm = -8000.0, hasActiveCorner = false)
        assertEquals(ThreadCutStatus.SelectFinishCorner, none.status)
    }

    @Test
    fun pastTheRetractLimitShowsWhenTheToolIsInTheCutBeyondTheRelief() {
        assertEquals(-20000.0, ThreadCut.retractLimitZUm(spec))
        val past = ThreadCut.values(spec, ThreadPassRecord(), xUm = 4700.0, readoutXUm = 0.0, zUm = -20001.0)
        assertEquals(RetractState.PastLimit, past.retract)
        assertEquals(ThreadCutStatus.PastRetractLimit, past.status)
        assertEquals(RetractState.Engaged, ThreadCut.retractState(spec, 4700.0, -20000.0))
        assertEquals(RetractState.Clear, ThreadCut.retractState(spec, 5600.0, -21000.0))
        assertEquals(RetractState.Engaged, ThreadCut.retractState(spec, 4700.0, null))
    }

    @Test
    fun anXWithNoReferenceGivesTheWarning() {
        val values = ThreadCut.values(spec, ThreadPassRecord(), xUm = 4700.0, readoutXUm = 0.0, zUm = -8000.0, xReferenced = false)
        assertEquals(setOf(ThreadCutWarning.XNotReferenced), values.warnings)
    }

    @Test
    fun anInternalThreadIsClearInsideTheMinorDiameter() {
        val internal = assertNotNull(
            ThreadSetup.toSpec(
                ThreadSetupInput(
                    form = ThreadForm.IsoMetricCoarse, internal = true, size = "M10 × 1.5", threadClass = ThreadClass.Class6H,
                    cutter = ThreadCutter.SharpV, startZUm = 0.0, lengthUm = 20000.0,
                ),
                workspaceId = "w1",
                now = now,
            ),
        )
        // Minor 8376.2 − 1000 = 7376.2.
        assertEquals(7376.202375, ThreadCut.clearDiameterUm(internal), 1e-6)
        assertEquals(RetractState.Clear, ThreadCut.retractState(internal, 3688.0, -5000.0))
        assertEquals(RetractState.Engaged, ThreadCut.retractState(internal, 3689.0, -5000.0))
        assertEquals(100.0, ThreadCut.depthUm(internal, internal.preOpDiameterUm + 100.0), 1e-9)
    }

    @Test
    fun aPassCountsWhenZCrossesTheBandOf25To75Percent() {
        // zMin = −15500, zMax = 0, L = 15500: the band is −11625 to −3875.
        var state = ThreadCut.arm(ThreadCutState(), "t1")
        for (z in listOf(-1000.0, -4000.0, -8000.0, -11000.0)) state = ThreadCut.update(spec, state, 4800.0, z)
        assertNull(state.record.deepestUm)
        state = ThreadCut.update(spec, state, 4800.0, -12000.0)
        assertEquals(9600.0, state.record.deepestUm)
        assertNull(state.record.previousDeepestUm)
        assertEquals("t1", state.record.threadId)
        assertTrue(state.stroke.latched)
        // A spring pass at the same depth does not change the record.
        state = ThreadCut.update(spec, state, 4800.0, 1000.0)
        assertEquals(ThreadStroke(), state.stroke)
        for (z in listOf(-500.0, -6000.0, -14000.0)) state = ThreadCut.update(spec, state, 4800.0, z)
        assertEquals(9600.0, state.record.deepestUm)
        // A deeper pass moves the record.
        state = ThreadCut.update(spec, state, 4700.0, Double.NaN)
        for (z in listOf(-500.0, -6000.0, -14000.0)) state = ThreadCut.update(spec, state, 4700.0, z)
        assertEquals(9400.0, state.record.deepestUm)
        assertEquals(9600.0, state.record.previousDeepestUm)
    }

    @Test
    fun aFastPassWithFewPositionsInsideTheLengthStillCounts() {
        // zMin = −15500, zMax = 0. The display rate gives only these positions of one pass.
        var state = ThreadCut.arm(ThreadCutState(), "t1")
        state = ThreadCut.update(spec, state, 4800.0, 2000.0)
        state = ThreadCut.update(spec, state, 4800.0, -16000.0)
        assertEquals(9600.0, state.record.deepestUm, "a line over all of the length is a pass")
        assertEquals(ThreadStroke(), state.stroke)
        // The return move with the tool out of the thread does not change the record.
        state = ThreadCut.update(spec, state, 5000.0, 1000.0)
        assertEquals(9600.0, state.record.deepestUm)
        // A deeper pass with one position inside the length near each end.
        state = ThreadCut.update(spec, state, 4700.0, -1000.0)
        state = ThreadCut.update(spec, state, 4700.0, -14000.0)
        state = ThreadCut.update(spec, state, 4700.0, -17000.0)
        assertEquals(9400.0, state.record.deepestUm)
        assertEquals(9600.0, state.record.previousDeepestUm)
        // A motion outside the length on one side is not a pass.
        state = ThreadCut.update(spec, state, 4600.0, -17000.0)
        state = ThreadCut.update(spec, state, 4600.0, -30000.0)
        assertEquals(9400.0, state.record.deepestUm)
        // With no Z, the next position starts a new line.
        state = ThreadCut.update(spec, state, 4600.0, null)
        state = ThreadCut.update(spec, state, 4600.0, 3000.0)
        assertEquals(9400.0, state.record.deepestUm)
    }

    @Test
    fun armingANewThreadStartsThePassRecordAgain() {
        val recorded = ThreadCutState(ThreadPassRecord(9400.0, 9600.0, "t1"), ThreadStroke(-14000.0, -500.0, true), activeStart = 2)
        assertEquals(ThreadCutState(ThreadPassRecord(9400.0, 9600.0, "t1"), activeStart = 2), ThreadCut.arm(recorded, "t1"))
        assertEquals(ThreadCutState(), ThreadCut.arm(recorded, "t2"))
    }

    @Test
    fun leftHandThreadUsesZMaxPlusRightReliefAsRetractLimit() {
        val leftHand = spec.copy(leftHand = true, rightReliefUm = 3000.0)
        // Zmin = -15500, Zmax = 0, W2 = 3000 -> retractLimitZUm = +3000.0.
        assertEquals(3000.0, ThreadCut.retractLimitZUm(leftHand), 1e-9)
        assertEquals(RetractState.Engaged, ThreadCut.retractState(leftHand, 4700.0, 3000.0))
        assertEquals(RetractState.PastLimit, ThreadCut.retractState(leftHand, 4700.0, 3001.0))
        assertEquals(RetractState.Clear, ThreadCut.retractState(leftHand, 5600.0, 4000.0))
    }

    @Test
    fun chipAreaUsesIncludedAngleAndRootFlatWidthOfForm() {
        val trSpec = spec.copy(
            form = ThreadForm.IsoTrapezoidal,
            cutter = ThreadCutter.FullForm,
            pitchUm = 2000.0,
        )
        // h = 0.5 mm (depthUm = 1000 µm), α = 30° -> tan(15°), w = (0.3660254 * 2000 - 0.5358984 * 250) / 1000 mm.
        val wMm = ThreadPassSchedule.rootFlatWidthUm(ThreadForm.IsoTrapezoidal, ThreadCutter.FullForm, 2000.0) / 1000.0
        val expected = kotlin.math.tan(15.0 * kotlin.math.PI / 180.0) * 0.25 + wMm * 0.5
        assertEquals(expected, ThreadCut.chipArea(trSpec, 1000.0), 1e-9)
    }

    @Test
    fun multiStartSelectionAndPassResetManageCutStateAndValues() {
        val multiSpec = spec.copy(starts = 3)
        var state = ThreadCut.arm(ThreadCutState(), "t1")
        state = ThreadCut.update(multiSpec, state, 4800.0, 1000.0)
        state = ThreadCut.update(multiSpec, state, 4800.0, -16000.0)
        assertEquals(9600.0, state.record.deepestUm)
        assertEquals(1, state.activeStart)

        // Switching to Start 2 resets the pass record and stroke for the new start.
        state = ThreadCut.selectStart(state, 2)
        assertEquals(2, state.activeStart)
        assertNull(state.record.deepestUm)
        assertEquals(ThreadStroke(), state.stroke)

        // Recording a pass on Start 2 keeps activeStart = 2.
        state = ThreadCut.update(multiSpec, state, 4750.0, 1000.0)
        state = ThreadCut.update(multiSpec, state, 4750.0, -16000.0)
        assertEquals(9500.0, state.record.deepestUm)
        assertEquals(2, state.activeStart)

        val values = ThreadCut.values(multiSpec, state.record, xUm = 4700.0, readoutXUm = 636.0, zUm = -8000.0, activeStart = state.activeStart)
        assertEquals(2, values.activeStart)
        assertEquals(3, values.totalStarts)
        assertEquals(8, values.schedule.roughingPassCount)
        val next = assertNotNull(values.nextPass)
        assertEquals(2, next.number)

        // Resetting passes clears the record while keeping Start 2 active.
        val reset = ThreadCut.resetPasses(state)
        assertEquals(2, reset.activeStart)
        assertNull(reset.record.deepestUm)
    }
}
