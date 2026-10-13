package org.superdro.app.android

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.KeepAliveStatus
import org.superdro.core.model.AdapterAddress
import org.superdro.platform.android.service.ConnectionServiceState
import org.superdro.platform.android.service.ServicePhase

/**
 * A machine with only a TCP link starts the `connectedDevice` service with no Bluetooth or USB
 * permission. The declared `CHANGE_NETWORK_STATE`
 * meets the prerequisite on Android 14 and later.
 *
 * The test needs a device where `BLUETOOTH_CONNECT` is not granted. Android stops the app process
 * when a test revokes a permission, so `app/android/scripts/service-device-test.sh` revokes it
 * before it runs this class. With the permission granted, the test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class TcpOnlyServiceDeviceTest {

    private val manager: ConnectionManager get() = DeviceTestSupport.get()
    private val serviceState: ConnectionServiceState get() = DeviceTestSupport.get()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        assumeFalse("BLUETOOTH_CONNECT is granted", DeviceTestSupport.isGranted(Manifest.permission.BLUETOOTH_CONNECT))
        DeviceTestSupport.grant(Manifest.permission.POST_NOTIFICATIONS)
        DeviceTestSupport.reset()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        DeviceTestSupport.reset()
    }

    @Test
    fun aTcpOnlyMachineStartsTheServiceWithNoBluetoothPermission() {
        // No adapter listens on port 9: the session reconnects and wants the process alive.
        val router = DeviceRef(DeviceId("device-test-router"), "Test router", AdapterAddress.Tcp("127.0.0.1", 9))
        manager.connect(router)
        DeviceTestSupport.waitUntil("The service runs for the TCP machine") { serviceState.phase.value == ServicePhase.Running }
        assertNotNull(DeviceTestSupport.serviceNotification())
        assertEquals(KeepAliveStatus.Running, DeviceTestSupport.get<ConnectionKeepAlive>().status.value)
    }

    @Test
    fun aDemoSessionWithNoBluetoothPermissionDoesNotStartTheService() {
        val demo = DeviceRef(DeviceId("device-test-demo"), "Test demo", AdapterAddress.Fake("still"))
        manager.connect(demo)
        DeviceTestSupport.waitUntil("The tab warning shows") {
            DeviceTestSupport.get<ConnectionKeepAlive>().status.value == KeepAliveStatus.PrerequisiteMissing
        }
        assertEquals(ServicePhase.Stopped, serviceState.phase.value)
        // The session runs while the app is visible.
        DeviceTestSupport.waitUntil("The demo session is online") { manager.session(demo.id)?.state?.value?.isOnline == true }
    }
}
