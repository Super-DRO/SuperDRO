package org.superdro.core.functions.thread

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.model.ThreadInfeedMode
import org.superdro.core.model.ThreadSpec

class ThreadPassScheduleTest {

    @Test
    fun defaultRoughingPassCountsFollowPitchBracketsAndScaleWithDepth() {
        assertEquals(5, ThreadPassSchedule.defaultRoughingPasses(pitchUm = 500.0, totalRadialDepthUm = 300.0))
        assertEquals(5, ThreadPassSchedule.defaultRoughingPasses(pitchUm = 800.0, totalRadialDepthUm = 500.0))
        assertEquals(8, ThreadPassSchedule.defaultRoughingPasses(pitchUm = 1500.0, totalRadialDepthUm = 936.0))
        assertEquals(12, ThreadPassSchedule.defaultRoughingPasses(pitchUm = 2500.0, totalRadialDepthUm = 1560.0))
        assertEquals(16, ThreadPassSchedule.defaultRoughingPasses(pitchUm = 4000.0, totalRadialDepthUm = 2500.0))
        assertEquals(22, ThreadPassSchedule.defaultRoughingPasses(pitchUm = 5000.0, totalRadialDepthUm = 3100.0))
        assertTrue(ThreadPassSchedule.defaultRoughingPasses(pitchUm = 6000.0, totalRadialDepthUm = 5000.0) > 22)
    }

    @Test
    fun infeedAnglesMatchRadialFlankAndModifiedFlankForEachForm() {
        assertEquals(0.0, ThreadPassSchedule.infeedAngleDeg(ThreadForm.IsoMetricCoarse, ThreadInfeedMode.Radial), 1e-9)
        assertEquals(30.0, ThreadPassSchedule.infeedAngleDeg(ThreadForm.IsoMetricCoarse, ThreadInfeedMode.Flank), 1e-9)
        assertEquals(29.5, ThreadPassSchedule.infeedAngleDeg(ThreadForm.IsoMetricCoarse, ThreadInfeedMode.ModifiedFlank), 1e-9)

        assertEquals(27.5, ThreadPassSchedule.infeedAngleDeg(ThreadForm.Bspp, ThreadInfeedMode.Flank), 1e-9)
        assertEquals(27.0, ThreadPassSchedule.infeedAngleDeg(ThreadForm.Bspp, ThreadInfeedMode.ModifiedFlank), 1e-9)

        assertEquals(15.0, ThreadPassSchedule.infeedAngleDeg(ThreadForm.IsoTrapezoidal, ThreadInfeedMode.Flank), 1e-9)
        assertEquals(14.5, ThreadPassSchedule.infeedAngleDeg(ThreadForm.IsoTrapezoidal, ThreadInfeedMode.ModifiedFlank), 1e-9)

        assertEquals(14.5, ThreadPassSchedule.infeedAngleDeg(ThreadForm.Acme, ThreadInfeedMode.Flank), 1e-9)
        assertEquals(14.0, ThreadPassSchedule.infeedAngleDeg(ThreadForm.Acme, ThreadInfeedMode.ModifiedFlank), 1e-9)
    }

    @Test
    fun rootFlatWidthFollowsFormAndCutterRules() {
        assertEquals(375.0, ThreadPassSchedule.rootFlatWidthUm(ThreadForm.IsoMetricCoarse, ThreadCutter.FullForm, 1500.0), 1e-9)
        assertEquals(0.0, ThreadPassSchedule.rootFlatWidthUm(ThreadForm.IsoMetricCoarse, ThreadCutter.SharpV, 1500.0), 1e-9)
        assertEquals(0.0, ThreadPassSchedule.rootFlatWidthUm(ThreadForm.IsoMetricCoarse, ThreadCutter.Partial, 1500.0), 1e-9)

        // Acme 10 TPI (P = 2540 µm, c = 254 µm) and 16 TPI (P = 1587.5 µm, c = 127 µm).
        assertEquals(
            0.3706912 * 2540.0 - 0.5172352 * 254.0,
            ThreadPassSchedule.rootFlatWidthUm(ThreadForm.Acme, ThreadCutter.FullForm, 2540.0),
            1e-6,
        )
        assertEquals(
            0.3706912 * 1587.5 - 0.5172352 * 127.0,
            ThreadPassSchedule.rootFlatWidthUm(ThreadForm.Acme, ThreadCutter.FullForm, 1587.5),
            1e-6,
        )

        // ISO Trapezoidal: P = 1500 (ac = 150), P = 3000 (ac = 250), P = 6000 (ac = 500).
        assertEquals(
            0.3660254 * 1500.0 - 0.5358984 * 150.0,
            ThreadPassSchedule.rootFlatWidthUm(ThreadForm.IsoTrapezoidal, ThreadCutter.FullForm, 1500.0),
            1e-6,
        )
        assertEquals(
            0.3660254 * 3000.0 - 0.5358984 * 250.0,
            ThreadPassSchedule.rootFlatWidthUm(ThreadForm.IsoTrapezoidal, ThreadCutter.FullForm, 3000.0),
            1e-6,
        )
        assertEquals(
            0.3660254 * 6000.0 - 0.5358984 * 500.0,
            ThreadPassSchedule.rootFlatWidthUm(ThreadForm.IsoTrapezoidal, ThreadCutter.FullForm, 6000.0),
            1e-6,
        )
    }

