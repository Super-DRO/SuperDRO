package org.superdro.core.protocol

import org.superdro.core.protocol.AdapterCommand.RefAxis
import org.superdro.core.protocol.AdapterCommand.SetMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The command sequences and the Adapter Details queries, as queue scenarios. */
class CommandQueueSequenceTest {

    private val startWrites = listOf(
        "\"#get:mac;\"", "\"#get:uuid;\"", "\"#get:features;\"", "\"#get:uptime;\"", "\"#get:model;\"",
        "#get_mode:x;", "#get_mode:y;", "#get_mode:z;", "#get_mode:w;", "#get_mode:u;", "#get_mode:v;",
    )
    private val detailsWrites = listOf(
        "\"#get:name;\"", "\"#get:version;\"", "\"#get:build;\"", "\"#get:date;\"",
        "#get_mode:x;", "#get_mode:y;", "#get_mode:z;", "#get_mode:w;", "#get_mode:u;", "#get_mode:v;",
    )

    @Test
    fun theConnectSequence() {
        val harness = QueueHarness(respond = QueueHarness.genuine())
        harness.phase(LinkPhase.Connected)
        harness.runUntil(1_999L)
        assertEquals(emptyList(), harness.writes())
        harness.runUntil(10_000L)
        assertEquals(startWrites, harness.writeTexts())
        assertEquals((0..10).map { 2_000L + 50L * it }, harness.writes().map { it.first })
        assertTrue(harness.writeTexts().none { it.startsWith("#get_sig") })
        val values = harness.queue.values
        assertEquals("246f28a1b2c3", values.mac.value)
        assertEquals("c0ffee00-1234-4abc-8def-0123456789ab", values.uuid.value)
        assertEquals(1L, values.features.value)
        assertEquals(0xE10L, values.uptime.value)
        assertEquals("tda-example", values.model.value)
        InputChannel.entries.forEach { channel ->
            val modes = values.modes.getValue(channel).value!!
            assertEquals(InputMode.QuadratureAB, modes.current)
            assertEquals(4, modes.supported.size)
        }
        assertEquals("24:6f:28:a1:b2:c5", values.reportedIdentity)
        assertEquals(InputChannel.entries.toSet(), harness.all<QueueOutput.BusyChannels>().first().second.channels)
        assertEquals(emptySet(), harness.queue.busyChannels)
        assertTrue(harness.all<QueueOutput.Result>().all { it.second.result == CommandResult.Completed })
    }

    @Test
    fun theMacAnswerAfterAnAckOfTheTable() {
        val genuine = QueueHarness.genuine()
        val harness = QueueHarness(respond = { command ->
            if (command == AdapterCommand.GetField(AdapterField.Mac)) listOf(100L to "\$0aa3;", 1_500L to "?0530aea4a1b2c3;") else genuine(command)
        })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(3_499L)
        assertNull(harness.resultOf(1))
        harness.runUntil(3_500L)
        assertEquals(CommandResult.Completed, harness.resultOf(1))
        assertEquals("30:ae:a4:a1:b2:c5", harness.queue.values.bluetoothId)
    }

    @Test
    fun theModeAnswerOfTheTypicalAnswers() {
        val genuine = QueueHarness.genuine()
        val harness = QueueHarness(respond = { command ->
            if (command == AdapterCommand.GetMode(InputChannel.X)) listOf(10L to "?01x00030002;") else genuine(command)
        })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(3_000L)
        val x = harness.queue.values.modes.getValue(InputChannel.X)
        assertEquals(listOf(InputMode.Disabled, InputMode.QuadratureAB), x.value?.supported)
        assertEquals(InputMode.QuadratureAB, x.value?.current)
        assertEquals(ValueState.Reported(2_260L, ValueSource.LegacyGet), x.state)
    }

