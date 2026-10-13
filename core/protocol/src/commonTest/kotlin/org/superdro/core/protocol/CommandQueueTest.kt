package org.superdro.core.protocol

import org.superdro.core.protocol.AdapterCommand.ClearCounter
import org.superdro.core.protocol.AdapterCommand.GetField
import org.superdro.core.protocol.AdapterCommand.RefAxis
import org.superdro.core.protocol.AdapterCommand.SetMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The queue tests, with the time as a parameter. */
class CommandQueueTest {

    private val mac = GetField(AdapterField.Mac)
    private val failedTimeout = CommandResult.Failed(CommandFailure.TIMEOUT)

    @Test
    fun theFirstWaitIs500Ms() {
        val harness = QueueHarness()
        harness.phase(LinkPhase.Connected)
        harness.runUntil(2_499L)
        assertEquals(listOf(2_000L to "\"#get:mac;\""), harness.writes())
        assertNull(harness.resultOf(1))
        harness.runUntil(2_500L)
        assertEquals(failedTimeout, harness.resultOf(1))
        assertEquals(2_500L, harness.resultTimeOf(1))
        assertEquals(ValueState.Failed(FailureCause.Timeout, 2_500L), harness.queue.values.state(ValueKey.Field(AdapterField.Mac)))
        // The next request goes out at once. There is no mac retry after 100, 200, and 300 ms.
        assertEquals(2_500L to "\"#get:uuid;\"", harness.writes().last())
    }

    @Test
    fun theAckGivesTheFullWaitOf2000Ms() {
        val harness = QueueHarness(respond = { if (it == mac) listOf(100L to "\$0aa3;") else emptyList() })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(4_099L)
        assertNull(harness.resultOf(1))
        assertEquals(1, harness.writes().size)
        harness.runUntil(4_100L)
        assertEquals(4_100L, harness.resultTimeOf(1))
        assertEquals(failedTimeout, harness.resultOf(1))
    }

    @Test
    fun theAckOfRefAxisGivesTheFullWaitOf1500Ms() {
        val harness = QueueHarness.afterStart()
        harness.respond = { if (it is RefAxis) listOf(10L to "\$0aa3;") else emptyList() }
        val id = harness.submit(RefAxis(InputChannel.X))
        harness.runUntil(4_509L)
        assertNull(harness.resultOf(id))
        harness.runUntil(4_510L)
        assertEquals(failedTimeout, harness.resultOf(id))
        assertEquals(3_000L to "#ref_axis:x,15;", harness.writes().last())
    }

    @Test
    fun busyResendsAfter100And200MsWithANewWaitAndBusyThreeTimesEndsTheRequest() {
        val harness = QueueHarness(respond = { if (it == mac) listOf(50L to "\$0a20;") else emptyList() })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(2_449L)
        assertEquals(listOf(2_000L, 2_150L, 2_400L), harness.writes().map { it.first })
        assertEquals(listOf(1, 2, 3), harness.all<QueueOutput.Write>().map { it.second.transmission })
        assertNull(harness.resultOf(1))
        harness.runUntil(2_450L)
        assertEquals(CommandResult.Failed(CommandFailure(Subsystem.RX, StatusCode.BUSY, "Busy")), harness.resultOf(1))
        assertEquals(2_450L to "\"#get:uuid;\"", harness.writes().last())
    }

    @Test
    fun aBusyResendStartsANewFirstWait() {
        var busy = true
        val harness = QueueHarness(respond = { command ->
            if (command == mac && busy) {
                busy = false
                listOf(50L to "!0a20;")
            } else {
                emptyList()
            }
        })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(2_649L)
        assertEquals(listOf(2_000L, 2_150L), harness.writes().map { it.first })
        assertNull(harness.resultOf(1))
        harness.runUntil(2_650L)
        assertEquals(failedTimeout, harness.resultOf(1))
    }

    @Test
    fun theGapBetweenTwoQueueWritesIs50Ms() {
        val harness = QueueHarness.afterStart()
        val times = harness.writes().map { it.first }
        assertEquals((0..10).map { 2_000L + 50L * it }, times)
        // Each answer came 10 ms after its write, but the next write waited for the gap.
        assertEquals(10L, harness.all<QueueOutput.Result>().first().first - 2_000L)
    }

