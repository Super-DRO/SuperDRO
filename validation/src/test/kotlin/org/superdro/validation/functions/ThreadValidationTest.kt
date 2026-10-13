package org.superdro.validation.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.thread.ThreadCalculator
import org.superdro.core.functions.thread.ThreadCut
import org.superdro.core.functions.thread.ThreadCutStatus
import org.superdro.core.functions.thread.ThreadCutWarning
import org.superdro.core.functions.thread.ThreadGeometry
import org.superdro.core.functions.thread.ThreadPassRecord
import org.superdro.core.functions.thread.ThreadPointRole
import org.superdro.core.functions.thread.ThreadPoints
import org.superdro.core.functions.thread.ThreadSetup
import org.superdro.core.functions.thread.ThreadSetupInput
import org.superdro.core.functions.thread.ThreadSetupProblem
import org.superdro.core.functions.thread.ThreadTables
import org.superdro.core.functions.thread.RetractState
import org.superdro.core.functions.thread.classesFor
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm

/**
 * The lathe thread functions, with the worked examples (M10 × 1.5 6g and 6H, 1/4-20 UNC 2A
 * and 2B, and others).
 *
 * The table values are in the thread unit. The tolerance is 0.5 in the last digit of each value.
 */
class ThreadValidationTest {

    private val m10 = assertNotNull(ThreadTables.find(ThreadForm.IsoMetricCoarse, "M10 × 1.5"))
    private val quarter20 = assertNotNull(ThreadTables.find(ThreadForm.Unc, "1/4-20"))

    private fun mm(expected: Double, actualUm: Double, decimals: Int, what: String) =
        Expect.near(expected, actualUm / 1000.0, 0.5 * Math.pow(10.0, -decimals.toDouble()), what)

    private fun inch(expected: Double, actualUm: Double, decimals: Int, what: String) =
        Expect.near(expected, actualUm / 25400.0, 0.5 * Math.pow(10.0, -decimals.toDouble()), what)

    private fun m10(internal: Boolean, cutter: ThreadCutter): ThreadGeometry =
        ThreadCalculator.standard(m10, internal, cutter, if (internal) ThreadClass.Class6H else ThreadClass.Class6g)

    private fun unc(internal: Boolean, cutter: ThreadCutter): ThreadGeometry =
        ThreadCalculator.standard(quarter20, internal, cutter, if (internal) ThreadClass.Class2B else ThreadClass.Class2A)

    @Test
    fun m10_x_1_5_6g_external() {
        val g = m10(internal = false, cutter = ThreadCutter.SharpV)
        mm(1.29904, g.heightUm, 5, "H")
        mm(9.02572, g.basicPitchDiameterUm, 5, "basic d2")
        val t = assertNotNull(g.tolerances)
        mm(-0.032, t.deviationUm, 3, "es")
        mm(0.132, t.pitchDiameterToleranceUm, 3, "Td2")
        mm(0.236, t.crestDiameterToleranceUm, 3, "Td")
        mm(9.968, g.preOp.valueUm, 3, "turn to")
        mm(9.732, g.preOp.minUm, 3, "turn to min")
        mm(9.968, g.preOp.maxUm, 3, "turn to max")
        mm(7.695, g.cutToDiameterUm, 3, "cut to, Sharp V")
        val ff = m10(internal = false, cutter = ThreadCutter.FullForm)
        mm(10.000, ff.preOp.valueUm, 3, "turn to, Full Form")
        mm(8.128, ff.cutToDiameterUm, 3, "cut to, Full Form")
    }

    @Test
    fun m10_x_1_5_6H_internal() {
        val g = m10(internal = true, cutter = ThreadCutter.SharpV)
        mm(1.29904, g.heightUm, 5, "H")
        mm(9.02572, g.basicPitchDiameterUm, 5, "basic d2")
        val t = assertNotNull(g.tolerances)
        mm(0.0, t.deviationUm, 3, "EI")
        mm(0.180, t.pitchDiameterToleranceUm, 3, "TD2")
        mm(0.300, t.crestDiameterToleranceUm, 3, "TD1")
        mm(8.376, g.preOp.valueUm, 3, "bore to")
        mm(8.376, g.preOp.minUm, 3, "bore to min")
        mm(8.676, g.preOp.maxUm, 3, "bore to max")
        mm(10.325, g.cutToDiameterUm, 3, "cut to, Sharp V")
        val ff = m10(internal = true, cutter = ThreadCutter.FullForm)
        mm(8.376, ff.preOp.valueUm, 3, "bore to, Full Form")
        mm(10.000, ff.cutToDiameterUm, 3, "cut to, Full Form")
    }

