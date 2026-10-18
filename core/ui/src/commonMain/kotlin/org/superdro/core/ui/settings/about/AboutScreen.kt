package org.superdro.core.ui.settings.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.route_about
import org.superdro.core.resources.settings_about_all_notices
import org.superdro.core.resources.settings_about_app_license
import org.superdro.core.resources.settings_about_group_help
import org.superdro.core.resources.settings_about_group_licenses
import org.superdro.core.resources.settings_about_group_version
import org.superdro.core.resources.settings_about_help_intro
import org.superdro.core.resources.settings_about_license_loading
import org.superdro.core.resources.settings_about_license_missing
import org.superdro.core.resources.settings_about_licenses_intro
import org.superdro.core.resources.settings_about_mark_read
import org.superdro.core.resources.settings_about_new
import org.superdro.core.resources.settings_about_open_release
import org.superdro.core.resources.settings_about_source
import org.superdro.core.resources.settings_about_summary
import org.superdro.core.resources.settings_about_update_available
import org.superdro.core.resources.settings_about_updated
import org.superdro.core.resources.settings_about_version
import org.superdro.core.resources.settings_about_version_code
import org.superdro.core.resources.settings_about_what_changed
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.settings.components.SettingsGroupItem
import org.superdro.core.ui.settings.components.SettingsGroupLayout
import org.superdro.core.ui.settings.components.SettingsNote
import org.superdro.core.ui.settings.components.SettingsSection
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.theme.LocalTouchTargets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import org.superdro.core.resources.ic_arrow_back
import org.superdro.core.resources.settings_about_back_to_list
import org.superdro.core.resources.settings_about_help_pick
import org.superdro.core.resources.settings_about_license_pick
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.theme.AccentButtons
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.NavigationEventInfo

/** The test tag of the version text. */
const val ABOUT_VERSION_TAG: String = "about-version"

/** The test tag of the "Mark as read" button of the "What changed" text. */
const val ABOUT_MARK_READ_TAG: String = "about-mark-read"

/** The test tag of the "New" marker of the "What changed" text. */
const val ABOUT_NEW_TAG: String = "about-new"

/** The test tag of the "Update available" section. */
const val ABOUT_UPDATE_AVAILABLE_TAG: String = "about-update-available"

/** The test tag of the button that opens the GitHub Release page. */
const val ABOUT_OPEN_RELEASE_TAG: String = "about-open-release"

/** The test tag of the text of an open license. */
const val LICENSE_TEXT_TAG: String = "license-text"

/** The test tag of the text of an open help topic. */
const val HELP_TEXT_TAG: String = "help-text"

/** The test tag of the detail pane of the help group and of the licenses group. */
const val ABOUT_DETAIL_TAG: String = "about-detail"

/** The test tag of the row back to the list (the layouts that are not expanded). */
const val ABOUT_BACK_TO_LIST_TAG: String = "about-back-to-list"

/** The width of the list of the help group and of the licenses group in the expanded layout. */
private val LIST_PANE_WIDTH = 340.dp

/** The test tag of the row "All notices as text". */
const val ALL_NOTICES_TAG: String = "about-all-notices"

/** The test tag of the row of the notice [id]. */
fun noticeTag(id: String): String = "notice:$id"

/** The test tag of the help row of [topic]. */
fun helpRowTag(topic: HelpTopic): String = "about-help:${topic.name}"

/** The events of the about screen. */
interface AboutEvents {
    fun onSelectGroup(group: AboutGroup)
    fun onMarkWhatChangedRead()
    fun onOpenRelease() = Unit
    fun onOpenHelp(topic: HelpTopic)
    fun onCloseHelp()
    fun onOpenNotice(id: String)
    fun onOpenAllNotices()
    fun onCloseLicense()
}

/** The [AboutEvents] of an [AboutPresenter]. */
fun AboutPresenter.events(): AboutEvents = object : AboutEvents {
    override fun onSelectGroup(group: AboutGroup) = selectGroup(group)
    override fun onMarkWhatChangedRead() = markWhatChangedRead()
    override fun onOpenRelease() = markReleaseNotified()
    override fun onOpenHelp(topic: HelpTopic) = openHelp(topic)
    override fun onCloseHelp() = closeHelp()
    override fun onOpenNotice(id: String) = openNotice(id)
    override fun onOpenAllNotices() = openAllNotices()
    override fun onCloseLicense() = closeLicense()
}

