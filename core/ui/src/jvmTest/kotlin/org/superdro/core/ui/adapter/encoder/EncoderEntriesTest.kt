package org.superdro.core.ui.adapter.encoder

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.ui.navigation.EncoderSettingsRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.ErrorMapDiagnosticsRoute
import org.superdro.core.ui.navigation.ErrorMapReportRoute
import org.superdro.core.ui.navigation.ErrorMapWizardRoute
import org.superdro.core.ui.navigation.MapEditorRoute
import org.superdro.core.ui.navigation.OfflineEncoderSettingsRoute
import org.superdro.core.ui.navigation.OfflineMapEditorRoute
import org.superdro.core.ui.navigation.RouteArea

/** The entries of the encoder area. */
class EncoderEntriesTest {

    @Test
    fun theRouteOfTheEditorGivesTheMapKind() {
        assertEquals(org.superdro.core.functions.errormap.MapKind.Error, MapEditorRoute('x').kind, "The error map is the default.")
        assertEquals(org.superdro.core.functions.errormap.MapKind.Backlash, MapEditorRoute('x', org.superdro.core.functions.errormap.MapKind.Backlash).kind)
        assertEquals(org.superdro.core.functions.errormap.MapKind.Error, OfflineMapEditorRoute("a", 'x').kind)
    }

    @Test
    fun eachRouteOfTheEncoderAreaIsAScreenOfTheAdapterArea() {
        val registry = EntryRegistry().apply { encoderEntries() }
        val tabRoutes = listOf(
            EncoderSettingsRoute('x'),
            MapEditorRoute('x'),
            ErrorMapWizardRoute('x'),
            ErrorMapReportRoute('x'),
            ErrorMapDiagnosticsRoute('x'),
        )
        tabRoutes.forEach { route ->
            assertEquals(EntryKind.Screen, registry.kindOf(route), route.toString())
            assertEquals(RouteArea.Adapter, registry.spec(route)?.area, route.toString())
        }
        listOf(OfflineEncoderSettingsRoute("a", 'x'), OfflineMapEditorRoute("a", 'x')).forEach { route ->
            assertEquals(EntryKind.Screen, registry.kindOf(route), route.toString())
            assertEquals(RouteArea.Adapter, registry.spec(route)?.area, route.toString())
        }
    }
}
