package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.ui.functions.common.FunctionServices

/**
 * The bindings of the machine functions area of `core:ui`: one [FunctionServices] object with the
 * repositories, the file picker, the sounds,
 * and the presenter context. The presenters of the area get it from the entries. They are not
 * bindings, because each entry or tab owns its presenters.
 */
internal val functionsUiModule = DI.Module("functionsUiModule") {
    bindSingleton {
        FunctionServices(
            machines = instance(),
            tools = instance(),
            drawings = instance(),
            probes = instance(),
            adapters = instance(),
            tabStore = instance(),
            appSettings = instance(),
            connections = instance(),
            filePicker = instance(),
            sounds = instance(),
            context = instance(),
        )
    }
}
