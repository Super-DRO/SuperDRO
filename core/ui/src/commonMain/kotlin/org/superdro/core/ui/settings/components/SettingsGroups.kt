package org.superdro.core.ui.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor

/** One group of a settings screen: its key and its title. */
@Immutable
data class SettingsGroupItem<K>(val key: K, val title: String)

/** The test tag of the group item of [key]. */
fun settingsGroupTag(key: Any): String = "settings-group:$key"

/**
 * The layout of a settings screen with groups:
 *
 * - The expanded layout (a landscape tablet): the list of the groups at the left, and the rows of
 *   the selected group at the right.
 * - The other layouts: a scrollable tab row of the groups above the rows of the selected group.
 *
 * The presenter holds the selected group, so the group stays when the tab changes. In light mode
 * the selection uses `secondary`.
 *
 * A group for which [fillsPane] is true gets the full content area with no scroll, so that it can
 * show a list and a detail pane side by side.
 */
@Composable
fun <K> SettingsGroupLayout(
    groups: List<SettingsGroupItem<K>>,
    selected: K,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
    fillsPane: (K) -> Boolean = { false },
    content: @Composable ColumnScope.(K) -> Unit,
) {
    if (LocalWindowLayout.current.expanded) {
        Row(modifier.fillMaxSize()) {
            Column(
                Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            ) {
                groups.forEach { group -> GroupListItem(group, group.key == selected, onSelect) }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (fillsPane(selected)) Column(Modifier.fillMaxSize()) { content(selected) } else SettingsColumn { content(selected) }
            }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            val index = groups.indexOfFirst { group -> group.key == selected }.coerceAtLeast(0)
            SecondaryScrollableTabRow(
                selectedTabIndex = index,
                edgePadding = 8.dp,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(index, matchContentSize = false),
                        color = accentColor(),
                    )
                },
            ) {
                groups.forEachIndexed { position, group ->
                    Tab(
                        selected = position == index,
                        onClick = { onSelect(group.key) },
                        selectedContentColor = MaterialTheme.colorScheme.onSurface,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(settingsGroupTag(group.key as Any)),
                        text = { Text(group.title, maxLines = 1) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (fillsPane(selected)) Column(Modifier.fillMaxSize()) { content(selected) } else SettingsColumn { content(selected) }
            }
        }
    }
}

@Composable
private fun <K> GroupListItem(group: SettingsGroupItem<K>, selected: Boolean, onSelect: (K) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (selected) scheme.secondaryContainer else scheme.surface,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .heightIn(min = LocalTouchTargets.current.button)
            .testTag(settingsGroupTag(group.key as Any))
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab) { onSelect(group.key) },
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
            Text(group.title, style = MaterialTheme.typography.titleSmall)
        }
    }
}
