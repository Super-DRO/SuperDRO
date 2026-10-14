package org.superdro.core.ui.tabs

import androidx.compose.runtime.Immutable
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.resources.Res
import org.superdro.core.resources.app_cause_bluetooth_off
import org.superdro.core.resources.app_cause_connect_failed
import org.superdro.core.resources.app_cause_link_lost
import org.superdro.core.resources.app_cause_no_answer
import org.superdro.core.resources.app_cause_no_network
import org.superdro.core.resources.app_cause_permission_missing
import org.superdro.core.resources.app_cause_unplugged
import org.superdro.core.resources.app_status_awaiting_data
import org.superdro.core.resources.app_status_connected
import org.superdro.core.resources.app_status_connecting
import org.superdro.core.resources.app_status_no_data
import org.superdro.core.resources.app_status_offline
import org.superdro.core.resources.app_status_reconnecting
import org.superdro.core.ui.text.UiText

/** The status icon of a tab. Each icon goes with a text. */
enum class TabStatusIcon {
    /** `Connected`: a filled circle. */
    Connected,

    /** `Connecting` or `AwaitingData`: a small progress ring. */
    Progress,

    /** `Reconnecting`: a progress ring with the attempt number in the text. */
    Reconnecting,

    /** `NoData`: a warning icon. */
    Warning,

    /** `Failed` or `WaitingForDevice`: an error icon with the cause. */
    Error,

    /** `Disconnected`: an empty circle. */
    Offline,
}

/**
 * The color roles of a link status. The roles are container
 * pairs with more than 7 : 1 in the two schemes. `primary` is not a status color.
 */
enum class StatusRoles {
    /** `primaryContainer` with `onPrimaryContainer`. */
    Connected,

    /** `tertiaryContainer` with `onTertiaryContainer`: a link that connects or reconnects. */
    Reconnect,

    /** `errorContainer` with `onErrorContainer`. */
    Failure,

    /** `surfaceContainerHighest` with `onSurfaceVariant`: no link. */
    Neutral,
}

/**
 * The status of one tab: an icon, a short text, and the color roles.
 *
 * @property attempt The attempt number of `Reconnecting`, or null.
 */
@Immutable
data class TabStatus(
    val icon: TabStatusIcon,
    val text: UiText,
    val roles: StatusRoles,
    val attempt: Int? = null,
)

/**
 * The status rules of the tab label:
 *
 * | Session state | Icon | Text | Roles |
 * |---|---|---|---|
 * | `Connected` | filled circle | "Connected" | Connected |
 * | `Connecting(1)` | progress ring | "Connecting" | Reconnect |
 * | `Connecting(n)`, n > 1 | ring and attempt | "Reconnecting (n)" | Reconnect |
 * | `AwaitingData` | progress ring | "Waiting for data" | Reconnect |
 * | `Reconnecting(n)` | ring and attempt | "Reconnecting (n)" | Reconnect |
 * | `NoData` | warning | "No data" | Reconnect |
 * | `WaitingForDevice`, `Failed` | error | the cause, for example "Bluetooth off" or "Unplugged" | Failure |
 * | `Disconnected` | empty circle | "Offline" | Neutral |
 *
 * A `Connecting` state with an attempt number above 1 is a part of the reconnect series. Thus the
 * status stays "Reconnecting", and the number increases when a new attempt starts.
 */
object TabStatusRules {

    /** Gives the status of a tab with a session in [state]. */
    fun of(state: SessionState): TabStatus = when (state) {
        is SessionState.Connected -> TabStatus(TabStatusIcon.Connected, UiText.of(Res.string.app_status_connected), StatusRoles.Connected)
        is SessionState.Connecting -> if (state.attempt > 1) {
            reconnecting(state.attempt)
        } else {
            TabStatus(TabStatusIcon.Progress, UiText.of(Res.string.app_status_connecting), StatusRoles.Reconnect)
        }
        SessionState.AwaitingData -> TabStatus(TabStatusIcon.Progress, UiText.of(Res.string.app_status_awaiting_data), StatusRoles.Reconnect)
        is SessionState.Reconnecting -> reconnecting(state.attempt)
        SessionState.NoData -> TabStatus(TabStatusIcon.Warning, UiText.of(Res.string.app_status_no_data), StatusRoles.Reconnect)
        is SessionState.WaitingForDevice -> TabStatus(TabStatusIcon.Error, causeText(state.cause), StatusRoles.Failure)
        is SessionState.Failed -> TabStatus(TabStatusIcon.Error, causeText(state.cause), StatusRoles.Failure)
        SessionState.Disconnected -> TabStatus(TabStatusIcon.Offline, UiText.of(Res.string.app_status_offline), StatusRoles.Neutral)
    }

    private fun reconnecting(attempt: Int): TabStatus =
        TabStatus(TabStatusIcon.Reconnecting, UiText.of(Res.string.app_status_reconnecting, attempt), StatusRoles.Reconnect, attempt = attempt)

    /** Gives the short text of the cause of a link failure. */
    fun causeText(cause: LinkFailure): UiText = UiText.of(
        when (cause) {
            LinkFailure.RadioOff -> Res.string.app_cause_bluetooth_off
            LinkFailure.DeviceGone -> Res.string.app_cause_unplugged
            LinkFailure.NetworkOff -> Res.string.app_cause_no_network
            LinkFailure.PermissionMissing -> Res.string.app_cause_permission_missing
            LinkFailure.Refused -> Res.string.app_cause_connect_failed
            LinkFailure.NoAnswer -> Res.string.app_cause_no_answer
            LinkFailure.Lost -> Res.string.app_cause_link_lost
        },
    )
}
