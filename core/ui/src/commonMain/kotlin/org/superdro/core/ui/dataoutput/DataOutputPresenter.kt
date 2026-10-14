package org.superdro.core.ui.dataoutput

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.dataoutput.BindAddresses
import org.superdro.core.dataoutput.DataOutputState
import org.superdro.core.dataoutput.DataOutputStatus
import org.superdro.core.dataoutput.DataOutputValidation
import org.superdro.core.dataoutput.Diagnostics
import org.superdro.core.dataoutput.OutputRunRule
import org.superdro.core.dataoutput.hostAndPort
import org.superdro.core.model.AngleUnit
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputCadence
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputDelimiter
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.DistanceUnit
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MqttTopicLayout
import org.superdro.core.model.TimestampFormat
import org.superdro.core.resources.Res
import org.superdro.core.resources.data_output_broker
import org.superdro.core.resources.data_output_counts
import org.superdro.core.resources.data_output_label_character
import org.superdro.core.resources.data_output_last_success
import org.superdro.core.resources.data_output_no_delivery
import org.superdro.core.resources.data_output_problem
import org.superdro.core.resources.dataoutput_broker_port_range
import org.superdro.core.resources.dataoutput_correct_first
import org.superdro.core.resources.dataoutput_interval_range
import org.superdro.core.resources.dataoutput_label_too_long
import org.superdro.core.resources.dataoutput_port_conflict
import org.superdro.core.resources.dataoutput_port_range
import org.superdro.core.resources.dataoutput_psk_copied
import org.superdro.core.resources.dataoutput_psk_new_done
import org.superdro.core.resources.dataoutput_rate_range
import org.superdro.core.resources.dataoutput_save_failed
import org.superdro.core.resources.dataoutput_saved
import org.superdro.core.resources.dataoutput_secret_error
import org.superdro.core.store.StoreResult
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.tabs.RequestSender
import org.superdro.core.ui.text.UiText

