package org.superdro.core.dataoutput

import kotlin.concurrent.Volatile
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.util.Clock
import org.superdro.core.util.MonotonicClock
import org.superdro.core.util.SecretStore

private val log = Arbor.tag("DataOutput")

/**
 * The parts of the platform that the data output uses. The app makes one object for all outputs.
 *
 * @property networkInterfaces The interfaces and their addresses.
 * @property secretStore The encrypted store of the PSK and the MQTT credentials.
 * @property listenSockets The TCP sockets (production: [KtorListenSocketFactory]).
 * @property mqttConnector The MQTT client (production: [defaultMqttConnector]).
 * @property pskSecurity Makes the PSK mode of a key (production: [pskStreamSecurity]).
 * @property ports The ports of the outputs of this process.
 * @property secretsDispatcher The dispatcher of the [secretStore] calls.
 * @property retryDelayMs The time from a failure to a new server or broker link (5 s).
 */
class DataOutputPlatform(
    val networkInterfaces: NetworkInterfaces,
    val secretStore: SecretStore,
    val listenSockets: ListenSocketFactory,
    val mqttConnector: MqttConnector = defaultMqttConnector(),
    val pskSecurity: (PskKey) -> StreamSecurity = ::pskStreamSecurity,
    val ports: PortRegistry = PortRegistry(),
    val clock: Clock = MonotonicClock(),
    val wallClock: WallClock = WallClock.System,
    val secretsDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val retryDelayMs: Long = TcpOutputServer.RETRY_DELAY_MS,
)

/**
 * The data output of one machine profile. The connection service
 * makes one controller for each machine profile, while the process lives. The session
 * code gives the values of the display in [values].
 *
 * The rules ([OutputRunRule]):
 * - The output runs only when the user turned it on and its machine is online. No server socket
 *   is open in each other case.
 * - A change of the switch to off stops the output, and closes the server and all clients at
 *   once.
 * - "On demand" (the default) runs after [requestStart] (the status chip). "Always on" runs
 *   while the machine is online.
 * - The plain text mode runs only after the confirmation of the warning.
 * - A change of the configuration while the output runs starts it again with the new values.
 *
 * Call the functions on one thread (the main thread). The state is in [state].
 */
