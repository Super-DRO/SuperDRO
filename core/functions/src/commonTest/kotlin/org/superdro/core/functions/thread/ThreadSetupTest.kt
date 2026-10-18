package org.superdro.core.functions.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm

class ThreadSetupTest {

    private val now = Instant.fromEpochMilliseconds(1_760_000_000_000)

    private val m10External = ThreadSetupInput(
        form = ThreadForm.IsoMetricCoarse,
        internal = false,
        size = "M10 × 1.5",
        threadClass = ThreadClass.Class6g,
        cutter = ThreadCutter.SharpV,
        startZUm = 0.0,
        lengthUm = 20000.0,
        leftReliefUm = 4500.0,
        rightReliefUm = null,
    )

    @Test
    fun theZPositionsOfTheExampleAreInMicrometers() {
        val z = ThreadSetup.zPositions(0.0, 20000.0, 4500.0, null)
        assertEquals(0.0, z.startZUm)
        assertEquals(-15500.0, z.stopZUm)
        val both = ThreadSetup.zPositions(5000.0, 20000.0, 3000.0, 2000.0)
        assertEquals(3000.0, both.startZUm)
        assertEquals(-12000.0, both.stopZUm)
    }

    @Test
    fun theDefaultTagCountsFromTheLargestThreadNumber() {
        assertEquals("Thread 1", ThreadSetup.defaultTag(emptyList()))
        assertEquals("Thread 4", ThreadSetup.defaultTag(listOf("Thread_3", "Thread 1", "Measured", "Thread x")))
        assertEquals("Thread 13", ThreadSetup.defaultTag(listOf("Thread 12")))
    }

    @Test
    fun theDefaultLeftReliefIsThreeTimesThePitch() {
        assertEquals(4500.0, ThreadSetup.defaultLeftReliefUm(1500.0))
    }

    @Test
    fun aValidInputHasNoProblem() {
        assertEquals(emptyList(), ThreadSetup.validate(m10External))
    }

    @Test
    fun theProblemsComeInTheOrderOfTheDialog() {
        val input = ThreadSetupInput(
            form = ThreadForm.CustomImperial,
            internal = true,
            customPitchUm = 0.0,
            customMajorDiameterUm = 10000.0,
            customMinorDiameterUm = 12000.0,
            startZUm = null,
            lengthUm = 0.0,
            leftReliefUm = -1.0,
            rightReliefUm = Double.NaN,
        )
        assertEquals(
            listOf(
                ThreadSetupProblem.NoWorkspace,
                ThreadSetupProblem.StartZRequired,
                ThreadSetupProblem.LengthNotPositive,
                ThreadSetupProblem.TpiNotPositive,
                ThreadSetupProblem.MinorNotSmallerThanMajor,
                ThreadSetupProblem.ReliefInvalid(ReliefLabel.Bottom),
                ThreadSetupProblem.ReliefInvalid(ReliefLabel.Mouth),
            ),
            ThreadSetup.validate(input, hasWorkspace = false),
        )
    }

    @Test
    fun theCustomDiametersAndTheReliefsAreChecked() {
        val custom = ThreadSetupInput(
            form = ThreadForm.CustomMetric,
            internal = false,
            customPitchUm = -1.0,
            customMajorDiameterUm = 0.0,
            customMinorDiameterUm = null,
            startZUm = Double.NaN,
            lengthUm = 10000.0,
            leftReliefUm = 6000.0,
            rightReliefUm = 4000.0,
        )
        assertEquals(
            listOf(
                ThreadSetupProblem.StartZNotANumber,
                ThreadSetupProblem.PitchNotPositive,
                ThreadSetupProblem.MinorNotPositive,
                ThreadSetupProblem.MajorNotPositive,
                ThreadSetupProblem.ReliefsTooLong,
            ),
            ThreadSetup.validate(custom),
        )
        assertEquals(listOf(ThreadSetupProblem.NoSize), ThreadSetup.validate(m10External.copy(size = null)))
        assertEquals(ReliefLabel.Shoulder, ThreadSetup.reliefLabel(internal = false, w1 = true))
        assertEquals(ReliefLabel.Face, ThreadSetup.reliefLabel(internal = false, w1 = false))
    }

    @Test
    fun theDesignationOfACustomThreadUsesItsValues() {
        assertEquals("M10 × 1.5", ThreadSetup.designation(m10External))
        val metric = ThreadSetupInput(form = ThreadForm.CustomMetric, customMajorDiameterUm = 12500.0, customPitchUm = 1250.0)
        assertEquals("M12.5 × 1.25", ThreadSetup.designation(metric))
        val inch = ThreadSetupInput(form = ThreadForm.CustomImperial, customMajorDiameterUm = 12700.0, customPitchUm = 25400.0 / 13.0)
        assertEquals("0.5-13", ThreadSetup.designation(inch))
        assertEquals("Custom", ThreadSetup.designation(ThreadSetupInput(form = ThreadForm.CustomImperial)))
    }