    @Test
    fun theMacQueryHasNoRetrySeriesAndTheIdGroupGetsOneMoreQueryAfter5000Ms() {
        // A legacy one-way adapter answers nothing.
        val harness = QueueHarness()
        harness.phase(LinkPhase.Connected)
        harness.runUntil(30_000L)
        assertEquals(
            listOf(
                2_000L to "\"#get:mac;\"", 2_500L to "\"#get:uuid;\"", 3_000L to "\"#get:features;\"",
                3_500L to "\"#get:uptime;\"", 4_000L to "\"#get:model;\"", 4_500L to "#get_mode:x;",
                5_000L to "#get_mode:y;", 5_500L to "#get_mode:z;", 6_000L to "#get_mode:w;", 6_500L to "#get_mode:u;",
                7_000L to "#get_mode:v;", 7_500L to "\"#get:mac;\"", 8_000L to "\"#get:uuid;\"",
                8_500L to "\"#get:features;\"", 9_000L to "\"#get:uptime;\"", 9_500L to "\"#get:model;\"",
            ),
            harness.writes(),
        )
        assertNull(harness.queue.nextTimerAtMs)
        assertEquals(ValueState.Failed(FailureCause.Timeout, 10_000L), harness.queue.values.state(ValueKey.Field(AdapterField.Model)))
    }

    @Test
    fun theIdRetryAsksOnlyForTheFieldsWithNoValue() {
        val genuine = QueueHarness.genuine()
        val harness = QueueHarness(respond = { command ->
            if (command == mac || command == GetField(AdapterField.Uuid)) genuine(command) else emptyList()
        })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(8_100L)
        val retry = harness.writes().filter { it.first >= 7_000L }
        assertEquals(listOf(7_000L to "\"#get:features;\"", 7_500L to "\"#get:uptime;\"", 8_000L to "\"#get:model;\""), retry)
        assertEquals(ValueState.Fetching(1, 7_000L), harness.queue.values.state(ValueKey.Field(AdapterField.Model)))
    }

    @Test
    fun theIdRetrySendsNoSecondQueryForAFieldThatIsStillInTheQueueWhenTheSlowAnswerComes() {
        // A slow adapter: the ack after 100 ms, the answer after 1900 ms.
        val genuine = QueueHarness.genuine()
        val harness = QueueHarness(respond = { command ->
            genuine(command).map { (_, text) -> 1_900L to text } + (100L to "\$0aa3;")
        })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(40_000L)
        AdapterField.idGroup.forEach { field ->
            assertEquals(1, harness.writeTexts().count { it == "\"#get:${field.getName};\"" }, field.name)
        }
        assertEquals(11, harness.writes().size)
        assertEquals("tda-example", harness.queue.values.model.value)
    }

    /** A slow adapter: the ack after 100 ms and the answer after 1900 ms. [features] gives the answers to the `features` queries. */
    private fun slowAdapter(features: (Int) -> List<Pair<Long, String>>): (AdapterCommand) -> List<Pair<Long, String>> {
        val genuine = QueueHarness.genuine()
        var featureQueries = 0
        return { command ->
            if (command == GetField(AdapterField.Features)) {
                features(featureQueries++)
            } else {
                genuine(command).map { (_, text) -> 1_900L to text } + (100L to "\$0aa3;")
            }
        }
    }

    @Test
    fun aQueryInTheQueueThatTimesOutAfterThe5000MsMarkGetsOneMoreQuery() {
        // The first "features" query goes out at 5800 and times out at 7900, after the mark at 7000.
        val harness = QueueHarness(respond = slowAdapter { count ->
            if (count == 0) listOf(100L to "\$0aa3;") else listOf(100L to "\$0aa3;", 1_900L to "?0a1;")
        })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(60_000L)
        val features = harness.writes().filter { it.second == "\"#get:features;\"" }
        assertEquals(listOf(5_800L, 23_100L), features.map { it.first })
        assertEquals(12, harness.writes().size)
        assertEquals(listOf("\"#get:uptime;\"", "\"#get:model;\""), harness.writeTexts().filter { it == "\"#get:uptime;\"" || it == "\"#get:model;\"" })
        assertEquals("\"#get:features;\"", harness.writeTexts().last())
        assertEquals(1L, harness.queue.values.features.value)
    }