    @Test
    fun changeAnInputMode() {
        val modes = InputChannel.entries.associateWith { 2 }.toMutableMap()
        val harness = QueueHarness.afterStart()
        harness.respond = QueueHarness.genuine(modes)
        harness.clearOutputs()
        val id = harness.submit(SetMode(InputChannel.X, InputMode.Tachometer))
        harness.runUntil(5_000L)
        assertEquals(listOf(3_000L to "#set_mode:x,4;", 3_050L to "#get_mode:x;"), harness.writes())
        assertEquals(QueueOutput.RestartNeeded(InputChannel.X), harness.all<QueueOutput.RestartNeeded>().single().second)
        assertEquals(3_020L, harness.all<QueueOutput.RestartNeeded>().single().first)
        assertEquals(CommandResult.Completed, harness.resultOf(id))
        assertEquals(3_060L, harness.resultTimeOf(id))
        assertEquals(InputMode.Tachometer, harness.queue.values.modes.getValue(InputChannel.X).value?.current)
        assertEquals(emptySet(), harness.queue.busyChannels)
    }

    @Test
    fun changeAnInputModeErrorCase() {
        val harness = QueueHarness.afterStart()
        harness.respond = { if (it is SetMode) listOf(10L to "!0603;") else emptyList() }
        val id = harness.submit(SetMode(InputChannel.X, InputMode.Tachometer))
        harness.runUntil(6_000L)
        val failed = harness.all<QueueOutput.CommandFailed>().single().second
        assertEquals("Command failed: Invalid argument (6:3)", failed.failure.message)
        assertEquals(id, failed.requestId)
        assertEquals(CommandResult.Failed(CommandFailure(6, 3, "Invalid argument")), harness.resultOf(id))
        assertEquals(emptyList(), harness.all<QueueOutput.RestartNeeded>())
    }

    @Test
    fun restartTheAdapter() {
        val harness = QueueHarness.afterStart()
        val id = harness.submit(AdapterCommand.Reboot)
        harness.runUntil(3_010L)
        assertEquals(3_000L to "#reboot:1000;", harness.writes().last())
        assertEquals(CommandResult.Completed, harness.resultOf(id))
        assertEquals(QueueOutput.RestartAdapter(3_010L, 3_260L, 5_010L), harness.all<QueueOutput.RestartAdapter>().single().second)
        // The session closes the link. A new link gets a new queue and the start queries again.
        harness.runUntil(3_260L)
        harness.phase(LinkPhase.Dropped)
        assertNull(harness.queue.currentCommand)
    }

    @Test
    fun referenceMarkSearch() {
        val harness = QueueHarness.afterStart()
        harness.clearOutputs()
        val id = harness.submit(RefAxis(InputChannel.X))
        harness.runUntil(3_020L)
        assertEquals(listOf(3_000L to "#ref_axis:x,15;"), harness.writes())
        assertEquals(CommandResult.Completed, harness.resultOf(id))
        assertEquals(QueueOutput.Reference(InputChannel.X, ReferenceState.InProgress), harness.all<QueueOutput.Reference>().single().second)
        harness.runUntil(8_000L)
        harness.receive("\$78a1;")
        assertEquals(ReferenceState.Finished, harness.all<QueueOutput.Reference>().last().second.state)
        assertEquals(ReferenceState.Finished, harness.queue.values.references.getValue(InputChannel.X).value)
        harness.receive("\$78a9;")
        assertEquals(ReferenceState.Cancelled, harness.all<QueueOutput.Reference>().last().second.state)
        harness.receive("\$7816;")
        assertEquals(QueueOutput.Reference(InputChannel.X, ReferenceState.TimedOut), harness.all<QueueOutput.Reference>().last().second)
        harness.receive("!7816;")
        assertEquals(5, harness.all<QueueOutput.Reference>().size)
        harness.receive("\$78aa;")
        assertEquals(ReferenceState.InProgress, harness.all<QueueOutput.Reference>().last().second.state)
    }

