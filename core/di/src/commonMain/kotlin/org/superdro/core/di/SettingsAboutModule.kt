package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindEagerSingleton
import org.kodein.di.bindProvider
import org.kodein.di.instance
import org.superdro.core.ui.settings.about.AboutPresenter
import org.superdro.core.ui.settings.about.VersionWatcher

/**
 * The bindings of the settings area: the about part (see [settingsUiModule]). The about presenter
 * is a provider: each entry gets its own presenter and closes it. The version watcher starts with
 * the graph and sets the "What changed" notice after an update.
 */
internal val settingsAboutModule = DI.Module("settingsAboutModule") {
    bindProvider { AboutPresenter(appSettings = instance(), context = instance()) }
    bindEagerSingleton { VersionWatcher(appSettings = instance()).also { watcher -> watcher.start(instance(tag = APP_SCOPE)) } }
}
