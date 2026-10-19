package org.superdro.core.ui.settings.about

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.superdro.core.appversion.AppVersion
import org.superdro.core.appversion.ReleaseVersion
import org.superdro.core.model.AppSettings
import org.superdro.core.resources.Res
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.settings.help.HelpTopic

/** The groups of the about screen. */
enum class AboutGroup { Version, Help, Licenses }

/**
 * One license text of an open notice.
 *
 * @property path The path below `files/`, for example `licenses/apache_2_0.txt`.
 * @property text The text, or null when the file does not load.
 */
@Immutable
data class LicenseText(val path: String, val text: String?)

/**
 * The open license view: one notice, or all notices ([notice] null).
 *
 * @property texts The texts of the files of the notice. Empty while [loading].
 */
@Immutable
data class LicenseView(
    val notice: ThirdPartyNotice?,
    val loading: Boolean = true,
    val texts: List<LicenseText> = emptyList(),
)

/**
 * The state of the about screen.
 *
 * @property whatChangedPending True after an update until the user marks the "What changed" text as
 *   read. The text then shows first, with a "New" marker.
 * @property availableReleaseVersion The version of a newer release on GitHub, or `null`.
 * @property availableReleaseUrl The web URL of [availableReleaseVersion], or `null`.
 */
@Immutable
data class AboutUiState(
    val versionName: String,
    val versionCode: Int,
    val whatChangedPending: Boolean = false,
    val availableReleaseVersion: String? = null,
    val availableReleaseUrl: String? = null,
    val group: AboutGroup = AboutGroup.Version,
    val notices: List<ThirdPartyNotice> = ThirdPartyNotices.ALL,
    val whatChanged: List<StringResource> = WhatChanged.ITEMS,
    val helpTopics: List<HelpTopic> = HelpTopic.entries,
    val openHelp: HelpTopic? = null,
    val openLicense: LicenseView? = null,
)

/**
 * The presenter of the about screen: the version from `core:app-version`, the "What
 * changed" text, the help texts, and the third-party notices with their
 * license texts. The screen has only these groups.
 *
 * @param readFile Reads a file below `files/` of `core:resources`. A test gives its own reader.
 */
class AboutPresenter(
    private val appSettings: AppSettingsStore,
    context: PresenterContext,
    private val readFile: suspend (String) -> ByteArray = { path -> Res.readBytes("files/$path") },
    versionName: String = AppVersion.NAME,
    private val versionCode: Int = AppVersion.CODE,
) : ScopedPresenter(context) {

    private val local = MutableStateFlow(AboutUiState(versionName, versionCode))
    private var loadJob: Job? = null

    /** The state of the screen. */
    val uiState: StateFlow<AboutUiState> = combine(local, appSettings.settings) { state, settings ->
        state.withSettings(settings)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        local.value.withSettings(appSettings.settings.value),
    )

    private fun AboutUiState.withSettings(settings: AppSettings): AboutUiState {
        val availableVer = VersionCheck.availableReleaseVersion(settings, versionCode)
        val availableUrl = if (availableVer != null) {
            settings.availableReleaseUrl?.takeIf { it.isNotBlank() } ?: releaseTagUrl(availableVer)
        } else {
            null
        }
        return copy(
            whatChangedPending = settings.whatChangedPending,
            availableReleaseVersion = availableVer,
            availableReleaseUrl = availableUrl,
        )
    }

    /** Shows the group [group]. */
    fun selectGroup(group: AboutGroup) = local.update { state -> state.copy(group = group) }

    /** Marks the "What changed" text as read. */
    fun markWhatChangedRead() {
        appSettings.update { settings -> settings.copy(whatChangedPending = false) }
    }

    /** Marks the available release notification as seen. */
    fun markReleaseNotified() {
        appSettings.update(VersionCheck::acknowledgeRelease)
    }

    /** Marks the available release notification as seen. */
    fun acknowledgeRelease() = markReleaseNotified()

    /** Opens the help text of [topic]. */
    fun openHelp(topic: HelpTopic) = local.update { state -> state.copy(openHelp = topic) }

    /** Closes the help text. */
    fun closeHelp() = local.update { state -> state.copy(openHelp = null) }

    /** Opens the license texts of the notice [id]. An unknown ID does nothing. */
    fun openNotice(id: String) {
        val notice = local.value.notices.firstOrNull { item -> item.id == id } ?: return
        open(notice, notice.files)
    }

    /** Opens the text file with all notices. */
    fun openAllNotices() = open(null, listOf(ALL_NOTICES_FILE))

    /** Closes the license view. */
    fun closeLicense() {
        loadJob?.cancel()
        local.update { state -> state.copy(openLicense = null) }
    }

    private fun open(notice: ThirdPartyNotice?, files: List<String>) {
        loadJob?.cancel()
        local.update { state -> state.copy(openLicense = LicenseView(notice)) }
        loadJob = scope.launch {
            val texts = files.map { path -> LicenseText(path, load(path)) }
            local.update { state ->
                val open = state.openLicense ?: return@update state
                if (open.notice != notice) state else state.copy(openLicense = open.copy(loading = false, texts = texts))
            }
        }
    }

    private suspend fun load(path: String): String? = try {
        readFile(path).decodeToString()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

/**
 * Applies [VersionCheck] at the start of the app and runs the daily GitHub release check.
 *
 * After the load of `app-settings.json`, it writes the version of this start, sets
 * `whatChangedPending` after an update, and checks GitHub at most once in 24 hours when
 * [releaseFetcher] is not [ReleaseFetcher.None].
 */
class VersionWatcher(
    private val appSettings: AppSettingsStore,
    private val current: String = AppVersion.NAME,
    private val currentCode: Int = ReleaseVersion.codeOf(current) ?: AppVersion.CODE,
    private val releaseFetcher: ReleaseFetcher = ReleaseFetcher.None,
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {

    /** Starts the check in [scope]. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        appSettings.awaitLoaded()
        val initial = appSettings.settings.value
        if (VersionCheck.afterStart(initial, current, currentCode) != initial) {
            appSettings.update { settings -> VersionCheck.afterStart(settings, current, currentCode) }
        }
        if (releaseFetcher === ReleaseFetcher.None) return@launch
        while (currentCoroutineContext().isActive) {
            appSettings.settings.first { settings -> settings.checkForUpdates }
            val delayMs = VersionCheck.nextCheckDelayMs(appSettings.settings.value, nowEpochMs())
            if (delayMs > 0L) {
                delay(delayMs)
                continue
            }
            val remote = releaseFetcher.fetchLatest()
            if (remote != null) {
                val now = nowEpochMs()
                appSettings.update { settings ->
                    VersionCheck.afterReleaseCheck(settings, remote, now, currentCode)
                }
                delay(VersionCheck.CHECK_INTERVAL_MS)
            } else {
                delay(VersionCheck.RETRY_INTERVAL_MS)
            }
        }
    }
}
