package org.superdro.app.android

import android.app.Application
import android.os.StrictMode
import com.toxicbakery.logging.Arbor
import com.toxicbakery.logging.LogCatSeedling
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.store.StoreIOProvider
import org.superdro.platform.android.camera.CameraProcess
import org.superdro.platform.android.store.StoreIOProviderAndroid

/**
 * The application object. It builds the Kodein graph with `DI.lazy`.
 * It starts no connection: the service starts only from the visible activity, a sticky restart,
 * or the boot. The restore of the links is at the start of
 * `MainActivity`.
 *
 * In the processes `:camera` and `:usbattach` of the camera feature, the start-up stops at once
 * (`CameraProcess.isAuxiliary`): no database, no settings, no log seedling, and no adapter link.
 */
class SuperDroApplication : Application(), DIAware {

    override val di: DI by DI.lazy {
        bindSingleton<Application> { this@SuperDroApplication }
        import(appModule)

        // The Android file access. The Android module storeIOProviderModule of core:di is empty,
        // because core/* must not depend on platform/*.
        bindProvider<StoreIOProvider> {
            StoreIOProviderAndroid(context = instance<Application>())
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (CameraProcess.isAuxiliary(this)) return
        if (BuildConfig.DEBUG) enableStrictMode()
        // The Arbor logs: Logcat in a debug build. The log screen adds its own seedling.
        if (BuildConfig.DEBUG) Arbor.sow(LogCatSeedling())
        log.d { "Application started, version ${BuildConfig.VERSION_NAME}" }
    }

    /**
     * The StrictMode policies of a debug build. Logcat shows each disk read and each disk write
     * on the main thread, and each closable object that the app did not close. A release build
     * does not call this function, so R8 removes it.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
    }

    private companion object {
        val log = Arbor.tag("SuperDroApplication")
    }
}
