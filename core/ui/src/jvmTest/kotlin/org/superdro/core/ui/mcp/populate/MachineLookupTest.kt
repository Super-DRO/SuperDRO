package org.superdro.core.ui.mcp.populate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.Tool
import org.superdro.core.store.StoreResult

class MachineLookupTest {

    @Test
    fun aProfileIsFoundByIdAndByName() = runTest {
        val fixture = PopulateFixture(this).start()
        val lookup = fixture.deps.lookup
        assertEquals(fixture.mill.id, lookup.profile(fixture.mill.id).id)
        assertEquals(fixture.mill.id, lookup.profile(" mill ").id)
        assertEquals(fixture.lathe.id, lookup.profile("LATHE").id)
        val error = assertFailsWith<ToolError> { lookup.profile("Router") }
        assertEquals("No machine has the ID or the name \"Router\".", error.message)
        fixture.close()
    }

    @Test
    fun aWorkspaceAndAToolAreFoundByIdAndByName() = runTest {
        val fixture = PopulateFixture(this).start()
        val lookup = fixture.deps.lookup
        val workspace = fixture.millWorkspace()
        assertEquals(workspace.id, lookup.workspace(fixture.mill.id, workspace.id).id)
        assertEquals(workspace.id, lookup.workspace(fixture.mill.id, "default workspace").id)
        assertFailsWith<ToolError> { lookup.workspace(fixture.mill.id, "Other") }
        val tool = (fixture.tools.addTool(fixture.mill.id, Tool(name = "End mill 10")) as StoreResult.Ok).value
        assertEquals(tool.id, lookup.tool(fixture.mill.id, "end mill 10").id)
        assertEquals(tool.id, lookup.tool(fixture.mill.id, tool.id).id)
        assertFailsWith<ToolError> { lookup.tool(fixture.lathe.id, "End mill 10") }
        fixture.close()
    }
}
