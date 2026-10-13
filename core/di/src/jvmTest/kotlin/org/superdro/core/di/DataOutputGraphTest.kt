package org.superdro.core.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Properties
import java.util.Vector
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bouncycastle.tls.AbstractTlsClient
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.BasicTlsPSKExternal
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.ServerOnlyTlsAuthentication
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.connection.SessionState
import org.superdro.core.connection.toDeviceRef
import org.superdro.core.dataoutput.DataOutputStatus
import org.superdro.core.dataoutput.PskKey
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.fakeadapter.FakeDebugAdapter
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.dataoutput.DataOutputPresenter

/**
 * The data output with the full common graph, the real connection manager, the fake adapter, and
 * real sockets on the loopback address. The
 * screen turns on the output of the demo mill in the PSK mode, and a TLS 1.3 client with the key
 * reads the records of the live values.
 */
class DataOutputGraphTest {

    private val directory = kotlin.io.path.createTempDirectory("superdro-output").toFile()

    private val loopback = object : NetworkInterfaces {
        override val interfaces = MutableStateFlow(listOf(NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf("127.0.0.1"))))
    }

    private val di = DI {
        import(coreDiModule)
        import(noOpPlatformModule)
        bindSingleton<SqlDriver> {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
                .also { driver -> SuperDroDatabase.Schema.create(driver) }
        }
        bindProvider<StoreIOProvider>(overrides = true) { StoreIOProviderJvm(directory) }
        bindSingleton<DebugAdapter>(overrides = true) { FakeDebugAdapter() }
        bindSingleton<NetworkInterfaces>(overrides = true) { loopback }
    }

    @AfterTest
    fun close() {
        di.direct.instance<ConnectionManager>().close()
        di.direct.instance<SqlDriver>().close()
        directory.deleteRecursively()
    }

    /** A TLS 1.3 client with one external PSK, as a client program of a user does it. */
    private class PskClient(private val identity: ByteArray, private val secret: ByteArray) : AbstractTlsClient(BcTlsCrypto(SecureRandom())) {
        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()

        override fun getSupportedCipherSuites(): IntArray =
            TlsUtils.getSupportedCipherSuites(crypto, intArrayOf(CipherSuite.TLS_AES_128_GCM_SHA256))

        override fun getExternalPSKs(): Vector<*> =
            Vector(listOf(BasicTlsPSKExternal(identity, crypto.createSecret(secret), PRFAlgorithm.tls13_hkdf_sha256)))

        override fun getAuthentication(): TlsAuthentication = object : ServerOnlyTlsAuthentication() {
            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) {
                throw TlsFatalAlert(AlertDescription.certificate_unknown)
            }
        }
    }

    private fun tlsConnect(port: Int, key: PskKey): Pair<Socket, InputStream> {
        val socket = Socket().apply {
            connect(InetSocketAddress("127.0.0.1", port), 2000)
            soTimeout = 5000
        }
        val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        try {
            protocol.connect(PskClient(key.identityBytes, key.secret))
        } catch (exception: IOException) {
            socket.close()
            throw exception
        }
        return socket to protocol.inputStream
    }

    private fun readLine(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) error("The stream ended after ${bytes.size()} bytes.")
            bytes.write(value)
            if (value == '\n'.code) return bytes.toString(Charsets.UTF_8)
        }
    }

    private suspend fun waitUntil(what: String, condition: suspend () -> Boolean) {
        withTimeout(20_000) {
            while (!condition()) delay(20)
        }
        check(condition()) { what }
    }

    @Test
    fun theScreenTurnsOnThePskOutputAndAClientWithTheKeyReadsTheLiveRecords() = runBlocking {
        val manager = di.direct.instance<ConnectionManager>()
        val machines = di.direct.instance<MachineRepository>()
        val mill = di.direct.instance<DebugAdapter>().demoLinks.first { link -> !link.name.contains("lathe", ignoreCase = true) }
        manager.connect(mill.toDeviceRef())
        val id = mill.deviceId.value
        waitUntil("The demo mill is online") {
            machines.loadProfile(id) != null && manager.sessions.value[DeviceId(id)]?.state?.value is SessionState.Connected
        }

        val presenter = di.direct.instance<DataOutputPresenter>()
        try {
            presenter.selectProfile(id)
            waitUntil("The screen shows the PSK of the demo mill") { presenter.uiState.value.profileId == id && presenter.uiState.value.psk != null }
            val port = ServerSocket(0).use { socket -> socket.localPort }
            presenter.onInterface(BindInterface.Localhost)
            presenter.onPortText(port.toString())
            presenter.onEnabledChange(true)
            waitUntil("The switch is on") { presenter.uiState.value.stored.enabled }
            check(presenter.uiState.value.stored.security == DataOutputSecurity.Psk)
            presenter.onStartStop()
            waitUntil("The server listens") { presenter.uiState.value.status.status == DataOutputStatus.Ready }
            val key = PskKey.parse(presenter.uiState.value.psk!!)

            val (socket, input) = tlsConnect(port, key)
            socket.use {
                val pattern = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z;-?\d+\.\d{4};(-?\d+\.\d{4})?;(-?\d+\.\d{4})?\n""")
                val first = readLine(input)
                assertTrue(pattern.matches(first), first)
                val second = readLine(input)
                assertTrue(pattern.matches(second), second)
                waitUntil("The screen shows one client") { presenter.uiState.value.status.clients == 1 }
            }

            val wrong = PskKey(key.identity, key.secret.also { bytes -> bytes[0] = (bytes[0] + 1).toByte() })
            assertFailsWith<IOException> { tlsConnect(port, wrong).first.close() }

            presenter.onEnabledChange(false)
            waitUntil("The switch off closes the server at once") {
                try {
                    Socket().use { probe -> probe.connect(InetSocketAddress("127.0.0.1", port), 500) }
                    false
                } catch (exception: ConnectException) {
                    true
                }
            }
        } finally {
            presenter.close()
        }
    }
}
