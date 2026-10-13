package org.superdro.core.dataoutput

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.model.MqttTopicLayout
import org.superdro.core.util.Clock

/**
 * The values of one broker link.
 *
 * @property tls True for `ssl://<host>:<port>` with the trust store of the system and a check of
 *   the host name (the default). False for `tcp://<host>:<port>`, only after the warning.
 * @property keepAliveSeconds The MQTT keep alive time.
 * @property connectTimeoutSeconds The time for the connection and the CONNACK.
 */
data class MqttConnectParams(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val clientId: String,
    val credentials: MqttCredentials? = null,
    val keepAliveSeconds: Int = DEFAULT_KEEP_ALIVE_SECONDS,
    val connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
) {
    /** The server URI, for example `ssl://broker.local:8883`. */
    val serverUri: String get() = (if (tls) "ssl://" else "tcp://") + hostAndPort(host, port)

    companion object {
        const val DEFAULT_KEEP_ALIVE_SECONDS: Int = 30
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS: Int = 10
    }
}

/** One open link to the broker. */
interface MqttConnection {

    /** Publishes [payload] on [topic] with QoS 0 and no retain flag. */
    suspend fun publish(topic: String, payload: ByteArray)

    /** Waits until the link stops, and gives the cause. */
    suspend fun awaitLoss(): Throwable

    /** Disconnects and closes the link. */
    fun close()
}

/** Opens a link to a broker. The production connector uses Eclipse Paho ([defaultMqttConnector]). */
fun interface MqttConnector {

    /** @throws MqttFailure when the link does not open. */
    suspend fun connect(params: MqttConnectParams): MqttConnection
}

/** An MQTT failure with its [type]: [DataOutputErrorType.BrokerUnreachable], `AuthRejected`, `TlsFailure`, and so on. */
class MqttFailure(val type: DataOutputErrorType, message: String?, cause: Throwable? = null) : Exception(message, cause)

/** Gives the MQTT connector of the platform: Eclipse Paho MQTT v3.1.1. */
expect fun defaultMqttConnector(): MqttConnector

/**
 * The MQTT topics of an output. The base topic is `superdro/<machine>`.
 *
 * - [MqttTopicLayout.JsonRecord]: one JSON record (the JSON format, with no line feed) for each
 *   message on `superdro/<machine>`.
 * - [MqttTopicLayout.TopicPerField]: one message for each enabled field on
 *   `superdro/<machine>/<label>`, with the number text as the payload (an empty payload for a
 *   field with no value). When the time stamp is on, the time stamp text goes to
 *   `superdro/<machine>/timestamp`.
 */
object MqttTopics {

    /** The first level of each topic. */
    const val ROOT: String = "superdro"

    /**
     * Gives the base topic `superdro/<machine>`. [machine] is the MQTT machine name, else the
     * profile name. In a profile name, the characters `/`, `+`, `#`, and U+0000 change to `_`.
     */
    fun base(machine: String): String {
        val level = machine.map { char -> if (DataOutputValidation.topicRefusedCharacter(char.toString()) != null) '_' else char }
            .joinToString("")
            .ifEmpty { "_" }
        return "$ROOT/$level"
    }

    /** Gives the messages of one record as (topic, payload) pairs. */
    fun messages(
        layout: MqttTopicLayout,
        baseTopic: String,
        formatter: RecordFormatter,
        record: OutputRecord,
        timestampEnabled: Boolean,
    ): List<Pair<String, ByteArray>> = when (layout) {
        MqttTopicLayout.JsonRecord -> {
            val text = formatter.record(record.texts, record.timestampEpochMs, record.sequence).removeSuffix("\n")
            listOf(baseTopic to text.encodeToByteArray())
        }
        MqttTopicLayout.TopicPerField -> buildList {
            if (timestampEnabled) {
                add("$baseTopic/${RecordFormatter.TIMESTAMP_KEY}" to formatter.timestampText(record.timestampEpochMs).encodeToByteArray())
            }
            formatter.fields.forEachIndexed { index, field ->
                add("$baseTopic/${field.label}" to record.texts[index].orEmpty().encodeToByteArray())
            }
        }
    }
}

