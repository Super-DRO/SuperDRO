package org.superdro.core.ui.dataoutput

import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.dataoutput.DataOutputController
import org.superdro.core.dataoutput.DataOutputPlatform
import org.superdro.core.dataoutput.DataOutputSecrets
import org.superdro.core.dataoutput.DataOutputState
import org.superdro.core.dataoutput.MqttCredentials
import org.superdro.core.dataoutput.OutputTime
import org.superdro.core.dataoutput.OutputValues
import org.superdro.core.dataoutput.PskKey
import org.superdro.core.dataoutput.TestConnectionResult
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.MachineProfile
import org.superdro.core.readout.FeedValues
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.session.Connections

private val log = Arbor.tag("DataOutputRunner")

/**
 * Runs the data output of each machine profile while the process lives. The app has
 * one runner, one [DataOutputPlatform] with the real platform parts, and one `PortRegistry` in
 * the platform.
 *
 * - One [DataOutputController] for each machine profile. A change of the stored configuration
 *   calls `setConfig`. A deleted profile closes its output and deletes its secrets
 *   (`data_output.<profileId>.psk`, `.mqtt_username`, `.mqtt_password`).
 * - The session state of the machine calls `setMachineOnline`. Thus an output runs only while
 *   its machine is online. The connection service keeps the links of the online machines, so
 *   the outputs run in the service.
 * - While an output runs, the runner fills its [OutputValues] from the readout model of the
 *   display ([OutputValueSource]): the frame of the session values, the feed rate of the
 *   2000 ms window (a new calculation each [tickMs] while a feed rate field is on, so that the
 *   rate goes to 0 at rest), the probe ports `p` and `q`, and the last trigger of probe 1.
 *
 * All calls of a controller run on [dispatcher], which runs one task at a time (the rule of
 * [DataOutputController]). The functions of this class are safe on each thread.
 *
 * @param dispatcher A dispatcher that runs one task at a time. A test gives its test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataOutputRunner(
    private val machines: MachineRepository,
    private val connections: Connections,
    private val platform: DataOutputPlatform,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val tickMs: Long = FEED_TICK_MS,
) {
    private class ProfileOutput(
        val profileId: String,
        val controller: DataOutputController,
        val values: MutableStateFlow<OutputValues>,
        val job: Job,
    ) {
        var online: Boolean = false
        var config: DataOutputConfig = DataOutputConfig()
    }

    private val _outputs = MutableStateFlow<Map<String, ProfileOutput>>(emptyMap())
    private var scope: CoroutineScope? = null

    /** The IDs of the profiles that have an output. */
    val profileIds: StateFlow<Set<String>> get() = _profileIds.asStateFlow()
    private val _profileIds = MutableStateFlow<Set<String>>(emptySet())

    /** Starts the runner in a child scope of [parent]. A second call does nothing. */
    fun start(parent: CoroutineScope) {
        if (scope != null) return
        val runnerScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job) + dispatcher)
        scope = runnerScope
        runnerScope.launch { machines.profiles().collect { profiles -> reconcile(runnerScope, profiles) } }
    }

    /** Stops each output and the runner. */
    fun stop() {
        val running = scope ?: return
        scope = null
        running.launch(dispatcher) {
            _outputs.value.values.forEach { output -> output.controller.close() }
            _outputs.value = emptyMap()
            _profileIds.value = emptySet()
        }.invokeOnCompletion { running.coroutineContext.job.cancel() }
    }

    /** The state of the output of [profileId], or null before the runner knows the profile. */
    fun state(profileId: String): Flow<DataOutputState?> =
        _outputs.map { outputs -> outputs[profileId] }
            .distinctUntilChanged()
            .flatMapLatest { output -> output?.controller?.state ?: flowOf(null) }

    /** The values of the output of [profileId], or null. The tests read them. */
    internal fun valuesOf(profileId: String): StateFlow<OutputValues>? = _outputs.value[profileId]?.values

    /** Does what a tap on the start and stop button does ("On demand"). */
    fun toggle(profileId: String) = onOutput(profileId) { output -> output.controller.toggle() }

    /** Starts the output with the activation "On demand". */
    fun requestStart(profileId: String) = onOutput(profileId) { output -> output.controller.requestStart() }

    /** Stops the output with the activation "On demand". */
    fun requestStop(profileId: String) = onOutput(profileId) { output -> output.controller.requestStop() }

    /** "Test connection" with [config], which the user does not have to save first. */
    suspend fun testConnection(profileId: String, config: DataOutputConfig): TestConnectionResult? {
        val output = _outputs.value[profileId] ?: return null
        return withContext(dispatcher) { output.controller.testConnection(config) }
    }

    /** Gives the PSK of [profileId]. [create] makes a key when the store has none. */
    suspend fun psk(profileId: String, create: Boolean): PskKey? = withContext(platform.secretsDispatcher) {
        val secrets = secretsOf(profileId)
        if (create) secrets.pskOrCreate() else secrets.psk()
    }

    /**
     * Makes a new PSK for [profileId] ("Make a new key"). The old key stops: an output
     * that runs starts again with the new key, so the clients with the old key go away.
     */
    suspend fun newPsk(profileId: String): PskKey {
        val key = withContext(platform.secretsDispatcher) { secretsOf(profileId).replacePsk() }
        restart(profileId)
        return key
    }

    /** Gives the stored MQTT credentials of [profileId], or null. */
    suspend fun mqttCredentials(profileId: String): MqttCredentials? =
        withContext(platform.secretsDispatcher) { secretsOf(profileId).mqttCredentials() }

    /**
     * Keeps the MQTT credentials of [profileId] in the `SecretStore`. An MQTT output that
     * runs starts again with the new credentials.
     */
    suspend fun setMqttCredentials(profileId: String, username: String, password: ByteArray?) {
        withContext(platform.secretsDispatcher) { secretsOf(profileId).setMqttCredentials(username, password) }
        val output = _outputs.value[profileId] ?: return
        if (output.config.transport == DataOutputTransport.Mqtt) restart(profileId)
    }

    /** The monotonic time of the platform clock, for the ages of the diagnostics. */
    fun nowMs(): Long = platform.clock.nowMs()

    private fun secretsOf(profileId: String): DataOutputSecrets =
        _outputs.value[profileId]?.controller?.secrets ?: DataOutputSecrets(platform.secretStore, profileId)

    /** Starts the output again when it runs: an offline and online step keeps the start request. */
    private suspend fun restart(profileId: String) {
        withContext(dispatcher) {
            val output = _outputs.value[profileId] ?: return@withContext
            if (!output.controller.state.value.running) return@withContext
            output.controller.setMachineOnline(false)
            output.controller.setMachineOnline(output.online)
        }
    }

    private fun onOutput(profileId: String, action: (ProfileOutput) -> Unit) {
        val running = scope ?: return
        running.launch { _outputs.value[profileId]?.let(action) }
    }

    private suspend fun reconcile(runnerScope: CoroutineScope, profiles: List<MachineProfile>) {
        val current = _outputs.value
        val next = LinkedHashMap<String, ProfileOutput>()
        profiles.forEach { profile ->
            val existing = current[profile.id]
            val output = existing ?: create(runnerScope, profile)
            output.config = profile.dataOutput
            if (existing != null) output.controller.setConfig(profile.dataOutput, profile.name)
            next[profile.id] = output
        }
        val removed = current.keys - next.keys
        _outputs.value = next
        _profileIds.value = next.keys.toSet()
        removed.forEach { id ->
            val output = current.getValue(id)
            output.job.cancel()
            output.controller.close()
            try {
                withContext(platform.secretsDispatcher) { output.controller.secrets.deleteAll() }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log.w(exception) { "The secrets of the deleted profile $id stay in the store" }
            }
        }
    }

    private fun create(runnerScope: CoroutineScope, profile: MachineProfile): ProfileOutput {
        val values = MutableStateFlow(OutputValues())
        val controller = DataOutputController(profile.id, runnerScope, values, platform, profile.dataOutput, profile.name)
        val job = SupervisorJob(runnerScope.coroutineContext.job)
        val output = ProfileOutput(profile.id, controller, values, job)
        output.config = profile.dataOutput
        val outputScope = CoroutineScope(runnerScope.coroutineContext + job)
        val session = session(profile.id)
        outputScope.launch {
            session.flatMapLatest { current -> current?.state?.map { state -> state.isOnline } ?: flowOf(false) }
                .distinctUntilChanged()
                .collect { online ->
                    output.online = online
                    controller.setMachineOnline(online)
                }
        }
        outputScope.launch {
            controller.state.map { state -> state.running }.distinctUntilChanged().collectLatest { running ->
                if (running) pumpValues(output, session) else values.value = OutputValues()
            }
        }
        return output
    }

    private fun session(profileId: String): Flow<DeviceSession?> =
        connections.sessions.map { sessions -> sessions[DeviceId(profileId)] }.distinctUntilChanged()

    /**
     * Fills the values of [output] while it runs. A new start makes the trigger count 0 (the
     * number of triggers since the start of the output).
     */
    private suspend fun pumpValues(output: ProfileOutput, session: Flow<DeviceSession?>) = coroutineScope {
        val profileId = output.profileId
        val snapshot = session.flatMapLatest { current -> current?.inputs ?: flowOf(InputSnapshot.EMPTY) }
        val frames = combine(
            machines.profile(profileId).filterNotNull(),
            machines.activeWorkspace(profileId).filterNotNull(),
            snapshot,
        ) { profile, workspace, inputs -> FrameInputs(DisplayFrames.of(profile, workspace, inputs), inputs) }
        val latest = MutableStateFlow<FrameInputs?>(null)
        launch { frames.collect { value -> latest.value = value } }

        val trigger = MutableStateFlow<ProbeTrigger?>(null)
        launch {
            var count = 0L
            session.flatMapLatest { current -> current?.probeEvents?.map { event -> current to event } ?: emptyFlow() }
                .filter { (_, event) -> event.port == Channels.P }
                .collect { (current, event) ->
                    val frame = latest.value?.frame ?: return@collect
                    count += 1
                    val directions = current.readHistory { history ->
                        TRIGGER_AXES.associateWith { axis -> history.axisDirection(axis, event.timeMs) }
                    }
                    val wall = OutputTime.wallClockTime(event.timeMs, platform.wallClock.nowEpochMs(), platform.clock.nowMs())
                    trigger.value = OutputValueSource.trigger(frame, event, directions, count, wall)
                }
        }

        val ticks = flow {
            while (true) {
                emit(Unit)
                if (!needsTicks(output.config)) break
                delay(tickMs)
            }
        }
        var lastFields: OutputValues? = null
        var lastSampleMs: Long? = null
        var changedAtMs = platform.clock.nowMs()
        combine(latest.filterNotNull(), trigger, ticks, session) { inputs, probe, _, current -> Triple(inputs, probe, current) }
            .collect { (inputs, probe, current) ->
                val now = platform.clock.nowMs()
                val feeds = if (current != null && needsTicks(output.config)) {
                    current.readHistory { history -> FEED_AXES.associateWith { axis -> FeedValues.feedRate(history, axis, now) } }
                } else {
                    emptyMap()
                }
                val fields = OutputValueSource.values(inputs.frame, inputs.snapshot, feeds, probe, updatedAtEpochMs = null)
                val sampleMs = inputs.snapshot.channels.values.maxOfOrNull { channel -> channel.timeMs }
                if (fields != lastFields) {
                    // The time stamp is the time of the last value update: the time of a new
                    // sample, else the time of the change (a feed rate at rest, a trigger).
                    changedAtMs = if (sampleMs != null && sampleMs != lastSampleMs) sampleMs else now
                    lastFields = fields
                    output.values.value = fields.copy(
                        updatedAtEpochMs = OutputTime.wallClockTime(changedAtMs, platform.wallClock.nowEpochMs(), now),
                    )
                }
                lastSampleMs = sampleMs
            }
    }

    private class FrameInputs(val frame: ReadoutFrame, val snapshot: InputSnapshot)

    companion object {
        /** The period of the new calculation of the feed rates while a feed rate field is on. */
        const val FEED_TICK_MS: Long = 100L

        /** The axes of the feed rate fields. */
        val FEED_AXES: List<AxisId> = listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular)

        /** The axes of the trigger fields of probe 1. */
        private val TRIGGER_AXES: List<AxisId> = listOf(AxisId.X, AxisId.Y, AxisId.Z)

        /** The fields that need the feed rates. */
        val FEED_FIELDS: Set<DataOutputFieldId> = setOf(
            DataOutputFieldId.XFeedRate,
            DataOutputFieldId.YFeedRate,
            DataOutputFieldId.ZFeedRate,
            DataOutputFieldId.AngularFeedRate,
        )

        /** True when an enabled field of [config] needs the feed rates. */
        fun needsTicks(config: DataOutputConfig): Boolean =
            config.fields.any { field -> field.enabled && field.id in FEED_FIELDS }
    }
}
