package org.superdro.core.ui.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.dataoutput.DataOutputSecrets
import org.superdro.core.dataoutput.DataOutputState
import org.superdro.core.dataoutput.PskKey
import org.superdro.core.dataoutput.SenderState
import org.superdro.core.dataoutput.StartBlock
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.MachineProfile
import org.superdro.core.readout.input.ProbeEvent

/**
 * The runner of the outputs with virtual time: one controller for each
 * profile, the online state of the machine, and the values of the readout model.
 */
class DataOutputRunnerTest {

    private val plainAlwaysOn = DataOutputConfig(
        enabled = true,
        activation = DataOutputActivation.AlwaysOn,
        security = DataOutputSecurity.PlainText,
        plainTextConfirmed = true,
    )

    private fun run(block: suspend TestScope.(DataOutputHarness) -> Unit) = runTest {
        val harness = DataOutputHarness(this)
        try {
            block(harness)
        } finally {
            harness.close()
        }
    }

    private suspend fun DataOutputHarness.state(profile: MachineProfile): DataOutputState = runner.state(profile.id).first()!!

    private suspend fun DataOutputHarness.update(profile: MachineProfile, config: DataOutputConfig) {
        store.machines.updateProfile(profile.id) { stored -> stored.copy(dataOutput = config) }
        settle()
    }

    @Test
    fun noServerSocketIsOpenWhileTheOutputIsOffAndTheSwitchOffClosesItAtOnce() = run { harness ->
        val profile = harness.profile()
        harness.session(profile)
        harness.start()
        assertTrue(harness.sockets.sockets.isEmpty(), "The output is off by default.")
        assertEquals(StartBlock.NotEnabled, harness.state(profile).block)

        harness.update(profile, plainAlwaysOn)
        assertEquals(listOf("192.168.1.20:5800"), harness.sockets.open.map { socket -> "${socket.host}:${socket.port}" }, "Only the Wi-Fi address.")
        assertEquals(SenderState.Ready, harness.state(profile).sender)

        val client = harness.sockets.open.single().connect()
        harness.settle()
        assertEquals(1, harness.state(profile).clients.size)

        harness.store.machines.updateProfile(profile.id) { stored -> stored.copy(dataOutput = stored.dataOutput.copy(enabled = false)) }
        runCurrent()
        assertTrue(harness.sockets.open.isEmpty(), "The switch off closes the server at once.")
        assertTrue(client.closed, "The switch off closes each client at once.")
    }

    @Test
    fun theOutputRunsOnlyWhileItsMachineIsOnline() = run { harness ->
        val profile = harness.profile { stored -> stored.copy(dataOutput = plainAlwaysOn) }
        val session = harness.session(profile, SessionState.Reconnecting(1, 0L, LinkFailure.Lost))
        harness.start()
        assertTrue(harness.sockets.sockets.isEmpty())
        assertEquals(StartBlock.MachineOffline, harness.state(profile).block)

        session.state.value = SessionState.Connected(0L)
        harness.settle()
        assertEquals(1, harness.sockets.open.size)

        session.state.value = SessionState.Disconnected
        harness.settle()
        assertTrue(harness.sockets.open.isEmpty())
        harness.connections.remove(session.id)
        harness.settle()
        assertTrue(harness.sockets.open.isEmpty())
    }

    @Test
    fun onDemandRunsOnlyAfterTheStartAndStopsAtTheStop() = run { harness ->
        val profile = harness.profile { stored -> stored.copy(dataOutput = plainAlwaysOn.copy(activation = DataOutputActivation.OnDemand)) }
        harness.session(profile)
        harness.start()
        assertEquals(StartBlock.NotRequested, harness.state(profile).block)
        harness.runner.toggle(profile.id)
        harness.settle()
        assertEquals(1, harness.sockets.open.size)
        harness.runner.toggle(profile.id)
        harness.settle()
        assertTrue(harness.sockets.open.isEmpty())
    }

    @Test
    fun aClientGetsTheDefaultRecordOfTheReadoutValues() = run { harness ->
        val profile = harness.profile { stored -> stored.copy(dataOutput = plainAlwaysOn) }
        val session = harness.session(profile)
        harness.start()
        val client = harness.sockets.open.single().connect()
        harness.settle()
        val sampleMs = testScheduler.currentTime
        session.sample(Channels.X, 12700L, sampleMs)
        session.sample(Channels.Y, -3175L, sampleMs)
        session.sample(Channels.Z, 0L, sampleMs)
        harness.settle()
        val values = harness.runner.valuesOf(profile.id)!!.value
        assertEquals(12700.0, values.xAbsolute)
        assertEquals(-3175.0, values.yAbsolute)
        assertEquals(0.0, values.zAbsolute)
        advanceTimeBy(600L)
        runCurrent()
        val line = client.receivedText.lines().last { text -> text.endsWith("0.0000") }
        assertTrue(line.endsWith(";12.7000;-3.1750;0.0000"), client.receivedText)
        assertEquals(DataOutputHarness.WALL_START_MS + sampleMs, values.updatedAtEpochMs, "The time stamp is the time of the last value update.")
    }