    @Test
    fun constantChipAreaScheduleGivesEqualChipAreaRoughingPassesFinishAndSpring() {
        val spec = ThreadSpec(
            form = ThreadForm.IsoMetricCoarse,
            internal = false,
            cutter = ThreadCutter.FullForm,
            pitchUm = 1500.0,
            preOpDiameterUm = 10000.0,
            cutToDiameterUm = 8128.0,
            infeedMode = ThreadInfeedMode.ModifiedFlank,
        )
        val schedule = ThreadPassSchedule.generate(spec)
        assertEquals(936.0, schedule.totalRadialDepthUm, 1e-9)
        assertEquals(8, schedule.roughingPassCount)
        assertEquals(29.5, schedule.infeedAngleDeg, 1e-9)
        assertEquals(10, schedule.passes.size) // 8 roughing + 1 finish + 1 spring

        val roughing = schedule.passes.filter { it.kind == PassKind.Roughing }
        assertEquals(8, roughing.size)
        assertEquals(901.0, roughing.last().cumulativeRadialUm, 1e-6)

        // Check that each roughing pass removes the same chip area.
        val tan30 = tan(30.0 * PI / 180.0)
        val w = 375.0
        fun area(h: Double): Double = tan30 * h * h + w * h
        val expectedStepArea = area(901.0) / 8.0
        var prevH = 0.0
        for (pass in roughing) {
            val deltaArea = area(pass.cumulativeRadialUm) - area(prevH)
            assertEquals(expectedStepArea, deltaArea, 1e-3)
            assertEquals(10000.0 - 2.0 * pass.cumulativeRadialUm, pass.targetDiameterUm, 1e-6)
            assertEquals(pass.stepRadialUm / cos(29.5 * PI / 180.0), pass.compoundStepUm, 1e-6)
            assertEquals(pass.stepRadialUm * tan(29.5 * PI / 180.0), pass.compoundZShiftUm, 1e-6)
            prevH = pass.cumulativeRadialUm
        }

        val finish = schedule.passes[8]
        assertEquals(PassKind.Finish, finish.kind)
        assertEquals(35.0, finish.stepRadialUm, 1e-6)
        assertEquals(936.0, finish.cumulativeRadialUm, 1e-6)
        assertEquals(8128.0, finish.targetDiameterUm, 1e-6)

        val spring = schedule.passes[9]
        assertEquals(PassKind.Spring, spring.kind)
        assertEquals(0.0, spring.stepRadialUm, 1e-9)
        assertEquals(936.0, spring.cumulativeRadialUm, 1e-6)
        assertEquals(8128.0, spring.targetDiameterUm, 1e-6)
        assertEquals(0.0, spring.compoundStepUm, 1e-9)
        assertEquals(0.0, spring.compoundZShiftUm, 1e-9)
    }

    @Test
    fun internalScheduleIncreasesTargetDiametersAndEnforcesMinimumStepFloor() {
        val schedule = ThreadPassSchedule.generate(
            form = ThreadForm.IsoMetricCoarse,
            cutter = ThreadCutter.SharpV,
            internal = true,
            pitchUm = 500.0,
            preOpDiameterUm = 2459.0,
            cutToDiameterUm = 3000.0,
            infeedMode = ThreadInfeedMode.Radial,
            roughingPasses = 25,
        )
        val roughing = schedule.passes.filter { it.kind == PassKind.Roughing }
        assertTrue(roughing.all { it.stepRadialUm >= ThreadPassSchedule.MIN_STEP_RADIAL_UM - 1e-6 })
        assertTrue(roughing.size < 25)
        val finish = schedule.passes.first { it.kind == PassKind.Finish }
        assertEquals(3000.0, finish.targetDiameterUm, 1e-6)
        assertEquals(finish.stepRadialUm, finish.compoundStepUm, 1e-9)
        assertEquals(0.0, finish.compoundZShiftUm, 1e-9)
    }

    @Test
    fun nextPassAdvancesThroughRoughingFinishAndSpringPasses() {
        val spec = ThreadSpec(
            form = ThreadForm.IsoMetricCoarse,
            internal = false,
            cutter = ThreadCutter.FullForm,
            pitchUm = 1500.0,
            preOpDiameterUm = 10000.0,
            cutToDiameterUm = 8128.0,
        )
        val schedule = ThreadPassSchedule.generate(spec)
        val first = assertNotNull(ThreadPassSchedule.nextPass(schedule, null, 10000.0, internal = false))
        assertEquals(1, first.number)
        assertEquals(PassKind.Roughing, first.kind)

        // Within 5 µm of Pass 1 target radial depth advances to Pass 2.
        val pass1Target = first.targetDiameterUm
        val second = assertNotNull(ThreadPassSchedule.nextPass(schedule, pass1Target + 4.0, 10000.0, internal = false))
        assertEquals(2, second.number)

        // At the roughing final diameter, the next pass is the finish pass.
        val lastRough = schedule.passes.last { it.kind == PassKind.Roughing }
        val finish = assertNotNull(ThreadPassSchedule.nextPass(schedule, lastRough.targetDiameterUm, 10000.0, internal = false))
        assertEquals(PassKind.Finish, finish.kind)

        // At the final cut-to diameter, the next pass is the spring pass.
        val spring = assertNotNull(ThreadPassSchedule.nextPass(schedule, 8128.0, 10000.0, internal = false))
        assertEquals(PassKind.Spring, spring.kind)
    }
}
