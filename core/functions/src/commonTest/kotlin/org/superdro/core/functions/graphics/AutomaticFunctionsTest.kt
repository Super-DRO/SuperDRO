package org.superdro.core.functions.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.PointUm
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.model.ProximityTarget
import org.superdro.core.model.SubDatum

/** The automatic functions of the graphical view. */
class AutomaticFunctionsTest {

    @Test
    fun snapToPointSelectsAPointAt6350Um() {
        val tool = PointUm(0.0, 0.0)
        val atLimit = listOf(ViewPoint("a", PointUm(6350.0, 0.0), 1))
        assertEquals("a", SnapToPoint.select(tool, atLimit, selectedId = null))
        val diagonal = listOf(ViewPoint("d", PointUm(4490.0, 4490.0), 1))
        assertEquals("d", SnapToPoint.select(tool, diagonal, selectedId = null))
        val outside = listOf(ViewPoint("b", PointUm(6350.01, 0.0), 1), ViewPoint("c", PointUm(4500.0, 4500.0), 2))
        assertNull(SnapToPoint.select(tool, outside, selectedId = null))
    }

    @Test
    fun snapToPointTakesTheNearestPointAndTheHigherRankOnEqualDistance() {
        val tool = PointUm(1000.0, 1000.0)
        val points = listOf(
            ViewPoint("far", PointUm(5000.0, 1000.0), 5),
            ViewPoint("near", PointUm(1000.0, 3000.0), 1),
        )
        assertEquals("near", SnapToPoint.select(tool, points, selectedId = null))
        val tie = listOf(ViewPoint("low", PointUm(3000.0, 1000.0), 3), ViewPoint("high", PointUm(-1000.0, 1000.0), 7))
        assertEquals("high", SnapToPoint.select(tool, tie, selectedId = null))
        assertEquals("high", SnapToPoint.select(tool, tie.reversed(), selectedId = null))
        // The nearest point is selected already: no change.
        assertNull(SnapToPoint.select(tool, points, selectedId = "near"))
    }

    @Test
    fun theVelocityNeedsMoreThanTwoSlopeSamplesInTheLast1000Ms() {
        val estimator = VelocityEstimator()
        estimator.add(0, 0.0)
        estimator.add(100, 100.0)
        estimator.add(200, 200.0)
        assertEquals(0.0, estimator.velocity(200))
        estimator.add(300, 300.0)
        assertEquals(1.0, estimator.velocity(300))
        // After 1000 ms with no sample, the old samples are gone.
        assertEquals(0.0, estimator.velocity(1301))
    }

    @Test
    fun snapToGuideSelectsTheGuideAheadPastTheMiddle() {
        val guides = listOf(
            ViewGuide("left", GuideOrientation.Vertical, 0.0),
            ViewGuide("right", GuideOrientation.Vertical, 10_000.0),
            ViewGuide("top", GuideOrientation.Horizontal, 50_000.0),
        )
        val snapper = GuideSnapper()
        assertNull(snapper.update(0, PointUm(6000.0, 0.0), guides))
        assertNull(snapper.update(100, PointUm(6100.0, 0.0), guides))
        assertNull(snapper.update(200, PointUm(6200.0, 0.0), guides))
        // Three slopes, a positive velocity, and pos > (0 + 10000)/2: the upper guide.
        assertEquals("right", snapper.update(300, PointUm(6300.0, 0.0), guides))
        assertNull(snapper.update(400, PointUm(6400.0, 0.0), guides))
        // Back to the left: no selection above the middle.
        assertNull(snapper.update(1500, PointUm(6000.0, 0.0), guides))
        assertNull(snapper.update(1600, PointUm(5800.0, 0.0), guides))
        assertNull(snapper.update(1700, PointUm(5600.0, 0.0), guides))
        assertNull(snapper.update(1800, PointUm(5400.0, 0.0), guides))
        assertEquals("left", snapper.update(1900, PointUm(4900.0, 0.0), guides))
    }

    @Test
    fun verticalMotionUsesHorizontalGuides() {
        val guides = listOf(
            ViewGuide("vertical", GuideOrientation.Vertical, 100.0),
            ViewGuide("horizontal", GuideOrientation.Horizontal, 100.0),
        )
        val snapper = GuideSnapper()
        for (step in 0..3) {
            val result = snapper.update(step * 100L, PointUm(0.0, step * 10.0), guides)
            if (step < 3) assertNull(result) else assertEquals("horizontal", result)
        }
    }

