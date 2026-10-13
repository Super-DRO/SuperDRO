package org.superdro.core.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.superdro.core.connection.DeviceId
import org.superdro.core.ui.presenter.LocalPresenterOwner
import org.superdro.core.ui.presenter.PresenterOwners
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.settings.help.LocalHelpOpener
import org.superdro.core.ui.text.UiText

/** The metadata key of the [EntryKind] of a navigation3 entry. */
internal const val ENTRY_KIND_KEY: String = "superdro.entryKind"

/**
 * The entry decorator that connects the presenters to the navigation3 entries: the thin adapter of the presenter pattern. It gives the
 * [PresenterOwner][org.superdro.core.ui.presenter.PresenterOwner]
 * of the entry to its content, and tells the owners when an entry left its back stack.
 */
@Composable
fun rememberPresenterNavEntryDecorator(owners: PresenterOwners): NavEntryDecorator<StackEntry> = remember(owners) {
    NavEntryDecorator(
        onPop = { contentKey -> owners.onPopped(contentKey.toString()) },
        decorate = { entry ->
            CompositionLocalProvider(LocalPresenterOwner provides owners.owner(entry.contentKey.toString())) {
                entry.Content()
            }
        },
    )
}

/**
 * The content area of the frame: one `NavDisplay` for the back stack of the selected tab and the
 * global back stack (the "multiple back stacks" recipe of navigation3).
 *
 * - Each tab stack gets its own decorators: the saveable state holder and the presenter adapter.
 *   The host decorates each stack in each composition, also the stacks of the hidden tabs, so
 *   that a tab keeps its state when the user changes the tab.
 * - The screen entries go to the `NavDisplay`. The sheet and dialog entries above the last
 *   screen show above it: their content shows the sheet ([org.superdro.core.ui.adaptive.AdaptiveSheet])
 *   or the `AlertDialog` itself.
 * - The back action (predictive back) removes the top global entry first, then the top entry
 *   of the selected tab.
 *
 * @param tabIds The devices of the visible tabs. The host removes the stacks of the other devices.
 * @param selected The device of the selected tab, or null.
 * @param readoutFactory Makes the readout presenter of a tab ([TabEntryScope.readout]).
 * @param emptyContent The content when no tab and no global screen shows.
 */
@Composable
fun NavigationHost(
    holder: NavigationHolder,
    registry: EntryRegistry,
    tabIds: List<DeviceId>,
    selected: DeviceId?,
    readoutFactory: (DeviceId) -> TabReadoutPresenter,
    showMessage: (UiText) -> Unit,
    modifier: Modifier = Modifier,
    emptyContent: @Composable () -> Unit = {},
) {
    val currentFactory by rememberUpdatedState(readoutFactory)
    val currentMessage by rememberUpdatedState(showMessage)
    LaunchedEffect(tabIds) { holder.retainTabs(tabIds) }

    val tabEntries: Map<DeviceId, List<NavEntry<StackEntry>>> = tabIds.associateWith { id ->
        key(id) {
            val stack = holder.tab(id)
            val scope = remember(stack) {
                TabEntryScope(
                    deviceId = id,
                    navigator = stack,
                    global = holder,
                    tabOwner = holder.presenters.owner(stack.scopeKey),
                    readoutFactory = { device -> currentFactory(device) },
                    messages = { text -> currentMessage(text) },
                )
            }
            registry.tabEffectContents.forEach { effect -> effect(scope) }
            val decorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator<StackEntry>(),
                rememberPresenterNavEntryDecorator(holder.presenters),
            )
            rememberDecoratedNavEntries(stack.entries, decorators) { entry -> tabNavEntry(entry, registry, scope) }
        }
    }

    val globalScope = GlobalEntryScope(
        navigator = holder,
        selectedDevice = selected,
        tabNavigator = { id -> holder.tabOrNull(id) },
        messages = { text -> currentMessage(text) },
    )
    val globalDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<StackEntry>(),
        rememberPresenterNavEntryDecorator(holder.presenters),
    )
    val globalEntries = rememberDecoratedNavEntries(holder.globalEntries, globalDecorators) { entry ->
        globalNavEntry(entry, registry, globalScope)
    }

    val all = (selected?.let(tabEntries::get).orEmpty()) + globalEntries
    val lastScreen = all.indexOfLast { entry -> entry.kind == EntryKind.Screen }
    val screens = all.filter { entry -> entry.kind == EntryKind.Screen }
    val overlays = all.drop(lastScreen + 1)

    val helpOpener: (HelpTopic) -> Unit = remember(holder) { { topic -> holder.push(HelpRoute(topic.name)) } }
    CompositionLocalProvider(LocalHelpOpener provides helpOpener) {
        Box(modifier.fillMaxSize()) {
            if (screens.isEmpty()) {
                emptyContent()
            } else {
                NavDisplay(
                    entries = screens,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { fade() },
                    popTransitionSpec = { fade() },
                    predictivePopTransitionSpec = { _ -> fade() },
                    onBack = { holder.back(selected) },
                )
            }
            overlays.forEach { entry -> key(entry.contentKey) { entry.Content() } }
        }
    }
}

/** The kind of a navigation3 entry of the host. */
internal val NavEntry<*>.kind: EntryKind get() = metadata[ENTRY_KIND_KEY] as? EntryKind ?: EntryKind.Screen

/** The containers use a short fade (only the containers and the sheets move). */
private fun fade(): ContentTransform = fadeIn() togetherWith fadeOut()

private fun tabNavEntry(entry: StackEntry, registry: EntryRegistry, scope: TabEntryScope): NavEntry<StackEntry> {
    val route = entry.route as TabRoute
    val spec = registry.spec(route)
    val kind = spec?.kind ?: EntryKind.Screen
    return NavEntry(key = entry, contentKey = entry.contentKey, metadata = mapOf(ENTRY_KIND_KEY to kind)) { _ ->
        if (spec == null) MissingEntry(route, onClose = scope.navigator::pop) else spec.content(scope, route)
    }
}

private fun globalNavEntry(entry: StackEntry, registry: EntryRegistry, scope: GlobalEntryScope): NavEntry<StackEntry> {
    val route = entry.route as GlobalRoute
    val spec = registry.spec(route)
    val kind = spec?.kind ?: EntryKind.Screen
    return NavEntry(key = entry, contentKey = entry.contentKey, metadata = mapOf(ENTRY_KIND_KEY to kind)) { _ ->
        if (spec == null) MissingEntry(route, onClose = scope.navigator::pop) else spec.content(scope, route)
    }
}

/** The content of a route with no registration: an error of the program, not a user state. */
@Composable
private fun MissingEntry(route: Any, onClose: () -> Unit) {
    ScreenScaffold(title = route::class.simpleName ?: route.toString(), onBack = onClose) {}
}
