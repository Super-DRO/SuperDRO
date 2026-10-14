package org.superdro.platform.android.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Tool
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.refusalOrNull
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.WorkspaceTransformResult
import org.superdro.core.store.valueOrNull

/**
 * [MachineRepository] on the device with the bundled SQLite and a database file.
 * The queries, the cascades, the triggers of
 * the undo copy, and a restart run on API 24.
 */
@RunWith(AndroidJUnit4::class)
class MachineRepositoryDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "repository-test.db"
    private val storeIO = StoreIOProviderAndroid(context)

    @Before
    fun deleteOldFile() {
        context.deleteDatabase(name)
    }

    @After
    fun deleteFile() {
        context.deleteDatabase(name)
    }

    private fun <T> withRepository(block: suspend (MachineRepository) -> T): T {
        val driver = AndroidSqlDriverFactory(context, name = name, checkDispatcher = Dispatchers.Unconfined).create()
        return try {
            runBlocking { block(MachineRepository(StoreWriter(createSuperDroDatabase(driver)), storeIO)) }
        } finally {
            driver.close()
        }
    }

    @Test
    fun theRepositoryRulesWorkOnTheDevice() {
        val profileId = withRepository { machines ->
            val profile = machines.createProfile(MachineProfile.create(name = "Mill").copy(tools = listOf(Tool(name = "T")))).valueOrNull()!!
            assertEquals(StoreRefusal.ProfileNameExists, machines.createProfile(MachineProfile.create(name = "mill")).refusalOrNull())
            val workspaceId = profile.activeWorkspaceId
            assertEquals("Point 1", machines.addSubDatum(workspaceId, SubDatum(x = 1.0, y = 2.0, tags = listOf("a", "b"))).valueOrNull()!!.name)
            machines.setAxisState(workspaceId, AxisId.X, AxisState(absoluteOffset = -25400.0))
            assertEquals(StoreRefusal.LastWorkspace, machines.deleteWorkspace(workspaceId).refusalOrNull())

            val point = machines.loadWorkspace(workspaceId)!!.subDatums.single()
            machines.applyTransform(workspaceId, WorkspaceTransformResult("Flip", listOf(point.copy(y = -2.0)), emptyList()))
            assertEquals(-2.0, machines.loadWorkspace(workspaceId)!!.subDatums.single().y)
            machines.undoLastTransform(workspaceId)
            assertEquals(2.0, machines.loadWorkspace(workspaceId)!!.subDatums.single().y)
            profile.id
        }
        // A new driver on the same file: the offsets stay after a restart.
        withRepository { machines ->
            val workspace = machines.loadFullProfile(profileId)!!.workspaces.single()
            assertEquals(-25400.0, workspace.axisStates.getValue(AxisId.X).absoluteOffset)
            assertEquals(listOf("a", "b"), workspace.subDatums.single().tags)

            storeIO.sinkFor("images/device-test.jpg").close()
            machines.setReferenceImage(workspace.id, ReferenceImage(fileName = "images/device-test.jpg"))
            assertEquals(true, machines.deleteProfile(profileId, deleteTools = true))
            assertFalse(storeIO.exists("images/device-test.jpg"))
            assertEquals(null, machines.loadProfile(profileId))
        }
    }
}
