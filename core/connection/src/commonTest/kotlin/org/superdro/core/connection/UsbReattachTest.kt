package org.superdro.core.connection

import com.toxicbakery.logging.Arbor
import com.toxicbakery.logging.ISeedling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterAddress

/** A USB adapter that is attached again next to a second adapter with the same vendor ID and product ID. */
class UsbReattachTest {

    private val id = DeviceId("mill")

    /** The TDA-410 of the remembered machine, with its serial number. */
    private val tda410 = AttachedUsbDevice(0x10C4, 0xEA60, serial = "A1", permitted = true)

    /** A TDA-420 with the same IDs and no permission: the app cannot read its serial number. */
    private val tda420 = AttachedUsbDevice(0x10C4, 0xEA60)

    /** Keeps the log lines of the sessions. */
    private class Lines : ISeedling {
        val texts = ArrayList<String>()
        override val tag: String = "test"
        override fun log(level: Int, tag: String, msg: String, throwable: Throwable?, args: Array<out Any?>?) {
            texts += msg
        }
    }

    private fun <T> withLines(block: (Lines) -> T): T {
        val lines = Lines()
        Arbor.sow(lines)
        try {
            return block(lines)
        } finally {
            Arbor.harvest(lines)
        }
    }

    @Test
    fun theSessionConnectsAgainWhenTheAdapterIsBackNextToASecondAdapter() = withLines { lines ->
        runTest {
            val h = Harness(this)
            val session = h.manager.connect(device("mill", USB, twoWay = false))
            runCurrent()
            assertEquals(SessionState.AwaitingData, session.state.value)
            h.capabilities.state.value = CapabilityState()
            runCurrent()
            assertEquals(SessionState.WaitingForDevice(LinkFailure.DeviceGone), session.state.value)
            // The two adapters come back with no permission. The TDA-420 is first in the list.
            h.capabilities.state.value = CapabilityState(attachedUsb = listOf(tda420, tda410.copy(serial = null, permitted = false)))
            runCurrent()
            assertEquals(SessionState.WaitingForDevice(LinkFailure.PermissionMissing), session.state.value)
            assertTrue(lines.texts.any { text -> text == "mill: capability changed while waiting: PermissionMissing" }, lines.texts.toString())
            assertEquals(1, h.links.links.size)
            // The grant of the TDA-410. The TDA-420 stays first and has no permission.
            h.capabilities.state.value = CapabilityState(attachedUsb = listOf(tda420, tda410))
            runCurrent()
            assertEquals(SessionState.AwaitingData, session.state.value)
            assertEquals(2, h.links.links.size)
            h.manager.disconnect(id)
            runCurrent()
        }
    }

    @Test
    fun aConnectWhileWaitingChecksTheCapabilityAgain() = runTest {
        val h = Harness(this)
        // The transport tells PermissionMissing, but the source tells "available": a fallback retry.
        h.links.openFailures.add(LinkFailure.PermissionMissing)
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        runCurrent()
        assertEquals(SessionState.WaitingForDevice(LinkFailure.PermissionMissing), session.state.value)
        assertEquals(1, h.links.links.size)
        // The user taps Connect before the fallback retry: the link opens at once.
        h.manager.connect(device("mill", USB, twoWay = false))
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        assertEquals(2, h.links.links.size)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aConnectWhileThePermissionIsMissingKeepsTheSessionWaiting() = withLines { lines ->
        runTest {
            val h = Harness(this)
            h.capabilities.state.value = CapabilityState(attachedUsb = listOf(tda410.copy(serial = null, permitted = false)))
            val session = h.manager.connect(device("mill", USB, twoWay = false))
            runCurrent()
            assertEquals(SessionState.WaitingForDevice(LinkFailure.PermissionMissing), session.state.value)
            h.manager.connect(device("mill", USB, twoWay = false))
            runCurrent()
            assertEquals(SessionState.WaitingForDevice(LinkFailure.PermissionMissing), session.state.value)
            assertTrue(lines.texts.any { text -> text == "mill: connect while waiting for PermissionMissing" }, lines.texts.toString())
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, h.links.links.size)
            h.capabilities.state.value = CapabilityState(attachedUsb = listOf(tda410))
            runCurrent()
            assertEquals(SessionState.AwaitingData, session.state.value)
            assertEquals(1, h.links.links.size)
            h.manager.disconnect(id)
            runCurrent()
        }
    }

    @Test
    fun anAddressWithNoSerialNumberUsesThePermittedAdapter() = runTest {
        val h = Harness(this)
        val noSerial = AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60)
        h.capabilities.state.value = CapabilityState(attachedUsb = listOf(tda420, tda410))
        val session = h.manager.connect(device("mill", noSerial, twoWay = false))
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        h.manager.disconnect(id)
        runCurrent()
    }
}
