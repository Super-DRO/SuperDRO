package org.superdro.core.ui.mcp.populate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.superdro.core.mcp.GateDecision
import org.superdro.core.mcp.GateTexts
import org.superdro.core.model.AxisId
import org.superdro.core.model.McpConfirmation
import org.superdro.core.model.Plane

class WorkspaceToolsTest {

    private fun point(name: String, x: Double, y: Double? = null, z: Double? = null, id: String? = null) = buildJsonObject {
        if (id != null) put("id", id)
        put("name", name)
        put("x", x)
        if (y != null) put("y", y)
        if (z != null) put("z", z)
    }

    // ---------------------------------------------------------------------------------------
    // create_workspace

    @Test
    fun createWorkspaceWritesAWorkspaceWithThePlane() = runTest {
        val fixture = PopulateFixture(this).start()
        val result = fixture.ok("create_workspace", args { put("machine", "Mill"); put("name", "Vise"); put("plane", "XZ") })
        assertTrue(result.text.startsWith("Workspace \"Vise\" created with ID "))
        assertEquals("XZ", result.obj.str("plane"))
        assertEquals(false, result.obj.bool("active"))
        val stored = fixture.machines.workspaces(fixture.mill.id).first().first { workspace -> workspace.name == "Vise" }
        assertEquals(Plane.XZ, fixture.machines.loadWorkspace(stored.id)!!.plane)
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    @Test
    fun createWorkspacePreviewWritesNothing() = runTest {
        val fixture = PopulateFixture(this).start()
        val result = fixture.ok("create_workspace", args { put("machine", "Mill"); put("name", "Vise"); put("preview", true) })
        assertEquals("Preview: workspace \"Vise\" would be created in Mill", result.text)
        assertEquals("Add", result.obj.list("toImport").single().str("action"))
        assertEquals(1, fixture.machines.workspaces(fixture.mill.id).first().size)
        fixture.close()
    }

    @Test
    fun createWorkspaceRefusesABadPlaneAndATakenName() = runTest {
        val fixture = PopulateFixture(this).start()
        val plane = fixture.error("create_workspace", args { put("machine", "Mill"); put("name", "Vise"); put("plane", "ZX") })
        assertEquals("The plane ZX is not valid for a VerticalMill. Use XY, XZ, YZ.", plane.message)
        val taken = fixture.error("create_workspace", args { put("machine", "Mill"); put("name", "Default Workspace") })
        assertEquals("A workspace with this name already exists.", taken.message)
        fixture.close()
    }

    @Test
    fun createWorkspaceAsksTheGateWithTheConfirmationAll() = runTest {
        val fixture = PopulateFixture(this, confirmation = McpConfirmation.All).start()
        fixture.ok("create_workspace", args { put("machine", "Mill"); put("name", "Vise") })
        assertEquals("Create workspace \"Vise\" on Mill", fixture.gate.requests.single().summary)
        assertEquals("Client 192.168.1.9", fixture.gate.requests.single().details.last())
        fixture.close()
    }

    // ---------------------------------------------------------------------------------------
    // update_workspace

    @Test
    fun updateWorkspaceChangesTheNameTheAxisStatesAndTheActiveSubDatum() = runTest {
        val fixture = PopulateFixture(this).start()
        val workspace = fixture.millWorkspace()
        val added = fixture.ok("put_sub_datums", args { put("machine", "Mill"); put("workspace", workspace.id); put("points", jsonList(point("P1", 1.0, 2.0))) })
        val pointId = added.obj.list("toImport").single().str("id")
        val result = fixture.ok(
            "update_workspace",
            args {
                put("machine", "Mill")
                put("workspace", "Default Workspace")
                put("name", "Vise")
                put("activeSubDatumId", pointId)
                put("axisStates", buildJsonObject { put("X", buildJsonObject { put("absoluteOffset", 10.0); put("relative", true) }) })
                put("graphical", buildJsonObject { put("snapToPoint", true) })
            },
        )
        assertEquals("4 fields updated on \"Vise\"", result.text)
        val stored = fixture.machines.loadWorkspace(workspace.id)!!
        assertEquals("Vise", stored.name)
        assertEquals(10_000.0, stored.axisStates.getValue(AxisId.X).absoluteOffset)
        assertEquals(true, stored.axisStates.getValue(AxisId.X).relative)
        assertEquals(pointId, stored.activeSubDatumId)
        assertEquals(true, stored.graphical.snapToPoint)
        assertEquals(10.0, result.obj["axisStates"]!!.jsonObjectValue("X").num("absoluteOffset"))
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    @Test
    fun updateWorkspacePreviewListsTheChangesAndWritesNothing() = runTest {
        val fixture = PopulateFixture(this).start()
        val result = fixture.ok("update_workspace", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("name", "Vise"); put("preview", true) })
        assertEquals("Preview: 1 field would change on \"Default Workspace\"", result.text)
        assertEquals("name", result.obj.list("toImport").single().str("label"))
        assertEquals("Default Workspace", fixture.millWorkspace().name)
        fixture.close()
    }

    @Test
    fun updateWorkspaceRefusesAnUnknownSubDatumAndAnEmptyCall() = runTest {
        val fixture = PopulateFixture(this).start()
        val unknown = fixture.error("update_workspace", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("activeSubDatumId", "nope") })
        assertEquals("The workspace has no sub-datum with the ID \"nope\".", unknown.message)
        val empty = fixture.error("update_workspace", args { put("machine", "Mill"); put("workspace", "Default Workspace") })
        assertEquals("Give one or more fields to change.", empty.message)
        fixture.close()
    }

