package org.superdro.core.ui.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.superdro.core.connection.DeviceId
import org.superdro.core.ui.presenter.PresenterOwners

/**
 * One entry of a back stack: a route and a unique number. Two entries with the same route have
 * different content keys, so each one has its own saved state and its own presenters.
 *
 * @property contentKey The key of the entry for navigation3 and for the presenter owners.
 */
sealed interface StackEntry {
    val number: Long
    val route: Any
    val contentKey: String
}

/** An entry of the back stack of the tab of [deviceId]. */
data class TabStackEntry(val deviceId: DeviceId, override val number: Long, override val route: TabRoute) : StackEntry {
    override val contentKey: String get() = "tab/${deviceId.value}/$number"
}

/** An entry of the global back stack. */
data class GlobalStackEntry(override val number: Long, override val route: GlobalRoute) : StackEntry {
    override val contentKey: String get() = "global/$number"
}

/** The navigation functions of one tab back stack. The root ([ReadoutRoute]) stays. */
interface TabNavigator {
    /** The device of the tab. */
    val deviceId: DeviceId

    /** Puts [route] on the top of the tab back stack. */
    fun push(route: TabRoute)

    /** Removes the top entry. The root stays. */
    fun pop()

    /** Removes the top entry when its route is [route]. A sheet calls it when it closes. */
    fun pop(route: TabRoute)

    /** Replaces the top entry with [route]. The root is never replaced. */
    fun replaceTop(route: TabRoute)

    /** Removes all entries above the root. */
    fun popToRoot()
}

/** The navigation functions of the global back stack. */
interface GlobalNavigator {
    /** Puts [route] on the top of the global back stack. */
    fun push(route: GlobalRoute)

    /** Removes the top entry. */
    fun pop()

    /** Removes the top entry when its route is [route]. */
    fun pop(route: GlobalRoute)

    /** Removes all global entries: the tab content shows again. */
    fun clear()
}

/**
 * The back stack of one tab. The root is [ReadoutRoute]. The holder
 * makes it, so the entries survive a configuration change.
 */
class TabStack internal constructor(
    override val deviceId: DeviceId,
    private val holder: NavigationHolder,
) : TabNavigator {

    /** The entries, root first. Compose reads this list. */
    val entries: SnapshotStateList<TabStackEntry> = mutableStateListOf(TabStackEntry(deviceId, holder.nextNumber(), ReadoutRoute))

    /** The routes, root first. */
    val routes: List<TabRoute> get() = entries.map { entry -> entry.route }

    /** The key of the presenters of the tab itself, for example the readout presenter. */
    val scopeKey: String get() = "tab/${deviceId.value}/scope"

    override fun push(route: TabRoute) {
        entries.add(TabStackEntry(deviceId, holder.nextNumber(), route))
    }

    override fun pop() {
        if (entries.size <= 1) return
        val removed = entries.removeAt(entries.lastIndex)
        holder.presenters.close(removed.contentKey)
    }

    override fun pop(route: TabRoute) {
        if (entries.size > 1 && entries.last().route == route) pop()
    }

    override fun replaceTop(route: TabRoute) {
        if (entries.size > 1) pop()
        push(route)
    }

    override fun popToRoot() {
        while (entries.size > 1) pop()
    }

    internal fun closeAll() {
        entries.reversed().forEach { entry -> holder.presenters.close(entry.contentKey) }
        holder.presenters.close(scopeKey)
    }
}

/**
 * The app-scope holder of the back stacks and their presenters.
 * `core:di` binds one object, so the activity and a configuration change do not own it.
 *
 * Each device tab has its own [TabStack] (the "multiple back stacks" recipe of navigation3). The
 * global back stack holds the screens of the gear and the overflow menu. The presenters of an
 * entry close when the entry leaves its back stack, or when the tab goes away ([retainTabs]).
 *
 * Call the functions on the UI thread: the stacks are Compose snapshot lists.
 */
class NavigationHolder : GlobalNavigator {

    private var counter = 0L
    private val tabs = LinkedHashMap<DeviceId, TabStack>()

    /** The presenter owners of all entries. */
    val presenters: PresenterOwners = PresenterOwners(isAlive = ::isAlive)

    /** The global back stack. Compose reads this list. */
    val globalEntries: SnapshotStateList<GlobalStackEntry> = mutableStateListOf()

    internal fun nextNumber(): Long = ++counter

    /** Gives the back stack of the tab of [deviceId]. A new tab gets a stack with the readout as the root. */
    fun tab(deviceId: DeviceId): TabStack = tabs.getOrPut(deviceId) { TabStack(deviceId, this) }

    /** Gives the back stack of [deviceId], or null when the tab has no stack. */
    fun tabOrNull(deviceId: DeviceId): TabStack? = tabs[deviceId]

    /** The devices with a back stack, in the order of their start. */
    val tabIds: List<DeviceId> get() = tabs.keys.toList()

    /**
     * Keeps the stacks of [visible] and removes the other stacks. The presenters of a removed
     * stack close (the tab hid after its grace time, or the user removed the machine).
     */
    fun retainTabs(visible: Collection<DeviceId>) {
        val keep = visible.toSet()
        tabs.keys.filter { id -> id !in keep }.forEach { id -> tabs.remove(id)?.closeAll() }
    }

    override fun push(route: GlobalRoute) {
        globalEntries.add(GlobalStackEntry(nextNumber(), route))
    }

    override fun pop() {
        if (globalEntries.isEmpty()) return
        val removed = globalEntries.removeAt(globalEntries.lastIndex)
        presenters.close(removed.contentKey)
    }

    override fun pop(route: GlobalRoute) {
        if (globalEntries.lastOrNull()?.route == route) pop()
    }

    override fun clear() {
        while (globalEntries.isNotEmpty()) pop()
    }

    /**
     * The back action of the frame: the top global entry first, then the top entry of the
     * tab of [selected]. Gives false when nothing was removed (the root of the tab shows).
     */
    fun back(selected: DeviceId?): Boolean {
        if (globalEntries.isNotEmpty()) {
            pop()
            return true
        }
        val stack = selected?.let(tabs::get) ?: return false
        if (stack.entries.size <= 1) return false
        stack.pop()
        return true
    }

    private fun isAlive(key: String): Boolean =
        globalEntries.any { entry -> entry.contentKey == key } ||
            tabs.values.any { stack -> stack.scopeKey == key || stack.entries.any { entry -> entry.contentKey == key } }
}
