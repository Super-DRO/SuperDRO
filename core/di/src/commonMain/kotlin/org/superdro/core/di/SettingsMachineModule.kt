package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindFactory
import org.kodein.di.instance
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.ui.settings.machine.MachineSettingsArgs
import org.superdro.core.ui.settings.machine.MachineSettingsPresenter

/**
 * The bindings of the settings area: the machine settings (see [settingsUiModule]). The presenter
 * is a factory of the profile ID, the tab flag, and the snackbar of the frame.
 */
internal val settingsMachineModule = DI.Module("settingsMachineModule") {
    bindFactory<MachineSettingsArgs, MachineSettingsPresenter> { args ->
        MachineSettingsPresenter(
            profileId = args.profileId,
            machines = instance(),
            adapters = instance(),
            connections = instance(),
            appSettings = instance<AppSettingsStore>().settings,
            context = instance(),
            showMessage = args.showMessage,
            tab = args.tab,
        )
    }
}
