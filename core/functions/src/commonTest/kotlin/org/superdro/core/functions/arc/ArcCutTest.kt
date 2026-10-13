package org.superdro.core.functions.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.superdro.core.functions.TestValues.assertDegrees
import org.superdro.core.functions.TestValues.assertMm
import org.superdro.core.functions.TestValues.deg
import org.superdro.core.functions.TestValues.mm
import org.superdro.core.functions.patterns.AxisPoint
import org.superdro.core.functions.patterns.PatternAngles
import org.superdro.core.functions.patterns.PatternCommon
import org.superdro.core.functions.patterns.PatternResult
import org.superdro.core.functions.patterns.PositionReference
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention

/** The arc cut. */
class ArcCutTest {

    private val legacy = PatternAngleConvention.Legacy

    private fun geometry(
        sweepDegrees: Double = 90.0,
        reference: AngleReference = AngleReference.Center,
        cutType: CutType = CutType.External,
    ): ArcCutGeometry {
        val result = ArcCut.geometry(mm(20.0), mm(6.0), mm(2.0), 0.0, deg(sweepDegrees), cutType, reference, legacy)
        return assertIs<ArcGeometryResult.Success>(result).geometry
    }

    @Test
    fun theCenterReferenceColumn() {
        val cut = geometry()
        assertDegrees(5.7320, cut.stepAngleArcSec)
        assertDegrees(0.0, cut.tangentAngleArcSec)
        assertDegrees(90.0, cut.effectiveSweepArcSec)
        assertDegrees(0.0, cut.startArcSec)
        assertEquals(16, cut.steps)
        assertEquals(17, cut.points.size)
        assertDegrees(5.6250, cut.stepArcSec)
        assertMm(0.000, cut.points[0].h)
        assertMm(23.000, cut.points[0].v)
        assertMm(2.254, cut.points[1].h)
        assertMm(22.889, cut.points[1].v)
        assertMm(23.000, cut.points.last().h)
        assertMm(0.000, cut.points.last().v)
    }

    @Test
    fun theTangentReferenceColumn() {
        val cut = geometry(reference = AngleReference.Tangent)
        assertDegrees(5.7320, cut.stepAngleArcSec)
        assertDegrees(7.4787, cut.tangentAngleArcSec)
        assertDegrees(75.0427, cut.effectiveSweepArcSec)
        assertDegrees(7.4787, cut.startArcSec)
        assertEquals(14, cut.steps)
        assertEquals(15, cut.points.size)
        assertDegrees(5.3602, cut.stepArcSec)
        assertMm(2.994, cut.points[0].h)
        assertMm(22.804, cut.points[0].v)
        assertMm(5.111, cut.points[1].h)
        assertMm(22.425, cut.points[1].v)
        assertMm(22.804, cut.points.last().h)
        assertMm(2.994, cut.points.last().v)
    }

    @Test
    fun aSweepOfMinus90Gives16StepsOfMinus5625() {
        val cut = geometry(sweepDegrees = -90.0)
        assertEquals(16, cut.steps)
        assertDegrees(-5.6250, cut.stepArcSec)
        assertDegrees(360.0 - 5.625, PatternAngles.normalize(cut.stepArcSec))
    }

    @Test
    fun theInternalCutUsesRMinusHalfTheCutter() {
        val external = geometry(cutType = CutType.External)
        val internal = geometry(cutType = CutType.Internal)
        assertMm(23.0, external.pointRadiusUm)
        assertMm(17.0, internal.pointRadiusUm)
        assertMm(17.0, internal.points[0].v)
        assertEquals(16, internal.steps)
    }

    @Test
    fun theChordAngleRefusesAnImpossibleChord() {
        assertNull(ArcCut.chordAngleArcSec(0.0, 1.0))
        assertNull(ArcCut.chordAngleArcSec(10.0, 0.0))
        assertNull(ArcCut.chordAngleArcSec(10.0, 20.001))
        assertDegrees(180.0, ArcCut.chordAngleArcSec(10.0, 20.0))
    }

