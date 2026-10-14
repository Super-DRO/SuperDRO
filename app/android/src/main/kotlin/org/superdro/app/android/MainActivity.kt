package org.superdro.app.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.superdro.app.android.files.AndroidFilePicker
import org.superdro.app.android.files.FilePickerHost
import org.superdro.app.android.files.PickerRequest
import org.superdro.core.connection.BatteryOptimization
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.ConnectionRequestManager
import org.superdro.core.connection.KeepAliveController
import org.superdro.core.connection.PermissionKind
import org.superdro.core.connection.toDeviceRef
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore
import org.superdro.core.ui.App
import org.superdro.core.ui.navigation.BatteryOptimizationRoute
import org.superdro.core.ui.navigation.NavigationHolder
import org.superdro.core.ui.ProvideDI
import org.superdro.platform.android.bluetooth.BluetoothIntents
import org.superdro.platform.android.camera.CameraPermissionBridge
import org.superdro.platform.android.di.PlatformRefresher
import org.superdro.platform.android.service.AndroidBatteryOptimization
import org.superdro.platform.android.usb.UsbPermissionRequester

/**
 * The one activity of SuperDRO. It shows [App] edge-to-edge, full screen when the app setting is
 * on, and collects the request bus of common code:
 *
 * - The run-time permissions ([PermissionRules]), the Bluetooth radio, the Bluetooth settings, the
 *   USB permission, the battery optimization request, the app settings, the local network
 *   permission, and `CAMERA` before the camera ([CameraPermissionBridge]) and before `IMAGE_CAPTURE`.
 *   After each result, the platform states are read again ([PlatformRefresher]).
 * - The restore of the links at each start, not in
 *   `Application.onCreate`.
 * - At the first connect: `POST_NOTIFICATIONS`, then the battery optimization request with its
 *   reason. The dialog with the reason is the global route
 *   `BatteryOptimizationRoute` of `core:ui`: the app has one battery dialog.
 * - The system screens of the file picker ([AndroidFilePicker]).
 * - The intent `USB_DEVICE_ATTACHED` of a known adapter, the tap on the notification (the
 *   tab of a machine), and the demo entry of a debug build.
 *
 * `singleTop`: one instance collects the bus. The UI does not bind to the connection service.
 */
class MainActivity : ComponentActivity(), DIAware, FilePickerHost {

    override val di: DI by lazy { (application as DIAware).di }

