package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindFactory
import org.kodein.di.bindProvider
import org.kodein.di.instance
import org.superdro.core.ui.settings.profiles.DeleteProfilePresenter
import org.superdro.core.ui.settings.profiles.MachineProfilesPresenter

/**
 * The bindings of the settings area: the machine profiles (see [settingsUiModule]). The delete
 * question has a factory with the profile ID.
 */
internal val settingsProfilesModule = DI.Module("settingsProfilesModule") {
    bindProvider { MachineProfilesPresenter(machines = instance(), connections = instance(), context = instance()) }
    bindFactory<String, DeleteProfilePresenter> { profileId ->
        DeleteProfilePresenter(
            profileId = profileId,
            machines = instance(),
            tools = instance(),
            tabStore = instance(),
            connections = instance(),
            context = instance(),
        )
    }
}