    @Test
    fun updateWorkspaceAxisStatesOfTheActiveWorkspaceOfAnOnlineMachineAreRisky() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.online += fixture.mill.id
        val arguments = args {
            put("machine", "Mill")
            put("workspace", "Default Workspace")
            put("axisStates", buildJsonObject { put("Z", buildJsonObject { put("absoluteOffset", -5.0) }) })
        }
        fixture.gate.decision = GateDecision.Refused
        assertEquals(GateTexts.REFUSED, fixture.error("update_workspace", arguments).message)
        assertEquals(0.0, fixture.millWorkspace().axisStates.getValue(AxisId.Z).absoluteOffset)
        fixture.gate.decision = GateDecision.Timeout
        assertEquals(GateTexts.TIMEOUT, fixture.error("update_workspace", arguments).message)
        fixture.gate.decision = GateDecision.Allowed
        fixture.ok("update_workspace", arguments)
        assertEquals(-5_000.0, fixture.millWorkspace().axisStates.getValue(AxisId.Z).absoluteOffset)
        assertEquals(3, fixture.gate.requests.size)
        // A rename of the same workspace is not risky.
        fixture.ok("update_workspace", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("name", "Vise") })
        assertEquals(3, fixture.gate.requests.size)
        fixture.close()
    }

    // ---------------------------------------------------------------------------------------
    // delete_workspace

    @Test
    fun deleteWorkspaceAsksTheGateAndDeletes() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.ok("create_workspace", args { put("machine", "Mill"); put("name", "Vise") })
        val preview = fixture.ok("delete_workspace", args { put("machine", "Mill"); put("workspace", "Vise"); put("preview", true) })
        assertEquals("Preview: workspace \"Vise\" would be deleted (0 sub-datums, 0 guides)", preview.text)
        assertEquals(2, fixture.machines.workspaces(fixture.mill.id).first().size)
        val result = fixture.ok("delete_workspace", args { put("machine", "Mill"); put("workspace", "Vise") })
        assertEquals("Workspace \"Vise\" deleted", result.text)
        assertEquals(1, fixture.machines.workspaces(fixture.mill.id).first().size)
        assertEquals("Delete workspace \"Vise\" on Mill", fixture.gate.requests.single().summary)
        val last = fixture.error("delete_workspace", args { put("machine", "Mill"); put("workspace", "Default Workspace") })
        assertEquals("The last workspace can't be deleted.", last.message)
        fixture.close()
    }

    @Test
    fun deleteWorkspaceWithTheConfirmationOffAsksNoGate() = runTest {
        val fixture = PopulateFixture(this, confirmation = McpConfirmation.Off).start()
        fixture.ok("create_workspace", args { put("machine", "Mill"); put("name", "Vise") })
        fixture.ok("delete_workspace", args { put("machine", "Mill"); put("workspace", "Vise") })
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    // ---------------------------------------------------------------------------------------
    // put_sub_datums

    @Test
    fun putSubDatumsAddsAndThenUpdatesByName() = runTest {
        val fixture = PopulateFixture(this).start()
        val first = fixture.ok(
            "put_sub_datums",
            args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("points", jsonList(point("Hole 1", 10.0, 20.0), point("Hole 2", 30.0, 40.0, 5.0))) },
        )
        assertEquals("2 points added, 0 points updated, 0 guides added, 0 rows skipped", first.text)
        assertEquals("UPDATE_MATCHING", first.obj.str("operation"))
        val stored = fixture.millWorkspace().subDatums
        assertEquals(listOf("Hole 1", "Hole 2"), stored.map { point -> point.name })
        assertEquals(10_000.0, stored[0].x)
        assertEquals(20_000.0, stored[0].y)
        assertEquals(5_000.0, stored[1].z)
        val second = fixture.ok(
            "put_sub_datums",
            args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("points", jsonList(point("Hole 1", 11.0, 21.0), point("Hole 3", 1.0, 1.0))) },
        )
        assertEquals("1 point added, 1 point updated, 0 guides added, 0 rows skipped", second.text)
        val updated = fixture.millWorkspace().subDatums
        assertEquals(3, updated.size)
        assertEquals(11_000.0, updated.first { point -> point.name == "Hole 1" }.x)
        assertEquals(stored[0].id, updated.first { point -> point.name == "Hole 1" }.id)
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    @Test
    fun putSubDatumsReadsInchAndMicrometersAndAnglesInDegrees() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.ok(
            "put_sub_datums",
            args {
                put("machine", "Mill")
                put("workspace", "Default Workspace")
                put("units", "inch")
                put("points", jsonList(buildJsonObject { put("name", "Inch"); put("x", 1.0); put("a", 90.0) }))
            },
        )
        fixture.ok(
            "put_sub_datums",
            args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("units", "um"); put("points", jsonList(point("Um", 2_500.0))) },
        )
        val stored = fixture.millWorkspace().subDatums.associateBy { point -> point.name }
        assertEquals(25_400.0, stored.getValue("Inch").x)
        assertEquals(324_000.0, stored.getValue("Inch").a)
        assertEquals(2_500.0, stored.getValue("Um").x)
        fixture.close()
    }

    @Test
    fun putSubDatumsPreviewGivesThePlanAndWritesNothing() = runTest {
        val fixture = PopulateFixture(this).start()
        val result = fixture.ok(
            "put_sub_datums",
            args {
                put("machine", "Mill")
                put("workspace", "Default Workspace")
                put("preview", true)
                put("points", jsonList(point("Hole 1", 10.0, 20.0), buildJsonObject { put("name", "Bad") }, buildJsonObject { put("name", "Guide"); put("x", 5.0) }))
            },
        )
        assertEquals("Preview: 1 point added, 0 points updated, 1 guide added, 1 row skipped", result.text)
        assertEquals(listOf("Add", "Add"), result.obj.list("toImport").map { row -> row.str("action") })
        assertEquals("Skip", result.obj.list("skipped").single().str("action"))
        assertEquals("Row 2: The row has no coordinate.", result.obj.list("skipped").single().list("problems").single().str("message"))
        assertEquals(emptyList(), fixture.millWorkspace().subDatums)
        fixture.close()
    }

    @Test
    fun putSubDatumsRefusesABadOperationAndAnEmptyList() = runTest {
        val fixture = PopulateFixture(this).start()
        val bad = fixture.error("put_sub_datums", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("operation", "UPSERT"); put("points", jsonList(point("A", 1.0))) })
        assertEquals("The operation must be one of UPDATE_MATCHING, ADD_AS_NEW, ADD_NEW_ONLY, MERGE_MATCHING, REPLACE_ALL, not \"UPSERT\".", bad.message)
        val empty = fixture.error("put_sub_datums", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("points", jsonList()) })
        assertEquals("The file has no data rows.", empty.message)
        fixture.close()
    }

    @Test
    fun putSubDatumsReplaceAllAsksTheGateAndDeletesTheOldPoints() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.ok("put_sub_datums", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("points", jsonList(point("Old", 1.0))) })
        val arguments = args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("operation", "REPLACE_ALL"); put("points", jsonList(point("New", 2.0))) }
        fixture.gate.decision = GateDecision.Refused
        fixture.error("put_sub_datums", arguments)
        assertEquals(listOf("Old"), fixture.millWorkspace().subDatums.map { point -> point.name })
        fixture.gate.decision = GateDecision.Allowed
        fixture.ok("put_sub_datums", arguments)
        assertEquals(listOf("New"), fixture.millWorkspace().subDatums.map { point -> point.name })
        assertEquals("REPLACE_ALL on \"Default Workspace\" of Mill", fixture.gate.requests.last().summary)
        assertTrue(fixture.gate.requests.last().details.any { line -> line.startsWith("Deletes all 1 points") })
        fixture.close()
    }

    @Test
    fun putSubDatumsOnTheActiveSubDatumOfAnOnlineMachineIsRisky() = runTest {
        val fixture = PopulateFixture(this).start()
        val workspace = fixture.millWorkspace()
        val added = fixture.ok("put_sub_datums", args { put("machine", "Mill"); put("workspace", workspace.id); put("points", jsonList(point("P1", 1.0), point("P2", 2.0))) })
        val activeId = added.obj.list("toImport").first().str("id")
        fixture.machines.updateWorkspace(workspace.id) { current -> current.copy(activeSubDatumId = activeId) }
        fixture.online += fixture.mill.id
        fixture.ok("put_sub_datums", args { put("machine", "Mill"); put("workspace", workspace.id); put("points", jsonList(point("P2", 3.0))) })
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.ok("put_sub_datums", args { put("machine", "Mill"); put("workspace", workspace.id); put("points", jsonList(point("P1", 4.0))) })
        assertEquals(1, fixture.gate.requests.size)
        fixture.close()
    }

    // ---------------------------------------------------------------------------------------
    // delete_sub_datums

    @Test
    fun deleteSubDatumsDeletesByIdAndSkipsUnknownIds() = runTest {
        val fixture = PopulateFixture(this).start()
        val added = fixture.ok("put_sub_datums", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("points", jsonList(point("P1", 1.0), point("P2", 2.0))) })
        val ids = added.obj.list("toImport").map { row -> row.str("id") }
        val preview = fixture.ok("delete_sub_datums", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("ids", jsonList(ids[0], "nope")); put("preview", true) })
        assertEquals("Preview: 1 point deleted, 1 ID not found", preview.text)
        assertEquals(2, fixture.millWorkspace().subDatums.size)
        val result = fixture.ok("delete_sub_datums", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("ids", jsonList(ids[0], "nope")) })
        assertEquals("1 point deleted, 1 ID not found", result.text)
        assertEquals(listOf(ids[1]), fixture.millWorkspace().subDatums.map { point -> point.id })
        assertEquals("Delete 1 point from \"Default Workspace\" of Mill", fixture.gate.requests.single().summary)
        val none = fixture.error("delete_sub_datums", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("ids", jsonList("nope")) })
        assertEquals("No point of the workspace has one of the IDs.", none.message)
        fixture.close()
    }

    // ---------------------------------------------------------------------------------------
    // put_guides

    @Test
    fun putGuidesAddsAndReplaces() = runTest {
        val fixture = PopulateFixture(this).start()
        val guide = { axis: String, value: Double -> buildJsonObject { put("axis", axis); put("value", value) } }
        val added = fixture.ok("put_guides", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("guides", jsonList(guide("X", 10.0), guide("Y", 20.0), guide("X", 10.0))) })
        assertEquals("2 guides added, 0 guides deleted, 1 guide skipped", added.text)
        assertEquals(2, fixture.millWorkspace().guides.size)
        assertEquals(10_000.0, fixture.millWorkspace().guides.first { it.axis == AxisId.X }.valueUm)
        assertEquals(emptyList(), fixture.gate.requests)
        val preview = fixture.ok("put_guides", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("operation", "REPLACE_ALL"); put("guides", jsonList(guide("Z", 1.0))); put("preview", true) })
        assertEquals("Preview: 1 guide added, 2 guides deleted, 0 guides skipped", preview.text)
        assertEquals(2, fixture.millWorkspace().guides.size)
        fixture.ok("put_guides", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("operation", "REPLACE_ALL"); put("guides", jsonList(guide("Z", 1.0))) })
        assertEquals(listOf(AxisId.Z), fixture.millWorkspace().guides.map { it.axis })
        assertEquals("REPLACE_ALL guides on \"Default Workspace\" of Mill", fixture.gate.requests.single().summary)
        val bad = fixture.error("put_guides", args { put("machine", "Lathe"); put("workspace", "Default Workspace"); put("guides", jsonList(guide("Y", 1.0))) })
        assertEquals("A guide needs the axis X or Z, not Y.", bad.message)
        fixture.close()
    }

    @Test
    fun aMachineOrAWorkspaceThatIsNotThereIsAnError() = runTest {
        val fixture = PopulateFixture(this).start()
        assertEquals("No machine has the ID or the name \"Router\".", fixture.error("delete_workspace", args { put("machine", "Router"); put("workspace", "X") }).message)
        assertEquals("No workspace has the ID or the name \"X\".", fixture.error("delete_workspace", args { put("machine", "Mill"); put("workspace", "X") }).message)
        assertEquals("The argument \"machine\" is required.", fixture.error("delete_workspace", args { put("workspace", "X") }).message)
        assertNull(fixture.machines.loadProfile("Router"))
        assertNotNull(fixture.machines.loadProfile(fixture.mill.id))
        fixture.close()
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectValue(key: String) = (this as kotlinx.serialization.json.JsonObject).getValue(key)
}
