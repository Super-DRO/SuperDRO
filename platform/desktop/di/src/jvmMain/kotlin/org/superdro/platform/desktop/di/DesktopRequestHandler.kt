package org.superdro.platform.desktop.di

import com.toxicbakery.logging.Arbor
import java.io.IOException
import org.superdro.core.connection.ConnectionRequest

/** What the desktop app did for one request of the bus. */
enum class RequestAction {
    /** The app started the Bluetooth settings of the operating system. */
    OpenedBluetoothSettings,

    /** The app read the serial ports again. */
    RefreshedPorts,

    /** The desktop has no such function: the request does nothing. */
    NothingToDo,
}

/**
 * The collector part of the request bus on the desktop. The desktop has no run-time permission,
 * no USB permission dialog, no battery optimization, and no system screen of the app.
 *
 * - "Pair new adapter" and "Turn on Bluetooth" start the Bluetooth settings of the operating
 *   system. The user pairs the adapter there. On Linux the user then binds an RFCOMM port,
 *   for example with `rfcomm bind 0 <address>`.
 * - A permission request and a USB permission request read the serial ports again. A USB port
 *   that the user cannot open needs a change of the system: on Linux, the group `dialout` (the log
 *   tells this).
 * - The battery optimization requests and the app settings request do nothing.
 *
 * @param refreshPorts Reads the serial ports again.
 * @param openBluetoothSettings Starts the Bluetooth settings. Gives false when no command started.
 */
class DesktopRequestHandler(
    private val refreshPorts: () -> Unit,
    private val openBluetoothSettings: () -> Boolean = { SystemSettings.openBluetoothSettings() },
) {

    /** Does the desktop part of [request]. The function does not throw for a missing system tool. */
    fun handle(request: ConnectionRequest): RequestAction = when (request) {
        ConnectionRequest.EnableBluetooth, ConnectionRequest.OpenBluetoothSettings ->
            if (openBluetoothSettings()) {
                RequestAction.OpenedBluetoothSettings
            } else {
                log.w { "No Bluetooth settings program started. Pair the adapter in the settings of the operating system." }
                RequestAction.NothingToDo
            }
        is ConnectionRequest.RequestPermissions -> {
            refreshPorts()
            RequestAction.RefreshedPorts
        }
        is ConnectionRequest.RequestUsbPermission -> {
            log.i { USB_ACCESS_HINT }
            refreshPorts()
            RequestAction.RefreshedPorts
        }
        ConnectionRequest.RequestIgnoreBatteryOptimizations,
        ConnectionRequest.OpenBatteryOptimizationSettings,
        ConnectionRequest.OpenAppSettings,
        -> RequestAction.NothingToDo
    }

    companion object {
        /** The log text of a USB permission request. */
        const val USB_ACCESS_HINT: String =
            "The desktop has no USB permission dialog. On Linux, add the user to the group dialout and log in again."

        private val log = Arbor.tag("DesktopRequests")
    }
}

/** The Bluetooth settings programs of the operating systems. */
object SystemSettings {

    /**
     * Gives the commands that open the Bluetooth settings on [osName], in the order of the tries:
     * - Windows: `explorer.exe ms-settings:bluetooth`.
     * - macOS: the Bluetooth pane of System Settings.
     * - Linux and other systems: GNOME Settings, KDE System Settings, then Blueman.
     */
    fun bluetoothSettingsCommands(osName: String): List<List<String>> {
        val os = osName.lowercase()
        return when {
            "mac" in os || "darwin" in os -> listOf(
                listOf("open", "x-apple.systempreferences:com.apple.BluetoothSettings"),
                listOf("open", "/System/Library/PreferencePanes/Bluetooth.prefPane"),
            )
            os.startsWith("windows") -> listOf(listOf("explorer.exe", "ms-settings:bluetooth"))
            else -> listOf(
                listOf("gnome-control-center", "bluetooth"),
                listOf("systemsettings", "kcm_bluetooth"),
                listOf("blueman-manager"),
            )
        }
    }

    /** Starts the first command of [bluetoothSettingsCommands] that starts. Gives false when no command started. */
    fun openBluetoothSettings(
        osName: String = System.getProperty("os.name").orEmpty(),
        start: (List<String>) -> Boolean = ::startProcess,
    ): Boolean = bluetoothSettingsCommands(osName).any(start)

    private fun startProcess(command: List<String>): Boolean = try {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    } catch (_: IOException) {
        false
    }
}
