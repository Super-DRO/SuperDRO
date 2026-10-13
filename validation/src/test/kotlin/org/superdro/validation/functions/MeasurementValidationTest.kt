package org.superdro.validation.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.measurement.ApproachSign
import org.superdro.core.functions.measurement.CircleFit
import org.superdro.core.functions.measurement.FittedLine
import org.superdro.core.functions.measurement.FrameAngles
import org.superdro.core.functions.measurement.HistorySample
import org.superdro.core.functions.measurement.LineFit
import org.superdro.core.functions.measurement.MeasurementFailure
import org.superdro.core.functions.measurement.MeasurementLog
import org.superdro.core.functions.measurement.MeasurementMath
import org.superdro.core.functions.measurement.MeasurementSession
import org.superdro.core.functions.measurement.MeasurementTolerance
import org.superdro.core.functions.measurement.MeasurementVerb
import org.superdro.core.functions.measurement.PointCapture
import org.superdro.core.functions.measurement.ProbeCapture
import org.superdro.core.functions.measurement.ProbeRefusal
import kotlin.time.Instant
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.LogDisplayFormat
import org.superdro.core.model.PointSource
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.validation.functions.Expect.DEG2

/**
 * The fits of the measurement mode, with the worked examples.
 */
class MeasurementValidationTest {

    private val p1 = CollectedPoint(0.0, 0.0)
    private val p2 = CollectedPoint(40_000.0, 0.0)
    private val p3 = CollectedPoint(0.0, 30_000.0)

    private fun probe(x: Double, y: Double, ax: Int, ay: Int, tip: Double = 1_000.0) =
        CollectedPoint(x, y, source = PointSource.Probe, tipRadiusUm = tip, approachX = ax, approachY = ay)

    @Test
    fun tolerance_is_4_times_the_largest_step_of_the_plane_axes() {
        assertEquals(20.0, MeasurementTolerance.toleranceUm(listOf(5_080.0, 5_080.0)), 1e-9)
        assertEquals(20.0, MeasurementTolerance.toleranceUm(listOf(25_400.0, 5_080.0)), 1e-9)
        assertEquals(4.0, MeasurementTolerance.toleranceUm(listOf(null, 25_400.0)), 1e-9, "no resolution gives 1 µm")
        assertEquals(4.0, MeasurementTolerance.toleranceUm(emptyList()), 1e-9)
    }

    @Test
    fun circle_from_3_points_with_no_tip() {
        val circle = assertIs<CircleFit.Fitted>(MeasurementMath.circleFrom3Points(p1, p2, p3, 20.0)).circle
        assertEquals(20_000.0, circle.centerXUm, 1e-6)
        assertEquals(15_000.0, circle.centerYUm, 1e-6)
        Expect.mm(50.0, circle.diameterUm, "Ø")
    }

    @Test
    fun tip_compensation_bore_52_mm_and_boss_48_mm() {
        val bore = assertIs<CircleFit.Fitted>(
            MeasurementMath.circleFrom3Points(probe(0.0, 0.0, -1, -1), probe(40_000.0, 0.0, 1, -1), probe(0.0, 30_000.0, -1, 1), 20.0),
        ).circle
        assertEquals(20_000.0, bore.centerXUm, 1e-6)
        assertEquals(15_000.0, bore.centerYUm, 1e-6)
        Expect.mm(52.0, bore.diameterUm, "bore Ø")
        assertEquals(-1, bore.side)
        val boss = assertIs<CircleFit.Fitted>(
            MeasurementMath.circleFrom3Points(probe(0.0, 0.0, 1, 1), probe(40_000.0, 0.0, -1, 1), probe(0.0, 30_000.0, 1, -1), 20.0),
        ).circle
        Expect.mm(48.0, boss.diameterUm, "boss Ø")
        assertEquals(1, boss.side)
    }

    @Test
    fun collinear_and_tip_fit_failures_have_separate_reasons() {
        val collinear = MeasurementMath.circleFrom3Points(
            CollectedPoint(0.0, 0.0), CollectedPoint(100_000.0, 0.0), CollectedPoint(200_000.0, 10.0), 20.0,
        )
        assertEquals(MeasurementFailure.Collinear, assertIs<CircleFit.Failed>(collinear).failure, "|D| / L = 5 µm < tol")
        // P1 says bore, P2 says boss: no candidate agrees with all approach signs.
        val disagree = MeasurementMath.circleFrom3Points(probe(0.0, 0.0, -1, -1), probe(40_000.0, 0.0, -1, 1), probe(0.0, 30_000.0, -1, 1), 20.0)
        val failure = assertIs<CircleFit.Failed>(disagree).failure
        assertTrue(failure != MeasurementFailure.Collinear, "a tip fit failure is not the collinear message: $failure")
    }

