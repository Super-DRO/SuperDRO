package org.superdro.validation.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.validation.Traceability

/**
 * The platform modules contain only the items of the platform contract list. The test scans each
 * public type of the main sources of the modules in `platform`. Each type must belong to one contract
 * item: the implementation of the item, or a part of it (its rules, its states, its texts). Each other
 * code is common code.
 *
 * The DI module of a platform (`platformModule`, `desktopPlatformModule`) binds the contracts
 * and is not a contract itself.
 */
class PlatformContractTest {

    private val root: File = Traceability.rootDir

    /** The public types of each platform module. */
    private val contracts: Map<String, Set<String>> = mapOf(
        "platform/android/bluetooth" to setOf(
            "BluetoothCapabilityMonitor", "BluetoothIntents", "Spp", "SppAdapterDiscovery",
            "SppRules", "SppTransport", "SppTransportFactory",
        ),
        "platform/android/camera" to setOf(
            "AndroidCameraAccessPlatform", "AndroidUvcCameraBackend", "CameraAttachActivity", "CameraPermissionBridge", "CameraProcess",
            "RunEvent", "CameraRemote", "CameraService", "StreamWorkerEvents", "CameraStreamWorker", "CameraWire", "CameraReply",
            "RequestBroker", "RunFilter", "ServiceState", "ServiceEvent", "ServiceStates", "UvcDevices", "UvcNative",
        ),
        "platform/android/di" to setOf(
            "AndroidCapabilitySource", "LazyTransportFactory", "LazyAdapterDiscovery",
            "BlueFalconLogger", "PlatformRefresher",
        ),
        "platform/android/network" to setOf(
            "AndroidNetworkInterfaces", "BindInterfaces", "NetworkCapabilityMonitor",
            "LinkTransport", "NetworkSnapshot", "NetworkRules",
        ),
        "platform/android/service" to setOf(
            "AndroidBatteryOptimization", "AndroidConnectionKeepAlive", "BootReceiver", "BootStart",
            "ComposeServiceTexts", "ConnectionNotificationChannel", "ConnectionNotifications",
            "ConnectionService", "ConnectionServiceHost", "ServiceCommand", "ConnectionServiceRules",
            "ServicePhase", "ConnectionServiceState", "ForegroundPrerequisite", "LinkIoDispatcher",
            "LinkThreadPriority", "ServiceTexts", "NotificationContent", "WakeLockHandle",
            "WakeLockController", "PowerManagerWakeLock", "WakeLockSetting",
        ),
        "platform/android/store" to setOf(
            "AndroidKeystoreSecretStore", "AndroidKeystoreSecretCipher", "AndroidSqlDriverFactory",
            "StoreIOProviderAndroid",
        ),
        "platform/android/usb" to setOf(
            "SuperDroUsbProber", "UsbDeviceMonitor", "UsbDriverKind", "UsbDeviceId",
            "UsbDeviceTable", "UsbPermissionRequester", "UsbLineSettings", "UsbIdentity",
            "UsbDeviceInfo", "UsbRules", "UsbSerialDefaults", "UsbSerialDiscovery",
            "UsbSerialTransport", "UsbSerialTransportFactory",
        ),
        "platform/desktop/di" to setOf(
            "SystemInterface", "DesktopNetworkRules", "DesktopNetworkInterfaces",
            "DesktopCapabilitySource", "DesktopConnectionKeepAlive", "DesktopLinkIoDispatcher",
            "DesktopNoCameraBackend", "RequestAction", "DesktopRequestHandler", "SystemSettings",
        ),
        "platform/desktop/serial" to setOf(
            "JSerialCommPorts", "SerialLineSettings", "SerialPortMonitor", "SerialAdapterDiscovery",
            "SerialTransportFactory", "SerialPortKind", "SerialPortRules", "LinuxRfcomm",
            "SerialPortTransport", "SerialPortInfo", "SerialPorts", "OpenSerialPort",
        ),
        "platform/desktop/store" to setOf(
            "DesktopSecretStore", "FileKeySecretCipher", "DesktopSqlDriverFactory", "DesktopStore",
        ),
    )

    /** The DI modules of the platforms: the binding of the contracts. */
    private val diModules = setOf("platformModule", "desktopPlatformModule")

    private val declaration = Regex(
        """^(?:(?:public|data|sealed|abstract|open|enum|annotation|value|inline|fun)\s+)*(class|object|interface|val|fun)\s+(\w+)""",
        RegexOption.MULTILINE,
    )

    /** The public top-level types, values, and functions of the main sources of [module]. */
    private fun publicDeclarations(module: String): Set<String> =
        root.resolve(module).resolve("src").listFiles().orEmpty()
            .filter { sourceSet -> sourceSet.name == "main" || sourceSet.name == "jvmMain" }
            .flatMap { sourceSet -> sourceSet.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }
            .flatMap { file -> declaration.findAll(file.readText()).map { match -> match.groupValues[2] }.toList() }
            .toSet()

    @Test
    fun eachPublicTypeOfAPlatformModuleBelongsToAnItemOfTheContractList() {
        val modules = root.resolve("platform").walkTopDown().maxDepth(2)
            .filter { directory -> directory.resolve("build.gradle.kts").isFile }
            .map { directory -> directory.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        assertEquals(contracts.keys, modules, "the platform modules")
        val problems = modules.sorted().flatMap { module ->
            val known = contracts.getValue(module)
            val found = publicDeclarations(module) - diModules
            (found - known).map { name -> "$module: $name is not an item of the contract list" } +
                (known - found).map { name -> "$module: $name is not there now; update the list" }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun theCommonCodeHasTheLogicThatThePlatformsShare() {
        // The parts that the Android app and the desktop app share are common code, not two platform copies.
        assertTrue(root.resolve("core/connection/src/jvmAndAndroidMain/kotlin/org/superdro/core/connection/FileStreamRecorder.kt").isFile)
        assertTrue(root.resolve("core/util/src/commonMain/kotlin/org/superdro/core/util/sound/ToneSynth.kt").isFile)
        val platformSources = root.resolve("platform").walkTopDown().filter { file -> file.isFile && file.extension == "kt" && "/build/" !in file.invariantSeparatorsPath }
        assertEquals(emptyList(), platformSources.filter { file -> Regex("""\bclass\s+FileStreamRecorder\b""").containsMatchIn(file.readText()) }.map { it.name }.toList())
    }
}
