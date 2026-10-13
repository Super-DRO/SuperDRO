package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.instance
import org.superdro.core.ui.settings.app.AppSettingsPresenter

/**
 * The bindings of the settings area: the app settings (see [settingsUiModule]). The presenter
 * reads the battery optimization state of the platform binding `BatteryOptimization`.
 */
internal val settingsAppModule = DI.Module("settingsAppModule") {
    bindProvider {
        AppSettingsPresenter(appSettings = instance(), battery = instance(), requests = instance(), context = instance())
    }
}
