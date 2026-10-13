package org.superdro.core.functions.thread

import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Instant
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.ThreadSpec

/**
 * The role of a thread point.
 *
 * @property wire The stored text of the role, for example `z_start`.
 */
enum class ThreadPointRole(val wire: String) {
    ZStart("z_start"),
    ZStop("z_stop"),
    WLeft("w_left"),
    WRight("w_right"),
    DMajor("d_major"),
    DMinor("d_minor"),
    ExtentLeftMajor("c_extent_l_major"),
    ExtentLeftMinor("c_extent_l_minor"),
    ExtentRightMajor("c_extent_r_major"),
    ExtentRightMinor("c_extent_r_minor"),
    ReliefInnerLeft("c_relief_inner_l"),
    ReliefInnerRight("c_relief_inner_r"),
    ;

    companion object {
        /** Gives the role of a stored text, or null. */
        fun fromWire(wire: String): ThreadPointRole? = entries.firstOrNull { role -> role.wire == wire }
    }
}

/**
 * One sub-datum of a thread, in µm. X is a radius. A point with only
 * [z] is a Z guide. A point with only [x] is an X guide. A "Back" point has a negative X.
 *
 * @property name The sub-datum name, for example `Thread 1 - Z₁` or `Thread 1 - D Back`.
 */
data class ThreadPoint(
    val name: String,
    val role: ThreadPointRole,
    val x: Double?,
    val z: Double?,
    val back: Boolean = false,
)

/**
 * The thread points as sub-datums with the group kind `Thread`
 * and the four finish corners of the thread arm.
 */
object ThreadPoints {

    /** Gives the sub-datum tag of a thread: the tag with each white space changed to `_`. */
    fun subDatumTag(tag: String): String = tag.replace(Regex("\\s"), "_")

    /**
     * Gives the points of the thread. A thread with no reliefs has 14 points. A thread with both
     * reliefs has 20 points. `R = D / 2`, `r1 = D₁ / 2`, `Zmin = min(StartZ, StopZ)`,
     * `Zmax = max(StartZ, StopZ)`.
     */
    fun build(spec: ThreadSpec): List<ThreadPoint> {
        val tag = spec.tag
        val r = spec.majorDiameterUm / 2.0
        val r1 = spec.minorDiameterUm / 2.0
        val zMin = min(spec.startZUm, spec.stopZUm)
        val zMax = max(spec.startZUm, spec.stopZUm)
        val w1 = spec.leftReliefUm?.takeIf { it > 0.0 }
        val w2 = spec.rightReliefUm?.takeIf { it > 0.0 }
        val leftEnd = zMin - (w1 ?: 0.0)
        val rightEnd = zMax + (w2 ?: 0.0)
        val inner = if (spec.internal) r else r1
        val points = mutableListOf<ThreadPoint>()
        fun pair(label: String, role: ThreadPointRole, x: Double, z: Double?) {
            points += ThreadPoint("$tag - $label", role, x, z)
            points += ThreadPoint("$tag - $label Back", role, -x, z, back = true)
        }
        points += ThreadPoint("$tag - Z₁", ThreadPointRole.ZStart, null, spec.startZUm)
        points += ThreadPoint("$tag - Z₂", ThreadPointRole.ZStop, null, spec.stopZUm)
        if (w1 != null) points += ThreadPoint("$tag - W₁", ThreadPointRole.WLeft, null, leftEnd)
        if (w2 != null) points += ThreadPoint("$tag - W₂", ThreadPointRole.WRight, null, rightEnd)
        pair("D", ThreadPointRole.DMajor, r, null)
        pair("D₁", ThreadPointRole.DMinor, r1, null)
        pair("L End D", ThreadPointRole.ExtentLeftMajor, r, leftEnd)
        pair("L End D₁", ThreadPointRole.ExtentLeftMinor, r1, leftEnd)
        pair("R End D", ThreadPointRole.ExtentRightMajor, r, rightEnd)
        pair("R End D₁", ThreadPointRole.ExtentRightMinor, r1, rightEnd)
        if (w1 != null) pair("W₁ Inner", ThreadPointRole.ReliefInnerLeft, inner, zMin)
        if (w2 != null) pair("W₂ Inner", ThreadPointRole.ReliefInnerRight, inner, zMax)
        return points
    }

