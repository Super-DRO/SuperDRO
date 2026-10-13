package org.superdro.core.ui.navigation

import java.io.File
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each route of Routes.kt has an entry, and each area has its own entry function. */
class EntryRegistryTest {

    /** The route types of Routes.kt, from the source text. */
    private fun declaredRoutes(): Map<String, String> {
        val source = File("src/commonMain/kotlin/org/superdro/core/ui/navigation/Routes.kt").readText()
        return Regex("""(?:data object|data class)\s+(\w+)(?:\([^)]*\))?\s*:\s*(TabRoute|GlobalRoute)""").findAll(source)
            .associate { match -> match.groupValues[1] to match.groupValues[2] }
    }

    @Test
    fun eachRouteOfRoutesKtHasARegisteredEntry() {
        val registry = appEntryRegistry()
        val registered = (registry.tabTypes + registry.globalTypes).map { type: KClass<*> -> type.simpleName!! }.toSet()
        val declared = declaredRoutes()
        assertTrue(declared.size >= 70, "Routes.kt has ${declared.size} routes")
        assertEquals(emptySet(), declared.keys - registered, "Routes with no entry")
        assertEquals(declared.filterValues { it == "TabRoute" }.keys, registry.tabTypes.map { it.simpleName!! }.toSet())
    }

    @Test
    fun theEntryKinds() {
        val registry = appEntryRegistry()
        assertEquals(EntryKind.Screen, registry.kindOf(ReadoutRoute))
        assertEquals(EntryKind.Sheet, registry.kindOf(KeypadRoute(org.superdro.core.model.AxisId.X)))
        assertEquals(EntryKind.Sheet, registry.kindOf(AddDeviceRoute))
        assertEquals(EntryKind.Screen, registry.kindOf(HoleCircleRoute))
        assertEquals(EntryKind.Sheet, registry.kindOf(RadiusCompensationRoute))
        assertEquals(EntryKind.Dialog, registry.kindOf(AddGuideRoute))
        assertEquals(EntryKind.Screen, registry.kindOf(AdapterDetailsRoute))
        assertEquals(RouteArea.Settings, registry.spec(MachineSettingsRoute)?.area)
        assertEquals(RouteArea.Adapter, registry.spec(AdaptersRoute)?.area)
        assertEquals(RouteArea.Functions, registry.spec(EdgeFinderRoute)?.area)
        assertEquals(RouteArea.Graphics, registry.spec(GraphicalViewRoute)?.area)
        assertEquals(RouteArea.Frame, registry.spec(ReadoutRoute)?.area)
    }

    @Test
    fun theSlotsOfTheReadoutScreenHaveAPlaceholder() {
        val registry = appEntryRegistry()
        org.superdro.core.model.SidePanelMode.entries.filter { it != org.superdro.core.model.SidePanelMode.Off }.forEach { mode ->
            assertTrue(registry.sidePanelContent(mode) != null, "No side panel for $mode")
        }
        assertTrue(registry.graphicalViewPaneContent != null)
        assertTrue(registry.addSubDatumTapAction != null)
    }

    @Test
    fun aLaterRegistrationReplacesAnEarlierOne() {
        val registry = EntryRegistry()
        registry.tab<ReadoutRoute>(EntryKind.Sheet, RouteArea.Functions) { }
        registry.tab<ReadoutRoute>(EntryKind.Screen, RouteArea.Frame) { }
        assertEquals(EntryKind.Screen, registry.kindOf(ReadoutRoute))
        assertEquals(RouteArea.Frame, registry.spec(ReadoutRoute)?.area)
    }
}
