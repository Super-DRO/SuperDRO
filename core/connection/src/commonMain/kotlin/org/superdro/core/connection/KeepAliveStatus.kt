package org.superdro.core.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The state of the platform keep-alive ([ConnectionKeepAlive.status]). The UI reads it and shows a
 * warning in the tab when the links can stop in the background.
 */
enum class KeepAliveStatus {
    /** No session wants a link. The service does not run. */
    Idle,

    /** The service runs in the foreground. The links stay up in the background. */
    Running,

    /**
     * Android refused the start of the service (`ForegroundServiceStartNotAllowedException`). The
     * sessions continue while the app is visible.
     */
    StartNotAllowed,

    /**
     * The prerequisite of the `connectedDevice` service type is not met: no granted
     * `BLUETOOTH_CONNECT`, no USB device with a granted permission, and no TCP machine. The
     * sessions continue while the app is visible.
     */
    PrerequisiteMissing;

    /** True when the tab must show the warning that the links can stop in the background. */
    val linksCanStopInBackground: Boolean
        get() = this == StartNotAllowed || this == PrerequisiteMissing

    companion object {
        /** A flow that always has [Idle]: the default of [ConnectionKeepAlive.status]. */
        val idleFlow: StateFlow<KeepAliveStatus> = MutableStateFlow(Idle).asStateFlow()
    }
}

/**
 * Gives the battery optimization state of the app for the settings. The settings show [exempt] and
 * a link to the system setting
 * (`ConnectionRequest.OpenBatteryOptimizationSettings`).
 *
 * The Android implementation reads `PowerManager.isIgnoringBatteryOptimizations()`. Android sends
 * no broadcast for a change of this state, so the app calls [refresh] when an activity comes back
 * to the foreground.
 */
interface BatteryOptimization {

    /** True when the system does not apply the battery optimization to the app. */
    val exempt: StateFlow<Boolean>

    /** Reads the state again. */
    fun refresh()
}

/** A [BatteryOptimization] for a platform with no battery optimization, and for the tests: always exempt. */
object NoBatteryOptimization : BatteryOptimization {
    override val exempt: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    override fun refresh() = Unit
}
