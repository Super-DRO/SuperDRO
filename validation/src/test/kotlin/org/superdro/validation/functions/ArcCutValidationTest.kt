package org.superdro.validation.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.superdro.core.functions.arc.AngleReference
import org.superdro.core.functions.arc.ArcCut
import org.superdro.core.functions.arc.ArcCutGeometry
import org.superdro.core.functions.arc.ArcCutInput
import org.superdro.core.functions.arc.ArcGeometryResult
import org.superdro.core.functions.arc.CutType
import org.superdro.core.functions.patterns.AxisPoint
import org.superdro.core.functions.patterns.PatternCommon
import org.superdro.core.functions.patterns.PatternResult
import org.superdro.core.functions.patterns.PositionReference
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.validation.functions.Expect.DEG
import org.superdro.validation.functions.Expect.DEG4
import org.superdro.validation.functions.Expect.MM

/**
 * The arc cut. The worked example is
 * `R` = 20 mm, `D` = 6 mm, `s` = 2 mm, `α` = 0, `θ` = 90, zero origin, plane `XY`.
 */
class ArcCutValidationTest {

    private fun geometry(
        sweepDeg: Double = 90.0,
        reference: AngleReference = AngleReference.Center,
        cutType: CutType = CutType.External,
        radiusMm: Double = 20.0,
        cutterMm: Double = 6.0,
        stepoverMm: Double = 2.0,
    ): ArcCutGeometry = assertIs<ArcGeometryResult.Success>(
        ArcCut.geometry(
            radiusMm * MM, cutterMm * MM, stepoverMm * MM, 0.0, sweepDeg * DEG,
            cutType, reference, PatternAngleConvention.Legacy,
        ),
    ).geometry

    private fun failure(
        sweepDeg: Double,
        reference: AngleReference,
        radiusMm: Double,
        cutterMm: Double,
        stepoverMm: Double,
        cutType: CutType = CutType.External,
    ): String = assertIs<ArcGeometryResult.Failure>(
        ArcCut.geometry(
            radiusMm * MM, cutterMm * MM, stepoverMm * MM, 0.0, sweepDeg * DEG,
            cutType, reference, PatternAngleConvention.Legacy,
        ),
    ).error.text

    @Test
    fun center_reference_column() {
        val g = geometry()
        Expect.near(5.7320, g.stepAngleArcSec / DEG, DEG4, "c(R, s)")
        Expect.near(0.0, g.tangentAngleArcSec / DEG, DEG4, "fc")
        Expect.near(90.0, g.effectiveSweepArcSec / DEG, DEG4, "sweep_eff")
        Expect.near(0.0, g.startArcSec / DEG, DEG4, "start")
        assertEquals(16, g.steps)
        assertEquals(17, g.points.size)
        Expect.near(5.6250, g.stepArcSec / DEG, DEG4, "step")
        Expect.mm(0.0, g.points[0].h, "point 0 X")
        Expect.mm(23.0, g.points[0].v, "point 0 Y")
        Expect.mm(2.254, g.points[1].h, "point 1 X")
        Expect.mm(22.889, g.points[1].v, "point 1 Y")
        Expect.mm(23.0, g.points.last().h, "last X")
        Expect.mm(0.0, g.points.last().v, "last Y")
    }

    @Test
    fun tangent_reference_column() {
        val g = geometry(reference = AngleReference.Tangent)
        Expect.near(5.7320, g.stepAngleArcSec / DEG, DEG4, "c(R, s)")
        Expect.near(7.4787, g.tangentAngleArcSec / DEG, DEG4, "fc = c(23, 3)")
        Expect.near(75.0427, g.effectiveSweepArcSec / DEG, DEG4, "sweep_eff")
        Expect.near(7.4787, g.startArcSec / DEG, DEG4, "start")
        assertEquals(14, g.steps)
        assertEquals(15, g.points.size)
        Expect.near(5.3602, g.stepArcSec / DEG, DEG4, "step")
        Expect.mm(2.994, g.points[0].h, "point 0 X")
        Expect.mm(22.804, g.points[0].v, "point 0 Y")
        Expect.mm(5.111, g.points[1].h, "point 1 X")
        Expect.mm(22.425, g.points[1].v, "point 1 Y")
        Expect.mm(22.804, g.points.last().h, "last X")
        Expect.mm(2.994, g.points.last().v, "last Y")
    }

    @Test
    fun the_standard_angle_convention_applies_to_the_arc() {
        val g = assertIs<ArcGeometryResult.Success>(
            ArcCut.geometry(20 * MM, 6 * MM, 2 * MM, 0.0, 90 * DEG, CutType.External, AngleReference.Center, PatternAngleConvention.Standard),
        ).geometry
        Expect.mm(23.0, g.points[0].h, "point 0 X: the angle 0 is on +X")
        Expect.mm(0.0, g.points[0].v, "point 0 Y")
        Expect.mm(0.0, g.points.last().h, "last X: a positive sweep turns counter-clockwise")
        Expect.mm(23.0, g.points.last().v, "last Y")
    }

