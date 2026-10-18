package org.superdro.core.functions.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.model.ThreadSpec

class ThreadPointsTest {

    private val now = Instant.fromEpochMilliseconds(1_760_000_000_000)

    private fun spec(w1: Double?, w2: Double?, internal: Boolean = false): ThreadSpec = assertNotNull(
        ThreadSetup.toSpec(
            ThreadSetupInput(
                form = ThreadForm.IsoMetricCoarse,
                internal = internal,
                size = "M10 × 1.5",
                threadClass = if (internal) ThreadClass.Class6H else ThreadClass.Class6g,
                cutter = ThreadCutter.SharpV,
                startZUm = 0.0,
                lengthUm = 20000.0,
                leftReliefUm = w1,
                rightReliefUm = w2,
                tag = "Thread 1",
            ),
            workspaceId = "w1",
            now = now,
        ),
    )

    @Test
    fun theExampleThreadHasItsGuidesAtTheExpectedPositions() {
        val points = ThreadPoints.build(spec(w1 = 4500.0, w2 = null))
        assertEquals(17, points.size)
        val byName = points.associateBy { it.name }
        assertEquals(0.0, byName.getValue("Thread 1 - Z₁").z)
        assertEquals(-15500.0, byName.getValue("Thread 1 - Z₂").z)
        assertEquals(-20000.0, byName.getValue("Thread 1 - W₁").z)
        assertEquals(4984.0, byName.getValue("Thread 1 - D").x!!, 1e-9)
        assertEquals(-4984.0, byName.getValue("Thread 1 - D Back").x!!, 1e-9)
        assertEquals(3847.3, byName.getValue("Thread 1 - D₁").x!!, 0.05)
        assertEquals(ThreadPointRole.ReliefInnerLeft, byName.getValue("Thread 1 - W₁ Inner").role)
        assertEquals(-15500.0, byName.getValue("Thread 1 - W₁ Inner").z)
        assertEquals(3847.3, byName.getValue("Thread 1 - W₁ Inner").x!!, 0.05)
        assertEquals(-20000.0, byName.getValue("Thread 1 - L End D").z)
        assertEquals(0.0, byName.getValue("Thread 1 - R End D₁ Back").z)
    }

    @Test
    fun aThreadWithNoReliefsHas14PointsAndWithBothReliefs20() {
        assertEquals(14, ThreadPoints.build(spec(w1 = null, w2 = null)).size)
        assertEquals(20, ThreadPoints.build(spec(w1 = 3000.0, w2 = 1000.0)).size)
        val roles = ThreadPoints.build(spec(w1 = null, w2 = null)).map { it.role.wire }.toSet()
        assertEquals(
            setOf("z_start", "z_stop", "d_major", "d_minor", "c_extent_l_major", "c_extent_l_minor", "c_extent_r_major", "c_extent_r_minor"),
            roles,
        )
        assertEquals(ThreadPointRole.WRight, ThreadPointRole.fromWire("w_right"))
    }

    @Test
    fun theArmStepUsesTheFourFinishCornersAtTheRootDiameter() {
        val external = spec(w1 = null, w2 = null)
        assertEquals(ThreadPointRole.ExtentLeftMinor to ThreadPointRole.ExtentRightMinor, ThreadPoints.cornerRoles(external))
        val corners = ThreadPoints.finishCorners(external)
        assertEquals(4, corners.size)
        val active = assertNotNull(ThreadPoints.activeCorner(external))
        assertEquals(external.cutToDiameterUm / 2.0, active.x!!, 1e-9)
        assertEquals(-20000.0, active.z)
        val relieved = spec(w1 = 4500.0, w2 = 1000.0, internal = true)
        assertEquals(ThreadPointRole.ReliefInnerLeft to ThreadPointRole.ReliefInnerRight, ThreadPoints.cornerRoles(relieved))
        val inner = assertNotNull(ThreadPoints.activeCorner(relieved))
        assertEquals(relieved.cutToDiameterUm / 2.0, inner.x!!, 1e-9)
        assertEquals(relieved.stopZUm, inner.z)
        assertEquals(ThreadPointRole.ExtentLeftMajor, ThreadPoints.cornerRoles(spec(w1 = null, w2 = null, internal = true)).first)
    }

    @Test
    fun theTagAndTheArmListFollowTheRules() {
        assertEquals("Thread_1", ThreadPoints.subDatumTag("Thread 1"))
        val good = spec(w1 = null, w2 = null)
        val flat = good.copy(id = "flat", stopZUm = good.startZUm)
        assertEquals(listOf(good), ThreadPoints.armable(listOf(good, flat)))
    }

    @Test
    fun theSubDatumsCarryTheThreadIdAndTheRole() {
        val thread = spec(w1 = 4500.0, w2 = null).copy(id = "t-1")
        val subDatums = ThreadPoints.toSubDatums(thread, now)
        assertEquals(17, subDatums.size)
        val first = subDatums.first()
        assertEquals("Thread 1 - Z₁", first.name)
        assertEquals(listOf("Thread_1"), first.tags)
        assertEquals(SubDatumGroupKind.Thread, first.groupKind)
        assertEquals("t-1:z_start", first.groupRef)
        assertEquals("t-1" to ThreadPointRole.ZStart, ThreadPoints.parseGroupRef(first.groupRef))
        assertEquals(null, ThreadPoints.parseGroupRef("t-1:other"))
        assertEquals(4, ThreadPoints.cornerSubDatums(thread, subDatums).size)
        assertEquals(false, ThreadPoints.needsRebuild(thread, subDatums))
        assertEquals(true, ThreadPoints.needsRebuild(thread, subDatums.drop(1).filter { it.name != "Thread 1 - W₁ Inner" }))
        assertEquals(true, ThreadPoints.needsRebuild(thread.copy(id = "t-2"), subDatums))
    }

    @Test
    fun leftHandThreadSelectsTheFinishCornerAtZMax() {
        val rightHand = spec(w1 = 4500.0, w2 = 1500.0)
        val leftHand = rightHand.copy(leftHand = true)

        val rhCorner = assertNotNull(ThreadPoints.activeCorner(rightHand))
        assertEquals(ThreadPointRole.ReliefInnerLeft, rhCorner.role)
        assertEquals(rightHand.stopZUm, rhCorner.z)
        assertEquals(rightHand.cutToDiameterUm / 2.0, rhCorner.x!!, 1e-9)

        val lhCorner = assertNotNull(ThreadPoints.activeCorner(leftHand))
        assertEquals(ThreadPointRole.ReliefInnerRight, lhCorner.role)
        assertEquals(leftHand.startZUm, lhCorner.z)
        assertEquals(leftHand.cutToDiameterUm / 2.0, lhCorner.x!!, 1e-9)
    }

    @Test
    fun multiStartThreadGivesAxialAndSpindleAngleOffsetsForEachStart() {
        val single = spec(w1 = null, w2 = null).copy(starts = 1)
        assertEquals(
            listOf(ThreadStartOffset(startNumber = 1, zOffsetUm = 0.0, spindleAngleArcSec = 0.0)),
            ThreadPoints.startOffsets(single),
        )

        val triple = spec(w1 = null, w2 = null).copy(starts = 3, pitchUm = 1500.0)
        val offsets = ThreadPoints.startOffsets(triple)
        assertEquals(3, offsets.size)
        assertEquals(ThreadStartOffset(1, 0.0, 0.0), offsets[0])
        assertEquals(ThreadStartOffset(2, 1500.0, 432_000.0), offsets[1])
        assertEquals(ThreadStartOffset(3, 3000.0, 864_000.0), offsets[2])
    }
}
