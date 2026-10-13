package org.superdro.platform.android.service

import org.superdro.core.connection.KeepAliveSummary

/** The command of one `onStartCommand` call of [ConnectionService]. */
enum class ServiceCommand {
    /** The keep-alive started the service for a summary that is not null. */
    Start,

    /**
     * Connect again to each remembered machine that is not paused: a sticky restart (a null
     * intent) or the start at boot.
     */
    Restore,

    /** The action "Disconnect all" of the notification. */
    DisconnectAll,
}

/**
 * The start and stop rules of [ConnectionService] with no Android type. A JVM unit test examines
 * them.
 */
object ConnectionServiceRules {

    /** The intent action of a start from the keep-alive. */
    const val ACTION_START: String = "org.superdro.service.START"

    /** The intent action of the start at boot. */
    const val ACTION_RESTORE: String = "org.superdro.service.RESTORE"

    /** The intent action of "Disconnect all" in the notification. */
    const val ACTION_DISCONNECT_ALL: String = "org.superdro.service.DISCONNECT_ALL"

    /**
     * The time that the service waits after a restore for a summary that is not null. The
     * keep-alive controller waits 300 ms before it sends a change. After this
     * time with no summary, the restore gave no session, and the service stops.
     */
    const val RESTORE_SETTLE_MS: Long = 3000L

    /** The ID of the notification of the service. */
    const val NOTIFICATION_ID: Int = 1

    /** The ID of the notification "Open SuperDRO to connect your machines". */
    const val BOOT_FALLBACK_NOTIFICATION_ID: Int = 2

    /**
     * Gives the command of a start. Android gives a null intent to a sticky restart. Thus a null
     * intent is a restore.
     *
     * @param hasIntent False for a sticky restart.
     * @param action The action of the intent.
     */
    fun commandOf(hasIntent: Boolean, action: String?): ServiceCommand = when {
        !hasIntent -> ServiceCommand.Restore
        action == ACTION_RESTORE -> ServiceCommand.Restore
        action == ACTION_DISCONNECT_ALL -> ServiceCommand.DisconnectAll
        else -> ServiceCommand.Start
    }

    /**
     * True when the service stops at once, after its call of `startForeground`: for "Disconnect
     * all", and for a start from the keep-alive when the summary is already null. A restore waits
     * for the sessions first.
     */
    fun stopsAtOnce(command: ServiceCommand, summary: KeepAliveSummary?): Boolean = when (command) {
        ServiceCommand.DisconnectAll -> true
        ServiceCommand.Start -> summary == null
        ServiceCommand.Restore -> false
    }

    /**
     * True when the service stops for a new summary: the summary is null after a summary that
     * was not null. Before the first summary that is not null, the service waits (a restore can
     * be in progress).
     */
    fun stopsForSummary(summary: KeepAliveSummary?, sawSummary: Boolean): Boolean = summary == null && sawSummary
}
