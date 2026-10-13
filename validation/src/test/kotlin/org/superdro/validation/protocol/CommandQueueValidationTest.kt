package org.superdro.validation.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.protocol.ChannelModes
import org.superdro.core.protocol.CommandFailure
import org.superdro.core.protocol.CommandQueue
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.FailureCause
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.LinkPhase
import org.superdro.core.protocol.Lifetime
import org.superdro.core.protocol.QueueOutput
import org.superdro.core.protocol.ReferenceState
import org.superdro.core.protocol.ValueKey
import org.superdro.core.protocol.ValueState
import org.superdro.validation.protocol.QueueDriver.Companion.DETAILS_QUERIES
import org.superdro.validation.protocol.QueueDriver.Companion.FEATURES
import org.superdro.validation.protocol.QueueDriver.Companion.MAC
import org.superdro.validation.protocol.QueueDriver.Companion.START_QUERIES
import org.superdro.validation.protocol.QueueDriver.Companion.UUID

/**
 * The command queue rules and the typical answers, the request handlers, the sequences on
 * the wire, and the register model. The queue is a pure state machine, so the tests give it the
 * times directly.
 */
class CommandQueueValidationTest {

    /** A queue whose start queries are complete. The queue is idle from 2551 ms. */
    private fun idle(): QueueDriver {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.runWithAdapter(10_000L, QueueDriver::goodAdapter)
        assertEquals(START_QUERIES, driver.texts)
        return driver
    }

    private fun QueueDriver.textsAfter(count: Int): List<String> = texts.drop(count)

    // The start.

    @Test
    fun queue_becomes_active_2000_ms_after_the_first_connected() {
        val driver = QueueDriver()
        driver.connected(1_000L)
        driver.advanceTo(2_999L)
        assertEquals(emptyList(), driver.writes)
        driver.advanceTo(3_000L)
        assertEquals(listOf(QueueDriver.Wire(3_000L, MAC, 1)), driver.writes)
    }

    @Test
    fun queue_is_not_active_in_other_states() {
        val driver = QueueDriver()
        driver.advanceTo(5_000L)
        assertEquals(emptyList(), driver.writes)
        driver.connected(6_000L)
        driver.phase(7_500L, LinkPhase.Waiting)
        driver.advanceTo(9_000L)
        assertEquals(emptyList(), driver.writes)
        // The start time (8000 ms) passed: at the next Connected the queue sends at once.
        driver.connected(9_500L)
        assertEquals(listOf(QueueDriver.Wire(9_500L, MAC, 1)), driver.writes)
    }

    // The send, the gap, and the start queries.

    @Test
    fun start_queries_go_out_one_at_a_time_in_order_with_a_gap_of_50_ms() {
        val driver = idle()
        assertEquals((0 until 11).map { 2_000L + 50L * it }, driver.writes.map { it.timeMs })
        assertTrue(driver.writes.all { it.transmission == 1 })
    }

