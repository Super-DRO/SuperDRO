package org.superdro.core.ui.settings.machine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AppSettings
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.TransportKind
import org.superdro.core.readout.settings.AxisSettingsRules
import org.superdro.core.readout.settings.InputConflicts
import org.superdro.core.readout.settings.SettingsResult
import org.superdro.core.resources.Res
import org.superdro.core.resources.add_device_error_host_blank
import org.superdro.core.resources.add_device_error_host_not_valid
import org.superdro.core.resources.add_device_error_port
import org.superdro.core.resources.settings_machine_error_label_blank
import org.superdro.core.resources.settings_machine_error_mac
import org.superdro.core.resources.settings_machine_error_presets
import org.superdro.core.resources.settings_machine_error_pulses
import org.superdro.core.store.StoreResult
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.MismatchDisposal
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.settings.typechange.MachineTypeChange
import org.superdro.core.ui.tabs.adddevice.HostCheck
import org.superdro.core.ui.tabs.adddevice.conflictText
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/**
 * The presenter of the machine settings screen of the profile [profileId].
 *
 * - Each change goes into [MachineRepository] at once. The session of the tab reads the
 *   profile through `DeviceSetupSource` and gets the new input configuration at once; the input
 *   stage starts a new filter state only for the changed channels. The screen follows the
 *   flow of the profile: no screen starts again.
 * - The validation rules of `core:readout` (`AxisSettingsRules`) refuse a change with their
 *   texts. A refusal shows through [showMessage], and the old value stays.
 * - One default table: a new profile has the defaults of `MachineProfile.create`.
 * - The writes go through one queue, so two changes of one value stay in their order.
 *
 * @param showMessage Shows a text in the snackbar of the frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachineSettingsPresenter(
    val profileId: String,
    private val machines: MachineRepository,
    private val adapters: AdapterRepository,
    private val connections: Connections,
    appSettings: StateFlow<AppSettings>,
    context: PresenterContext,
    private val showMessage: (UiText) -> Unit = {},
) : ScopedPresenter(context), MachineSettingsEvents {

    private val local = MutableStateFlow(LocalState())
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** The machine type change (the warning and the list of the changed settings). */
    val typeChange: MachineTypeChange = MachineTypeChange(machines, scope, showMessage)

    /** The stored profile. The outer null means "not loaded yet"; an inner null means "no such profile". */
    private val stored: StateFlow<Stored?> = machines.profile(profileId).map(::Stored).stateIn(scope, SharingStarted.Eagerly, null)

    private val profile: StateFlow<MachineProfile?> = stored.map { value -> value?.profile }.stateIn(scope, SharingStarted.Eagerly, null)

    private val tachometerEncoder: StateFlow<EncoderProfile?> = profile
        .map { current -> current?.adapterProfileId }
        .flatMapLatest { id -> if (id == null) flowOf(null) else adapters.adapterProfile(id).map { adapter -> adapter?.encoders?.get(Channels.T) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The state of the screen. */
    val uiState: StateFlow<MachineSettingsUiState> = combine(stored, local, appSettings, tachometerEncoder, typeChange.state) { value, view, app, encoder, question ->
        val current = value?.profile
        MachineSettingsUiState(
            loaded = value != null,
            profile = current,
            group = view.group.takeIf { group -> current == null || group in MachineSettingsGroup.groupsFor(current.machineType) }
                ?: MachineSettingsGroup.Machine,
            texts = view.texts,
            errors = view.errors,
            conflicts = current?.let { profile -> InputConflicts.check(profile).map { conflict -> conflictText(conflict, profile) } }.orEmpty(),
            muteScope = app.muteScope,
            tachometerPulses = encoder?.resolution,
            linkType = view.linkType ?: current?.adapter?.kind,
            typeChange = question,
        )
    }.stateIn(scope, SharingStarted.Eagerly, MachineSettingsUiState())

    init {
        scope.launch { for (write in writes) write() }
    }

    // -------------------------------------------------------------------------------------
    // The screen

    /** Selects the group [group]. The texts that the user typed and their errors stay. */
    override fun selectGroup(group: MachineSettingsGroup) = local.update { old -> old.copy(group = group) }

    // -------------------------------------------------------------------------------------
    // The machine

    /** Changes the name of the profile. The name must be unique. */
    override fun setName(text: String) {
        setText(SettingsTextField.Name, text)
        enqueue {
            when (val result = machines.renameProfile(profileId, text)) {
                is StoreResult.Ok -> clearError(SettingsTextField.Name)
                is StoreResult.Refused -> setError(SettingsTextField.Name, result.refusal.toUiText())
            }
        }
    }

    /** Asks for a change of the machine type with the warning and the list of the changed settings. */
    override fun setMachineType(type: MachineType) = typeChange.ask(profileId, type)

    override fun setTypeChangeDisposal(disposal: MismatchDisposal) = typeChange.setDisposal(disposal)

    override fun confirmTypeChange() = typeChange.confirm()

    override fun cancelTypeChange() = typeChange.cancel()

    override fun setMetricDefault(on: Boolean) = write { it.copy(metricDefault = on) }

    /** The mute switch of this machine. It applies with the mute scope "each machine". */
    override fun setMuteSounds(on: Boolean) = write { it.copy(muteSounds = on) }

    /** "Use shared tool library". The tool list changes at once. */
    override fun setUseSharedToolLibrary(on: Boolean) = write { it.copy(useSharedToolLibrary = on) }

    override fun setSidePanel(mode: SidePanelMode) = write { it.copy(sidePanel = mode) }

    override fun setUse3dSubDatums(on: Boolean) = write { it.copy(use3dSubDatums = on) }

    // -------------------------------------------------------------------------------------
    // The display

    override fun setReadoutFormat(format: ReadoutFormat) = write { it.copy(display = it.display.copy(readoutFormat = format)) }

    override fun setReadoutFont(font: ReadoutFont) = write { it.copy(display = it.display.copy(readoutFont = font)) }

    override fun setShowSliders(on: Boolean) = write { it.copy(display = it.display.copy(showSliders = on)) }

    override fun setShortPressZero(on: Boolean) = write { it.copy(display = it.display.copy(shortPressZero = on)) }

    override fun setActivateNewSubDatum(on: Boolean) = write { it.copy(display = it.display.copy(activateNewSubDatum = on)) }

    /** The pattern angle convention: a lathe always uses LEGACY. */
    override fun setPatternAngleConvention(convention: PatternAngleConvention) = write { current ->
        if (current.machineType.isLathe) current else current.copy(patternAngleConvention = convention)
    }

    override fun setCameraKeepOverlays(on: Boolean) = write { it.copy(display = it.display.copy(cameraKeepOverlays = on)) }

    /** The crosshair color: a key of `CrosshairColor` of `core:camera`. */
    override fun setCameraCrosshairColor(key: String) = write { it.copy(display = it.display.copy(cameraCrosshairColor = key)) }

    // -------------------------------------------------------------------------------------
    // The function strip

    override fun setFunctionButton(button: FunctionSwitch, on: Boolean) = write { it.copy(functionButtons = button.set(it.functionButtons, on)) }

    // -------------------------------------------------------------------------------------
    // The axes

    /**
     * Enables or disables [axis]. The last of X, Y, Z, and Angular cannot be disabled: "At least one
     * output axis must be enabled".
     */
    override fun setAxisEnabled(axis: AxisId, on: Boolean) {
        val current = profile.value ?: return
        val result = AxisSettingsRules.setAxisEnabled(current, axis, on)
        if (result is SettingsResult.Refused) {
            showMessage(result.message.toUiText())
            return
        }
        write { stored -> (AxisSettingsRules.setAxisEnabled(stored, axis, on) as? SettingsResult.Accepted)?.value ?: stored }
    }

    /** Changes the label of [axis]. A blank label is not written. */
    override fun setLabel(axis: AxisId, text: String) {
        val field = SettingsTextField.labelOf(axis) ?: return
        setText(field, text)
        if (text.isBlank()) {
            setError(field, UiText.of(Res.string.settings_machine_error_label_blank))
            return
        }
        clearError(field)
        val label = text.trim()
        write { current ->
            when (axis) {
                AxisId.X, AxisId.Y, AxisId.Z -> current.withLinear(axis) { config -> config.copy(label = label) }
                AxisId.Angular -> current.copy(angular = current.angular.copy(label = label))
                AxisId.Tachometer -> current.copy(tachometer = current.tachometer.copy(label = label))
                AxisId.Caliper -> current
            }
        }
    }

    /** Sets the function of the action button [index] (0, 1, 2) of [axis]. */
    override fun setButtonMode(axis: AxisId, index: Int, mode: AxisButtonMode) {
        if (mode !in buttonModesFor(axis)) return
        write { current -> current.withButtons(axis) { buttons -> buttons.replace(index) { it.copy(mode = mode) } } }
    }

    /** Sets "Show when expanded" of the action button [index] of [axis]. */
    override fun setButtonShowExpanded(axis: AxisId, index: Int, on: Boolean) =
        write { current -> current.withButtons(axis) { buttons -> buttons.replace(index) { it.copy(showExpanded = on) } } }

    /** Sets "Show when collapsed" of the action button [index] of [axis]. */
    override fun setButtonShowCollapsed(axis: AxisId, index: Int, on: Boolean) =
        write { current -> current.withButtons(axis) { buttons -> buttons.replace(index) { it.copy(showCollapsed = on) } } }

    /**
     * Sets the primary channel of [axis] (`x y z w u v`). A linear axis refuses the channel of
     * its secondary input: "Primary and secondary inputs can't use the same prefix".
     */
    override fun setPrimaryChannel(axis: AxisId, channel: Char) {
        val letter = Channels.normalize(channel)
        if (!Channels.isAxisChannel(letter)) return
        val current = profile.value ?: return
        when (axis) {
            AxisId.X, AxisId.Y, AxisId.Z -> {
                val result = AxisSettingsRules.setPrimaryChannel(current.linear(axis), letter)
                if (result is SettingsResult.Refused) {
                    showMessage(result.message.toUiText())
                    return
                }
                write { stored ->
                    (AxisSettingsRules.setPrimaryChannel(stored.linear(axis), letter) as? SettingsResult.Accepted)
                        ?.let { accepted -> stored.withLinear(axis) { accepted.value } } ?: stored
                }
            }
            AxisId.Angular -> write { it.copy(angular = it.angular.copy(primary = it.angular.primary.copy(channel = letter))) }
            AxisId.Caliper -> write { it.copy(caliper = it.caliper.copy(input = it.caliper.input.copy(channel = letter))) }
            AxisId.Tachometer -> Unit
        }
    }

    /** Sets the invert of the primary input of [axis]: the sign −1 (`<a>_invert`). */
    override fun setInvert(axis: AxisId, on: Boolean) {
        val sign = if (on) -1 else 1
        write { current ->
            when (axis) {
                AxisId.X, AxisId.Y, AxisId.Z -> current.withLinear(axis) { config -> config.copy(primary = config.primary.copy(sign = sign)) }
                AxisId.Angular -> current.copy(angular = current.angular.copy(primary = current.angular.primary.copy(sign = sign)))
                AxisId.Caliper -> current.copy(caliper = current.caliper.copy(input = current.caliper.input.copy(sign = sign)))
                AxisId.Tachometer -> current.copy(tachometer = current.tachometer.copy(invert = on))
            }
        }
    }

    /**
     * Turns the secondary input of [axis] (X, Y, or Z) on or off. On: the
     * channel becomes `w`, or the default primary channel when the primary uses `w`. Off: no
     * channel and invert false. The input conflict check then runs (the state has the conflicts).
     */
    override fun setSecondaryEnabled(axis: AxisId, on: Boolean) =
        write { current -> current.withLinear(axis) { config -> AxisSettingsRules.setSecondaryEnabled(axis, config, on) } }

    /** Sets the secondary channel of [axis]. The channel of the primary input is refused. */
    override fun setSecondaryChannel(axis: AxisId, channel: Char) {
        val letter = Channels.normalize(channel)
        if (!Channels.isAxisChannel(letter)) return
        val current = profile.value ?: return
        val result = AxisSettingsRules.setSecondaryChannel(current.linear(axis), letter)
        if (result is SettingsResult.Refused) {
            showMessage(result.message.toUiText())
            return
        }
        write { stored ->
            (AxisSettingsRules.setSecondaryChannel(stored.linear(axis), letter) as? SettingsResult.Accepted)
                ?.let { accepted -> stored.withLinear(axis) { accepted.value } } ?: stored
        }
    }

    /** Sets the invert of the secondary input of [axis]. */
    override fun setSecondaryInvert(axis: AxisId, on: Boolean) = write { current ->
        current.withLinear(axis) { config -> config.secondary?.let { input -> config.copy(secondary = input.copy(sign = if (on) -1 else 1)) } ?: config }
    }

    // -------------------------------------------------------------------------------------
    // The auxiliary readout of a lathe

    override fun setAuxiliaryType(type: AuxiliaryType) = write { it.copy(auxiliary = it.auxiliary.copy(type = type)) }

    override fun setAuxiliaryVectoring(on: Boolean) = write { it.copy(auxiliary = it.auxiliary.copy(vectoring = on)) }

    /** The angle reference of the auxiliary readout: 0, 90, or 180. */
    override fun setAuxiliaryAngleReference(degrees: Int) {
        if (degrees !in ANGLE_REFERENCES) return
        write { it.copy(auxiliary = it.auxiliary.copy(angleReferenceDegrees = degrees)) }
    }

    // -------------------------------------------------------------------------------------
    // The tachometer

    /** The tachometer mode. RPM sensor shows the pulses; preset shows the presets; direct entry hides both. */
    override fun setTachometerMode(mode: TachometerMode) = write { it.copy(tachometer = it.tachometer.copy(mode = mode)) }

    /** The spindle speed presets: numbers separated by spaces (`105 160 250 585 898 1400`). */
    override fun setPresets(text: String) {
        setText(SettingsTextField.Presets, text)
        val values = PresetsText.parse(text)
        if (values == null) {
            setError(SettingsTextField.Presets, UiText.of(Res.string.settings_machine_error_presets))
            return
        }
        clearError(SettingsTextField.Presets)
        write { it.copy(tachometer = it.tachometer.copy(presets = values)) }
    }

    /**
     * The pulses for each revolution of the tachometer: the resolution of the encoder profile of the
     * input `t` of the adapter profile. A number more than 0. A new value that is not 0 sets
     * the tachometer offsets of each workspace to 0.
     */
    override fun setPulses(text: String) {
        setText(SettingsTextField.Pulses, text)
        val value = text.trim().replace(',', '.').toDoubleOrNull()
        if (value == null || !value.isFinite() || value <= 0.0) {
            setError(SettingsTextField.Pulses, UiText.of(Res.string.settings_machine_error_pulses))
            return
        }
        clearError(SettingsTextField.Pulses)
        enqueue {
            val adapterId = profile.value?.adapterProfileId ?: return@enqueue
            val adapter = adapters.loadAdapterProfile(adapterId) ?: return@enqueue
            val old = adapter.encoders[Channels.T] ?: EncoderProfile.defaultFor(Channels.T)
            if (old.resolution == value) return@enqueue
            adapters.saveEncoderProfile(adapterId, old.copy(resolution = value))
            machines.workspaces(profileId).first().forEach { workspace ->
                machines.updateWorkspace(workspace.id) { stored -> AxisSettingsRules.onTachometerPulsesChanged(stored, old.resolution, value) }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // The probe

    override fun setProbeEnabled(on: Boolean) = write { it.copy(probe = it.probe.copy(enabled = on)) }

    override fun setEdgeFinderEnabled(on: Boolean) = write { it.copy(probe = it.probe.copy(edgeFinderEnabled = on)) }

    override fun setToolSetterEnabled(on: Boolean) = write { it.copy(probe = it.probe.copy(toolSetterEnabled = on)) }

    // -------------------------------------------------------------------------------------
    // The adapter link

    /**
     * Selects the link type of the link group. The stored address changes only when the new type
     * has a valid address: a Bluetooth address, or a Wi-Fi host and port. A USB device comes only
     * from the add-device sheet.
     */
    override fun setLinkType(kind: TransportKind) {
        local.update { old -> old.copy(linkType = kind) }
        val current = uiState.value
        when (kind) {
            TransportKind.BluetoothSpp -> setBluetoothAddress(current.textOf(SettingsTextField.BluetoothAddress))
            TransportKind.Tcp -> applyWifi(current.textOf(SettingsTextField.WifiHost), current.textOf(SettingsTextField.WifiPort))
            else -> Unit
        }
    }

    /** The Bluetooth address of the adapter, for example `24:6F:28:A1:B2:C3`. */
    override fun setBluetoothAddress(text: String) {
        setText(SettingsTextField.BluetoothAddress, text)
        val mac = BluetoothAddressCheck.normalize(text)
        if (mac == null) {
            setError(SettingsTextField.BluetoothAddress, UiText.of(Res.string.settings_machine_error_mac))
            return
        }
        clearError(SettingsTextField.BluetoothAddress)
        setAddress(AdapterAddress.BluetoothSpp(mac))
    }

    /** The Wi-Fi host: an IPv4 address, an IPv6 address, or a host name. */
    override fun setWifiHost(text: String) {
        setText(SettingsTextField.WifiHost, text)
        applyWifi(text, uiState.value.textOf(SettingsTextField.WifiPort))
    }

    /** The Wi-Fi port: 1 to 65535. */
    override fun setWifiPort(text: String) {
        setText(SettingsTextField.WifiPort, text)
        applyWifi(uiState.value.textOf(SettingsTextField.WifiHost), text)
    }

    private fun applyWifi(hostText: String, portText: String) {
        val hostError = when (HostCheck.check(hostText)) {
            HostCheck.Result.Valid -> null
            HostCheck.Result.Blank -> UiText.of(Res.string.add_device_error_host_blank)
            HostCheck.Result.NotValid -> UiText.of(Res.string.add_device_error_host_not_valid)
        }
        val port = HostCheck.port(portText)
        if (hostError == null) clearError(SettingsTextField.WifiHost) else setError(SettingsTextField.WifiHost, hostError)
        if (port == null) setError(SettingsTextField.WifiPort, UiText.of(Res.string.add_device_error_port)) else clearError(SettingsTextField.WifiPort)
        if (hostError != null || port == null) return
        setAddress(AdapterAddress.Tcp(hostText.trim(), port))
    }

    /**
     * The USB baud rate (9600 to 115200; default 115200). The profile setting and the rate of
     * the current USB link stay equal.
     */
    override fun setUsbBaudRate(rate: Int) {
        if (rate !in AdapterAddress.UsbSerial.BAUD_RATES) return
        enqueue {
            val result = machines.updateProfile(profileId) { current ->
                val usb = current.adapter as? AdapterAddress.UsbSerial
                current.copy(usbBaudRate = rate, adapter = usb?.copy(baudRate = rate) ?: current.adapter)
            }
            (result as? StoreResult.Ok)?.value?.let(::applyLink)
        }
    }

    /** Two-way communication. Off: the session writes no byte. The session follows the profile. */
    override fun setTwoWayCommunication(on: Boolean) = write { it.copy(twoWayCommunication = on) }

    /** "Adapter configuration commands" (default off). Off: the session refuses the configuration commands. */
    override fun setAdapterConfigurationCommands(on: Boolean) = write { it.copy(adapterConfigurationCommands = on) }

    private fun setAddress(address: AdapterAddress) {
        enqueue {
            val stored = profile.value ?: machines.loadProfile(profileId) ?: return@enqueue
            if (stored.adapter == address) return@enqueue
            val result = machines.updateProfile(profileId) { current -> current.copy(adapter = address) }
            (result as? StoreResult.Ok)?.value?.let(::applyLink)
        }
    }

    /** A running session gets the new address at once: the session starts a new link. */
    private fun applyLink(stored: MachineProfile) {
        val address = stored.adapter ?: return
        val session = connections.sessions.value[DeviceId(profileId)] ?: return
        if (session.state.value == SessionState.Disconnected) return
        if (session.device.value.address == address) return
        connections.connect(DeviceRef(DeviceId(profileId), stored.name, address, stored.twoWayCommunication))
    }

    // -------------------------------------------------------------------------------------

    private fun write(transform: (MachineProfile) -> MachineProfile) {
        enqueue { machines.updateProfile(profileId, transform) }
    }

    private fun enqueue(work: suspend () -> Unit) {
        writes.trySend(work)
    }

    private fun setText(field: SettingsTextField, text: String) = local.update { old -> old.copy(texts = old.texts + (field to text)) }

    private fun setError(field: SettingsTextField, error: UiText) = local.update { old -> old.copy(errors = old.errors + (field to error)) }

    private fun clearError(field: SettingsTextField) = local.update { old -> old.copy(errors = old.errors - field) }

    private data class Stored(val profile: MachineProfile?)

    private data class LocalState(
        val group: MachineSettingsGroup = MachineSettingsGroup.Machine,
        val texts: Map<SettingsTextField, String> = emptyMap(),
        val errors: Map<SettingsTextField, UiText> = emptyMap(),
        val linkType: TransportKind? = null,
    )
}

/** Gives the list with the item at [index] changed by [transform]. An index that is not in the list changes nothing. */
private fun <T> List<T>.replace(index: Int, transform: (T) -> T): List<T> =
    if (index !in indices) this else mapIndexed { position, item -> if (position == index) transform(item) else item }

/** The check of a Bluetooth address: six groups of two hex digits with `:` or `-` between them. */
object BluetoothAddressCheck {

    private val pattern = Regex("[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}")

    /** Gives the address in the form `24:6F:28:A1:B2:C3`, or null when [text] is not an address. */
    fun normalize(text: String): String? {
        val trimmed = text.trim()
        if (!pattern.matches(trimmed)) return null
        return trimmed.replace('-', ':').uppercase()
    }
}
