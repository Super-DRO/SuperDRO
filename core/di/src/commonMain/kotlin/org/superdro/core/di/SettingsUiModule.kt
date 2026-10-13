package org.superdro.core.di

import org.kodein.di.DI

/**
 * The bindings of the settings area of `core:ui`: the
 * presenters of the machine settings, the app settings, the machine profiles, the about screen,
 * the log screen, and the key mapping, with the log sink and the record of the byte streams.
 * Each screen has its own module file, so that the parts of the area do not change one file.
 */
internal val settingsUiModule = DI.Module("settingsUiModule") {
    import(settingsMachineModule)
    import(settingsAppModule)
    import(settingsProfilesModule)
    import(settingsAboutModule)
    import(settingsLogsModule)
    import(settingsKeysModule)
}
