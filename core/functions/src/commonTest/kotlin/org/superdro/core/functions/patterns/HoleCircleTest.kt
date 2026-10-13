package org.superdro.core.functions.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.superdro.core.functions.TestValues.assertDegrees
import org.superdro.core.functions.TestValues.assertMm
import org.superdro.core.functions.TestValues.deg
import org.superdro.core.functions.TestValues.mm
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.Plane
import org.superdro.core.model.Workspace

/** The hole circle. */
class HoleCircleTest {

    private val center = AxisPoint(mm(100.0), mm(50.0), null)

    private fun legacy(reference: PositionReference = PositionReference.Center) = PatternCommon(
        origin = center,
        reference = reference,
        convention = PatternAngleConvention.Legacy,
    )

    private fun create(common: PatternCommon, input: HoleCircleInput): PatternGroup =
        assertIs<PatternResult.Success>(HoleCircle.create(common, input)).group

    @Test
    fun theSixHoleLegacyTableOfTheWorkedExample() {
        val group = create(legacy(), HoleCircleInput(radiusUm = mm(50.0), holeCount = 6, startArcSec = 0.0, sweepArcSec = deg(360.0)))
        val batch = PatternBatch.create(Workspace(), group, groupId = 1L)
        val expected = listOf(
            Triple("Circle - 1", 100.000, 100.000),
            Triple("Circle - 2", 143.301, 75.000),
            Triple("Circle - 3", 143.301, 25.000),
            Triple("Circle - 4", 100.000, 0.000),
            Triple("Circle - 5", 56.699, 25.000),
            Triple("Circle - 6", 56.699, 75.000),
        )
        assertEquals(expected.map { row -> row.first }, batch.subDatums.map { subDatum -> subDatum.name })
        expected.forEachIndexed { index, (name, x, y) ->
            assertMm(x, batch.subDatums[index].x, "$name X")
            assertMm(y, batch.subDatums[index].y, "$name Y")
            assertEquals(null, batch.subDatums[index].z, "$name Z")
        }
        val angles = HoleCircle.angles(6, 0.0, deg(360.0))
        listOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0).forEachIndexed { index, angle -> assertDegrees(angle, angles[index]) }
    }

    @Test
    fun theStandardConventionPutsHoleOneAtThreeOClock() {
        val common = PatternCommon(origin = center, convention = PatternAngleConvention.Standard)
        val group = create(common, HoleCircleInput(radiusUm = mm(50.0), holeCount = 6))
        assertMm(150.000, group.points[0].x)
        assertMm(50.000, group.points[0].y)
        assertMm(125.000, group.points[1].x)
        assertMm(93.301, group.points[1].y)
    }

    @Test
    fun aSweepOf90With4HolesGivesTheAngles0To90() {
        val angles = HoleCircle.angles(4, 0.0, deg(90.0))
        listOf(0.0, 30.0, 60.0, 90.0).forEachIndexed { index, angle -> assertDegrees(angle, angles[index]) }
    }

    @Test
    fun theFirstHoleReferencePutsHoleOneAtTheOrigin() {
        val group = create(legacy(PositionReference.FirstPoint), HoleCircleInput(radiusUm = mm(50.0), holeCount = 6))
        assertMm(100.000, group.points[0].x)
        assertMm(50.000, group.points[0].y)
        assertMm(143.301, group.points[1].x)
        assertMm(25.000, group.points[1].y)
    }

    @Test
    fun aSweepOfMinus360HasNoDuplicateHole() {
        val angles = HoleCircle.angles(6, 0.0, deg(-360.0))
        listOf(0.0, 300.0, 240.0, 180.0, 120.0, 60.0).forEachIndexed { index, angle -> assertDegrees(angle, angles[index]) }
        assertEquals(6, angles.map { angle -> kotlin.math.round(angle) }.toSet().size)
    }

    @Test
    fun aSweepOf0IsAFullCircleAndTheStartAngleIsNormalized() {
        assertEquals(HoleCircle.angles(4, 0.0, deg(360.0)), HoleCircle.angles(4, 0.0, 0.0))
        assertDegrees(330.0, HoleCircle.angles(2, deg(-30.0), deg(360.0))[0])
        assertDegrees(0.0, HoleCircle.angles(2, deg(-360.0), deg(360.0))[0])
        assertDegrees(360.0, PatternAngles.normalize(deg(360.0)))
        assertDegrees(350.0, PatternAngles.normalize(deg(-370.0)))
        assertDegrees(10.0, PatternAngles.normalize(deg(370.0)))
    }

    @Test
    fun theFieldLimitsGiveTheFieldMessages() {
        val failure = assertIs<PatternResult.Failure>(
            HoleCircle.create(
                PatternCommon(prefix = "ab"),
                HoleCircleInput(radiusUm = 0.0, holeCount = 101, startArcSec = deg(361.0), sweepArcSec = deg(-361.0)),
            ),
        )
        assertEquals(
            listOf(
                "Sub-Datum Prefix must be at least 3 characters long.",
                "Circle Radius value is too small",
                "Hole Count value is too large",
                "Start Angle value is too large",
                "Sweep Angle value is too small",
            ),
            failure.errors.map { error -> error.text },
        )
        val missing = assertIs<PatternResult.Failure>(HoleCircle.create(PatternCommon(), HoleCircleInput(null, 1)))
        assertEquals(listOf("Circle Radius value is required", "Hole Count value is too small"), missing.errors.map { it.text })
    }

    @Test
    fun aLatheUsesTheZxPlaneTheLegacyAnglesAndItsOwnTitle() {
        val common = PatternCommon(
            plane = Plane.XY,
            origin = AxisPoint(0.0, null, 0.0),
            machineType = MachineType.Lathe,
            convention = PatternAngleConvention.Standard,
        )
        val group = create(common, HoleCircleInput(radiusUm = mm(10.0), holeCount = 4))
        assertEquals("Circle (ZX)", group.groupName)
        // LEGACY: hole 1 is on +py, which the ZX map sends to Z.
        assertMm(0.0, group.points[0].x)
        assertMm(10.0, group.points[0].z)
        assertEquals(null, group.points[0].y)
        assertEquals("Circular Point Array", HoleCircle.title(MachineType.Lathe).text)
        assertEquals("Hole Circle", HoleCircle.title(MachineType.VerticalMill).text)
        val batch = PatternBatch.create(Workspace(), group, 1L)
        assertEquals("Circle (ZX) - 1", batch.subDatums.first().name)
    }

    @Test
    fun anXzPlaneOnAMillGetsTheSuffix() {
        val common = PatternCommon(plane = Plane.XZ, origin = AxisPoint(0.0, 5.0, 0.0), convention = PatternAngleConvention.Legacy)
        val group = create(common, HoleCircleInput(radiusUm = mm(10.0), holeCount = 4, startArcSec = deg(90.0)))
        assertEquals("Circle (XZ)", group.groupName)
        assertMm(10.0, group.points[0].x)
        assertEquals(5.0, group.points[0].y)
        assertMm(0.0, group.points[0].z)
    }
}
