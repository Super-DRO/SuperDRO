package org.superdro.app.android

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.MachineRepository
import org.superdro.platform.android.service.BootReceiver
import org.superdro.platform.android.service.BootStart
import org.superdro.platform.android.service.ConnectionService
import org.superdro.platform.android.service.ConnectionServiceHost
import org.superdro.platform.android.service.ConnectionServiceRules
import org.superdro.platform.android.service.ConnectionServiceState
import org.superdro.platform.android.service.ServicePhase
import org.superdro.platform.android.service.ServiceTexts

/**
 * The connection service on an emulator. The tests use the
 * fake adapter. `BLUETOOTH_CONNECT` is granted, so the `connectedDevice` prerequisite is met. The
 * activity is visible at the start, as for a user connect.
 *
 * The sticky restart after a process stop and the boot of the emulator need the shell:
 * `app/android/scripts/service-device-test.sh` runs them.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionServiceDeviceTest {

    private val context = DeviceTestSupport.context
    private val manager: ConnectionManager get() = DeviceTestSupport.get()
    private val serviceState: ConnectionServiceState get() = DeviceTestSupport.get()
    private var scenario: ActivityScenario<MainActivity>? = null

    private val mill = DeviceRef(DeviceId("device-test-mill"), "Test mill", AdapterAddress.Fake("mill"))

    @Before
    fun setUp() {
        DeviceTestSupport.grant(Manifest.permission.BLUETOOTH_CONNECT)
        DeviceTestSupport.grant(Manifest.permission.POST_NOTIFICATIONS)
        DeviceTestSupport.reset()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        DeviceTestSupport.shell("input keyevent KEYCODE_WAKEUP")
        runCatching { scenario?.close() }
        DeviceTestSupport.reset()
    }

    private fun online(id: DeviceId): Boolean = manager.session(id)?.state?.value?.isOnline == true

    private fun connected(id: DeviceId): Boolean = manager.session(id)?.state?.value is SessionState.Connected

    private fun connectMill() {
        manager.connect(mill)
        DeviceTestSupport.waitUntil("The fake session is connected") { connected(mill.id) }
        DeviceTestSupport.waitUntil("The service runs") { serviceState.phase.value == ServicePhase.Running }
    }

    @Test
    fun theServiceStartsWithAFakeSessionAndShowsOneOngoingNotification() {
        connectMill()
        DeviceTestSupport.waitUntil("The notification has the line of the machine") {
            DeviceTestSupport.serviceNotification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Test mill: Connected (Demo)"
        }
        val notification = assertNotNull(DeviceTestSupport.serviceNotification())
        assertEquals("1 machine online", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertNotEquals(0, notification.flags and Notification.FLAG_FOREGROUND_SERVICE)
        assertEquals("connections", notification.channelId)
        assertEquals("Disconnect all", notification.actions.single().title.toString())
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("connections")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("Connections", channel.name.toString())
    }

    @Test
    fun theServiceHoldsAPartialWakeLockWhileALinkIsOpen() {
        connectMill()
        DeviceTestSupport.waitUntil("The wake lock is held") { DeviceTestSupport.heldWakeLocks().any { "SuperDRO:links" in it } }
        manager.remove(mill.id)
        DeviceTestSupport.waitUntil("The service stops with the last link") { serviceState.phase.value == ServicePhase.Stopped }
        DeviceTestSupport.waitUntil("The wake lock is released") { DeviceTestSupport.heldWakeLocks().none { "SuperDRO:links" in it } }
    }

    @Test
    fun aSwipeAwayOfTheTaskKeepsTheLinks() {
        connectMill()
        // The removal of the task calls onTaskRemoved(). The links stay.
        scenario?.onActivity { activity -> activity.finishAndRemoveTask() }
        Thread.sleep(3_000L)
        assertEquals(ServicePhase.Running, serviceState.phase.value)
        assertTrue(online(mill.id))
        assertNotNull(DeviceTestSupport.serviceNotification())
    }

    @Test
    fun theLinksStayInTheBackgroundWithTheScreenOffAndNoTimer() {
        connectMill()
        val session = assertNotNull(manager.session(mill.id))
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        DeviceTestSupport.shell("input keyevent KEYCODE_SLEEP")
        val before = session.inputs.value
        // SuperDRO has no timer that closes the link when the app goes to the background.
        Thread.sleep(35_000L)
        assertTrue(session.state.value is SessionState.Connected, "State after 35 s: ${session.state.value}")
        assertNotEquals(before, session.inputs.value, "The readout stopped in the background.")
        assertEquals(ServicePhase.Running, serviceState.phase.value)
    }

    @Test
    fun onlyDisconnectAllStopsTheServiceAndItPausesTheRememberedMachines() {
        val tabs: TabStore = DeviceTestSupport.get()
        runBlocking {
            tabs.remember(mill.id.value)
            tabs.awaitWrites()
        }
        connectMill()
        val action = assertNotNull(DeviceTestSupport.serviceNotification()).actions.single()
        action.actionIntent.send()
        DeviceTestSupport.waitUntil("The service stops") { serviceState.phase.value == ServicePhase.Stopped }
        assertEquals(SessionState.Disconnected, manager.session(mill.id)?.state?.value)
        DeviceTestSupport.waitUntil("The notification goes") { DeviceTestSupport.serviceNotification() == null }
        assertTrue(tabs.state.value.remembered.single().paused)
    }

    @Test
    fun aStartForANullSummaryCallsStartForegroundAndStops() {
        val phases = CopyOnWriteArrayList<ServicePhase>()
        val collector = CoroutineScope(Dispatchers.Default).launch { serviceState.phase.toList(phases) }
        ContextCompat.startForegroundService(context, ConnectionService.intent(context, ConnectionServiceRules.ACTION_START))
        DeviceTestSupport.waitUntil("The service started") { ServicePhase.Running in phases || ServicePhase.Stopping in phases }
        DeviceTestSupport.waitUntil("The service stopped") { serviceState.phase.value == ServicePhase.Stopped }
        collector.cancel()
        // No ForegroundServiceDidNotStartInTimeException: the process still runs this test.
        assertEquals(null, DeviceTestSupport.serviceNotification())
    }

    @Test
    fun theBootReceiverRestoresTheRememberedMachines() {
        val machines: MachineRepository = DeviceTestSupport.get()
        val tabs: TabStore = DeviceTestSupport.get()
        val profile = MachineProfile(id = "device-test-boot", name = "Boot mill", adapter = AdapterAddress.Fake("still"))
        runBlocking {
            if (machines.loadProfile(profile.id) == null) machines.createProfile(profile)
            tabs.remember(profile.id)
            tabs.awaitWrites()
        }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        DeviceTestSupport.waitUntil("The service restores the machine") { online(DeviceId(profile.id)) }
        DeviceTestSupport.waitUntil("The service runs") { serviceState.phase.value == ServicePhase.Running }
        runBlocking { machines.deleteProfile(profile.id, deleteTools = true) }
    }

    @Test
    fun theBootFallbackNotificationAsksTheUserToOpenTheApp() {
        val host: ConnectionServiceHost = DeviceTestSupport.get()
        val texts: ServiceTexts = DeviceTestSupport.get()
        runBlocking { BootStart.postFallback(context, host, texts) }
        DeviceTestSupport.waitUntil("The fallback notification shows") {
            DeviceTestSupport.notification(ConnectionServiceRules.BOOT_FALLBACK_NOTIFICATION_ID) != null
        }
        val notification = assertNotNull(DeviceTestSupport.notification(ConnectionServiceRules.BOOT_FALLBACK_NOTIFICATION_ID))
        assertEquals("Open SuperDRO to connect your machines", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(context.packageName, notification.contentIntent.creatorPackage)
    }
}
