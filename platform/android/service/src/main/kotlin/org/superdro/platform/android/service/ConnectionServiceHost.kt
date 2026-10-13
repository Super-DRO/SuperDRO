package org.superdro.platform.android.service

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.Flow
import org.superdro.core.connection.DeviceId

/**
 * The parts of the connection service that the app gives through DI: the activity of the
 * notification tap, and the action "Disconnect all". `app:android` binds it, because this module
 * does not know `MainActivity` or the tab store.
 */
interface ConnectionServiceHost {

    /**
     * Gives the intent that opens the main activity (`singleTop`) on the tab of [device]. Null
     * [device]: the activity opens with no tab change.
     */
    fun openAppIntent(context: Context, device: DeviceId?): Intent

    /**
     * "Disconnect all": the remembered machines pause, so that no
     * restore connects them again, and each session goes to `Disconnected`.
     */
    fun disconnectAll()
}

/**
 * The app setting of the partial wake lock: on by default. `platform:android:di`
 * binds it to `AppSettings.wakeLockWhileLinked`.
 */
fun interface WakeLockSetting {
    /** The value now, then each change. */
    fun enabled(): Flow<Boolean>
}
