package org.superdro.core.ui.navigation

import androidx.compose.runtime.Composable
import kotlin.reflect.KClass
import org.jetbrains.compose.resources.StringResource
import org.superdro.core.model.SidePanelMode

/** The kind of an entry. */
enum class EntryKind {
    /** An entry of the back stack that fills the content area. */
    Screen,

    /** A `ModalBottomSheet` in portrait and a dialog in landscape, above the screen below it. */
    Sheet,

    /** An `AlertDialog` above the screen below it. The entry content shows the dialog itself. */
    Dialog,
}

/** The area that implements an entry. The placeholder of an area that did not register its entry tells it. */
enum class RouteArea { Frame, Settings, Adapter, Functions, Graphics }

/**
 * The registration of the entry of one tab route type. [placeholder] is true for the entry "This
 * screen is under construction".
 */
class TabEntrySpec internal constructor(
    val type: KClass<out TabRoute>,
    val kind: EntryKind,
    val area: RouteArea,
    internal val content: @Composable TabEntryScope.(TabRoute) -> Unit,
    val placeholder: Boolean = false,
)

/**
 * The registration of the entry of one global route type. [placeholder] is true for the entry
 * "This screen is under construction".
 */
class GlobalEntrySpec internal constructor(
    val type: KClass<out GlobalRoute>,
    val kind: EntryKind,
    val area: RouteArea,
    internal val content: @Composable GlobalEntryScope.(GlobalRoute) -> Unit,
    val placeholder: Boolean = false,
)

/**
 * The entries of all routes, and the slots of the readout screen that the other areas fill.
 *
 * Each area registers its entries in its own entry file with one extension function:
 * `settingsEntries()`, `adapterEntries()`, `functionEntries()`, and `graphicsEntries()`. The
 * central provider ([appEntryRegistry]) calls the four functions, then the entries of the frame.
 * A second registration of a route type replaces the first one.
 */
class EntryRegistry {

    private val tabSpecs = LinkedHashMap<KClass<out TabRoute>, TabEntrySpec>()
    private val globalSpecs = LinkedHashMap<KClass<out GlobalRoute>, GlobalEntrySpec>()
    private val sidePanels = LinkedHashMap<SidePanelMode, @Composable TabEntryScope.() -> Unit>()
    private var graphicalPane: (@Composable TabEntryScope.() -> Unit)? = null
    private var addSubDatumTap: (TabEntryScope.() -> Unit)? = null
    private val tabEffects = ArrayList<@Composable TabEntryScope.() -> Unit>()
    private val statusItems = ArrayList<@Composable TabEntryScope.() -> Unit>()

    /** Registers the entry of the tab route type [type]. */
    @Suppress("UNCHECKED_CAST")
    fun <R : TabRoute> tab(
        type: KClass<R>,
        kind: EntryKind,
        area: RouteArea,
        content: @Composable TabEntryScope.(R) -> Unit,
    ) {
        // A new lambda, not a cast of the function type: a cast of a composable lambda fails at
        // run time, because the class of the lambda does not implement each function interface.
        tabSpecs[type] = TabEntrySpec(type, kind, area, content = { route -> content(this, route as R) })
    }

    /** Registers the entry of the tab route type [R]. */
    inline fun <reified R : TabRoute> tab(
        kind: EntryKind,
        area: RouteArea,
        noinline content: @Composable TabEntryScope.(R) -> Unit,
    ) = tab(R::class, kind, area, content)

    /** Registers the entry of the global route type [type]. */
    @Suppress("UNCHECKED_CAST")
    fun <R : GlobalRoute> global(
        type: KClass<R>,
        kind: EntryKind,
        area: RouteArea,
        content: @Composable GlobalEntryScope.(R) -> Unit,
    ) {
        globalSpecs[type] = GlobalEntrySpec(type, kind, area, content = { route -> content(this, route as R) })
    }

    /** Registers the entry of the global route type [R]. */
    inline fun <reified R : GlobalRoute> global(
        kind: EntryKind,
        area: RouteArea,
        noinline content: @Composable GlobalEntryScope.(R) -> Unit,
    ) = global(R::class, kind, area, content)

