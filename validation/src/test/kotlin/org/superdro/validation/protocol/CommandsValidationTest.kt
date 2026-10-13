package org.superdro.validation.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterDialect
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.AdapterFieldValues
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.protocol.ChannelModes
import org.superdro.core.protocol.ChannelRules
import org.superdro.core.protocol.CommandFailure
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.RawView
import org.superdro.core.protocol.RequestKind
import org.superdro.core.protocol.StatusCode
import org.superdro.core.protocol.Subsystem

/**
 * The channels and input modes, the adapter fields, the command reference, the subsystems and status
 * codes, the dialect constants, and the channel rules. The expected bytes and texts are
 * independent of the implementation.
 */
class CommandsValidationTest {

    private fun ascii(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }

    // The command bytes.

    @Test
    fun get_mac_is_the_11_bytes_of_the_byte_example() {
        val expected = byteArrayOf(0x22, 0x23, 0x67, 0x65, 0x74, 0x3A, 0x6D, 0x61, 0x63, 0x3B, 0x22)
        assertContentEquals(expected, AdapterCommand.GetField(AdapterField.Mac).encode())
    }

    @Test
    fun get_field_texts_have_a_quote_before_and_after() {
        val names = listOf(
            AdapterField.Mac to "mac", AdapterField.Uuid to "uuid", AdapterField.Features to "features",
            AdapterField.Uptime to "uptime", AdapterField.Model to "model", AdapterField.Name to "name",
            AdapterField.Version to "version", AdapterField.Build to "build", AdapterField.Date to "date",
        )
        for ((field, name) in names) {
            val command = AdapterCommand.GetField(field)
            assertEquals("\"#get:$name;\"", ascii(command.encode()))
            assertEquals(RequestKind.Query, command.kind)
            assertEquals(500L, command.firstWaitMs)
            assertEquals(2000L, command.fullWaitMs)
        }
    }

    @Test
    fun get_mode_text_for_each_channel() {
        for ((channel, letter) in listOf(InputChannel.X to 'x', InputChannel.Y to 'y', InputChannel.Z to 'z', InputChannel.W to 'w', InputChannel.U to 'u', InputChannel.V to 'v')) {
            val command = AdapterCommand.GetMode(channel)
            assertEquals("#get_mode:$letter;", ascii(command.encode()))
            assertEquals(RequestKind.Query, command.kind)
            assertEquals(500L, command.firstWaitMs)
            assertEquals(2000L, command.fullWaitMs)
        }
    }

