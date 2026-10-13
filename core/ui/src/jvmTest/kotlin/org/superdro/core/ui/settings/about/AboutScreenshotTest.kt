package org.superdro.core.ui.settings.about

import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.resources.Res
import org.superdro.core.ui.settings.SettingsWindow
import org.superdro.core.ui.settings.renderSettings
import kotlinx.coroutines.runBlocking

/**
 * Draws the about screen to PNG files (`core/ui/build/screenshots/settings-about-*.png`) in light
 * and dark mode, in landscape and in portrait: each group, and an open license text.
 */
class AboutScreenshotTest {

    private object NoEvents : AboutEvents {
        override fun onSelectGroup(group: AboutGroup) = Unit
        override fun onMarkWhatChangedRead() = Unit
        override fun onOpenHelp(topic: org.superdro.core.ui.settings.help.HelpTopic) = Unit
        override fun onCloseHelp() = Unit
        override fun onOpenNotice(id: String) = Unit
        override fun onOpenAllNotices() = Unit
        override fun onCloseLicense() = Unit
    }

    private val base = AboutUiState(versionName = "26.41.1", versionCode = 2641001)

    @Test
    fun theVersionGroupWithTheNewNotice() {
        val files = renderSettings("about-version") { _ -> AboutScreen(base.copy(whatChangedPending = true), NoEvents, onBack = {}) }
        assertTrue(files.all { it.length() > 0 })
    }

    @Test
    fun theOtherGroups() {
        renderSettings("about-help") { _ -> AboutScreen(base.copy(group = AboutGroup.Help), NoEvents, onBack = {}) }
        renderSettings("about-licenses") { _ -> AboutScreen(base.copy(group = AboutGroup.Licenses), NoEvents, onBack = {}) }
        renderSettings("about-help-open") { _ ->
            AboutScreen(base.copy(group = AboutGroup.Help, openHelp = org.superdro.core.ui.settings.help.HelpTopic.Readout), NoEvents, onBack = {})
        }
    }

    @Test
    fun anOpenLicenseText() {
        val notice = ThirdPartyNotices.ALL.single { it.id == "kodein" }
        val text = runBlocking { Res.readBytes("files/licenses/kodein_mit.txt").decodeToString() }
        val view = LicenseView(notice, loading = false, texts = listOf(LicenseText(notice.files.single(), text)))
        renderSettings("about-license") { _ -> AboutScreen(base.copy(group = AboutGroup.Licenses, openLicense = view), NoEvents, onBack = {}) }
    }
}