    @Test
    fun line_angle_example_0_57_and_minus_89_43() {
        val line = assertIs<LineFit.Fitted>(MeasurementMath.lineFrom2Points(CollectedPoint(0.0, 0.0), CollectedPoint(100_000.0, 1_000.0), 20.0)).line
        Expect.near(0.57, line.angleToX, DEG2, "angle to X")
        Expect.near(-89.43, line.angleToY, DEG2, "angle to Y")
        val tooClose = MeasurementMath.lineFrom2Points(CollectedPoint(0.0, 0.0), CollectedPoint(19.0, 0.0), 20.0)
        assertEquals(MeasurementFailure.PointsTooClose, assertIs<LineFit.Failed>(tooClose).failure)
    }

    @Test
    fun line_tip_example_is_the_common_tangent() {
        val line = assertIs<LineFit.Fitted>(
            MeasurementMath.lineFrom2Points(probe(0.0, 1_000.0, 0, -1), probe(50_000.0, 1_000.0, 0, -1), 20.0),
        ).line
        assertEquals(0.0, line.startXUm, 1e-6)
        assertEquals(0.0, line.startYUm, 1e-6)
        assertEquals(50_000.0, line.endXUm, 1e-6)
        assertEquals(0.0, line.endYUm, 1e-6)
        Expect.near(0.0, line.angleToX, DEG2, "angle to X")
    }

    @Test
    fun angle_between_lines_example() {
        val l1 = FittedLine(0.0, 0.0, 100_000.0, 0.0)
        val l2 = FittedLine(100_000.0, 10_000.0, 90_000.0, 27_321.0)
        Expect.near(-60.0007, l2.angleToX, 0.00005, "a2")
        val angle = MeasurementMath.includedAngle(l1.angleToX, l2.angleToX)
        Expect.near(120.00, angle, DEG2, "angle")
        Expect.near(60.00, 180.0 - angle, DEG2, "supplement")
        val corner = assertNotNull(MeasurementMath.corner(l1, l2))
        Expect.mm(105.773, corner.h, "corner X")
        Expect.mm(0.0, corner.v, "corner Y")
        assertTrue(MeasurementMath.cornerAddEnabled(corner, l1, l2))
        // Parallel lines give an angle but no corner.
        assertNull(MeasurementMath.corner(l1, FittedLine(0.0, 10.0, 100_000.0, 10.0)))
        assertEquals(0.0, MeasurementMath.includedAngle(0.0, 0.0))
    }

    @Test
    fun line_angles_are_in_minus_90_to_90_and_the_included_angle_in_0_to_180() {
        assertEquals(90.0, MeasurementMath.angleToX(0.0, 1.0), 1e-12, "90° is in (−90°, 90°]")
        assertEquals(90.0, MeasurementMath.angleToX(0.0, -1.0), 1e-12, "−90° goes to 90°")
        assertEquals(-45.0, MeasurementMath.angleToX(-1.0, 1.0), 1e-12)
        assertEquals(170.0, MeasurementMath.includedAngle(10.0, 0.0), 1e-12, "[0°, 180°)")
    }

    @Test
    fun approach_sign_5_um_in_1000_ms_with_no_gap_of_more_than_500_ms() {
        val t = 100_000L
        assertEquals(1, ApproachSign.of(listOf(HistorySample(t - 300, 0.0), HistorySample(t - 100, 10.0), HistorySample(t, 20.0)), t))
        assertEquals(-1, ApproachSign.of(listOf(HistorySample(t - 300, 20.0), HistorySample(t - 100, 10.0), HistorySample(t, 0.0)), t))
        assertEquals(0, ApproachSign.of(listOf(HistorySample(t - 300, 0.0), HistorySample(t, 4.0)), t), "less than 5 µm")
        assertEquals(0, ApproachSign.of(listOf(HistorySample(t - 800, 0.0), HistorySample(t - 200, 10.0), HistorySample(t, 10.0)), t), "a gap of 600 ms")
        assertEquals(0, ApproachSign.of(listOf(HistorySample(t - 1_200, 0.0), HistorySample(t - 1_100, 50.0), HistorySample(t - 700, 50.0), HistorySample(t - 300, 50.0), HistorySample(t, 50.0)), t), "older than 1000 ms")
    }

