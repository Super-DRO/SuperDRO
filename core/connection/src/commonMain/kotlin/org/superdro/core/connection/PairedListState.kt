package org.superdro.core.connection

/**
 * The state of the paired Bluetooth list that a screen shows.
 *
 * Android cannot read the paired devices while the radio is off or while the app does not have
 * `BLUETOOTH_CONNECT`. Then the discovery gives an empty list, but the tablet can have paired
 * adapters. Thus an empty list means "no paired adapter" only in [NoPaired].
 */
enum class PairedListState {

    /** The app does not have the Bluetooth permission. The screen shows only the permission request. */
    PermissionMissing,

    /** The Bluetooth radio is off. The screen shows only "Bluetooth is off" and the control to turn it on. */
    RadioOff,

    /** The radio is on, the app has the permission, and no adapter is paired. */
    NoPaired,

    /** The radio is on, the app has the permission, and the list has one or more adapters. */
    Paired,
    ;

    /** True when the screen can show the list: the radio is on and the app has the permission. */
    val readable: Boolean get() = this == NoPaired || this == Paired

    companion object {

        /**
         * Gives the state of the paired list from the Bluetooth [capability] and the [paired]
         * adapters of the discovery. The permission comes first, then the radio, then the list.
         */
        fun of(capability: LinkCapability, paired: List<DiscoveredAdapter>): PairedListState {
            val cause = (capability as? LinkCapability.Unavailable)?.cause
            return when {
                cause == LinkFailure.PermissionMissing -> PermissionMissing
                cause == LinkFailure.RadioOff -> RadioOff
                paired.isEmpty() -> NoPaired
                else -> Paired
            }
        }
    }
}