    /**
     * Gives the roles of the left and the right finish corner: the
     * relief corner when the relief is set, else the extent corner at the root diameter (major
     * for an internal thread, minor for an external thread).
     */
    fun cornerRoles(spec: ThreadSpec): Pair<ThreadPointRole, ThreadPointRole> {
        val left = when {
            (spec.leftReliefUm ?: 0.0) > 0.0 -> ThreadPointRole.ReliefInnerLeft
            spec.internal -> ThreadPointRole.ExtentLeftMajor
            else -> ThreadPointRole.ExtentLeftMinor
        }
        val right = when {
            (spec.rightReliefUm ?: 0.0) > 0.0 -> ThreadPointRole.ReliefInnerRight
            spec.internal -> ThreadPointRole.ExtentRightMajor
            else -> ThreadPointRole.ExtentRightMinor
        }
        return left to right
    }

    /**
     * Gives the 4 finish corners of the thread: (±root radius, Zmin) and (±root radius, Zmax).
     */
    fun finishCorners(spec: ThreadSpec): List<ThreadPoint> {
        val (left, right) = cornerRoles(spec)
        return build(spec).filter { point -> point.role == left || point.role == right }
    }

    /**
     * Gives the corner that the arm step activates: the corner with
     * X > 0 and the smallest Z. The X and Z readouts then show the distance to it.
     */
    fun activeCorner(spec: ThreadSpec): ThreadPoint? =
        finishCorners(spec).filter { point -> (point.x ?: 0.0) > 0.0 }.minByOrNull { point -> point.z ?: Double.MAX_VALUE }

    /**
     * Gives the group reference of a thread point: `<thread id>:<role>`, for example
     * `0b6f…:z_start`. [SubDatum.groupRef] holds the thread id and the role in one text.
     */
    fun groupRef(threadId: String, role: ThreadPointRole): String = "$threadId:${role.wire}"

    /** Reads a group reference of [groupRef]. Gives the thread id and the role, or null. */
    fun parseGroupRef(ref: String?): Pair<String, ThreadPointRole>? {
        if (ref == null) return null
        val split = ref.lastIndexOf(':')
        if (split <= 0) return null
        val role = ThreadPointRole.fromWire(ref.substring(split + 1)) ?: return null
        return ref.substring(0, split) to role
    }

    /**
     * Makes the sub-datums of the thread: the tag with `_` for each
     * white space, the group kind `Thread`, and the group reference of [groupRef]. X is a radius.
     */
    fun toSubDatums(spec: ThreadSpec, now: Instant = Clock.System.now()): List<SubDatum> {
        val tag = subDatumTag(spec.tag)
        return build(spec).map { point ->
            SubDatum(
                name = point.name,
                tags = listOf(tag),
                x = point.x,
                z = point.z,
                groupKind = SubDatumGroupKind.Thread,
                groupRef = groupRef(spec.id, point.role),
                createdOn = now,
                updatedOn = now,
            )
        }
    }

    /**
     * Gives the corner sub-datums of the thread from [subDatums]: the group kind `Thread`, the
     * thread id, and a corner role.
     */
    fun cornerSubDatums(spec: ThreadSpec, subDatums: List<SubDatum>): List<SubDatum> {
        val (left, right) = cornerRoles(spec)
        return subDatums.filter { subDatum ->
            val ref = parseGroupRef(subDatum.groupRef)
            subDatum.groupKind == SubDatumGroupKind.Thread && ref != null && ref.first == spec.id &&
                (ref.second == left || ref.second == right)
        }
    }

    /**
     * True when the arm step must make the thread points again: the workspace does not have exactly
     * 4 corner sub-datums of the thread.
     */
    fun needsRebuild(spec: ThreadSpec, subDatums: List<SubDatum>): Boolean = cornerSubDatums(spec, subDatums).size != 4

    /**
     * Gives the threads that the arm button can arm: the threads with StartZ ≠ StopZ.
     */
    fun armable(threads: List<ThreadSpec>): List<ThreadSpec> = threads.filter { thread -> thread.startZUm != thread.stopZUm }
}
