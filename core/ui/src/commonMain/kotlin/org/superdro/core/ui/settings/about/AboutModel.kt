package org.superdro.core.ui.settings.about

import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.StringResource
import org.superdro.core.appversion.AppVersion
import org.superdro.core.appversion.ReleaseVersion
import org.superdro.core.model.AppSettings
import org.superdro.core.resources.Res
import org.superdro.core.resources.settings_about_change_background
import org.superdro.core.resources.settings_about_change_languages
import org.superdro.core.resources.settings_about_change_links
import org.superdro.core.resources.settings_about_change_settings
import org.superdro.core.resources.settings_about_change_tabs
import org.superdro.core.resources.settings_about_change_undo
import org.superdro.core.resources.settings_about_notice_fonts
import org.superdro.core.resources.settings_about_notice_icons
import org.superdro.core.resources.settings_about_notice_libraries
import org.superdro.core.resources.settings_about_notice_native

/** The groups of the notices of the about screen. */
enum class NoticeGroup(val label: StringResource) {
    Library(Res.string.settings_about_notice_libraries),
    NativeLibrary(Res.string.settings_about_notice_native),
    Font(Res.string.settings_about_notice_fonts),
    Icon(Res.string.settings_about_notice_icons),
}

/**
 * The notice of one component of an APK: its name, version, license,
 * copyright line, and license texts. The name, the license, and the copyright line are technical
 * names: they show in English in each language.
 *
 * @property files The license texts, as paths below `files/` of `core:resources`, for example
 *   `licenses/apache_2_0.txt`.
 * @property source The URL of the source of the component.
 */
@Immutable
data class ThirdPartyNotice(
    val id: String,
    val group: NoticeGroup,
    val name: String,
    val version: String,
    val license: String,
    val copyright: String,
    val files: List<String>,
    val source: String,
)

/** The path below `files/` of the text file with all notices. */
const val ALL_NOTICES_FILE: String = "licenses/third_party_notices.txt"

/**
 * The "What changed" text of this version: a short list of SuperDRO
 * texts. SuperDRO shows this text in the about screen after an update.
 */
object WhatChanged {
    val ITEMS: List<StringResource> = listOf(
        Res.string.settings_about_change_tabs,
        Res.string.settings_about_change_background,
        Res.string.settings_about_change_links,
        Res.string.settings_about_change_undo,
        Res.string.settings_about_change_settings,
        Res.string.settings_about_change_languages,
    )
}

/**
 * The rules of the "What changed" notice and the daily release check.
 *
 * At each start of the app, the version of the last start goes into `AppSettings.lastRunVersion`:
 *
 * | Last start | Result |
 * |---|---|
 * | None (a new install) | No notice |
 * | The same version | No change |
 * | A different version (an update) | `whatChangedPending` becomes true |
 */
object VersionCheck {

    /** The minimum time in milliseconds between two release checks (24 hours). */
    const val CHECK_INTERVAL_MS: Long = 86_400_000L

    /** The retry delay in milliseconds after a failed network check (1 hour). */
    const val RETRY_INTERVAL_MS: Long = 3_600_000L

    /**
     * Gives [settings] after a start of the app with the version [current] and [currentCode].
     *
     * Clears [AppSettings.availableReleaseVersion] and [AppSettings.availableReleaseUrl] when the
     * stored release is not newer than [currentCode].
     */
    fun afterStart(
        settings: AppSettings,
        current: String,
        currentCode: Int = ReleaseVersion.codeOf(current) ?: AppVersion.CODE,
    ): AppSettings {
        val last = settings.lastRunVersion
        val withRun = if (last == current) {
            settings
        } else {
            settings.copy(
                lastRunVersion = current,
                whatChangedPending = settings.whatChangedPending || last != null,
            )
        }
        val available = withRun.availableReleaseVersion
        return if (available != null && !ReleaseVersion.isNewer(available, currentCode)) {
            withRun.copy(availableReleaseVersion = null, availableReleaseUrl = null)
        } else {
            withRun
        }
    }

    /**
     * Gives `true` when [settings] permits a release check at [nowEpochMs].
     */
    fun shouldCheckRelease(settings: AppSettings, nowEpochMs: Long): Boolean {
        if (!settings.checkForUpdates) return false
        val lastCheck = settings.lastReleaseCheckEpochMs
        if (lastCheck <= 0L) return true
        if (nowEpochMs < lastCheck) return true
        return nowEpochMs - lastCheck >= CHECK_INTERVAL_MS
    }

    /**
     * Gives the delay in milliseconds until the next release check after [nowEpochMs].
     */
    fun nextCheckDelayMs(settings: AppSettings, nowEpochMs: Long): Long {
        if (shouldCheckRelease(settings, nowEpochMs)) return 0L
        val elapsed = nowEpochMs - settings.lastReleaseCheckEpochMs
        return (CHECK_INTERVAL_MS - elapsed).coerceAtLeast(1L)
    }

    /**
     * Gives [settings] after a release check at [nowEpochMs] with result [remote].
     */
    fun afterReleaseCheck(
        settings: AppSettings,
        remote: RemoteRelease?,
        nowEpochMs: Long,
        currentCode: Int = AppVersion.CODE,
    ): AppSettings {
        if (remote == null) {
            return settings.copy(lastReleaseCheckEpochMs = nowEpochMs)
        }
        return if (ReleaseVersion.isNewer(remote.version, currentCode)) {
            settings.copy(
                lastReleaseCheckEpochMs = nowEpochMs,
                availableReleaseVersion = remote.version,
                availableReleaseUrl = remote.htmlUrl,
            )
        } else {
            settings.copy(
                lastReleaseCheckEpochMs = nowEpochMs,
                availableReleaseVersion = null,
                availableReleaseUrl = null,
            )
        }
    }

    /**
     * Gives [AppSettings.availableReleaseVersion] when it is newer than [currentCode], or `null`.
     */
    fun availableReleaseVersion(
        settings: AppSettings,
        currentCode: Int = AppVersion.CODE,
    ): String? = settings.availableReleaseVersion?.takeIf { version ->
        ReleaseVersion.isNewer(version, currentCode)
    }

    /**
     * Gives the available release version when the user did not yet see its notification, or `null`.
     */
    fun unseenReleaseVersion(
        settings: AppSettings,
        currentCode: Int = AppVersion.CODE,
    ): String? {
        if (!settings.checkForUpdates) return null
        return availableReleaseVersion(settings, currentCode)?.takeIf { version ->
            version != settings.notifiedReleaseVersion
        }
    }

    /**
     * Marks [AppSettings.availableReleaseVersion] as seen in [AppSettings.notifiedReleaseVersion].
     */
    fun acknowledgeRelease(settings: AppSettings): AppSettings {
        val available = settings.availableReleaseVersion ?: return settings
        return settings.copy(notifiedReleaseVersion = available)
    }
}