    @Test
    fun aFieldGetsNeverMoreThanOneMoreQueryForEachLink() {
        // Each "features" query ends with "Bad data" 1500 ms after the write, the first one after the mark.
        val harness = QueueHarness(respond = slowAdapter { listOf(100L to "\$0aa3;", 1_500L to "!0a21;") })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(120_000L)
        val features = harness.writes().filter { it.second == "\"#get:features;\"" }
        assertEquals(2, features.size)
        assertEquals(5_800L, features.first().first)
        assertTrue(features.last().first > 7_300L)
        assertEquals(12, harness.writes().size)
        assertEquals(ValueState.Failed(FailureCause.Status(0x21), features.last().first + 1_500L), harness.queue.values.state(ValueKey.Field(AdapterField.Features)))
        // A legacy adapter answers nothing: each "id" field gets one more query, and no field gets a third.
        val legacy = QueueHarness()
        legacy.phase(LinkPhase.Connected)
        legacy.runUntil(120_000L)
        AdapterField.idGroup.forEach { field ->
            assertEquals(2, legacy.writeTexts().count { it == "\"#get:${field.getName};\"" }, field.name)
        }
    }

    @Test
    fun aChannelIsBusyWhileACommandOrAQueryOfThatChannelIsCurrentOrInTheQueue() {
        val harness = QueueHarness.afterStart()
        harness.respond = { emptyList() }
        harness.submit(GetField(AdapterField.Name))
        harness.submit(AdapterCommand.GetMode(InputChannel.U))
        harness.submit(ClearCounter(InputChannel.V))
        assertEquals(GetField(AdapterField.Name), harness.queue.currentCommand)
        assertEquals(setOf(InputChannel.U, InputChannel.V), harness.queue.busyChannels)
        harness.runUntil(3_500L)
        assertEquals(AdapterCommand.GetMode(InputChannel.U), harness.queue.currentCommand)
        assertEquals(setOf(InputChannel.U, InputChannel.V), harness.queue.busyChannels)
        harness.runUntil(4_000L)
        assertEquals(setOf(InputChannel.V), harness.queue.busyChannels)
        harness.runUntil(4_500L)
        assertEquals(emptySet(), harness.queue.busyChannels)
        // A mode write keeps its channel busy through the read-back.
        val genuine = QueueHarness.genuine()
        harness.respond = { command ->
            if (command is SetMode) listOf(10L to "\$0aa3;", 20L to "\$060;") else genuine(command)
        }
        harness.submit(SetMode(InputChannel.X, InputMode.Tachometer))
        harness.runUntil(4_520L)
        assertEquals(AdapterCommand.GetMode(InputChannel.X), harness.queue.queuedCommands.single())
        assertEquals(setOf(InputChannel.X), harness.queue.busyChannels)
        harness.runUntil(4_560L)
        assertEquals(emptySet(), harness.queue.busyChannels)
    }

    @Test
    fun anInfoWithNoRequestUpdatesTheAdapterValues() {
        val harness = QueueHarness.afterStart()
        harness.clearOutputs()
        assertNull(harness.queue.currentCommand)
        harness.receive("?03Other name;")
        assertEquals("Other name", harness.queue.values.name.value)
        assertEquals(emptyList(), harness.all<QueueOutput.Result>())
        assertEquals(harness.queue.values, harness.all<QueueOutput.ValuesChanged>().single().second.values)
    }

    @Test
    fun anInfoCompletesTheCurrentQueryEvenForADifferentField() {
        val harness = QueueHarness(respond = { if (it == mac) listOf(10L to "?03Other;") else emptyList() })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(2_010L)
        assertEquals(CommandResult.Completed, harness.resultOf(1))
        assertEquals("Other", harness.queue.values.name.value)
        assertNull(harness.queue.values.mac.value)
    }

    @Test
    fun anInfoAnswerWhileACommandIsCurrentLeavesTheCommandCurrent() {
        val modes = InputChannel.entries.associateWith { 2 }.toMutableMap()
        val genuine = QueueHarness.genuine(modes)
        val harness = QueueHarness.afterStart()
        harness.respond = { command ->
            if (command is SetMode) {
                modes[command.channel] = command.mode.id
                listOf(10L to "\$0aa3;", 20L to "?03Other;", 30L to "\$060;")
            } else {
                genuine(command)
            }
        }
        val id = harness.submit(SetMode(InputChannel.X, InputMode.Tachometer))
        harness.runUntil(3_020L)
        assertEquals("Other", harness.queue.values.name.value)
        assertEquals(SetMode(InputChannel.X, InputMode.Tachometer), harness.queue.currentCommand)
        assertNull(harness.resultOf(id))
        harness.runUntil(3_060L)
        assertEquals(listOf(3_000L to "#set_mode:x,4;", 3_050L to "#get_mode:x;"), harness.writes().filter { it.first >= 3_000L })
        assertEquals(CommandResult.Completed, harness.resultOf(id))
        assertEquals(3_060L, harness.resultTimeOf(id))
    }

