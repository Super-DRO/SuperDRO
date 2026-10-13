package org.superdro.core.dataoutput

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.model.BindInterface

/** A fake of the network interfaces. */
class FakeNetworkInterfaces(
    initial: List<NetworkInterfaceInfo> = listOf(
        NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("192.168.1.20")),
        NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf("127.0.0.1")),
        NetworkInterfaceInfo("rmnet0", null, listOf("100.64.0.9")),
    ),
) : NetworkInterfaces {
    override val interfaces = MutableStateFlow(initial)
}

/** A fake MQTT connector. Each connect gives a [FakeMqttConnection], or throws [failure]. */
class FakeMqttConnector : MqttConnector {
    val params = mutableListOf<MqttConnectParams>()
    val connections = mutableListOf<FakeMqttConnection>()
    var failure: Exception? = null

    override suspend fun connect(params: MqttConnectParams): MqttConnection {
        this.params += params
        failure?.let { exception -> throw exception }
        return FakeMqttConnection().also { connection -> connections += connection }
    }
}

class FakeMqttConnection : MqttConnection {
    val messages = mutableListOf<Pair<String, String>>()
    private val loss = CompletableDeferred<Throwable>()
    var closed = false
        private set

    /** The broker stops the link. */
    fun lose(cause: Throwable) {
        loss.complete(cause)
    }

    override suspend fun publish(topic: String, payload: ByteArray) {
        messages += topic to payload.decodeToString()
    }

    override suspend fun awaitLoss(): Throwable = loss.await()

    override fun close() {
        closed = true
    }
}