    @Test
    fun probe_point_refusals_and_the_tip_radius() {
        assertEquals(ProbeCapture.Refused(ProbeRefusal.NoApproach), PointCapture.probe(1.0, 2.0, null, 0, 0, null, 1_000.0))
        assertEquals(ProbeCapture.Refused(ProbeRefusal.PositionUnknown), PointCapture.probe(Double.NaN, 2.0, null, 1, 0, null, 1_000.0))
        assertIs<ProbeCapture.Collected>(PointCapture.probe(1.0, 2.0, null, 0, -1, null, 1_000.0))
        assertEquals(1_000.0, PointCapture.tipRadiusUm(ProbeProfile(type = ProbeType.TouchProbe, stylusDiameterUm = 3_000.0, effectiveDiameterUm = 2_000.0)))
        assertEquals(1_500.0, PointCapture.tipRadiusUm(ProbeProfile(type = ProbeType.EdgeFinder2D, stylusDiameterUm = 3_000.0)))
        assertNull(PointCapture.tipRadiusUm(ProbeProfile(type = ProbeType.Camera, stylusDiameterUm = 3_000.0)), "only a touch probe and a 2D edge finder")
    }

    @Test
    fun log_keeps_the_stored_format_turns_neither_points_nor_results_and_starts_at_1() {
        assertEquals(1, MeasurementLog.nextSequence(emptyList()), "the sequence starts at 1 in each workspace")
        assertEquals(4, MeasurementLog.nextSequence(listOf(1, 3, 2)))
        var session = MeasurementSession.open(MeasurementVerb.Circle, 20.0)
        for (p in listOf(p1, p2, p3)) session = session.capture(p, FrameAngles(alignmentArcSec = 30 * 3600.0)).session
        val display = LogDisplayFormat(metric = false, precision = 4)
        val entry = assertNotNull(
            MeasurementLog.buildEntry(session, "Circle from 3 points", "w", "Default Workspace", 1, display, 30 * 3600.0, Instant.parse("2026-10-12T00:00:00Z")),
        )
        assertEquals(display, entry.display, "the log keeps the format of the log time")
        assertEquals(listOf(p1, p2, p3).map { it.x to it.y }, entry.points.map { it.x to it.y }, "the points are not turned")
        val center = entry.results.single { it.x != null }
        assertEquals(20_000.0, center.x!!, 1e-6, "the results are not turned")
        assertEquals(15_000.0, center.y!!, 1e-6)
        assertEquals(1, session.markLogged(1).loggedSequence, "the sequence number shows")
    }

    @Test
    fun slots_fill_in_order_and_the_notes() {
        var session = MeasurementSession.open(MeasurementVerb.Distance, 20.0)
        session = session.capture(probe(0.0, 0.0, 1, 0), FrameAngles()).session
        assertEquals(1, session.armedSlot)
        assertTrue("UNCOMPENSATED" in session.slots[0]!!.notes, "a probe point in Distance is not compensated")
        session = session.capture(CollectedPoint(10.0, 0.0), FrameAngles(alignmentArcSec = 3.0)).session
        assertTrue("FRAME_CHANGED" in session.slots[1]!!.notes, "more than 2″")
        assertTrue(session.isComplete)
        val cleared = session.clearSlot(0)
        assertEquals(0, cleared.armedSlot, "a long press clears one slot")
        val refilled = cleared.capture(CollectedPoint(5.0, 5.0), FrameAngles()).session
        assertEquals(5.0, refilled.slots[0]!!.x, "a capture goes to the first empty slot")
        assertEquals(10.0, refilled.slots[1]!!.x)
        val small = MeasurementSession.open(MeasurementVerb.Distance, 20.0)
            .capture(CollectedPoint(0.0, 0.0), FrameAngles()).session
            .capture(CollectedPoint(10.0, 0.0), FrameAngles(alignmentArcSec = 2.0)).session
        assertFalse("FRAME_CHANGED" in small.slots[1]!!.notes, "2″ is not more than 2″")
        val circle = MeasurementSession.open(MeasurementVerb.Circle, 20.0).capture(probe(0.0, 0.0, 1, 1), FrameAngles()).session
        assertFalse("UNCOMPENSATED" in circle.slots[0]!!.notes, "the circle compensates the tip")
    }
}