/**
 * One record of the output: the value texts of the enabled fields, the time stamp, and the
 * record number.
 */
class OutputRecord(val texts: List<String?>, val timestampEpochMs: Long, val sequence: Long)

/**
 * The MQTT client output. It connects to the broker, publishes each record, and
 * opens a new link [retryDelayMs] after a failure or a loss.
 */
class MqttOutput(
    private val scope: CoroutineScope,
    private val connector: MqttConnector,
    private val params: MqttConnectParams,
    private val layout: MqttTopicLayout,
    private val baseTopic: String,
    private val formatter: RecordFormatter,
    private val timestampEnabled: Boolean,
    private val clock: Clock,
    private val onError: (DataOutputError) -> Unit = {},
    private val onWrite: (Boolean) -> Unit = {},
    private val retryDelayMs: Long = TcpOutputServer.RETRY_DELAY_MS,
) {
    private val _state = MutableStateFlow<SenderState>(SenderState.Stopped)

    /** The state of the client. */
    val state: StateFlow<SenderState> = _state.asStateFlow()

    /** The current run, or null while the client is stopped. A late change of an old run has no effect. */
    @Volatile
    private var current: Run? = null

    /** Starts the client. A second call has no effect while it runs. */
    fun start() {
        if (current != null) return
        val run = Run()
        current = run
        _state.value = SenderState.Starting
        run.job = scope.launch { run.serve() }
    }

    /** Stops the client and closes the link at once. */
    fun stop() {
        val run = current ?: return
        current = null
        run.close()
        _state.value = SenderState.Stopped
    }

    /** Gives the newest record to the publisher. A record that did not go out yet goes away. */
    fun publish(record: OutputRecord) {
        current?.slot?.trySend(record)
    }

    /** One start of the client. */
    private inner class Run {
        var job: Job? = null
        val slot = Channel<OutputRecord>(Channel.CONFLATED)

        @Volatile
        private var connection: MqttConnection? = null

        @Volatile
        private var closed = false

        fun close() {
            closed = true
            job?.cancel()
            connection?.let { link -> runCatching { link.close() } }
            connection = null
        }

        private fun setState(state: SenderState) {
            _state.update { value -> if (current === this && !closed) state else value }
        }

        suspend fun serve() {
            while (true) {
                setState(SenderState.Starting)
                val link = try {
                    connector.connect(params)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: MqttFailure) {
                    fail(DataOutputError(exception.type, clock.nowMs(), params.host, params.port, exception.message))
                    continue
                } catch (exception: Exception) {
                    fail(DataOutputError(DataOutputErrorType.BrokerUnreachable, clock.nowMs(), params.host, params.port, exception.message))
                    continue
                }
                connection = link
                if (closed) {
                    runCatching { link.close() }
                    throw CancellationException("The client stopped.")
                }
                setState(SenderState.Active)
                val error = try {
                    publishUntilLoss(link)
                } finally {
                    runCatching { link.close() }
                    connection = null
                }
                fail(error)
            }
        }

        private suspend fun publishUntilLoss(link: MqttConnection): DataOutputError = coroutineScope {
            val publisher = launch {
                for (record in slot) {
                    MqttTopics.messages(layout, baseTopic, formatter, record, timestampEnabled).forEach { (topic, payload) ->
                        try {
                            link.publish(topic, payload)
                            onWrite(true)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            onWrite(false)
                            onError(DataOutputError(DataOutputErrorType.PublishFailed, clock.nowMs(), params.host, params.port, exception.message))
                        }
                    }
                }
            }
            val cause = link.awaitLoss()
            publisher.cancel()
            DataOutputError(DataOutputErrorType.ConnectionLost, clock.nowMs(), params.host, params.port, cause.message)
        }

        private suspend fun fail(error: DataOutputError) {
            onError(error)
            setState(SenderState.Failed(error, error.atMs + retryDelayMs))
            delay(retryDelayMs)
        }
    }
}
