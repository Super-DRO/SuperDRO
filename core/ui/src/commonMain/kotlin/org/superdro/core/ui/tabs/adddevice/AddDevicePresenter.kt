package org.superdro.core.ui.tabs.adddevice

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.PermissionKind
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AppSettings
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.TransportKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.add_device_error_host_blank
import org.superdro.core.resources.add_device_error_host_not_valid
import org.superdro.core.resources.add_device_error_port
import org.superdro.core.resources.add_device_error_select_profile
import org.superdro.core.store.StoreResult
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.tabs.FirstConnectRequests
import org.superdro.core.ui.tabs.RequestSender
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** The steps of the add-device sheet. */
enum class AddDeviceStep {
    /** Step 1: select a link. */
    Link,

    /** Step 2: select a machine profile and name the machine. */
    Profile,
}

/** The source of the machine profile of step 2. */
enum class ProfileSource {
    /** A new machine with the machine setup. */
    New,

    /** A copy of a stored profile. */
    Copy,

    /** A stored profile. */
    Stored,
}

/**
 * One link that the user can select.
 *
 * @property suggestedName The machine name that step 2 shows first, or null.
 */
@Immutable
data class LinkOption(
    val address: AdapterAddress,
    val name: String,
    val detail: String? = null,
    val needsPermission: Boolean = false,
    val suggestedName: String? = null,
)

/**
 * The adapters of one link type in step 1.
 *
 * @property permissionMissing True when the Bluetooth permission is not granted: the sheet shows
 *   "Grant permission".
 * @property radioOff True when the Bluetooth radio is off: the sheet shows "Turn on Bluetooth".
 */
@Immutable
data class LinkSection(
    val kind: TransportKind,
    val options: List<LinkOption> = emptyList(),
    val permissionMissing: Boolean = false,
    val radioOff: Boolean = false,
)

/** One stored machine profile of step 2. */
@Immutable
data class StoredProfile(val id: String, val name: String, val machineType: MachineType)

/** The result of the sheet: the machine [profileId] is remembered and connects. */
@Immutable
data class AddDeviceResult(val profileId: String)

/**
 * The state of the add-device sheet.
 *
 * @property sections The Bluetooth and USB sections that the app settings show.
 * @property wifiShown True when the Wi-Fi link type shows.
 * @property demo The demo links of a debug build, or null in a release build.
 */
@Immutable
data class AddDeviceUiState(
    val step: AddDeviceStep = AddDeviceStep.Link,
    val sections: List<LinkSection> = emptyList(),
    val wifiShown: Boolean = true,
    val demo: LinkSection? = null,
    val selected: LinkOption? = null,
    val wifiHost: String = "",
    val wifiPort: String = AdapterAddress.Tcp.DEFAULT_PORT.toString(),
    val wifiHostError: UiText? = null,
    val wifiPortError: UiText? = null,
    val source: ProfileSource = ProfileSource.New,
    val profiles: List<StoredProfile> = emptyList(),
    val sourceProfileId: String? = null,
    val name: String = "",
    val nameError: UiText? = null,
    val setup: MachineSetup = MachineSetup(),
    val setupError: UiText? = null,
    val conflicts: List<UiText> = emptyList(),
    val busy: Boolean = false,
    val result: AddDeviceResult? = null,
    val rfcommBindHint: Boolean = false,
)

/**
 * The presenter of the add-device sheet.
 *
 * 1. Select a link: a paired Bluetooth adapter, an attached USB adapter, a Wi-Fi adapter (host and
 *    port, default port 8080), or "Demo" (debug builds only, through the [DebugAdapter]
 *    seam). "Pair new adapter" opens the system Bluetooth settings. The app settings can hide a
 *    link type.
 * 2. Select a machine profile: a new machine (the machine setup), a copy, or a stored
 *    profile. The user names the machine. The names are unique.
 * 3. The app remembers the machine and connects. The tab shows when the machine is online. The
 *    sheet sends the permission requests through the request bus.
 *
 * A new link gets a new adapter profile with the default encoder profiles, so the setup reminder shows until the user
 * calibrates the inputs.
 *
 * @param rfcommBindHint True on the Linux desktop: the Bluetooth section shows the hint for
 *   `rfcomm bind` ([platformRfcommBindHint]).
 */
