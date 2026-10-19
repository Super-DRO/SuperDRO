package org.superdro.core.ui.settings.about

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import org.superdro.core.ui.settings.SettingsThemed
import org.superdro.core.ui.settings.SettingsWindow
import org.superdro.core.ui.settings.components.settingsGroupTag
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.UiStoreFixture

/** The main flows of the about screen. */
@OptIn(ExperimentalTestApi::class)
class AboutScreenUiTest {

    private class Harness {
        val scope = TestScope()
        val store = UiStoreFixture(scope)
        val presenter = AboutPresenter(
            store.appSettings,
            scope.testPresenterContext(),
            readFile = { path -> "The text of $path".encodeToByteArray() },
            versionName = "26.41.1",
            versionCode = 2641001,
        )

        fun settle() = scope.testScheduler.advanceUntilIdle()
    }

    @Test
    fun theVersionShowsAndMarkAsReadClearsTheNotice() = runComposeUiTest {
        val harness = Harness()
        harness.store.appSettings.update { settings -> settings.copy(whatChangedPending = true) }
        harness.settle()
        setContent {
            SettingsThemed(dark = false, window = SettingsWindow.Landscape) {
                val state by harness.presenter.uiState.collectAsState()
                AboutScreen(state, harness.presenter.events(), onBack = {})
            }
        }
        harness.settle()
        waitForIdle()
        onNodeWithTag(ABOUT_VERSION_TAG).assertIsDisplayed()
        onNodeWithTag(ABOUT_NEW_TAG).assertIsDisplayed()
        onNodeWithTag(ABOUT_MARK_READ_TAG).performClick()
        harness.settle()
        waitForIdle()
        assertFalse(harness.store.appSettings.settings.value.whatChangedPending)
        assertEquals(0, onAllNodesWithTag(ABOUT_NEW_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun anAvailableReleaseShowsInVersionGroupAndMarksNotifiedOnOpen() = runComposeUiTest {
        val harness = Harness()
        val openedUris = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
            }
        }
        harness.store.appSettings.update { settings ->
            settings.copy(
                availableReleaseVersion = "26.42.2",
                availableReleaseUrl = "https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.2",
            )
        }
        harness.settle()
        setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                SettingsThemed(dark = false, window = SettingsWindow.Landscape) {
                    val state by harness.presenter.uiState.collectAsState()
                    AboutScreen(state, harness.presenter.events(), onBack = {})
                }
            }
        }
        harness.settle()
        waitForIdle()
        onNodeWithTag(ABOUT_UPDATE_AVAILABLE_TAG).assertIsDisplayed()
        onNodeWithTag(ABOUT_OPEN_RELEASE_TAG).assertIsDisplayed().performClick()
        harness.settle()
        waitForIdle()
        assertEquals("26.42.2", harness.store.appSettings.settings.value.notifiedReleaseVersion)
        assertEquals(
            listOf("https://github.com/Super-DRO/SuperDRO/releases/tag/v26.42.2"),
            openedUris,
        )
    }

    @Test
    fun theScreenHasThreeGroupsAndNoListOfDifferences() = runComposeUiTest {
        // The about screen has only the version, help, and license groups.
        assertEquals(listOf(AboutGroup.Version, AboutGroup.Help, AboutGroup.Licenses), AboutGroup.entries.toList())
        val harness = Harness()
        setContent {
            SettingsThemed(dark = false, window = SettingsWindow.Landscape) {
                val state by harness.presenter.uiState.collectAsState()
                AboutScreen(state, harness.presenter.events(), onBack = {})
            }
        }
        harness.settle()
        waitForIdle()
        AboutGroup.entries.forEach { group -> onNodeWithTag(settingsGroupTag(group)).assertIsDisplayed() }
        assertEquals(0, onAllNodesWithText("Differences", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aTapOnANoticeShowsItsLicenseText() = runComposeUiTest {
        val harness = Harness()
        setContent {
            SettingsThemed(dark = false, window = SettingsWindow.Landscape) {
                val state by harness.presenter.uiState.collectAsState()
                AboutScreen(state, harness.presenter.events(), onBack = {})
            }
        }
        onNodeWithTag(settingsGroupTag(AboutGroup.Licenses)).performClick()
        harness.settle()
        waitForIdle()
        onNodeWithTag(noticeTag("kodein")).performScrollTo().performClick()
        harness.settle()
        waitForIdle()
        assertEquals("kodein", harness.presenter.uiState.value.openLicense?.notice?.id)
        onNodeWithTag(LICENSE_TEXT_TAG).assertIsDisplayed()
    }

    @Test
    fun aHelpRowOpensTheHelpText() = runComposeUiTest {
        val harness = Harness()
        setContent {
            SettingsThemed(dark = true, window = SettingsWindow.Portrait) {
                val state by harness.presenter.uiState.collectAsState()
                AboutScreen(state, harness.presenter.events(), onBack = {})
            }
        }
        onNodeWithTag(settingsGroupTag(AboutGroup.Help)).performClick()
        harness.settle()
        waitForIdle()
        onNodeWithTag(helpRowTag(HelpTopic.KeyMapping)).performScrollTo().performClick()
        harness.settle()
        waitForIdle()
        assertEquals(HelpTopic.KeyMapping, harness.presenter.uiState.value.openHelp)
        assertTrue(onAllNodesWithTag("help-text").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun inLandscapeTheTextShowsBesideTheListWithNoDialog() = runComposeUiTest {
        // The dialog rule: general information shows in the open area at the right, not in a dialog.
        val harness = Harness()
        setContent {
            SettingsThemed(dark = false, window = SettingsWindow.Landscape) {
                val state by harness.presenter.uiState.collectAsState()
                AboutScreen(state, harness.presenter.events(), onBack = {})
            }
        }
        onNodeWithTag(settingsGroupTag(AboutGroup.Help)).performClick()
        harness.settle()
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(ABOUT_DETAIL_TAG).fetchSemanticsNodes().size)
        onNodeWithTag(helpRowTag(HelpTopic.Readout)).performClick()
        harness.settle()
        waitForIdle()
        onNodeWithTag(ABOUT_DETAIL_TAG).assertIsDisplayed()
        onNodeWithTag(HELP_TEXT_TAG).assertIsDisplayed()
        onNodeWithTag(helpRowTag(HelpTopic.Readout)).assertIsDisplayed()
        onNodeWithTag(helpRowTag(HelpTopic.AddMachine)).assertIsDisplayed()
        assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size)
        onNodeWithTag(settingsGroupTag(AboutGroup.Licenses)).performClick()
        harness.settle()
        waitForIdle()
        onNodeWithTag(noticeTag("kodein")).performScrollTo().performClick()
        harness.settle()
        waitForIdle()
        onNodeWithTag(LICENSE_TEXT_TAG).assertIsDisplayed()
        onNodeWithTag(noticeTag("kodein")).assertIsDisplayed()
        assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size)
    }

    @Test
    fun inPortraitTheTextReplacesTheListUntilBackToTheList() = runComposeUiTest {
        val harness = Harness()
        setContent {
            SettingsThemed(dark = false, window = SettingsWindow.Portrait) {
                val state by harness.presenter.uiState.collectAsState()
                AboutScreen(state, harness.presenter.events(), onBack = {})
            }
        }
        onNodeWithTag(settingsGroupTag(AboutGroup.Help)).performClick()
        harness.settle()
        waitForIdle()
        onNodeWithTag(helpRowTag(HelpTopic.Readout)).performClick()
        harness.settle()
        waitForIdle()
        onNodeWithTag(HELP_TEXT_TAG).assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag(helpRowTag(HelpTopic.Readout)).fetchSemanticsNodes().size)
        onNodeWithTag(ABOUT_BACK_TO_LIST_TAG).performClick()
        harness.settle()
        waitForIdle()
        assertEquals(null, harness.presenter.uiState.value.openHelp)
        onNodeWithTag(helpRowTag(HelpTopic.Readout)).assertIsDisplayed()
    }
}