    @Test
    fun quarter_20_unc_2A_external() {
        val g = unc(internal = false, cutter = ThreadCutter.SharpV)
        inch(0.043301, g.heightUm, 6, "H")
        inch(0.217524, g.basicPitchDiameterUm, 6, "basic d2")
        val t = assertNotNull(g.tolerances)
        inch(-0.0011, t.deviationUm, 4, "es")
        inch(0.0037, t.pitchDiameterToleranceUm, 4, "Td2")
        inch(0.0081, t.crestDiameterToleranceUm, 4, "Td")
        inch(0.2489, g.preOp.valueUm, 4, "turn to")
        inch(0.2408, g.preOp.minUm, 4, "turn to min")
        inch(0.2489, g.preOp.maxUm, 4, "turn to max")
        inch(0.1731, g.cutToDiameterUm, 4, "cut to, Sharp V")
        val ff = unc(internal = false, cutter = ThreadCutter.FullForm)
        inch(0.2500, ff.preOp.valueUm, 4, "turn to, Full Form")
        inch(0.1876, ff.cutToDiameterUm, 4, "cut to, Full Form")
    }

    @Test
    fun quarter_20_unc_2B_internal() {
        val g = unc(internal = true, cutter = ThreadCutter.SharpV)
        inch(0.043301, g.heightUm, 6, "H")
        inch(0.217524, g.basicPitchDiameterUm, 6, "basic d2")
        val t = assertNotNull(g.tolerances)
        inch(0.0, t.deviationUm, 4, "EI")
        inch(0.0048, t.pitchDiameterToleranceUm, 4, "TD2")
        inch(0.0115, t.crestDiameterToleranceUm, 4, "TD1")
        inch(0.1959, g.preOp.valueUm, 4, "bore to")
        inch(0.1959, g.preOp.minUm, 4, "bore to min")
        inch(0.2074, g.preOp.maxUm, 4, "bore to max")
        inch(0.2608, g.cutToDiameterUm, 4, "cut to, Sharp V")
        val ff = unc(internal = true, cutter = ThreadCutter.FullForm)
        inch(0.1959, ff.preOp.valueUm, 4, "bore to, Full Form")
        inch(0.2500, ff.cutToDiameterUm, 4, "cut to, Full Form")
    }

    @Test
    fun basic_profile_formulas_and_the_target_pitch_diameter() {
        val g = m10(internal = false, cutter = ThreadCutter.SharpV)
        val h = 0.8660254 * 1500.0
        assertEquals(h, g.heightUm, 1e-9, "H = 0.8660254 × P")
        assertEquals(10_000.0 - 0.75 * h, g.basicPitchDiameterUm, 1e-9, "d2 = D − 0.75 × H")
        assertEquals(10_000.0 - 1.25 * h, g.basicMinorDiameterUm, 1e-9, "D1 = D − 1.25 × H")
        assertEquals(g.limits!!.pitchMaxUm, g.targetPitchDiameterUm, "external: d2max")
        assertEquals(g.targetPitchDiameterUm - h, g.cutToDiameterUm, 1e-9, "external Sharp V: dp − H")
        val i = m10(internal = true, cutter = ThreadCutter.SharpV)
        assertEquals(i.limits!!.pitchMinUm, i.targetPitchDiameterUm, "internal: D2min")
        assertEquals(i.targetPitchDiameterUm + h, i.cutToDiameterUm, 1e-9, "internal Sharp V: dp + H")
        val ffExternal = m10(internal = false, cutter = ThreadCutter.FullForm)
        assertEquals(h / 6.0, ffExternal.rootRadiusUm, 1e-9, "Full Form external r = H/6")
        val ffInternal = m10(internal = true, cutter = ThreadCutter.FullForm)
        assertEquals(h / 8.0, ffInternal.rootRadiusUm, 1e-9, "Full Form internal r = H/8")
    }

    @Test
    fun classes_of_each_form() {
        assertEquals(listOf(ThreadClass.Class6H), classesFor(ThreadForm.IsoMetricCoarse, internal = true))
        assertEquals(listOf(ThreadClass.Class6g), classesFor(ThreadForm.IsoMetricFine, internal = false))
        assertEquals(listOf(ThreadClass.Class2B, ThreadClass.Class3B), classesFor(ThreadForm.Unc, internal = true))
        assertEquals(listOf(ThreadClass.Class2A, ThreadClass.Class3A), classesFor(ThreadForm.Unef, internal = false))
        assertTrue(classesFor(ThreadForm.CustomMetric, internal = false).isEmpty())
        val wrongUnit = ThreadCalculator.standard(quarter20, internal = false, cutter = ThreadCutter.SharpV, threadClass = ThreadClass.Class6g)
        assertTrue(wrongUnit.showsNoToleranceNotice, "an ISO class on an inch thread has no class values")
        assertEquals(wrongUnit.basicPitchDiameterUm, wrongUnit.targetPitchDiameterUm, "basic d2 with no class")
    }

    private val m10Input = ThreadSetupInput(
        form = ThreadForm.IsoMetricCoarse,
        internal = false,
        size = "M10 × 1.5",
        threadClass = ThreadClass.Class6g,
        cutter = ThreadCutter.SharpV,
        startZUm = 0.0,
        lengthUm = 20_000.0,
        leftReliefUm = 4_500.0,
        rightReliefUm = null,
    )

