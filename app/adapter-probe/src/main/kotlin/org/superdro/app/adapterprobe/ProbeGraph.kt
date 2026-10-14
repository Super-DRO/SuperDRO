package org.superdro.app.adapterprobe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.toxicbakery.logging.Arbor
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.adapterprobe.CameraAttach
import org.superdro.core.adapterprobe.DirectoryProbeFiles
import org.superdro.core.adapterprobe.LinkSpec
import org.superdro.core.adapterprobe.LocalNetworkAccess
import org.superdro.core.adapterprobe.PairedAdapter
import org.superdro.core.adapterprobe.ProbeClock
import org.superdro.core.adapterprobe.ProbeConsole
import org.superdro.core.adapterprobe.ProbeEnvironment
import org.superdro.core.adapterprobe.ProbeRunner
import org.superdro.core.adapterprobe.RunRecord
import org.superdro.core.adapterprobe.TabletInfo
import org.superdro.core.connection.TransportFactory
import org.superdro.core.connection.tcp.TcpAddressRules
import org.superdro.core.connection.tcp.TcpTransportFactory
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind
import org.superdro.platform.android.bluetooth.BluetoothCapabilityMonitor
import org.superdro.platform.android.bluetooth.SppAdapterDiscovery
import org.superdro.platform.android.bluetooth.SppTransportFactory
import org.superdro.platform.android.network.NetworkRules
import org.superdro.platform.android.usb.UsbPermissionRequester
import org.superdro.platform.android.usb.UsbSerialDiscovery
import org.superdro.platform.android.usb.UsbSerialTransportFactory

/** The link types of the Link screen. [Fake] is only in a debug build. */
enum class LinkType { Bluetooth, Usb, Tcp, Fake }

/**
 * The link that the user selected on the Link screen.
 *
 * @property baudRate The USB baud rate. The default is 115200.
 * @property port The TCP port as typed. The default is 8080.
 */
data class LinkChoice(
    val type: LinkType = LinkType.Bluetooth,
    val bluetooth: String? = null,
    val bluetoothName: String? = null,
    val usb: AdapterAddress.UsbSerial? = null,
    val baudRate: Int = AdapterAddress.UsbSerial.DEFAULT_BAUD_RATE,
    val host: String = "",
    val port: String = AdapterAddress.Tcp.DEFAULT_PORT.toString(),
    val fakeScenario: String = "commands",
) {
    /** The address of the selected link, or null when the selection is not complete or not valid. */
    fun address(): AdapterAddress? = when (type) {
        LinkType.Bluetooth -> bluetooth?.let { AdapterAddress.BluetoothSpp(it) }
        LinkType.Usb -> usb?.copy(baudRate = baudRate)
        LinkType.Tcp -> tcpAddress()
        LinkType.Fake -> AdapterAddress.Fake(fakeScenario)
    }

    /** The TCP address of [host] and [port], or null when it is not valid. */
    fun tcpAddress(): AdapterAddress.Tcp? {
        val port = port.trim().toIntOrNull() ?: return null
        val address = AdapterAddress.Tcp(host.trim(), port)
        return if (TcpAddressRules.isValid(address)) address else null
    }
}

/** A request of run-time permissions to the activity. The activity shows the Android dialog and completes [result]. */
class PermissionAsk(val permissions: List<String>, val result: CompletableDeferred<Map<String, Boolean>>)

/** The bridge from the probe to the permission launcher of the activity. */
class PermissionBridge {
    private val asks = MutableSharedFlow<PermissionAsk>(extraBufferCapacity = 4)

    /** The requests for the activity. */
    val requests: SharedFlow<PermissionAsk> = asks.asSharedFlow()

    /** Asks for [permissions] and waits for the answer. With no activity, the answer comes after 60 s as "not granted". */
    suspend fun request(permissions: List<String>): Map<String, Boolean> {
        val ask = PermissionAsk(permissions, CompletableDeferred())
        asks.emit(ask)
        return withTimeoutOrNull(REQUEST_LIMIT_MS) { ask.result.await() } ?: permissions.associateWith { false }
    }