    @Test
    fun theTwoChecksGiveTheirTexts() {
        val cutter = ArcCut.geometry(mm(1.0), mm(10.0), mm(0.1), 0.0, deg(10.0), CutType.External, AngleReference.Tangent, legacy)
        assertEquals("Cutter Diameter value too large for this sweep angle.", assertIs<ArcGeometryResult.Failure>(cutter).error.text)
        val stepover = ArcCut.geometry(mm(20.0), mm(6.0), mm(10.0), 0.0, deg(10.0), CutType.External, AngleReference.Center, legacy)
        assertEquals("Stepover value too large for this sweep angle.", assertIs<ArcGeometryResult.Failure>(stepover).error.text)
        val chord = ArcCut.geometry(mm(1.0), mm(1.0), mm(3.0), 0.0, deg(90.0), CutType.External, AngleReference.Center, legacy)
        assertEquals("Stepover value too large for this sweep angle.", assertIs<ArcGeometryResult.Failure>(chord).error.text)
        val internal = ArcCut.geometry(mm(3.0), mm(6.0), mm(1.0), 0.0, deg(90.0), CutType.Internal, AngleReference.Center, legacy)
        assertEquals(
            "Cutter Diameter value too large for an internal cut of this radius.",
            assertIs<ArcGeometryResult.Failure>(internal).error.text,
        )
    }

    @Test
    fun theCreateFunctionChecksTheFieldsAndTheStartAngleIsRequired() {
        val failure = assertIs<PatternResult.Failure>(
            ArcCut.create(PatternCommon(), ArcCutInput(radiusUm = mm(20.0), cutterDiameterUm = 2_540_001.0, stepoverUm = 0.0, startArcSec = null)),
        )
        assertEquals(
            listOf("Cutter Diameter value is too large", "Stepover value is too small", "Start Angle value is required"),
            failure.errors.map { error -> error.text },
        )
    }

    @Test
    fun theStartOfCutReferencePutsPointZeroAtTheOriginAndTheDefaultSweepIs90() {
        val common = PatternCommon(
            origin = AxisPoint(mm(1.0), mm(2.0)),
            reference = PositionReference.FirstPoint,
            convention = legacy,
        )
        val result = ArcCut.create(common, ArcCutInput(mm(20.0), mm(6.0), mm(2.0), startArcSec = 0.0))
        val group = assertIs<PatternResult.Success>(result).group
        assertEquals("Arc", group.groupName)
        assertEquals(17, group.points.size)
        assertMm(1.0, group.points[0].x)
        assertMm(2.0, group.points[0].y)
        assertMm(1.0 + 23.0, group.points.last().x)
        assertMm(2.0 - 23.0, group.points.last().y)
    }

    @Test
    fun theStandardConventionAppliesToTheArc() {
        val result = ArcCut.geometry(mm(20.0), mm(6.0), mm(2.0), 0.0, deg(90.0), CutType.External, AngleReference.Center, PatternAngleConvention.Standard)
        val cut = assertIs<ArcGeometryResult.Success>(result).geometry
        assertMm(23.0, cut.points[0].h)
        assertMm(0.0, cut.points[0].v)
        assertMm(0.0, cut.points.last().h)
        assertMm(23.0, cut.points.last().v)
    }

    @Test
    fun theDefaultCutterIsTheActiveRadiusCompensationDiameterOfAMill() {
        assertEquals(10000.0, ArcCut.defaultCutterDiameterUm(MachineType.VerticalMill, 10000.0))
        assertNull(ArcCut.defaultCutterDiameterUm(MachineType.VerticalMill, 0.0))
        assertNull(ArcCut.defaultCutterDiameterUm(MachineType.Lathe, 10000.0))
        assertEquals("Radius/Arc Cutting", ArcCut.title().text)
    }
}