    @Test
    fun sweep_minus_90_gives_16_steps_of_minus_5_625() {
        val g = geometry(sweepDeg = -90.0)
        assertEquals(16, g.steps)
        Expect.near(-5.6250, g.stepArcSec / DEG, DEG4, "step")
        assertTrue(kotlin.math.abs(g.stepArcSec) <= g.stepAngleArcSec, "the step is not larger than the stepover angle")
    }

    @Test
    fun internal_cut_uses_R_minus_D_over_2_and_external_uses_R_plus_D_over_2() {
        val internal = geometry(cutType = CutType.Internal)
        Expect.mm(17.0, internal.pointRadiusUm, "internal Rc")
        Expect.mm(0.0, internal.points[0].h, "internal point 0 X")
        Expect.mm(17.0, internal.points[0].v, "internal point 0 Y")
        val external = geometry(cutType = CutType.External)
        Expect.mm(23.0, external.pointRadiusUm, "external Rc")
        // The step angle uses R in both cuts: the stepover is the chord on the part surface.
        assertEquals(external.steps, internal.steps)
    }

    @Test
    fun check_1_cutter_diameter_too_large_for_the_sweep() {
        // fc = c(25, 5) = 11.48°, so sweep_eff = 10 − 2 × 11.48 < 0.
        assertEquals(
            "Cutter Diameter value too large for this sweep angle.",
            failure(sweepDeg = 10.0, reference = AngleReference.Tangent, radiusMm = 20.0, cutterMm = 10.0, stepoverMm = 1.0),
        )
    }

    @Test
    fun check_2_stepover_too_large_for_the_sweep() {
        // c(20, 30) = 97.2°, more than the sweep of 90°.
        assertEquals(
            "Stepover value too large for this sweep angle.",
            failure(sweepDeg = 90.0, reference = AngleReference.Center, radiusMm = 20.0, cutterMm = 6.0, stepoverMm = 30.0),
        )
        // s > 2R: c(R, s) fails.
        assertEquals(
            "Stepover value too large for this sweep angle.",
            failure(sweepDeg = 90.0, reference = AngleReference.Center, radiusMm = 20.0, cutterMm = 6.0, stepoverMm = 41.0),
        )
    }

    @Test
    fun chord_angle_formula() {
        Expect.near(2 * kotlin.math.asin(2.0 / 40.0) * 57.29578, ArcCut.chordAngleArcSec(20 * MM, 2 * MM)!! / DEG, 1e-9, "c(20, 2)")
        assertEquals(null, ArcCut.chordAngleArcSec(0.0, 1.0))
        assertEquals(null, ArcCut.chordAngleArcSec(10.0, 0.0))
        assertEquals(null, ArcCut.chordAngleArcSec(10.0, 20.1))
    }

    @Test
    fun inputs_defaults_and_start_of_cut_reference() {
        assertEquals(90 * DEG, ArcCut.DEFAULT_SWEEP_ARCSEC)
        assertEquals(2_540_000.0, ArcCut.MAX_CUTTER_UM)
        assertEquals(6 * MM, ArcCut.defaultCutterDiameterUm(MachineType.VerticalMill, 6 * MM))
        assertEquals(null, ArcCut.defaultCutterDiameterUm(MachineType.VerticalMill, 0.0))
        assertEquals("Radius/Arc Cutting", ArcCut.title().text)
        val common = PatternCommon(
            origin = AxisPoint(0.0, 0.0, null),
            reference = PositionReference.FirstPoint,
            convention = PatternAngleConvention.Legacy,
        )
        val group = assertIs<PatternResult.Success>(
            ArcCut.create(common, ArcCutInput(20 * MM, 6 * MM, 2 * MM, 0.0)),
        ).group
        assertEquals("Arc", group.groupName)
        assertEquals(17, group.points.size, "the default sweep is 90°")
        Expect.mm(0.0, group.points[0].x, "start of cut X")
        Expect.mm(0.0, group.points[0].y, "start of cut Y")
        Expect.mm(23.0, group.points.last().x, "last X")
        Expect.mm(-23.0, group.points.last().y, "last Y")
        val missingStart = assertIs<PatternResult.Failure>(ArcCut.create(common, ArcCutInput(20 * MM, 6 * MM, 2 * MM, null)))
        assertTrue(missingStart.errors.any { "Start Angle" in it.text }, "the start angle is required")
    }
}