/**
 * The about screen: three groups (a tab row in portrait).
 *
 * - Version and changes: the app name, the version and the version code from `core:app-version`,
 *   and the "What changed" text. After an update the text shows first, with a
 *   "New" marker (an icon and a text) and "Mark as read".
 * - Help: the in-app help texts of the main screens.
 * - Licenses: each third-party notice.
 *
 * Help and Licenses are a list and a detail pane (the dialog rule): in the expanded layout the text of
 * the selected row shows in the open area at the right of the list. In the other layouts the text
 * replaces the list, with a row back to the list. No dialog and no sheet shows general
 * information.
 */
@Composable
fun AboutScreen(state: AboutUiState, events: AboutEvents, onBack: () -> Unit) {
    val groups = listOf(
        SettingsGroupItem(AboutGroup.Version, stringResource(Res.string.settings_about_group_version)),
        SettingsGroupItem(AboutGroup.Help, stringResource(Res.string.settings_about_group_help)),
        SettingsGroupItem(AboutGroup.Licenses, stringResource(Res.string.settings_about_group_licenses)),
    )
    ScreenScaffold(title = stringResource(Res.string.route_about), onBack = onBack) {
        SettingsGroupLayout(
            groups = groups,
            selected = state.group,
            onSelect = events::onSelectGroup,
            fillsPane = { group -> group != AboutGroup.Version },
        ) { group ->
            when (group) {
                AboutGroup.Version -> VersionGroup(state, events)
                AboutGroup.Help -> HelpGroup(state, events)
                AboutGroup.Licenses -> LicensesGroup(state, events)
            }
        }
    }
}

@Composable
private fun ColumnScope.VersionGroup(state: AboutUiState, events: AboutEvents) {
    val availableVersion = state.availableReleaseVersion
    if (availableVersion != null) {
        UpdateAvailableSection(
            version = availableVersion,
            releaseUrl = state.availableReleaseUrl,
            onOpenRelease = events::onOpenRelease,
        )
    }
    if (state.whatChangedPending) WhatChangedSection(state, events)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("SuperDRO", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(Res.string.settings_about_version, state.versionName),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(ABOUT_VERSION_TAG),
        )
        Text(
            stringResource(Res.string.settings_about_version_code, state.versionCode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        SettingsNote(stringResource(Res.string.settings_about_summary), color = MaterialTheme.colorScheme.onSurface)
        SettingsNote(stringResource(Res.string.settings_about_app_license))
    }
    if (!state.whatChangedPending) WhatChangedSection(state, events)
}

@Composable
private fun UpdateAvailableSection(
    version: String,
    releaseUrl: String?,
    onOpenRelease: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val url = releaseUrl ?: releaseTagUrl(version)
    SettingsSection(
        stringResource(Res.string.settings_about_new),
        modifier = Modifier.testTag(ABOUT_UPDATE_AVAILABLE_TAG),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            NewMarker(tag = null)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(Res.string.settings_about_update_available, version),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(
                onClick = {
                    onOpenRelease()
                    runCatching { uriHandler.openUri(url) }
                },
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(ABOUT_OPEN_RELEASE_TAG),
            ) {
                Text(stringResource(Res.string.settings_about_open_release))
            }
        }
    }
}

@Composable
private fun WhatChangedSection(state: AboutUiState, events: AboutEvents) {
    SettingsSection(stringResource(Res.string.settings_about_what_changed)) {
        if (state.whatChangedPending) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                NewMarker()
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(Res.string.settings_about_updated, state.versionName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        state.whatChanged.forEach { item -> Bullet(stringResource(item)) }
        if (state.whatChangedPending) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(
                    onClick = events::onMarkWhatChangedRead,
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(ABOUT_MARK_READ_TAG),
                ) { Text(stringResource(Res.string.settings_about_mark_read)) }
            }
        }
    }
}

