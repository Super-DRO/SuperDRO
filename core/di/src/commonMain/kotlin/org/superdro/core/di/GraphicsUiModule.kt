package org.superdro.core.di

import kotlin.time.Clock
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.ui.graphics.GraphicsDependencies

/**
 * The bindings of the graphics area of `core:ui`: the one [GraphicsDependencies] of the presenters
 * of the graphical view, the
 * probe functions, the measurement mode, and the drawings.
 */
internal val graphicsUiModule = DI.Module("graphicsUiModule") {
    bindSingleton {
        GraphicsDependencies(
            connections = instance(),
            machines = instance(),
            adapters = instance(),
            tools = instance(),
            probes = instance(),
            drawings = instance(),
            measurementLog = instance(),
            storeIO = instance(),
            filePicker = instance(),
            sounds = instance(),
            appSettings = instance(),
            context = instance(),
            wallClock = Clock.System,
        )
    }
}
