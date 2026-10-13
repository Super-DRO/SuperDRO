package org.superdro.core.ui.settings.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import org.superdro.core.model.MuteScope
import org.superdro.core.model.TransportKind
import org.superdro.core.ui.settings.renderSettings
import org.superdro.core.ui.tabs.RecordingRequests
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.UiStoreFixture

/**
 * Draws the app settings screen to PNG files (`core/ui/build/screenshots/settings-app-*.png`) in
 * light and dark mode, in landscape and in portrait.
 */
class AppSettingsScreenshotTest {

    private fun render(name: String, group: AppSettingsGroup, prepare: (AppSettingsPresenter) -> Unit = {}) {
        val scope = TestScope()
        val store = UiStoreFixture(scope)
        val presenter = AppSettingsPresenter(store.appSettings, FakeBatteryOptimization(exempt = false), RecordingRequests(), scope.testPresenterContext())
        scope.testScheduler.advanceUntilIdle()
        presenter.selectGroup(group)
        prepare(presenter)
        scope.testScheduler.advanceUntilIdle()
        val files = renderSettings(name) {
            val state by presenter.uiState.collectAsState()
            AppSettingsScreen(state, presenter, AppSettingsLinks(), onBack = {})
        }
        assertTrue(files.all { file -> file.length() > 0 })
        presenter.close()
        store.close()
    }

    @Test
    fun theAppearanceGroup() = render("app-appearance", AppSettingsGroup.Appearance)

    @Test
    fun theSoundsGroupWithOneSwitchForEachMachine() = render("app-sounds", AppSettingsGroup.Sounds) { presenter ->
        presenter.setMuteScope(MuteScope.EachMachine)
    }

    @Test
    fun theConnectionGroup() = render("app-connection", AppSettingsGroup.Connection) { presenter ->
        presenter.setLinkTypeShown(TransportKind.Tcp, false)
    }

    @Test
    fun theMachinesGroup() = render("app-machines", AppSettingsGroup.Machines)

    @Test
    fun theDxfGroupWithAFieldThatIsNotValid() = render("app-dxf", AppSettingsGroup.DxfImport) { presenter ->
        presenter.setDxfText(DxfLimit.BlockLevels, "0")
    }
}