    /**
     * Registers a placeholder entry ("This screen is under construction") for the tab route type
     * [type]. [title] names the screen. The area replaces it with its real entry later.
     */
    fun <R : TabRoute> tabPlaceholder(type: KClass<R>, kind: EntryKind, area: RouteArea, title: StringResource) {
        tabSpecs[type] = TabEntrySpec(type, kind, area, { _ -> PlaceholderEntry(kind = kind, title = title, onClose = { navigator.pop() }) }, placeholder = true)
    }

    /** Registers a placeholder entry for the global route type [type]. */
    fun <R : GlobalRoute> globalPlaceholder(type: KClass<R>, kind: EntryKind, area: RouteArea, title: StringResource) {
        globalSpecs[type] = GlobalEntrySpec(type, kind, area, { _ -> PlaceholderEntry(kind = kind, title = title, onClose = { navigator.pop() }) }, placeholder = true)
    }

    /**
     * Registers the content of the side panel for [mode]: the sub-datum
     * list, the tool list, or the machine datums. The functions area fills these slots.
     */
    fun sidePanel(mode: SidePanelMode, content: @Composable TabEntryScope.() -> Unit) {
        sidePanels[mode] = content
    }

    /**
     * Registers the graphical view pane of the readout screen: the
     * supporting pane in landscape, and the pane below or above the readout in portrait. The
     * graphics area fills this slot.
     */
    fun graphicalViewPane(content: @Composable TabEntryScope.() -> Unit) {
        graphicalPane = content
    }

    /**
     * Registers the single tap of the "+" button: add a sub-datum at the current position.
     * The double tap opens [SubDatumEditRoute] and the long press opens
     * [ShapeMenuRoute]. The functions area fills this action.
     */
    fun addSubDatumTap(action: TabEntryScope.() -> Unit) {
        addSubDatumTap = action
    }

    /**
     * Registers an effect of each visible tab: a composable with no layout that the host keeps in
     * the composition while the tab is visible, also while a screen of the tab covers the readout.
     * The adapter area uses it to open the "new adapter" prompt of a tab.
     */
    fun tabEffect(content: @Composable TabEntryScope.() -> Unit) {
        tabEffects += content
    }

    /** The effects of each visible tab, in the order of their registration. */
    val tabEffectContents: List<@Composable TabEntryScope.() -> Unit> get() = tabEffects

    /**
     * Adds an item at the end of the status strip of the readout screen, for example the
     * status chip of the data output with its start and stop. Each call adds one
     * item. The items show in the order of the calls.
     */
    fun statusStripItem(content: @Composable TabEntryScope.() -> Unit) {
        statusItems += content
    }

    /** Gives the registration of [route], or null. */
    fun spec(route: TabRoute): TabEntrySpec? = tabSpecs[route::class]

    /** Gives the registration of [route], or null. */
    fun spec(route: GlobalRoute): GlobalEntrySpec? = globalSpecs[route::class]

    /** Gives the registration of the tab route type [type], or null. */
    fun tabSpecOf(type: KClass<out TabRoute>): TabEntrySpec? = tabSpecs[type]

    /** Gives the registration of the global route type [type], or null. */
    fun globalSpecOf(type: KClass<out GlobalRoute>): GlobalEntrySpec? = globalSpecs[type]

    /** Gives the kind of the entry of [route]. A route with no registration is a screen. */
    fun kindOf(route: Any): EntryKind = when (route) {
        is TabRoute -> spec(route)?.kind ?: EntryKind.Screen
        is GlobalRoute -> spec(route)?.kind ?: EntryKind.Screen
        else -> EntryKind.Screen
    }

    /** The registered tab route types. */
    val tabTypes: Set<KClass<out TabRoute>> get() = tabSpecs.keys

    /** The registered global route types. */
    val globalTypes: Set<KClass<out GlobalRoute>> get() = globalSpecs.keys

    /** Gives the side panel content of [mode], or null. */
    fun sidePanelContent(mode: SidePanelMode): (@Composable TabEntryScope.() -> Unit)? = sidePanels[mode]

    /** The graphical view pane, or null. */
    val graphicalViewPaneContent: (@Composable TabEntryScope.() -> Unit)? get() = graphicalPane

    /** The single tap action of the "+" button, or null. */
    val addSubDatumTapAction: (TabEntryScope.() -> Unit)? get() = addSubDatumTap

    /** The items of the status strip, in the order of [statusStripItem]. */
    val statusStripItems: List<@Composable TabEntryScope.() -> Unit> get() = statusItems.toList()
}