/** The "New" marker: an icon and a text on `tertiaryContainer`. */
@Composable
private fun NewMarker(tag: String? = ABOUT_NEW_TAG) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.settings_about_new), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HelpGroup(state: AboutUiState, events: AboutEvents) {
    val open = state.openHelp
    ListDetailPane(
        detailOpen = open != null,
        emptyText = stringResource(Res.string.settings_about_help_pick),
        onCloseDetail = events::onCloseHelp,
        list = {
            SettingsNote(stringResource(Res.string.settings_about_help_intro))
            state.helpTopics.forEach { topic ->
                PaneListItem(
                    title = stringResource(topic.title),
                    selected = topic == open,
                    onClick = { events.onOpenHelp(topic) },
                    tag = helpRowTag(topic),
                )
            }
        },
        detail = {
            if (open != null) {
                Text(
                    stringResource(open.title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.size(12.dp))
                SelectionContainer {
                    Text(
                        stringResource(open.text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag(HELP_TEXT_TAG),
                    )
                }
            }
        },
    )
}

@Composable
private fun LicensesGroup(state: AboutUiState, events: AboutEvents) {
    val open = state.openLicense
    ListDetailPane(
        detailOpen = open != null,
        emptyText = stringResource(Res.string.settings_about_license_pick),
        onCloseDetail = events::onCloseLicense,
        list = {
            SettingsNote(stringResource(Res.string.settings_about_licenses_intro))
            NoticeGroup.entries.forEach { group ->
                val items = state.notices.filter { notice -> notice.group == group }
                if (items.isEmpty()) return@forEach
                SettingsSection(stringResource(group.label)) {
                    items.forEach { notice ->
                        PaneListItem(
                            title = notice.name,
                            summary = "${notice.version} · ${notice.license}",
                            selected = open != null && open.notice?.id == notice.id,
                            onClick = { events.onOpenNotice(notice.id) },
                            tag = noticeTag(notice.id),
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            PaneListItem(
                title = stringResource(Res.string.settings_about_all_notices),
                selected = open != null && open.notice == null,
                onClick = events::onOpenAllNotices,
                tag = ALL_NOTICES_TAG,
            )
        },
        detail = { if (open != null) LicenseText(open) },
    )
}

/**
 * A list and a detail pane. In the expanded layout the list is at the left and the
 * detail shows in the open area at the right ([emptyText] when no row is selected). In the other
 * layouts the detail replaces the list, with a row back to the list, and the back gesture closes
 * the detail first.
 */
@Composable
private fun ListDetailPane(
    detailOpen: Boolean,
    emptyText: String,
    onCloseDetail: () -> Unit,
    list: @Composable ColumnScope.() -> Unit,
    detail: @Composable ColumnScope.() -> Unit,
) {
    if (LocalWindowLayout.current.expanded) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(LIST_PANE_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                content = list,
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (detailOpen) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp).testTag(ABOUT_DETAIL_TAG),
                    content = detail,
                )
            } else {
                Box(Modifier.weight(1f).fillMaxHeight().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(emptyText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else if (detailOpen) {
        NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onCloseDetail)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag(ABOUT_DETAIL_TAG)) {
            TextButton(
                onClick = onCloseDetail,
                colors = AccentButtons.text(),
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(ABOUT_BACK_TO_LIST_TAG),
            ) {
                Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_about_back_to_list))
            }
            detail()
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), content = list)
    }
}

/** One row of the list of a [ListDetailPane]. The selected row uses `secondaryContainer`. */
@Composable
private fun PaneListItem(title: String, selected: Boolean, onClick: () -> Unit, tag: String, summary: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (selected) scheme.secondaryContainer else scheme.surface,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag(tag)
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(
            Modifier.heightIn(min = LocalTouchTargets.current.button).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The license texts of one notice (or of all notices) in the detail pane. */
@Composable
private fun ColumnScope.LicenseText(view: LicenseView) {
    val notice = view.notice
    Text(
        notice?.name ?: stringResource(Res.string.settings_about_all_notices),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    if (notice != null) {
        Text("${notice.version} · ${notice.license}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(notice.copyright, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(Res.string.settings_about_source, notice.source),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (view.loading) {
        SettingsNote(stringResource(Res.string.settings_about_license_loading))
    }
    view.texts.forEach { item ->
        Text(
            item.path.substringAfterLast('/'),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
        )
        val text = item.text
        if (text == null) {
            SettingsNote(stringResource(Res.string.settings_about_license_missing), color = MaterialTheme.colorScheme.error)
        } else {
            SelectionContainer {
                Text(
                    reflowLicenseText(text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(LICENSE_TEXT_TAG),
                )
            }
        }
    }
}

/**
 * Joins the lines of each paragraph of a license file, so that the text wraps to the width of the
 * pane. A blank line ends a paragraph. The words and their order do not change.
 */
internal fun reflowLicenseText(text: String): String =
    text.trimEnd().replace("\r\n", "\n").split(Regex("\n[ \t]*\n"))
        .joinToString("\n\n") { paragraph -> paragraph.lines().joinToString(" ") { line -> line.trim() }.trim() }
