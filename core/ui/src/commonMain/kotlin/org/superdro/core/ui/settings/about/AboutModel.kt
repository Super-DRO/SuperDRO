package org.superdro.core.ui.settings.about

import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.StringResource
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
 * The rule of the "What changed" notice. At each start of the app, the version of the
 * last start goes into `AppSettings.lastRunVersion`:
 *
 * | Last start | Result |
 * |---|---|
 * | None (a new install) | No notice |
 * | The same version | No change |
 * | A different version (an update) | `whatChangedPending` becomes true |
 */
object VersionCheck {

    /** Gives [settings] after a start of the app with the version [current]. */
    fun afterStart(settings: AppSettings, current: String): AppSettings {
        val last = settings.lastRunVersion
        if (last == current) return settings
        return settings.copy(
            lastRunVersion = current,
            whatChangedPending = settings.whatChangedPending || last != null,
        )
    }
}
