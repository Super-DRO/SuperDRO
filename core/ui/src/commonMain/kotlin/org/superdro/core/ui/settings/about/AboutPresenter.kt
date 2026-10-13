package org.superdro.core.ui.settings.about

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.superdro.core.appversion.AppVersion
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
 */
@Immutable
data class AboutUiState(
    val versionName: String,
    val versionCode: Int,
    val whatChangedPending: Boolean = false,
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
    versionCode: Int = AppVersion.CODE,
) : ScopedPresenter(context) {

    private val local = MutableStateFlow(AboutUiState(versionName, versionCode))
    private var loadJob: Job? = null

    /** The state of the screen. */
    val uiState: StateFlow<AboutUiState> = combine(local, appSettings.settings) { state, settings ->
        state.copy(whatChangedPending = settings.whatChangedPending)
    }.stateIn(scope, SharingStarted.Eagerly, local.value.copy(whatChangedPending = appSettings.settings.value.whatChangedPending))

    /** Shows the group [group]. */
    fun selectGroup(group: AboutGroup) = local.update { state -> state.copy(group = group) }

    /** Marks the "What changed" text as read. */
    fun markWhatChangedRead() {
        appSettings.update { settings -> settings.copy(whatChangedPending = false) }
    }

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
 * Applies [VersionCheck] at the start of the app: after the load of `app-settings.json`,
 * it writes the version of this start and, after an update, sets `whatChangedPending`. `core:di`
 * makes one object at the start of the graph.
 */
class VersionWatcher(private val appSettings: AppSettingsStore, private val current: String = AppVersion.NAME) {

    /** Starts the check in [scope]. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        appSettings.awaitLoaded()
        if (appSettings.settings.value.lastRunVersion != current) {
            appSettings.update { settings -> VersionCheck.afterStart(settings, current) }
        }
    }
}
