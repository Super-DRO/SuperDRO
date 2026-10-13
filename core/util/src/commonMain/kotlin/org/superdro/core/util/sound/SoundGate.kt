package org.superdro.core.util.sound

import kotlin.jvm.Synchronized

/**
 * The busy rule of the UI sounds: a busy sound drops a new request of the same kind. The class is
 * thread safe.
 *
 * Each app gives its own player of each sound ([kindOf]) and the busy time of each sound
 * ([busyMs]), because the players are different: the Android app plays the link tones with
 * `ToneGenerator`, and the desktop app makes them in code.
 *
 * @param S The type of a sound.
 * @param K The type of a player.
 * @param nowMs The monotonic clock in milliseconds.
 */
class SoundGate<S, K>(
    private val nowMs: () -> Long,
    private val kindOf: (S) -> K,
    private val busyMs: (S) -> Long,
) {
    private val busyUntil = HashMap<K, Long>()

    /** Gives true and marks the player of [sound] busy, or gives false when the player is busy. */
    @Synchronized
    fun tryStart(sound: S): Boolean {
        val kind = kindOf(sound)
        val now = nowMs()
        val until = busyUntil[kind]
        if (until != null && now < until) return false
        busyUntil[kind] = now + busyMs(sound)
        return true
    }
}
