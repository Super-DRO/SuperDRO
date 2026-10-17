package org.superdro.core.model

import kotlinx.serialization.Serializable

/** The theme mode: the system mode, or always dark. */
@Serializable
enum class ThemeMode { System, Dark }

/** The place of the graphical view in portrait. */
@Serializable
enum class GraphicalViewPlacement { BelowReadout, AboveReadout, FullScreen }

/** The scope of the mute switch. */
@Serializable
enum class MuteScope {
    /** One switch for the app mutes all sounds, also the connection tones. */
    App,

    /** One switch for each machine ([MachineProfile.muteSounds]). */
    EachMachine,
}

/**
 * An action that a hardware key can start in the selected tab. A later
 * version can add values. A value that an older version does not know gives
 * [None] (`coerceInputValues`).
 */
@Serializable
enum class KeyAction {
    None,
    ZeroAxis,
    SetAxis,
    ZeroAll,
    ToggleAbsoluteIncremental,
    ToggleUnits,
    NextSubDatum,
    PreviousSubDatum,
    OpenKeypad,

    /** The hold of one axis: the first press holds the shown value, the second press sets it. */
    HoldAxis,

    /** The half (center) of one axis in incremental mode. */
    HalfAxis,
}

/**
 * One entry of the key map.
 *
 * @property keyCode The key code of the key event.
 * @property deviceDescriptor The HID device, or null for each device.
 * @property axis The axis of an axis action, or null.
 */
@Serializable
data class KeyMapping(
    val keyCode: Long = 0L,
    val deviceDescriptor: String? = null,
    val action: KeyAction = KeyAction.None,
    val axis: AxisId? = null,
)

/**
 * The global settings: the "App" settings, the set of shown hints, and the key map. The file is
 * `app-settings.json` (`AppSettingsStore`).
 */
@Serializable
data class AppSettings(
    val schemaVersion: Int = SCHEMA_VERSION,
    val themeMode: ThemeMode = ThemeMode.System,
    val graphicalViewInPortrait: GraphicalViewPlacement = GraphicalViewPlacement.BelowReadout,
    /** The part of the window width that the readout column uses in the expanded layout. */
    val readoutPaneFraction: Float = 0.45f,
    /** The part of the content height that the graphical view uses in portrait. */
    val graphicalPaneWeight: Float = 0.6f,
    /** Large buttons: 25 % larger touch targets. */
    val largeButtons: Boolean = false,
    val muteAllSounds: Boolean = false,
    val muteScope: MuteScope = MuteScope.App,
    /** Hide the status bar and the navigation bar. */
    val fullScreen: Boolean = true,
    /** The partial wake lock while a link is open. */
    val wakeLockWhileLinked: Boolean = true,
    /**
     * True after the app asked one time for the battery optimization exemption, at the first
     * connect. After that, only the settings ask again.
     */
    val batteryExemptionAsked: Boolean = false,
    /**
     * True after the app asked one time for `POST_NOTIFICATIONS`, at the first connect. A restart
     * does not ask again.
     */
    val notificationPermissionAsked: Boolean = false,
    /**
     * True after the app asked one time for the local network permission, at the first TCP connect.
     * A restart does not ask again.
     */
    val localNetworkPermissionAsked: Boolean = false,
    val showHints: Boolean = true,
    val showSetupReminder: Boolean = true,
    /** The developer option "Send `#get_sig` once". */
    val sendGetSigOnce: Boolean = false,
    /** The link types that the add-device sheet does not show. One link type stays shown. */
    val hiddenLinkTypes: Set<TransportKind> = emptySet(),
    /** The hints that the app showed one time. */
    val shownHints: Set<String> = emptySet(),
    val keyMappings: List<KeyMapping> = emptyList(),
    /**
     * False while the app uses its default key map. The key codes of the
     * default map come from the platform, so `core:ui` makes the map. The first change of the
     * user writes the full map to [keyMappings] and sets this flag.
     */
    val keyMapCustomized: Boolean = false,
    /** The limits of the DXF import. */
    val dxfImportLimits: DxfImportLimits = DxfImportLimits(),
    /** The version name of the last start of the app, or null before the first start. */
    val lastRunVersion: String? = null,
    /** True after an update until the user closes the "What changed" text of the about screen. */
    val whatChangedPending: Boolean = false,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * One remembered machine. [paused] stops the automatic connect.
 */
@Serializable
data class RememberedMachine(
    val profileId: String = "",
    val paused: Boolean = false,
)

/**
 * The tab state: the remembered machines, their pause flags, and the last selected machine. The
 * file is `tabs.json` (`TabStore`).
 */
@Serializable
data class TabState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val remembered: List<RememberedMachine> = emptyList(),
    val selectedProfileId: String? = null,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}
