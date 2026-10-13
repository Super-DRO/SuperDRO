package org.superdro.core.dataoutput

import java.io.IOException
import javax.net.SocketFactory
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.MqttSecurityException
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

actual fun defaultMqttConnector(): MqttConnector = PahoMqttConnector()

/**
 * The MQTT connector with Eclipse Paho MQTT v3.1.1. The consumer R8 rules of this module keep
 * the Paho classes that Paho
 * loads through reflection.
 *
 * - `ssl://`: TLS with [tlsSocketFactory] (default: the factory of the system, with the trust
 *   store of the system) and the check of the broker host name.
 * - `tcp://`: no TLS. The output uses it only after the warning.
 * - Clean session, QoS 0, no retain flag, no automatic reconnect: [MqttOutput] opens a new link
 *   after 5 s.
 *
 * @param tlsSocketFactory The TLS socket factory, for example a factory with the CA of a test broker.
 */
class PahoMqttConnector(private val tlsSocketFactory: SocketFactory? = null) : MqttConnector {

    override suspend fun connect(params: MqttConnectParams): MqttConnection {
        val client = try {
            MqttAsyncClient(params.serverUri, params.clientId, MemoryPersistence())
        } catch (exception: MqttException) {
            throw failure(params, exception)
        }
        val loss = CompletableDeferred<Throwable>()
        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                loss.complete(cause ?: IOException("The broker link stopped."))
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) = Unit

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val options = MqttConnectOptions().apply {
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            isCleanSession = true
            isAutomaticReconnect = false
            keepAliveInterval = params.keepAliveSeconds
            connectionTimeout = params.connectTimeoutSeconds
            params.credentials?.let { credentials ->
                userName = credentials.username
                credentials.password?.let { bytes -> password = bytes.decodeToString().toCharArray() }
            }
            if (params.tls) {
                socketFactory = tlsSocketFactory ?: SSLSocketFactory.getDefault()
                isHttpsHostnameVerificationEnabled = true
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                client.connect(options, null, continuation.listener())
                continuation.invokeOnCancellation { runCatching { client.close(true) } }
            }
        } catch (exception: Exception) {
            runCatching { client.close(true) }
            throw if (exception is MqttException) failure(params, exception) else exception
        }
        return PahoConnection(client, loss)
    }

    private fun failure(params: MqttConnectParams, exception: MqttException): MqttFailure {
        val type = when {
            exception is MqttSecurityException -> DataOutputErrorType.AuthRejected
            exception.reasonCode == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                exception.reasonCode == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() -> DataOutputErrorType.AuthRejected
            generateSequence<Throwable>(exception) { cause -> cause.cause }.any { cause -> cause is SSLException } ->
                DataOutputErrorType.TlsFailure
            else -> DataOutputErrorType.BrokerUnreachable
        }
        val message = generateSequence<Throwable>(exception) { cause -> cause.cause }.lastOrNull()?.message ?: exception.message
        return MqttFailure(type, "${params.serverUri}: $message", exception)
    }
}

private class PahoConnection(
    private val client: MqttAsyncClient,
    private val loss: CompletableDeferred<Throwable>,
) : MqttConnection {

    override suspend fun publish(topic: String, payload: ByteArray) {
        suspendCancellableCoroutine<Unit> { continuation ->
            client.publish(topic, payload, QOS, false, null, continuation.listener())
        }
    }

    override suspend fun awaitLoss(): Throwable = loss.await()

    override fun close() {
        runCatching { if (client.isConnected) client.disconnectForcibly(0L, DISCONNECT_TIMEOUT_MS, true) }
        runCatching { client.close(true) }
    }

    companion object {
        private const val QOS = 0
        private const val DISCONNECT_TIMEOUT_MS = 200L
    }
}

private fun kotlinx.coroutines.CancellableContinuation<Unit>.listener(): IMqttActionListener = object : IMqttActionListener {
    override fun onSuccess(asyncActionToken: IMqttToken?) {
        if (isActive) resume(Unit)
    }

    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
        if (isActive) resumeWithException(exception ?: IOException("The MQTT action failed."))
    }
}