    @Test
    fun theAutomaticToolOffsetAtAGuide() {
        val xGuide = Guide(axis = AxisId.X, valueUm = 20_000.0)
        // The position is more than the guide value: sign −1.
        assertEquals(
            ToolOffsetChange(AxisId.X, -5000.0),
            AutoGuideActions.toolOffset(xGuide, Plane.XY, MachineType.VerticalMill, 30_000.0, 10_000.0),
        )
        assertEquals(
            ToolOffsetChange(AxisId.X, 5000.0),
            AutoGuideActions.toolOffset(xGuide, Plane.XY, MachineType.VerticalMill, 20_000.0, 10_000.0),
        )
        val yGuide = Guide(axis = AxisId.Y, valueUm = -1000.0)
        assertEquals(
            ToolOffsetChange(AxisId.Y, 3000.0),
            AutoGuideActions.toolOffset(yGuide, Plane.XY, MachineType.HorizontalMill, -2000.0, 6000.0),
        )
        assertNull(AutoGuideActions.toolOffset(xGuide, Plane.XZ, MachineType.VerticalMill, 0.0, 10_000.0))
        assertNull(AutoGuideActions.toolOffset(xGuide, Plane.ZX, MachineType.Lathe, 0.0, 10_000.0))
    }

    @Test
    fun theAutomaticProximityTargetIsZeroInIncrementalMode() {
        assertEquals(
            ProximityTarget(axis = AxisId.Y, targetUm = 0.0, absolute = false),
            AutoGuideActions.proximityTarget(Guide(axis = AxisId.Y, valueUm = 1234.0)),
        )
    }

    @Test
    fun theProximityTargetAtAPointHasTheTwoAxesOfThePlane() {
        val xy = AutoPointActions.proximityTarget(Plane.XY, "p1")
        assertEquals(ProximityTarget(AxisId.X, 0.0, absolute = false, secondAxis = AxisId.Y, pointId = "p1"), xy)
        assertEquals(listOf(AxisId.X, AxisId.Y), xy.axes)
        assertEquals(listOf(AxisId.Y, AxisId.Z), AutoPointActions.proximityTarget(Plane.YZ, "p1").axes)
        assertEquals(listOf(AxisId.Z, AxisId.X), AutoPointActions.proximityTarget(Plane.ZX, "p1").axes)
        // A guide target has one axis.
        assertEquals(listOf(AxisId.Y), AutoGuideActions.proximityTarget(Guide(axis = AxisId.Y, valueUm = 1234.0)).axes)
    }

    @Test
    fun thePlungeDetectorHasAHysteresisOf10000Um() {
        val detector = PlungeDetector()
        assertNull(detector.update(0.0))
        assertNull(detector.update(5000.0))
        assertNull(detector.update(-4999.0))
        assertEquals(PlungeEdge.Down(5000.0), detector.update(-5000.0))
        assertNull(detector.update(-30_000.0))
        assertNull(detector.update(-20_001.0))
        assertEquals(PlungeEdge.Up(-30_000.0), detector.update(-20_000.0))
        assertNull(detector.update(-29_999.0))
        assertEquals(PlungeEdge.Down(-20_000.0), detector.update(-30_000.0))
    }

    @Test
    fun aValidPlungeMovesXAndYEach250UmOrLess() {
        val watcher = PlungeWatcher()
        assertNull(watcher.update(0.0, 1000.0, 2000.0))
        assertNull(watcher.update(-10_000.0, 1000.0, 2000.0))
        assertNull(watcher.update(-25_000.0, 1100.0, 2100.0))
        assertEquals(Plunge(-25_000.0), watcher.update(-15_000.0, 1250.0, 1750.0))
        // A second plunge with a motion of 251 µm in Y is not valid.
        assertNull(watcher.update(-25_000.0, 1250.0, 1750.0))
        assertNull(watcher.update(-15_000.0, 1250.0, 2001.0))
        // An up edge without a down edge is not a plunge.
        val fresh = PlungeWatcher()
        assertNull(fresh.update(0.0, 0.0, 0.0))
        assertNull(fresh.update(20_000.0, 0.0, 0.0))
    }

    @Test
    fun thePlungeFunctionsRunOnlyOnAMillOutOfTheMeasurementMode() {
        assertTrue(PlungeWatcher.isActive(MachineType.VerticalMill, MachineMode.Cutting, automaticZero = true, automaticSubDatum = false))
        assertTrue(PlungeWatcher.isActive(MachineType.HorizontalMill, MachineMode.Cutting, automaticZero = false, automaticSubDatum = true))
        assertFalse(PlungeWatcher.isActive(MachineType.Lathe, MachineMode.Cutting, automaticZero = true, automaticSubDatum = true))
        assertFalse(PlungeWatcher.isActive(MachineType.VerticalMill, MachineMode.Measurement, automaticZero = true, automaticSubDatum = true))
        assertFalse(PlungeWatcher.isActive(MachineType.VerticalMill, MachineMode.Cutting, automaticZero = false, automaticSubDatum = false))
        assertEquals(listOf(AxisId.X, AxisId.Y), AutoPlungeActions.AUTO_ZERO_AXES)
    }