    @Test
    fun anErrorFunctionThatKeepsTheRequestStartsANewFullWait() {
        val harness = QueueHarness.afterStart()
        harness.respond = { if (it is SetMode) listOf(10L to "!0603;") else emptyList() }
        val id = harness.submit(SetMode(InputChannel.X, InputMode.Tachometer))
        harness.runUntil(5_009L)
        assertNull(harness.resultOf(id))
        val failed = harness.all<QueueOutput.CommandFailed>().single()
        assertEquals(3_010L, failed.first)
        assertEquals("Command failed: Invalid argument (6:3)", failed.second.failure.message)
        harness.runUntil(5_010L)
        assertEquals(CommandResult.Failed(CommandFailure(6, 3, "Invalid argument")), harness.resultOf(id))
    }

    @Test
    fun badDataOnAQueryMarksTheFieldFailed() {
        val harness = QueueHarness(respond = { if (it == mac) listOf(10L to "!0a21;") else emptyList() })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(2_010L)
        assertEquals(CommandResult.Failed(CommandFailure(10, 33, "Bad data")), harness.resultOf(1))
        assertEquals(ValueState.Failed(FailureCause.Status(0x21), 2_010L), harness.queue.values.state(ValueKey.Field(AdapterField.Mac)))
        harness.runUntil(2_050L)
        assertEquals(2_050L to "\"#get:uuid;\"", harness.writes().last())
    }

    @Test
    fun anErrorOfAnotherSubsystemEndsAQuery() {
        val harness = QueueHarness(respond = { if (it == mac) listOf(10L to "\$0605;") else emptyList() })
        harness.phase(LinkPhase.Connected)
        harness.runUntil(2_010L)
        assertEquals(CommandResult.Failed(CommandFailure(6, 5, "Not supported")), harness.resultOf(1))
        // The query has no error function on this path: the field stays "Fetching".
        assertEquals(ValueState.Fetching(1, 2_000L), harness.queue.values.state(ValueKey.Field(AdapterField.Mac)))
    }

    @Test
    fun aChangeToDisconnectedClearsAll() {
        val harness = QueueHarness.afterStart()
        harness.respond = { emptyList() }
        val clear = harness.submit(ClearCounter(InputChannel.Y))
        val reference = harness.submit(RefAxis(InputChannel.X))
        val name = harness.submit(GetField(AdapterField.Name))
        harness.runUntil(3_100L)
        assertEquals(ClearCounter(InputChannel.Y), harness.queue.currentCommand)
        harness.clearOutputs()
        harness.phase(LinkPhase.Dropped)
        assertEquals(CommandResult.Cancelled, harness.resultOf(clear))
        assertEquals(CommandResult.Cancelled, harness.resultOf(reference))
        assertEquals(CommandResult.Cancelled, harness.resultOf(name))
        assertNull(harness.queue.currentCommand)
        assertEquals(emptyList(), harness.queue.queuedCommands)
        assertNull(harness.queue.nextTimerAtMs)
        assertEquals(emptyList(), harness.all<QueueOutput.Timer>())
        assertFalse(harness.queue.isActive)
        assertEquals(emptySet(), harness.queue.busyChannels)
        assertEquals(
            ValueState.Failed(FailureCause.SessionDropped, 3_100L),
            harness.queue.values.state(ValueKey.Field(AdapterField.Name)),
        )
        harness.runUntil(60_000L)
        assertEquals(emptyList(), harness.writes())
    }

