package org.superdro.core.ui.graphics.proximity

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.functions.proximity.ProximityState
import org.superdro.core.functions.proximity.ProximityWarning
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProximityTarget
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.util.Clock

/**
 * The proximity warning tone of one tab. The target is workspace
 * state ([org.superdro.core.model.Workspace.proximity]): the linear axis details set it, and the
 * automatic proximity warning of the graphical view sets it.
 *
 * Rules:
 * - The value of the target axis is the shown value, or the absolute value when the target is
 *   absolute ([ProximityTarget.absolute]).
 * - Each new value goes through the level rule of [ProximityState.update]. The runner sends the
 *   level to [SoundController.proximity] only when the level changes.
 * - The level goes to 0 after [ProximityWarning.SILENCE_MS] with no new value. One timer
 *   holds the deadline: a new value moves the deadline, and does not start a new timer. After
 *   the deadline the runner has no timer, so a test with virtual time can run until idle.
 * - The measurement mode stops the warning.
 * - The mute rules of the sound controller apply.
 *
 * A point target ([ProximityTarget.pointId]) has its own rules:
 * - The value of each axis of the target is the distance to the point on that axis:
 *   `abs + S`, with the sub-datum offset `S` of the point. Each axis has its own level rule.
 * - Only an axis that moved in the last [ProximityWarning.STILL_MS] gives a tone. The level is
 *   the highest level of these axes. Thus the tone follows the axis that moves to its target,
 *   and the distance on the other axis has no effect.
 * - The tone stops [ProximityWarning.STILL_MS] after the last motion, and starts again with the
 *   next motion.
 * - The target has no effect when its point is not the active sub-datum.
 *
 * The collector and the timers run on one thread at a time (a view of the dispatcher of the
 * scope with a parallelism of 1), so they do not need a lock.
 */
class ProximityToneRunner(
    private val frame: StateFlow<ReadoutFrame?>,
    private val sounds: SoundController,
    private val deviceId: DeviceId,
    private val clock: Clock,
) {
    private var target: ProximityTarget? = null
    private var state: ProximityState? = null
    private var sentLevel: Double = 0.0
    private var silence: Job? = null

    /** The time of the end of the silence time: the time of the last value plus [ProximityWarning.SILENCE_MS]. */
    private var silenceAtMs: Long = 0L

    // The state of a point target: the level rule and the time of the last motion of each axis.
    private val pointStates = LinkedHashMap<AxisId, ProximityState>()
    private val lastMotionMs = LinkedHashMap<AxisId, Long>()
    private var still: Job? = null

    /** The level that the runner sent last, 0 to 1. */
    val level: Double get() = sentLevel

    /**
     * Collects the readout frames in [scope] until the scope stops. The timers are children of the
     * collector, on the same thread.
     */
    fun start(scope: CoroutineScope): Job {
        val dispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
        // An unconfined dispatcher has no view with a limit: it runs in the thread of the caller.
        val confined = dispatcher?.takeIf { value -> value.isDispatchNeeded(scope.coroutineContext) }?.limitedParallelism(1)
        return scope.launch(confined ?: EmptyCoroutineContext) {
            try {
                frame.collect { current -> onFrame(current, this) }
            } finally {
                send(0.0)
            }
        }
    }

    private fun onFrame(current: ReadoutFrame?, scope: CoroutineScope) {
        val newTarget = current?.workspace?.proximity
        if (current == null || newTarget == null || current.measurementActive) {
            target = null
            state = null
            silence?.cancel()
            clearPoint()
            send(0.0)
            return
        }
        if (newTarget != target) {
            val pointChange = newTarget.pointId != null || target?.pointId != null
            target = newTarget
            state = ProximityWarning.start(newTarget.targetUm)
            if (pointChange) {
                // A change to or from a point target stops the tone of the old target.
                silence?.cancel()
                clearPoint()
                send(0.0)
            }
        }
        if (newTarget.pointId != null) {
            onPointFrame(current, newTarget, scope)
            return
        }
        val reading = current.reading(newTarget.axis)
        if (!reading.hasValue) return
        val value = if (newTarget.absolute) reading.absolute else reading.shown
        val old = state ?: ProximityWarning.start(newTarget.targetUm)
        if (old.previousUm == value) return
        val now = clock.nowMs()
        val next = old.update(value, now)
        state = next
        send(next.levelAt(now))
        startSilence(scope, now)
    }

    /**
     * Moves the end of the silence time to [nowMs] + [ProximityWarning.SILENCE_MS]. The timer
     * that runs keeps on with the new deadline. Only a runner with no timer starts one.
     */
    private fun startSilence(scope: CoroutineScope, nowMs: Long) {
        silenceAtMs = nowMs + ProximityWarning.SILENCE_MS
        if (silence?.isActive == true) return
        silence = scope.launch {
            while (true) {
                val before = clock.nowMs()
                val left = silenceAtMs - before
                if (left <= 0L) break
                delay(left)
                // A clock that does not follow the delays cannot move the deadline: stop.
                if (clock.nowMs() == before) break
            }
            send(0.0)
        }
    }

    private fun onPointFrame(current: ReadoutFrame, point: ProximityTarget, scope: CoroutineScope) {
        if (current.workspace.activeSubDatumId != point.pointId) {
            clearPoint()
            send(0.0)
            return
        }
        val now = clock.nowMs()
        var moved = false
        point.axes.forEach { axis ->
            val offset = current.subDatumOffsets[axis] ?: return@forEach
            val reading = current.reading(axis)
            if (!reading.hasValue) return@forEach
            val value = reading.absolute + offset
            val old = pointStates[axis] ?: ProximityWarning.start(point.targetUm)
            if (old.previousUm == value) return@forEach
            pointStates[axis] = old.update(value, now)
            // The first value is not a motion.
            if (old.previousUm != null) {
                lastMotionMs[axis] = now
                moved = true
            }
        }
        if (!moved) return
        send(pointLevel(now))
        if (still?.isActive != true) startStillTimer(scope)
    }

    /** The highest level of the axes that moved in the last [ProximityWarning.STILL_MS]. */
    private fun pointLevel(nowMs: Long): Double =
        lastMotionMs.entries
            .filter { (_, time) -> nowMs - time < ProximityWarning.STILL_MS }
            .maxOfOrNull { (axis, _) -> pointStates[axis]?.level ?: 0.0 } ?: 0.0

    /**
     * Sets the level again when the still time of each moving axis ends. The timer then stops. A
     * motion while the timer runs does not start a new timer: the timer reads the new times of
     * the last motion at its next step.
     */
    private fun startStillTimer(scope: CoroutineScope) {
        still = scope.launch {
            while (true) {
                val now = clock.nowMs()
                val next = lastMotionMs.values.filter { time -> now - time < ProximityWarning.STILL_MS }.minOrNull() ?: break
                delay(next + ProximityWarning.STILL_MS - now)
                send(pointLevel(clock.nowMs()))
            }
        }
    }

    private fun clearPoint() {
        still?.cancel()
        pointStates.clear()
        lastMotionMs.clear()
    }

    private fun send(level: Double) {
        if (level == sentLevel) return
        sentLevel = level
        sounds.proximity(level, deviceId)
    }
}