class AddDevicePresenter(
    private val connections: Connections,
    private val tabStore: TabStore,
    private val machines: MachineRepository,
    private val adapters: AdapterRepository,
    appSettings: StateFlow<AppSettings>,
    discoveries: List<AdapterDiscovery>,
    capabilities: CapabilitySource,
    debugAdapter: DebugAdapter,
    private val requests: RequestSender,
    private val firstConnect: FirstConnectRequests,
    context: PresenterContext,
    rfcommBindHint: Boolean = platformRfcommBindHint(),
) : ScopedPresenter(context) {

    private val state = MutableStateFlow(AddDeviceUiState(rfcommBindHint = rfcommBindHint))

    /** The state of the sheet. */
    val uiState: StateFlow<AddDeviceUiState> = state.asStateFlow()

    init {
        val bluetooth = adaptersOf(discoveries, TransportKind.BluetoothSpp)
        val usb = adaptersOf(discoveries, TransportKind.UsbSerial)
        scope.launch {
            combine(appSettings, capabilities.state, bluetooth, usb) { settings, capability, bluetoothList, usbList ->
                val shown = LinkTypes.shown(settings.hiddenLinkTypes)
                val bluetoothCapability = capability.bluetooth
                val missing = bluetoothCapability is LinkCapability.Unavailable && bluetoothCapability.cause == LinkFailure.PermissionMissing
                val radioOff = bluetoothCapability is LinkCapability.Unavailable && bluetoothCapability.cause == LinkFailure.RadioOff
                val sections = shown.mapNotNull { kind ->
                    when (kind) {
                        TransportKind.BluetoothSpp -> LinkSection(kind, bluetoothList.map(::optionOf), permissionMissing = missing, radioOff = radioOff)
                        TransportKind.UsbSerial -> LinkSection(kind, usbList.map(::optionOf))
                        else -> null
                    }
                }
                sections to (TransportKind.Tcp in shown)
            }.collect { (sections, wifi) -> state.update { old -> old.copy(sections = sections, wifiShown = wifi) } }
        }
        if (debugAdapter.transportFactory != null) {
            val demoLinks = debugAdapter.demoLinks.map { link ->
                LinkOption(link.address, link.name, detail = null, suggestedName = link.name)
            }
            val fakes = debugAdapter.discovery?.adapters ?: flowOf(emptyList())
            scope.launch {
                fakes.onStart { emit(emptyList()) }.collect { list ->
                    val options = demoLinks + list.map(::optionOf).filter { option -> demoLinks.none { it.address == option.address } }
                    state.update { old -> old.copy(demo = LinkSection(TransportKind.Fake, options)) }
                }
            }
        }
        scope.launch {
            machines.profiles().collect { profiles ->
                state.update { old -> old.copy(profiles = profiles.map { StoredProfile(it.id, it.name, it.machineType) }) }
            }
        }
    }

    /** Selects a discovered link or a demo link, and goes to step 2. A USB adapter with no permission asks for it. */
    fun selectLink(option: LinkOption) {
        val address = option.address
        if (option.needsPermission && address is AdapterAddress.UsbSerial) requests.send(ConnectionRequest.RequestUsbPermission(address))
        state.update { old -> old.copy(step = AddDeviceStep.Profile, selected = option, name = option.suggestedName ?: old.name, nameError = null) }
    }

    /** Changes the host text of the Wi-Fi link. */
    fun setWifiHost(text: String) = state.update { old -> old.copy(wifiHost = text, wifiHostError = null) }

    /** Changes the port text of the Wi-Fi link. */
    fun setWifiPort(text: String) = state.update { old -> old.copy(wifiPort = text, wifiPortError = null) }

    /** Checks the Wi-Fi host and port and selects the link. */
    fun useWifi() {
        val current = state.value
        val hostError = when (HostCheck.check(current.wifiHost)) {
            HostCheck.Result.Valid -> null
            HostCheck.Result.Blank -> UiText.of(Res.string.add_device_error_host_blank)
            HostCheck.Result.NotValid -> UiText.of(Res.string.add_device_error_host_not_valid)
        }
        val port = HostCheck.port(current.wifiPort)
        val portError = if (port == null) UiText.of(Res.string.add_device_error_port) else null
        if (hostError != null || port == null) {
            state.update { old -> old.copy(wifiHostError = hostError, wifiPortError = portError) }
            return
        }
        val host = current.wifiHost.trim()
        selectLink(LinkOption(AdapterAddress.Tcp(host, port), name = "$host:$port"))
    }

    /** "Pair new adapter": opens the system Bluetooth settings. */
    fun pairNewAdapter() = requests.send(ConnectionRequest.OpenBluetoothSettings)

    /** Asks for the Bluetooth permission. */
    fun grantBluetooth() = requests.send(ConnectionRequest.RequestPermissions(setOf(PermissionKind.BluetoothConnect)))

    /** Asks the user to turn on the Bluetooth radio. */
    fun enableBluetooth() = requests.send(ConnectionRequest.EnableBluetooth)

    /** Goes back to step 1. */
    fun back() = state.update { old -> old.copy(step = AddDeviceStep.Link) }

    /** Selects the source of the machine profile. */
    fun setSource(source: ProfileSource) = state.update { old ->
        old.copy(source = source, nameError = null, setupError = null, sourceProfileId = if (source == ProfileSource.New) null else old.sourceProfileId)
    }

    /** Selects the stored profile of a copy or of a stored profile. A stored profile gives its name. */
    fun selectProfile(id: String) = state.update { old ->
        val profile = old.profiles.firstOrNull { it.id == id }
        old.copy(sourceProfileId = id, nameError = null, name = if (old.source == ProfileSource.Stored && profile != null) profile.name else old.name)
    }

    /** Changes the name of the machine. */
    fun setName(text: String) = state.update { old -> old.copy(name = text, nameError = null) }

    /** Changes the answers of the machine setup. The conflict check runs again. */
    fun updateSetup(transform: (MachineSetup) -> MachineSetup) = state.update { old ->
        val setup = transform(old.setup)
        old.copy(setup = setup, setupError = null, conflicts = setup.conflicts())
    }

    /** Step 3: remembers the machine and connects. */
    fun connect() {
        val current = state.value
        val link = current.selected ?: return
        if (current.busy) return
        state.update { old -> old.copy(busy = true) }
        scope.launch {
            val profile = writeProfile(current, link)
            if (profile == null) {
                state.update { old -> old.copy(busy = false) }
                return@launch
            }
            val linked = ensureAdapterProfile(profile, link)
            tabStore.remember(linked.id)
            tabStore.setPaused(linked.id, false)
            // The stored link, not the selected one: the store gives a USB link the baud rate of the
            // setting of the profile.
            connections.connect(DeviceRef(DeviceId(linked.id), linked.name, linked.adapter ?: link.address, linked.twoWayCommunication))
            // The notification permission and the battery request of the first connect have one
            // flow in the activity. Only the local network permission of
            // the first TCP connect is here.
            if (link.address is AdapterAddress.Tcp && firstConnect.claimLocalNetwork()) {
                requests.send(ConnectionRequest.RequestPermissions(FirstConnectRequests.LOCAL_NETWORK))
            }
            state.update { old -> old.copy(busy = false, result = AddDeviceResult(linked.id)) }
        }
    }

    /** Writes the profile of step 2 with the link. Gives null and shows the refusal on a failure. */
    private suspend fun writeProfile(current: AddDeviceUiState, link: LinkOption): MachineProfile? {
        when (current.source) {
            ProfileSource.New -> {
                current.setup.refusal()?.let { refusal ->
                    state.update { old -> old.copy(setupError = refusal) }
                    return null
                }
                return stored(machines.createProfile(current.setup.toProfile(current.name).copy(adapter = link.address)))
            }
            ProfileSource.Copy -> {
                val source = current.sourceProfileId ?: return refuseSource()
                val copy = stored(machines.copyProfile(source, current.name)) ?: return null
                return stored(machines.updateProfile(copy.id) { profile -> profile.copy(adapter = link.address) })
            }
            ProfileSource.Stored -> {
                val id = current.sourceProfileId ?: return refuseSource()
                val old = machines.loadProfile(id) ?: return refuseSource()
                if (current.name.trim() != old.name) stored(machines.renameProfile(id, current.name)) ?: return null
                return stored(machines.updateProfile(id) { profile -> profile.copy(adapter = link.address) })
            }
        }
    }

    private fun refuseSource(): MachineProfile? {
        state.update { old -> old.copy(nameError = UiText.of(Res.string.add_device_error_select_profile)) }
        return null
    }

    private fun stored(result: StoreResult<MachineProfile>): MachineProfile? = when (result) {
        is StoreResult.Ok -> result.value
        is StoreResult.Refused -> {
            state.update { old -> old.copy(nameError = result.refusal.toUiText()) }
            null
        }
    }

    /**
     * Gives [profile] with an adapter profile: a profile with no adapter profile gets a new one
     * with a unique alias and the link as the last link.
     */
    private suspend fun ensureAdapterProfile(profile: MachineProfile, link: LinkOption): MachineProfile {
        if (profile.adapterProfileId != null) return profile
        val taken = adapters.adapterProfiles().first().map { adapter -> adapter.alias }
        val alias = AdapterAliases.unique(link.name, taken, fallback = profile.name)
        val saved = adapters.saveAdapterProfile(AdapterProfile(alias = alias, lastLink = link.address))
        val adapter = (saved as? StoreResult.Ok)?.value ?: return profile
        return (machines.updateProfile(profile.id) { old -> old.copy(adapterProfileId = adapter.id) } as? StoreResult.Ok)?.value ?: profile
    }

    private fun adaptersOf(discoveries: List<AdapterDiscovery>, kind: TransportKind): Flow<List<DiscoveredAdapter>> {
        val flows = discoveries.filter { discovery -> discovery.kind == kind }.map { discovery -> discovery.adapters.onStart { emit(emptyList()) } }
        if (flows.isEmpty()) return flowOf(emptyList())
        return combine(flows) { lists -> lists.flatMap { it } }
    }

    private fun optionOf(adapter: DiscoveredAdapter): LinkOption =
        LinkOption(adapter.address, adapter.displayName, adapter.detail, adapter.needsPermission)
}
