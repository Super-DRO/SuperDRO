package org.superdro.app.android

import android.app.Application
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.provider
import org.superdro.app.android.files.AndroidFilePicker
import org.superdro.app.android.sound.AndroidUiSounds
import org.superdro.core.di.coreDiModule
import org.superdro.core.ui.sound.UiSounds
import org.superdro.core.ui.tabs.DisconnectAllCommand
import org.superdro.core.util.FilePicker
import org.superdro.platform.android.di.platformModule
import org.superdro.platform.android.service.ConnectionServiceHost

/**
 * The module of the app: the common graph, the platform bindings, the debug adapter, and the
 * bindings of the contracts that `app:android` implements: [UiSounds], [FilePicker],
 * and [ConnectionServiceHost].
 */
internal val appModule = DI.Module("appModule") {
    import(coreDiModule)
    import(platformModule)
    // Binds DebugAdapter. The debug source set binds the fake adapter. The release source set
    // binds NoDebugAdapter, so a release APK holds no part of the fake adapter.
    import(debugModule)

    bindSingleton<UiSounds> { AndroidUiSounds() }
    bindSingleton { AndroidFilePicker(instance<Application>()) }
    bindSingleton<FilePicker> { instance<AndroidFilePicker>() }
    bindSingleton<ConnectionServiceHost> { AppServiceHost(manager = provider(), tabs = provider()) }
    // "Disconnect all" of the overflow menu is the command of the notification.
    bindSingleton<DisconnectAllCommand> { DisconnectAllCommand { instance<ConnectionServiceHost>().disconnectAll() } }
}