    @Test
    fun theFeedRateComesFromThe2000MsWindowAndGoesTo0AtRest() = run { harness ->
        val config = plainAlwaysOn.copy(fields = DataOutputConfig.defaultFields().map { field -> field.copy(enabled = field.id == DataOutputFieldId.XFeedRate) })
        val profile = harness.profile { stored -> stored.copy(dataOutput = config) }
        val session = harness.session(profile)
        harness.start()
        session.sample(Channels.X, 0L, testScheduler.currentTime)
        advanceTimeBy(2000L)
        session.sample(Channels.X, 0L, testScheduler.currentTime)
        advanceTimeBy(1000L)
        session.sample(Channels.X, 10_000L, testScheduler.currentTime)
        harness.settle(200L)
        assertEquals(300_000.0, harness.runner.valuesOf(profile.id)!!.value.xFeedRate, "10 mm in the last 2000 ms: 300 mm/min.")
        advanceTimeBy(2500L)
        runCurrent()
        assertEquals(0.0, harness.runner.valuesOf(profile.id)!!.value.xFeedRate, "With no new sample the rate goes to 0.")
    }

    @Test
    fun theTriggerFieldsComeFromTheProbeEventsOfPortP() = run { harness ->
        val triggers = setOf(DataOutputFieldId.Probe1TriggerX, DataOutputFieldId.Probe1TriggerSeq, DataOutputFieldId.Probe1TriggerDirX)
        val config = plainAlwaysOn.copy(fields = DataOutputConfig.defaultFields().map { field -> field.copy(enabled = field.id in triggers) })
        val profile = harness.profile { stored -> stored.copy(dataOutput = config) }
        val session = harness.session(profile)
        harness.start()
        session.sample(Channels.X, 0L, testScheduler.currentTime)
        advanceTimeBy(500L)
        session.sample(Channels.X, 1000L, testScheduler.currentTime)
        harness.settle()
        assertNull(harness.runner.valuesOf(profile.id)!!.value.probe1TriggerX, "No trigger before the first probe event.")

        fun event(sequence: Long, port: Char) = ProbeEvent(
            sequence = sequence,
            port = port,
            timeMs = testScheduler.currentTime,
            inputPositions = mapOf(Channels.X to 1000.0),
            axisPositions = mapOf(AxisId.X to 1000.0),
            incompleteAxes = emptySet(),
            axisVelocities = mapOf(AxisId.X to 1000.0),
        )
        session.probe(event(1L, Channels.P))
        harness.settle()
        var values = harness.runner.valuesOf(profile.id)!!.value
        assertEquals(1000.0, values.probe1TriggerX)
        assertEquals(1.0, values.probe1TriggerSeq)
        assertEquals(1.0, values.probe1TriggerDirX, "X moved in the + direction before the trigger.")
        assertEquals(60_000.0, values.probe1TriggerFeed)

        session.probe(event(2L, Channels.Q))
        harness.settle()
        assertEquals(1.0, harness.runner.valuesOf(profile.id)!!.value.probe1TriggerSeq, "A trigger of the port q is not a trigger of probe 1.")
        session.probe(event(3L, Channels.P))
        harness.settle()
        values = harness.runner.valuesOf(profile.id)!!.value
        assertEquals(2.0, values.probe1TriggerSeq, "The count of the triggers since the start of the output.")

        session.state.value = SessionState.Disconnected
        harness.settle()
        session.state.value = SessionState.Connected(0L)
        harness.settle()
        assertNull(harness.runner.valuesOf(profile.id)!!.value.probe1TriggerSeq, "A new start counts from 0.")
    }

    @Test
    fun twoOutputsCannotListenOnTheSamePort() = run { harness ->
        val mill = harness.profile(name = "Mill") { stored -> stored.copy(dataOutput = plainAlwaysOn) }
        val router = harness.profile(name = "Router") { stored -> stored.copy(dataOutput = plainAlwaysOn) }
        harness.session(mill)
        harness.session(router)
        harness.start()
        assertEquals(1, harness.sockets.open.size)
        val blocks = listOf(harness.state(mill).block, harness.state(router).block)
        assertEquals(1, blocks.count { block -> block is StartBlock.PortTaken }, blocks.toString())
    }

    @Test
    fun aNewKeyStartsTheOutputAgainAndADeletedProfileLosesItsSecrets() = run { harness ->
        val profile = harness.profile { stored -> stored.copy(dataOutput = DataOutputConfig(enabled = true, activation = DataOutputActivation.AlwaysOn)) }
        harness.session(profile)
        harness.start()
        val secrets = DataOutputSecrets(harness.secrets, profile.id)
        val first = secrets.psk()!!
        assertEquals(PskKey.DEFAULT_IDENTITY, first.identity, "The first start in the PSK mode makes a key.")
        val oldSocket = harness.sockets.open.single()
        val second = harness.runner.newPsk(profile.id)
        harness.settle()
        assertNotEquals(first, second)
        assertEquals(second, secrets.psk())
        assertTrue(oldSocket.closed, "The output starts again with the new key.")
        assertEquals(1, harness.sockets.open.size)

        secrets.setMqttCredentials("dro", "secret".encodeToByteArray())
        harness.store.machines.deleteProfile(profile.id, deleteTools = false)
        harness.settle()
        assertNull(harness.secrets.get("data_output.${profile.id}.psk"))
        assertNull(harness.secrets.get("data_output.${profile.id}.mqtt_username"))
        assertNull(harness.secrets.get("data_output.${profile.id}.mqtt_password"))
        assertTrue(harness.sockets.open.isEmpty())
    }
}