    private val debugAdapter: DebugAdapter by instance()
    private val connectionRequests: ConnectionRequestManager by instance()
    private val connectionManager: ConnectionManager by instance()
    private val keepAliveController: KeepAliveController by instance()
    private val appSettings: AppSettingsStore by instance()
    private val tabStore: TabStore by instance()
    private val refresher: PlatformRefresher by instance()
    private val batteryOptimization: BatteryOptimization by instance()
    private val usbPermissions: UsbPermissionRequester by instance()
    private val cameraBridge: CameraPermissionBridge by instance()
    private val filePicker: AndroidFilePicker by instance()
    private val navigation: NavigationHolder by instance()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        log.d { "Permission result: $result" }
        refresher.refreshAll()
        result[PermissionRules.CAMERA]?.let { granted -> cameraBridge.onResult(granted, showsCameraRationale()) }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        log.d { "Notification permission: $granted" }
        lifecycleScope.launch { askBatteryExemptionIfNeeded() }
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresher.refreshAll()
    }

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> filePicker.deliver(uri) }

    private val createDocumentContract = CreateDocumentOfType()
    private val createDocumentLauncher = registerForActivityResult(createDocumentContract) { uri -> filePicker.deliver(uri) }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken -> filePicker.deliver(taken) }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        filePicker.deliver(granted)
        cameraBridge.onResult(granted, showsCameraRationale())
        refresher.refreshAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        filePicker.attach(this)
        // A new activity handles its launch intent. An activity that Android makes again keeps
        // the tab and the demo state of the old one.
        if (savedInstanceState == null) handleIntent(intent)
        collectConnectionRequests()
        collectFullScreen()
        watchFirstConnect()
        // Each remembered machine that is not paused connects at the app start. A session that
        // runs does not change.
        connectionManager.restore()

        setContent {
            ProvideDI(di) {
                App()
            }
        }
    }

    /**
     * A launcher tap, a notification tap, or a USB attach comes here while the activity runs,
     * because the activity is `singleTop`.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Android sends no broadcast for a permission or a battery optimization change.
        refresher.refreshAll()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A system dialog can show the bars. Hide them again when the app has the focus.
        if (hasFocus) SystemBars.apply(window, appSettings.settings.value.fullScreen)
    }

    override fun onDestroy() {
        filePicker.detach(this, finishing = isFinishing)
        super.onDestroy()
    }

    // FilePickerHost

    override fun launch(request: PickerRequest): Boolean = try {
        when (request) {
            is PickerRequest.Open -> openDocumentLauncher.launch(request.mimeTypes)
            is PickerRequest.Create -> {
                createDocumentContract.mimeType = request.mimeType
                createDocumentLauncher.launch(request.suggestedName)
            }
            is PickerRequest.Photo -> takePictureLauncher.launch(request.output)
            PickerRequest.CameraPermission -> cameraPermissionLauncher.launch(PermissionRules.CAMERA)
        }
        true
    } catch (error: ActivityNotFoundException) {
        log.w(error) { "No activity for $request" }
        false
    }

    override fun startActivitySafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        log.w(error) { "No activity for $intent" }
        false
    }

    // Launch intents

    private fun handleIntent(intent: Intent?) {
        applyDemo(
            LaunchRules.demoSwitch(
                hasIntent = intent != null,
                isLauncher = intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER),
                demoExtra = intent?.takeIf { it.hasExtra(EXTRA_DEMO) }?.getBooleanExtra(EXTRA_DEMO, false),
            ),
        )
        // Android opened the app for a known USB adapter. The user gave the permission in
        // the same dialog, so the session in WaitingForDevice can connect now.
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) refresher.refreshUsb()
        // The tap on the notification opens the tab of the machine.
        intent?.getStringExtra(EXTRA_DEVICE_ID)?.let { id -> tabStore.select(id) }
    }

    /** The demo entry connects the two fake machines; a launcher tap removes them. */
    private fun applyDemo(switch: Boolean?) {
        if (switch == null) return
        val wasOn = debugAdapter.active
        debugAdapter.applySwitch(switch)
        when {
            switch -> debugAdapter.demoLinks.forEach { link -> connectionManager.connect(link.toDeviceRef()) }
            wasOn -> debugAdapter.demoLinks.forEach { link -> connectionManager.remove(link.deviceId) }
        }
    }

    // The request bus

    private fun collectConnectionRequests() {
        lifecycleScope.launch {
            connectionRequests.bus.collect { request ->
                // A failure of one request must not stop the collector. A cancellation must stop it.
                runCatching { handle(request) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.e(error) { "The request $request failed" }
                    }
            }
        }
    }

    private fun handle(request: ConnectionRequest) {
        log.d { "Request: $request" }
        when (request) {
            is ConnectionRequest.RequestPermissions -> requestPermissions(request.kinds)
            ConnectionRequest.EnableBluetooth -> enableBluetoothLauncher.launch(BluetoothIntents.enableBluetooth())
            ConnectionRequest.OpenBluetoothSettings -> startActivitySafely(BluetoothIntents.openBluetoothSettings())
            is ConnectionRequest.RequestUsbPermission -> lifecycleScope.launch {
                val granted = usbPermissions.request(request.address, request.deviceKey)
                log.i { "USB permission of ${request.address}: $granted" }
                refresher.refreshUsb()
            }
            ConnectionRequest.RequestIgnoreBatteryOptimizations -> requestBatteryExemption()
            ConnectionRequest.OpenBatteryOptimizationSettings -> startActivitySafely(AndroidBatteryOptimization.settingsIntent())
            ConnectionRequest.OpenAppSettings ->
                startActivitySafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun requestPermissions(kinds: Set<PermissionKind>) {
        val permissions = PermissionRules.manifestPermissions(kinds, Build.VERSION.SDK_INT)
        if (permissions.isEmpty()) {
            // This API level needs no dialog for these kinds.
            refresher.refreshAll()
            return
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun showsCameraRationale(): Boolean = shouldShowRequestPermissionRationale(PermissionRules.CAMERA)

    // The first connect

    private fun watchFirstConnect() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                keepAliveController.summary.filterNotNull().first()
                appSettings.awaitLoaded()
                val prompts = FirstConnectRules.prompts(
                    sdkInt = Build.VERSION.SDK_INT,
                    notificationsGranted = isGranted(PermissionRules.POST_NOTIFICATIONS),
                    notificationsAsked = appSettings.settings.value.notificationPermissionAsked,
                    batteryAsked = appSettings.settings.value.batteryExemptionAsked,
                    batteryExempt = batteryOptimization.exempt.value,
                )
                when {
                    FirstConnectPrompt.Notifications in prompts -> {
                        // The flag stays in the app settings, so a restart does not ask again.
                        appSettings.update { settings -> settings.copy(notificationPermissionAsked = true) }
                        notificationPermissionLauncher.launch(PermissionRules.POST_NOTIFICATIONS)
                    }
                    FirstConnectPrompt.BatteryOptimization in prompts -> openBatteryRequest()
                }
            }
        }
    }

    private suspend fun askBatteryExemptionIfNeeded() {
        appSettings.awaitLoaded()
        batteryOptimization.refresh()
        if (!appSettings.settings.value.batteryExemptionAsked && !batteryOptimization.exempt.value) {
            openBatteryRequest()
        }
    }

    /**
     * Opens the battery request of `core:ui` (`BatteryOptimizationRoute`): the reason text, with
     * "Continue" and "Not now". The dialog sets `AppSettings.batteryExemptionAsked`, and
     * "Continue" sends [ConnectionRequest.RequestIgnoreBatteryOptimizations] on the request bus.
     */
    private fun openBatteryRequest() {
        if (BatteryRequestRules.shouldOpen(navigation.globalEntries.map { entry -> entry.route })) {
            navigation.push(BatteryOptimizationRoute)
        }
    }

    private fun requestBatteryExemption() {
        // Some devices have no activity for the request: the settings list is the fallback.
        if (!startActivitySafely(AndroidBatteryOptimization.requestIntent(this))) {
            startActivitySafely(AndroidBatteryOptimization.settingsIntent())
        }
    }

    // Full screen

    private fun collectFullScreen() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appSettings.settings.map { settings -> settings.fullScreen }.distinctUntilChanged().collect { fullScreen ->
                    SystemBars.apply(window, fullScreen)
                }
            }
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** `CreateDocument` with the MIME type of each request. */
    private class CreateDocumentOfType : ActivityResultContracts.CreateDocument(DEFAULT_MIME_TYPE) {
        var mimeType: String = DEFAULT_MIME_TYPE

        override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).setType(mimeType)

        private companion object {
            const val DEFAULT_MIME_TYPE = "application/octet-stream"
        }
    }

    companion object {
        /** The intent extra of the demo mode. Only the Demo launcher entry of a debug build sets it. */
        internal const val EXTRA_DEMO = "demo"

        /** The intent extra of the notification tap: the ID of the machine of the tab to open. */
        const val EXTRA_DEVICE_ID = "org.superdro.extra.DEVICE_ID"

        private val log = Arbor.tag("MainActivity")
    }
}
