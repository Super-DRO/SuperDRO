package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AxisId
import org.superdro.core.model.CameraSelection
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeRoles
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.SubDatum
import org.superdro.core.model.ThreadSpec
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.model.Workspace
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreResult
import org.superdro.core.store.refusalOrNull
import org.superdro.core.store.valueOrNull

class MachineProfileRepositoryTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    private suspend fun create(profile: MachineProfile): MachineProfile =
        assertIs<StoreResult.Ok<MachineProfile>>(store.machines.createProfile(profile)).value

    @Test
    fun theStoreKeepsTheUsbLinkAtTheRateOfTheProfileSetting() = scope.runTest {
        val usb = AdapterAddress.UsbSerial(0x10C4, 0xEA60, "A1", baudRate = 115_200)
        val created = create(MachineProfile.create(MachineType.Lathe, "South Bend").copy(usbBaudRate = 9_600, adapter = usb))
        assertEquals(usb.copy(baudRate = 9_600), created.adapter)
        // A new USB link of the profile (for example from the add-device sheet) gets the rate of the setting.
        val relinked = assertIs<StoreResult.Ok<MachineProfile>>(
            store.machines.updateProfile(created.id) { profile -> profile.copy(adapter = AdapterAddress.UsbSerial(0x1A86, 0x7523, "B2")) },
        ).value
        assertEquals(9_600, (relinked.adapter as AdapterAddress.UsbSerial).baudRate)
        assertEquals(9_600, store.machines.loadProfile(created.id)!!.usbBaudRate)

        // A row of an older version with two different rates: the read gives the rate of the setting.
        val old = store.json.encodeToString(
            MachineProfile.serializer(),
            MachineProfile.create(MachineType.VerticalMill, "Old").copy(usbBaudRate = 38_400, adapter = usb),
        )
        val row = store.database.machineProfileQueries.selectById(created.id).executeAsOne()
        store.database.machineProfileQueries.update(
            name = row.name, machineType = row.machine_type, metricDefault = row.metric_default, useSharedTools = row.use_shared_tools,
            adapterProfileId = row.adapter_profile_id, activeWorkspaceId = row.active_workspace_id, configJson = old,
            updatedOn = row.updated_on, id = row.id,
        )
        val loaded = store.machines.loadProfile(created.id)!!
        assertEquals(38_400, loaded.usbBaudRate)
        assertEquals(38_400, (loaded.adapter as AdapterAddress.UsbSerial).baudRate)
    }

    @Test
    fun aNewProfileStoresItsSettingsWorkspacesToolsAndDatums() = scope.runTest {
        val workspace = Workspace.forMachine(MachineType.VerticalMill).copy(
            subDatums = listOf(SubDatum(name = "Hole", tags = listOf("left", "deep"), x = 25400.0, y = 1000.0)),
            guides = listOf(Guide(axis = AxisId.Y, valueUm = 30000.0)),
        )
        val profile = MachineProfile.create(MachineType.VerticalMill, "Bridgeport").copy(
            metricDefault = true,
            adapter = AdapterAddress.Tcp(host = "192.168.4.1"),
            workspaces = listOf(workspace),
            activeWorkspaceId = workspace.id,
            tools = listOf(Tool(name = "6 mm end mill", metric = true, diameterUm = 6000.0, toothCount = 4)),
            machineDatums = listOf(MachineDatum(name = "Vise", x = 152400.0, y = 76200.0, z = -12700.0)),
        )
        create(profile)

        val full = store.machines.loadFullProfile(profile.id)!!
        assertEquals("Bridgeport", full.name)
        assertTrue(full.metricDefault)
        assertEquals(AdapterAddress.Tcp(host = "192.168.4.1", port = 8080), full.adapter)
        assertEquals(workspace.id, full.activeWorkspaceId)
        assertEquals(listOf("Default Workspace"), full.workspaces.map { it.name })
        assertEquals(listOf("left", "deep"), full.workspaces.single().subDatums.single().tags)
        assertEquals(30000.0, full.workspaces.single().guides.single().valueUm)
        assertEquals(listOf("6 mm end mill"), full.tools.map { it.name })
        assertEquals(-12700.0, full.machineDatums.single().z)

        val settings = store.machines.loadProfile(profile.id)!!
        assertEquals(emptyList(), settings.workspaces)
        assertEquals(emptyList(), settings.tools)
        assertEquals(profile.copy(workspaces = emptyList(), tools = emptyList(), machineDatums = emptyList()), settings)
    }

    @Test
    fun aProfileWithNoWorkspaceGetsTheDefaultWorkspace() = scope.runTest {
        val profile = create(MachineProfile.create(MachineType.Lathe).copy(workspaces = emptyList()))
        val workspaces = store.machines.loadFullProfile(profile.id)!!.workspaces
        assertEquals(listOf("Default Workspace"), workspaces.map { it.name })
        assertEquals(workspaces.single().id, store.machines.loadProfile(profile.id)!!.activeWorkspaceId)
    }

    @Test
    fun profileNamesAreUnique() = scope.runTest {
        create(MachineProfile.create(MachineType.VerticalMill, "Mill"))
        val duplicate = store.machines.createProfile(MachineProfile.create(MachineType.Lathe, "Mill"))
        assertEquals(StoreRefusal.ProfileNameExists, duplicate.refusalOrNull())
        assertEquals("A profile with this name already exists.", duplicate.refusalOrNull()!!.message)
        // The compare ignores the letter case and the spaces at the ends.
        assertEquals(StoreRefusal.ProfileNameExists, store.machines.createProfile(MachineProfile.create(name = "  mill ")).refusalOrNull())
        assertEquals(StoreRefusal.ProfileNameBlank, store.machines.createProfile(MachineProfile.create(name = "   ")).refusalOrNull())
        assertEquals("Profile name can't be blank.", StoreRefusal.ProfileNameBlank.message)
        assertEquals(1L, store.count("machine_profile"))
    }

    @Test
    fun aRenameKeepsTheNamesUnique() = scope.runTest {
        val mill = create(MachineProfile.create(MachineType.VerticalMill, "Mill"))
        create(MachineProfile.create(MachineType.Lathe, "Lathe"))
        assertEquals(StoreRefusal.ProfileNameExists, store.machines.renameProfile(mill.id, "LATHE").refusalOrNull())
        assertEquals("Bridgeport", store.machines.renameProfile(mill.id, " Bridgeport ").valueOrNull()!!.name)
        // A new letter case of the same profile is not a duplicate.
        assertEquals("BRIDGEPORT", store.machines.renameProfile(mill.id, "BRIDGEPORT").valueOrNull()!!.name)
        assertEquals(StoreRefusal.NotFound, store.machines.renameProfile("missing", "X").refusalOrNull())
    }

    @Test
    fun aCopyHasTheSettingsAndToolsButNotTheWorkspaces() = scope.runTest {
        val workspace = Workspace.forMachine(MachineType.VerticalMill, "Second op").copy(
            subDatums = listOf(SubDatum(name = "P1", x = 1.0)),
        )
        val source = create(
            MachineProfile.create(MachineType.VerticalMill, "Mill").copy(
                metricDefault = true,
                adapter = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"),
                workspaces = listOf(workspace),
                activeWorkspaceId = workspace.id,
                tools = listOf(Tool(name = "Drill", diameterUm = 5000.0)),
                machineDatums = listOf(MachineDatum(name = "Home", x = 0.0)),
            )
        )
        val ownProbe = ProbeProfile(profileId = source.id, name = "Probe")
        store.probes.saveProbeProfile(ownProbe)
        store.machines.updateProfile(source.id) { it.copy(probeRoles = ProbeRoles(edgeFinderProfileId = ownProbe.id, zReferenceProfileId = "6f3d2a10-5c1e-4b7a-9d01-000000000004")) }

        val copy = store.machines.copyProfile(source.id, "Mill copy").valueOrNull()!!
        assertNotEquals(source.id, copy.id)
        assertEquals("Mill copy", copy.name)
        assertTrue(copy.metricDefault)
        assertNull(copy.adapter)
        assertNull(copy.probeRoles.edgeFinderProfileId)
        assertEquals("6f3d2a10-5c1e-4b7a-9d01-000000000004", copy.probeRoles.zReferenceProfileId)

        val full = store.machines.loadFullProfile(copy.id)!!
        assertEquals(listOf("Default Workspace"), full.workspaces.map { it.name })
        assertEquals(emptyList(), full.workspaces.single().subDatums)
        assertEquals(listOf("Drill"), full.tools.map { it.name })
        assertNotEquals(store.machines.loadFullProfile(source.id)!!.tools.single().id, full.tools.single().id)
        assertEquals(emptyList(), full.machineDatums)
        assertEquals(StoreRefusal.ProfileNameExists, store.machines.copyProfile(source.id, "mill copy").refusalOrNull())
    }

    @Test
    fun theSettingsAreTypedJson() = scope.runTest {
        val profile = MachineProfile.create(MachineType.VerticalMill).let { profile ->
            // A text setting with the value "true" stays a text. A label with ':' keeps all its text.
            profile.copy(x = profile.x.copy(label = "true"), tachometer = profile.tachometer.copy(label = "RPM:1"))
        }
        create(profile)
        val stored = store.machines.loadProfile(profile.id)!!
        assertEquals("true", stored.x.label)
        assertEquals("RPM:1", stored.tachometer.label)
        assertEquals(profile.dataOutput, stored.dataOutput)
    }

    @Test
    fun anUpdateWritesTheSettingsButNotTheMachineType() = scope.runTest {
        val profile = create(MachineProfile.create(MachineType.VerticalMill))
        val updated = store.machines.updateProfile(profile.id) { it.copy(machineType = MachineType.Lathe, connectAtStart = true, metricDefault = true) }
        val value = updated.valueOrNull()!!
        assertEquals(MachineType.VerticalMill, value.machineType)
        assertTrue(value.connectAtStart)
        assertTrue(value.metricDefault)
        assertEquals(clock(), value.updatedOn)
    }

    @Test
    fun aProfileDeleteRemovesAllItsData() = scope.runTest {
        val workspace = Workspace.forMachine(MachineType.VerticalMill).copy(
            subDatums = listOf(SubDatum(name = "A", tags = listOf("t"), x = 1.0)),
            guides = listOf(Guide(axis = AxisId.X, valueUm = 2.0)),
            referenceImage = ReferenceImage(fileName = "images/one.jpg"),
        )
        val profile = create(
            MachineProfile.create(MachineType.VerticalMill).copy(
                workspaces = listOf(workspace),
                activeWorkspaceId = workspace.id,
                tools = listOf(Tool(name = "T1")),
                machineDatums = listOf(MachineDatum(name = "D")),
            )
        )
        store.storeIO.putText("images/one.jpg", "jpeg")
        store.probes.saveProbeProfile(ProbeProfile(profileId = profile.id, name = "Own probe"))
        store.cameras.saveSelection(CameraSelection(profileId = profile.id, cameraKey = "046d:0825", modeKey = "mjpeg-640x480"))
        store.drawings.replaceDrawing(Drawing(workspaceId = workspace.id, shapes = listOf(DrawingShape(x1 = 0, y1 = 0, x2 = 10, y2 = 10))))
        store.logs.append(workspace.id, MeasurementLogEntry(verb = "point"))
        store.machines.saveThread(workspace.id, ThreadSpec(size = "M10 × 1.5"))

        assertTrue(store.machines.deleteProfile(profile.id, deleteTools = true))

        for (table in listOf(
            "machine_profile", "workspace", "axis_state", "sub_datum", "sub_datum_tag", "guide", "machine_datum",
            "thread_spec", "tool", "camera_selection", "drawing", "drawing_layer", "drawing_shape", "measurement_log",
        )) {
            assertEquals(0L, store.count(table), table)
        }
        // The probe profiles for all machine profiles (the six standard tips) stay.
        assertEquals(6L, store.count("probe_profile"))
        assertFalse(store.storeIO.exists("images/one.jpg"))
        assertFalse(store.machines.deleteProfile(profile.id, deleteTools = true))
    }

    @Test
    fun aProfileDeleteCanKeepTheToolsInTheSharedLibrary() = scope.runTest {
        val profile = create(MachineProfile.create(MachineType.VerticalMill).copy(tools = listOf(Tool(name = "Kept"))))
        assertEquals("Delete the tools of this profile too?", org.superdro.core.store.DELETE_PROFILE_TOOLS_QUESTION)
        store.machines.deleteProfile(profile.id, deleteTools = false)
        val shared = store.database.toolQueries.selectShared(ToolKind.Mill).executeAsList()
        assertEquals(listOf("Kept"), shared.map { it.name })
        assertNull(shared.single().profile_id)
    }

    @Test
    fun anUnknownAdapterProfileIdBecomesNull() = scope.runTest {
        val profile = create(MachineProfile.create().copy(adapterProfileId = "not-stored"))
        assertNull(store.machines.loadProfile(profile.id)!!.adapterProfileId)
    }

    private fun clock() = store.clock.now()
}
