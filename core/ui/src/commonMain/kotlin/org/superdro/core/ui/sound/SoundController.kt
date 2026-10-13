package org.superdro.core.ui.sound

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AppSettings
import org.superdro.core.model.MuteScope
import org.superdro.core.util.Clock

/**
 * The one sound controller of the app. It applies
 * the mute rules, then it calls the platform contract [UiSounds].
 *
 * Mute rules ("Mute scope"):
 * - [MuteScope.App] (the default): the app switch [AppSettings.muteAllSounds] mutes all sounds.
 * - [MuteScope.EachMachine]: the switch of each machine (`MachineProfile.muteSounds`) mutes the
 *   sounds of that machine. A sound with no machine follows the app switch.
 * - The mute stops each sound, also the link tones.
 *
 * Link tones:
 * - Link up: a session changes to `Connected` from a different state, while the app is visible,
 *   more than 2000 ms after the app came to the foreground.
 * - Link lost: a connected session goes to `Reconnecting` while the app is visible. A session is
 *   "connected" from its change to `Connected` until it goes offline, so `Connected`, `NoData`,
 *   then `Reconnecting` also plays the tone (the `NO_DATA` recovery).
 *
 * Haptic feedback is not a sound: the composables give it with `LocalHapticFeedback`
 * ([rememberKeyFeedback]).
 *
 * @param mutedMachines The machines whose own mute switch is on.
 * @param clock The monotonic clock of the 2000 ms rule.
 */
class SoundController(
    private val sounds: UiSounds,
    private val settings: StateFlow<AppSettings>,
    private val mutedMachines: StateFlow<Set<DeviceId>>,
    private val clock: Clock,
) {
    @Volatile
    private var foregroundSinceMs: Long = NOT_VISIBLE

    private val lastStates = HashMap<DeviceId, SessionState>()
    private val connected = HashSet<DeviceId>()

    /** True while the app is visible: between [onForeground] and [onBackground]. */
    val visible: Boolean get() = foregroundSinceMs != NOT_VISIBLE

    /** The app came to the foreground (the activity started). */
    fun onForeground() {
        foregroundSinceMs = clock.nowMs()
    }

    /** The app went to the background (the activity stopped). */
    fun onBackground() {
        foregroundSinceMs = NOT_VISIBLE
    }

    /** True when the sounds of [device] are muted. A null [device] follows the app switch. */
    fun isMuted(device: DeviceId?): Boolean {
        val current = settings.value
        return when (current.muteScope) {
            MuteScope.App -> current.muteAllSounds
            MuteScope.EachMachine -> if (device == null) current.muteAllSounds else device in mutedMachines.value
        }
    }

    /** Plays [sound] for [device] when the sounds of [device] are not muted. */
    fun play(sound: UiSound, device: DeviceId? = null) {
        if (!isMuted(device)) sounds.play(sound)
    }

    /** The short sound of a tap on a button. */
    fun tap(device: DeviceId? = null) = play(UiSound.Short, device)

    /** The long sound of an accepted long press. */
    fun longPress(device: DeviceId? = null) = play(UiSound.Long, device)

    /** The error sound of a refused action. */
    fun error(device: DeviceId? = null) = play(UiSound.Error, device)

    /**
     * Sets the level of the proximity warning tone of [device]: 0 stops the tone, 1 gives a
     * continuous tone. A muted device gets the level 0.
     */
    fun proximity(level: Double, device: DeviceId? = null) {
        sounds.proximity(if (isMuted(device)) 0.0 else level)
    }

    /**
     * Applies the link tone rules to the change of the session of [device] to [state]. The first
     * state of a session is not a change.
     */
    fun onSessionState(device: DeviceId, state: SessionState) {
        val old = lastStates.put(device, state)
        if (old == null || old == state) {
            if (state is SessionState.Connected) connected += device
            return
        }
        if (state is SessionState.Connected && old !is SessionState.Connected) {
            val since = foregroundSinceMs
            if (since != NOT_VISIBLE && clock.nowMs() - since > LINK_UP_AFTER_FOREGROUND_MS) play(UiSound.LinkUp, device)
        }
        if (state is SessionState.Reconnecting && device in connected && visible) play(UiSound.LinkLost, device)
        when {
            state is SessionState.Connected -> connected += device
            !state.isOnline -> connected -= device
        }
    }

    /**
     * Collects the state of each session of [sessions] in [scope] and applies the link tone rules.
     * A session that goes away loses its state.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watch(scope: CoroutineScope, sessions: Flow<Map<DeviceId, DeviceSession>>): Job = scope.launch {
        sessions.flatMapLatest { map ->
            forget(map.keys)
            if (map.isEmpty()) emptyFlow() else map.values.map { session -> session.state.map { state -> session.id to state } }.merge()
        }.collect { (id, state) -> onSessionState(id, state) }
    }

    private fun forget(keep: Set<DeviceId>) {
        lastStates.keys.retainAll(keep)
        connected.retainAll(keep)
    }

    companion object {
        /** The link up tone needs more than this time after the app came to the foreground. */
        const val LINK_UP_AFTER_FOREGROUND_MS: Long = 2_000L

        private const val NOT_VISIBLE: Long = Long.MIN_VALUE
    }
}
