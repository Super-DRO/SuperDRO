package org.superdro.app.android

import android.content.Context
import android.content.Intent
import com.toxicbakery.logging.Arbor
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.store.json.TabStore
import org.superdro.platform.android.service.ConnectionServiceHost

/**
 * The parts of the connection service that the app gives (see [ConnectionServiceHost]).
 *
 * "Disconnect all" pauses each remembered machine in [TabStore] and disconnects each session.
 * Thus a sticky restart or the next app start does not connect them again. The command "Disconnect
 * all" of the overflow menu must
 * use the same binding.
 */
class AppServiceHost(
    private val manager: () -> ConnectionManager,
    private val tabs: () -> TabStore,
) : ConnectionServiceHost {

    override fun openAppIntent(context: Context, device: DeviceId?): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (device != null) putExtra(MainActivity.EXTRA_DEVICE_ID, device.value) }

    override fun disconnectAll() {
        log.i { "Disconnect all" }
        tabs().update { state -> state.copy(remembered = state.remembered.map { machine -> machine.copy(paused = true) }) }
        manager().disconnectAll()
    }

    private companion object {
        val log = Arbor.tag("AppServiceHost")
    }
}
