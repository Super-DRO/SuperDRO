package org.superdro.core.ui.settings.about

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AppSettings
import org.superdro.core.resources.Res
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.UiStoreFixture

/** The presenter of the about screen, the "What changed" notice, and the daily release check. */
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
    fun anUpdateToTheAvailableReleaseClearsTheStoredRelease() {
        val before = AppSettings(
            lastRunVersion = "26.41.1",
            availableReleaseVersion = "26.42.2",
            availableReleaseUrl = "https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.2",
        )
        val after = VersionCheck.afterStart(before, "26.42.2", currentCode = 2642002)
        assertEquals("26.42.2", after.lastRunVersion)
        assertTrue(after.whatChangedPending)
        assertNull(after.availableReleaseVersion)
        assertNull(after.availableReleaseUrl)
    }

    @Test
    fun parseLatestReleaseJsonAcceptsOnlyACleanPublishedRelease() {
        val release = parseLatestReleaseJson(
            """
            {
              "tag_name": "v26.42.2",
              "html_url": "https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.2",
              "draft": false,
              "prerelease": false
            }
            """.trimIndent(),
        )
        assertEquals(
            RemoteRelease("26.42.2", "https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.2"),
            release,
        )
        // A bad URL falls back to the release tag URL.
        val fallback = parseLatestReleaseJson(
            """{"tag_name": "v26.42.2", "html_url": "https://example.com/other"}""",
        )
        assertEquals(
            RemoteRelease("26.42.2", "https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.2"),
            fallback,
        )
        // Drafts, prereleases, bad tags, and broken JSON give null.
        assertNull(parseLatestReleaseJson("""{"tag_name": "v26.42.2", "draft": true}"""))
        assertNull(parseLatestReleaseJson("""{"tag_name": "v26.42.2", "prerelease": true}"""))
        assertNull(parseLatestReleaseJson("""{"tag_name": "v26.42.2-rc1"}"""))
        assertNull(parseLatestReleaseJson("not json"))
    }

    @Test
    fun versionCheckRulesRespectTheDailyIntervalAndSingleNotification() {
        val base = AppSettings()
        assertTrue(VersionCheck.shouldCheckRelease(base, nowEpochMs = 100_000L))
        assertFalse(VersionCheck.shouldCheckRelease(base.copy(checkForUpdates = false), nowEpochMs = 100_000L))

        val checked = VersionCheck.afterReleaseCheck(
            settings = base,
            remote = RemoteRelease("26.42.2", "https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.2"),
            nowEpochMs = 100_000L,
            currentCode = 2641001,
        )
        assertEquals(100_000L, checked.lastReleaseCheckEpochMs)
        assertEquals("26.42.2", checked.availableReleaseVersion)
        assertEquals("26.42.2", VersionCheck.unseenReleaseVersion(checked, currentCode = 2641001))
        assertFalse(VersionCheck.shouldCheckRelease(checked, nowEpochMs = 100_000L + VersionCheck.CHECK_INTERVAL_MS - 1L))
        assertTrue(VersionCheck.shouldCheckRelease(checked, nowEpochMs = 100_000L + VersionCheck.CHECK_INTERVAL_MS))
        assertTrue(VersionCheck.shouldCheckRelease(checked, nowEpochMs = 50_000L))

        val acknowledged = VersionCheck.acknowledgeRelease(checked)
        assertEquals("26.42.2", acknowledged.notifiedReleaseVersion)
        assertNull(VersionCheck.unseenReleaseVersion(acknowledged, currentCode = 2641001))
        assertEquals("26.42.2", VersionCheck.availableReleaseVersion(acknowledged, currentCode = 2641001))
        assertNull(VersionCheck.unseenReleaseVersion(checked.copy(checkForUpdates = false), currentCode = 2641001))
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
    fun theWatcherChecksAtMostOnceEachDayEvenWithMultipleReleases() = runTest {
        val store = UiStoreFixture(this)
        var calls = 0
        var latestRelease = RemoteRelease("26.42.1", "https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.1")
        val fetcher = ReleaseFetcher {
            calls += 1
            latestRelease
        }
        val baseEpochMs = 1_700_000_000_000L
        val job = VersionWatcher(
            appSettings = store.appSettings,
            current = "26.41.1",
            currentCode = 2641001,
            releaseFetcher = fetcher,
            nowEpochMs = { baseEpochMs + testScheduler.currentTime },
        ).start(this)
        runCurrent()
        assertEquals(1, calls)
        assertEquals("26.42.1", store.appSettings.settings.value.availableReleaseVersion)

        // A second release comes out two hours later on the same day: no second check happens.
        latestRelease = RemoteRelease("26.42.2", "https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.2")
        advanceTimeBy(2 * 3_600_000L)
        runCurrent()
        assertEquals(1, calls)
        assertEquals("26.42.1", store.appSettings.settings.value.availableReleaseVersion)

        // Just before 24 hours: still only one check.
        advanceTimeBy(VersionCheck.CHECK_INTERVAL_MS - 2 * 3_600_000L - 1L)
        runCurrent()
        assertEquals(1, calls)

        // At 24 hours: the next daily check finds the newer release.
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, calls)
        assertEquals("26.42.2", store.appSettings.settings.value.availableReleaseVersion)

        job.cancel()
        store.close()
    }

    @Test
    fun theWatcherRetriesAfterOneHourOnNetworkFailureAndRespectsTheSwitch() = runTest {
        val store = UiStoreFixture(this)
        store.appSettings.update { settings -> settings.copy(checkForUpdates = false) }
        runCurrent()
        var calls = 0
        var response: RemoteRelease? = null
        val fetcher = ReleaseFetcher {
            calls += 1
            response
        }
        val baseEpochMs = 1_700_000_000_000L
        val job = VersionWatcher(
            appSettings = store.appSettings,
            current = "26.41.1",
            currentCode = 2641001,
            releaseFetcher = fetcher,
            nowEpochMs = { baseEpochMs + testScheduler.currentTime },
        ).start(this)
        runCurrent()
        advanceTimeBy(VersionCheck.CHECK_INTERVAL_MS)
        runCurrent()
        assertEquals(0, calls)

        // Turning the switch on starts a check; a network failure retries after 1 hour.
        store.appSettings.update { settings -> settings.copy(checkForUpdates = true) }
        runCurrent()
        assertEquals(1, calls)
        assertEquals(0L, store.appSettings.settings.value.lastReleaseCheckEpochMs)

        response = RemoteRelease("26.42.3", "https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.3")
        advanceTimeBy(VersionCheck.RETRY_INTERVAL_MS - 1L)
        runCurrent()
        assertEquals(1, calls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, calls)
        assertEquals("26.42.3", store.appSettings.settings.value.availableReleaseVersion)

        job.cancel()
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
        harness.store.appSettings.update { settings ->
            settings.copy(whatChangedPending = true, availableReleaseVersion = "26.42.2")
        }
        runCurrent()
        assertTrue(harness.presenter.uiState.value.whatChangedPending)
        assertEquals("26.42.2", harness.presenter.uiState.value.availableReleaseVersion)
        assertEquals(
            "https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.2",
            harness.presenter.uiState.value.availableReleaseUrl,
        )
        harness.presenter.markWhatChangedRead()
        harness.presenter.markReleaseNotified()
        runCurrent()
        assertFalse(harness.store.appSettings.settings.value.whatChangedPending)
        assertFalse(harness.presenter.uiState.value.whatChangedPending)
        assertEquals("26.42.2", harness.store.appSettings.settings.value.notifiedReleaseVersion)
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