    @Test
    fun theStoredThreadHasAllLengthsInMicrometers() {
        val spec = assertNotNull(ThreadSetup.toSpec(m10External, workspaceId = "w1", existingTags = listOf("Thread 2"), now = now))
        assertEquals("Thread 3", spec.tag)
        assertEquals("M10 × 1.5", spec.size)
        assertEquals(1500.0, spec.pitchUm)
        assertEquals(9968.0, spec.preOpDiameterUm, 1e-9)
        assertEquals(9968.0, spec.majorDiameterUm, 1e-9)
        assertEquals(7694.683325, spec.minorDiameterUm, 1e-6)
        assertEquals(7694.683325, spec.cutToDiameterUm, 1e-6)
        assertEquals(8993.721425, spec.pitchDiameterUm, 1e-6)
        assertEquals(0.0, spec.startZUm)
        assertEquals(-15500.0, spec.stopZUm)
        assertEquals(4500.0, spec.leftReliefUm)
        assertNull(spec.rightReliefUm)
        assertNull(spec.cutterTipRadiusUm)
        assertNull(ThreadSetup.toSpec(m10External.copy(lengthUm = null), workspaceId = "w1"))
    }

    @Test
    fun theRestoreGivesBackAllFieldsAlsoStartZTheLengthAndTheReliefs() {
        val input = m10External.copy(startZUm = 2500.0, rightReliefUm = 1000.0, cutter = ThreadCutter.Partial, cutterTipRadiusUm = 80.0)
        val spec = assertNotNull(ThreadSetup.toSpec(input, workspaceId = "w1", now = now))
        assertEquals(input.copy(tag = ""), ThreadSetup.restore(spec))
        val custom = ThreadSetupInput(
            form = ThreadForm.CustomMetric,
            internal = true,
            customPitchUm = 1250.0,
            customMajorDiameterUm = 12500.0,
            customMinorDiameterUm = 11000.0,
            startZUm = 0.0,
            lengthUm = 15000.0,
        )
        val customSpec = assertNotNull(ThreadSetup.toSpec(custom, workspaceId = "w1", now = now))
        assertEquals(custom, ThreadSetup.restore(customSpec).copy(threadClass = null))
    }

    @Test
    fun calculateToSpecAndRestorePreserveHandStartsInfeedPresetAndInspectionFields() {
        val input = m10External.copy(
            cutter = ThreadCutter.Partial,
            insertPreset = org.superdro.core.model.PartialInsertPreset.AG60,
            leftHand = true,
            starts = 2,
            infeedMode = org.superdro.core.model.ThreadInfeedMode.Flank,
            roughingPasses = 12,
            wireDiameterUm = 900.0,
            measuredOverWiresUm = 10450.0,
        )
        val geometry = assertNotNull(ThreadSetup.calculate(input))
        assertEquals(60.0, geometry.rootRadiusUm, 1e-6)
        assertEquals(3000.0, geometry.leadUm, 1e-6)
        assertEquals(true, geometry.leftHand)
        val wires = assertNotNull(geometry.threeWire)
        assertEquals(900.0, wires.wireDiameterUm, 1e-6)
        assertNotNull(wires.actualPitchDiameterUm)
        assertNotNull(wires.remainingRadialDepthUm)

        val spec = assertNotNull(ThreadSetup.toSpec(input, workspaceId = "w1", now = now))
        assertEquals(true, spec.leftHand)
        assertEquals(2, spec.starts)
        assertEquals(org.superdro.core.model.ThreadInfeedMode.Flank, spec.infeedMode)
        assertEquals(12, spec.roughingPasses)
        assertEquals(900.0, spec.wireDiameterUm)
        assertEquals(org.superdro.core.model.PartialInsertPreset.AG60, spec.insertPreset)
        assertEquals(60.0, spec.cutterTipRadiusUm)

        val restored = ThreadSetup.restore(spec)
        assertEquals(true, restored.leftHand)
        assertEquals(2, restored.starts)
        assertEquals(org.superdro.core.model.ThreadInfeedMode.Flank, restored.infeedMode)
        assertEquals(12, restored.roughingPasses)
        assertEquals(900.0, restored.wireDiameterUm)
        assertEquals(org.superdro.core.model.PartialInsertPreset.AG60, restored.insertPreset)
        assertEquals(60.0, restored.cutterTipRadiusUm)
    }
}
