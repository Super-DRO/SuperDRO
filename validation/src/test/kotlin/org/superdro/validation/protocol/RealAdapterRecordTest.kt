package org.superdro.validation.protocol

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSetup
import org.superdro.core.connection.DeviceSetupSource
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.AdapterParser
import org.superdro.validation.Traceability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Parses a record of the adapter of the user (USB serial, 115200 baud, PC tool `adapter_probe.py`, mode `record`).
 * The record starts with the boot log of the ESP32, then the adapter sends one group `@<hex>;x0;y0;z0;w0;t0;` about
 * each second, with an LF before each group.
 *
 * The expected values come from the protocol rules and from the summary lines that the PC tool wrote at the end of
 * the `.log` file (an independent parser).
 */
class RealAdapterRecordTest {

    private val folder = Traceability.rootDir.resolve("validation/src/test/resources/adapter-logs")

    /** One received block of the `.log` file: the PC time and the bytes. */
    private data class Block(val timeMs: Long, val bytes: ByteArray)

    private val blocks: List<Block> = folder.resolve("20261012-102526-record-usb.log").readLines()
        .mapNotNull { line -> Regex("""^\s*([0-9.]+) RX\s+\d+ ([0-9a-f ]+) \|""").find(line) }
        .map { match ->
            val bytes = match.groupValues[2].trim().split(' ').map { hex -> hex.toInt(16).toByte() }.toByteArray()
            Block(match.groupValues[1].toDouble().toLong(), bytes)
        }

    private val messages: List<AdapterMessage> = AdapterParser().let { parser ->
        blocks.flatMap { block -> parser.feed(block.bytes, block.timeMs) }
    }

    @Test
    fun theLogHasTheSameBytesAsTheBinFile() {
        val bin = folder.resolve("20261012-102526-record-usb.bin").readBytes()
        assertEquals(4065, bin.size)
        assertTrue(bin.contentEquals(blocks.fold(ByteArray(0)) { all, block -> all + block.bytes }))
    }

    @Test
    fun eachLowerCaseReadoutGivesAValueAndTheBootLogGivesNone() {
        val readouts = messages.filterIsInstance<AdapterMessage.AxisCount>()
        // The PC tool: X, Y, Z, W, and T each 14 times, value 0. The case of a prefix does not matter.
        assertEquals(listOf('W', 'T', 'X', 'Y', 'Z').sorted(), readouts.map { it.channel }.distinct().sorted())
        listOf('X', 'Y', 'Z', 'W', 'T').forEach { channel ->
            assertEquals(14, readouts.count { it.channel == channel }, "Readouts of $channel")
        }
        assertTrue(readouts.all { it.count == 0 && it.carriesValue })
        assertTrue(messages.none { it is AdapterMessage.BinaryInput || it is AdapterMessage.Info || it is AdapterMessage.Error })
    }

    @Test
    fun theTimeStampsCountInMillisecondsAndGiveTheTimeOfTheirReadouts() {
        val stamps = messages.filterIsInstance<AdapterMessage.Timestamp>()
        assertEquals(14, stamps.size)
        assertEquals(0xdc7L, stamps.first().adapterTime)
        assertEquals(0x4296L, stamps.last().adapterTime)
        // The adapter sends a group about each 1040 ms when no input changes (the first step of the record is 1039).
        assertEquals(listOf(1039L) + List(12) { 1040L }, stamps.zipWithNext().map { (a, b) -> b.adapterTime - a.adapterTime })
        // The PC tool: the largest difference from a line is 7.1 ms. Thus the 250 ms drift rule never sets a new offset.
        assertEquals(1, stamps.map { it.timeMs - it.adapterTime }.distinct().size)
        // A readout gets tAdapter of the last @.
        var last: AdapterMessage.Timestamp? = null
        messages.forEach { message ->
            when (message) {
                is AdapterMessage.Timestamp -> last = message
                is AdapterMessage.AxisCount -> last?.let { stamp -> assertEquals(stamp.timeMs, message.timeMs) }
                else -> Unit
            }
        }
    }

    @Test
    fun theDirtyAxesStatusComesOneTimeAndTheValidMessagesKeepTheWatchdogQuiet() {
        assertEquals(listOf(AdapterMessage.Status(0x32, 0)), messages.filterIsInstance<AdapterMessage.Status>())
        // The no-data limit is 7000 ms. The longest gap of the PC tool is 1047 ms.
        val readoutTimes = messages.filterIsInstance<AdapterMessage.AxisCount>().map { it.timeMs }.distinct()
        assertEquals(14, readoutTimes.size)
        assertTrue(readoutTimes.zipWithNext().all { (a, b) -> b - a in 1..6999 })
        val firstReadout = messages.indexOfFirst { it is AdapterMessage.AxisCount }
        assertTrue(firstReadout > 0, "The boot log comes before the first readout.")
        assertTrue(messages.take(firstReadout).none { it.isValid }, "The boot log gives no valid message.")
    }

    @Test
    fun theRecordThroughAUsbSessionGivesConnectedAndLivePositions() = runTest {
        val mill = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true)
        val setups = DeviceSetupSource { flowOf(DeviceSetup(mill, AdapterProfile())) }
        val harness = SessionHarness(this, deviceSetups = setups)
        val usb = SessionHarness.USB
        harness.capabilities.value = harness.capabilities.value.copy(
            attachedUsb = listOf(AttachedUsbDevice(usb.vendorId, usb.productId, usb.serial, permitted = true)),
        )
        val session = harness.connect(DeviceRef(DeviceId(mill.id), mill.name, SessionHarness.USB))
        val states = harness.record(session)
        val transport = harness.factory.last
        // The record has its own clock: the link opened at 125 ms of the PC time.
        val start = harness.now - 125L
        blocks.forEach { block ->
            harness.advanceTo(maxOf(harness.now, start + block.timeMs))
            transport.send(String(block.bytes, Charsets.ISO_8859_1))
            harness.settle()
        }
        harness.advanceTo(harness.now + 500L)
        assertIs<SessionState.Connected>(session.state.value)
        // The boot log is not a valid message, so the session waits for the first readout.
        val firstConnected = states.first { (_, state) -> state is SessionState.Connected }.first
        assertTrue(firstConnected - start >= 3_000L, "Connected at ${firstConnected - start} ms of the record")
        assertTrue(states.none { (_, state) -> state is SessionState.NoData })
        val values = session.inputs.value.axisValues()
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertEquals(0.0, values[axis], "$axis") }
        harness.manager.disconnectAll()
        harness.settle()
    }
}
