package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.ui.settings.about.AboutPresenter
import org.superdro.core.ui.settings.about.ReleaseFetcher
import org.superdro.core.ui.settings.about.VersionWatcher

/**
 * The bindings of the settings area: the about part (see [settingsUiModule]). The about presenter
 * is a provider: each entry gets its own presenter and closes it. The version watcher starts with
 * the graph, in the background ([bindBackgroundStart]), sets the "What changed" notice after an
 * update, and checks GitHub for a new release at most once a day.
 */
internal val settingsAboutModule = DI.Module("settingsAboutModule") {
    bindSingleton<ReleaseFetcher> { ReleaseFetcher.default(instance()) }
    bindProvider { AboutPresenter(appSettings = instance(), context = instance()) }
    bindSingleton {
        VersionWatcher(appSettings = instance(), releaseFetcher = instance())
            .also { watcher -> watcher.start(instance(tag = APP_SCOPE)) }
    }
    bindBackgroundStart<VersionWatcher>()
}
