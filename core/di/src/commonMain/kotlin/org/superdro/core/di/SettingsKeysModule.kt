package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.instance
import org.superdro.core.ui.settings.keys.KeyMappingPresenter

/**
 * The bindings of the settings area: the key mapping (see [settingsUiModule]). The entry of the
 * screen owns its presenter and closes it when the entry leaves the back stack.
 */
internal val settingsKeysModule = DI.Module("settingsKeysModule") {
    bindProvider { KeyMappingPresenter(appSettings = instance(), context = instance()) }
}