    @Test
    fun aModeWriteAClearAndAReferenceFailAfter10000MsWithNoResult() {
        val harness = QueueHarness.afterStart()
        harness.phase(LinkPhase.Waiting)
        val write = harness.submit(SetMode(InputChannel.X, InputMode.Tachometer))
        val clear = harness.submit(ClearCounter(InputChannel.Y))
        val reference = harness.submit(RefAxis(InputChannel.Z))
        assertEquals(setOf(InputChannel.X, InputChannel.Y, InputChannel.Z), harness.queue.busyChannels)
        harness.runUntil(12_999L)
        assertNull(harness.resultOf(write))
        harness.runUntil(13_000L)
        assertEquals(CommandResult.NotConfirmed, harness.resultOf(write))
        assertEquals(CommandResult.NotConfirmed, harness.resultOf(clear))
        assertEquals(CommandResult.NotConfirmed, harness.resultOf(reference))
        assertEquals(emptySet(), harness.queue.busyChannels)
        // The requests that did not go out leave the queue.
        harness.clearOutputs()
        harness.phase(LinkPhase.Connected)
        harness.runUntil(20_000L)
        assertEquals(emptyList(), harness.writes())
    }

    @Test
    fun aModeWriteFailsWhenTheReadBackShowsTheOldMode() {
        val harness = QueueHarness.afterStart()
        val genuine = QueueHarness.genuine()
        harness.respond = { command ->
            if (command is SetMode) listOf(10L to "\$0aa3;", 20L to "\$060;") else genuine(command)
        }
        val id = harness.submit(SetMode(InputChannel.X, InputMode.Tachometer))
        harness.runUntil(4_000L)
        assertEquals(CommandResult.NotConfirmed, harness.resultOf(id))
        assertEquals(3_060L, harness.resultTimeOf(id))
        assertEquals(listOf(QueueOutput.RestartNeeded(InputChannel.X)), harness.all<QueueOutput.RestartNeeded>().map { it.second })
    }

    @Test
    fun theEndOfARequestClearsTheBusyFlagOfItsChannel() {
        val harness = QueueHarness.afterStart()
        harness.respond = { emptyList() }
        harness.clearOutputs()
        val id = harness.submit(ClearCounter(InputChannel.Y))
        assertEquals(setOf(InputChannel.Y), harness.all<QueueOutput.BusyChannels>().single().second.channels)
        harness.runUntil(3_500L)
        assertEquals(failedTimeout, harness.resultOf(id))
        assertEquals(emptySet(), harness.all<QueueOutput.BusyChannels>().last().second.channels)
        assertEquals(3_500L, harness.all<QueueOutput.BusyChannels>().last().first)
        // The clear shows its failure in the channel row.
        assertEquals("Command failed: Timeout (-1:256)", harness.all<QueueOutput.CommandFailed>().single().second.failure.message)
    }

    @Test
    fun aClearErrorShowsTheFailureText() {
        val harness = QueueHarness.afterStart()
        harness.respond = { if (it is ClearCounter) listOf(10L to "!0a21;") else emptyList() }
        val id = harness.submit(ClearCounter(InputChannel.Y))
        harness.runUntil(3_010L)
        assertEquals("Command failed: Bad data (10:33)", harness.all<QueueOutput.CommandFailed>().single().second.failure.message)
        assertEquals(CommandResult.Failed(CommandFailure(10, 33, "Bad data")), harness.resultOf(id))
    }

    @Test
    fun theDirtyAxesStatusIsDecodedStoredAndLoggedWithNoEffectOnTheQueue() {
        val harness = QueueHarness.afterStart()
        harness.respond = { emptyList() }
        harness.submit(GetField(AdapterField.Name))
        harness.runUntil(3_100L)
        harness.clearOutputs()
        harness.receive("\$3205;")
        assertEquals(QueueOutput.DirtyFlags(5, setOf(InputChannel.X, InputChannel.Z)), harness.all<QueueOutput.DirtyFlags>().single().second)
        assertEquals("Dirty axes mask 0x05: x, z", harness.all<QueueOutput.Log>().single().second.text)
        assertEquals(setOf(InputChannel.X, InputChannel.Z), harness.queue.values.dirty.value)
        assertEquals(GetField(AdapterField.Name), harness.queue.currentCommand)
        assertEquals(emptyList(), harness.all<QueueOutput.Result>())
    }

    @Test
    fun aSmallerUptimeTellsThatTheAdapterRestarted() {
        val harness = QueueHarness.afterStart()
        harness.clearOutputs()
        harness.receive("?0905;")
        assertEquals(QueueOutput.AdapterRestartDetected(3600L, 5L), harness.all<QueueOutput.AdapterRestartDetected>().single().second)
        val values = harness.queue.values
        assertNull(values.name.value)
        assertNull(values.features.value)
        assertTrue(values.modes.values.all { it.value == null })
        assertEquals("tda-example", values.model.value)
        assertEquals("246f28a1b2c3", values.mac.value)
        assertEquals(5L, values.uptime.value)
    }