    @Test
    fun one_request_at_a_time_and_50_ms_since_the_last_queue_write() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.advanceTo(2_005L)
        assertEquals(listOf(MAC), driver.texts)
        driver.receive(2_010L, "?05246f28a1b2c3;")
        driver.advanceTo(2_049L)
        assertEquals(listOf(MAC), driver.texts)
        driver.advanceTo(2_050L)
        assertEquals(listOf(MAC, UUID), driver.texts)
        driver.receive(2_300L, "?02c0ffee00-1234-4abc-8def-0123456789ab;")
        assertEquals(QueueDriver.Wire(2_300L, FEATURES, 1), driver.writes.last())
    }

    // The timeout.

    @Test
    fun no_answer_in_500_ms_calls_the_error_function_with_timeout_then_sends_the_next() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.advanceTo(2_499L)
        assertEquals(listOf(MAC), driver.texts)
        driver.advanceTo(2_500L)
        assertEquals(listOf(QueueDriver.Wire(2_000L, MAC, 1), QueueDriver.Wire(2_500L, UUID, 1)), driver.writes)
        val result = driver.results.single { it.second.command == AdapterCommand.GetField(AdapterField.Mac) }
        assertEquals(2_500L, result.first)
        assertEquals(CommandResult.Failed(CommandFailure.TIMEOUT), result.second.result)
        val state = driver.queue.values.mac.state
        assertIs<ValueState.Failed>(state)
        assertEquals(FailureCause.Timeout, state.cause)
    }

    // The ack and the full waits.

    @Test
    fun ack_restarts_the_wait_with_the_full_wait_of_2000_ms() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.receive(2_100L, "\$0aa3;")
        driver.advanceTo(4_099L)
        assertEquals(listOf(MAC), driver.texts)
        driver.advanceTo(4_100L)
        assertEquals(listOf(MAC, UUID), driver.texts)
        assertEquals(4_100L, driver.writes.last().timeMs)
    }

    @Test
    fun typical_answer_ack_then_the_mac_is_stored() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.receive(2_100L, "\$0aa3;")
        driver.receive(3_000L, "?0530aea4a1b2c3;")
        assertEquals("30aea4a1b2c3", driver.queue.values.mac.value)
        assertEquals(QueueDriver.Wire(3_000L, UUID, 1), driver.writes.last())
    }

    @Test
    fun ref_axis_full_wait_is_1500_ms() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.RefAxis(InputChannel.X))
        assertEquals("#ref_axis:x,15;", driver.texts.last())
        driver.receive(20_010L, "\$0aa3;")
        driver.advanceTo(21_509L)
        assertNull(driver.resultOf(id))
        driver.advanceTo(21_510L)
        assertEquals(CommandResult.Failed(CommandFailure.TIMEOUT), driver.resultOf(id))
    }

    @Test
    fun get_sig_full_wait_is_10000_ms() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.GetSig(1L))
        driver.receive(20_010L, "\$0aa3;")
        driver.advanceTo(30_009L)
        assertNull(driver.resultOf(id))
        driver.advanceTo(30_010L)
        assertIs<CommandResult.Failed>(driver.resultOf(id))
    }

    @Test
    fun full_wait_of_a_query_and_of_a_command_is_2000_ms() {
        val driver = idle()
        val query = driver.submit(20_000L, AdapterCommand.GetField(AdapterField.Name))
        driver.receive(20_100L, "\$0aa3;")
        driver.advanceTo(22_099L)
        assertNull(driver.resultOf(query))
        driver.advanceTo(22_100L)
        assertIs<CommandResult.Failed>(driver.resultOf(query))
        val command = driver.submit(30_000L, AdapterCommand.SetMode(InputChannel.Y, InputMode.Bin6))
        driver.receive(30_100L, "\$0aa3;")
        driver.advanceTo(32_099L)
        assertNull(driver.resultOf(command))
        driver.advanceTo(32_100L)
        assertNotEquals(null, driver.resultOf(command))
        assertNotEquals(CommandResult.Completed, driver.resultOf(command))
    }

    // The info messages.

    @Test
    fun info_with_no_request_is_stored() {
        val driver = QueueDriver()
        driver.receive(500L, "?03Mill;")
        assertEquals("Mill", driver.queue.values.name.value)
        assertIs<ValueState.Reported>(driver.queue.values.name.state)
    }

    @Test
    fun info_while_a_command_is_current_keeps_the_command() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer))
        driver.receive(20_010L, "?03Mill;")
        assertEquals("Mill", driver.queue.values.name.value)
        assertEquals(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer), driver.queue.currentCommand)
        assertNull(driver.resultOf(id))
    }

    @Test
    fun info_completes_the_current_query_also_for_a_different_field() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.GetField(AdapterField.Name))
        driver.receive(20_010L, "?04tda-x;")
        assertEquals(CommandResult.Completed, driver.resultOf(id))
        assertEquals("tda-x", driver.queue.values.model.value)
        assertNull(driver.queue.currentCommand)
    }

    // The dirty axes.

    @Test
    fun dirty_axes_example_3205_marks_x_and_z_with_no_effect_on_the_queue() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.receive(2_010L, "\$3205;")
        val flags = driver.all<QueueOutput.DirtyFlags>().single()
        assertEquals(0x05, flags.mask)
        assertEquals(setOf(InputChannel.X, InputChannel.Z), flags.channels)
        assertEquals(setOf(InputChannel.X, InputChannel.Z), driver.queue.values.dirty.value)
        assertEquals(AdapterCommand.GetField(AdapterField.Mac), driver.queue.currentCommand)
        driver.advanceTo(2_500L)
        assertEquals(listOf(MAC, UUID), driver.texts)
        assertEquals(2_500L, driver.writes.last().timeMs)
    }

    @Test
    fun dirty_mask_bit_n_is_the_input_with_index_n() {
        assertEquals(setOf(InputChannel.U), InputChannel.dirtyChannels(0x10))
        assertEquals(setOf(InputChannel.V), InputChannel.dirtyChannels(0x20))
        assertEquals(setOf(InputChannel.W), InputChannel.dirtyChannels(0x08))
        assertEquals(InputChannel.entries.toSet(), InputChannel.dirtyChannels(0x3F))
        assertEquals(emptySet(), InputChannel.dirtyChannels(0x00))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), InputChannel.entries.map { it.index })
    }

    // The error path.

    @Test
    fun busy_resends_after_100_ms_and_200_ms_with_a_new_first_wait() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.receive(2_010L, "\$0a20;")
        driver.advanceTo(2_109L)
        assertEquals(1, driver.writes.size)
        driver.advanceTo(2_110L)
        assertEquals(QueueDriver.Wire(2_110L, MAC, 2), driver.writes.last())
        driver.receive(2_120L, "!0a20;")
        driver.advanceTo(2_319L)
        assertEquals(2, driver.writes.size)
        driver.advanceTo(2_320L)
        assertEquals(QueueDriver.Wire(2_320L, MAC, 3), driver.writes.last())
        // The resend starts a new 500 ms wait.
        driver.advanceTo(2_819L)
        assertEquals(3, driver.writes.size)
        driver.advanceTo(2_820L)
        assertEquals(QueueDriver.Wire(2_820L, UUID, 1), driver.writes.last())
    }

    @Test
    fun busy_resend_has_a_new_500_ms_wait() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.receive(2_010L, "\$0a20;")
        driver.advanceTo(2_609L)
        assertEquals(listOf(MAC, MAC), driver.texts)
        driver.advanceTo(2_610L)
        assertEquals(listOf(MAC, MAC, UUID), driver.texts)
    }

    @Test
    fun busy_three_times_ends_the_request() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.receive(2_010L, "\$0a20;")
        driver.receive(2_120L, "\$0a20;")
        driver.receive(2_330L, "\$0a20;")
        val result = driver.results.single { it.second.command == AdapterCommand.GetField(AdapterField.Mac) }
        assertEquals(2_330L, result.first)
        val failed = assertIs<CommandResult.Failed>(result.second.result)
        assertEquals(0x0A, failed.failure.subsystem)
        assertEquals(0x20, failed.failure.code)
        // No error function: the field is not marked "Failed".
        assertFalse(driver.queue.values.mac.state is ValueState.Failed)
        driver.advanceTo(2_370L)
        assertEquals(listOf(MAC, MAC, MAC, UUID), driver.texts)
        assertEquals(2_370L, driver.writes.last().timeMs)
    }

    @Test
    fun bad_data_and_failed_call_the_error_function_of_the_query() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.receive(2_010L, "!0a21;")
        val mac = driver.queue.values.mac.state
        assertIs<ValueState.Failed>(mac)
        assertEquals(FailureCause.Status(0x21), mac.cause)
        driver.advanceTo(2_050L)
        assertEquals(listOf(MAC, UUID), driver.texts)
        driver.receive(2_060L, "\$0a01;")
        val uuid = driver.queue.values.uuid.state
        assertIs<ValueState.Failed>(uuid)
        assertEquals(FailureCause.Status(0x01), uuid.cause)
    }

    @Test
    fun rx_with_an_other_code_clears_the_request_with_no_error_function() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.receive(2_010L, "!0a05;")
        assertNull(driver.queue.currentCommand)
        assertFalse(driver.queue.values.mac.state is ValueState.Failed)
        driver.advanceTo(2_050L)
        assertEquals(listOf(MAC, UUID), driver.texts)
    }

    @Test
    fun error_function_that_keeps_the_request_starts_a_new_full_wait() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer))
        driver.receive(20_100L, "!0603;")
        val failed = driver.all<QueueOutput.CommandFailed>().single()
        assertEquals("Command failed: Invalid argument (6:3)", failed.failure.message)
        assertEquals(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer), driver.queue.currentCommand)
        driver.advanceTo(22_099L)
        assertNull(driver.resultOf(id))
        driver.advanceTo(22_100L)
        assertIs<CommandResult.Failed>(driver.resultOf(id))
    }

    @Test
    fun status_with_a_code_from_1_to_159_uses_the_error_path() {
        val driver = idle()
        driver.submit(20_000L, AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer))
        driver.receive(20_100L, "\$0603;")
        assertEquals("Command failed: Invalid argument (6:3)", driver.all<QueueOutput.CommandFailed>().single().failure.message)
        assertEquals(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer), driver.queue.currentCommand)
    }

    @Test
    fun set_mode_with_rx_bad_data_is_not_handled() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer))
        driver.receive(20_100L, "!0a21;")
        assertIs<CommandResult.Failed>(driver.resultOf(id))
        assertNull(driver.queue.currentCommand)
    }

    @Test
    fun other_subsystem_for_a_query_clears_the_request() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.GetMode(InputChannel.Y))
        driver.receive(20_100L, "!0603;")
        assertIs<CommandResult.Failed>(driver.resultOf(id))
        assertNull(driver.queue.currentCommand)
        assertTrue(driver.all<QueueOutput.CommandFailed>().isEmpty())
    }

    @Test
    fun error_with_no_current_request_goes_to_the_channel_handlers() {
        val driver = idle()
        driver.receive(20_000L, "!7816;")
        assertEquals(QueueOutput.Reference(InputChannel.X, ReferenceState.TimedOut), driver.all<QueueOutput.Reference>().single())
        assertEquals(ReferenceState.TimedOut, driver.queue.values.references.getValue(InputChannel.X).value)
    }

    // The request handlers.

    @Test
    fun handler_clear_counter_completes_on_the_ack() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.ClearCounter(InputChannel.X))
        assertEquals("#clear_counter:x;", driver.texts.last())
        driver.receive(20_010L, "\$0aa3;")
        assertEquals(CommandResult.Completed, driver.resultOf(id))
    }

    @Test
    fun handler_clear_counter_error_shows_the_text_and_is_not_handled() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.ClearCounter(InputChannel.X))
        driver.receive(20_010L, "!0a21;")
        val shown = driver.all<QueueOutput.CommandFailed>().single()
        assertTrue(shown.failure.message.startsWith("Command failed: Bad data ("), shown.failure.message)
        assertIs<CommandResult.Failed>(driver.resultOf(id))
    }

    @Test
    fun handler_reboot_completes_on_the_ack_and_starts_the_restart_250_and_2000_ms() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.Reboot)
        assertEquals("#reboot:1000;", driver.texts.last())
        driver.receive(20_005L, "\$0aa3;")
        assertEquals(CommandResult.Completed, driver.resultOf(id))
        assertEquals(QueueOutput.RestartAdapter(20_005L, 20_255L, 22_005L), driver.all<QueueOutput.RestartAdapter>().single())
    }

    @Test
    fun handler_ref_axis_in_progress_and_the_ends() {
        for ((code, state) in listOf("a0" to ReferenceState.InProgress, "aa" to ReferenceState.InProgress, "a1" to ReferenceState.Finished, "a9" to ReferenceState.Cancelled)) {
            val driver = idle()
            val id = driver.submit(20_000L, AdapterCommand.RefAxis(InputChannel.X))
            driver.receive(20_010L, "\$0aa3;")
            driver.receive(20_020L, "\$78$code;")
            assertEquals(CommandResult.Completed, driver.resultOf(id), code)
            assertEquals(listOf(QueueOutput.Reference(InputChannel.X, state)), driver.all<QueueOutput.Reference>(), code)
        }
    }

    @Test
    fun handler_ref_axis_user_timeout_completes_by_status_and_by_error() {
        for (text in listOf("!7816;", "\$7816;")) {
            val driver = idle()
            val id = driver.submit(20_000L, AdapterCommand.RefAxis(InputChannel.X))
            driver.receive(20_010L, text)
            assertEquals(CommandResult.Completed, driver.resultOf(id), text)
            assertEquals(listOf(QueueOutput.Reference(InputChannel.X, ReferenceState.TimedOut)), driver.all<QueueOutput.Reference>(), text)
        }
    }

    @Test
    fun handler_ref_axis_other_code_on_its_subsystem_shows_the_text_and_does_not_complete() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.RefAxis(InputChannel.X))
        driver.receive(20_010L, "\$78a2;")
        assertTrue(driver.all<QueueOutput.CommandFailed>().single().failure.message.startsWith("Command failed: Ready ("))
        assertNull(driver.resultOf(id))
        assertEquals(AdapterCommand.RefAxis(InputChannel.X), driver.queue.currentCommand)
    }

    @Test
    fun handler_ref_axis_status_of_an_other_channel_does_not_complete() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.RefAxis(InputChannel.X))
        driver.receive(20_010L, "\$79a0;")
        assertNull(driver.resultOf(id))
        assertEquals(listOf(QueueOutput.Reference(InputChannel.Y, ReferenceState.InProgress)), driver.all<QueueOutput.Reference>())
    }

    // A link drop and the busy flag.

    @Test
    fun a_drop_clears_the_queue_the_current_request_and_all_timers() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.advanceTo(2_000L)
        val clear = driver.submit(2_010L, AdapterCommand.ClearCounter(InputChannel.X))
        assertTrue(InputChannel.X in driver.queue.busyChannels)
        driver.phase(2_020L, LinkPhase.Dropped)
        assertEquals(CommandResult.Cancelled, driver.resultOf(clear))
        assertEquals(12, driver.results.count { it.second.result == CommandResult.Cancelled })
        assertNull(driver.queue.currentCommand)
        assertEquals(emptyList(), driver.queue.queuedCommands)
        assertNull(driver.queue.nextTimerAtMs)
        assertEquals(emptySet(), driver.queue.busyChannels)
        driver.advanceTo(60_000L)
        assertEquals(listOf(MAC), driver.texts)
        // The register model: each "Fetching" value is "Failed" now.
        val values = driver.queue.values
        listOf(values.mac, values.uuid, values.features, values.uptime, values.model).forEach { value -> assertIs<ValueState.Failed>(value.state) }
        InputChannel.entries.forEach { channel -> assertIs<ValueState.Failed>(values.modes.getValue(channel).state) }
    }

    @Test
    fun d10_the_end_of_a_request_clears_the_busy_flag_of_its_channel() {
        val driver = idle()
        driver.submit(20_000L, AdapterCommand.ClearCounter(InputChannel.Y))
        assertEquals(setOf(InputChannel.Y), driver.queue.busyChannels)
        driver.advanceTo(20_500L)
        assertEquals(emptySet(), driver.queue.busyChannels)
        driver.submit(21_000L, AdapterCommand.ClearCounter(InputChannel.Z))
        driver.receive(21_010L, "!0a21;")
        assertEquals(emptySet(), driver.queue.busyChannels)
    }

    // Two-way communication off.

    @Test
    fun two_way_off_writes_nothing_and_the_values_stay_empty() {
        val driver = QueueDriver(CommandQueue(twoWay = false))
        driver.connected(0L)
        driver.advanceTo(60_000L)
        assertEquals(emptyList(), driver.writes)
        val id = driver.submit(60_000L, AdapterCommand.GetField(AdapterField.Name))
        assertEquals(CommandResult.NotSent, driver.resultOf(id))
        val reboot = driver.submit(60_000L, AdapterCommand.Reboot)
        assertEquals(CommandResult.NotSent, driver.resultOf(reboot))
        driver.receive(60_010L, "?05246f28a1b2c3;")
        assertEquals(AdapterValues(), driver.queue.values)
        driver.advanceTo(120_000L)
        assertEquals(emptyList(), driver.writes)
    }

    // Once for each link, and the "id" group retry.

    @Test
    fun start_queries_go_out_once_for_each_link() {
        val driver = idle()
        driver.phase(10_000L, LinkPhase.Waiting)
        driver.connected(11_000L)
        driver.runWithAdapter(60_000L, QueueDriver::goodAdapter)
        assertEquals(START_QUERIES, driver.texts)
    }

    @Test
    fun id_field_with_no_value_5000_ms_after_the_queue_start_gets_one_more_query() {
        var featureQueries = 0
        val answer: (String) -> String? = { text ->
            if (text == FEATURES && featureQueries++ == 0) null else QueueDriver.goodAdapter(text)
        }
        val driver = QueueDriver()
        driver.connected(0L)
        driver.runWithAdapter(6_999L, answer)
        assertEquals(START_QUERIES, driver.texts)
        driver.runWithAdapter(60_000L, answer)
        assertEquals(START_QUERIES + FEATURES, driver.texts)
        assertEquals(7_000L, driver.writes.last().timeMs)
        assertEquals(1L, driver.queue.values.features.value)
    }

    @Test
    fun legacy_adapter_start_queries_time_out_and_each_id_field_gets_one_more_query() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.runWithAdapter(60_000L) { null }
        val idGroup = START_QUERIES.take(5)
        assertEquals(START_QUERIES, driver.texts.take(11))
        assertEquals(idGroup, driver.texts.drop(11))
        assertEquals((0 until 11).map { 2_000L + 500L * it }, driver.writes.take(11).map { it.timeMs })
        assertTrue(driver.writes.drop(11).all { it.timeMs >= 7_000L })
    }

    @Test
    fun no_mac_retry_after_100_200_and_300_ms() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.receive(2_010L, "!0a21;")
        driver.advanceTo(2_400L)
        assertEquals(listOf(MAC, UUID), driver.texts)
    }

    // The Adapter Details screen.

    @Test
    fun adapter_details_queues_the_secondary_group_then_the_six_modes() {
        val driver = idle()
        driver.openDetails(20_000L)
        driver.runWithAdapter(30_000L, QueueDriver::goodAdapter)
        assertEquals(DETAILS_QUERIES, driver.textsAfter(11))
        assertEquals((0 until 10).map { 20_000L + 50L * it }, driver.writes.drop(11).map { it.timeMs })
        val values = driver.queue.values
        assertEquals("Mill adapter", values.name.value)
        assertEquals("1.2.3", values.version.value)
        assertEquals("b42", values.build.value)
        assertEquals(0x6955b900L, values.date.value)
    }

    @Test
    fun before_a_get_mode_the_mode_is_unknown_and_the_supported_list_is_empty() {
        val driver = idle()
        assertEquals(InputMode.QuadratureAB, driver.queue.values.modes.getValue(InputChannel.X).value!!.current)
        driver.openDetails(20_000L)
        driver.runWithAdapter(20_199L, QueueDriver::goodAdapter)
        assertEquals(InputMode.QuadratureAB, driver.queue.values.modes.getValue(InputChannel.X).value!!.current)
        driver.advanceTo(20_200L)
        assertEquals("#get_mode:x;", driver.texts.last())
        val modes = driver.queue.values.modes.getValue(InputChannel.X).value!!
        assertEquals(InputMode.Unknown, modes.current)
        assertEquals(emptyList(), modes.supported)
    }

    @Test
    fun change_to_connected_while_the_screen_is_open_queues_the_group_again() {
        val driver = idle()
        driver.openDetails(20_000L)
        driver.runWithAdapter(25_000L, QueueDriver::goodAdapter)
        driver.phase(26_000L, LinkPhase.Waiting)
        driver.connected(27_000L)
        driver.runWithAdapter(40_000L, QueueDriver::goodAdapter)
        assertEquals(DETAILS_QUERIES + DETAILS_QUERIES, driver.textsAfter(11))
    }

    @Test
    fun a_field_that_is_fetching_for_less_than_5000_ms_gets_no_second_query() {
        val driver = idle()
        driver.submit(20_000L, AdapterCommand.GetSig(1L))
        driver.receive(20_001L, "\$0aa3;")
        driver.openDetails(20_010L)
        assertEquals(DETAILS_QUERIES.size, driver.queue.queuedCommands.size)
        driver.openDetails(21_000L)
        assertEquals(DETAILS_QUERIES.size, driver.queue.queuedCommands.size)
        assertEquals(driver.queue.queuedCommands.toSet().size, driver.queue.queuedCommands.size)
    }

    // The result limits above the queue.

    @Test
    fun mode_write_clear_and_reference_fail_when_no_result_comes_in_10000_ms() {
        for (command in listOf(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer), AdapterCommand.ClearCounter(InputChannel.X), AdapterCommand.RefAxis(InputChannel.X))) {
            val driver = QueueDriver()
            val id = driver.submit(100L, command)
            driver.advanceTo(10_099L)
            assertNull(driver.resultOf(id), command.text)
            driver.advanceTo(10_100L)
            val result = driver.resultOf(id)
            assertNotEquals(null, result, command.text)
            assertNotEquals(CommandResult.Completed, result, command.text)
            assertEquals(emptyList(), driver.writes)
        }
    }

    @Test
    fun change_an_input_mode_with_the_read_back() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer))
        assertEquals("#set_mode:x,4;", driver.texts.last())
        driver.receive(20_010L, "\$0aa3;")
        driver.receive(20_020L, "\$060;")
        assertEquals(listOf(QueueOutput.RestartNeeded(InputChannel.X)), driver.all<QueueOutput.RestartNeeded>())
        driver.advanceTo(20_050L)
        assertEquals("#get_mode:x;", driver.texts.last())
        assertNull(driver.resultOf(id))
        driver.receive(20_060L, "?01x000f0004;")
        assertEquals(CommandResult.Completed, driver.resultOf(id))
        assertEquals(InputMode.Tachometer, driver.queue.values.modes.getValue(InputChannel.X).value!!.current)
    }

    @Test
    fun mode_write_fails_when_the_read_back_shows_an_other_mode() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer))
        driver.receive(20_010L, "\$060;")
        driver.receive(20_060L, "?01x000f0002;")
        val result = driver.resultOf(id)
        assertNotEquals(null, result)
        assertNotEquals(CommandResult.Completed, result)
    }

    @Test
    fun error_case_shows_command_failed_invalid_argument_6_3() {
        val driver = idle()
        driver.submit(20_000L, AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer))
        driver.receive(20_010L, "!0603;")
        assertEquals("Command failed: Invalid argument (6:3)", driver.all<QueueOutput.CommandFailed>().single().failure.message)
    }

    @Test
    fun reference_mark_search() {
        val driver = idle()
        val id = driver.submit(20_000L, AdapterCommand.RefAxis(InputChannel.X))
        driver.receive(20_010L, "\$0aa3;")
        driver.receive(20_500L, "\$78a0;")
        assertEquals(CommandResult.Completed, driver.resultOf(id))
        driver.receive(25_000L, "\$78a1;")
        assertEquals(
            listOf(QueueOutput.Reference(InputChannel.X, ReferenceState.InProgress), QueueOutput.Reference(InputChannel.X, ReferenceState.Finished)),
            driver.all<QueueOutput.Reference>(),
        )
        assertEquals(ReferenceState.Finished, driver.queue.values.references.getValue(InputChannel.X).value)
    }

    @Test
    fun other_ends_of_the_search() {
        for ((text, state) in listOf("\$78a9;" to ReferenceState.Cancelled, "\$7816;" to ReferenceState.TimedOut, "!7816;" to ReferenceState.TimedOut)) {
            val driver = idle()
            driver.receive(20_000L, text)
            assertEquals(listOf(QueueOutput.Reference(InputChannel.X, state)), driver.all<QueueOutput.Reference>(), text)
        }
        for ((text, channel) in listOf("\$77a0;" to InputChannel.W, "\$79a0;" to InputChannel.Y, "\$7aa0;" to InputChannel.Z)) {
            val driver = idle()
            driver.receive(20_000L, text)
            assertEquals(listOf(QueueOutput.Reference(channel, ReferenceState.InProgress)), driver.all<QueueOutput.Reference>(), text)
        }
    }

    // The connect sequence at the queue.

    @Test
    fun connect_and_start_queries_with_the_answers_of_the_sequence() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.runWithAdapter(10_000L) { text -> if (text == MAC) "\$0aa3;?05246f28a1b2c3;" else QueueDriver.goodAdapter(text) }
        assertEquals(START_QUERIES, driver.texts)
        val values = driver.queue.values
        assertEquals("246f28a1b2c3", values.mac.value)
        assertEquals("c0ffee00-1234-4abc-8def-0123456789ab", values.uuid.value)
        assertEquals(1L, values.features.value)
        assertEquals(0xe10L, values.uptime.value)
        assertEquals("tda-example", values.model.value)
        val x = values.modes.getValue(InputChannel.X).value!!
        assertEquals(listOf(InputMode.Disabled, InputMode.QuadratureAB, InputMode.Tachometer, InputMode.DigitalProbe), x.supported)
        assertEquals(InputMode.QuadratureAB, x.current)
        assertEquals("24:6f:28:a1:b2:c5", values.bluetoothId)
    }

    // The register model.

    @Test
    fun fetching_rule_5000_ms_and_the_attempt_count() {
        val key = ValueKey.Field(AdapterField.Name)
        var values = AdapterValues().fetching(key, 1_000L)
        assertEquals(ValueState.Fetching(1, 1_000L), values.state(key))
        assertFalse(values.shouldQuery(key, 5_999L))
        assertTrue(values.shouldQuery(key, 6_000L))
        values = values.fetching(key, 6_000L)
        assertEquals(ValueState.Fetching(2, 6_000L), values.state(key))
        assertTrue(AdapterValues().shouldQuery(key, 0L))
    }

    @Test
    fun value_with_no_command_is_unsupported() {
        for (key in listOf(ValueKey.Reference(InputChannel.X), ValueKey.Dirty)) {
            assertNull(key.command)
            assertFalse(AdapterValues().shouldQuery(key, 0L))
            assertEquals(ValueState.Failed(FailureCause.Unsupported, 5L), AdapterValues().read(key, 5L).state(key))
        }
    }

    @Test
    fun smaller_uptime_clears_the_values_that_are_not_constant() {
        val driver = QueueDriver()
        driver.connected(0L)
        driver.runWithAdapter(10_000L, QueueDriver::goodAdapter)
        driver.openDetails(20_000L)
        driver.runWithAdapter(30_000L, QueueDriver::goodAdapter)
        driver.receive(30_000L, "\$3205;")
        val before = driver.queue.values
        driver.receive(40_000L, "?09a;")
        assertEquals(listOf(QueueOutput.AdapterRestartDetected(0xe10L, 0xaL)), driver.all<QueueOutput.AdapterRestartDetected>())
        val after = driver.queue.values
        assertEquals(before.uuid, after.uuid)
        assertEquals(before.model, after.model)
        assertEquals(before.mac, after.mac)
        assertEquals(before.version, after.version)
        assertEquals(before.build, after.build)
        assertEquals(before.date, after.date)
        assertNull(after.name.value)
        assertNull(after.features.value)
        assertNull(after.dirty.value)
        InputChannel.entries.forEach { channel -> assertNull(after.modes.getValue(channel).value) }
        assertEquals(0xaL, after.uptime.value)
    }

    @Test
    fun lifetimes_and_writable_values() {
        val lifetimes = mapOf(
            AdapterField.Uuid to Lifetime.Constant, AdapterField.Name to Lifetime.Session, AdapterField.Model to Lifetime.Constant,
            AdapterField.Mac to Lifetime.Constant, AdapterField.Version to Lifetime.Constant, AdapterField.Build to Lifetime.Constant,
            AdapterField.Date to Lifetime.Constant, AdapterField.Uptime to Lifetime.Dynamic, AdapterField.Features to Lifetime.Session,
        )
        lifetimes.forEach { (field, lifetime) ->
            assertEquals(lifetime, ValueKey.Field(field).lifetime, field.name)
            assertFalse(ValueKey.Field(field).writable)
        }
        assertEquals(5_000L, Lifetime.DYNAMIC_AGE_LIMIT_MS)
        InputChannel.entries.forEach { channel ->
            assertEquals(Lifetime.Session, ValueKey.Mode(channel).lifetime)
            assertTrue(ValueKey.Mode(channel).writable)
        }
        assertEquals(Lifetime.Dynamic, ValueKey.Reference(InputChannel.X).lifetime)
        assertEquals(Lifetime.Session, ValueKey.Dirty.lifetime)
        assertFalse(ValueKey.Dirty.writable)
    }

    @Test
    fun session_drop_marks_each_fetching_value_failed() {
        val key = ValueKey.Mode(InputChannel.V)
        val dropped = AdapterValues().fetching(key, 1L).withModes(ChannelModes.parse("x000f0002")!!, 2L).dropped(3L)
        assertIs<ValueState.Failed>(dropped.state(key))
        assertIs<ValueState.Reported>(dropped.state(ValueKey.Mode(InputChannel.X)))
    }
}
