package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.refusalOrNull
import org.superdro.core.store.valueOrNull

class ToolRepositoryTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    private suspend fun profile(type: MachineType, name: String): String =
        store.machines.createProfile(MachineProfile.create(type, name)).valueOrNull()!!.id

    @Test
    fun aHorizontalMillUsesTheMillTools() = scope.runTest {
        val horizontal = profile(MachineType.HorizontalMill, "Horizontal")
        val added = store.tools.addTool(horizontal, Tool(kind = ToolKind.Lathe, name = "Slab cutter", diameterUm = 63500.0)).valueOrNull()!!
        // The store sets the kind from the machine type: a mill tool on both mill types.
        assertEquals(ToolKind.Mill, added.kind)
        assertEquals(listOf("Slab cutter"), store.tools.loadLibrary(horizontal).map { it.name })

        // The shared library gives the mill tools to both mill types, and the lathe tools to a lathe.
        val vertical = profile(MachineType.VerticalMill, "Vertical")
        val lathe = profile(MachineType.Lathe, "Lathe")
        listOf(vertical, horizontal, lathe).forEach { id -> store.tools.setUseSharedLibrary(id, true) }
        store.tools.addTool(vertical, Tool(name = "Shared end mill"))
        store.tools.addTool(lathe, Tool(name = "Shared boring bar", internal = true))
        assertEquals(listOf("Shared end mill"), store.tools.loadLibrary(horizontal).map { it.name })
        assertEquals(listOf("Shared end mill"), store.tools.loadLibrary(vertical).map { it.name })
        assertEquals(listOf("Shared boring bar"), store.tools.loadLibrary(lathe).map { it.name })
        assertEquals(true, store.tools.loadLibrary(lathe).single().internal)
    }

    @Test
    fun theLibrarySwitchTakesEffectAtOnce() = scope.runTest {
        val mill = profile(MachineType.VerticalMill, "Mill")
        store.tools.addTool(mill, Tool(name = "Own"))
        store.tools.setUseSharedLibrary(mill, true)
        store.tools.addTool(mill, Tool(name = "Shared"))
        store.tools.setUseSharedLibrary(mill, false)

        val seen = mutableListOf<List<String>>()
        val job = launch(store.dispatcher) {
            store.tools.library(mill).collect { tools -> seen += tools.map { it.name } }
        }
        advanceUntilIdle()
        store.tools.setUseSharedLibrary(mill, true)
        advanceUntilIdle()
        store.tools.setUseSharedLibrary(mill, false)
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf(listOf("Own"), listOf("Shared"), listOf("Own")), seen)
        // The profile settings show the switch too.
        assertEquals(false, store.machines.loadProfile(mill)!!.useSharedToolLibrary)
    }

    @Test
    fun aBulkSaveAddsAndUpdatesInOneCall() = scope.runTest {
        val mill = profile(MachineType.VerticalMill, "Mill")
        val first = store.tools.addTool(mill, Tool(name = "A", diameterUm = 1000.0)).valueOrNull()!!
        val counts = store.tools.saveTools(mill, listOf(first.copy(diameterUm = 2000.0), Tool(name = "B"), Tool(name = "C")))
        assertEquals(ToolSaveCounts(added = 2, updated = 1), counts.valueOrNull())
        assertEquals(listOf("A" to 2000.0, "B" to 0.0, "C" to 0.0), store.tools.loadLibrary(mill).map { it.name to it.diameterUm })

        store.tools.deleteTools(listOf(first.id))
        assertEquals(listOf("B", "C"), store.tools.loadLibrary(mill).map { it.name })
        assertEquals(StoreRefusal.NotFound, store.tools.updateTool(first).refusalOrNull())
    }

    @Test
    fun anUpdateKeepsTheLibraryOfTheTool() = scope.runTest {
        val mill = profile(MachineType.VerticalMill, "Mill")
        val own = store.tools.addTool(mill, Tool(name = "Own")).valueOrNull()!!
        store.tools.setUseSharedLibrary(mill, true)
        store.tools.updateTool(own.copy(name = "Own 2"))
        assertEquals(emptyList(), store.tools.loadLibrary(mill))
        store.tools.setUseSharedLibrary(mill, false)
        assertEquals(listOf("Own 2"), store.tools.loadLibrary(mill).map { it.name })
    }
}
