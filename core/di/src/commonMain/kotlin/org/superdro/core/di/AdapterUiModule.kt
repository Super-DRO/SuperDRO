package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindEagerSingleton
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.adapterTabEffects
import org.superdro.core.ui.navigation.EntryRegistry

/** The tag of the eager binding that registers the tab effects of the adapter area. */
internal const val ADAPTER_TAB_EFFECTS: String = "adapterTabEffects"

/**
 * The bindings of the adapter area of `core:ui`:
 * the one [AdapterUiDeps] object. Each presenter of the area gets it and the arguments of its
 * route. The presenters close with their entries, so the graph binds no presenter object.
 *
 * When the graph starts, the module also registers the tab effects of the area (the "new adapter"
 * prompt) on the one [EntryRegistry] of the app. The effects need the graph.
 */
internal val adapterUiModule = DI.Module("adapterUiModule") {
    bindSingleton {
        AdapterUiDeps(
            connections = instance(),
            machines = instance(),
            adapters = instance(),
            probes = instance(),
            filePicker = instance(),
            sounds = instance(),
            context = instance(),
        )
    }
    bindEagerSingleton(tag = ADAPTER_TAB_EFFECTS) {
        instance<EntryRegistry>().adapterTabEffects()
        true
    }
}