class DataOutputController(
    private val profileId: String,
    parentScope: CoroutineScope,
    private val values: StateFlow<OutputValues>,
    private val platform: DataOutputPlatform,
    config: DataOutputConfig = DataOutputConfig(),
    profileName: String = "",
) {
    /** The scope of this output: a child of the scope of the caller. [close] cancels only this scope. */
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))
    private var config: DataOutputConfig = config
    private var profileName: String = profileName
    private var machineOnline = false
    private var requested = false

    /** The running session. A change of the state from an old session has no effect. */
    @Volatile
    private var session: Session? = null
    private var lastStatistics: OutputStatistics? = null

    /** The secrets of this output. */
    val secrets: DataOutputSecrets = DataOutputSecrets(platform.secretStore, profileId)

    private val _state = MutableStateFlow(idleState(OutputRunRule.block(config, machineOnline = false, requested = false)))

    /** The state of the output. */
    val state: StateFlow<DataOutputState> = _state.asStateFlow()

    /** The configuration in use. */
    val currentConfig: DataOutputConfig get() = config

    /**
     * Sets the configuration of the profile. The switch off stops the output at once. A
     * different configuration starts the output again when it runs.
     */
    fun setConfig(config: DataOutputConfig, profileName: String = this.profileName) {
        val changed = config != this.config || profileName != this.profileName
        this.config = config
        this.profileName = profileName
        if (!config.enabled) requested = false
        if (changed) stopSession()
        evaluate()
    }

    /** Sets the online state of the machine. An offline machine stops the output. */
    fun setMachineOnline(online: Boolean) {
        machineOnline = online
        evaluate()
    }

    /**
     * Starts the output with the activation "On demand" (a tap on the status chip). The
     * request stays until [requestStop] or until the user turns off the output.
     */
    fun requestStart() {
        requested = true
        evaluate()
    }

    /** Stops the output with the activation "On demand". */
    fun requestStop() {
        requested = false
        evaluate()
    }

    /** Does what a tap on the status chip does: a start when the output does not run, else a stop. */
    fun toggle() {
        if (session != null) requestStop() else requestStart()
    }

    /** Stops the output and its coroutines. The controller cannot start again. The scope of the caller continues. */
    fun close() {
        stopSession()
        scope.cancel()
    }

    /**
     * "Test connection" with [config], which the user
     * does not have to save first. TCP: binds and closes each selected address. MQTT: connects to
     * the broker and disconnects.
     */
    suspend fun testConnection(config: DataOutputConfig = this.config): TestConnectionResult {
        val problems = DataOutputValidation.problems(config)
        if (problems.isNotEmpty()) return TestConnectionResult.InvalidConfig(problems)
        return when (config.transport) {
            DataOutputTransport.Tcp -> testPort(config)
            DataOutputTransport.Mqtt -> testBroker(config)
        }
    }

    private suspend fun testPort(config: DataOutputConfig): TestConnectionResult {
        val running = session
        val runningState = _state.value
        if (running != null && running.config.transport == DataOutputTransport.Tcp && running.config.port == config.port) {
            return TestConnectionResult.Running(config.port, runningState.listening, runningState.clients.size)
        }
        platform.ports.owners.value[config.port]?.takeIf { owner -> owner != profileId }?.let { owner ->
            return TestConnectionResult.PortTaken(config.port, owner)
        }
        val hosts = BindAddresses.hosts(platform.networkInterfaces.interfaces.value, config.bindInterface)
        if (hosts.isEmpty()) return TestConnectionResult.NoAddress(config.bindInterface)
        for (host in hosts) {
            try {
                platform.listenSockets.bind(host, config.port).close()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                return TestConnectionResult.PortNotFree(config.port, host, exception.message)
            }
        }
        return TestConnectionResult.PortFree(config.port, hosts.map { host -> hostAndPort(host, config.port) })
    }

    private suspend fun testBroker(config: DataOutputConfig): TestConnectionResult {
        val params = brokerParams(config)
        return try {
            platform.mqttConnector.connect(params).close()
            TestConnectionResult.BrokerReachable(params.host, params.port, params.tls)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: MqttFailure) {
            TestConnectionResult.BrokerFailed(DataOutputError(exception.type, platform.clock.nowMs(), params.host, params.port, exception.message))
        } catch (exception: Exception) {
            TestConnectionResult.BrokerFailed(
                DataOutputError(DataOutputErrorType.BrokerUnreachable, platform.clock.nowMs(), params.host, params.port, exception.message),
            )
        }
    }

    private suspend fun brokerParams(config: DataOutputConfig): MqttConnectParams {
        val credentials = withContext(platform.secretsDispatcher) { secrets.mqttCredentials() }
        val clientId = config.mqttClientId.ifEmpty { "superdro-" + profileId.filter { char -> char.isLetterOrDigit() }.take(8) }
        return MqttConnectParams(config.mqttBrokerHost, config.mqttBrokerPort, config.mqttTls, clientId, credentials)
    }

    private fun evaluate() {
        val block = OutputRunRule.block(config, machineOnline, requested)
        if (block != null) {
            stopSession()
            _state.value = idleState(block)
            return
        }
        if (session != null) return
        val owner = platform.ports.takeIf { config.transport == DataOutputTransport.Tcp }?.claim(config.port, profileId)
        if (owner != null) {
            _state.value = idleState(StartBlock.PortTaken(config.port, owner))
            return
        }
        session = Session(config, profileName).also { started -> started.start() }
    }

    private fun stopSession() {
        val running = session ?: return
        session = null
        running.stop()
        lastStatistics = running.statistics.value
        _state.value = idleState(OutputRunRule.block(config, machineOnline, requested))
    }

    private fun idleState(block: StartBlock?): DataOutputState = DataOutputState(
        transport = config.transport,
        security = config.security,
        bindInterface = config.bindInterface,
        port = config.port,
        brokerHost = config.mqttBrokerHost,
        brokerPort = config.mqttBrokerPort,
        brokerTls = config.mqttTls,
        running = false,
        block = block,
        statistics = lastStatistics,
        bindWarning = BindAddresses.needsWarning(config.bindInterface),
    )

    /** One start of the output: the sender, the cadence, and the statistics. */
    private inner class Session(val config: DataOutputConfig, val profileName: String) {
        private val job = SupervisorJob(scope.coroutineContext.job)
        private val sessionScope = CoroutineScope(scope.coroutineContext + job)
        val statistics = MutableStateFlow(OutputStatistics(platform.clock.nowMs()))
        private var sequence = 0L

        // The session coroutine sets the sender. stop() reads it on the caller thread.
        @Volatile
        private var tcp: TcpOutputServer? = null

        @Volatile
        private var mqtt: MqttOutput? = null
        private val senderState = MutableStateFlow<SenderState>(SenderState.Starting)
        private val listeningAddresses = MutableStateFlow<List<String>>(emptyList())
        private val clientAddresses = MutableStateFlow<List<String>>(emptyList())

        fun start() {
            val format = if (config.transport == DataOutputTransport.Mqtt) DataOutputFormat.Json else config.format
            val formatter = RecordFormatter.create(config, format)
            publishState()
            sessionScope.launch {
                when (config.transport) {
                    DataOutputTransport.Tcp -> startTcp()
                    DataOutputTransport.Mqtt -> startMqtt(formatter)
                }
                launch {
                    RecordScheduler(config.cadence, config.intervalMs, config.maxRateHz, platform.clock)
                        .run(values, formatter::valueTexts) { current -> send(formatter, current) }
                }
            }
        }

        fun stop() {
            tcp?.stop()
            mqtt?.stop()
            job.cancel()
            if (config.transport == DataOutputTransport.Tcp) platform.ports.release(config.port, profileId)
        }

        private suspend fun startTcp() {
            val security = when (config.security) {
                DataOutputSecurity.Psk -> {
                    val key = withContext(platform.secretsDispatcher) { secrets.pskOrCreate() }
                    platform.pskSecurity(key)
                }
                DataOutputSecurity.PlainText -> PlainTextSecurity
            }
            val server = TcpOutputServer(
                scope = sessionScope,
                sockets = platform.listenSockets,
                security = security,
                bindHosts = { BindAddresses.hosts(platform.networkInterfaces.interfaces.value, config.bindInterface) },
                port = config.port,
                clock = platform.clock,
                onError = ::onError,
                onWrite = ::onWrite,
                retryDelayMs = platform.retryDelayMs,
            )
            tcp = server
            server.start()
            sessionScope.launch {
                combine(server.state, server.listening, server.clients) { sender, listening, clients -> Triple(sender, listening, clients) }
                    .collect { (sender, listening, clients) ->
                        senderState.value = sender
                        listeningAddresses.value = listening
                        clientAddresses.value = clients
                    }
            }
            // A change of the selected addresses starts a new server on the new addresses.
            sessionScope.launch {
                platform.networkInterfaces.interfaces
                    .map { interfaces -> BindAddresses.hosts(interfaces, config.bindInterface) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { server.restart() }
            }
        }

        private suspend fun startMqtt(formatter: RecordFormatter) {
            val params = brokerParams(config)
            val output = MqttOutput(
                scope = sessionScope,
                connector = platform.mqttConnector,
                params = params,
                layout = config.mqttTopicLayout,
                baseTopic = MqttTopics.base(config.mqttMachineName ?: profileName.ifEmpty { profileId }),
                formatter = formatter,
                timestampEnabled = config.timestampEnabled,
                clock = platform.clock,
                onError = ::onError,
                onWrite = ::onWrite,
                retryDelayMs = platform.retryDelayMs,
            )
            mqtt = output
            output.start()
            sessionScope.launch { output.state.collect { sender -> senderState.value = sender } }
        }

        private fun send(formatter: RecordFormatter, current: OutputValues) {
            val number = sequence++
            val timestamp = current.updatedAtEpochMs ?: platform.wallClock.nowEpochMs()
            val texts = formatter.valueTexts(current)
            statistics.update { value -> value.withRecord() }
            tcp?.publish(formatter.record(texts, timestamp, number).encodeToByteArray())
            mqtt?.publish(OutputRecord(texts, timestamp, number))
        }

        private fun onError(error: DataOutputError) {
            log.w("Output $profileId: ${error.logText()}")
            statistics.update { value -> value.withError(error) }
        }

        private fun onWrite(success: Boolean) {
            val now = platform.clock.nowMs()
            statistics.update { value -> value.withWrite(success, now) }
        }

        private fun publishState() {
            val ticks = flow {
                while (true) {
                    emit(Unit)
                    delay(WarningRules.STATUS_PERIOD_MS)
                }
            }
            sessionScope.launch {
                combine(senderState, listeningAddresses, clientAddresses, statistics, ticks) { sender, listening, clients, stats, _ ->
                    DataOutputState(
                        transport = config.transport,
                        security = config.security,
                        bindInterface = config.bindInterface,
                        port = config.port,
                        brokerHost = config.mqttBrokerHost,
                        brokerPort = config.mqttBrokerPort,
                        brokerTls = config.mqttTls,
                        running = true,
                        block = null,
                        sender = sender,
                        status = WarningRules.status(sender, stats, config.cadence, config.intervalMs, platform.clock.nowMs()),
                        listening = listening,
                        clients = clients,
                        statistics = stats,
                        bindWarning = BindAddresses.needsWarning(config.bindInterface),
                    )
                }.collect { value -> _state.update { current -> if (session === this@Session) value else current } }
            }
        }
    }
}
