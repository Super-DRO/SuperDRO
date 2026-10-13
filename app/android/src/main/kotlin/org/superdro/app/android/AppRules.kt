package org.superdro.app.android

import org.superdro.core.connection.PermissionKind
import org.superdro.core.ui.navigation.BatteryOptimizationRoute
import org.superdro.core.ui.navigation.GlobalRoute
import org.superdro.platform.android.bluetooth.SppRules
import org.superdro.platform.android.network.NetworkRules

/**
 * The rules of the request bus with no Android type, so that a JVM unit test can examine them.
 */
object PermissionRules {

    /** API 33: the run-time permission `POST_NOTIFICATIONS`. */
    const val NOTIFICATIONS_API: Int = 33

    const val POST_NOTIFICATIONS: String = "android.permission.POST_NOTIFICATIONS"
    const val CAMERA: String = "android.permission.CAMERA"

    /**
     * Gives the run-time permissions of [kinds] on [sdkInt], in a stable order. A kind that needs no
     * request on this API level gives no permission: the Bluetooth kinds before API 31
     * ([SppRules.manifestPermissions]), the local network before the API level that has it
     * ([NetworkRules.manifestPermissions]), and the notifications before API 33.
     */
    fun manifestPermissions(kinds: Set<PermissionKind>, sdkInt: Int): List<String> =
        PermissionKind.entries.filter { kind -> kind in kinds }.flatMap { kind ->
            when (kind) {
                PermissionKind.BluetoothConnect, PermissionKind.BluetoothScan -> SppRules.manifestPermissions(kind, sdkInt)
                PermissionKind.LocalNetwork -> NetworkRules.manifestPermissions(kind, sdkInt)
                PermissionKind.Notifications -> if (sdkInt >= NOTIFICATIONS_API) listOf(POST_NOTIFICATIONS) else emptyList()
                PermissionKind.Camera -> listOf(CAMERA)
            }
        }.distinct()
}

/** A question that the app asks at the first connect. */
enum class FirstConnectPrompt {
    /** The system dialog of `POST_NOTIFICATIONS`, so that the service notification shows. */
    Notifications,

    /**
     * The text "SuperDRO keeps the links to your machines open while the screen is off.", then
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
     */
    BatteryOptimization,
}

/** The rule of the questions at the first connect. */
object FirstConnectRules {

    /**
     * Gives the questions to ask, in this order: the notifications, then the battery optimization.
     *
     * @param notificationsGranted True when `POST_NOTIFICATIONS` is granted.
     * @param notificationsAsked The flag `AppSettings.notificationPermissionAsked`: the app asked one
     *   time already, also in an earlier process.
     * @param batteryAsked The flag `AppSettings.batteryExemptionAsked`: the app asked one time already.
     * @param batteryExempt True when the app is exempt from the battery optimization.
     */
    fun prompts(
        sdkInt: Int,
        notificationsGranted: Boolean,
        notificationsAsked: Boolean,
        batteryAsked: Boolean,
        batteryExempt: Boolean,
    ): List<FirstConnectPrompt> = buildList {
        if (sdkInt >= PermissionRules.NOTIFICATIONS_API && !notificationsGranted && !notificationsAsked) {
            add(FirstConnectPrompt.Notifications)
        }
        if (!batteryAsked && !batteryExempt) add(FirstConnectPrompt.BatteryOptimization)
    }
}

/**
 * The rule of the battery request of the first connect.
 * The app has one battery dialog: the global route `BatteryOptimizationRoute` of `core:ui`.
 */
object BatteryRequestRules {

    /** True when the first connect opens the route: the route is not on the global stack [routes] already. */
    fun shouldOpen(routes: List<GlobalRoute>): Boolean = BatteryOptimizationRoute !in routes
}

/** The rules of the launch intents of `MainActivity`. */
object LaunchRules {

    /**
     * Gives the demo switch of a launch: the launcher entry of the app (`MAIN` and
     * `LAUNCHER`) turns the demo off unless it has the demo extra, an intent with the demo extra
     * sets the switch, and each other intent (a notification tap, a USB attach) gives null: no
     * change.
     *
     * @param hasIntent False when Android gives no intent.
     * @param isLauncher True for the action `MAIN` with the category `LAUNCHER`.
     * @param demoExtra The value of the demo extra, or null when the intent has no such extra.
     */
    fun demoSwitch(hasIntent: Boolean, isLauncher: Boolean, demoExtra: Boolean?): Boolean? = when {
        !hasIntent -> null
        demoExtra != null -> demoExtra
        isLauncher -> false
        else -> null
    }
}