    private companion object {
        const val REQUEST_LIMIT_MS = 60_000L
    }
}

/** The run records of the report: the file `runs.jsonl` in the log folder, one JSON line for each run. */
class RunStore(private val file: File) {
    private val _records = MutableStateFlow(load())

    /** The runs in their order. */
    val records: StateFlow<List<RunRecord>> = _records.asStateFlow()

    private fun load(): List<RunRecord> =
        if (file.isFile) file.readLines().mapNotNull(RunRecord::fromJsonLine) else emptyList()

    /** Adds one run. */
    @Synchronized
    fun add(record: RunRecord) {
        file.parentFile?.mkdirs()
        file.appendText(record.toJsonLine() + "\n")
        _records.update { list -> list + record }
    }
}

/** The camera attaches of TT2: the file `tt2-attaches.jsonl` in the log folder. */
class AttachStore(private val file: File) {
    private val _attaches = MutableStateFlow(load())

    /** The attaches in their order. */
    val attaches: StateFlow<List<CameraAttach>> = _attaches.asStateFlow()

    private fun load(): List<CameraAttach> =
        if (file.isFile) file.readLines().mapNotNull(CameraAttach::fromJsonLine) else emptyList()

    /** Adds one attach. */
    @Synchronized
    fun add(attach: CameraAttach) {
        file.parentFile?.mkdirs()
        file.appendText(attach.toJsonLine() + "\n")
        _attaches.value = load()
    }

    /** Sets the answer of the user for the attach [index]: true when Android showed a dialog. */
    @Synchronized
    fun answer(index: Int, dialogShown: Boolean) {
        val list = load().toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(dialogShown = dialogShown)
        file.writeText(list.joinToString("") { it.toJsonLine() + "\n" })
        _attaches.value = list
    }

    /** Reads the file again (the attach activity writes it). */
    fun reload() {
        _attaches.value = load()
    }
}

/** The local network permission of TT1: Android 17 (API 37) and higher has `ACCESS_LOCAL_NETWORK`. */
class AndroidLocalNetworkAccess(private val context: Context, private val bridge: PermissionBridge) : LocalNetworkAccess {
    override val exists: Boolean get() = NetworkRules.localNetworkPermissionNeeded(Build.VERSION.SDK_INT)

    override fun granted(): Boolean =
        exists && ContextCompat.checkSelfPermission(context, NetworkRules.LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override suspend fun request(): Boolean {
        if (!exists) return false
        return bridge.request(listOf(NetworkRules.LOCAL_NETWORK_PERMISSION))[NetworkRules.LOCAL_NETWORK_PERMISSION] == true || granted()
    }
}

/**
 * The objects of the probe app for the lifetime of the process. The runner and the console live
 * here, so that a test continues while the activity is not visible.
 */
class ProbeGraph(context: Context) {

    private val app: Context = context.applicationContext
    private val log = Arbor.tag("ProbeGraph")

    /** The scope of the runs: one thread at a time. */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
            CoroutineExceptionHandler { _, failure -> log.e(failure) { "A probe coroutine failed" } },
    )

    /** The folder of the `.bin` files, the `.log` files, and the run records. */
    val logFolder: File = File(app.filesDir, LOG_FOLDER).apply { mkdirs() }

    /** The tablet data of the report. */
    val tablet: TabletInfo = TabletInfo(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        securityPatch = Build.VERSION.SECURITY_PATCH.orEmpty(),
        appVersion = "SuperDRO Probe ${BuildConfig.VERSION_NAME}",
    )

    val permissions = PermissionBridge()
    val usbRequester = UsbPermissionRequester(app)
    val sppDiscovery = SppAdapterDiscovery(app)

    /** The Bluetooth radio and the `BLUETOOTH_CONNECT` permission. */
    val bluetoothCapability = BluetoothCapabilityMonitor(app, scope)