    @Test
    fun set_mode_text_has_the_decimal_mode_id() {
        assertEquals("#set_mode:x,4;", ascii(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer).encode()))
        assertEquals("#set_mode:v,2048;", ascii(AdapterCommand.SetMode(InputChannel.V, InputMode.MitutoyoSpc).encode()))
        assertEquals("#set_mode:u,1;", ascii(AdapterCommand.SetMode(InputChannel.U, InputMode.Disabled).encode()))
        val command = AdapterCommand.SetMode(InputChannel.Y, InputMode.QuadratureAB)
        assertEquals(RequestKind.Command, command.kind)
        assertEquals(500L, command.firstWaitMs)
        assertEquals(2000L, command.fullWaitMs)
    }

    @Test
    fun ref_axis_text_constant_15_and_full_wait_n_times_100() {
        assertEquals("#ref_axis:x,15;", ascii(AdapterCommand.RefAxis(InputChannel.X).encode()))
        assertEquals("#ref_axis:w,15;", ascii(AdapterCommand.RefAxis(InputChannel.W).encode()))
        assertEquals(1500L, AdapterCommand.RefAxis(InputChannel.Z).fullWaitMs)
        assertEquals(500L, AdapterCommand.RefAxis(InputChannel.Z).firstWaitMs)
        assertEquals(RequestKind.Command, AdapterCommand.RefAxis(InputChannel.Y).kind)
        assertEquals(2000L, AdapterCommand.RefAxis(InputChannel.Y, 20).fullWaitMs)
        assertFailsWith<IllegalArgumentException> { AdapterCommand.RefAxis(InputChannel.U) }
        assertFailsWith<IllegalArgumentException> { AdapterCommand.RefAxis(InputChannel.V) }
    }

    @Test
    fun clear_counter_and_reboot_texts() {
        assertEquals("#clear_counter:u;", ascii(AdapterCommand.ClearCounter(InputChannel.U).encode()))
        assertEquals("#clear_counter:x;", ascii(AdapterCommand.ClearCounter(InputChannel.X).encode()))
        assertEquals("#reboot:1000;", ascii(AdapterCommand.Reboot.encode()))
        assertEquals(RequestKind.Command, AdapterCommand.Reboot.kind)
        assertEquals(2000L, AdapterCommand.Reboot.fullWaitMs)
        assertEquals(2000L, AdapterCommand.ClearCounter(InputChannel.X).fullWaitMs)
    }

    @Test
    fun get_sig_seconds_formula_and_waits() {
        // `(nowMs − 1326369600000) / 1000`. At 2026-10-11 00:00:00 UTC, the formula gives 465307200.
        val now = 1_326_369_600_000L + 465_307_200_000L + 999L
        assertEquals(465_307_200L, AdapterCommand.GetSig.secondsAt(now))
        assertEquals("#get_sig:465307200,1;", ascii(AdapterCommand.GetSig.at(now).encode()))
        val command = AdapterCommand.GetSig(1L)
        assertEquals(RequestKind.Query, command.kind)
        assertEquals(500L, command.firstWaitMs)
        assertEquals(10_000L, command.fullWaitMs)
        assertFalse(command.resendOnBusy)
    }

    @Test
    fun bel_byte_is_one_byte_07() {
        assertContentEquals(byteArrayOf(0x07), AdapterCommand.Bel.encode())
    }

    @Test
    fun outbound_text_is_ascii_with_no_line_end() {
        val commands = AdapterField.entries.filter { it.gettable }.map { AdapterCommand.GetField(it) } +
            InputChannel.entries.map { AdapterCommand.GetMode(it) } +
            InputChannel.entries.map { AdapterCommand.ClearCounter(it) } +
            listOf(AdapterCommand.Reboot, AdapterCommand.SetMode(InputChannel.X, InputMode.QuadratureAB), AdapterCommand.RefAxis(InputChannel.X))
        for (command in commands) {
            val bytes = command.encode()
            assertTrue(bytes.all { (it.toInt() and 0xFF) in 0x20..0x7E }, ascii(bytes))
            val text = ascii(bytes).trim('"')
            assertTrue(text.startsWith("#") && text.endsWith(";"), text)
        }
    }

    @Test
    fun busy_resend_is_on_for_the_commands_and_off_for_get_sig() {
        val commands = listOf(
            AdapterCommand.GetField(AdapterField.Mac), AdapterCommand.GetMode(InputChannel.X),
            AdapterCommand.SetMode(InputChannel.X, InputMode.QuadratureAB), AdapterCommand.RefAxis(InputChannel.X),
            AdapterCommand.ClearCounter(InputChannel.X), AdapterCommand.Reboot,
        )
        assertTrue(commands.all { it.resendOnBusy })
    }

    // Channels and input modes.

    @Test
    fun channel_table() {
        val rows = listOf(
            listOf(InputChannel.X, 'x', "Input X", true, 0x78, 200),
            listOf(InputChannel.Y, 'y', "Input Y", true, 0x79, 201),
            listOf(InputChannel.Z, 'z', "Input Z", true, 0x7A, 202),
            listOf(InputChannel.W, 'w', "Input W", true, 0x77, 203),
            listOf(InputChannel.U, 'u', "Aux 1", false, null, 204),
            listOf(InputChannel.V, 'v', "Aux 2", false, null, 205),
        )
        assertEquals(rows.map { it[0] }, InputChannel.entries.toList())
        for (row in rows) {
            val channel = row[0] as InputChannel
            assertEquals(row[1], channel.letter)
            assertEquals(row[2], channel.uiName)
            assertEquals(row[3], channel.referenceAllowed)
            assertEquals(row[4], channel.referenceSubsystem)
            assertEquals(row[5], channel.clearedCode)
        }
        assertEquals(listOf(0xC8, 0xC9, 0xCA, 0xCB, 0xCC, 0xCD), InputChannel.entries.map { it.clearedCode })
    }

    @Test
    fun input_mode_table() {
        val rows = listOf(
            Triple(1, "0001", "Disabled") to RawView.ProbeForm,
            Triple(2, "0002", "Quadrature A/B") to RawView.ChannelCount,
            Triple(4, "0004", "Tachometer") to RawView.TachometerValue,
            Triple(8, "0008", "Digital Probe") to RawView.ProbeForm,
            Triple(16, "0010", "Limit (X,Y)") to RawView.ProbeForm,
            Triple(32, "0020", "Limit (Z,W)") to RawView.ProbeForm,
            Triple(64, "0040", "Binary Switch") to RawView.ProbeForm,
            Triple(128, "0080", "BCD-7") to RawView.ProbeForm,
            Triple(256, "0100", "BIN6") to RawView.ChannelCount,
            Triple(512, "0200", "iGaging 21 Bit") to RawView.ProbeForm,
            Triple(1024, "0400", "iGaging SPC") to RawView.ProbeForm,
            Triple(2048, "0800", "Mitutoyo SPC") to RawView.ChannelCount,
        )
        assertEquals(rows.size, InputMode.wireModes.size)
        rows.forEachIndexed { index, (row, view) ->
            val mode = InputMode.wireModes[index]
            assertEquals(row.first, mode.id)
            assertEquals(row.second, mode.hexId)
            assertEquals(row.third, mode.text)
            assertEquals(view, mode.rawView)
            assertEquals(mode, InputMode.forId(row.first))
        }
        assertEquals(InputMode.Unknown, InputMode.forId(3))
        assertEquals(InputMode.Unknown, InputMode.forId(4096))
        assertEquals(InputMode.Unknown, InputMode.forId(0))
    }

    @Test
    fun mode_answer_example() {
        val modes = ChannelModes.parse("x000f0002")!!
        assertEquals(InputChannel.X, modes.channel)
        assertEquals(listOf(1, 2, 4, 8), modes.supported.map { it.id })
        assertEquals(InputMode.QuadratureAB, modes.current)
    }

    @Test
    fun mode_answer_must_match_lower_case_pattern() {
        assertNull(ChannelModes.parse("X000f0002"))
        assertNull(ChannelModes.parse("x000F0002"))
        assertNull(ChannelModes.parse("x000f000"))
        assertNull(ChannelModes.parse("x000f00021"))
        assertNull(ChannelModes.parse("a000f0002"))
        assertNull(ChannelModes.parse("t000f0002"))
        assertNull(ChannelModes.parse(""))
        assertEquals(InputMode.Unknown, ChannelModes.parse("x000f0003")!!.current)
        assertEquals(InputMode.Unknown, ChannelModes.parse("x000f0000")!!.current)
        assertEquals(
            listOf(1, 2, 4, 8, 256, 512, 1024, 2048),
            ChannelModes.parse("y0f0f0002")!!.supported.map { it.id },
        )
        assertEquals(emptyList(), ChannelModes.parse("z00000002")!!.supported)
    }

    @Test
    fun mode_answer_that_does_not_match_is_ignored() {
        val values = AdapterValues().withInfo(1, "x000f0002", 100L)
        assertEquals(InputMode.QuadratureAB, values.modes.getValue(InputChannel.X).value!!.current)
        val after = values.withInfo(1, "X000F0004", 200L)
        assertEquals(values, after)
    }

    // The adapter fields.

    @Test
    fun field_table_ids_and_groups() {
        val rows = listOf(
            AdapterField.Mode to 0x01, AdapterField.Uuid to 0x02, AdapterField.Name to 0x03,
            AdapterField.Model to 0x04, AdapterField.Mac to 0x05, AdapterField.Version to 0x06,
            AdapterField.Build to 0x07, AdapterField.Date to 0x08, AdapterField.Uptime to 0x09,
            AdapterField.Features to 0x0A, AdapterField.Signature to 0x64,
        )
        for ((field, id) in rows) {
            assertEquals(id, field.id)
            assertEquals(field, AdapterField.forId(id))
        }
        assertFalse(AdapterField.Mode.gettable)
        assertFalse(AdapterField.Signature.gettable)
        assertNull(AdapterField.forId(0x0B))
        assertEquals(listOf(AdapterField.Mac, AdapterField.Uuid, AdapterField.Features, AdapterField.Uptime, AdapterField.Model), AdapterField.idGroup)
        assertEquals(listOf(AdapterField.Name, AdapterField.Version, AdapterField.Build, AdapterField.Date), AdapterField.secondaryGroup)
    }

    @Test
    fun value_formats_and_values_that_are_not_valid() {
        assertEquals("246f28a1b2c3", AdapterFieldValues.parseMac("246F28A1B2C3"))
        assertNull(AdapterFieldValues.parseMac("246f28a1b2c"))
        assertNull(AdapterFieldValues.parseMac("246f28a1b2c3d"))
        assertNull(AdapterFieldValues.parseMac("24:6f:28:a1:b2:c3"))
        assertNull(AdapterFieldValues.parseMac("246f28a1b2cg"))
        assertEquals("123e4567-e89b-12d3-a456-426614174000", AdapterFieldValues.parseUuid("123e4567-e89b-12d3-a456-426614174000"))
        assertNull(AdapterFieldValues.parseUuid("00000000-0000-0000-0000-000000000000"))
        assertNull(AdapterFieldValues.parseUuid("not a uuid"))
        assertEquals(3600L, AdapterFieldValues.parseHex("e10"))
        assertNull(AdapterFieldValues.parseHex("-1"))
        assertNull(AdapterFieldValues.parseHex(""))
        assertEquals(1L, AdapterFieldValues.parseDecimal("1"))
        assertEquals(10L, AdapterFieldValues.parseDecimal("10"))
        assertNull(AdapterFieldValues.parseDecimal("a"))
        assertNull(AdapterFieldValues.parseDecimal(""))
    }

    @Test
    fun answers_go_into_the_field_that_the_answer_names() {
        var values = AdapterValues()
        values = values.withInfo(0x05, "246f28a1b2c3", 1L)
        values = values.withInfo(0x02, "c0ffee00-1234-4abc-8def-0123456789ab", 2L)
        values = values.withInfo(0x0A, "1", 3L)
        values = values.withInfo(0x09, "e10", 4L)
        values = values.withInfo(0x04, "tda-example", 5L)
        values = values.withInfo(0x03, "Mill adapter", 6L)
        values = values.withInfo(0x06, "1.2.3", 7L)
        values = values.withInfo(0x07, "b42", 8L)
        values = values.withInfo(0x08, "6955b900", 9L)
        assertEquals("246f28a1b2c3", values.mac.value)
        assertEquals("c0ffee00-1234-4abc-8def-0123456789ab", values.uuid.value)
        assertEquals(1L, values.features.value)
        assertEquals(3600L, values.uptime.value)
        assertEquals("tda-example", values.model.value)
        assertEquals("Mill adapter", values.name.value)
        assertEquals("1.2.3", values.version.value)
        assertEquals("b42", values.build.value)
        assertEquals(0x6955b900L, values.date.value)
        // A field that is not in the table (the ID −1) is ignored.
        assertEquals(values, values.withInfo(0x0B, "x", 10L))
        // A value that is not valid is dropped, and the field keeps its old state.
        assertEquals(values, values.withInfo(0x05, "zz", 11L))
        assertEquals(values, values.withInfo(0x02, "00000000-0000-0000-0000-000000000000", 12L))
        assertEquals(values, values.withInfo(0x09, "-1", 13L))
        assertEquals(values, values.withInfo(0x0A, "x", 14L))
    }

    @Test
    fun bluetooth_id_is_mac_plus_2_with_carry() {
        assertEquals("24:6f:28:a1:b2:c5", AdapterFieldValues.macPlusTwo("246f28a1b2c3"))
        assertEquals("24:6f:28:a1:b3:00", AdapterFieldValues.macPlusTwo("246f28a1b2fe"))
        assertEquals("24:6f:28:a1:b3:01", AdapterFieldValues.macPlusTwo("246f28a1b2ff"))
        assertEquals("24:6f:28:a2:00:00", AdapterFieldValues.macPlusTwo("246f28a1fffe"))
        assertEquals("0a:0b:0c:0d:0e:11", AdapterFieldValues.macPlusTwo("0a0b0c0d0e0f"))
        assertNull(AdapterFieldValues.macPlusTwo("246f28a1b2c"))
        val values = AdapterValues().withInfo(5, "246f28a1b2fe", 0L)
        assertEquals("24:6f:28:a1:b3:00", values.bluetoothId)
        assertEquals(values.bluetoothId, values.reportedIdentity)
    }

    @Test
    fun dc25_identity_is_the_uuid_when_no_mac_is_known() {
        val values = AdapterValues().withInfo(2, "c0ffee00-1234-4abc-8def-0123456789ab", 0L)
        assertEquals("c0ffee00-1234-4abc-8def-0123456789ab", values.reportedIdentity)
        assertNull(values.bluetoothId)
    }

    // Subsystems and status codes.

    @Test
    fun subsystem_table() {
        val rows = mapOf(
            -1 to "None", 0x01 to "ESP32", 0x02 to "Core", 0x03 to "Brownout Detection", 0x04 to "Firmware Update",
            0x06 to "Inputs", 0x07 to "Touch Probe", 0x08 to "Tachometer", 0x09 to "Home Switch", 0x0A to "RX",
            0x32 to "Dirty Axes", 0x77 to "W Axis Reference", 0x78 to "X Axis Reference", 0x79 to "Y Axis Reference",
            0x7A to "Z Axis Reference",
        )
        rows.forEach { (id, name) -> assertEquals(name, Subsystem.name(id), "subsystem $id") }
        assertEquals("Unknown", Subsystem.name(0x05))
        assertEquals("Unknown", Subsystem.name(0x7B))
        assertEquals(listOf('w', 'x', 'y', 'z').map { it.code }, listOf(Subsystem.W_AXIS_REFERENCE, Subsystem.X_AXIS_REFERENCE, Subsystem.Y_AXIS_REFERENCE, Subsystem.Z_AXIS_REFERENCE))
    }

    @Test
    fun status_code_table() {
        val rows = mapOf(
            -1 to "None", 0 to "OK", 1 to "Failed", 2 to "Invalid state", 3 to "Invalid argument",
            4 to "Peripheral not found", 5 to "Not supported", 20 to "Initialization failed", 21 to "System timeout",
            22 to "User timeout", 23 to "Peripheral busy", 24 to "Peripheral overflow", 30 to "Data checksum failed",
            31 to "Invalid data length", 32 to "Busy", 33 to "Bad data", 160 to "Initialized", 161 to "Finished",
            162 to "Ready", 163 to "Received", 165 to "Write OK", 166 to "Read OK", 167 to "Not changed", 168 to "Done",
            169 to "Cancelled", 170 to "NOOP", 200 to "X Axis Cleared", 201 to "Y Axis Cleared", 202 to "Z Axis Cleared",
            203 to "W Axis Cleared", 204 to "U Axis Cleared", 205 to "V Axis Cleared", 255 to "Rebooting",
        )
        rows.forEach { (code, text) -> assertEquals(text, StatusCode.text(code), "code $code") }
        assertEquals("Status code 164", StatusCode.text(164))
        assertEquals("Status code 256", StatusCode.text(256))
        assertEquals("Status code 6", StatusCode.text(6))
        assertEquals("Timeout", CommandFailure.TIMEOUT.text)
        assertEquals(-1, CommandFailure.TIMEOUT.subsystem)
        assertEquals(256, CommandFailure.TIMEOUT.code)
    }

    @Test
    fun error_path_codes_1_to_159() {
        assertFalse(StatusCode.isErrorPath(0))
        assertTrue(StatusCode.isErrorPath(1))
        assertTrue(StatusCode.isErrorPath(32))
        assertTrue(StatusCode.isErrorPath(159))
        assertFalse(StatusCode.isErrorPath(160))
        assertFalse(StatusCode.isErrorPath(163))
        assertFalse(StatusCode.isErrorPath(255))
    }

    @Test
    fun command_failed_text_of_the_error_case() {
        assertEquals("Command failed: Invalid argument (6:3)", CommandFailure.of(6, 3).message)
    }

    // The dialect constants.

    @Test
    fun dialect_constants() {
        val timing = AdapterDialect.DEFAULT_TIMING
        assertEquals(7000L, timing.noDataMs)
        assertEquals(30_000L, timing.stateLimitMs)
        assertEquals(2000L, timing.queueStartMs)
        assertEquals(500L, timing.firstWaitMs)
        assertEquals(2000L, timing.fullWaitMs)
        assertEquals(100L, timing.referenceWaitStepMs)
        assertEquals(10_000L, timing.signatureWaitMs)
        assertEquals(5000L, timing.idRetryMs)
        assertEquals(10_000L, timing.resultLimitMs)
        assertEquals(5000L, timing.readAgainLimitMs)
        assertEquals(750L, timing.noDataRecoveryMs)
        assertEquals(5000L, timing.tcpConnectMs)
        assertEquals(4000L, timing.bluetoothWriteWatchdogMs)
        assertEquals(50L, timing.queueGapMs)
        assertEquals(500L, timing.belPeriodMs)
        assertEquals(250L, timing.restartCloseMs)
        assertEquals(2000L, timing.restartConnectMs)
        assertEquals(3, timing.maxTransmissions)
        assertEquals(AdapterDialect.entries.map { timing }, AdapterDialect.entries.map { it.timing })
    }

    @Test
    fun dialects() {
        assertTrue(AdapterDialect.Esp32TwoWay.answersCommands && AdapterDialect.Esp32TwoWay.allMessageForms)
        assertTrue(AdapterDialect.Esp32TwoWayTcp.answersCommands && AdapterDialect.Esp32TwoWayTcp.allMessageForms)
        assertFalse(AdapterDialect.LegacyOneWay.answersCommands)
        assertFalse(AdapterDialect.LegacyOneWay.allMessageForms)
        assertTrue(AdapterDialect.isGenuineModel("tda-example"))
        assertTrue(AdapterDialect.isGenuineModel("TDA-X1"))
        assertFalse(AdapterDialect.isGenuineModel("xtda-1"))
        assertFalse(AdapterDialect.isGenuineModel(null))
    }

    // The USB baud rate.

    @Test
    fun dc23_usb_baud_default_115200_and_a_stored_value_stays() {
        assertEquals(115_200, AdapterAddress.UsbSerial().baudRate)
        assertEquals(115_200, MachineProfile().usbBaudRate)
        assertEquals(listOf(9_600, 19_200, 38_400, 57_600, 115_200), AdapterAddress.UsbSerial.BAUD_RATES)
        assertEquals(9_600, AdapterAddress.UsbSerial(baudRate = 9_600).copy(serial = "1").baudRate)
    }

    @Test
    fun f20_10_profile_switch_adapter_configuration_commands_is_off_by_default() {
        assertFalse(MachineProfile().adapterConfigurationCommands)
    }

    // The channel rows.

    @Test
    fun reference_shows_only_when_all_conditions_are_true() {
        for (channel in listOf(InputChannel.X, InputChannel.Y, InputChannel.Z, InputChannel.W)) {
            assertTrue(ChannelRules.showReference("tda-example", channel, InputMode.QuadratureAB, modeUnchanged = true))
            assertTrue(ChannelRules.showReference("TDA-Example", channel, InputMode.QuadratureAB, modeUnchanged = true))
        }
        assertFalse(ChannelRules.showReference("tda-example", InputChannel.U, InputMode.QuadratureAB, modeUnchanged = true))
        assertFalse(ChannelRules.showReference("tda-example", InputChannel.V, InputMode.QuadratureAB, modeUnchanged = true))
        assertFalse(ChannelRules.showReference("tda-example", InputChannel.X, InputMode.Bin6, modeUnchanged = true))
        assertFalse(ChannelRules.showReference("tda-example", InputChannel.X, InputMode.QuadratureAB, modeUnchanged = false))
        // The button does not show when no command can go out.
        assertFalse(ChannelRules.showReference("legacy-1", InputChannel.X, InputMode.QuadratureAB, modeUnchanged = true))
        assertFalse(ChannelRules.showReference(null, InputChannel.X, InputMode.QuadratureAB, modeUnchanged = true))
    }

    @Test
    fun clear_shows_for_quadrature_with_no_mode_change() {
        assertTrue(ChannelRules.showClear(InputMode.QuadratureAB, modeUnchanged = true))
        assertFalse(ChannelRules.showClear(InputMode.QuadratureAB, modeUnchanged = false))
        assertFalse(ChannelRules.showClear(InputMode.MitutoyoSpc, modeUnchanged = true))
    }

    @Test
    fun mode_list_and_save() {
        val modes = ChannelModes.parse("x000f0002")!!
        assertEquals(listOf(InputMode.Disabled, InputMode.QuadratureAB, InputMode.Tachometer, InputMode.DigitalProbe), ChannelRules.modeList(modes))
        assertFalse(ChannelRules.showSave(InputMode.QuadratureAB, InputMode.QuadratureAB))
        assertTrue(ChannelRules.showSave(InputMode.Tachometer, InputMode.QuadratureAB))
    }
}
