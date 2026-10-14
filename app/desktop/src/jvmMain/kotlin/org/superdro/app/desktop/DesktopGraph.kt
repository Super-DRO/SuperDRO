package org.superdro.app.desktop

import java.awt.Frame
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.app.desktop.files.AwtFilePicker
import org.superdro.app.desktop.sound.DesktopUiSounds
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.debugadapter.NoDebugAdapter
import org.superdro.core.di.coreDiModule
import org.superdro.core.fakeadapter.FakeDebugAdapter
import org.superdro.core.ui.sound.UiSounds
import org.superdro.core.ui.tabs.DisconnectAllCommand
import org.superdro.core.ui.tabs.PauseAndDisconnectAll
import org.superdro.core.util.FilePicker
import org.superdro.platform.desktop.di.desktopPlatformModule
import org.superdro.platform.desktop.serial.JSerialCommPorts
import org.superdro.platform.desktop.serial.SerialPorts
import org.superdro.platform.desktop.store.DesktopStore

/** Holds the window of the app, so that a file dialog has its parent. */
class WindowHolder {
    @Volatile
    var window: Frame? = null
}

/**
 * Makes the DI graph of the desktop app: the common graph, the desktop
 * platform module (after the common graph, with the override of the `StoreIOProvider`), and the
 * app module. The graph opens the database file at once: the keep-alive controller and its
 * connection manager are eager singletons. The graph makes the demo profiles in the background.
 *
 * @param store The files of the app.
 * @param serialPorts The serial ports of the system. A test gives a fake.
 */
fun desktopDI(
    options: DesktopOptions,
    store: DesktopStore = DesktopStore(options.dataDirectory),
    serialPorts: () -> SerialPorts = { JSerialCommPorts() },
): DI = DI {
    import(coreDiModule)
    import(desktopPlatformModule(store, serialPorts), allowOverride = true)
    import(desktopAppModule(options))
}

/**
 * The bindings of the contracts that `app:desktop` implements (the table of `coreDiModule`): the
 * [DebugAdapter], [UiSounds], the [FilePicker], and the [DisconnectAllCommand].
 * The desktop has no connection service, so "Disconnect all" is the common [PauseAndDisconnectAll].
 */
fun desktopAppModule(options: DesktopOptions): DI.Module = DI.Module("desktopAppModule") {
    // Only the argument --demo binds the fake adapter.
    bindSingleton<DebugAdapter> { if (options.demo) FakeDebugAdapter() else NoDebugAdapter }
    bindSingleton { WindowHolder() }
    bindSingleton<UiSounds> { DesktopUiSounds() }
    bindSingleton<FilePicker> {
        val holder = instance<WindowHolder>()
        AwtFilePicker(parent = { holder.window })
    }
    bindSingleton<DisconnectAllCommand> { PauseAndDisconnectAll(instance(), instance()) }
}
