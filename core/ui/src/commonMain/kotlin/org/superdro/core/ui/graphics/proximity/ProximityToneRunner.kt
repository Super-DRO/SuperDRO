package org.superdro.core.ui.graphics.proximity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.functions.proximity.ProximityState
import org.superdro.core.functions.proximity.ProximityWarning
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
 * - The level goes to 0 after [ProximityWarning.SILENCE_MS] with no new value. The runner then
 *   has no timer, so a test with virtual time can run until idle.
 * - The measurement mode stops the warning.
 * - The mute rules of the sound controller apply.
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

    /** The level that the runner sent last, 0 to 1. */
    val level: Double get() = sentLevel

    /** Collects the readout frames in [scope] until the scope stops. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        try {
            frame.collect { current -> onFrame(current, scope) }
        } finally {
            send(0.0)
        }
    }

    private fun onFrame(current: ReadoutFrame?, scope: CoroutineScope) {
        val newTarget = current?.workspace?.proximity
        if (current == null || newTarget == null || current.measurementActive) {
            target = null
            state = null
            silence?.cancel()
            send(0.0)
            return
        }
        if (newTarget != target) {
            target = newTarget
            state = ProximityWarning.start(newTarget.targetUm)
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
        silence?.cancel()
        silence = scope.launch {
            delay(ProximityWarning.SILENCE_MS)
            send(0.0)
        }
    }

    private fun send(level: Double) {
        if (level == sentLevel) return
        sentLevel = level
        sounds.proximity(level, deviceId)
    }
}