    @Test
    fun aUserTimeoutCompletesTheCurrentReferenceCommand() {
        val harness = QueueHarness.afterStart()
        harness.respond = { if (it is RefAxis) listOf(10L to "\$0aa3;", 20L to "!7916;") else emptyList() }
        val id = harness.submit(RefAxis(InputChannel.Y))
        harness.runUntil(3_020L)
        assertEquals(CommandResult.Completed, harness.resultOf(id))
        assertEquals(QueueOutput.Reference(InputChannel.Y, ReferenceState.TimedOut), harness.all<QueueOutput.Reference>().single().second)
    }

    @Test
    fun anotherCodeOnTheReferenceSubsystemShowsAFailureAndTheRequestStays() {
        val harness = QueueHarness.afterStart()
        harness.respond = { if (it is RefAxis) listOf(10L to "\$0aa3;", 20L to "\$79a2;") else emptyList() }
        val id = harness.submit(RefAxis(InputChannel.Y))
        harness.runUntil(3_020L)
        assertEquals("Command failed: Ready (121:162)", harness.all<QueueOutput.CommandFailed>().single().second.failure.message)
        assertNull(harness.resultOf(id))
        assertEquals(RefAxis(InputChannel.Y), harness.queue.currentCommand)
        harness.runUntil(4_510L)
        assertEquals(CommandResult.Failed(CommandFailure(0x79, 0xA2, "Ready")), harness.resultOf(id))
    }

    @Test
    fun clearACounter() {
        val harness = QueueHarness.afterStart()
        val id = harness.submit(AdapterCommand.ClearCounter(InputChannel.Z))
        harness.runUntil(3_010L)
        assertEquals(3_000L to "#clear_counter:z;", harness.writes().last())
        assertEquals(CommandResult.Completed, harness.resultOf(id))
    }

    @Test
    fun theAdapterDetailsQueries() {
        val harness = QueueHarness.afterStart()
        harness.clearOutputs()
        harness.openDetails()
        harness.runUntil(3_005L)
        // A second open while the values are "Fetching" for less than 5000 ms adds nothing.
        harness.openDetails()
        harness.runUntil(4_000L)
        assertEquals(detailsWrites, harness.writeTexts())
        assertEquals((0..9).map { 3_000L + 50L * it }, harness.writes().map { it.first })
        assertEquals("Shop adapter", harness.queue.values.name.value)
        assertEquals("1.4.2", harness.queue.values.version.value)
        assertEquals("b42", harness.queue.values.build.value)
        assertEquals(0x66000000L, harness.queue.values.date.value)
        // A change to Connected while the screen is open queues the group again.
        harness.phase(LinkPhase.Waiting)
        harness.runUntil(4_100L)
        harness.clearOutputs()
        harness.phase(LinkPhase.Connected)
        harness.runUntil(6_000L)
        assertEquals(detailsWrites, harness.writeTexts())
        // A closed screen does not queue the group.
        harness.queue.closeAdapterDetails()
        harness.phase(LinkPhase.Waiting)
        harness.runUntil(6_100L)
        harness.clearOutputs()
        harness.phase(LinkPhase.Connected)
        harness.runUntil(8_000L)
        assertEquals(emptyList(), harness.writes())
    }

    @Test
    fun theAdapterDetailsGroupBeforeTheQueueStartGoesAfterTheStartQueries() {
        val harness = QueueHarness(respond = QueueHarness.genuine())
        harness.phase(LinkPhase.Connected)
        harness.runUntil(500L)
        harness.openDetails()
        harness.runUntil(5_000L)
        assertEquals(startWrites + detailsWrites.take(4), harness.writeTexts())
    }

    @Test
    fun theDetailsSkipAFieldThatIsFetchingForLessThan5000Ms() {
        val harness = QueueHarness.afterStart()
        harness.respond = { emptyList() }
        harness.submit(AdapterCommand.GetField(AdapterField.Name))
        harness.runUntil(3_100L)
        harness.clearOutputs()
        harness.openDetails()
        harness.runUntil(3_500L)
        assertEquals(listOf("\"#get:version;\""), harness.writeTexts())
    }
}