    val usbDiscovery = UsbSerialDiscovery(app, usbRequester)
    val localNetwork: LocalNetworkAccess = AndroidLocalNetworkAccess(app, permissions)

    /** The SuperDRO transport factories of the three link types, and the fake adapter in a debug build. */
    val factories: Map<TransportKind, TransportFactory> = buildMap {
        put(TransportKind.BluetoothSpp, SppTransportFactory(app, Dispatchers.IO))
        put(TransportKind.UsbSerial, UsbSerialTransportFactory(app, Dispatchers.IO))
        put(TransportKind.Tcp, TcpTransportFactory(Dispatchers.IO))
        DebugLinks.factory?.let { put(TransportKind.Fake, it) }
    }

    val records = RunStore(File(logFolder, RUNS_FILE))
    val attaches = AttachStore(File(logFolder, ATTACH_FILE))

    /** The environment of the probe: the monotonic clock `SystemClock.elapsedRealtimeNanos()`. */
    val environment = ProbeEnvironment(
        clock = ProbeClock { SystemClock.elapsedRealtimeNanos() },
        wallClockMs = System::currentTimeMillis,
        files = DirectoryProbeFiles(logFolder.path),
        linkIo = Dispatchers.IO,
        writer = "SuperDRO adapter probe ${BuildConfig.VERSION_NAME}",
        beforeOpen = { address -> if (address is AdapterAddress.UsbSerial) usbRequester.request(address) },
        pairedDevices = { pairedDevices() },
    )

    val runner = ProbeRunner(scope, environment) { record -> records.add(record) }
    val console = ProbeConsole(scope, environment) { record -> records.add(record) }

    private val _link = MutableStateFlow(LinkChoice(type = if (DebugLinks.available) LinkType.Fake else LinkType.Bluetooth))

    /** The link that the user selected. */
    val link: StateFlow<LinkChoice> = _link.asStateFlow()

    /** Changes the selected link. */
    fun selectLink(transform: (LinkChoice) -> LinkChoice) {
        _link.update(transform)
    }

    /** The link of a run, or null when the selection is not complete. */
    fun linkSpec(choice: LinkChoice = link.value): LinkSpec? {
        val address = choice.address() ?: return null
        val factory = factories[address.kind] ?: return null
        return LinkSpec(address, factory)
    }

    /** The paired Bluetooth devices. Without the permission the list is empty. */
    suspend fun pairedDevices(): List<PairedAdapter> =
        sppDiscovery.adapters.first().mapNotNull { adapter ->
            (adapter.address as? AdapterAddress.BluetoothSpp)?.let { PairedAdapter(it.mac, adapter.displayName) }
        }

    /** True while a test or the console holds a link. */
    val active: kotlinx.coroutines.flow.Flow<Boolean> =
        combine(runner.state, console.state) { run, console -> run.running || console.open }.distinctUntilChanged()

    init {
        // A foreground service of the type connectedDevice holds the link while a test runs.
        scope.launch {
            active.collect { on ->
                val intent = Intent(app, ProbeService::class.java)
                try {
                    if (on) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
                } catch (failure: RuntimeException) {
                    log.w(failure) { "The foreground service did not change" }
                }
            }
        }
    }

    /** The run-time permissions of the links and the notification, on this Android version. */
    fun runtimePermissions(): List<String> = buildList {
        addAll(bluetoothPermissions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (NetworkRules.localNetworkPermissionNeeded(Build.VERSION.SDK_INT)) add(NetworkRules.LOCAL_NETWORK_PERMISSION)
    }

    /** The run-time permission of the paired Bluetooth list on this Android version. */
    fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    /** True when the app has [permission]. */
    fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val LOG_FOLDER = "adapter-logs"
        const val RUNS_FILE = "runs.jsonl"
        const val ATTACH_FILE = "tt2-attaches.jsonl"
    }
}
