package org.superdro.core.dataoutput

import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import java.util.Vector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.bouncycastle.tls.AbstractTlsClient
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
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.superdro.core.dataoutput.RealSockets.DEFAULT_RECORD
import org.superdro.core.dataoutput.RealSockets.LoopbackOutput
import org.superdro.core.dataoutput.RealSockets.connect
import org.superdro.core.dataoutput.RealSockets.freePort
import org.superdro.core.dataoutput.RealSockets.readLine
import org.superdro.core.dataoutput.RealSockets.readUntilEnd
import org.superdro.core.dataoutput.RealSockets.waitUntil
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputFormat

/**
 * The encrypted mode "Encrypted (PSK)" with real sockets: TLS 1.3 with an external
 * PSK. The client is the BouncyCastle TLS client.
 */
class PskTlsOutputTest {

    private val key = PskKey("superdro", ByteArray(32) { index -> (index * 7 + 3).toByte() })

    /** A TLS 1.3 client with one external PSK, as a client program of a user does it. */
    private class PskClient(private val identity: ByteArray, private val secret: ByteArray) :
        AbstractTlsClient(BcTlsCrypto(SecureRandom())) {

        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()

        override fun getSupportedCipherSuites(): IntArray =
            TlsUtils.getSupportedCipherSuites(crypto, intArrayOf(CipherSuite.TLS_AES_128_GCM_SHA256))

        override fun getExternalPSKs(): Vector<*> =
            Vector(listOf(BasicTlsPSKExternal(identity, crypto.createSecret(secret), PRFAlgorithm.tls13_hkdf_sha256)))

        override fun getAuthentication(): TlsAuthentication = object : ServerOnlyTlsAuthentication() {
            // The server must use the PSK. A certificate is refused.
            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) {
                throw TlsFatalAlert(AlertDescription.certificate_unknown)
            }
        }
    }

    private fun tlsConnect(port: Int, identity: ByteArray, secret: ByteArray): Pair<Socket, TlsClientProtocol> {
        val socket = connect(port)
        val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        try {
            protocol.connect(PskClient(identity, secret))
        } catch (exception: IOException) {
            socket.close()
            throw exception
        }
        return socket to protocol
    }

    private fun start(config: DataOutputConfig = DataOutputConfig(enabled = true)): Pair<LoopbackOutput, Int> {
        val port = freePort()
        val output = LoopbackOutput(config.copy(port = port))
        output.controller.secrets.setPsk(key)
        output.controller.setMachineOnline(true)
        output.controller.requestStart()
        return output to port
    }

    @Test
    fun aClientWithTheKeyGetsTheSameRecordsAsInThePlainTextMode() = runBlocking {
        val (output, port) = start()
        try {
            waitUntil { output.controller.state.value.sender == SenderState.Ready }
            val (socket, protocol) = tlsConnect(port, key.identityBytes, key.secret)
            socket.use {
                val input = protocol.inputStream
                val first = readLine(input)
                assertEquals(DEFAULT_RECORD, first)
                assertEquals(48, first!!.encodeToByteArray().size)
                assertEquals(DEFAULT_RECORD, readLine(input))
                assertEquals(1, output.controller.state.value.clients.size)
            }
        } finally {
            output.close()
        }
    }

    @Test
    fun theJsonRecordsAreTheSameInTheEncryptedStream() = runBlocking {
        val (output, port) = start(DataOutputConfig(enabled = true, format = DataOutputFormat.Json))
        try {
            waitUntil { output.controller.state.value.sender == SenderState.Ready }
            val (socket, protocol) = tlsConnect(port, key.identityBytes, key.secret)
            socket.use {
                val record = readLine(protocol.inputStream)!!
                assertTrue(record.startsWith("{\"timestamp\":\"2026-10-11T14:03:22.517Z\",\"sequence\":"), record)
                assertTrue(record.endsWith(",\"x_absolute\":12.7000,\"y_absolute\":-3.1750,\"z_absolute\":0.0000}\n"), record)
            }
        } finally {
            output.close()
        }
    }

    @Test
    fun aClientWithAWrongKeyGetsNoRecord() = runBlocking {
        val (output, port) = start()
        try {
            waitUntil { output.controller.state.value.sender == SenderState.Ready }
            val wrong = key.secret.also { bytes -> bytes[0] = (bytes[0] + 1).toByte() }
            assertFailsWith<IOException> { tlsConnect(port, key.identityBytes, wrong) }
            waitUntil { output.controller.state.value.statistics?.lastError?.type == DataOutputErrorType.HandshakeFailed }
            assertEquals("127.0.0.1", output.controller.state.value.statistics?.lastError?.host)
            assertTrue(output.controller.state.value.clients.isEmpty())
        } finally {
            output.close()
        }
    }

    @Test
    fun aClientWithAnUnknownIdentityGetsNoRecord() = runBlocking {
        val (output, port) = start()
        try {
            waitUntil { output.controller.state.value.sender == SenderState.Ready }
            assertFailsWith<IOException> { tlsConnect(port, "intruder".encodeToByteArray(), key.secret) }
            assertTrue(output.controller.state.value.clients.isEmpty())
        } finally {
            output.close()
        }
    }

    @Test
    fun aPlainTextClientGetsNoRecordFromTheEncryptedMode() = runBlocking {
        val (output, port) = start()
        try {
            waitUntil { output.controller.state.value.sender == SenderState.Ready }
            connect(port).use { socket ->
                // The client of the plain text mode sends nothing and reads. The server waits for
                // the TLS handshake and sends no record.
                socket.soTimeout = 1500
                assertFailsWith<java.net.SocketTimeoutException> { socket.getInputStream().read() }
                // Bytes that are not a TLS record stop the connection with no record.
                socket.getOutputStream().write("#Z;\n#Y;\n".encodeToByteArray())
                socket.getOutputStream().flush()
                val rest = readUntilEnd(socket, 3000)
                assertTrue(!rest.contains("12.7000"), rest)
            }
            assertTrue(output.controller.state.value.clients.isEmpty())
        } finally {
            output.close()
        }
    }

    @Test
    fun aNewKeyStopsTheOldKey() = runBlocking {
        val (output, port) = start()
        try {
            waitUntil { output.controller.state.value.sender == SenderState.Ready }
            val replaced = output.controller.secrets.replacePsk()
            // The UI starts the output again after a new key.
            output.controller.requestStop()
            output.controller.requestStart()
            waitUntil { output.controller.state.value.sender == SenderState.Ready }
            assertFailsWith<IOException> { tlsConnect(port, key.identityBytes, key.secret) }
            val (socket, protocol) = tlsConnect(port, replaced.identityBytes, replaced.secret)
            socket.use { assertEquals(DEFAULT_RECORD, readLine(protocol.inputStream)) }
        } finally {
            output.close()
        }
    }
}