/**
 * The presenter of the data output screen of a machine. The
 * screen is a global screen: it selects the machine profile, and
 * it starts with the machine of the selected tab.
 *
 * Rules:
 * - The switch is off by default. No server socket is open while it is off. The switch
 *   off writes at once, and the output closes the server and the clients at once. The
 *   switch on saves the draft first. It needs a draft with no problem.
 * - The plain text mode and a broker link with no TLS apply only after the warning "Use only on
 *   trusted networks" (the security rules).
 * - Two outputs cannot use the same port.
 * - A label refuses the characters that its format cannot carry.
 * - "Always on" is available only after the user turns on the output.
 * - The PSK and the MQTT credentials stay in the `SecretStore`.
 * - The local network permission request goes out when the user turns on or starts the output.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataOutputPresenter(
    private val runner: DataOutputRunner,
    private val machines: MachineRepository,
    private val networkInterfaces: NetworkInterfaces,
    private val requests: RequestSender,
    context: PresenterContext,
) : ScopedPresenter(context), DataOutputEvents {

    private val _uiState = MutableStateFlow(DataOutputUiState())

    /** The UI state of the screen. */
    val uiState: StateFlow<DataOutputUiState> = _uiState.asStateFlow()

    private val selected = MutableStateFlow<String?>(null)
    private var requestedProfile: String? = null
    private var profiles: List<MachineProfile> = emptyList()
    private var runtime: DataOutputState? = null
    private var interfaces: List<NetworkInterfaceInfo> = emptyList()
    private var storedUsername = ""
    private var storedPassword = ""
    private var diagnosticsJob: Job? = null
    private var messageIds = 0L

    init {
        scope.launch {
            machines.profiles().collect { list ->
                profiles = list
                val current = selected.value?.takeIf { id -> list.any { profile -> profile.id == id } }
                val next = current ?: requestedProfile?.takeIf { id -> list.any { profile -> profile.id == id } } ?: list.firstOrNull()?.id
                setState { state -> state.copy(profiles = list.map { profile -> ProfileChoice(profile.id, profile.name) }) }
                if (next != selected.value) {
                    selected.value = next
                    if (next == null) setState { state -> state.copy(loading = false, profileId = null) }
                } else {
                    list.firstOrNull { profile -> profile.id == next }?.let { profile -> onStoredChanged(profile.dataOutput) }
                }
            }
        }
        scope.launch {
            selected.collect { id -> if (id != null) load(id) }
        }
        scope.launch {
            selected.flatMapLatest { id -> if (id == null) flowOf(null) else runner.state(id) }.collect { state ->
                runtime = state
                refresh()
            }
        }
        scope.launch {
            networkInterfaces.interfaces.collect { list ->
                interfaces = list
                setState { state ->
                    state.copy(interfaces = BindInterface.entries.map { bind -> InterfaceChoice(bind, BindAddresses.hosts(list, bind)) })
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The machine

    override fun onSelectProfile(profileId: String) {
        selectProfile(profileId)
    }

    /** Selects the machine [profileId], for example the machine of the selected tab. */
    fun selectProfile(profileId: String?) {
        requestedProfile = profileId
        if (profileId != null && profiles.any { profile -> profile.id == profileId }) selected.value = profileId
    }

    private suspend fun load(profileId: String) {
        val profile = profiles.firstOrNull { item -> item.id == profileId } ?: return
        val config = profile.dataOutput
        setState { state ->
            state.copy(
                loading = false,
                profileId = profileId,
                draft = config,
                stored = config,
                portText = config.port.toString(),
                intervalText = config.intervalMs.toString(),
                maxRateText = config.maxRateHz.toString(),
                brokerPortText = config.mqttBrokerPort.toString(),
                mqttUsername = "",
                mqttPassword = "",
                credentialsChanged = false,
                labelRefusals = emptyMap(),
                psk = null,
                dialog = null,
                testResult = null,
            )
        }
        secrets {
            val credentials = runner.mqttCredentials(profileId)
            storedUsername = credentials?.username.orEmpty()
            storedPassword = credentials?.password?.decodeToString().orEmpty()
            setState { state ->
                if (state.profileId != profileId) state else state.copy(mqttUsername = storedUsername, mqttPassword = storedPassword)
            }
        }
        if (config.security == DataOutputSecurity.Psk) loadPsk(profileId)
    }

    private fun onStoredChanged(config: DataOutputConfig) {
        setState { state ->
            if (state.stored == config) return@setState state
            // A change of the switch somewhere else changes only the switch of the draft.
            val draft = if (state.draft == state.stored) config else state.draft.copy(enabled = config.enabled)
            state.copy(stored = config, draft = draft)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The switch, the start and stop, and the diagnostics

    /**
     * The switch of the output. On: saves the draft with the switch on and asks for the local
     * network permission. Off: writes the switch at once.
     */
    override fun onEnabledChange(enabled: Boolean) {
        val id = _uiState.value.profileId ?: return
        if (!enabled) {
            setState { state -> state.copy(draft = state.draft.copy(enabled = false)) }
            scope.launch {
                machines.updateProfile(id) { profile -> profile.copy(dataOutput = profile.dataOutput.copy(enabled = false)) }
            }
            return
        }
        val state = _uiState.value
        if (state.problems.isNotEmpty()) {
            showMessage(UiText.of(Res.string.dataoutput_correct_first))
            return
        }
        val config = state.draft.copy(enabled = true)
        save(id, config) { saved -> OutputRunRule.localNetworkRequest(saved)?.let(requests::send) }
    }

    /** The start and stop button with the activation "On demand". */
    override fun onStartStop() {
        val state = _uiState.value
        val id = state.profileId ?: return
        if (!state.status.canStartStop) return
        if (state.status.running) {
            runner.requestStop(id)
        } else {
            OutputRunRule.localNetworkRequest(state.stored)?.let(requests::send)
            runner.requestStart(id)
        }
    }

    /**
     * Opens the diagnostics in the status section of the screen. They update
     * each second while they show. [onDismissDialog] closes them.
     */
    override fun onShowDiagnostics() {
        setState { state -> state.copy(dialog = DataOutputDialog.Diagnostics) }
        diagnosticsJob?.cancel()
        diagnosticsJob = scope.launch {
            while (isActive) {
                setState { state -> state.copy(diagnostics = diagnosticsOf(state)) }
                delay(DIAGNOSTICS_PERIOD_MS)
            }
        }
    }

    /**
     * "Stop the output" of the diagnostics. "On demand" stops the output. "Always on" runs
     * while the machine is online, so the switch goes off.
     */
    override fun onStopFromDiagnostics() {
        val state = _uiState.value
        val id = state.profileId ?: return
        onDismissDialog()
        if (state.stored.activation == DataOutputActivation.AlwaysOn) onEnabledChange(false) else runner.requestStop(id)
    }

    // ---------------------------------------------------------------------------------------
    // The edits of the draft

    /** Selects the network interface. "All interfaces" shows its warning text. */
    override fun onInterface(bind: BindInterface) = edit { config -> config.copy(bindInterface = bind) }

    /** The text of the port. */
    override fun onPortText(text: String) {
        val port = DataOutputForm.parseInt(text)
        setState { state -> state.copy(portText = text, draft = if (port != null) state.draft.copy(port = port) else state.draft) }
    }

    /** Selects the security mode. The plain text mode shows the warning first. */
    override fun onSecurity(mode: DataOutputSecurity) {
        val (config, dialog) = DataOutputForm.selectSecurity(_uiState.value.draft, mode)
        setState { state -> state.copy(draft = config, dialog = dialog ?: state.dialog) }
        val id = _uiState.value.profileId
        if (mode == DataOutputSecurity.Psk && id != null && _uiState.value.psk == null) scope.launch { loadPsk(id) }
    }

    /** The user confirmed the warning "Use only on trusted networks" of the open dialog. */
    override fun onConfirmWarning() {
        when (_uiState.value.dialog) {
            DataOutputDialog.PlainTextWarning -> setState { state ->
                state.copy(draft = DataOutputForm.confirmPlainText(state.draft), dialog = null)
            }
            DataOutputDialog.MqttNoTlsWarning -> setState { state ->
                val draft = DataOutputForm.confirmNoTls(state.draft)
                state.copy(draft = draft, brokerPortText = draft.mqttBrokerPort.toString(), dialog = null)
            }
            else -> return
        }
    }

    /** Closes the open dialog or the diagnostics. A warning that the user does not confirm keeps the old mode. */
    override fun onDismissDialog() {
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        setState { state -> state.copy(dialog = null, diagnostics = null) }
    }

    /** Selects the transport. */
    override fun onTransport(transport: DataOutputTransport) = edit { config -> config.copy(transport = transport) }

    /** Selects the activation. "Always on" needs the switch on. */
    override fun onActivation(activation: DataOutputActivation) {
        if (activation == DataOutputActivation.AlwaysOn && !_uiState.value.draft.enabled) return
        edit { config -> config.copy(activation = activation) }
    }

    /** The broker host. */
    override fun onBrokerHost(host: String) = edit { config -> config.copy(mqttBrokerHost = host.trim()) }

    /** The text of the broker port. */
    override fun onBrokerPortText(text: String) {
        val port = DataOutputForm.parseInt(text)
        setState { state ->
            state.copy(brokerPortText = text, draft = if (port != null) state.draft.copy(mqttBrokerPort = port) else state.draft)
        }
    }

    /** TLS to the broker (the security rules). TLS off shows the warning first. */
    override fun onTls(tls: Boolean) {
        val (config, dialog) = DataOutputForm.selectTls(_uiState.value.draft, tls)
        setState { state ->
            state.copy(draft = config, brokerPortText = config.mqttBrokerPort.toString(), dialog = dialog ?: state.dialog)
        }
    }

    /** The MQTT user name (it stays in the `SecretStore`). */
    override fun onUsername(username: String) {
        setState { state -> state.copy(mqttUsername = username, credentialsChanged = username != storedUsername || state.mqttPassword != storedPassword) }
    }

    /** The MQTT password. */
    override fun onPassword(password: String) {
        setState { state -> state.copy(mqttPassword = password, credentialsChanged = state.mqttUsername != storedUsername || password != storedPassword) }
    }

    /** The MQTT client ID. An empty text gives `superdro-<id>`. */
    override fun onClientId(clientId: String) = edit { config -> config.copy(mqttClientId = clientId.trim()) }

    /** The machine part of the topic. An empty text gives the profile name. */
    override fun onMachineName(name: String) = edit { config -> config.copy(mqttMachineName = name.takeIf { text -> text.isNotEmpty() }) }

    /** The topic layout. */
    override fun onTopicLayout(layout: MqttTopicLayout) = edit { config -> config.copy(mqttTopicLayout = layout) }

    /** The record format. */
    override fun onFormat(format: DataOutputFormat) = edit { config -> config.copy(format = format) }

    /** The delimiter of the Delimited format. */
    override fun onDelimiter(delimiter: DataOutputDelimiter) = edit { config -> config.copy(delimiter = delimiter) }

    /** The field labels in the Delimited format (`label=value`). */
    override fun onLabels(labels: Boolean) = edit { config -> config.copy(labels = labels) }

    /** The time stamp switch. */
    override fun onTimestamp(enabled: Boolean) = edit { config -> config.copy(timestampEnabled = enabled) }

    /** The time stamp format. */
    override fun onTimestampFormat(format: TimestampFormat) = edit { config -> config.copy(timestampFormat = format) }

    /** The unit of the lengths. */
    override fun onDistanceUnit(unit: DistanceUnit) = edit { config -> config.copy(distanceUnit = unit) }

    /** The unit of the angles. */
    override fun onAngleUnit(unit: AngleUnit) = edit { config -> config.copy(angleUnit = unit) }

    /** The decimals: 0 to 6. */
    override fun onPrecision(precision: Int) = edit { config -> config.copy(precision = precision) }

    /** The cadence. */
    override fun onCadence(cadence: DataOutputCadence) = edit { config -> config.copy(cadence = cadence) }

    /** The text of the interval. */
    override fun onIntervalText(text: String) {
        val interval = DataOutputForm.parseLong(text)
        setState { state ->
            state.copy(intervalText = text, draft = if (interval != null) state.draft.copy(intervalMs = interval) else state.draft)
        }
    }

    /** The text of the rate limit. */
    override fun onMaxRateText(text: String) {
        val rate = DataOutputForm.parseInt(text)
        setState { state -> state.copy(maxRateText = text, draft = if (rate != null) state.draft.copy(maxRateHz = rate) else state.draft) }
    }

    /** The switch of a field. */
    override fun onField(field: DataOutputFieldId, enabled: Boolean) = edit { config -> DataOutputForm.setField(config, field, enabled) }

    /** The label of a field. A refused character does not go into the label. */
    override fun onFieldLabel(field: DataOutputFieldId, label: String) {
        when (val result = DataOutputForm.editLabel(_uiState.value.draft, field, label)) {
            is LabelEdit.Accepted -> setState { state -> state.copy(draft = result.config, labelRefusals = state.labelRefusals - field) }
            is LabelEdit.Refused -> setState { state ->
                state.copy(
                    labelRefusals = state.labelRefusals +
                        (field to UiText.of(Res.string.data_output_label_character, DataOutputTexts.characterText(result.character))),
                )
            }
            LabelEdit.TooLong -> setState { state ->
                state.copy(labelRefusals = state.labelRefusals + (field to UiText.of(Res.string.dataoutput_label_too_long)))
            }
        }
    }

    /** Discards the changes of the draft. */
    override fun onDiscard() {
        setState { state ->
            val config = state.stored
            state.copy(
                draft = config,
                portText = config.port.toString(),
                intervalText = config.intervalMs.toString(),
                maxRateText = config.maxRateHz.toString(),
                brokerPortText = config.mqttBrokerPort.toString(),
                mqttUsername = storedUsername,
                mqttPassword = storedPassword,
                credentialsChanged = false,
                labelRefusals = emptyMap(),
            )
        }
    }

    /** "Save": writes the draft and the credentials. */
    override fun onSave() {
        val state = _uiState.value
        val id = state.profileId ?: return
        if (state.problems.isNotEmpty()) {
            showMessage(UiText.of(Res.string.dataoutput_correct_first))
            return
        }
        save(id, state.draft) { }
    }

    private fun save(id: String, config: DataOutputConfig, after: (DataOutputConfig) -> Unit) {
        setState { state -> state.copy(saving = true) }
        scope.launch {
            val state = _uiState.value
            try {
                if (state.credentialsChanged) {
                    runner.setMqttCredentials(id, state.mqttUsername.trim(), state.mqttPassword.takeIf { text -> text.isNotEmpty() }?.encodeToByteArray())
                    storedUsername = state.mqttUsername.trim()
                    storedPassword = state.mqttPassword
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                setState { current -> current.copy(saving = false) }
                showMessage(UiText.of(Res.string.dataoutput_secret_error))
                return@launch
            }
            val result = machines.updateProfile(id) { profile -> profile.copy(dataOutput = config) }
            if (result is StoreResult.Ok) {
                setState { current ->
                    current.copy(saving = false, draft = config, stored = config, credentialsChanged = false, mqttUsername = storedUsername)
                }
                showMessage(UiText.of(Res.string.dataoutput_saved))
                after(config)
            } else {
                setState { current -> current.copy(saving = false) }
                showMessage(UiText.of(Res.string.dataoutput_save_failed))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The PSK

    /** The user copied the share text: the snackbar tells it. */
    override fun onKeyCopied() = showMessage(UiText.of(Res.string.dataoutput_psk_copied))

    /** "Make a new key": the question first. */
    override fun onNewKey() {
        setState { state -> state.copy(dialog = DataOutputDialog.NewKey) }
    }

    /** The user confirmed "Make a new key". The old key stops. */
    override fun onConfirmNewKey() {
        val id = _uiState.value.profileId ?: return
        setState { state -> state.copy(dialog = null) }
        secrets {
            val key = runner.newPsk(id)
            setState { state -> if (state.profileId == id) state.copy(psk = key.shareText()) else state }
            showMessage(UiText.of(Res.string.dataoutput_psk_new_done))
        }
    }

    private suspend fun loadPsk(profileId: String) {
        secretsNow {
            val key = runner.psk(profileId, create = true)
            setState { state -> if (state.profileId == profileId) state.copy(psk = key?.shareText()) else state }
        }
    }

    // ---------------------------------------------------------------------------------------
    // "Test connection"

    /** Tests the draft: TCP binds and closes each address, MQTT connects to the broker. */
    override fun onTest() {
        val state = _uiState.value
        val id = state.profileId ?: return
        if (state.testing) return
        setState { current -> current.copy(testing = true, testResult = null) }
        scope.launch {
            val result = runner.testConnection(id, state.draft)
            setState { current ->
                current.copy(
                    testing = false,
                    testResult = result?.let { value -> TestResultUiState(DataOutputTexts.testResult(value), DataOutputTexts.testPassed(value)) },
                )
            }
        }
    }

    /** The snackbar showed the message [id]. */
    override fun onMessageShown(id: Long) {
        setState { state -> if (state.message?.id == id) state.copy(message = null) else state }
    }

    // ---------------------------------------------------------------------------------------

    private fun edit(transform: (DataOutputConfig) -> DataOutputConfig) {
        setState { state -> state.copy(draft = transform(state.draft)) }
    }

    private fun showMessage(text: UiText) {
        messageIds += 1
        val id = messageIds
        setState { state -> state.copy(message = OutputMessage(id, text)) }
    }

    private fun secrets(block: suspend () -> Unit) {
        scope.launch { secretsNow(block) }
    }

    private suspend fun secretsNow(block: suspend () -> Unit) {
        try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            showMessage(UiText.of(Res.string.dataoutput_secret_error))
        }
    }

    /**
     * Changes the UI state with [transform]. The same step calculates the derived values (the
     * problems and the status). Thus a reader never sees a stored configuration together with the
     * status of an older configuration. For example, [onStartStop] reads the status: it must not
     * see the switch on and an old status that cannot start the output.
     */
    private fun setState(transform: (DataOutputUiState) -> DataOutputUiState) {
        _uiState.update { state ->
            val next = transform(state)
            next.copy(problems = problemsOf(next), status = statusOf(next))
        }
    }

    /** Calculates the derived values again after a change of the output state. */
    private fun refresh() = setState { state -> state }

    private fun problemsOf(state: DataOutputUiState): List<FormProblem> {
        val id = state.profileId ?: return emptyList()
        val draft = state.draft
        val problems = mutableListOf<FormProblem>()
        val portParsed = DataOutputForm.parseInt(state.portText) != null
        val intervalParsed = DataOutputForm.parseLong(state.intervalText) != null
        val rateParsed = DataOutputForm.parseInt(state.maxRateText) != null
        val brokerPortParsed = DataOutputForm.parseInt(state.brokerPortText) != null
        if (!portParsed) problems += FormProblem(ProblemTarget.Port, UiText.of(Res.string.dataoutput_port_range))
        if (!intervalParsed) problems += FormProblem(ProblemTarget.Interval, UiText.of(Res.string.dataoutput_interval_range))
        if (!rateParsed) problems += FormProblem(ProblemTarget.MaxRate, UiText.of(Res.string.dataoutput_rate_range))
        if (draft.transport == DataOutputTransport.Mqtt && !brokerPortParsed) {
            problems += FormProblem(ProblemTarget.BrokerPort, UiText.of(Res.string.dataoutput_broker_port_range))
        }
        DataOutputValidation.problems(draft).map(DataOutputTexts::problem).forEach { problem ->
            val duplicate = (problem.target == ProblemTarget.Port && !portParsed) ||
                (problem.target == ProblemTarget.Interval && !intervalParsed) ||
                (problem.target == ProblemTarget.MaxRate && !rateParsed) ||
                (problem.target == ProblemTarget.BrokerPort && !brokerPortParsed)
            if (!duplicate) problems += problem
        }
        if (portParsed) {
            val others = profiles.filter { profile -> profile.id != id }.associate { profile -> profile.id to profile.dataOutput }
            DataOutputForm.portOwners(id, draft, others).firstOrNull()?.let { owner ->
                val name = profiles.firstOrNull { profile -> profile.id == owner }?.name ?: owner
                problems += FormProblem(ProblemTarget.Port, UiText.of(Res.string.dataoutput_port_conflict, name, draft.port))
            }
        }
        return problems
    }

    private fun statusOf(state: DataOutputUiState): OutputStatusUiState {
        val stored = state.stored
        val output = runtime
        val running = output?.running == true
        val transport = output?.transport ?: stored.transport
        val addresses = when {
            transport == DataOutputTransport.Mqtt -> listOf(hostAndPort(stored.mqttBrokerHost.ifEmpty { "?" }, stored.mqttBrokerPort))
            running && output.listening.isNotEmpty() -> output.listening
            else -> BindAddresses.hosts(interfaces, stored.bindInterface).map { host -> hostAndPort(host, stored.port) }
        }
        val statistics = output?.statistics
        val error = statistics?.lastError
        return OutputStatusUiState(
            status = output?.status ?: DataOutputStatus.Stopped,
            running = running,
            enabled = stored.enabled,
            blockText = DataOutputTexts.block(output?.block ?: if (stored.enabled) null else org.superdro.core.dataoutput.StartBlock.NotEnabled),
            transport = transport,
            addresses = addresses,
            port = stored.port,
            security = stored.security,
            brokerTls = stored.mqttTls,
            clients = output?.clients?.size ?: 0,
            records = statistics?.records,
            sent = statistics?.sent,
            failed = statistics?.failed,
            lastError = error?.let { value -> DataOutputTexts.error(value, transport == DataOutputTransport.Mqtt) },
            activation = stored.activation,
            canStartStop = stored.enabled && stored.activation == DataOutputActivation.OnDemand,
        )
    }

    private fun diagnosticsOf(state: DataOutputUiState): DiagnosticsUiState? {
        val output = runtime ?: return null
        val diagnostics = Diagnostics.of(output, runner.nowMs())
        return diagnosticsUiState(diagnostics, output.status == DataOutputStatus.Warning)
    }

    companion object {
        /** The update period of the diagnostics. */
        const val DIAGNOSTICS_PERIOD_MS: Long = 1000L

        /** Gives the lines of the diagnostics of [diagnostics]. */
        fun diagnosticsUiState(diagnostics: Diagnostics, warning: Boolean): DiagnosticsUiState {
            val mqtt = diagnostics.transport == DataOutputTransport.Mqtt
            return DiagnosticsUiState(
                running = diagnostics.running,
                listening = diagnostics.listening.joinToString(", ").takeIf { diagnostics.running && !mqtt },
                clients = diagnostics.clientCount,
                broker = diagnostics.broker?.takeIf { diagnostics.running },
                problem = diagnostics.problem?.let { error ->
                    UiText.of(Res.string.data_output_problem, DataOutputTexts.error(error, mqtt), DataOutputTexts.age(diagnostics.problemAgeMs ?: 0L))
                },
                delivery = diagnostics.lastSuccessAgeMs?.let { age -> UiText.of(Res.string.data_output_last_success, DataOutputTexts.age(age)) }
                    ?: UiText.of(Res.string.data_output_no_delivery),
                counts = if (diagnostics.showCounts) {
                    UiText.of(Res.string.data_output_counts, diagnostics.sent.toInt(), diagnostics.failed.toInt(), diagnostics.recentFailurePercent)
                } else {
                    null
                },
                warning = warning,
            )
        }
    }
}

/** The broker text of the diagnostics, for a screen that shows it. */
internal fun brokerText(broker: String): UiText = UiText.of(Res.string.data_output_broker, broker)
