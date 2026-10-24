package org.superdro.core.ui.mcp.populate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.superdro.core.mcp.GateDecision
import org.superdro.core.mcp.GateTexts
import org.superdro.core.model.MachineType
import org.superdro.core.store.files.settings.ProfileFile

class ProfileToolsTest {

    private fun settings(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is Boolean -> put(key, value)
                is Number -> put(key, value)
                is String -> put(key, value)
                is JsonObject -> put(key, value)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // put_profile_settings

    @Test
    fun putProfileSettingsAppliesTheValidValuesAndWarnsOnTheRest() = runTest {
        val fixture = PopulateFixture(this).start()
        val result = fixture.ok(
            "put_profile_settings",
            args { put("machine", "Mill"); put("settings", settings("metricDefault" to false, "use3dSubDatums" to false, "notASetting" to 1, "usbBaudRate" to 7)) },
        )
        assertEquals("2 settings changed, 1 key skipped, 3 warnings", result.text)
        assertEquals(2, result.obj.int("changed"))
        val rows = result.obj.list("toImport").associateBy { row -> row.str("label") }
        assertEquals("false", rows.getValue("metricDefault").str("newValue"))
        assertEquals("true", rows.getValue("metricDefault").str("currentValue"))
        assertEquals("Unchanged", rows.getValue("usbBaudRate").str("action"))
        assertEquals("notASetting", result.obj.list("skipped").single().str("label"))
        val stored = assertNotNull(fixture.machines.loadProfile(fixture.mill.id))
        assertEquals(false, stored.metricDefault)
        assertEquals(false, stored.use3dSubDatums)
        assertEquals(fixture.mill.usbBaudRate, stored.usbBaudRate)
        assertEquals("Put profile settings", fixture.gate.requests.single().toolTitle)
        assertEquals(listOf("metricDefault", "use3dSubDatums", "Client 192.168.1.9"), fixture.gate.requests.single().details)
        fixture.close()
    }

    @Test
    fun putProfileSettingsPreviewWritesNothing() = runTest {
        val fixture = PopulateFixture(this).start()
        val result = fixture.ok("put_profile_settings", args { put("machine", "Mill"); put("settings", settings("metricDefault" to false)); put("preview", true) })
        assertTrue(result.text.startsWith("Preview: 1 setting changed"), result.text)
        assertEquals(true, fixture.machines.loadProfile(fixture.mill.id)!!.metricDefault)
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    @Test
    fun putProfileSettingsIsRefusedOnlineOrWithAMachineTypeChange() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.online += fixture.mill.id
        val online = fixture.error("put_profile_settings", args { put("machine", "Mill"); put("settings", settings("metricDefault" to false)) })
        assertTrue(online.message.startsWith("The machine \"Mill\" is online. Disconnect it"), online.message)
        fixture.online.clear()
        val type = fixture.error("put_profile_settings", args { put("machine", "Mill"); put("settings", settings("machineType" to "Lathe")) })
        assertEquals("The settings change the machine type from VERTICAL_MILL to LATHE. Change the machine type in the app.", type.message)
        val none = fixture.error("put_profile_settings", args { put("machine", "Mill"); put("settings", settings("nope" to 1)) })
        assertEquals("The file has no setting that the import can use.", none.message)
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    @Test
    fun putProfileSettingsStopsOnARefusedGate() = runTest {
        val fixture = PopulateFixture(this, RecordingGate(GateDecision.Refused)).start()
        val result = fixture.error("put_profile_settings", args { put("machine", "Mill"); put("settings", settings("metricDefault" to false)) })
        assertEquals(GateTexts.REFUSED, result.message)
        assertEquals(true, fixture.machines.loadProfile(fixture.mill.id)!!.metricDefault)
        fixture.close()
    }

    // ---------------------------------------------------------------------------------------
    // create_profile

    @Test
    fun createProfileMakesAProfileWithTheTypeAndTheSettings() = runTest {
        val fixture = PopulateFixture(this).start()
        val plain = fixture.ok("create_profile", args { put("name", "Router"); put("machineType", "HORIZONTAL_MILL") })
        assertTrue(plain.text.startsWith("Profile \"Router\" created with ID "))
        val router = assertNotNull(fixture.machines.loadFullProfile(plain.obj.str("id")))
        assertEquals(MachineType.HorizontalMill, router.machineType)
        assertEquals(1, router.workspaces.size)
        val withSettings = fixture.ok("create_profile", args { put("name", "Lathe 2"); put("machineType", "LATHE"); put("settings", settings("metricDefault" to true, "bad" to 1)) })
        val lathe = assertNotNull(fixture.machines.loadProfile(withSettings.obj.str("id")))
        assertEquals(MachineType.Lathe, lathe.machineType)
        assertEquals(true, lathe.metricDefault)
        assertEquals("LATHE", withSettings.obj.str("machineType"))
        assertTrue(withSettings.obj.list("warnings").any { warning -> warning.str("message").contains("“bad”") })
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    @Test
    fun createProfileRefusesATakenNameAndABadType() = runTest {
        val fixture = PopulateFixture(this).start()
        assertEquals("A profile with this name already exists.", fixture.error("create_profile", args { put("name", "mill"); put("machineType", "LATHE") }).message)
        assertEquals(
            "The machine type must be one of VERTICAL_MILL, LATHE, HORIZONTAL_MILL, not \"Router\".",
            fixture.error("create_profile", args { put("name", "X"); put("machineType", "Router") }).message,
        )
        assertEquals(2, fixture.machines.profiles().first().size)
        fixture.close()
    }

    // ---------------------------------------------------------------------------------------
    // export_profile_bundle and import_profile_bundle

    private suspend fun PopulateFixture.fillMill() {
        val workspace = millWorkspace()
        ok("put_sub_datums", args { put("machine", "Mill"); put("workspace", workspace.id); put("points", jsonList(buildJsonObject { put("name", "Hole 1"); put("x", 10.0); put("y", 20.0) })) })
        ok("put_guides", args { put("machine", "Mill"); put("workspace", workspace.id); put("guides", jsonList(buildJsonObject { put("axis", "X"); put("value", 5.0) })) })
        ok("set_drawing", args { put("machine", "Mill"); put("workspace", workspace.id); put("document", LINE_AND_CIRCLE) })
        val fileId = upload("image/png", PNG_32_16)
        ok("set_reference_image", args { put("machine", "Mill"); put("workspace", workspace.id); put("fileId", fileId); put("matrix", jsonList(1000, 0, 0, 0, 1000, 0, 0, 0, 1)) })
        ok("put_tools", args { put("machine", "Mill"); put("tools", jsonList(buildJsonObject { put("name", "End mill 10"); put("diameter", 10.0); put("toothCount", 4) })) })
    }

    @Test
    fun exportProfileBundleWritesTheBundle() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.fillMill()
        val result = fixture.ok("export_profile_bundle", args { put("machine", "Mill") })
        val fileId = result.obj.str("fileId")
        assertEquals("Bundle of \"Mill\" with 1 workspace and 1 tool at /download/$fileId", result.text)
        val file = assertNotNull(fixture.files.download(fileId))
        assertEquals("application/json", file.contentType)
        val bundle = kotlinx.serialization.json.Json.parseToJsonElement(file.bytes.decodeToString()).jsonObject
        assertEquals("bundle", bundle.str("superdro"))
        assertEquals(1, bundle.int("format"))
        assertEquals(fixture.mill.id, bundle["profile"]!!.str("id"))
        assertEquals("VERTICAL_MILL", bundle["profile"]!!.str("machineType"))
        assertEquals(ProfileFile.settingsOf(fixture.machines.loadProfile(fixture.mill.id)!!), bundle["profile"]!!.jsonObject["settings"])
        assertEquals("End mill 10", bundle.list("tools").single().str("name"))
        val workspace = bundle.list("workspaces").single()
        assertEquals("Hole 1", workspace.list("subDatums").single().str("name"))
        assertEquals(1, workspace.list("guides").size)
        assertEquals("touchdro.wireframe", workspace.jsonObject["drawing"]!!.str("schema"))
        assertEquals("image/png", workspace.jsonObject["referenceImage"]!!.str("mimeType"))
        assertEquals(kotlin.io.encoding.Base64.encode(PNG_32_16), workspace.jsonObject["referenceImage"]!!.str("base64"))
        assertTrue(fixture.files.downloadNames.getValue(fileId).startsWith("superdro-bundle-Mill-"))
        fixture.close()
    }

    @Test
    fun importProfileBundleMakesANewProfileWithFreshIdsWhenTheProfileExists() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.fillMill()
        val exported = fixture.ok("export_profile_bundle", args { put("machine", "Mill") })
        val bytes = fixture.files.download(exported.obj.str("fileId"))!!.bytes
        val fileId = fixture.upload("application/json", bytes)
        val preview = fixture.ok("import_profile_bundle", args { put("fileId", fileId); put("name", "Mill copy"); put("preview", true) })
        assertEquals("Preview: new profile \"Mill copy\" with 1 workspace and 1 tool", preview.text)
        assertEquals("Add", preview.obj["profile"]!!.str("action"))
        assertEquals(false, preview.obj["profile"]!!.bool("keepIds"))
        assertEquals(2, fixture.machines.profiles().first().size)
        val result = fixture.ok("import_profile_bundle", args { put("fileId", fileId); put("name", "Mill copy") })
        assertTrue(result.text.startsWith("Imported new profile \"Mill copy\""), result.text)
        val copy = assertNotNull(fixture.machines.loadFullProfile(result.obj.str("id")))
        assertNotEquals(fixture.mill.id, copy.id)
        assertEquals("Mill copy", copy.name)
        val workspace = copy.workspaces.single()
        assertNotEquals(fixture.millWorkspace().id, workspace.id)
        assertEquals("Hole 1", workspace.subDatums.single().name)
        assertEquals(10_000.0, workspace.subDatums.single().x)
        assertEquals(1, workspace.guides.size)
        val image = assertNotNull(workspace.referenceImage)
        assertNotEquals(fixture.millWorkspace().referenceImage!!.fileName, image.fileName)
        assertTrue(fixture.storeIO.files.getValue(image.fileName).contentEquals(PNG_32_16))
        assertEquals(2, fixture.drawings.loadDrawing(workspace.id)!!.shapes.size)
        val tools = fixture.tools.loadLibrary(copy.id)
        assertEquals("End mill 10", tools.single().name)
        assertNotEquals(fixture.tools.loadLibrary(fixture.mill.id).single().id, tools.single().id)
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    @Test
    fun importProfileBundleKeepsTheIdsOnANewDeviceAndUpdatesTheNamedProfile() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.fillMill()
        val bytes = fixture.files.download(fixture.ok("export_profile_bundle", args { put("machine", "Mill") }).obj.str("fileId"))!!.bytes
        val original = fixture.machines.loadFullProfile(fixture.mill.id)!!
        fixture.machines.deleteProfile(fixture.mill.id, deleteTools = true)
        assertNull(fixture.machines.loadProfile(fixture.mill.id))

        val fileId = fixture.upload("application/json", bytes)
        val fresh = fixture.ok("import_profile_bundle", args { put("fileId", fileId) })
        assertEquals(fixture.mill.id, fresh.obj.str("id"))
        val restored = fixture.machines.loadFullProfile(fixture.mill.id)!!
        assertEquals(original.workspaces.single().id, restored.workspaces.single().id)
        assertEquals(original.workspaces.single().subDatums.single().id, restored.workspaces.single().subDatums.single().id)
        assertEquals(original.tools.single().id, fixture.tools.loadLibrary(fixture.mill.id).single().id)
        assertEquals(emptyList(), fixture.gate.requests)

        // A change on the device, then the same bundle with the name of the profile: an update.
        fixture.ok("put_sub_datums", args { put("machine", "Mill"); put("workspace", restored.workspaces.single().id); put("points", jsonList(buildJsonObject { put("name", "Extra"); put("x", 1.0) })) })
        fixture.ok("create_workspace", args { put("machine", "Mill"); put("name", "Vise") })
        fixture.gate.decision = GateDecision.Refused
        assertEquals(GateTexts.REFUSED, fixture.error("import_profile_bundle", args { put("fileId", fileId); put("name", "Mill") }).message)
        fixture.gate.decision = GateDecision.Allowed
        val preview = fixture.ok("import_profile_bundle", args { put("fileId", fileId); put("name", "Mill"); put("preview", true) })
        assertEquals("Update", preview.obj["profile"]!!.str("action"))
        assertEquals("Update", preview.obj.list("workspaces").single().str("action"))
        val update = fixture.ok("import_profile_bundle", args { put("fileId", fileId); put("name", "mill") })
        assertTrue(update.text.startsWith("Imported update profile \"mill\""), update.text)
        val updated = fixture.machines.loadFullProfile(fixture.mill.id)!!
        assertEquals("mill", updated.name)
        assertEquals(2, updated.workspaces.size)
        val workspace = updated.workspaces.first { it.id == original.workspaces.single().id }
        assertEquals(listOf("Hole 1"), workspace.subDatums.map { point -> point.name })
        assertEquals(2, fixture.gate.requests.size)
        fixture.close()
    }

    @Test
    fun importProfileBundleRefusesABadFile() = runTest {
        val fixture = PopulateFixture(this).start()
        val text = fixture.upload("text/plain", "hello".encodeToByteArray())
        assertEquals("The upload is not a JSON object.", fixture.error("import_profile_bundle", args { put("fileId", text) }).message)
        val profileFile = fixture.upload("application/json", """{"superdro":"profile","format":1,"settings":{}}""".encodeToByteArray())
        assertEquals("The file contains \"profile\". This import needs \"bundle\".", fixture.error("import_profile_bundle", args { put("fileId", profileFile) }).message)
        val noName = fixture.upload("application/json", """{"superdro":"bundle","format":1,"profile":{"settings":{"metricDefault":true}}}""".encodeToByteArray())
        assertEquals("Give a name: the bundle has none.", fixture.error("import_profile_bundle", args { put("fileId", noName) }).message)
        val named = fixture.ok("import_profile_bundle", args { put("fileId", noName); put("name", "Bare") })
        assertEquals(1, fixture.machines.loadFullProfile(named.obj.str("id"))!!.workspaces.size)
        fixture.close()
    }
}
