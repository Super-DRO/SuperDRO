package org.superdro.core.functions.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.PointSource

class MeasurementSessionTest {

    private fun latch(x: Double, y: Double) = CollectedPoint(x = x, y = y, source = PointSource.Latch)

    private fun probe(x: Double, y: Double) =
        CollectedPoint(x = x, y = y, source = PointSource.Probe, tipRadiusUm = 1000.0, approachX = 1, approachY = 0)

    @Test
    fun theSlotsFillInOrderAndAClearedSlotIsArmedAgain() {
        var session = MeasurementSession.open(MeasurementVerb.Circle, 20.0)
        assertEquals(listOf(SlotState.Armed, SlotState.Blank, SlotState.Blank), (0..2).map(session::slotState))
        val first = session.capture(latch(0.0, 0.0))
        assertEquals(CaptureStatus.Added, first.status)
        session = first.session.capture(latch(40000.0, 0.0)).session
        assertEquals(listOf(SlotState.Filled, SlotState.Filled, SlotState.Armed), (0..2).map(session::slotState))
        val third = session.capture(latch(0.0, 30000.0))
        assertEquals(CaptureStatus.Completed, third.status)
        session = third.session
        assertNull(session.armedSlot)
        val extra = session.capture(latch(1.0, 1.0))
        assertEquals(CaptureStatus.NotAdded, extra.status)
        assertSame(session, extra.session)
        session = session.clearSlot(1)
        assertEquals(1, session.armedSlot)
        assertEquals(listOf(SlotState.Filled, SlotState.Armed, SlotState.Filled), (0..2).map(session::slotState))
        session = session.capture(latch(40000.0, 0.0)).session
        assertEquals(40000.0, session.slots[1]!!.x)
        assertEquals(true, session.reset().isEmpty)
    }

    @Test
    fun theMessageLineGivesTheStepTheProbeErrorTheFailureAndTheNotes() {
        var session = MeasurementSession.open(MeasurementVerb.Circle, 20.0)
        assertEquals(MeasurementMessage.Step(MeasurementStep.LocateAPointOnTheCircle), session.message())
        session = session.capture(latch(0.0, 0.0)).session
        assertEquals(MeasurementMessage.Step(MeasurementStep.LocateASecondPointWellApart), session.message())
        session = session.withProbeError(ProbeRefusal.NoApproach)
        assertEquals(MeasurementMessage.ProbeError(ProbeRefusal.NoApproach), session.message())
        session = session.capture(latch(1.0, 0.0)).session
        assertEquals(MeasurementMessage.Step(MeasurementStep.LocateTheThirdPoint), session.message())
        session = session.capture(latch(2.0, 0.0)).session
        assertEquals(MeasurementMessage.Failure(MeasurementFailure.Collinear), session.message())
        val good = MeasurementSession.open(MeasurementVerb.Point, 20.0).capture(latch(1.0, 1.0)).session
        assertNull(good.message())
    }

    @Test
    fun aProbePointInPointOrDistanceGetsTheUncompensatedNote() {
        val distance = MeasurementSession.open(MeasurementVerb.Distance, 20.0)
            .capture(probe(0.0, 0.0)).session
            .capture(latch(30000.0, 40000.0)).session
        assertEquals(listOf("UNCOMPENSATED"), distance.slots[0]!!.notes)
        assertEquals(emptyList(), distance.slots[1]!!.notes)
        assertEquals(SlotState.FilledSuspect, distance.slotState(0))
        assertEquals(listOf("UNCOMPENSATED"), distance.systemNotes())
        assertEquals(MeasurementMessage.Notes(listOf(NoteMessage(PointNote.Uncompensated))), distance.message())
        val circle = MeasurementSession.open(MeasurementVerb.Circle, 20.0).capture(probe(0.0, 0.0)).session
        assertEquals(emptyList(), circle.slots[0]!!.notes)
    }

    // More than 2 arc seconds
    @Test
    fun aFrameChangeOfMoreThanTwoArcSecondsGivesTheFrameNote() {
        val start = FrameAngles(alignmentArcSec = 100.0, rotationArcSec = 0.0)
        val opened = MeasurementSession.open(MeasurementVerb.Distance, 20.0).capture(latch(0.0, 0.0), start).session
        val changed = opened.capture(latch(1.0, 1.0), FrameAngles(alignmentArcSec = 102.1)).session
        assertEquals(listOf("FRAME_CHANGED"), changed.slots[1]!!.notes)
        val message = changed.message() as MeasurementMessage.Notes
        assertEquals(PointNote.FrameChanged, message.notes.single().note)
        assertEquals(2.1 / 3600.0, message.notes.single().angleChangeDegrees!!, 1e-12)
        val same = opened.capture(latch(1.0, 1.0), FrameAngles(alignmentArcSec = 102.0)).session
        assertEquals(emptyList(), same.slots[1]!!.notes)
        val rotated = opened.capture(latch(1.0, 1.0), FrameAngles(alignmentArcSec = 100.0, rotationArcSec = -2.5)).session
        assertEquals(listOf("FRAME_CHANGED"), rotated.slots[1]!!.notes)
    }

    // The reduction to −180° to +180°
    @Test
    fun theFrameChangeIsReducedToAHalfTurn() {
        val start = FrameAngles(alignmentArcSec = 1_295_999.0)
        assertEquals(2.0, FrameAngles(alignmentArcSec = 1.0).changeFrom(start), 1e-9)
        val opened = MeasurementSession.open(MeasurementVerb.Distance, 20.0).capture(latch(0.0, 0.0), start).session
        val next = opened.capture(latch(1.0, 1.0), FrameAngles(alignmentArcSec = 1.0)).session
        assertEquals(emptyList(), next.slots[1]!!.notes)
    }

    @Test
    fun theLogRulesFollowTheSetAndTheNote() {
        val empty = MeasurementSession.open(MeasurementVerb.Point, 20.0)
        assertEquals(false, empty.canLog)
        val complete = empty.capture(latch(1.0, 2.0)).session
        assertEquals(true, complete.canLog)
        val logged = complete.markLogged(7)
        assertEquals(false, logged.canLog)
        assertEquals(7, logged.loggedSequence)
        assertEquals(true, logged.withNote("Bore A").canLog)
        assertSame(logged, logged.withNote(""))
        assertEquals(true, logged.clearSlot(0).capture(latch(1.0, 2.0)).session.canLog)
        val collinear = MeasurementSession.open(MeasurementVerb.Circle, 20.0)
            .capture(latch(0.0, 0.0)).session
            .capture(latch(1.0, 0.0)).session
            .capture(latch(2.0, 0.0)).session
        assertEquals(false, collinear.canLog)
    }

    @Test
    fun theFunctionsHaveTheirWireIdsAndSlots() {
        assertEquals(
            listOf("point", "distance_2_points", "circle_from_3_points", "line_angle", "angle_between_lines"),
            MeasurementVerb.entries.map { verb -> verb.wire },
        )
        assertEquals(listOf(1, 2, 3, 2, 4), MeasurementVerb.entries.map { verb -> verb.slotCount })
        assertEquals(listOf(0, 0, 1, 1), MeasurementVerb.AngleBetweenLines.slotGroups)
        assertEquals(MeasurementVerb.LineAngle, MeasurementVerb.ofWire("line_angle"))
    }
}