    @Test
    fun theStartQueriesGoOutOnceForEachLink() {
        val harness = QueueHarness.afterStart()
        harness.phase(LinkPhase.Waiting)
        harness.runUntil(10_000L)
        harness.phase(LinkPhase.Connected)
        harness.runUntil(30_000L)
        assertEquals(11, harness.writes().size)
    }

    @Test
    fun aNewLinkAfterADropGetsTheStartQueriesAgain() {
        val harness = QueueHarness.afterStart()
        harness.runUntil(4_000L)
        harness.phase(LinkPhase.Dropped)
        harness.runUntil(4_100L)
        harness.phase(LinkPhase.Waiting)
        harness.runUntil(5_000L)
        harness.phase(LinkPhase.Connected)
        harness.runUntil(6_999L)
        assertEquals(11, harness.writes().size)
        harness.runUntil(8_000L)
        assertEquals(7_000L to "\"#get:mac;\"", harness.writes()[11])
        assertEquals(22, harness.writes().size)
    }

    @Test
    fun withTwoWayOffTheQueueWritesNothingAndTheValuesStayEmpty() {
        val harness = QueueHarness(CommandQueue(twoWay = false), QueueHarness.genuine())
        harness.phase(LinkPhase.Connected)
        harness.runUntil(60_000L)
        harness.openDetails()
        val id = harness.submit(SetMode(InputChannel.X, InputMode.Tachometer))
        harness.receive("?03Name;")
        harness.receive("\$3205;")
        harness.runUntil(120_000L)
        assertEquals(emptyList(), harness.writes())
        assertEquals(emptyList(), harness.all<QueueOutput.Timer>())
        assertEquals(CommandResult.NotSent, harness.resultOf(id))
        assertEquals(AdapterValues(), harness.queue.values)
        assertEquals(1, harness.all<QueueOutput.DirtyFlags>().size)
    }

    @Test
    fun eachSessionHasItsOwnParserAndQueue() {
        val first = QueueHarness(respond = QueueHarness.genuine())
        val second = QueueHarness()
        first.phase(LinkPhase.Connected)
        second.phase(LinkPhase.Connected)
        first.runUntil(3_000L)
        second.runUntil(3_000L)
        assertEquals(11, first.writes().size)
        assertEquals(listOf(2_000L, 2_500L, 3_000L), second.writes().map { it.first })
        assertEquals("tda-example", first.queue.values.model.value)
        assertNull(second.queue.values.model.value)
        val parserA = AdapterParser()
        val parserB = AdapterParser()
        parserA.feed("@3E8;X12", 1_000L)
        assertEquals(listOf(AdapterMessage.AxisCount('Y', 5, 2_000L)), parserB.feed("Y5;", 2_000L))
        assertNull(parserB.timeBase.offsetMs)
    }

    @Test
    fun theValuesComeBeforeTheResultThatNeedsThem() {
        val harness = QueueHarness(respond = QueueHarness.genuine())
        harness.phase(LinkPhase.Connected)
        harness.runUntil(2_009L)
        val outputs = harness.queue.onMessage(AdapterMessage.Info(5, "246f28a1b2c3"), 2_010L)
        assertTrue(outputs.first() is QueueOutput.ValuesChanged)
        assertTrue(outputs.last() is QueueOutput.Timer)
        assertEquals(CommandResult.Completed, outputs.filterIsInstance<QueueOutput.Result>().single().result)
    }

    @Test
    fun theBelByteDoesNotGoThroughTheQueue() {
        val queue = CommandQueue()
        val failure = runCatching { queue.submit(AdapterCommand.Bel, 0L) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun theQueueIsActive2000MsAfterTheFirstConnectedOnly() {
        val harness = QueueHarness()
        harness.phase(LinkPhase.Connected)
        assertFalse(harness.queue.isActive)
        assertEquals(2_000L, harness.queue.nextTimerAtMs)
        harness.runUntil(1_000L)
        harness.phase(LinkPhase.Waiting)
        harness.runUntil(5_000L)
        assertEquals(emptyList(), harness.writes())
        harness.phase(LinkPhase.Connected)
        assertTrue(harness.queue.isActive)
        assertEquals(listOf(5_000L to "\"#get:mac;\""), harness.writes())
        assertEquals(5_000L, harness.queue.startedAtMs)
    }
}
