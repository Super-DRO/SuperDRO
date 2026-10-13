package org.superdro.core.ui.integration

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.ui.navigation.CalibrationWizardRoute
import org.superdro.core.ui.navigation.CameraSetupRoute
import org.superdro.core.ui.navigation.DataOutputRoute
import org.superdro.core.ui.navigation.ProfileFileRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.appEntryRegistry

/** Each route of the central entry provider has a real screen: no area left a placeholder. */
class RegistryLinksTest {

    @Test
    fun noRouteHasThePlaceholderEntry() {
        val registry = appEntryRegistry()
        val tabPlaceholders = registry.tabTypes.filter { type -> registry.tabSpecOf(type)?.placeholder == true }.map { it.simpleName }
        val globalPlaceholders = registry.globalTypes.filter { type -> registry.globalSpecOf(type)?.placeholder == true }.map { it.simpleName }
        assertEquals(emptyList(), tabPlaceholders + globalPlaceholders)
        assertEquals(RouteArea.Adapter, registry.spec(CalibrationWizardRoute(AxisId.X))?.area)
        assertEquals(RouteArea.Functions, registry.spec(ProfileFileRoute)?.area)
        assertEquals(false, registry.spec(CameraSetupRoute)?.placeholder)
        assertEquals(false, registry.spec(DataOutputRoute)?.placeholder)
    }

    @Test
    fun noSourceOfCoreUiRegistersAPlaceholder() {
        val root = File("src/commonMain/kotlin")
        assertTrue(root.isDirectory)
        val calls = root.walkTopDown().filter { file -> file.isFile && file.extension == "kt" && file.name != "EntryRegistry.kt" }
            .flatMap { file -> Regex("""\b(tab|global)Placeholder\(""").findAll(file.readText()).map { file.name } }
            .toList()
        assertEquals(emptyList(), calls)
    }

    @Test
    fun coreUiHasNoOwnListOfBindableInterfaces() {
        // The data output takes the interfaces and the bind addresses from core:data-output
        // (BindAddresses). The old copy of core:ui went away with its test.
        val root = File("src")
        val hits = root.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> Regex("""\bBindableInterfaces\b""").containsMatchIn(file.readText()) && file.name != "RegistryLinksTest.kt" }
            .map { file -> file.name }
            .toList()
        assertEquals(emptyList(), hits)
        assertTrue(!File("src/commonMain/kotlin/org/superdro/core/ui/dataoutput/BindableInterfaces.kt").exists())
    }
}
