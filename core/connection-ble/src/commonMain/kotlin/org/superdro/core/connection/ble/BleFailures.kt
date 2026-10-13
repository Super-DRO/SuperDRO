package org.superdro.core.connection.ble

import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothNotEnabledException
import dev.bluefalcon.core.BluetoothPermissionException
import dev.bluefalcon.core.BluetoothResettingException
import dev.bluefalcon.core.BluetoothUnsupportedException
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure

/** True when [this] is a platform permission failure, for example a `SecurityException` on Android. */
internal expect fun Throwable.isPermissionDenial(): Boolean

/**
 * Gives the link failure of an exception from a BlueFalcon connect or a discovery. A permission
 * failure is [LinkFailure.PermissionMissing]. A radio that is off, absent, or in a reset is
 * [LinkFailure.RadioOff]. Each other failure is [fallback].
 */
fun bleFailureOf(failure: Throwable, fallback: LinkFailure = LinkFailure.Refused): LinkFailure = when {
    failure is BluetoothPermissionException || failure.isPermissionDenial() -> LinkFailure.PermissionMissing
    failure is BluetoothNotEnabledException ||
        failure is BluetoothUnsupportedException ||
        failure is BluetoothResettingException -> LinkFailure.RadioOff
    else -> fallback
}

/**
 * Gives the BLE part of the Bluetooth capability from the BlueFalcon radio
 * state: [BluetoothManagerState.Ready] is [LinkCapability.Available], and
 * [BluetoothManagerState.NotReady] is [LinkFailure.RadioOff]. On Android, the Bluetooth permission
 * comes from the capability part of `platform:android:bluetooth`.
 */
fun BluetoothManagerState.toLinkCapability(): LinkCapability = when (this) {
    BluetoothManagerState.Ready -> LinkCapability.Available
    BluetoothManagerState.NotReady -> LinkCapability.Unavailable(LinkFailure.RadioOff)
}