    /** Gives the absolute Z of the raw value [rawUm] with the axis sign [sign] and an absolute offset of 5000 µm. */
    private fun absoluteZ(rawUm: Double, sign: Int): Double = sign * rawUm + 5000.0

    /** Gives the plunge of the raw Z values [raw] with the axis sign [sign], at X = 1000 and Y = 2000. */
    private fun plungeOf(raw: List<Double>, sign: Int): Plunge? {
        val watcher = PlungeWatcher()
        return raw.map { value -> watcher.update(absoluteZ(value, sign), 1000.0, 2000.0) }.lastOrNull { plunge -> plunge != null }
    }

    @Test
    fun theAutomaticSubDatumIsAtTheBottomOfThePlunge() {
        // A normal Z sign: the raw count goes down with Z. The absolute Z goes 5000, −5000, −20000, −10000.
        val plunge = assertNotNull(plungeOf(listOf(0.0, -10_000.0, -25_000.0, -15_000.0), sign = 1))
        assertEquals(Plunge(bottomZUm = -20_000.0), plunge)
        val point = assertNotNull(AutoPlungeActions.autoSubDatum(plunge, 1000.0, 2000.0, zApplies = true, existing = emptyList()))
        assertEquals(AutoSubDatumPoint(1000.0, 2000.0, -20_000.0), point)
        val subDatum = point.toSubDatum(7)
        assertEquals("Point 7", subDatum.name)
        assertEquals(listOf(1000.0, 2000.0, -20_000.0), listOf(subDatum.x, subDatum.y, subDatum.z))
        val flat = AutoPlungeActions.autoSubDatum(plunge, 1000.0, 2000.0, zApplies = false, existing = emptyList())
        assertEquals(AutoSubDatumPoint(1000.0, 2000.0, null), flat)
        assertTrue(AutoPlungeActions.zApplies(zEnabled = true, MachineType.VerticalMill, use3dSubDatums = true))
        assertFalse(AutoPlungeActions.zApplies(zEnabled = true, MachineType.VerticalMill, use3dSubDatums = false))
        assertFalse(AutoPlungeActions.zApplies(zEnabled = false, MachineType.VerticalMill, use3dSubDatums = true))
    }

    @Test
    fun anInvertedRawDirectionAlsoGivesTheBottomOfThePlunge() {
        // The raw count goes up while Z goes down. The axis sign −1 gives the same absolute Z.
        val raw = listOf(0.0, 10_000.0, 25_000.0, 15_000.0)
        val plunge = assertNotNull(plungeOf(raw, sign = -1))
        assertEquals(Plunge(bottomZUm = -20_000.0), plunge)
        assertEquals(-20_000.0, AutoPlungeActions.autoSubDatum(plunge, 1000.0, 2000.0, zApplies = true, existing = emptyList())?.zUm)
        // The raw count itself goes up first, so a detector on the raw count finds no plunge.
        val rawWatcher = PlungeWatcher()
        assertTrue(raw.all { value -> rawWatcher.update(value, 1000.0, 2000.0) == null })
    }

    @Test
    fun noAutomaticSubDatumWithin250Um() {
        val plunge = Plunge(bottomZUm = -30_000.0)
        val near = listOf(SubDatum(x = 1250.0, y = 1750.0, z = -29_800.0))
        assertNull(AutoPlungeActions.autoSubDatum(plunge, 1000.0, 2000.0, zApplies = true, existing = near))
        val farInX = listOf(SubDatum(x = 1251.0, y = 2000.0, z = -30_000.0))
        assertNotNull(AutoPlungeActions.autoSubDatum(plunge, 1000.0, 2000.0, zApplies = true, existing = farInX))
        val farInZ = listOf(SubDatum(x = 1000.0, y = 2000.0, z = -29_749.0))
        assertNotNull(AutoPlungeActions.autoSubDatum(plunge, 1000.0, 2000.0, zApplies = true, existing = farInZ))
        // Without Z, only X and Y count.
        assertNull(AutoPlungeActions.autoSubDatum(plunge, 1000.0, 2000.0, zApplies = false, existing = farInZ))
    }
}
