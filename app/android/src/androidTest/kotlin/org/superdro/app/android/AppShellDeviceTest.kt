package org.superdro.app.android

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.superdro.app.android.files.AndroidFilePicker
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.fakeadapter.FakeDebugAdapter
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.sound.UiSounds

/** The app shell on an emulator: full screen, the demo entry, the sounds, and the file picker. */
@RunWith(AndroidJUnit4::class)
class AppShellDeviceTest {

    private val context = DeviceTestSupport.context
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        DeviceTestSupport.grant(Manifest.permission.BLUETOOTH_CONNECT)
        DeviceTestSupport.grant(Manifest.permission.POST_NOTIFICATIONS)
        DeviceTestSupport.reset()
    }

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        DeviceTestSupport.reset()
    }

    private fun systemBarsVisible(): Boolean {
        var visible = true
        scenario?.onActivity { activity ->
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            visible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        }
        return visible
    }

    @Test
    fun theFullScreenSettingHidesAndShowsTheSystemBars() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        DeviceTestSupport.waitUntil("The bars hide with the default setting") { !systemBarsVisible() }
        val settings: AppSettingsStore = DeviceTestSupport.get()
        settings.update { value -> value.copy(fullScreen = false) }
        DeviceTestSupport.waitUntil("The bars show when the setting is off") { systemBarsVisible() }
        settings.update { value -> value.copy(fullScreen = true) }
        DeviceTestSupport.waitUntil("The bars hide again") { !systemBarsVisible() }
        runBlocking { settings.awaitWrites() }
    }

    @Test
    fun theDemoEntryOpensTheAppWithTwoFakeMachines() {
        val manager: ConnectionManager = DeviceTestSupport.get()
        // The intent of DemoActivity: MainActivity with the demo extra.
        val demo = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEMO, true)
        scenario = ActivityScenario.launch(demo)
        val ids = listOf(FakeDebugAdapter.DEMO_MILL_ID, FakeDebugAdapter.DEMO_LATHE_ID).map(::DeviceId)
        DeviceTestSupport.waitUntil("Two fake machines are online") {
            ids.all { id -> manager.session(id)?.state?.value?.isOnline == true }
        }
        assertTrue(DeviceTestSupport.get<DebugAdapter>().active)
        // A launcher tap turns the demo off and removes the two fake machines.
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        DeviceTestSupport.waitUntil("The demo machines go") { ids.all { id -> manager.session(id) == null } }
        assertFalse(DeviceTestSupport.get<DebugAdapter>().active)
    }

    @Test
    fun eachSoundPlaysWithNoError() {
        val sounds: UiSounds = DeviceTestSupport.get()
        UiSound.entries.forEach { sound ->
            sounds.play(sound)
            Thread.sleep(1_100L)
        }
        sounds.proximity(0.65)
        Thread.sleep(500L)
        sounds.proximity(1.0)
        Thread.sleep(300L)
        sounds.proximity(0.0)
        Thread.sleep(200L)
        // The process still runs: no audio call failed with a crash.
        assertTrue(sounds is org.superdro.app.android.sound.AndroidUiSounds)
    }

    @Test
    fun theFilePickerNeedsAVisibleActivityAndSharesACopyInTheCache() = runBlocking {
        val picker: AndroidFilePicker = DeviceTestSupport.get()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.close()
        scenario = null
        // No visible activity: each function gives null or false.
        assertNull(picker.openDocument(listOf("text/csv")))
        assertFalse(picker.createDocument("tools.csv", "text/csv", byteArrayOf(1)))
        assertFalse(picker.share("log.txt", "text/plain", byteArrayOf(1)))

        scenario = ActivityScenario.launch(MainActivity::class.java)
        val content = "SuperDRO log".encodeToByteArray()
        assertTrue(picker.share("log 1.txt", "text/plain", content))
        val copy = File(context.cacheDir, "share/log 1.txt")
        assertEquals("SuperDRO log", copy.readText())
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(500L)
    }
}
