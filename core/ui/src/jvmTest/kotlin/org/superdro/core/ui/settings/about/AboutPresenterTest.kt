package org.superdro.core.ui.settings.about

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AppSettings
import org.superdro.core.resources.Res
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.UiStoreFixture

/** The presenter of the about screen and the rule of the "What changed" notice. */
class AboutPresenterTest {

    private class Harness(scope: TestScope, reader: (suspend (String) -> ByteArray)? = null) {
        val store = UiStoreFixture(scope)
        val presenter = if (reader == null) {
            AboutPresenter(store.appSettings, scope.testPresenterContext(), versionName = "26.41.1", versionCode = 2641001)
        } else {
            AboutPresenter(store.appSettings, scope.testPresenterContext(), reader, "26.41.1", 2641001)
        }

        fun close() {
            presenter.close()
            store.close()
        }
    }

    @Test
    fun aNewInstallGetsNoNoticeAndKeepsTheVersion() {
        val after = VersionCheck.afterStart(AppSettings(), "26.41.1")
        assertEquals("26.41.1", after.lastRunVersion)
        assertFalse(after.whatChangedPending)
    }

    @Test
    fun aStartWithTheSameVersionChangesNothing() {
        val settings = AppSettings(lastRunVersion = "26.41.1")
        assertEquals(settings, VersionCheck.afterStart(settings, "26.41.1"))
        val pending = AppSettings(lastRunVersion = "26.41.1", whatChangedPending = true)
        assertEquals(pending, VersionCheck.afterStart(pending, "26.41.1"))
    }

    @Test
    fun anUpdateSetsTheNoticeAndTheNewVersion() {
        val after = VersionCheck.afterStart(AppSettings(lastRunVersion = "26.40.0"), "26.41.1")
        assertEquals("26.41.1", after.lastRunVersion)
        assertTrue(after.whatChangedPending)
        // A notice that the user did not read stays after a second update.
        val again = VersionCheck.afterStart(after.copy(lastRunVersion = "26.41.1"), "26.42.0")
        assertTrue(again.whatChangedPending)
    }

    @Test
    fun theWatcherWritesTheVersionAfterTheLoadOfTheFile() = runTest {
        val store = UiStoreFixture(this)
        store.appSettings.update { settings -> settings.copy(lastRunVersion = "26.40.0") }
        runCurrent()
        VersionWatcher(store.appSettings, "26.41.1").start(this)
        runCurrent()
        assertEquals("26.41.1", store.appSettings.settings.value.lastRunVersion)
        assertTrue(store.appSettings.settings.value.whatChangedPending)
        store.close()
    }

    @Test
    fun theStateHasTheVersionAndThePendingNotice() = runTest {
        val harness = Harness(this)
        runCurrent()
        val state = harness.presenter.uiState.value
        assertEquals("26.41.1", state.versionName)
        assertEquals(2641001, state.versionCode)
        assertFalse(state.whatChangedPending)
        harness.store.appSettings.update { settings -> settings.copy(whatChangedPending = true) }
        runCurrent()
        assertTrue(harness.presenter.uiState.value.whatChangedPending)
        harness.presenter.markWhatChangedRead()
        runCurrent()
        assertFalse(harness.store.appSettings.settings.value.whatChangedPending)
        assertFalse(harness.presenter.uiState.value.whatChangedPending)
        harness.close()
    }

    @Test
    fun aNoticeOpensWithTheTextOfEachOfItsFiles() = runTest {
        val read = mutableListOf<String>()
        val harness = Harness(this) { path ->
            read += path
            "text of $path".encodeToByteArray()
        }
        harness.presenter.openNotice("libusb")
        runCurrent()
        val view = assertNotNull(harness.presenter.uiState.value.openLicense)
        assertEquals("libusb", view.notice?.id)
        assertFalse(view.loading)
        assertEquals(listOf("notices_camera.txt", "licenses/libusb_lgpl_2_1.txt", "licenses/libusb_authors.txt"), read)
        assertEquals("text of notices_camera.txt", view.texts.first().text)
        harness.presenter.closeLicense()
        runCurrent()
        assertNull(harness.presenter.uiState.value.openLicense)
        harness.close()
    }

    @Test
    fun aFileThatDoesNotLoadGivesNoText() = runTest {
        val harness = Harness(this) { path -> if (path.endsWith("kodein_mit.txt")) error("no file") else ByteArray(0) }
        harness.presenter.openNotice("kodein")
        runCurrent()
        val view = assertNotNull(harness.presenter.uiState.value.openLicense)
        assertEquals(listOf(LicenseText("licenses/kodein_mit.txt", null)), view.texts)
        harness.presenter.openNotice("unknown-id")
        runCurrent()
        assertEquals("kodein", harness.presenter.uiState.value.openLicense?.notice?.id)
        harness.close()
    }

    @Test
    fun allNoticesOpenTheTextFileOfTheResources() = runTest {
        val harness = Harness(this) { path -> Res.readBytes("files/$path") }
        harness.presenter.openAllNotices()
        runCurrent()
        val view = assertNotNull(harness.presenter.uiState.value.openLicense)
        assertNull(view.notice)
        val text = assertNotNull(view.texts.single().text)
        assertTrue(text.startsWith("SuperDRO third-party notices"))
        assertTrue("Kodein DI" in text && "usb-serial-for-android" in text)
        harness.close()
    }

    @Test
    fun aHelpTopicOpensAndCloses() = runTest {
        val harness = Harness(this)
        harness.presenter.openHelp(HelpTopic.MachineProfiles)
        runCurrent()
        assertEquals(HelpTopic.MachineProfiles, harness.presenter.uiState.value.openHelp)
        harness.presenter.closeHelp()
        runCurrent()
        assertNull(harness.presenter.uiState.value.openHelp)
        harness.presenter.selectGroup(AboutGroup.Licenses)
        runCurrent()
        assertEquals(AboutGroup.Licenses, harness.presenter.uiState.value.group)
        harness.close()
    }
}
