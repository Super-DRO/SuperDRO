package org.superdro.platform.android.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.superdro.core.connection.BatteryOptimization

/**
 * The battery optimization state of SuperDRO. The settings
 * show [exempt] and a link to the system setting. Samsung devices put apps to sleep,
 * so the app asks for the exemption at the first connect.
 *
 * Android sends no broadcast for a change of the state. `MainActivity` calls [refresh] in `onResume`.
 */
class AndroidBatteryOptimization(context: Context) : BatteryOptimization {

    private val context: Context = context.applicationContext
    private val mutableExempt = MutableStateFlow(read())

    override val exempt: StateFlow<Boolean> = mutableExempt.asStateFlow()

    override fun refresh() {
        mutableExempt.value = read()
    }

    private fun read(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    companion object {
        /**
         * The system dialog that asks the user to exempt SuperDRO
         * (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, permission
         * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). The app shows the reason first.
         */
        // SuperDRO keeps the links to a machine open with the screen off. The app does not
        // go to Google Play, so it can make this request.
        @SuppressLint("BatteryLife")
        fun requestIntent(context: Context): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

        /** The system list of the battery optimization settings. */
        fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}