    @Test
    fun thread_points_of_m10_6g_external_with_W1_4_5_mm() {
        val spec = assertNotNull(ThreadSetup.toSpec(m10Input, workspaceId = "w"))
        assertEquals(0.0, spec.startZUm)
        assertEquals(-15_500.0, spec.stopZUm, 1e-9)
        assertEquals(1_500.0, spec.pitchUm, "µm")
        assertEquals("Thread 1", spec.tag)
        val points = ThreadPoints.build(spec)
        assertEquals(17, points.size, "14 points, plus the W₁ guide and the two W₁ Inner points")
        val w1 = points.single { it.role == ThreadPointRole.WLeft }
        assertEquals(-20_000.0, w1.z!!, 1e-9)
        assertNull(w1.x, "a Z guide")
        val d = points.single { it.role == ThreadPointRole.DMajor && !it.back }
        Expect.near(4_984.0, d.x, 0.5, "R")
        val d1 = points.single { it.role == ThreadPointRole.DMinor && !it.back }
        Expect.near(3_847.3, d1.x, 0.05, "r1")
        val d1Back = points.single { it.role == ThreadPointRole.DMinor && it.back }
        assertEquals(-d1.x!!, d1Back.x!!, "a Back point has a negative X")
        assertEquals("Thread 1 - Z₁", points.first().name)
        assertEquals(14, ThreadPoints.build(spec.copy(leftReliefUm = null)).size)
        assertEquals(20, ThreadPoints.build(spec.copy(rightReliefUm = 1_000.0)).size)
    }

    @Test
    fun setup_defaults_z_positions_and_checks() {
        assertEquals("Thread 4", ThreadSetup.defaultTag(listOf("Thread 1", "Thread_3", "Other 9")))
        assertEquals(4_500.0, ThreadSetup.defaultLeftReliefUm(1_500.0), "W₁ = 3 × pitch")
        val z = ThreadSetup.zPositions(startZUm = 1_000.0, lengthUm = 20_000.0, leftReliefUm = 4_500.0, rightReliefUm = 2_000.0)
        assertEquals(-1_000.0, z.startZUm, "StartZ = sz − W₂")
        assertEquals(1_000.0 - 15_500.0, z.stopZUm, "StopZ = sz − (L − W₁)")
        assertTrue(ThreadSetupProblem.LengthNotPositive in ThreadSetup.validate(m10Input.copy(lengthUm = 0.0)))
        assertTrue(ThreadSetupProblem.ReliefsTooLong in ThreadSetup.validate(m10Input.copy(leftReliefUm = 15_000.0, rightReliefUm = 5_000.0)))
        assertTrue(ThreadSetupProblem.NoSize in ThreadSetup.validate(m10Input.copy(size = null)))
        assertTrue(ThreadSetup.validate(m10Input).isEmpty())
    }

    @Test
    fun prefill_restores_start_z_length_and_reliefs_and_three_wire_values_show() {
        val input = m10Input.copy(startZUm = 2_000.0, rightReliefUm = 1_500.0)
        val spec = assertNotNull(ThreadSetup.toSpec(input, workspaceId = "w"))
        val restored = ThreadSetup.restore(spec)
        assertEquals(2_000.0, restored.startZUm!!, 1e-9)
        assertEquals(20_000.0, restored.lengthUm!!, 1e-9)
        assertEquals(4_500.0, restored.leftReliefUm)
        assertEquals(1_500.0, restored.rightReliefUm)
        assertEquals(ThreadCutter.SharpV, restored.cutter)
        assertEquals("M10 × 1.5", restored.size)
        assertNotNull(m10(internal = false, cutter = ThreadCutter.SharpV).threeWire, "external: the three-wire values show")
        // Partial: the pre-op diameter is the class limit, as for Sharp V.
        val partial = m10(internal = false, cutter = ThreadCutter.Partial)
        assertEquals(m10(internal = false, cutter = ThreadCutter.SharpV).preOp, partial.preOp)
    }

    @Test
    fun past_the_retract_limit_state_and_the_x_reference_warning() {
        val spec = assertNotNull(ThreadSetup.toSpec(m10Input, workspaceId = "w"))
        // X radius 4.5 mm is in the thread (not clear of the crests). Z is far past the thread end.
        assertEquals(RetractState.PastLimit, ThreadCut.retractState(spec, xUm = 4_500.0, zUm = -30_000.0))
        val values = ThreadCut.values(spec, ThreadPassRecord(), xUm = 4_500.0, readoutXUm = 9_000.0, zUm = -30_000.0, xReferenced = false)
        assertEquals(ThreadCutStatus.PastRetractLimit, values.status)
        assertTrue(ThreadCutWarning.XNotReferenced in values.warnings)
        assertEquals(RetractState.Clear, ThreadCut.retractState(spec, xUm = 6_000.0, zUm = -30_000.0), "clear of the crests")
    }
}
